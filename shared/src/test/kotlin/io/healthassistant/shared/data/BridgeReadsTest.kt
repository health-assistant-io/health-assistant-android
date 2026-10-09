package io.healthassistant.shared.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeReadsTest {

    @Test
    fun `decodes examinations envelope`() {
        val text = """
            {"data":[
              {"id":"abc","examination_date":"2026-08-08","notes":"Annual","extraction_status":"completed","diagnoses":["Hypertension"]},
              {"id":"def","examination_date":"2026-07-01","notes":"Follow-up"}
            ],"cursor":null,"cached_at":"2026-08-08T12:00:00Z"}
        """.trimIndent()

        val exams = BridgeReads.decodeEnvelope(text, ExaminationSummary.serializer())

        assertEquals(2, exams.size)
        assertEquals("abc", exams[0].id)
        assertEquals("Annual", exams[0].notes)
        assertEquals(listOf("Hypertension"), exams[0].diagnoses)
        assertEquals("completed", exams[0].extractionStatus)
        assertEquals("Follow-up", exams[1].notes)
    }

    @Test
    fun `empty data list is fine`() {
        val exams = BridgeReads.decodeEnvelope("""{"data":[],"cursor":null}""", ExaminationSummary.serializer())
        assertEquals(0, exams.size)
    }

    @Test
    fun `decodes observation time-series envelope`() {
        val text = """
            {"data":[
              {"id":"o1","effective_datetime":"2026-08-10T09:00:00Z","raw_value":72.0,"normalized_value":72.0,"normalized_unit":"beats/min"},
              {"id":"o2","effective_datetime":"2026-08-10T10:00:00Z","raw_value":68.0,"normalized_value":68.0,"normalized_unit":"beats/min"}
            ],"cursor":null,"cached_at":"2026-08-10T12:00:00Z"}
        """.trimIndent()

        val points = BridgeReads.decodeEnvelope(text, ObservationPoint.serializer())

        assertEquals(2, points.size)
        assertEquals("o1", points[0].id)
        assertEquals("2026-08-10T09:00:00Z", points[0].effectiveDatetime)
        assertEquals(72.0, points[0].chartValue)
        assertEquals("beats/min", points[0].normalizedUnit)
    }

    @Test
    fun `observation without raw value falls back to normalized`() {
        val text = """{"data":[{"id":"o1","effective_datetime":"2026-08-10T09:00:00Z","normalized_value":5.5}]}"""

        val points = BridgeReads.decodeEnvelope(text, ObservationPoint.serializer())

        assertEquals(1, points.size)
        assertEquals(5.5, points[0].chartValue)
    }

    @Test
    fun `latest observation exposes its biomarker code`() {
        val text = """
            {"data":[
              {"id":"o1","code":{"coding":[{"system":"http://loinc.org","code":"8867-4"}]},"effective_datetime":"2026-08-10T09:00:00Z","raw_value":72.0,"normalized_unit":"beats/min"},
              {"id":"o2","code":{"coding":[{"system":"custom","code":"sleep-duration"}]},"effective_datetime":"2026-08-10T01:00:00Z","raw_value":420.0,"normalized_unit":"min"}
            ],"cursor":null}
        """.trimIndent()

        val points = BridgeReads.decodeEnvelope(text, ObservationPoint.serializer())

        assertEquals("8867-4", points[0].primaryCode)
        assertEquals("sleep-duration", points[1].primaryCode)
        assertEquals(72.0, points[0].chartValue)
    }

    @Test
    fun `decodes reference range flat shape`() {
        val text = """
            {"data":[{"id":"o1","effective_datetime":"2026-08-10T09:00:00Z","raw_value":120.0,"reference_range":{"low":110.0,"high":150.0}}]}
        """.trimIndent()

        val points = BridgeReads.decodeEnvelope(text, ObservationPoint.serializer())

        assertEquals(110.0, points[0].referenceRange?.low)
        assertEquals(150.0, points[0].referenceRange?.high)
    }

    @Test
    fun `tolerates FHIR list reference range`() {
        val text = """
            {"data":[{"id":"o1","effective_datetime":"2026-08-10T09:00:00Z","raw_value":120.0,"reference_range":[{"low":{"value":110.0,"unit":"mmol/L"},"high":{"value":150.0,"unit":"mmol/L"}}]}]}
        """.trimIndent()

        val points = BridgeReads.decodeEnvelope(text, ObservationPoint.serializer())

        assertEquals(110.0, points[0].referenceRange?.low)
        assertEquals(150.0, points[0].referenceRange?.high)
    }

    @Test
    fun `missing reference range is null`() {
        val text = """{"data":[{"id":"o1","raw_value":1.0}]}"""

        val points = BridgeReads.decodeEnvelope(text, ObservationPoint.serializer())

        assertNull(points[0].referenceRange)
        assertFalse(points[0].range?.present ?: false)
    }

    @Test
    fun `reference range falls back to biomarker definition min max`() {
        val text = """
            {"data":[{"id":"o1","raw_value":120.0,"biomarker_reference_range_min":70.0,"biomarker_reference_range_max":100.0}]}
        """.trimIndent()

        val points = BridgeReads.decodeEnvelope(text, ObservationPoint.serializer())

        assertEquals(70.0, points[0].range?.low)
        assertEquals(100.0, points[0].range?.high)
        assertTrue(points[0].range?.present ?: false)
    }

    @Test
    fun `decodes biomarker catalog envelope`() {
        val text = """
            {"data":[
              {"id":"b1","name":"Heart Rate","slug":"heart-rate","code":"8867-4","coding_system":"loinc","unit":"beats/min","is_telemetry":true,"reference_range_min":60.0,"reference_range_max":100.0,"value_type":"quantity"},
              {"id":"b2","name":"Fasting Glucose","slug":"glucose-fasting","code":"2345-7","coding_system":"loinc","unit":"mmol/L","is_telemetry":false}
            ],"cursor":null,"cached_at":"2026-08-10T12:00:00Z"}
        """.trimIndent()

        val biomarkers = BridgeReads.decodeEnvelope(text, BiomarkerSummary.serializer())

        assertEquals(2, biomarkers.size)
        assertEquals("Heart Rate", biomarkers[0].name)
        assertEquals("8867-4", biomarkers[0].code)
        assertTrue(biomarkers[0].isTelemetry)
        assertEquals(60.0, biomarkers[0].referenceRange?.low)
        assertEquals("beats/min", biomarkers[0].unit)
        assertFalse(biomarkers[1].isTelemetry)
        assertEquals("quantity", biomarkers[0].valueType)
    }

    @Test
    fun `observation display name prefers code text then slug`() {
        val text = """
            {"data":[
              {"id":"o1","code":{"text":"Heart Rate","coding":[{"system":"http://loinc.org","code":"8867-4"}]},"raw_value":72.0},
              {"id":"o2","biomarker_slug":"cholesterol-total","raw_value":5.0}
            ]}
        """.trimIndent()

        val points = BridgeReads.decodeEnvelope(text, ObservationPoint.serializer())

        assertEquals("Heart Rate", points[0].displayName)
        assertEquals("cholesterol-total", points[1].displayName)
    }
}
