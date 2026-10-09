package io.healthassistant.android.data.cache

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import io.healthassistant.shared.data.cache.CachedReading

/**
 * Room entity mirroring [CachedReading] 1:1 (M8). `effective_epoch_ms` is a
 * separate, indexed column (copied from [CachedReading.effectiveEpochMs]) so
 * time-window queries and the "latest per biomarker" picks are index-driven.
 *
 * The compound index `(biomarker_code, effective_epoch_ms)` backs the per-code
 * time-series query; the single `biomarker_code` index backs the latest-per-code
 * correlated subquery.
 *
 * Offline-first M9: every row is scoped by `connection_id` (part of the primary
 * key and of both indices) — two saved connections can hold the same
 * observation id without colliding, and no query can cross the patient
 * boundary.
 */
@Entity(
    tableName = "observation_cache",
    primaryKeys = ["connection_id", "id"],
    indices = [
        Index(value = ["connection_id", "biomarker_code"]),
        Index(value = ["connection_id", "biomarker_code", "effective_epoch_ms"]),
    ],
)
data class CachedObservation(
    @ColumnInfo(name = "connection_id") val connectionId: String,
    @ColumnInfo(name = "biomarker_code") val biomarkerCode: String,
    val id: String,
    @ColumnInfo(name = "effective_datetime") val effectiveDatetime: String?,
    @ColumnInfo(name = "effective_epoch_ms") val effectiveEpochMs: Long?,
    @ColumnInfo(name = "raw_value") val rawValue: Double?,
    @ColumnInfo(name = "normalized_value") val normalizedValue: Double?,
    @ColumnInfo(name = "normalized_unit") val normalizedUnit: String?,
    @ColumnInfo(name = "value_string") val valueString: String?,
    @ColumnInfo(name = "code_text") val codeText: String?,
    @ColumnInfo(name = "coding_display") val codingDisplay: String?,
    @ColumnInfo(name = "coding_system") val codingSystem: String?,
    @ColumnInfo(name = "reference_range_low") val referenceRangeLow: Double?,
    @ColumnInfo(name = "reference_range_high") val referenceRangeHigh: Double?,
    @ColumnInfo(name = "biomarker_id") val biomarkerId: String?,
    @ColumnInfo(name = "biomarker_slug") val biomarkerSlug: String?,
    @ColumnInfo(name = "biomarker_value_type") val biomarkerValueType: String?,
    @ColumnInfo(name = "interpretation") val interpretation: String?,
    @ColumnInfo(name = "relative_score") val relativeScore: Double?,
    @ColumnInfo(name = "biomarker_reference_range_min") val biomarkerReferenceRangeMin: Double?,
    @ColumnInfo(name = "biomarker_reference_range_max") val biomarkerReferenceRangeMax: Double?,
)

/** Entity ⇄ the shared [CachedReading] row (pure data, no Room types). */
fun CachedObservation.toRow(): CachedReading =
    CachedReading(
        id = id,
        biomarkerCode = biomarkerCode,
        effectiveDatetime = effectiveDatetime,
        effectiveEpochMs = effectiveEpochMs,
        rawValue = rawValue,
        normalizedValue = normalizedValue,
        normalizedUnit = normalizedUnit,
        valueString = valueString,
        codeText = codeText,
        codingDisplay = codingDisplay,
        codingSystem = codingSystem,
        referenceRangeLow = referenceRangeLow,
        referenceRangeHigh = referenceRangeHigh,
        biomarkerId = biomarkerId,
        biomarkerSlug = biomarkerSlug,
        biomarkerValueType = biomarkerValueType,
        interpretation = interpretation,
        relativeScore = relativeScore,
        biomarkerReferenceRangeMin = biomarkerReferenceRangeMin,
        biomarkerReferenceRangeMax = biomarkerReferenceRangeMax,
    )

/** Entity ⇄ the shared [CachedReading] row (pure data, no Room types). */
fun CachedReading.toEntity(connectionId: String): CachedObservation =
    CachedObservation(
        connectionId = connectionId,
        biomarkerCode = biomarkerCode,
        id = id,
        effectiveDatetime = effectiveDatetime,
        effectiveEpochMs = effectiveEpochMs,
        rawValue = rawValue,
        normalizedValue = normalizedValue,
        normalizedUnit = normalizedUnit,
        valueString = valueString,
        codeText = codeText,
        codingDisplay = codingDisplay,
        codingSystem = codingSystem,
        referenceRangeLow = referenceRangeLow,
        referenceRangeHigh = referenceRangeHigh,
        biomarkerId = biomarkerId,
        biomarkerSlug = biomarkerSlug,
        biomarkerValueType = biomarkerValueType,
        interpretation = interpretation,
        relativeScore = relativeScore,
        biomarkerReferenceRangeMin = biomarkerReferenceRangeMin,
        biomarkerReferenceRangeMax = biomarkerReferenceRangeMax,
    )
