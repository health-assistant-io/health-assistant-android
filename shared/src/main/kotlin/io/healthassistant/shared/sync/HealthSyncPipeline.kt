package io.healthassistant.shared.sync

import io.healthassistant.shared.healthconnect.HealthConnectMapper
import io.healthassistant.shared.healthconnect.HcType
import io.healthassistant.shared.healthconnect.RawSample
import io.healthassistant.shared.source.HealthDataSource

/**
 * Phase D: orchestrates one source-read pass — read the enabled [types] from a
 * [HealthDataSource] since their per-type cursors, map each [RawSample] to a
 * bridge [ClientRecord] via [HealthConnectMapper], enqueue a `/sync` outbox item
 * per record, and return the new cursors to persist. Pure-Kotlin + JVM-testable
 * with [FakeHealthDataSource] + [InMemoryOutboxStore] (no Android dependency).
 *
 * The enhanced [io.healthassistant.android.work.SyncWorker] calls this before
 * draining; the [SyncCoordinator] groups the enqueued `/sync` items into one
 * `SyncPayload` at drain time.
 *
 * @param outbox where the per-record `/sync` items are enqueued.
 */
class HealthSyncPipeline(
    private val outbox: OutboxStore,
) {
    /** Outcome of one read-and-enqueue pass. */
    data class Result(
        val enqueued: Int,
        val perTypeCounts: Map<HcType, Int>,
        val newCursors: Map<HcType, Long>,
        /** The most recent sample observed per type this pass (for the UI). */
        val latestByType: Map<HcType, RawSample> = emptyMap(),
    )

    suspend fun readAndEnqueue(
        source: HealthDataSource,
        types: Set<HcType>,
        cursors: Map<HcType, Long>,
        clientVersion: String = DEFAULT_CLIENT_VERSION,
        sourceSystem: String = DEFAULT_SOURCE_SYSTEM,
    ): Result {
        if (types.isEmpty()) return Result(0, emptyMap(), emptyMap())
        val read = source.read(types, cursors)
        if (read.samples.isEmpty()) return Result(0, emptyMap(), read.newCursors)

        val perType = mutableMapOf<HcType, Int>()
        val latest = mutableMapOf<HcType, RawSample>()
        for (sample in read.samples) {
            val record = HealthConnectMapper.map(sample)
            outbox.enqueue(SyncItems.recordItem(clientVersion, sourceSystem, record))
            perType[sample.hcType] = (perType[sample.hcType] ?: 0) + 1
            latest[sample.hcType] = latest[sample.hcType]?.takeIf { isLater(it, sample) } ?: sample
        }
        return Result(read.samples.size, perType, read.newCursors, latest)
    }

    private fun isLater(a: RawSample, b: RawSample): Boolean =
        (a.timestamp ?: "") >= (b.timestamp ?: "")

    private companion object {
        const val DEFAULT_CLIENT_VERSION = "0.1"
        const val DEFAULT_SOURCE_SYSTEM = "android-app"
    }
}
