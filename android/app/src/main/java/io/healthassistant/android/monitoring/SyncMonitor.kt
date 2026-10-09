package io.healthassistant.android.monitoring

import io.healthassistant.shared.healthconnect.HcType
import io.healthassistant.shared.healthconnect.RawSample

/**
 * Status of the most recent sync pass (Phase C of the health-connect-sync plan,
 * §2.5). Part of [SourceState], surfaced on the Monitoring dashboard.
 */
sealed class SyncStatus {
    /** No sync has happened yet. */
    data object Idle : SyncStatus()

    /** A source read is in progress. */
    data object Reading : SyncStatus()

    /** The outbox is draining. */
    data object Syncing : SyncStatus()

    /** Last pass finished cleanly; [count] items were pushed. */
    data class Success(
        val count: Int,
    ) : SyncStatus()

    /** Last pass ended in an error; [message] is the user-facing reason. */
    data class Error(
        val message: String,
    ) : SyncStatus()
}

/**
 * Observable runtime state of one [io.healthassistant.shared.source.HealthDataSource]
 * (plan §2.4). Owned by [SyncMonitorRepository]; rendered by the Monitoring screen.
 *
 * @param lastReadAt per-type cursor (epoch ms) of the last successful read.
 * @param totalSyncedPerType cumulative count of items pushed per [HcType].
 */
data class SourceState(
    val sourceId: String,
    val displayName: String,
    val available: Boolean,
    val permissionsGranted: Boolean,
    val lastReadAt: Map<HcType, Long> = emptyMap(),
    val totalSyncedPerType: Map<HcType, Int> = emptyMap(),
    val lastSyncAt: Long? = null,
    val lastSyncStatus: SyncStatus = SyncStatus.Idle,
)

/**
 * A flattened dead-letter item for the Monitoring dashboard's "View dead
 * letters" list. Mirrors the relevant fields of an
 * [io.healthassistant.shared.sync.OutboxItem] without exposing the payload bytes.
 */
data class DeadLetterSummary(
    val id: String,
    val method: String,
    val path: String,
    val attempts: Int,
    val reason: String?,
)

/**
 * The full monitoring snapshot (plan §2.5). One entry per registered source +
 * the outbox-wide counters. Exposed as a `StateFlow` by [SyncMonitorRepository]
 * and collected reactively by Compose.
 */
data class SyncMonitor(
    val sources: List<SourceState> = emptyList(),
    val outboxPending: Int = 0,
    val outboxDeadLettered: Int = 0,
    val deadLetters: List<DeadLetterSummary> = emptyList(),
    /** Progress of the active sync pass, or null when idle. */
    val progress: SyncProgress? = null,
    /** Most recent reading observed per type (local HC read), for the dashboard. */
    val latestReadings: Map<HcType, RawSample> = emptyMap(),
    /** Human-readable label of the date range currently being read, or null. */
    val readingWindow: String? = null,
)

/**
 * Live progress of one drain pass (Phase G). Updated by the enhanced
 * [io.healthassistant.android.work.SyncWorker] after every batch so the
 * Monitoring dashboard's progress bar + per-biomarker breakdown update in
 * real time.
 *
 * @param processed items finalized this pass (synced + dead-lettered).
 * @param total items pending at the start of the pass (the backlog size).
 * @param startedAt epoch ms when the pass began; the UI derives throughput + ETA.
 */
data class SyncProgress(
    val active: Boolean,
    val processed: Int,
    val total: Int,
    val perTypeSynced: Map<HcType, Int>,
    val perTypeFailed: Map<HcType, Int>,
    val startedAt: Long = System.currentTimeMillis(),
    val lastUpdatedAt: Long = System.currentTimeMillis(),
) {
    /** 0f..1f for a determinate [androidx.compose.material3.LinearProgressIndicator]. */
    val fraction: Float get() = if (total <= 0) 0f else (processed.toFloat() / total).coerceIn(0f, 1f)

    /** Remaining items in the backlog at the last update (total − processed). */
    val remaining: Int get() = (total - processed).coerceAtLeast(0)

    /** Items finalized per second over the pass, or 0 if no time has elapsed. */
    fun itemsPerSec(now: Long = System.currentTimeMillis()): Double {
        val elapsedSec = ((now - startedAt) / 1000.0).coerceAtLeast(0.001)
        return processed / elapsedSec
    }

    /** Estimated ms to drain [remaining] at the current [itemsPerSec], or null if 0 rate. */
    fun etaMs(now: Long = System.currentTimeMillis()): Long? {
        val rate = itemsPerSec(now)
        return if (rate <= 0.0) null else (remaining / rate * 1000).toLong()
    }
}
