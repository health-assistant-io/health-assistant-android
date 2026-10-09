package io.healthassistant.android.data.cache

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Migration gate for v8 → v9 (offline-first M9, plan §6): the rebuild that
 * scopes every cache table by `connection_id` must preserve every pre-migration
 * row, backfill the id of the ACTIVE connection, keep PK dedupe semantics, and
 * create the `cache_meta` staleness table — against the real SQLite, validated
 * by `MigrationTestHelper` against the exported v8/v9 schemas (no
 * hand-rolled DDL trust). `fallbackToDestructiveMigration` stays off; a failed
 * migration must never wipe health data.
 */
@RunWith(RobolectricTestRunner::class)
class CacheMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), ObservationDatabase::class.java)

    @Test
    fun migrate_8_to_9_backfills_the_active_connection_and_preserves_rows() =
        runTest {
            val db = helper.createDatabase(DB_NAME, 8)
            db.execSQL(
                """
                INSERT INTO observation_cache (
                    id, biomarker_code, effective_datetime, effective_epoch_ms, raw_value,
                    normalized_value, normalized_unit, value_string, code_text, coding_display,
                    coding_system, reference_range_low, reference_range_high, biomarker_id,
                    biomarker_slug, biomarker_value_type, interpretation, relative_score,
                    biomarker_reference_range_min, biomarker_reference_range_max
                ) VALUES ('o1', '8867-4', '2026-08-14T08:00:00Z', 1755164800000, 70.0,
                          70.0, 'bpm', NULL, 'Heart rate', 'Heart rate', 'http://loinc.org',
                          NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO biomarker_cache (
                    id, name, slug, code, coding_system, unit, is_telemetry,
                    reference_range_min, reference_range_max, value_type, info
                ) VALUES ('b1', 'Heart rate', 'heart-rate', '8867-4', 'http://loinc.org', 'bpm', 1, 60.0, 100.0, 'numeric', NULL)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO examination_cache (
                    id, examination_date, notes, patient_notes, extraction_status,
                    diagnoses, impressions, category, lab_name, external_id
                ) VALUES ('e1', '2026-08-01', 'note', NULL, 'processed', NULL, NULL, NULL, NULL, NULL)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO document_cache (
                    id, filename, status, progress, external_id, created_at, content_type,
                    file_size, examination_id, local_path, local_cached_at
                ) VALUES ('d1', 'lab.pdf', 'processed', NULL, NULL, '2026-08-01T00:00:00Z', 'application/pdf', 10, 'e1', NULL, NULL)
                """.trimIndent(),
            )
            db.execSQL(
                "INSERT INTO medication_cache (id, status, intent, codeJson, startDate, endDate, dosage, frequencyJson, reason, note, examinationId, createdAt, updatedAt) VALUES ('m1', 'ACTIVE', 'PLAN', NULL, '2026-01-01', NULL, '1 pill', NULL, NULL, NULL, NULL, NULL, NULL)",
            )
            db.execSQL(
                "INSERT INTO allergy_cache (id, clinicalStatus, verificationStatus, category, criticality, codeJson, onsetDate, resolvedDate, lastOccurrence, note, reactionsJson, createdAt, updatedAt) VALUES ('a1', 'ACTIVE', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)",
            )
            db.execSQL(
                "INSERT INTO vaccine_cache (id, status, vaccineCodeJson, administeredAt, doseNumber, lotNumber, manufacturer, location, note, examinationId, createdAt, updatedAt) VALUES ('v1', 'COMPLETED', NULL, '2025-01-01', '1', NULL, NULL, NULL, NULL, NULL, NULL, NULL)",
            )
            db.execSQL(
                "INSERT INTO clinical_event_cache (id, patientId, typeId, typeName, typeSlug, typeIcon, typeColor, status, title, description, onsetDate, resolvedDate, codingSystem, code, createdAt, updatedAt) VALUES ('c1', NULL, NULL, NULL, NULL, NULL, NULL, 'ACTIVE', 'Flu', NULL, '2026-02-01', NULL, NULL, NULL, NULL, NULL)",
            )
            db.execSQL(
                "INSERT INTO notification_cache (recipientId, status, readAt, dismissedAt, notificationId, title, body, type, category, severity, source, payloadJson, patientId, createdAt) VALUES ('r1', 'unread', NULL, NULL, 'n1', 'Hi', NULL, NULL, NULL, NULL, NULL, NULL, NULL, '2026-08-01T00:00:00Z')",
            )
            db.close()

            helper.runMigrationsAndValidate(DB_NAME, 9, true, CacheMigrations.migration8to9(ACTIVE))

            val context = ApplicationProvider.getApplicationContext<Context>()
            val room =
                Room
                    .databaseBuilder(context, ObservationDatabase::class.java, DB_NAME)
                    .allowMainThreadQueries()
                    .build()
            try {
                assertEquals(1, room.cacheDao().rowCount(ACTIVE))
                assertEquals(
                    "8867-4",
                    room
                        .cacheDao()
                        .latestForCode(ACTIVE, "8867-4")
                        .first()
                        ?.biomarkerCode,
                )
                assertEquals(1, room.biomarkerDao().rowCount(ACTIVE))
                assertEquals(1, room.examinationDao().rowCount(ACTIVE))
                assertEquals(1, room.documentDao().rowCount(ACTIVE))
                assertEquals(1, room.clinicalRecordDao().medicationCount(ACTIVE))
                assertEquals(1, room.clinicalRecordDao().allergyCount(ACTIVE))
                assertEquals(1, room.clinicalRecordDao().vaccineCount(ACTIVE))
                assertEquals(1, room.clinicalRecordDao().clinicalEventCount(ACTIVE))
                assertEquals(1, room.notificationDao().rowCount(ACTIVE))
                assertEquals("no other connection may own the backfilled rows", 0, room.cacheDao().rowCount("someone-else"))

                room.cacheMetaDao().recordSuccess(ACTIVE, "observations", 42L, 1)
                val meta = room.cacheMetaDao().get(ACTIVE, "observations")
                assertEquals("cache_meta must exist with the plan's shape", 42L, meta?.lastSuccessAtEpochMs)
            } finally {
                room.close()
            }
        }

    private companion object {
        const val DB_NAME = "migration-test.db"
        const val ACTIVE = "active-integration"
    }
}
