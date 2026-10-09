package io.healthassistant.shared.data.cache

import io.healthassistant.bridge.Allergy
import io.healthassistant.bridge.ClinicalEvent
import io.healthassistant.bridge.Medication
import io.healthassistant.bridge.Vaccine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Flat, Room-friendly rows for the four clinical-record families
 * (offline-first M5), mirroring the SDK read models with the polymorphic JSON
 * fields (`code`, `frequency`, `reactions`, `vaccine_code`) encoded as strings.
 * Pure-Kotlin so the cache interface + this mapper live in the KMP `shared`
 * core (JVM-testable); the Android entities mirror these rows 1:1 — see
 * `android/data/cache/CachedClinicalRecords.kt`.
 *
 * Rows are keyed by `id`; a refresh upserts + reconciles, so a record removed
 * server-side disappears locally too.
 */
data class CachedMedicationRow(
    val id: String,
    val status: String?,
    val intent: String?,
    val codeJson: String?,
    val startDate: String?,
    val endDate: String?,
    val dosage: String?,
    val frequencyJson: String?,
    val reason: String?,
    val note: String?,
    val examinationId: String?,
    val createdAt: String?,
    val updatedAt: String?,
)

data class CachedAllergyRow(
    val id: String,
    val clinicalStatus: String?,
    val verificationStatus: String?,
    val category: String?,
    val criticality: String?,
    val codeJson: String?,
    val onsetDate: String?,
    val resolvedDate: String?,
    val lastOccurrence: String?,
    val note: String?,
    val reactionsJson: String?,
    val createdAt: String?,
    val updatedAt: String?,
)

data class CachedVaccineRow(
    val id: String,
    val status: String?,
    val vaccineCodeJson: String?,
    val administeredAt: String?,
    val doseNumber: String?,
    val lotNumber: String?,
    val manufacturer: String?,
    val location: String?,
    val note: String?,
    val examinationId: String?,
    val createdAt: String?,
    val updatedAt: String?,
)

data class CachedClinicalEventRow(
    val id: String,
    val patientId: String?,
    val typeId: String?,
    val typeName: String?,
    val typeSlug: String?,
    val typeIcon: String?,
    val typeColor: String?,
    val status: String?,
    val title: String?,
    val description: String?,
    val onsetDate: String?,
    val resolvedDate: String?,
    val codingSystem: String?,
    val code: String?,
    val createdAt: String?,
    val updatedAt: String?,
)

/** SDK model ⇄ cache row. The JSON-field encode/decode is tolerant: a corrupt
 *  stored string decodes to null instead of crashing the read. */
object ClinicalRecordCacheMapper {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun toRow(m: Medication): CachedMedicationRow =
        CachedMedicationRow(
            id = m.id,
            status = m.status,
            intent = m.intent,
            codeJson = encodeMap(m.code),
            startDate = m.startDate,
            endDate = m.endDate,
            dosage = m.dosage,
            frequencyJson = encodeElement(m.frequency),
            reason = m.reason,
            note = m.note,
            examinationId = m.examinationId,
            createdAt = m.createdAt,
            updatedAt = m.updatedAt,
        )

    fun toModel(r: CachedMedicationRow): Medication =
        Medication(
            id = r.id,
            status = r.status,
            intent = r.intent,
            code = decodeMap(r.codeJson),
            startDate = r.startDate,
            endDate = r.endDate,
            dosage = r.dosage,
            frequency = decodeElement(r.frequencyJson),
            reason = r.reason,
            note = r.note,
            examinationId = r.examinationId,
            createdAt = r.createdAt,
            updatedAt = r.updatedAt,
        )

    fun toRow(a: Allergy): CachedAllergyRow =
        CachedAllergyRow(
            id = a.id,
            clinicalStatus = a.clinicalStatus,
            verificationStatus = a.verificationStatus,
            category = a.category,
            criticality = a.criticality,
            codeJson = encodeMap(a.code),
            onsetDate = a.onsetDate,
            resolvedDate = a.resolvedDate,
            lastOccurrence = a.lastOccurrence,
            note = a.note,
            reactionsJson = encodeElement(a.reactions),
            createdAt = a.createdAt,
            updatedAt = a.updatedAt,
        )

    fun toModel(r: CachedAllergyRow): Allergy =
        Allergy(
            id = r.id,
            clinicalStatus = r.clinicalStatus,
            verificationStatus = r.verificationStatus,
            category = r.category,
            criticality = r.criticality,
            code = decodeMap(r.codeJson),
            onsetDate = r.onsetDate,
            resolvedDate = r.resolvedDate,
            lastOccurrence = r.lastOccurrence,
            note = r.note,
            reactions = decodeElement(r.reactionsJson),
            createdAt = r.createdAt,
            updatedAt = r.updatedAt,
        )

    fun toRow(v: Vaccine): CachedVaccineRow =
        CachedVaccineRow(
            id = v.id,
            status = v.status,
            vaccineCodeJson = encodeMap(v.vaccineCode),
            administeredAt = v.administeredAt,
            doseNumber = v.doseNumber,
            lotNumber = v.lotNumber,
            manufacturer = v.manufacturer,
            location = v.location,
            note = v.note,
            examinationId = v.examinationId,
            createdAt = v.createdAt,
            updatedAt = v.updatedAt,
        )

    fun toModel(r: CachedVaccineRow): Vaccine =
        Vaccine(
            id = r.id,
            status = r.status,
            vaccineCode = decodeMap(r.vaccineCodeJson),
            administeredAt = r.administeredAt,
            doseNumber = r.doseNumber,
            lotNumber = r.lotNumber,
            manufacturer = r.manufacturer,
            location = r.location,
            note = r.note,
            examinationId = r.examinationId,
            createdAt = r.createdAt,
            updatedAt = r.updatedAt,
        )

    fun toRow(e: ClinicalEvent): CachedClinicalEventRow =
        CachedClinicalEventRow(
            id = e.id,
            patientId = e.patientId,
            typeId = e.typeId,
            typeName = e.typeName,
            typeSlug = e.typeSlug,
            typeIcon = e.typeIcon,
            typeColor = e.typeColor,
            status = e.status,
            title = e.title,
            description = e.description,
            onsetDate = e.onsetDate,
            resolvedDate = e.resolvedDate,
            codingSystem = e.codingSystem,
            code = e.code,
            createdAt = e.createdAt,
            updatedAt = e.updatedAt,
        )

    fun toModel(r: CachedClinicalEventRow): ClinicalEvent =
        ClinicalEvent(
            id = r.id,
            patientId = r.patientId,
            typeId = r.typeId,
            typeName = r.typeName,
            typeSlug = r.typeSlug,
            typeIcon = r.typeIcon,
            typeColor = r.typeColor,
            status = r.status,
            title = r.title,
            description = r.description,
            onsetDate = r.onsetDate,
            resolvedDate = r.resolvedDate,
            codingSystem = r.codingSystem,
            code = r.code,
            createdAt = r.createdAt,
            updatedAt = r.updatedAt,
        )

    private fun encodeMap(map: Map<String, JsonElement>): String? =
        if (map.isEmpty()) null else JsonObject(map).toString()

    private fun decodeMap(raw: String?): Map<String, JsonElement> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val element = decodeElement(raw) ?: return emptyMap()
        return (element as? JsonObject)?.toMap() ?: emptyMap()
    }

    private fun encodeElement(element: JsonElement?): String? = element?.toString()

    private fun decodeElement(raw: String?): JsonElement? =
        raw?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
}
