package io.healthassistant.shared.data

import io.healthassistant.shared.healthconnect.HcType
import io.healthassistant.shared.healthconnect.RawSample

/** One Home dashboard card: a biomarker (from the instance catalog) with its
 *  latest known reading. Covers both Health Connect types and instance-only lab
 *  biomarkers — the app must not branch on source. */
data class BiomarkerReading(
    val code: String,
    val displayName: String,
    val value: Double? = null,
    val valueString: String? = null,
    val unit: String? = null,
    val timestamp: String? = null,
    val referenceRange: ReferenceRange? = null,
    val hcType: HcType? = null,
    val isTelemetry: Boolean = false,
) {
    /** Where the latest numeric value sits relative to the reference range
     *  (null when unknown — no range or no numeric value). */
    val rangeStatus: RangeStatus?
        get() {
            val v = value ?: return null
            val range = referenceRange?.takeIf { it.present } ?: return null
            val high = range.high
            val low = range.low
            return when {
                high != null && v > high -> RangeStatus.HIGH
                low != null && v < low -> RangeStatus.LOW
                (high == null || v <= high) && (low == null || v >= low) -> RangeStatus.NORMAL
                else -> null
            }
        }
}

/** Latest-value position relative to the reference range. */
enum class RangeStatus { NORMAL, HIGH, LOW }

/** Builds the Home dashboard model from the bridge read paths. Pure-Kotlin and
 *  JVM-testable: merges the instance biomarker catalog with the latest-per-
 *  biomarker values (server) and the local Health Connect readings (offline
 *  fallback), keyed by biomarker code. */
object HomeDashboardBuilder {

    /** Cards from the server's `GET /observations/latest` + `GET /biomarkers`.
     *  Only biomarkers that have a latest value produce a card. */
    fun fromServer(
        latest: List<ObservationPoint>,
        catalog: List<BiomarkerSummary>,
    ): List<BiomarkerReading> {
        val byCode = catalog.mapNotNull { s -> s.code?.let { it to s } }.toMap()
        return latest.mapNotNull { obs ->
            val code = obs.primaryCode ?: return@mapNotNull null
            val summary = byCode[code]
            BiomarkerReading(
                code = code,
                displayName = summary?.name ?: obs.displayName ?: code,
                value = obs.chartValue,
                valueString = obs.valueString,
                unit = obs.normalizedUnit ?: summary?.unit,
                timestamp = obs.effectiveDatetime,
                referenceRange = obs.range ?: summary?.referenceRange,
                hcType = HcType.byCode(code),
                isTelemetry = summary?.isTelemetry == true,
            )
        }
    }

    /** Cards from the local Health Connect readings (offline fallback / merge
     *  source). */
    fun fromLocal(local: Map<HcType, RawSample>): List<BiomarkerReading> =
        local.map { (type, sample) ->
            BiomarkerReading(
                code = type.code,
                displayName = type.display,
                value = sample.value,
                valueString = sample.valueString,
                unit = sample.unit ?: type.defaultUnit,
                timestamp = sample.timestamp,
                hcType = type,
            )
        }

    /** Merge local + server readings for one code — newer timestamp wins.
     *  Local is the fallback when the server has nothing. */
    fun merge(
        local: List<BiomarkerReading>,
        server: List<BiomarkerReading>,
    ): List<BiomarkerReading> {
        val byCode = linkedMapOf<String, BiomarkerReading>()
        (local + server).forEach { reading ->
            val existing = byCode[reading.code]
            if (existing == null || (reading.timestamp ?: "") > (existing.timestamp ?: "")) {
                byCode[reading.code] = reading
            }
        }
        return byCode.values.toList()
    }
}
