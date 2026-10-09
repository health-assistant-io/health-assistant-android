package io.healthassistant.android.data.cache

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.healthassistant.shared.data.DocumentSummary
import io.healthassistant.shared.data.ExaminationSummary
import io.healthassistant.shared.data.cache.CacheDomain
import io.healthassistant.shared.data.cache.CacheRefreshMeta
import io.healthassistant.shared.data.cache.CachedAllergyRow
import io.healthassistant.shared.data.cache.CachedClinicalEventRow
import io.healthassistant.shared.data.cache.CachedMedicationRow
import io.healthassistant.shared.data.cache.CachedVaccineRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The multi-connection leakage gate (offline-first M9, plan §10): two
 * [RoomCaches] bundles — one per connection id — over ONE Room file must never
 * surface each other's rows. This is the "wrong patient's data" risk the
 * milestone exists to close: same row ids, reconciles, clears, and staleness
 * meta on one connection must leave the other connection untouched.
 */
@RunWith(RobolectricTestRunner::class)
class ConnectionScopingTest {
    private lateinit var db: ObservationDatabase
    private lateinit var a: RoomCaches
    private lateinit var b: RoomCaches

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ObservationDatabase::class.java).allowMainThreadQueries().build()
        a = RoomCaches(db, CONN_A)
        b = RoomCaches(db, CONN_B)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun observations_are_isolated_per_connection() =
        runTest {
            a.observations.store(listOf(point("shared-id", epochMs = 100)))
            b.observations.store(listOf(point("shared-id", epochMs = 999)))

            assertEquals(
                listOf(100L),
                a.observations.seriesFor("8867-4").first().map {
                    it.effectiveDatetime!!.let(java.time.Instant::parse).toEpochMilli()
                },
            )
            assertEquals(
                listOf(999L),
                b.observations.seriesFor("8867-4").first().map {
                    it.effectiveDatetime!!.let(java.time.Instant::parse).toEpochMilli()
                },
            )
        }

    @Test
    fun biomarker_catalog_swap_is_isolated_per_connection() =
        runTest {
            a.biomarkers.replaceAll(
                listOf(summary("b1", "A")),
            )
            b.biomarkers.replaceAll(listOf(summary("b1", "B")))

            assertEquals(
                "A",
                a.biomarkers
                    .observeAll()
                    .first()
                    .single()
                    .name,
            )
            assertEquals(
                "B",
                b.biomarkers
                    .observeAll()
                    .first()
                    .single()
                    .name,
            )
        }

    @Test
    fun examination_reconcile_on_one_connection_keeps_the_other_connections_rows() =
        runTest {
            a.examinations.storeAll(listOf(examRow("e1"), examRow("e2")))
            b.examinations.storeAll(listOf(examRow("e1"), examRow("e3")))

            a.examinations.reconcile(listOf("e1"))

            assertEquals(
                listOf("e1"),
                a.examinations
                    .observeAll()
                    .first()
                    .map { it.id },
            )
            assertEquals(
                "the other connection's rows must survive a foreign reconcile",
                listOf(
                    "e1",
                    "e3",
                ),
                b.examinations
                    .observeAll()
                    .first()
                    .map {
                        it.id
                    }.sorted(),
            )
        }

    @Test
    fun document_reconcile_and_delete_are_isolated() =
        runTest {
            a.documents.storeAll(listOf(docRow("d1", "exam-1"), docRow("d2", "exam-1")))
            b.documents.storeAll(listOf(docRow("d1", "exam-1")))

            a.documents.reconcileForExam("exam-1", listOf("d1"))
            a.documents.delete("d1")

            assertEquals(
                0,
                a.documents
                    .observeForExam("exam-1")
                    .first()
                    .size,
            )
            assertEquals(
                "same id on the other connection must survive the delete",
                listOf(
                    "d1",
                ),
                b.documents.observeForExam("exam-1").first().map {
                    it.id
                },
            )
        }

    @Test
    fun clinical_record_reconcile_is_isolated_per_family() =
        runTest {
            a.records.storeMedications(listOf(med("m1")))
            b.records.storeMedications(listOf(med("m1"), med("m2")))

            a.records.reconcileMedications(emptyList())

            assertEquals(
                0,
                a.records
                    .observeMedications()
                    .first()
                    .size,
            )
            assertEquals(
                2,
                b.records
                    .observeMedications()
                    .first()
                    .size,
            )

            a.records.storeAllergies(listOf(CachedAllergyRow("a1", null, null, null, null, null, null, null, null, null, null, null, null)))
            b.records.storeAllergies(listOf(CachedAllergyRow("a1", null, null, null, null, null, null, null, null, null, null, null, null)))
            a.records.reconcileAllergies(emptyList())
            assertEquals(
                0,
                a.records
                    .observeAllergies()
                    .first()
                    .size,
            )
            assertEquals(
                1,
                b.records
                    .observeAllergies()
                    .first()
                    .size,
            )

            a.records.storeVaccines(listOf(CachedVaccineRow("v1", null, null, null, null, null, null, null, null, null, null, null)))
            b.records.storeVaccines(listOf(CachedVaccineRow("v1", null, null, null, null, null, null, null, null, null, null, null)))
            a.records.reconcileVaccines(emptyList())
            assertEquals(
                0,
                a.records
                    .observeVaccines()
                    .first()
                    .size,
            )
            assertEquals(
                1,
                b.records
                    .observeVaccines()
                    .first()
                    .size,
            )

            a.records.storeClinicalEvents(
                listOf(
                    CachedClinicalEventRow("c1", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null),
                ),
            )
            b.records.storeClinicalEvents(
                listOf(
                    CachedClinicalEventRow("c1", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null),
                ),
            )
            a.records.reconcileClinicalEvents(emptyList())
            assertEquals(
                0,
                a.records
                    .observeClinicalEvents()
                    .first()
                    .size,
            )
            assertEquals(
                1,
                b.records
                    .observeClinicalEvents()
                    .first()
                    .size,
            )
        }

    @Test
    fun notification_inbox_mark_ops_are_isolated() =
        runTest {
            a.notifications.storeAll(listOf(notif("r1")))
            b.notifications.storeAll(listOf(notif("r1")))

            a.notifications.markAllRead()

            assertEquals(0, a.notifications.observeUnreadCount().first())
            assertEquals("the other connection's badge must be untouched", 1, b.notifications.observeUnreadCount().first())
        }

    @Test
    fun synced_writes_record_meta_only_for_their_connection() =
        runTest {
            a.examinations.storeAllSynced(listOf(examRow("e1")), CacheRefreshMeta.success(CacheDomain.EXAMINATIONS, atEpochMs = 10L))
            b.examinations.storeAllSynced(listOf(examRow("e9")), CacheRefreshMeta.success(CacheDomain.EXAMINATIONS, atEpochMs = 20L))
            a.meta.recordRefresh(CacheRefreshMeta.failure(CacheDomain.MEDICATIONS, "boom"))

            assertEquals(
                10L,
                a.meta
                    .observe(CacheDomain.EXAMINATIONS)
                    .first()
                    ?.lastSuccessAtEpochMs,
            )
            assertEquals(
                1,
                a.meta
                    .observe(CacheDomain.EXAMINATIONS)
                    .first()
                    ?.rowCount,
            )
            assertEquals(
                20L,
                b.meta
                    .observe(CacheDomain.EXAMINATIONS)
                    .first()
                    ?.lastSuccessAtEpochMs,
            )
            assertEquals(
                "boom",
                a.meta
                    .observe(CacheDomain.MEDICATIONS)
                    .first()
                    ?.lastError,
            )
            assertNull(
                "a failure keeps the last success",
                a.meta.observe(CacheDomain.MEDICATIONS).first()?.lastSuccessAtEpochMs?.takeIf {
                    it !=
                        0L
                },
            )
            assertNull(b.meta.observe(CacheDomain.MEDICATIONS).first())
        }

    @Test
    fun clear_on_one_connection_leaves_the_other_untouched() =
        runTest {
            a.examinations.storeAll(listOf(examRow("e1")))
            b.examinations.storeAll(listOf(examRow("e1")))
            a.meta.recordRefresh(CacheRefreshMeta.success(CacheDomain.EXAMINATIONS, 5L))
            b.meta.recordRefresh(CacheRefreshMeta.success(CacheDomain.EXAMINATIONS, 6L))

            a.examinations.clear()
            a.meta.clearDomain(CacheDomain.EXAMINATIONS)

            assertEquals(
                0,
                a.examinations
                    .observeAll()
                    .first()
                    .size,
            )
            assertEquals(
                1,
                b.examinations
                    .observeAll()
                    .first()
                    .size,
            )
            assertNull(a.meta.observe(CacheDomain.EXAMINATIONS).first())
            assertTrue(b.meta.observe(CacheDomain.EXAMINATIONS).first() != null)
        }

    private fun point(
        id: String,
        epochMs: Long,
    ): io.healthassistant.shared.data.ObservationPoint =
        io.healthassistant.shared.data.ObservationPoint(
            id = id,
            effectiveDatetime =
                java.time.Instant
                    .ofEpochMilli(epochMs)
                    .toString(),
            rawValue = 70.0,
            code =
                io.healthassistant.shared.data.ObservationCode(
                    coding =
                        listOf(
                            io.healthassistant.shared.data.ObservationCode
                                .Coding(code = "8867-4"),
                        ),
                ),
        )

    private fun summary(
        id: String,
        name: String,
    ): io.healthassistant.shared.data.BiomarkerSummary =
        io.healthassistant.shared.data.BiomarkerSummary(
            id = id,
            name = name,
            slug = null,
            code = "8867-4",
            codingSystem = null,
            unit = null,
            isTelemetry = false,
            referenceRangeMin = null,
            referenceRangeMax = null,
            valueType = null,
            info = null,
        )

    private fun examRow(id: String) = ExaminationSummary(id = id)

    private fun docRow(
        id: String,
        examId: String,
    ) = DocumentSummary(id = id, examinationId = examId)

    private fun med(id: String) =
        CachedMedicationRow(
            id = id,
            status = "ACTIVE",
            intent = null,
            codeJson = null,
            startDate = null,
            endDate = null,
            dosage = null,
            frequencyJson = null,
            reason = null,
            note = null,
            examinationId = null,
            createdAt = null,
            updatedAt = null,
        )

    private fun notif(recipientId: String) =
        io.healthassistant.shared.data.cache.CachedNotificationRow(
            recipientId = recipientId,
            status = "unread",
            readAt = null,
            dismissedAt = null,
            notificationId = recipientId,
            title = "t",
            body = null,
            type = null,
            category = null,
            severity = null,
            source = null,
            payloadJson = null,
            patientId = null,
            createdAt = null,
        )

    private companion object {
        const val CONN_A = "conn-a"
        const val CONN_B = "conn-b"
    }
}
