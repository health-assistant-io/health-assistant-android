package io.healthassistant.shared.healthconnect

import io.healthassistant.bridge.ClientRecord

/**
 * Neutral health-data sample kinds (the §5 mapping table). Android's real
 * `androidx.health.connect` record types are converted to [HcType] by the
 * Android adapter (Phase 5, device-only); iOS's HealthKit samples map to the
 * same [HcType] set. This keeps the [HealthConnectMapper] pure-Kotlin and
 * JVM-testable with fakes — no Android dependency.
 */
enum class HcType(
    val code: String,
    val codingSystem: String,
    val display: String,
    val defaultUnit: String,
    val recordType: String,
) {
    HEART_RATE("8867-4", "loinc", "Heart Rate", "bpm", "quantitative"),
    STEPS("55423-8", "loinc", "Steps", "count", "quantitative"),
    WEIGHT("29463-7", "loinc", "Weight", "kg", "quantitative"),
    OXYGEN_SATURATION("59408-5", "loinc", "Oxygen Saturation", "%", "quantitative"),
    SLEEP_DURATION("sleep-duration", "custom", "Sleep Duration", "min", "quantitative"),

    // Phase J — expanded type set
    BLOOD_PRESSURE_SYS("8480-6", "loinc", "Blood Pressure (Systolic)", "mmHg", "quantitative"),
    BLOOD_PRESSURE_DIA("8462-4", "loinc", "Blood Pressure (Diastolic)", "mmHg", "quantitative"),
    BLOOD_GLUCOSE("2339-0", "loinc", "Blood Glucose", "mmol/L", "quantitative"),
    BODY_TEMPERATURE("8310-5", "loinc", "Body Temperature", "°C", "quantitative"),
    RESPIRATION_RATE("9279-1", "loinc", "Respiration Rate", "{breaths}/min", "quantitative"),
    HEIGHT("8302-2", "loinc", "Height", "m", "quantitative"),
    DISTANCE("ha-distance", "custom", "Distance", "m", "quantitative"),
    CALORIES("ha-calories", "custom", "Calories Burned", "kcal", "quantitative"),
    ;

    companion object {
        /** Lookup by the bridge `code` (round-trip). */
        fun byCode(code: String): HcType? = entries.firstOrNull { it.code == code }
    }
}

/** A neutral on-device reading; produced by the platform adapter, consumed by [HealthConnectMapper]. */
data class RawSample(
    val hcType: HcType,
    val value: Double? = null,
    val valueString: String? = null,
    val unit: String? = null,
    val timestamp: String? = null,
    val performer: String? = null,
)

/** Maps neutral [RawSample]s → bridge [ClientRecord] per the §5 table. Pure-Kotlin, JVM-testable. */
object HealthConnectMapper {
    fun map(sample: RawSample): ClientRecord = ClientRecord(
        type = sample.hcType.recordType,
        code = sample.hcType.code,
        codingSystem = sample.hcType.codingSystem,
        name = sample.hcType.display,
        value = sample.value,
        valueString = sample.valueString,
        unit = sample.unit ?: sample.hcType.defaultUnit,
        timestamp = sample.timestamp,
        performer = sample.performer,
    )

    fun mapAll(samples: List<RawSample>): List<ClientRecord> = samples.map(::map)
}
