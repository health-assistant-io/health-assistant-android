package io.healthassistant.shared.data

import io.healthassistant.bridge.BridgeClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * Upload a document for an examination via the bridge
 * `POST /examinations/{id}/documents` (base64 JSON). Idempotent: pass a stable
 * [clientRequestId] and re-uploads after a network blip return the same row
 * (the backend dedups on `(tenant, patient, integration, external_id)`).
 *
 * Direct (online) upload — for v1. Offline-via-the-outbox (LARGE lane with a
 * content reference) is the planned enhancement; the contract is identical.
 */
object BridgeUploads {

    private val json = Json { encodeDefaults = false }

    /** @return the response body text (the `{id, external_id, filename, status, progress}` object). */
    suspend fun uploadDocument(
        client: BridgeClient,
        examId: String,
        filename: String,
        content: ByteArray,
        clientRequestId: String,
        contentType: String? = null,
        includeInExtraction: Boolean = false,
    ): String {
        val payload: JsonObject = buildJsonObject {
            put("id", clientRequestId)
            put("filename", filename)
            if (contentType != null) put("content_type", contentType)
            put("data", JsonPrimitive(Base64.getEncoder().encodeToString(content)))
            put("include_in_extraction", includeInExtraction)
        }
        val body = json.encodeToString(JsonObject.serializer(), payload).encodeToByteArray()
        return client.requestText("POST", "/examinations/$examId/documents", body)
    }
}
