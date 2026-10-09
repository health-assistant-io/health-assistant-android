package io.healthassistant.shared.source

import io.healthassistant.shared.healthconnect.HcType
import io.healthassistant.shared.healthconnect.RawSample
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Phase E stub [HealthDataSource]: demonstrates the pluggable plugin path. A
 * user-typed reading (value + type) is queued via [submit]; the next sync drain
 * pulls queued samples into the pipeline exactly like a real source (Fitbit,
 * Withings, …). No cursors — manual entries are one-shot, so [read] returns an
 * empty cursor map (it never advances Health Connect's per-type cursors).
 *
 * Pure-Kotlin + JVM-testable; lives in shared so future platform sources
 * register the same way. The queue is in-memory (entries are lost on restart) —
 * persistence is deferred (the source registry is the deliverable here).
 */
class ManualEntrySource : HealthDataSource {
    override val id = ID
    override val displayName = "Manual Entry"
    override val availableTypes = HcType.entries.toList()

    private val mutex = Mutex()
    private val pending = mutableListOf<RawSample>()

    /** Queue a user-entered reading for the next sync. */
    suspend fun submit(sample: RawSample) = mutex.withLock { pending.add(sample); Unit }

    override suspend fun isAvailable(): Boolean = true

    override suspend fun read(
        types: Set<HcType>,
        since: Map<HcType, Long>,
    ): ReadResult {
        val drained =
            mutex.withLock {
                val taken = pending.filter { it.hcType in types }
                pending.removeAll(taken)
                taken
            }
        return ReadResult(drained, emptyMap())
    }

    /** Snapshot of queued readings (test helper). */
    suspend fun queued(): List<RawSample> = mutex.withLock { pending.toList() }

    companion object {
        const val ID = "manual"
    }
}
