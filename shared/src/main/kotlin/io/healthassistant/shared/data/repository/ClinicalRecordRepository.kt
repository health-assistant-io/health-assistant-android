package io.healthassistant.shared.data.repository

import io.healthassistant.bridge.Allergy
import io.healthassistant.bridge.ClinicalEvent
import io.healthassistant.bridge.Medication
import io.healthassistant.bridge.Vaccine
import io.healthassistant.shared.data.cache.CacheDomain
import io.healthassistant.shared.data.cache.CacheMetaStore
import io.healthassistant.shared.data.cache.CacheRefreshMeta
import io.healthassistant.shared.data.cache.ClinicalRecordCache
import io.healthassistant.shared.data.cache.ClinicalRecordCacheMapper
import io.healthassistant.shared.data.cache.DeltaRows
import io.healthassistant.shared.sync.ChangesDelta
import kotlinx.coroutines.flow.Flow

/**
 * Network read abstraction for the four clinical-record families
 * (offline-first M5), isolating [ClinicalRecordRepository] from the SDK's
 * ktor-dependent `BridgeClient` so it stays pure-Kotlin + JVM-testable. Each
 * method performs exactly one bridge read and throws on a non-2xx response.
 */
interface ClinicalRecordGateway {
    suspend fun medications(limit: Int = 500): List<Medication>

    /** All allergies (active + resolved) — matches the screens' `active=null` read. */
    suspend fun allergies(limit: Int = 500): List<Allergy>

    suspend fun vaccines(limit: Int = 500): List<Vaccine>

    suspend fun clinicalEvents(limit: Int = 500): List<ClinicalEvent>
}

/**
 * Single-Source-of-Truth repository for clinical records (offline-first M5)
 * with four typed sections — medications, allergies, vaccines, clinical
 * events. Each section follows the SSOT contract: `observe*` reads the
 * Room-backed cache (reactive, instant, offline); `refresh*` decouples the
 * network (success → upsert + reconcile removals; offline/failure → the cache
 * is left untouched, so the saved list stays on screen); `on*Deleted` drops a
 * row instantly after a successful remote delete.
 *
 * Offline-first M9: refreshes record the domain's cache_meta success row in
 * the same Room transaction as the upsert; failures record the error.
 */
class ClinicalRecordRepository(
    private val cache: ClinicalRecordCache,
    private val gateway: ClinicalRecordGateway,
    private val connectivity: ConnectivityProvider,
    private val meta: CacheMetaStore,
) {
    fun observeMedications(): Flow<List<Medication>> = cache.observeMedications()

    suspend fun refreshMedications(): RefreshOutcome {
        if (!connectivity.isOnline()) return RefreshOutcome.OFFLINE
        return try {
            val meds = gateway.medications()
            cache.storeMedicationsSynced(meds.map(ClinicalRecordCacheMapper::toRow), CacheRefreshMeta.success(CacheDomain.MEDICATIONS, System.currentTimeMillis()))
            cache.reconcileMedications(meds.map { it.id })
            RefreshOutcome.REFRESHED
        } catch (e: Exception) {
            meta.recordRefresh(CacheRefreshMeta.failure(CacheDomain.MEDICATIONS, e.message))
            RefreshOutcome.FAILED
        }
    }

    fun observeAllergies(): Flow<List<Allergy>> = cache.observeAllergies()

    suspend fun refreshAllergies(): RefreshOutcome {
        if (!connectivity.isOnline()) return RefreshOutcome.OFFLINE
        return try {
            val allergies = gateway.allergies()
            cache.storeAllergiesSynced(allergies.map(ClinicalRecordCacheMapper::toRow), CacheRefreshMeta.success(CacheDomain.ALLERGIES, System.currentTimeMillis()))
            cache.reconcileAllergies(allergies.map { it.id })
            RefreshOutcome.REFRESHED
        } catch (e: Exception) {
            meta.recordRefresh(CacheRefreshMeta.failure(CacheDomain.ALLERGIES, e.message))
            RefreshOutcome.FAILED
        }
    }

    fun observeVaccines(): Flow<List<Vaccine>> = cache.observeVaccines()

    suspend fun refreshVaccines(): RefreshOutcome {
        if (!connectivity.isOnline()) return RefreshOutcome.OFFLINE
        return try {
            val vaccines = gateway.vaccines()
            cache.storeVaccinesSynced(vaccines.map(ClinicalRecordCacheMapper::toRow), CacheRefreshMeta.success(CacheDomain.VACCINES, System.currentTimeMillis()))
            cache.reconcileVaccines(vaccines.map { it.id })
            RefreshOutcome.REFRESHED
        } catch (e: Exception) {
            meta.recordRefresh(CacheRefreshMeta.failure(CacheDomain.VACCINES, e.message))
            RefreshOutcome.FAILED
        }
    }

    fun observeClinicalEvents(): Flow<List<ClinicalEvent>> = cache.observeClinicalEvents()

    suspend fun refreshClinicalEvents(): RefreshOutcome {
        if (!connectivity.isOnline()) return RefreshOutcome.OFFLINE
        return try {
            val events = gateway.clinicalEvents()
            cache.storeClinicalEventsSynced(events.map(ClinicalRecordCacheMapper::toRow), CacheRefreshMeta.success(CacheDomain.CLINICAL_EVENTS, System.currentTimeMillis()))
            cache.reconcileClinicalEvents(events.map { it.id })
            RefreshOutcome.REFRESHED
        } catch (e: Exception) {
            meta.recordRefresh(CacheRefreshMeta.failure(CacheDomain.CLINICAL_EVENTS, e.message))
            RefreshOutcome.FAILED
        }
    }

    /** Drop a row instantly after a successful remote delete (soft server-side,
     *  so the next refresh would also reconcile it away — this just makes it
     *  immediate). */
    suspend fun onMedicationDeleted(id: String) = cache.deleteMedication(id)

    suspend fun onAllergyDeleted(id: String) = cache.deleteAllergy(id)

    /** Hydrate a `/changes` delta into the four caches (offline-first M7).
     *  Merge-upserts by id — deletions are NOT represented in the delta, so
     *  they wait for the periodic full re-snapshot (see PullSyncWorker).
     *  Idempotent; a throw aborts the pull (the cursor stays put and the next
     *  pass re-applies the same window safely). */
    suspend fun applyDelta(delta: ChangesDelta) {
        val meds = DeltaRows.medications(delta)
        if (meds.isNotEmpty()) cache.applyMedicationDeltas(meds)
        val allergies = DeltaRows.allergies(delta)
        if (allergies.isNotEmpty()) cache.applyAllergyDeltas(allergies)
        val vaccines = DeltaRows.vaccines(delta)
        if (vaccines.isNotEmpty()) cache.applyVaccineDeltas(vaccines)
        val events = DeltaRows.clinicalEvents(delta)
        if (events.isNotEmpty()) cache.applyClinicalEventDeltas(events)
    }

    /** Drop every cached row + the four sections' staleness rows (the Settings
     *  "clear cached data" action). */
    suspend fun clear() {
        cache.clear()
        meta.clearDomain(CacheDomain.MEDICATIONS)
        meta.clearDomain(CacheDomain.ALLERGIES)
        meta.clearDomain(CacheDomain.VACCINES)
        meta.clearDomain(CacheDomain.CLINICAL_EVENTS)
    }
}
