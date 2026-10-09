package io.healthassistant.shared.data

import io.healthassistant.bridge.Allergy
import io.healthassistant.bridge.Medication
import io.healthassistant.bridge.Vaccine
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Phase H — JVM tests for the clinical-record display helpers. */
class ClinicalRecordDisplaysTest {
    @Test
    fun `medication display name comes from code text`() {
        val med = Medication(id = "m1", code = mapOf("text" to JsonPrimitive("Aspirin")))
        assertEquals("Aspirin", med.displayName)
    }

    @Test
    fun `medication display falls back to the FHIR code when no text`() {
        val med = Medication(id = "m1", code = mapOf("code" to JsonPrimitive("MS_ASPIRIN")))
        assertEquals("MS_ASPIRIN", med.displayName)
    }

    @Test
    fun `medication display is null for an empty code object`() {
        assertNull(Medication(id = "m1").displayName)
        assertNull(Medication(id = "m1", code = mapOf("catalog_id" to JsonPrimitive("abc"))).displayName)
    }

    @Test
    fun `blank text falls back to the FHIR code`() {
        val med = Medication(id = "m1", code = mapOf("text" to JsonPrimitive("  "), "code" to JsonPrimitive("X1")))
        assertEquals("X1", med.displayName)
    }

    @Test
    fun `allergy display name comes from code text`() {
        val allergy = Allergy(id = "a1", code = mapOf("text" to JsonPrimitive("Penicillin")))
        assertEquals("Penicillin", allergy.displayName)
    }

    @Test
    fun `vaccine display name comes from vaccine code text`() {
        val vaccine = Vaccine(id = "v1", vaccineCode = mapOf("text" to JsonPrimitive("COVID-19 mRNA")))
        assertEquals("COVID-19 mRNA", vaccine.displayName)
    }

    @Test
    fun `helpers work with buildJsonObject maps`() {
        val code = buildJsonObject { put("text", "Metformin") }
        assertEquals("Metformin", ClinicalRecordDisplays.codeText(code))
    }

    @Test
    fun `missing keys are null`() {
        assertNull(ClinicalRecordDisplays.codeText(emptyMap()))
        assertNull(ClinicalRecordDisplays.code(emptyMap()))
    }

    @Test
    fun `json null never surfaces as the literal string`() {
        // JsonNull IS a JsonPrimitive whose .content is "null" — the helpers
        // must treat it as absent (regression: med names rendered "null").
        assertNull(ClinicalRecordDisplays.codeText(mapOf("text" to JsonNull)))
        assertNull(ClinicalRecordDisplays.code(mapOf("code" to JsonNull)))
        val med = Medication(id = "m1", code = mapOf("text" to JsonNull))
        assertNull(med.displayName)
    }
}
