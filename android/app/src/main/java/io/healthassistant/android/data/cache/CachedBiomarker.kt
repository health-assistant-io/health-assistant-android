package io.healthassistant.android.data.cache

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import io.healthassistant.shared.data.BiomarkerSummary

/**
 * Room entity mirroring [BiomarkerSummary] (offline-first M2). The catalog is
 * stored as a full snapshot keyed by the definition `id`; the `code` index
 * backs the by-code lookup (Home card merge + Insights pre-selection).
 *
 * Offline-first M9: rows are scoped by `connection_id` (part of the primary
 * key + the code index) — each connection sees only its own instance's
 * catalog.
 */
@Entity(
    tableName = "biomarker_cache",
    primaryKeys = ["connection_id", "id"],
    indices = [Index(value = ["connection_id", "code"])],
)
data class CachedBiomarker(
    @ColumnInfo(name = "connection_id") val connectionId: String,
    val id: String,
    val name: String,
    val slug: String?,
    val code: String?,
    @ColumnInfo(name = "coding_system") val codingSystem: String?,
    val unit: String?,
    @ColumnInfo(name = "is_telemetry") val isTelemetry: Boolean,
    @ColumnInfo(name = "reference_range_min") val referenceRangeMin: Double?,
    @ColumnInfo(name = "reference_range_max") val referenceRangeMax: Double?,
    @ColumnInfo(name = "value_type") val valueType: String?,
    /** Markdown education text (R4 bridge read). */
    val info: String?,
)

/** Entity ⇄ the shared read model. */
fun CachedBiomarker.toSummary(): BiomarkerSummary =
    BiomarkerSummary(
        id = id,
        name = name,
        slug = slug,
        code = code,
        codingSystem = codingSystem,
        unit = unit,
        isTelemetry = isTelemetry,
        referenceRangeMin = referenceRangeMin,
        referenceRangeMax = referenceRangeMax,
        valueType = valueType,
        info = info,
    )

/** Entity ⇄ the shared read model. */
fun BiomarkerSummary.toEntity(connectionId: String): CachedBiomarker =
    CachedBiomarker(
        connectionId = connectionId,
        id = id,
        name = name,
        slug = slug,
        code = code,
        codingSystem = codingSystem,
        unit = unit,
        isTelemetry = isTelemetry,
        referenceRangeMin = referenceRangeMin,
        referenceRangeMax = referenceRangeMax,
        valueType = valueType,
        info = info,
    )
