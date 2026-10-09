package io.healthassistant.shared.data.repository

import io.healthassistant.bridge.Allergy
import io.healthassistant.bridge.ClinicalEvent
import io.healthassistant.bridge.Medication
import io.healthassistant.bridge.Vaccine
import io.healthassistant.shared.data.cache.CachedAllergyRow
import io.healthassistant.shared.data.cache.CachedClinicalEventRow
import io.healthassistant.shared.data.cache.CachedMedicationRow
import io.healthassistant.shared.data.cache.CachedVaccineRow
import io.healthassistant.shared.data.cache.ClinicalRecordCache
import io.healthassistant.shared.data.cache.ClinicalRecordCacheMapper
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the offline-first invariants of [ClinicalRecordRepository]
 * (M5), exercised through the medication section + spot-checks on the others:
 * cache-first observe with no gateway call, refresh upserts + reconciles
 * removals (incl. the JSON-field round-trip via
 * [ClinicalRecordCacheMapper]), offline / failed refreshes leave the saved
 * list intact, and a remote delete drops the row instantly.
 */
class ClinicalRecordRepositoryTest {
    @Test
    fun `observe emits cached medications with no gateway call`() =
        runTest {
            val cache = FakeClinicalRecordCache()
            cache.storeMedications(listOf(ClinicalRecordCacheMapper.toRow(medication("m1", "Vitamin D"))))
            val gateway = RecordingGateway()
            val repo = ClinicalRecordRepository(cache, gateway, ConnectivityProvider { true }, FakeCacheMetaStore())

            val meds = repo.observeMedications().first()

            assertEquals(listOf("m1"), meds.map { it.id })
            assertEquals("gateway must not be called for a plain observe", 0, gateway.medicationCalls)
        }

    @Test
    fun `medication refresh round-trips the code JSON and reconciles removals`() =
        runTest {
            val cache = FakeClinicalRecordCache()
            cache.storeMedications(
                listOf(
                    ClinicalRecordCacheMapper.toRow(medication("m1", "Vitamin D")),
                    ClinicalRecordCacheMapper.toRow(medication("m2", "Metformin")),
                ),
            )
            val gateway = RecordingGateway(medications = listOf(medication("m1", "Vitamin D 2000 IU")))
            val repo = ClinicalRecordRepository(cache, gateway, ConnectivityProvider { true }, FakeCacheMetaStore())

            val outcome = repo.refreshMedications()

            assertEquals(RefreshOutcome.REFRESHED, outcome)
            val meds = repo.observeMedications().first()
            assertEquals("a medication removed server-side must disappear locally", listOf("m1"), meds.map { it.id })
            assertEquals(
                "the code JSON must round-trip losslessly",
                "Vitamin D 2000 IU",
                meds.single().code["text"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content },
            )
        }

    @Test
    fun `offline refresh returns OFFLINE and keeps the saved list`() =
        runTest {
            val cache = FakeClinicalRecordCache()
            cache.storeAllergies(listOf(ClinicalRecordCacheMapper.toRow(allergy("a1", "Peanuts"))))
            val gateway = RecordingGateway()
            val repo = ClinicalRecordRepository(cache, gateway, ConnectivityProvider { false }, FakeCacheMetaStore())

            assertEquals(RefreshOutcome.OFFLINE, repo.refreshAllergies())
            assertEquals("offline must skip the network entirely", 0, gateway.allergyCalls)
            assertEquals(1, repo.observeAllergies().first().size)
        }

    @Test
    fun `failed refresh keeps the saved list`() =
        runTest {
            val cache = FakeClinicalRecordCache()
            cache.storeVaccines(listOf(ClinicalRecordCacheMapper.toRow(vaccine("v1", "COVID-19"))))
            val gateway = RecordingGateway(vaccinesThrow = IllegalStateException("503"))
            val repo = ClinicalRecordRepository(cache, gateway, ConnectivityProvider { true }, FakeCacheMetaStore())

            assertEquals(RefreshOutcome.FAILED, repo.refreshVaccines())
            assertEquals(1, repo.observeVaccines().first().size)
        }

    @Test
    fun `clinical events refresh upserts and reconciles`() =
        runTest {
            val cache = FakeClinicalRecordCache()
            cache.storeClinicalEvents(listOf(ClinicalRecordCacheMapper.toRow(event("e1", "Flu"))))
            val gateway = RecordingGateway(events = listOf(event("e1", "Influenza"), event("e2", "COVID-19")))
            val repo = ClinicalRecordRepository(cache, gateway, ConnectivityProvider { true }, FakeCacheMetaStore())

            assertEquals(RefreshOutcome.REFRESHED, repo.refreshClinicalEvents())

            val events = repo.observeClinicalEvents().first()
            assertEquals(setOf("e1", "e2"), events.map { it.id }.toSet())
            assertEquals("Influenza", events.firstOrNull { it.id == "e1" }?.title)
        }

