package io.healthassistant.android.data.cache

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import io.healthassistant.shared.data.ExaminationSummary
import org.json.JSONArray

/**
 * Room entity mirroring [ExaminationSummary] (offline-first M3). One table
 * holds both the list and the detail projection — the list shape is a subset,
 * so a detail fetch fills more columns on the same row. `diagnoses` is stored
 * as a JSON array string (encoded via org.json — kotlinx-serialization is not
 * on :app's compile classpath).
 *
 * Offline-first M9: rows are scoped by `connection_id` (part of the primary
 * key + the date index) so connections never share exam rows.
 */
@Entity(
    tableName = "examination_cache",
    primaryKeys = ["connection_id", "id"],
    indices = [Index(value = ["connection_id", "examination_date"])],
)
data class CachedExamination(
    @ColumnInfo(name = "connection_id") val connectionId: String,
    val id: String,
    @ColumnInfo(name = "examination_date") val examinationDate: String?,
    val notes: String?,
    @ColumnInfo(name = "patient_notes") val patientNotes: String?,
    @ColumnInfo(name = "extraction_status") val extractionStatus: String?,
    val diagnoses: String?,
    val impressions: String?,
    val category: String?,
    @ColumnInfo(name = "lab_name") val labName: String?,
    @ColumnInfo(name = "external_id") val externalId: String?,
)

/** Entity ⇄ the shared read model. [existing] preserves detail-projection
 *  fields (diagnoses, impressions, category, lab, external id) when the
 *  incoming row is the list shape — a list refresh must not null out what a
 *  detail fetch filled in. */
fun CachedExamination.toSummary(): ExaminationSummary =
    ExaminationSummary(
        id = id,
        examinationDate = examinationDate,
        notes = notes,
        patientNotes = patientNotes,
        extractionStatus = extractionStatus,
        diagnoses = decodeDiagnoses(diagnoses),
        impressions = impressions,
        category = category,
        labName = labName,
        externalId = externalId,
    )

/** Entity ⇄ the shared read model. [existing] carries the previous row so a
 *  list-shape upsert keeps the detail-only fields (null-in → keep old). */
fun ExaminationSummary.toEntity(
    connectionId: String,
    existing: CachedExamination? = null,
): CachedExamination =
    CachedExamination(
        connectionId = connectionId,
        id = id,
        examinationDate = examinationDate,
        notes = notes,
        patientNotes = patientNotes,
        extractionStatus = extractionStatus,
        diagnoses = encodeDiagnoses(diagnoses).takeIf { it != null } ?: existing?.diagnoses,
        impressions = impressions ?: existing?.impressions,
        category = category ?: existing?.category,
        labName = labName ?: existing?.labName,
        externalId = externalId ?: existing?.externalId,
    )

private fun encodeDiagnoses(diagnoses: List<String>): String? = if (diagnoses.isEmpty()) null else JSONArray(diagnoses).toString()

private fun decodeDiagnoses(raw: String?): List<String> {
    if (raw.isNullOrEmpty()) return emptyList()
    return runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { idx -> arr.optString(idx) }
    }.getOrDefault(emptyList())
}
