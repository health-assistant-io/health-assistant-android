package io.healthassistant.shared.data.repository

import io.healthassistant.shared.data.ExaminationSummary
import io.healthassistant.shared.data.cache.CachedAllergyRow
import io.healthassistant.shared.data.cache.CachedDocumentRow
import io.healthassistant.shared.data.cache.CachedMedicationRow
import io.healthassistant.shared.data.cache.DeltaRows
import io.healthassistant.shared.sync.ChangesDelta
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for the `/changes` delta decode + merge semantics (offline-first
 * M7): delta fields win, omitted fields keep their cached value, sparse rows
 * insert when nothing is cached, and the document byte-manifest columns always
 * survive.
 */
class DeltaHydrationTest {
    @Test
    fun `medication delta updates carried fields and preserves omitted ones`() {
        val existing =
            CachedMedicationRow(
                id = "m1", status = "ACTIVE", intent = "PLAN", codeJson = """{"text":"Old","catalog_id":"c9"}""",
                startDate = "2026-01-01", endDate = null, dosage = "1 pill", frequencyJson = null,
                reason = " deficiency", note = "with food", examinationId = null,
                createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z",
            )
        val delta =
            ChangesDelta(
                medications =
                    listOf(
                        jsonOf(
                            "id" to "m1",
                            "updated_at" to "2026-08-14T10:00:00Z",
                            "status" to "STOPPED",
                            "code_text" to "Vitamin D 2000",
                            "start_date" to "2026-02-02",
                        ),
                    ),
            )

        val merged = DeltaRows.merge(existing, DeltaRows.medications(delta).single())

        assertEquals("STOPPED", merged.status)
        assertEquals("2026-02-02", merged.startDate)
        assertEquals("2026-08-14T10:00:00Z", merged.updatedAt)
        val codeJson = kotlinx.serialization.json.Json.parseToJsonElement(merged.codeJson!!).let { it as kotlinx.serialization.json.JsonObject }
        assertEquals("the delta text replaces only the text key", "Vitamin D 2000", (codeJson["text"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("c9", (codeJson["catalog_id"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("omitted fields keep their cached value", "1 pill", merged.dosage)
        assertEquals("PLAN", merged.intent)
        assertEquals("with food", merged.note)
    }

    @Test
    fun `medication delta inserts a sparse row when nothing is cached`() {
        val delta =
            ChangesDelta(
                medications =
                    listOf(
                        jsonOf(
                            "id" to "m9",
                            "updated_at" to "2026-08-14T10:00:00Z",
                            "status" to "ACTIVE",
                            "code_text" to "Metformin",
                            "start_date" to "2026-08-01",
                        ),
                    ),
            )

        val merged = DeltaRows.merge(null, DeltaRows.medications(delta).single())

        assertEquals("m9", merged.id)
        assertEquals("ACTIVE", merged.status)
        assertEquals("""{"text":"Metformin"}""", merged.codeJson)
        assertNull("omitted fields are null on a sparse insert", merged.dosage)
    }

    @Test
    fun `null delta values keep the cached value`() {
        val existing =
            CachedAllergyRow(
                id = "a1", clinicalStatus = "ACTIVE", verificationStatus = null, category = null,
                criticality = "HIGH", codeJson = """{"text":"Peanuts"}""", onsetDate = "2025-01-01",
                resolvedDate = null, lastOccurrence = null, note = "epipen", reactionsJson = null,
                createdAt = null, updatedAt = null,
            )
        val delta =
            ChangesDelta(
                allergies =
                    listOf(
                        jsonOf(
                            "id" to "a1",
                            "updated_at" to "2026-08-14T10:00:00Z",
                            "clinical_status" to "INACTIVE",
                            "code_text" to null,
                        ),
                    ),
            )

        val merged = DeltaRows.merge(existing, DeltaRows.allergies(delta).single())

        assertEquals("INACTIVE", merged.clinicalStatus)
        assertEquals("a null delta text keeps the cached code object", """{"text":"Peanuts"}""", merged.codeJson)
        assertEquals("HIGH", merged.criticality)
    }

    @Test
    fun `examination delta merges date + status and preserves notes`() {
        val existing =
            ExaminationSummary(
                id = "e1", examinationDate = "2026-03-01", notes = "annual", patientNotes = "ok",
                extractionStatus = "pending",
            )
        val delta =
            ChangesDelta(
                examinations =
                    listOf(
                        jsonOf(
                            "id" to "e1",
                            "updated_at" to "2026-08-14T10:00:00Z",
                            "examination_date" to "2026-03-18",
                            "extraction_status" to "processed",
                        ),
                    ),
            )

        val merged = DeltaRows.merge(existing, DeltaRows.examinations(delta).single())

        assertEquals("2026-03-18", merged.examinationDate)
        assertEquals("processed", merged.extractionStatus)
        assertEquals("annual", merged.notes)
        assertEquals("ok", merged.patientNotes)
    }

    @Test
    fun `document delta preserves the byte-manifest columns`() {
        val existing =
            CachedDocumentRow(
                id = "d1", filename = "old.pdf", status = "uploaded", progress = null,
                externalId = null, createdAt = "2026-03-18T00:00:00Z", contentType = "application/pdf",
                fileSize = 31975L, examinationId = "e1", localPath = "d1", localCachedAt = 123L,
            )
        val delta =
            ChangesDelta(
                documents =
                    listOf(
                        jsonOf(
                            "id" to "d1",
                            "updated_at" to "2026-08-14T10:00:00Z",
                            "filename" to "new-name.pdf",
                            "status" to "extracted",
                            "examination_id" to "e1",
                        ),
                    ),
            )

        val merged = DeltaRows.merge(existing, DeltaRows.documents(delta).single())

        assertEquals("new-name.pdf", merged.filename)
        assertEquals("extracted", merged.status)
        assertEquals("the manifest columns are never in the delta and must survive", "d1", merged.localPath)
        assertEquals(123L, merged.localCachedAt)
        assertEquals(31975L, merged.fileSize)
    }

    @Test
    fun `vaccine and clinical-event deltas merge their carried fields`() {
        val existingVaccine =
            io.healthassistant.shared.data.cache.CachedVaccineRow(
                id = "v1", status = "in_progress", vaccineCodeJson = """{"text":"COVID-19"}""",
                administeredAt = "2026-07-30", doseNumber = "2", lotNumber = null, manufacturer = null,
                location = null, note = null, examinationId = null, createdAt = null, updatedAt = null,
            )
        val vaccine =
            DeltaRows.merge(
                existingVaccine,
                DeltaRows
                    .vaccines(
                        ChangesDelta(
                            vaccines =
                                listOf(
                                    jsonOf(
                                        "id" to "v1",
                                        "updated_at" to "2026-08-14T10:00:00Z",
                                        "status" to "completed",
                                        "administered_at" to "2026-08-01",
                                    ),
                                ),
                        ),
                    ).single(),
            )
        assertEquals("completed", vaccine.status)
        assertEquals("2026-08-01", vaccine.administeredAt)
        assertEquals("omitted field preserved", """{"text":"COVID-19"}""", vaccine.vaccineCodeJson)

        val existingEvent =
            io.healthassistant.shared.data.cache.CachedClinicalEventRow(
                id = "ev1", patientId = null, typeId = null, typeName = "Illness", typeSlug = null,
                typeIcon = null, typeColor = null, status = "active", title = "Flu", description = null,
                onsetDate = "2026-01-01", resolvedDate = null, codingSystem = null, code = null,
                createdAt = null, updatedAt = null,
            )
        val event =
            DeltaRows.merge(
                existingEvent,
                DeltaRows
                    .clinicalEvents(
                        ChangesDelta(
                            clinicalEvents =
                                listOf(
                                    jsonOf(
                                        "id" to "ev1",
                                        "updated_at" to "2026-08-14T10:00:00Z",
                                        "status" to "resolved",
                                        "title" to "Flu (resolved)",
                                        "onset_date" to null,
                                    ),
                                ),
                        ),
                    ).single(),
            )
        assertEquals("resolved", event.status)
        assertEquals("Flu (resolved)", event.title)
        assertEquals("null delta keeps cached onset", "2026-01-01", event.onsetDate)
    }

    private fun jsonOf(vararg pairs: Pair<String, String?>): JsonObject =
        buildJsonObject {
            pairs.forEach { (k, v) ->
                if (v != null) put(k, v) else put(k, JsonNull)
            }
        }
}
