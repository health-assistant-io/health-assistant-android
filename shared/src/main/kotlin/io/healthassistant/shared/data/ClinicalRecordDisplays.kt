package io.healthassistant.shared.data

import io.healthassistant.bridge.Allergy
import io.healthassistant.bridge.Medication
import io.healthassistant.bridge.Vaccine
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Phase H — display helpers for the SDK's clinical-record models. The FHIR
 * `code` / `vaccineCode` fields are JSON objects (`{"text": "Aspirin",
 * "catalog_id": "…"}`); these extract the human-readable `text` (and, for
 * vaccines, the code itself) so the Compose list rows can render a name +
 * subtitle without touching raw JSON.
 *
 * Pure-Kotlin + JVM-tested (no Android deps) so the More-tab screens and the
 * Home allergies card share one source of truth.
 */
object ClinicalRecordDisplays {
    /** The `text` member of a FHIR CodeableConcept-like JSON object.
     *  JSON-null-safe: `JsonNull` is a [JsonPrimitive] whose content is the
     *  literal "null" — it must not surface as a display name. */
    fun codeText(code: Map<String, JsonElement>): String? = primitive(code["text"])

    /** The FHIR code itself (e.g. the LOINC/SNOMED identifier). */
    fun code(code: Map<String, JsonElement>): String? = primitive(code["code"])

    private fun primitive(el: JsonElement?): String? {
        if (el == null || el is JsonNull) return null
        return (el as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    }
}

/** Best display name for a medication — the code text, else the FHIR code. */
val Medication.displayName: String?
    get() = ClinicalRecordDisplays.codeText(code) ?: ClinicalRecordDisplays.code(code)

/** Best display name for an allergy — the code text, else the FHIR code. */
val Allergy.displayName: String?
    get() = ClinicalRecordDisplays.codeText(code) ?: ClinicalRecordDisplays.code(code)

/** Best display name for a vaccine — the vaccine_code text, else its code. */
val Vaccine.displayName: String?
    get() = ClinicalRecordDisplays.codeText(vaccineCode) ?: ClinicalRecordDisplays.code(vaccineCode)
