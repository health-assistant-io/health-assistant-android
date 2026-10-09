package io.healthassistant.android.monitoring

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.healthassistant.shared.healthconnect.HcType
import io.healthassistant.shared.healthconnect.RawSample
import io.healthassistant.shared.sync.OutboxLane
import io.healthassistant.shared.sync.OutboxStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

private val Context.syncMonitorDataStore: DataStore<Preferences> by preferencesDataStore(name = "ha_sync_monitor")

/**
 * Phase C gate: the observable monitoring state (plan §2.5). Exposes a
 * `StateFlow<SyncMonitor>` that the Compose Monitoring screen collects. Reads
 * the live outbox (pending + dead letters) via [refresh] and tracks cumulative
 * per-type synced counts + last-pass status across restarts in DataStore.
 *
 * The enhanced [io.healthassistant.android.work.SyncWorker] (Phase D) calls
 * [recordSyncResult] after each drain; the Monitoring screen's "Sync now" calls
 * [refresh] for an up-to-the-moment outbox snapshot.
 *
 * @param sourceIds the registered sources to report (initially just Health Connect).
 */
class SyncMonitorRepository(
    context: Context,
    private val outbox: OutboxStore,
    private val sourceIds: List<SourceDescriptor> = listOf(SourceDescriptor.HEALTH_CONNECT),
) {
    private val store = context.applicationContext.syncMonitorDataStore
    private val _monitor = MutableStateFlow(SyncMonitor())
    val monitor: StateFlow<SyncMonitor> = _monitor.asStateFlow()

    init {
        _monitor.value =
            SyncMonitor(
                sources =
                    sourceIds.map { desc ->
                        SourceState(
                            sourceId = desc.id,
                            displayName = desc.displayName,
                            available = false,
                            permissionsGranted = false,
                        )
                    },
            )
    }

    /** Refresh the outbox-derived parts of the snapshot (pending/dead). The
     *  dead-letter list is capped (payload-free previews) so a backlog of tens
     *  of thousands of failed items can't flood memory or the UI. */
    suspend fun refresh() {
        val pending = outbox.pendingCount(OutboxLane.DEFAULT) + outbox.pendingCount(OutboxLane.LARGE)
        val deadCount = outbox.deadLetterCount()
        val deadSummaries =
            outbox.deadLetterPreviews(DEAD_PREVIEW_LIMIT).map {
                DeadLetterSummary(
                    id = it.id,
                    method = it.method,
                    path = it.path,
                    attempts = it.attempts,
                    reason = it.deadReason,
                )
            }
        update { it.copy(outboxPending = pending, outboxDeadLettered = deadCount, deadLetters = deadSummaries) }
    }

    /**
     * Record the outcome of one sync pass for [sourceId]. Adds [perTypeSynced] to
     * the cumulative per-type counts, stamps [lastSyncAt], and sets [status].
     * Called by the enhanced SyncWorker (Phase D) after each drain.
     */
    suspend fun recordSyncResult(
        sourceId: String,
        perTypeSynced: Map<HcType, Int>,
        status: SyncStatus,
    ) {
        val now = System.currentTimeMillis()
        val prefs = store.data.first()
        val sources =
            sourceIds.map { desc ->
                if (desc.id != sourceId) {
                    currentSource(desc, prefs)
                } else {
                    val current = currentSource(desc, prefs)
                    val mergedCounts =
                        HcType.entries.associateWith { type ->
                            (current.totalSyncedPerType[type] ?: 0) + (perTypeSynced[type] ?: 0)
                        }
                    current.copy(
                        totalSyncedPerType = mergedCounts,
                        lastSyncAt = now,
                        lastSyncStatus = status,
                    )
                }
            }
        persistCounts(sources, prefs)
        update { it.copy(sources = sources) }
    }

    /** Merge the latest readings observed this pass into the snapshot (keeps the
     *  newest per type across passes). */
    suspend fun reportLatestReadings(readings: Map<HcType, RawSample>) {
        if (readings.isEmpty()) return
        update { monitor ->
            val merged = monitor.latestReadings.toMutableMap()
            readings.forEach { (type, sample) ->
                val existing = merged[type]
                merged[type] = if (existing != null && (existing.timestamp ?: "") >= (sample.timestamp ?: "")) existing else sample
            }
            monitor.copy(latestReadings = merged)
        }
    }

    /** Set/clear the human-readable label of the date range being read right now. */
    suspend fun reportReadingWindow(label: String?) {
        update { it.copy(readingWindow = label) }
    }

    /** Drop the entire outbox (Phase G "Clear outbox") + reset the progress snapshot. */
    suspend fun clearOutbox() {
        outbox.clear()
        clearProgress()
        refresh()
    }

    /** Revive a dead-letter item back to PENDING (Phase F dead-letter retry),
     *  then refresh the snapshot so the dashboard updates. */
    suspend fun retryDeadLetter(id: String) {
        outbox.revive(id)
        refresh()
    }

    /** Revive every dead-letter item back to PENDING (bulk "Try all again"),
     *  then refresh the snapshot. One SQL statement regardless of backlog size. */
    suspend fun retryAllDeadLetters() {
        outbox.reviveAll()
        refresh()
    }

    /** Mark the start of a drain pass with the backlog [total]. */
    suspend fun beginProgress(total: Int) {
        update {
            it.copy(
                progress =
                    SyncProgress(
                        active = true,
                        processed = 0,
                        total = total,
                        perTypeSynced = emptyMap(),
                        perTypeFailed = emptyMap(),
                    ),
            )
        }
    }

    /** Update the live progress of the active drain pass. */
    suspend fun reportProgress(
        processed: Int,
        total: Int,
        remaining: Int,
        perTypeSynced: Map<HcType, Int>,
        perTypeFailed: Map<HcType, Int>,
    ) {
        update {
            it.copy(
                outboxPending = remaining,
                progress =
                    it.progress?.copy(
                        processed = processed,
                        total = total,
                        perTypeSynced = perTypeSynced,
                        perTypeFailed = perTypeFailed,
                    ) ?: SyncProgress(true, processed, total, perTypeSynced, perTypeFailed),
            )
        }
    }

    /** Mark the drain pass finished (clears the active flag). */
    suspend fun endProgress(
        perTypeSynced: Map<HcType, Int>,
        perTypeFailed: Map<HcType, Int>,
    ) {
        update {
            it.copy(
                progress = it.progress?.copy(active = false, perTypeSynced = perTypeSynced, perTypeFailed = perTypeFailed),
            )
        }
    }

    /** Drop the progress snapshot entirely (back to idle). */
    suspend fun clearProgress() {
        update { it.copy(progress = null) }
    }

    /** Mark a source's availability / permission state (probed from the UI).
     *  Persisted so the worker's frequent recordSyncResult calls (which rebuild
     *  the source list) don't reset a known-good "Connected" back to false. */
    suspend fun setSourceAvailability(
        sourceId: String,
        available: Boolean,
        permissionsGranted: Boolean,
    ) {
        store.edit { prefs ->
            prefs[availabilityKey(sourceId)] = available
            prefs[permissionsKey(sourceId)] = permissionsGranted
        }
        update { monitor ->
            val sources =
                monitor.sources.map {
                    if (it.sourceId == sourceId) {
                        it.copy(available = available, permissionsGranted = permissionsGranted)
                    } else {
                        it
                    }
                }
            monitor.copy(sources = sources)
        }
    }

    /** Reset cumulative counts (e.g. after "Reset cursors"). */
    suspend fun resetCounts() {
        store.edit { prefs ->
            sourceIds.forEach { desc ->
                HcType.entries.forEach { type -> prefs.remove(countKey(desc.id, type)) }
                prefs.remove(lastSyncKey(desc.id))
            }
        }
        val sources =
            sourceIds.map { desc ->
                SourceState(
                    sourceId = desc.id,
                    displayName = desc.displayName,
                    available = false,
                    permissionsGranted = false,
                )
            }
        update { it.copy(sources = sources) }
    }

    private fun currentSource(
        desc: SourceDescriptor,
        prefs: Preferences,
    ): SourceState =
        SourceState(
            sourceId = desc.id,
            displayName = desc.displayName,
            available = prefs[availabilityKey(desc.id)] ?: false,
            permissionsGranted = prefs[permissionsKey(desc.id)] ?: false,
            totalSyncedPerType =
                HcType.entries.associateWith { type -> prefs[countKey(desc.id, type)] ?: 0 },
            lastSyncAt = prefs[lastSyncKey(desc.id)],
            lastSyncStatus = SyncStatus.Idle,
        )

    private suspend fun persistCounts(
        sources: List<SourceState>,
        prefs: Preferences,
    ) {
        store.edit { ed ->
            sources.forEach { source ->
                source.totalSyncedPerType.forEach { (type, count) -> ed[countKey(source.sourceId, type)] = count }
                source.lastSyncAt?.let { ed[lastSyncKey(source.sourceId)] = it }
            }
        }
    }

    private fun update(transform: (SyncMonitor) -> SyncMonitor) {
        _monitor.value = transform(_monitor.value)
    }

    private fun countKey(
        sourceId: String,
        type: HcType,
    ) = intPreferencesKey("count_${sourceId}_${type.name}")

    private fun lastSyncKey(sourceId: String) = longPreferencesKey("last_sync_$sourceId")

    private fun availabilityKey(sourceId: String) = booleanPreferencesKey("avail_$sourceId")

    private fun permissionsKey(sourceId: String) = booleanPreferencesKey("perms_$sourceId")

    private companion object {
        const val DEAD_PREVIEW_LIMIT = 100
    }
}

/** Lightweight descriptor for a registered source (Phase E formalizes this into a registry). */
data class SourceDescriptor(
    val id: String,
    val displayName: String,
) {
    companion object {
        val HEALTH_CONNECT = SourceDescriptor("health_connect", "Health Connect")
    }
}
