package io.healthassistant.android.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.healthassistant.android.alerts.AlertEngineHost
import io.healthassistant.android.data.CredentialStore
import io.healthassistant.android.data.SqliteOutboxStore
import io.healthassistant.android.monitoring.SyncMonitorRepository
import io.healthassistant.android.monitoring.SyncStatus
import io.healthassistant.android.settings.SyncHistoryWindow
import io.healthassistant.android.settings.SyncSettings
import io.healthassistant.android.settings.SyncSettingsRepository
import io.healthassistant.android.source.SourceRegistry
import io.healthassistant.bridge.BridgeClient
import io.healthassistant.shared.healthconnect.HcType
import io.healthassistant.shared.source.HealthDataSource
import io.healthassistant.shared.sync.BridgeSyncSender
import io.healthassistant.shared.sync.HealthSyncPipeline
import io.healthassistant.shared.sync.OutboxItem
import io.healthassistant.shared.sync.OutboxStore
import io.healthassistant.shared.sync.SyncCoordinator
import io.healthassistant.shared.sync.SyncItems
import kotlinx.coroutines.flow.first
import org.koin.core.context.GlobalContext

/**
 * Background drain of the outbox + Health Connect source read (Phases D/E) +
 * the Phase G efficiency + progress work. One `doWork()` invocation now:
 *
 * 1. Reads every sync-enabled source (cursors default to the user's chosen
 *    history window ago
 *    on the first sync so a fresh install never floods the outbox with the
 *    device's entire history), enqueuing one `/sync` item per record.
 * 2. **Drains in a loop** — claim → send batches of 200 until the outbox is
 *    empty or [DRAIN_BUDGET_MS] elapses, then chains another one-time run if
 *    items remain. This is why a 70k-item backlog clears in minutes, not days.
 * 3. Reports **live progress** (processed/total + per-biomarker synced/failed)
 *    to the shared [SyncMonitorRepository] after every batch, so the Monitoring
 *    dashboard's progress bar updates in real time.
 *
 * The worker always returns success — the periodic schedule + per-item
 * `nextAttemptAt` + the self-reschedule own cadence; failures surface as a
 * `SyncStatus.Error` on the monitor, not as WorkManager retries.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val credential =
            CredentialStore(applicationContext).load()
                ?: return Result.success() // no connection → nothing to sync

        val koin = GlobalContext.getOrNull()
        val outbox: OutboxStore = koin?.get() ?: SqliteOutboxStore(applicationContext)
        val settingsRepo = koin?.get<SyncSettingsRepository>()
        val monitorRepo = koin?.get<SyncMonitorRepository>()
        val registry = koin?.get<SourceRegistry>()
        // M5 — one-shot alert engine for this run's connection; best-effort,
        // reuses the onSyncedItems hook so freshly pushed pages fire alerts
        // in the background too.
        val alertEngine = koin?.get<AlertEngineHost>()?.workerEngine(credential.integrationId)

        val client =
            BridgeClient(
                baseUrl = credential.baseUrl,
                integrationId = credential.integrationId,
                apiSecret = credential.apiSecret,
            )
        val pipeline = HealthSyncPipeline(outbox)

        val settings = settingsRepo?.current()
        val sources = registry?.syncEnabled(settings ?: SyncSettings()).orEmpty()
        val nowMs = System.currentTimeMillis()
        // How far back the first sync reads (user-selectable; default 7 days).
        // The walking-window read still bounds each pass to 1 day regardless.
        val backfillFloor = (settings?.historyWindow ?: SyncHistoryWindow.LAST_7_DAYS).floorEpochMs(nowMs)

        // Per-type tally + live progress, updated each batch.
        val perTypeSynced = mutableMapOf<HcType, Int>()
        val perTypeFailed = mutableMapOf<HcType, Int>()
        val coordinator =
            SyncCoordinator(
                outbox,
                BridgeSyncSender(client),
                SyncCoordinator.Config(
                    onSyncedItems = { items ->
                        items.forEach { tally(it, perTypeSynced) }
                        runCatching { alertEngine?.onSyncedItems(items) }
                    },
                    onDeadItems = { items -> items.forEach { tally(it, perTypeFailed) } },
                ),
            )

        // 1. Read FIRST when the backlog is small — so the just-read items are
        //    drained in the SAME pass and the progress card reflects them (drain-
        //    then-read left the card stuck at "0 of 0" because the drain ran on
        //    an empty outbox before the read enqueued anything). Walk 1-day
        //    reading windows within this run until one yields data or the cursor
        //    catches up. HC reads are local, so walking empty days is fast.
        var readMore = false
        val initialPending = outbox.pendingCount()
        monitorRepo?.recordSyncResult(SOURCE_HC, emptyMap(), SyncStatus.Reading)
        if (initialPending <= READ_RESUME_THRESHOLD) {
            val readDeadline = System.currentTimeMillis() + READ_BUDGET_MS
            for (source in sources) {
                val types = typesFor(source, settings)
                if (types.isEmpty()) continue
                try {
                    var caughtUp = false
                    while (!caughtUp && System.currentTimeMillis() < readDeadline) {
                        // Default ABSENT cursors to the backfill floor (not epoch) — otherwise
                        // the first sync / a reset scans from 1970 day-by-day and never reaches
                        // today's data within the read budget.
                        val stored = settingsRepo?.cursors?.first() ?: emptyMap()
                        val cursors =
                            types.associateWith { type ->
                                val v = stored[type]
                                if (v == null || v <= 0L) backfillFloor else v
                            }
                        val windowStart = cursors.values.minOrNull() ?: backfillFloor
                        val result = pipeline.readAndEnqueue(source, types, cursors)
                        Log.i(TAG, "read ${source.id}: window from ${fmt(windowStart)} enqueued=${result.enqueued}")
                        monitorRepo?.reportReadingWindow("${fmt(windowStart)} → ${fmt(nowMs)}")
                        if (result.newCursors.isNotEmpty() && settingsRepo != null) {
                            settingsRepo.setCursors(result.newCursors)
                        }
                        if (result.latestByType.isNotEmpty() && monitorRepo != null) {
                            monitorRepo.reportLatestReadings(result.latestByType)
                        }
                        caughtUp = result.newCursors.all { it.value >= nowMs }
                        if (result.enqueued > 0) break // found data → drain it this run
                    }
                    if (!caughtUp) readMore = true
                } catch (e: Exception) {
                    Log.w(TAG, "source ${source.id} read failed: ${e.javaClass.simpleName}: ${e.message}", e)
                    monitorRepo?.recordSyncResult(source.id, emptyMap(), SyncStatus.Error(e.message ?: e.javaClass.simpleName))
                }
            }
        } else {
            readMore = true // backlog still large — skip the read, just drain.
        }

        // 2. Drain the outbox (the backlog + anything the read just enqueued).
        val total = outbox.pendingCount()
        monitorRepo?.beginProgress(total)
        monitorRepo?.recordSyncResult(SOURCE_HC, emptyMap(), SyncStatus.Syncing)

        var synced = 0
        var deadLettered = 0
        var rateLimitedUntil: Long? = null
        val deadline = System.currentTimeMillis() + DRAIN_BUDGET_MS
        try {
            do {
                val drain = coordinator.drain()
                synced += drain.synced
                deadLettered += drain.deadLettered
                drain.rateLimitedUntil?.let { rateLimitedUntil = it }
                val remaining = outbox.pendingCount()
                monitorRepo?.reportProgress(
                    processed = synced + deadLettered,
                    total = total,
                    remaining = remaining,
                    perTypeSynced = perTypeSynced.toMap(),
                    perTypeFailed = perTypeFailed.toMap(),
                )
                // Rate-limited (429): further requests this window burn quota —
                // stop draining and chain a delayed continuation instead.
                if (rateLimitedUntil != null) break
                if (remaining == 0) break
                if (System.currentTimeMillis() >= deadline) break
            } while (true)
        } finally {
            Log.i(TAG, "drain pass: synced=$synced dead=$deadLettered remaining=${outbox.pendingCount()}")
        }

        // 3. Report outcome.
        val status =
            if (deadLettered > 0 && synced == 0) SyncStatus.Error("$deadLettered dead-lettered") else SyncStatus.Success(synced)
        sources.forEach { source -> monitorRepo?.recordSyncResult(source.id, perTypeSynced, status) }
        monitorRepo?.endProgress(perTypeSynced.toMap(), perTypeFailed.toMap())
        monitorRepo?.reportReadingWindow(null)
        monitorRepo?.refresh()

        if (deadLettered > 0) {
            SyncNotifications.postDeadLetters(applicationContext, deadLettered)
        } else if (synced > 0) {
            SyncNotifications.postSyncSuccess(applicationContext, synced)
        }

        // 4. Reschedule while there's a backlog OR more history to backfill.
        //    APPEND_OR_REPLACE chains as a continuation — never cancels this pass.
        //    A 429 cooldown defers the chained pass past the rate-limit window.
        if (outbox.pendingCount() > 0 || readMore) {
            val delayMs = rateLimitedUntil?.let { (it - System.currentTimeMillis()).coerceAtLeast(0L) } ?: 0L
            SyncScheduler.syncContinue(applicationContext, delayMs)
        } else {
            monitorRepo?.clearProgress()
        }
        return Result.success()
    }

    /** Decode the item's `/sync` payload → record code → [HcType], tallying into [acc]. */
    private fun tally(
        item: OutboxItem,
        acc: MutableMap<HcType, Int>,
    ) {
        val type = SyncItems.recordCode(item)?.let(HcType::byCode) ?: return
        acc[type] = (acc[type] ?: 0) + 1
    }

    private fun typesFor(
        source: HealthDataSource,
        settings: SyncSettings?,
    ): Set<HcType> =
        if (source.id == "manual") {
            source.availableTypes.toSet()
        } else {
            settings?.enabledTypes ?: emptySet()
        }

    private fun fmt(epochMs: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(epochMs))

    private companion object {
        const val TAG = "HASyncWorker"
        const val SOURCE_HC = "health_connect"
        const val DRAIN_BUDGET_MS = 300_000L // 5 min — one pass drains heavily before chaining
        const val READ_BUDGET_MS = 15_000L // walk empty read windows for up to 15s within one pass
        const val READ_RESUME_THRESHOLD = 1_000 // don't read more while the backlog exceeds this
    }
}
