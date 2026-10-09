package io.healthassistant.shared.data.cache

import io.healthassistant.shared.sync.ChangesDelta
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Partial row projections carried by the unified `/changes` delta (offline-first
 * M7). Each type's projection is a *subset* of the full read model (e.g.
 * medications carry only `{id, updated_at, status, code_text, start_date}`), so
 * hydration MERGES onto the existing cached row instead of replacing it: fields
 * the delta carries win; fields it omits keep their cached value. A row with no
 * cached counterpart inserts a sparse row (better than nothing offline — the
 * next full refresh completes it).
 *
 * Merge policy for nulls: a null delta value KEEPS the cached value. The server
 * projection always emits its keys (nullable), so null-vs-absent is ambiguous
 * at this layer; keeping the cached value is the safe choice — a stale field is
 * corrected by the next refresh, whereas wiping a field (e.g. a dosage) on a
 * null projection would be visible data loss.
 */
data class MedicationDeltaRow(
    val id: String,
    val updatedAt: String?,
    val status: String?,
    val codeText: String?,
    val startDate: String?,
)

data class AllergyDeltaRow(
    val id: String,
    val updatedAt: String?,
    val clinicalStatus: String?,
    val codeText: String?,
)

data class VaccineDeltaRow(
    val id: String,
    val updatedAt: String?,
    val status: String?,
    val administeredAt: String?,
)

data class ClinicalEventDeltaRow(
    val id: String,
    val updatedAt: String?,
    val status: String?,
    val title: String?,
    val onsetDate: String?,
)

data class DocumentDeltaRow(
    val id: String,
    val updatedAt: String?,
    val filename: String?,
    val status: String?,
    val examinationId: String?,
)

data class ExaminationDeltaRow(
    val id: String,
    val updatedAt: String?,
    val examinationDate: String?,
    val extractionStatus: String?,
)

/** Decode + merge for the delta projections. Pure-Kotlin, JVM-tested. */
object DeltaRows {
    fun medications(delta: ChangesDelta): List<MedicationDeltaRow> =
        delta.medications.map { obj ->
            MedicationDeltaRow(
                id = obj.reqId(),
                updatedAt = obj.str("updated_at"),
                status = obj.str("status"),
                codeText = obj.str("code_text"),
                startDate = obj.str("start_date"),
            )
        }

    fun allergies(delta: ChangesDelta): List<AllergyDeltaRow> =
        delta.allergies.map { obj ->
            AllergyDeltaRow(
                id = obj.reqId(),
                updatedAt = obj.str("updated_at"),
                clinicalStatus = obj.str("clinical_status"),
                codeText = obj.str("code_text"),
            )
        }

    fun vaccines(delta: ChangesDelta): List<VaccineDeltaRow> =
        delta.vaccines.map { obj ->
            VaccineDeltaRow(
                id = obj.reqId(),
                updatedAt = obj.str("updated_at"),
                status = obj.str("status"),
                administeredAt = obj.str("administered_at"),
            )
        }

    fun clinicalEvents(delta: ChangesDelta): List<ClinicalEventDeltaRow> =
        delta.clinicalEvents.map { obj ->
            ClinicalEventDeltaRow(
                id = obj.reqId(),
                updatedAt = obj.str("updated_at"),
                status = obj.str("status"),
                title = obj.str("title"),
                onsetDate = obj.str("onset_date"),
            )
        }

    fun documents(delta: ChangesDelta): List<DocumentDeltaRow> =
        delta.documents.map { obj ->
            DocumentDeltaRow(
                id = obj.reqId(),
                updatedAt = obj.str("updated_at"),
                filename = obj.str("filename"),
                status = obj.str("status"),
                examinationId = obj.str("examination_id"),
            )
        }

    fun examinations(delta: ChangesDelta): List<ExaminationDeltaRow> =
        delta.examinations.map { obj ->
            ExaminationDeltaRow(
                id = obj.reqId(),
                updatedAt = obj.str("updated_at"),
                examinationDate = obj.str("examination_date"),
                extractionStatus = obj.str("extraction_status"),
            )
        }

