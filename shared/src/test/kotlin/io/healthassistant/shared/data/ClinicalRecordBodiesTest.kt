package io.healthassistant.shared.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase H — JVM tests for the clinical-record create bodies. These encode the
 * bridge create contract (`code: {"text": …}` required; status / dosage /
 * criticality optional) that the backend Pydantic schemas enforce, so getting
 * them right is what makes the native add dialogs work against the server.
 */
class ClinicalRecordBodiesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(body: ByteArray): JsonObject =
        json.decodeFromString(JsonObject.serializer(), body.decodeToString())

    @Test
    fun `medication body has code text and active status`() {
        val body = parse(ClinicalRecordBodies.medicationCreateBody("  Aspirin  ", "100 mg"))
        assertEquals("Aspirin", (body["code"] as JsonObject)["text"]?.jsonPrimitive?.content)
        assertEquals("ACTIVE", body["status"]?.jsonPrimitive?.content)
        assertEquals("100 mg", body["dosage"]?.jsonPrimitive?.content)
    }

    @Test
    fun `medication body omits dosage when blank`() {
        assertFalse(parse(ClinicalRecordBodies.medicationCreateBody("Aspirin", "   ")).containsKey("dosage"))
        assertFalse(parse(ClinicalRecordBodies.medicationCreateBody("Aspirin", null)).containsKey("dosage"))
        assertFalse(parse(ClinicalRecordBodies.medicationCreateBody("Aspirin", "")).containsKey("dosage"))
    }

    @Test
    fun `medication body trims the name`() {
        val body = parse(ClinicalRecordBodies.medicationCreateBody("   Metformin   ", null))
        assertEquals("Metformin", (body["code"] as JsonObject)["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `allergy body has code text and active clinical status`() {
        val body = parse(ClinicalRecordBodies.allergyCreateBody("Penicillin", "high"))
        assertEquals("Penicillin", (body["code"] as JsonObject)["text"]?.jsonPrimitive?.content)
        assertEquals("ACTIVE", body["clinical_status"]?.jsonPrimitive?.content)
        assertEquals("HIGH", body["criticality"]?.jsonPrimitive?.content)
    }

    @Test
    fun `allergy criticality is normalized case-insensitively`() {
        assertEquals("LOW", parse(ClinicalRecordBodies.allergyCreateBody("Latex", "low"))["criticality"]?.jsonPrimitive?.content)
        assertEquals("HIGH", parse(ClinicalRecordBodies.allergyCreateBody("Latex", "High"))["criticality"]?.jsonPrimitive?.content)
        assertEquals(
            "UNABLE_TO_ASSESS",
            parse(ClinicalRecordBodies.allergyCreateBody("Latex", "unable to assess"))["criticality"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `allergy body omits criticality when blank or unrecognized`() {
        assertFalse(parse(ClinicalRecordBodies.allergyCreateBody("Latex", null)).containsKey("criticality"))
        assertFalse(parse(ClinicalRecordBodies.allergyCreateBody("Latex", "  ")).containsKey("criticality"))
        // Unrecognized severities are dropped (never 422 on an unknown enum member).
        assertFalse(parse(ClinicalRecordBodies.allergyCreateBody("Latex", "moderate")).containsKey("criticality"))
    }

    @Test
    fun `bodies carry exactly the expected keys`() {
        val med = parse(ClinicalRecordBodies.medicationCreateBody("Aspirin", "100 mg"))
        assertEquals(setOf("code", "status", "dosage"), med.keys)
        val allergy = parse(ClinicalRecordBodies.allergyCreateBody("Peanuts", null))
        assertEquals(setOf("code", "clinical_status"), allergy.keys)
    }

    @Test
    fun `names with quotes and special characters are valid JSON`() {
        val body = parse(ClinicalRecordBodies.medicationCreateBody("Tylenol \"PM\" \\ Extra", null))
        assertEquals("Tylenol \"PM\" \\ Extra", (body["code"] as JsonObject)["text"]?.jsonPrimitive?.content)
    }
}
