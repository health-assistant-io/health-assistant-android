package io.healthassistant.shared.data.cache

import io.healthassistant.shared.data.ObservationPoint
import io.healthassistant.shared.data.ReferenceRange

/**
 * The flat, Room-friendly row stored in the on-device observation cache
 * (M8). Pure-Kotlin (no Room annotations) so the cache interface, the mapper,
 * and all logic stay in the KMP `shared` core and convert to multiplatform
 * mechanically when iOS lands. The Android `CachedObservation` entity mirrors
 * these fields 1:1 (plus an indexed `effective_epoch_ms` column copied from
 * [effectiveEpochMs]) — see `android/data/cache/`.
 *
 * Cache rows are keyed by [id] (the FHIR Observation id) so a re-fetch upserts
 * the same row instead of duplicating it. [biomarkerCode] (the LOINC / custom
 * code) is the query key for "series for this biomarker".
 */
data class CachedReading(
    val id: String,
    val biomarkerCode: String,
    val effectiveDatetime: String?,
    val effectiveEpochMs: Long?,
    val rawValue: Double?,
    val normalizedValue: Double?,
    val normalizedUnit: String?,
    val valueString: String?,
    val codeText: String?,
    val codingDisplay: String?,
    val codingSystem: String?,
    val referenceRangeLow: Double?,
    val referenceRangeHigh: Double?,
    val biomarkerId: String?,
    val biomarkerSlug: String?,
    val biomarkerValueType: String?,
    val interpretation: String?,
    val relativeScore: Double?,
    val biomarkerReferenceRangeMin: Double?,
    val biomarkerReferenceRangeMax: Double?,
) {
    /** Convenience accessor matching [ObservationPoint.range]. */
    val referenceRange: ReferenceRange?
        get() =
            if (referenceRangeLow != null || referenceRangeHigh != null) {
                ReferenceRange(referenceRangeLow, referenceRangeHigh)
            } else {
                null
            }
}