    @Test
    fun `onMedicationDeleted removes the row instantly`() =
        runTest {
            val cache = FakeClinicalRecordCache()
            cache.storeMedications(listOf(ClinicalRecordCacheMapper.toRow(medication("m1", "Vitamin D"))))
            val repo = ClinicalRecordRepository(cache, RecordingGateway(), ConnectivityProvider { true }, FakeCacheMetaStore())

            repo.onMedicationDeleted("m1")

            assertTrue(repo.observeMedications().first().isEmpty())
        }

    @Test
    fun `applyDelta hydrates a changes delta into the caches without a gateway call`() =
        runTest {
            val cache = FakeClinicalRecordCache()
            cache.storeMedications(listOf(ClinicalRecordCacheMapper.toRow(medication("m1", "Old name"))))
            val gateway = RecordingGateway()
            val repo = ClinicalRecordRepository(cache, gateway, ConnectivityProvider { false }, FakeCacheMetaStore())

            repo.applyDelta(delta())

            assertEquals("gateway must not be touched by delta hydration", 0, gateway.medicationCalls)
            val med = repo.observeMedications().first().single()
            assertEquals("STOPPED", med.status)
            assertEquals(
                "New name",
                med.code["text"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content },
            )
            assertEquals("dosage preserved through the merge", "1 pill", med.dosage)
            assertEquals(listOf("a1"), repo.observeAllergies().first().map { it.id })
        }

    private fun delta(): io.healthassistant.shared.sync.ChangesDelta {
        fun row(vararg pairs: Pair<String, String?> = emptyArray()) = buildJsonObject {
            pairs.forEach { (k, v) ->
                if (v != null) put(k, v) else put(k, JsonNull)
            }
        }
        return io.healthassistant.shared.sync.ChangesDelta(
            medications =
                listOf(
                    row(
                        "id" to "m1",
                        "updated_at" to "2026-08-14T10:00:00Z",
                        "status" to "STOPPED",
                        "code_text" to "New name",
                        "start_date" to null,
                    ),
                ),
            allergies =
                listOf(
                    row(
                        "id" to "a1",
                        "updated_at" to "2026-08-14T10:00:00Z",
                        "clinical_status" to "ACTIVE",
                        "code_text" to "Peanuts",
                    ),
                ),
        )
    }

    private fun medication(
        id: String,
        name: String,
    ): Medication =
        Medication(
            id = id,
            status = "ACTIVE",
            code = mapOf("text" to kotlinx.serialization.json.JsonPrimitive(name)),
            dosage = "1 pill",
        )

    private fun allergy(
        id: String,
        name: String,
    ): Allergy =
        Allergy(
            id = id,
            clinicalStatus = "ACTIVE",
            code = mapOf("text" to kotlinx.serialization.json.JsonPrimitive(name)),
        )

    private fun vaccine(
        id: String,
        name: String,
    ): Vaccine =
        Vaccine(
            id = id,
            status = "completed",
            vaccineCode = mapOf("text" to kotlinx.serialization.json.JsonPrimitive(name)),
        )

    private fun event(
        id: String,
        title: String,
    ): ClinicalEvent = ClinicalEvent(id = id, title = title, status = "active")

    private class RecordingGateway(
        private val medications: List<Medication> = emptyList(),
        private val allergies: List<Allergy> = emptyList(),
        private val vaccines: List<Vaccine> = emptyList(),
        private val events: List<ClinicalEvent> = emptyList(),
        private val vaccinesThrow: Throwable? = null,
    ) : ClinicalRecordGateway {
        var medicationCalls = 0
            private set
        var allergyCalls = 0
            private set

        override suspend fun medications(limit: Int): List<Medication> {
            medicationCalls++
            return medications
        }

        override suspend fun allergies(limit: Int): List<Allergy> {
            allergyCalls++
            return allergies
        }

        override suspend fun vaccines(limit: Int): List<Vaccine> {
            vaccinesThrow?.let { throw it }
            return vaccines
        }

        override suspend fun clinicalEvents(limit: Int): List<ClinicalEvent> = events
    }

    /** In-memory [ClinicalRecordCache] mirroring the Room semantics. */
    private class FakeClinicalRecordCache : ClinicalRecordCache {
        private val meds = MutableStateFlow<List<CachedMedicationRow>>(emptyList())
        private val allergies = MutableStateFlow<List<CachedAllergyRow>>(emptyList())
        private val vaccines = MutableStateFlow<List<CachedVaccineRow>>(emptyList())
        private val events = MutableStateFlow<List<CachedClinicalEventRow>>(emptyList())