    fun merge(
        existing: CachedMedicationRow?,
        delta: MedicationDeltaRow,
    ): CachedMedicationRow =
        (existing ?: CachedMedicationRow(delta.id, null, null, null, null, null, null, null, null, null, null, null, null)).let { row ->
            row.copy(
                status = delta.status ?: row.status,
                codeJson = mergeCodeText(row.codeJson, delta.codeText),
                startDate = delta.startDate ?: row.startDate,
                updatedAt = delta.updatedAt ?: row.updatedAt,
            )
        }

    fun merge(
        existing: CachedAllergyRow?,
        delta: AllergyDeltaRow,
    ): CachedAllergyRow =
        (existing ?: CachedAllergyRow(delta.id, null, null, null, null, null, null, null, null, null, null, null, null)).let { row ->
            row.copy(
                clinicalStatus = delta.clinicalStatus ?: row.clinicalStatus,
                codeJson = mergeCodeText(row.codeJson, delta.codeText),
                updatedAt = delta.updatedAt ?: row.updatedAt,
            )
        }

    fun merge(
        existing: CachedVaccineRow?,
        delta: VaccineDeltaRow,
    ): CachedVaccineRow =
        (existing ?: CachedVaccineRow(delta.id, null, null, null, null, null, null, null, null, null, null, null)).let { row ->
            row.copy(
                status = delta.status ?: row.status,
                administeredAt = delta.administeredAt ?: row.administeredAt,
                updatedAt = delta.updatedAt ?: row.updatedAt,
            )
        }

    fun merge(
        existing: CachedClinicalEventRow?,
        delta: ClinicalEventDeltaRow,
    ): CachedClinicalEventRow =
        (existing ?: CachedClinicalEventRow(delta.id, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)).let { row ->
            row.copy(
                status = delta.status ?: row.status,
                title = delta.title ?: row.title,
                onsetDate = delta.onsetDate ?: row.onsetDate,
                updatedAt = delta.updatedAt ?: row.updatedAt,
            )
        }

    fun merge(
        existing: io.healthassistant.shared.data.ExaminationSummary?,
        delta: ExaminationDeltaRow,
    ): io.healthassistant.shared.data.ExaminationSummary =
        (existing ?: io.healthassistant.shared.data.ExaminationSummary(id = delta.id)).let { row ->
            row.copy(
                examinationDate = delta.examinationDate ?: row.examinationDate,
                extractionStatus = delta.extractionStatus ?: row.extractionStatus,
            )
        }

    /** The local_* byte-manifest columns are deliberately not in the delta
     *  projection, so they always survive a merge onto an existing row. */
    fun merge(
        existing: CachedDocumentRow?,
        delta: DocumentDeltaRow,
    ): CachedDocumentRow =
        (existing ?: CachedDocumentRow(delta.id, null, null, null, null, null, null, null, null, null, null)).let { row ->
            row.copy(
                filename = delta.filename ?: row.filename,
                status = delta.status ?: row.status,
                examinationId = delta.examinationId ?: row.examinationId,
            )
        }

    /** Merge a delta `code_text` into a cached code JSON object, preserving the
     *  other keys (e.g. catalog_id). Null delta text keeps the cached object. */
    private fun mergeCodeText(
        cachedJson: String?,
        deltaText: String?,
    ): String? {
        if (deltaText == null) return cachedJson
        val base = cachedJson?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val merged =
            buildJsonObject {
                base?.forEach { (k, v) -> put(k, v) }
                put("text", deltaText)
            }
        return merged.toString()
    }

    private fun JsonObject.reqId(): String = str("id") ?: error("changes row without id")

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}

/**
 * The flat cache-row twin of [io.healthassistant.shared.data.DocumentSummary]
 * for delta merges — mirrors `CachedDocument`'s fields without Room types so
 * the merge stays in the KMP core. (The examination/medication/etc. merges
 * reuse the existing row types; documents get this one because the metadata
 * cache stores an entity, not a row type.)
 */
data class CachedDocumentRow(
    val id: String,
    val filename: String?,
    val status: String?,
    val progress: Double?,
    val externalId: String?,
    val createdAt: String?,
    val contentType: String?,
    val fileSize: Long?,
    val examinationId: String?,
    val localPath: String?,
    val localCachedAt: Long?,
)
