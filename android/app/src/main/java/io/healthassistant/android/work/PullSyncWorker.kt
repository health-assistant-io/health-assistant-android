package io.healthassistant.android.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.healthassistant.android.HAApplication
import io.healthassistant.android.data.CredentialStore
import io.healthassistant.android.data.PullSyncRepository
import io.healthassistant.bridge.BridgeClient
import io.healthassistant.shared.data.repository.ClinicalRecordRepository
import io.healthassistant.shared.data.repository.ConnectivityProvider
import io.healthassistant.shared.data.repository.DocumentRepository
import io.healthassistant.shared.data.repository.ExaminationRepository
import io.healthassistant.shared.sync.ChangeType
import io.healthassistant.shared.sync.ChangesDelta
import io.healthassistant.shared.sync.PullFetchException
import io.healthassistant.shared.sync.PullResult
import io.healthassistant.shared.sync.PullSyncCoordinator
import io.healthassistant.shared.sync.PullSyncStore
import org.koin.core.context.GlobalContext

/**
 * Phase G — the pull side of two-way incremental sync. Calls `GET /changes`
 * (via the SDK's `BridgeClient.getChangesRaw`), hands the delta to the
 * [PullSyncCoordinator] which hydrates caches + advances the cursor crash-safely,
 * then bumps the [PullSyncRepository.lastPullEpochMs] signal so any
 * not-yet-migrated screens re-fetch and reflect PWA-side edits.
 *
 * **Offline-first M7:** `applyDelta` now hydrates the per-domain Room caches
 * directly (merge-upsert by id via [io.healthassistant.shared.data.cache.DeltaRows])
 * — a medication added on the PWA lands in the cache and the observing screens
 * re-render without any network round-trip of their own.
 *
 * **Deletion reconciliation:** `/changes` does not represent deletes, so a full
 * re-snapshot (per-domain list reads + reconcile) runs when the last one is
 * older than [RESYNC_INTERVAL_MS] (24h), keeping the caches exact mirrors.
 *
 * Always returns [Result.success] — failures surface in the log + as an
 * unchanged cursor (the crash-safe contract), not as WorkManager retries that
 * could stack on top of a network outage. The next periodic schedule retries.
 *
 * Scheduled by [SyncScheduler.schedulePullPeriodic] (~30 min, network-constrained)
 * + on-demand via [SyncScheduler.pullNow] (KEEP — a pull-to-refresh does not
 * cancel an in-flight pull). Local-only writes (HC) push first via [SyncWorker];
 * this worker only pulls server-side clinical mutations (meds/allergies/vaccines/
 * events/docs/exams). Conflict policy: server wins (plan §G.3).
 */
class PullSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val credential =
            CredentialStore(applicationContext).load()
                ?: return Result.success()

        val koin = GlobalContext.getOrNull() ?: return Result.success()

        val client =
            BridgeClient(
                baseUrl = credential.baseUrl,
                integrationId = credential.integrationId,
                apiSecret = credential.apiSecret,
            )

        // Offline-first M9: the caches are connection-scoped; the worker
        // binds them to the active connection (the one it pulls for) via the
        // same RoomCaches bundle the Routes use, so delta hydration + the
        // re-snapshot write the right patient's rows and staleness meta.
        val db: io.healthassistant.android.data.cache.ObservationDatabase = koin.get()
        val caches =
            io.healthassistant.android.data.cache
                .RoomCaches(db, credential.integrationId)
        val connectivity: ConnectivityProvider = koin.get()
        val recordRepo = ClinicalRecordRepository(caches.records, BridgeGatewayHolder.record(client), connectivity, caches.meta)
        val examRepo = ExaminationRepository(caches.examinations, BridgeGatewayHolder.exam(client), connectivity, caches.meta)
        val docRepo = DocumentRepository(caches.documents, BridgeGatewayHolder.doc(client), connectivity, caches.meta)

        val pullStore: PullSyncStore =
            koin.get<PullSyncRepository>().let { repo ->
                PullSyncStoreAdapter(repo, DeltaHydrator(recordRepo, examRepo, docRepo))
            }

        val coordinator =
            PullSyncCoordinator(
                fetch = { since -> client.getChangesRaw(since = since, limit = FETCH_LIMIT) },
                store = pullStore,
            )

        val result: PullResult =
            try {
                coordinator.pull()
            } catch (e: PullFetchException) {
                Log.w(TAG, "pull fetch failed (cursor=${e.cursor}): ${e.cause?.message}")
                return Result.success()
            } catch (e: Exception) {
                Log.w(TAG, "pull failed: ${e.javaClass.simpleName}: ${e.message}", e)
                return Result.success()
            }

        Log.i(
            TAG,
            "pull: changed=${result.totalChanged} cursor=${result.newCursor} " +
                "meds=${result.counts[ChangeType.MEDICATIONS]} " +
                "allergies=${result.counts[ChangeType.ALLERGIES]} " +
                "exams=${result.counts[ChangeType.EXAMINATIONS]}",
        )

        // Deletion reconciliation: `/changes` can't express deletes, so refresh
        // full snapshots (upsert + reconcile) on a 24h cadence.
        if (shouldResnapshot(koin)) {
            Log.i(TAG, "running the 24h full re-snapshot (deletion reconciliation)")
            val outcomes =
                listOf(
                    recordRepo.refreshMedications(),
                    recordRepo.refreshAllergies(),
                    recordRepo.refreshVaccines(),
                    recordRepo.refreshClinicalEvents(),
                    examRepo.refresh(),
                    docRepo.refreshAll(),
                )
            Log.i(TAG, "re-snapshot outcomes: $outcomes")
            koin.get<PullSyncRepository>().markResnapshotted()
        }

        if (result.changed) {
            // The adapter already bumped the refresh signal inside applyDelta;
            // here we surface a notification for high-signal changes (new exam
            // results / clinical events) per the Phase I channel model.
            PullSyncNotifications.postPullSummary(applicationContext, result)
        }
        return Result.success()
    }

    private suspend fun shouldResnapshot(koin: org.koin.core.Koin): Boolean {
        val pullRepo = koin.get<PullSyncRepository>()
        return pullRepo.millisecondsSinceLastResnapshot() > RESYNC_INTERVAL_MS
    }

    private companion object {
        const val TAG = "HAPullWorker"
        const val FETCH_LIMIT = 500
        val RESYNC_INTERVAL_MS: Long = 24L * 60 * 60 * 1000
    }
}

/** Hydrates a `/changes` delta into the three record caches (offline-first M7). */
private class DeltaHydrator(
    private val recordRepo: ClinicalRecordRepository,
    private val examRepo: ExaminationRepository,
    private val docRepo: DocumentRepository,
) : PullSyncRepository.DeltaApplier {
    override suspend fun apply(delta: ChangesDelta) {
        recordRepo.applyDelta(delta)
        examRepo.applyDelta(delta)
        docRepo.applyDelta(delta)
    }
}

/** Lazily-built per-connection gateways for the worker (kept tiny — the
 *  screens build their own in the Routes). */
private object BridgeGatewayHolder {
    fun record(client: BridgeClient) =
        io.healthassistant.android.data.repository
            .BridgeClinicalRecordGateway(client)

    fun exam(client: BridgeClient) =
        io.healthassistant.android.data.repository
            .BridgeExaminationGateway(client)

    fun doc(client: BridgeClient) =
        io.healthassistant.android.data.repository
            .BridgeDocumentGateway(client)
}

/**
 * Phase G — local adapter that satisfies the shared [PullSyncStore] contract
 * using the Android [PullSyncRepository]. Kept in the worker file so the shared
 * core stays free of Android types; the adapter is the bridge boundary.
 */
private class PullSyncStoreAdapter(
    private val repo: PullSyncRepository,
    private val hydrator: PullSyncRepository.DeltaApplier,
) : PullSyncStore {
    override suspend fun currentCursor(): String? = repo.currentCursor()

    override suspend fun saveCursor(isoCursor: String) = repo.saveCursor(isoCursor)

    override suspend fun applyDelta(delta: ChangesDelta) = repo.applyDelta(delta)

    override suspend fun clearCursor() = repo.clearCursor()
}

/** Phase G — high-signal change notifications posted from the pull worker. */
private object PullSyncNotifications {
    fun postPullSummary(
        context: Context,
        result: PullResult,
    ) {
        val exams = result.counts[ChangeType.EXAMINATIONS] ?: 0
        if (exams > 0) {
            SyncNotifications.postChangeResult(
                context = context,
                channelId = HAApplication.CHANNEL_EXAMINATION,
                title = "New examination results",
                text = "$exams new result${if (exams > 1) "s" else ""} available",
                notificationId = NOTIF_ID_EXAMS,
            )
        }
    }

    private const val NOTIF_ID_EXAMS = 1010
}
