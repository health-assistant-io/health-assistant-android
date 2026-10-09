package io.healthassistant.shared.data.cache

import io.healthassistant.bridge.Allergy
import io.healthassistant.bridge.ClinicalEvent
import io.healthassistant.bridge.Medication
import io.healthassistant.bridge.Vaccine
import kotlinx.coroutines.flow.Flow

/**
 * The on-device clinical-record cache (offline-first M5) — four families
 * (medications, allergies, vaccines, clinical events) behind one interface.
 * Each family behaves like the examination cache: writes upsert by id, a
 * refresh follows with a reconcile that drops rows missing from the snapshot,
 * and reads are reactive so the lists re-render the moment a refresh lands.
 *
 * Pure-Kotlin interface (KMP `shared` core, JVM-testable with fakes); the Room
 * implementation is in `android/data/cache/`.
 */
interface ClinicalRecordCache {
    // --- medications -----------------------------------------------------

    suspend fun storeMedications(rows: List<CachedMedicationRow>)

    /** [storeMedications] + the cache_meta success row in the SAME Room
     *  transaction (offline-first M9). */
    suspend fun storeMedicationsSynced(
        rows: List<CachedMedicationRow>,
        meta: CacheRefreshMeta,
    )

    suspend fun reconcileMedications(ids: List<String>)

    suspend fun deleteMedication(id: String)

    fun observeMedications(): Flow<List<Medication>>

    // --- allergies -------------------------------------------------------

    suspend fun storeAllergies(rows: List<CachedAllergyRow>)

    /** [storeAllergies] + the cache_meta success row in the SAME Room
     *  transaction (offline-first M9). */
    suspend fun storeAllergiesSynced(
        rows: List<CachedAllergyRow>,
        meta: CacheRefreshMeta,
    )

    suspend fun reconcileAllergies(ids: List<String>)

    suspend fun deleteAllergy(id: String)

    fun observeAllergies(): Flow<List<Allergy>>

    // --- vaccines --------------------------------------------------------

    suspend fun storeVaccines(rows: List<CachedVaccineRow>)

    /** [storeVaccines] + the cache_meta success row in the SAME Room
     *  transaction (offline-first M9). */
    suspend fun storeVaccinesSynced(
        rows: List<CachedVaccineRow>,
        meta: CacheRefreshMeta,
    )

    suspend fun reconcileVaccines(ids: List<String>)

    suspend fun deleteVaccine(id: String)

    fun observeVaccines(): Flow<List<Vaccine>>

    // --- clinical events -------------------------------------------------

    suspend fun storeClinicalEvents(rows: List<CachedClinicalEventRow>)

    /** [storeClinicalEvents] + the cache_meta success row in the SAME Room
     *  transaction (offline-first M9). */
    suspend fun storeClinicalEventsSynced(
        rows: List<CachedClinicalEventRow>,
        meta: CacheRefreshMeta,
    )

    suspend fun reconcileClinicalEvents(ids: List<String>)

    suspend fun deleteClinicalEvent(id: String)

    fun observeClinicalEvents(): Flow<List<ClinicalEvent>>

    // --- /changes delta hydration (M7) ------------------------------------
    // Partial-projection merges: fields the delta carries win; omitted fields
    // keep their cached value (see [DeltaRows]). Sparse inserts when the row
    // is not cached yet.

    /** Merge-upsert medication deltas by id. */
    suspend fun applyMedicationDeltas(rows: List<MedicationDeltaRow>)

    /** Merge-upsert allergy deltas by id. */
    suspend fun applyAllergyDeltas(rows: List<AllergyDeltaRow>)

    /** Merge-upsert vaccine deltas by id. */
    suspend fun applyVaccineDeltas(rows: List<VaccineDeltaRow>)

    /** Merge-upsert clinical-event deltas by id. */
    suspend fun applyClinicalEventDeltas(rows: List<ClinicalEventDeltaRow>)

    /** Drop every cached row across all four families. */
    suspend fun clear()
}
