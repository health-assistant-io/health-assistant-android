package io.healthassistant.shared.data.cache

import io.healthassistant.shared.data.ObservationCode
import io.healthassistant.shared.data.ObservationPoint
import io.healthassistant.shared.data.ReferenceRange
import io.healthassistant.shared.healthconnect.RawSample
import java.time.Instant

/**
 * Pure-Kotlin mapper between the bridge read model [ObservationPoint] and the
 * cacheable [CachedReading] row (M8). All chart/card screens consume
 * [ObservationPoint], so the cache stores rows that round-trip back to an
 * equivalent [ObservationPoint] without loss for the fields the UI uses.
 *
 * [toCached] returns null when the point carries no addressable biomarker code
 * (neither a FHIR coding code nor a biomarker slug) — such rows cannot be
 * queried by biomarker and are skipped.
 */
object CacheMapper {
    /** Convert a read point to a cache row, or null if not addressable. */
    fun toCached(point: ObservationPoint): CachedReading? {
        val coding = point.code?.coding?.firstOrNull()
        val code = coding?.code ?: point.biomarkerSlug ?: return null
        return CachedReading(
            id = point.id,
            biomarkerCode = code,
            effectiveDatetime = point.effectiveDatetime,
            effectiveEpochMs = point.effectiveDatetime?.let(::parseEpochMs),
            rawValue = point.rawValue,
            normalizedValue = point.normalizedValue,
            normalizedUnit = point.normalizedUnit,
            valueString = point.valueString,
            codeText = point.code?.text,
            codingDisplay = coding?.display,
            codingSystem = coding?.system,
            referenceRangeLow = point.referenceRange?.low,
            referenceRangeHigh = point.referenceRange?.high,
            biomarkerId = point.biomarkerId,
            biomarkerSlug = point.biomarkerSlug,
            biomarkerValueType = point.biomarkerValueType,
            interpretation = point.interpretation,
            relativeScore = point.relativeScore,
            biomarkerReferenceRangeMin = point.biomarkerReferenceRangeMin,
            biomarkerReferenceRangeMax = point.biomarkerReferenceRangeMax,
        )
    }

    /** Reconstruct a read point from a cache row. */
    fun toPoint(row: CachedReading): ObservationPoint {
        val code =
            ObservationCode(
                coding =
                    listOf(
                        ObservationCode.Coding(
                            code = row.biomarkerCode,
                            system = row.codingSystem,
                            display = row.codingDisplay,
                        ),
                    ),
                text = row.codeText,
            )
        val referenceRange = row.referenceRange
        return ObservationPoint(
            id = row.id,
            effectiveDatetime = row.effectiveDatetime,
            rawValue = row.rawValue,
            normalizedValue = row.normalizedValue,
            normalizedUnit = row.normalizedUnit,
            code = code,
            referenceRange = referenceRange,
            biomarkerId = row.biomarkerId,
            biomarkerSlug = row.biomarkerSlug,
            biomarkerValueType = row.biomarkerValueType,
            valueString = row.valueString,
            interpretation = row.interpretation,
            relativeScore = row.relativeScore,
            biomarkerReferenceRangeMin = row.biomarkerReferenceRangeMin,
            biomarkerReferenceRangeMax = row.biomarkerReferenceRangeMax,
        )
    }

    /** Best-effort ISO-8601 → epoch ms; null on an unparseable timestamp. */
    private fun parseEpochMs(iso: String): Long? =
        runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()

    /**
     * Convert a local Health Connect [RawSample] into a cacheable point. The id
     * is derived from the type code + the sample timestamp so a re-read of the
     * same sample upserts the same row (no duplicate). Local points coexist
     * with server points in the cache; the newest by epoch wins per biomarker.
     */
    fun fromSample(sample: RawSample): ObservationPoint {
        val type = sample.hcType
        val id = "local-${type.code}-${sample.timestamp ?: "latest"}"
        return ObservationPoint(
            id = id,
            effectiveDatetime = sample.timestamp,
            rawValue = sample.value,
            normalizedUnit = sample.unit ?: type.defaultUnit,
            code =
                ObservationCode(
                    coding = listOf(ObservationCode.Coding(code = type.code, system = "http://loinc.org", display = type.display)),
                    text = type.display,
                ),
            valueString = sample.valueString,
            biomarkerValueType = type.recordType,
        )
    }
}
