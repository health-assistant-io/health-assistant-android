package io.healthassistant.shared.source

import io.healthassistant.shared.healthconnect.HcType
import io.healthassistant.shared.healthconnect.RawSample

/**
 * A pluggable on-device health data source. Health Connect is the first
 * implementation; future sources (Fitbit, Withings, manual entry) implement
 * this and register in a `SourceRegistry`. Pure-Kotlin + JVM-testable with
 * fakes — no Android dependency.
 */
interface HealthDataSource {

    /** Stable id for storage + logging ("health_connect", "withings", …). */
    val id: String

    /** Human-readable name for the UI. */
    val displayName: String

    /** The data types this source can provide. */
    val availableTypes: List<HcType>

    /** Is the source ready to read (installed + permissions granted)? */
    suspend fun isAvailable(): Boolean

    /**
     * Read samples of the given [types] since their per-type cursor.
     * @param types the enabled types to read.
     * @param since per-type epoch-ms cursor (last successful read); missing = read from epoch.
     * @return the samples + the updated per-type cursors (= now, per type that was read).
     */
    suspend fun read(types: Set<HcType>, since: Map<HcType, Long>): ReadResult
}

/** Result of a [HealthDataSource.read] call. */
data class ReadResult(
    val samples: List<RawSample>,
    val newCursors: Map<HcType, Long>,
)

/** A JVM-testable fake [HealthDataSource] for unit tests + shared-layer wiring. */
class FakeHealthDataSource(
    override val id: String = "fake",
    override val displayName: String = "Fake Source",
    override val availableTypes: List<HcType> = HcType.entries.toList(),
    private val available: Boolean = true,
    private val samplesByType: Map<HcType, List<RawSample>> = emptyMap(),
) : HealthDataSource {

    var readCalls: List<Pair<Set<HcType>, Map<HcType, Long>>> = emptyList()
        private set

    override suspend fun isAvailable(): Boolean = available

    override suspend fun read(types: Set<HcType>, since: Map<HcType, Long>): ReadResult {
        readCalls = readCalls + (types to since)
        val now = System.currentTimeMillis()
        val samples = types.flatMap { samplesByType[it] ?: emptyList() }
        val cursors = types.associateWith { now }
        return ReadResult(samples, cursors)
    }
}
