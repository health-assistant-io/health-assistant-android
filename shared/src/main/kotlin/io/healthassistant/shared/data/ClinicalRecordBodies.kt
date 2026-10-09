package io.healthassistant.shared.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Phase H — request bodies for the clinical-record create paths (`POST
 * /medications`, `POST /allergies`). The bridge create handlers take a free-form
 * JSON body; the Pydantic schemas only require `code: {"text": name}` and default
 * the rest. **Enum fields are UPPERCASE** (`MedicationStatus.ACTIVE`,
 * `AllergyClinicalStatus.ACTIVE`, `AllergyCriticality.HIGH` — see
 * `core/backend/app/models/enums.py`), which is the #1 cause of 422s from a
 * mobile client. Built in `shared` so the wire contract is JVM-testable and the
 * :app module never touches kotlinx-serialization-json directly.
 *
 * [medicationCreateBody] and [allergyCreateBody] return the exact bytes to send
 * as the request body (utf-8 encoded JSON).
 */
object ClinicalRecordBodies {
    private val json = Json { encodeDefaults = false }

    /** `POST /medications` body: `{code: {text}, status: ACTIVE, dosage?}`. */
    fun medicationCreateBody(
        name: String,
        dosage: String?,
    ): ByteArray =
        encode(
            buildJsonObject {
                put("code", buildJsonObject { put("text", name.trim()) })
                dosage?.takeIf { it.isNotBlank() }?.let { put("dosage", it.trim()) }
                put("status", "ACTIVE")
            },
        )

    /** `POST /allergies` body: `{code: {text}, clinical_status: ACTIVE, criticality?}`. */
    fun allergyCreateBody(
        name: String,
        criticality: String?,
    ): ByteArray =
        encode(
            buildJsonObject {
                put("code", buildJsonObject { put("text", name.trim()) })
                criticality?.takeIf { it.isNotBlank() }?.let { raw ->
                    normalizeCriticality(raw)?.let { put("criticality", it) }
                }
                put("clinical_status", "ACTIVE")
            },
        )

    /**
     * Map a user's free-text severity to the valid `AllergyCriticality` enum
     * values (case-insensitive, spaces → underscores). Anything that doesn't
     * map is dropped so the request never 422s on an unknown enum member.
     */
    private fun normalizeCriticality(raw: String): String? =
        when (raw.trim().uppercase().replace(' ', '_')) {
            "LOW", "HIGH", "UNABLE_TO_ASSESS" -> raw.trim().uppercase().replace(' ', '_')
            else -> null
        }

    private fun encode(obj: JsonObject): ByteArray =
        json.encodeToString(JsonObject.serializer(), obj).encodeToByteArray()
}