        override suspend fun storeMedications(rows: List<CachedMedicationRow>) = upsert(meds, rows) { it.id }

        override suspend fun storeMedicationsSynced(
            rows: List<CachedMedicationRow>,
            meta: io.healthassistant.shared.data.cache.CacheRefreshMeta,
        ) = storeMedications(rows)

        override suspend fun reconcileMedications(ids: List<String>) {
            meds.value = meds.value.filter { it.id in ids }
        }

        override suspend fun deleteMedication(id: String) {
            meds.value = meds.value.filterNot { it.id == id }
        }

        override fun observeMedications(): Flow<List<Medication>> = meds.asStateFlow().map { it.map(ClinicalRecordCacheMapper::toModel) }

        override suspend fun storeAllergies(rows: List<CachedAllergyRow>) = upsert(allergies, rows) { it.id }

        override suspend fun storeAllergiesSynced(
            rows: List<CachedAllergyRow>,
            meta: io.healthassistant.shared.data.cache.CacheRefreshMeta,
        ) = storeAllergies(rows)

        override suspend fun reconcileAllergies(ids: List<String>) {
            allergies.value = allergies.value.filter { it.id in ids }
        }

        override suspend fun deleteAllergy(id: String) {
            allergies.value = allergies.value.filterNot { it.id == id }
        }

        override fun observeAllergies(): Flow<List<Allergy>> = allergies.asStateFlow().map { it.map(ClinicalRecordCacheMapper::toModel) }

        override suspend fun storeVaccines(rows: List<CachedVaccineRow>) = upsert(vaccines, rows) { it.id }

        override suspend fun storeVaccinesSynced(
            rows: List<CachedVaccineRow>,
            meta: io.healthassistant.shared.data.cache.CacheRefreshMeta,
        ) = storeVaccines(rows)

        override suspend fun reconcileVaccines(ids: List<String>) {
            vaccines.value = vaccines.value.filter { it.id in ids }
        }

        override suspend fun deleteVaccine(id: String) {
            vaccines.value = vaccines.value.filterNot { it.id == id }
        }

        override fun observeVaccines(): Flow<List<Vaccine>> = vaccines.asStateFlow().map { it.map(ClinicalRecordCacheMapper::toModel) }

        override suspend fun storeClinicalEvents(rows: List<CachedClinicalEventRow>) = upsert(events, rows) { it.id }

        override suspend fun storeClinicalEventsSynced(
            rows: List<CachedClinicalEventRow>,
            meta: io.healthassistant.shared.data.cache.CacheRefreshMeta,
        ) = storeClinicalEvents(rows)

        override suspend fun reconcileClinicalEvents(ids: List<String>) {
            events.value = events.value.filter { it.id in ids }
        }

        override suspend fun deleteClinicalEvent(id: String) {
            events.value = events.value.filterNot { it.id == id }
        }

        override fun observeClinicalEvents(): Flow<List<ClinicalEvent>> = events.asStateFlow().map { it.map(ClinicalRecordCacheMapper::toModel) }

        override suspend fun clear() {
            meds.value = emptyList()
            allergies.value = emptyList()
            vaccines.value = emptyList()
            events.value = emptyList()
        }

        override suspend fun applyMedicationDeltas(rows: List<io.healthassistant.shared.data.cache.MedicationDeltaRow>) {
            val existing = meds.value.associateBy { it.id }
            storeMedications(rows.map { io.healthassistant.shared.data.cache.DeltaRows.merge(existing[it.id], it) })
        }

        override suspend fun applyAllergyDeltas(rows: List<io.healthassistant.shared.data.cache.AllergyDeltaRow>) {
            val existing = allergies.value.associateBy { it.id }
            storeAllergies(rows.map { io.healthassistant.shared.data.cache.DeltaRows.merge(existing[it.id], it) })
        }

        override suspend fun applyVaccineDeltas(rows: List<io.healthassistant.shared.data.cache.VaccineDeltaRow>) {
            val existing = vaccines.value.associateBy { it.id }
            storeVaccines(rows.map { io.healthassistant.shared.data.cache.DeltaRows.merge(existing[it.id], it) })
        }

        override suspend fun applyClinicalEventDeltas(rows: List<io.healthassistant.shared.data.cache.ClinicalEventDeltaRow>) {
            val existing = events.value.associateBy { it.id }
            storeClinicalEvents(rows.map { io.healthassistant.shared.data.cache.DeltaRows.merge(existing[it.id], it) })
        }

        private fun <T> upsert(
            flow: MutableStateFlow<List<T>>,
            rows: List<T>,
            idOf: (T) -> String,
        ) {
            val byId = flow.value.associateBy(idOf).toMutableMap()
            rows.forEach { byId[idOf(it)] = it }
            flow.value = byId.values.toList()
        }
    }
}
