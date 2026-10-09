package io.healthassistant.shared.data.repository

import io.healthassistant.shared.data.ExaminationSummary
import io.healthassistant.shared.data.cache.CacheDomain
import io.healthassistant.shared.data.cache.CacheMetaStore
import io.healthassistant.shared.data.cache.CacheRefreshMeta
import io.healthassistant.shared.data.cache.DeltaRows
import io.healthassistant.shared.data.cache.ExaminationCache
import io.healthassistant.shared.sync.ChangesDelta
import kotlinx.coroutines.flow.Flow

/**
 * Single-Source-of-Truth repository for examinations (offline-first M3). The
 * Records list and the exam-detail header observe [observeAll] /
 * [observeById] — Room-backed, reactive, instant, offline. [refresh] decouples
 * the network: on success it upserts the fetched page and reconciles removals
 * into the cache; offline or on failure the cache is left untouched, so the
 * user keeps the saved list instead of "Couldn't load records".
 *
 * Offline-first M9: refreshes record the cache_meta success row in the same
 * Room transaction as the upsert; failures record the error.
 */
class ExaminationRepository(
    private val cache: ExaminationCache,
    private val gateway: ExaminationGateway,
    private val connectivity: ConnectivityProvider,
    private val meta: CacheMetaStore,
) {
    /** Every exam, newest examination-date first. Reactive. */
    fun observeAll(): Flow<List<ExaminationSummary>> = cache.observeAll()

    /** One exam by id (list or detail projection), or null. Reactive. */
    fun observeById(id: String): Flow<ExaminationSummary?> = cache.observeById(id)

    /** Best-effort full-list refresh (upsert + reconcile removals). */
    suspend fun refresh(): RefreshOutcome {
        if (!connectivity.isOnline()) return RefreshOutcome.OFFLINE
        return try {
            val exams = gateway.list(LIST_LIMIT)
            cache.storeAllSynced(exams, CacheRefreshMeta.success(CacheDomain.EXAMINATIONS, System.currentTimeMillis()))
            cache.reconcile(exams.map { it.id })
            RefreshOutcome.REFRESHED
        } catch (e: Exception) {
            meta.recordRefresh(CacheRefreshMeta.failure(CacheDomain.EXAMINATIONS, e.message))
            RefreshOutcome.FAILED
        }
    }

    /** Best-effort detail refresh for one exam (fills diagnoses + impressions). */
    suspend fun refreshDetail(id: String): RefreshOutcome {
        if (!connectivity.isOnline()) return RefreshOutcome.OFFLINE
        return try {
            cache.storeAllSynced(listOf(gateway.detail(id)), CacheRefreshMeta.success(CacheDomain.EXAMINATIONS, System.currentTimeMillis()))
            RefreshOutcome.REFRESHED
        } catch (e: Exception) {
            meta.recordRefresh(CacheRefreshMeta.failure(CacheDomain.EXAMINATIONS, e.message))
            RefreshOutcome.FAILED
        }
    }

    /** Drop one row after a successful remote delete so the UI updates
     *  instantly (no refresh round-trip). */
    suspend fun onDeleted(id: String) = cache.delete(id)

    /** Hydrate `/changes` examination deltas (offline-first M7): merge-upsert
     *  by id; deletions wait for the periodic full re-snapshot. Idempotent. */
    suspend fun applyDelta(delta: ChangesDelta) {
        val rows = DeltaRows.examinations(delta)
        if (rows.isNotEmpty()) cache.applyDeltas(rows)
    }

    /** Drop every cached row + the domain's staleness row (the Settings
     *  "clear cached data" action). */
    suspend fun clear() {
        cache.clear()
        meta.clearDomain(CacheDomain.EXAMINATIONS)
    }

    private companion object {
        /** `GET /examinations` caps at 200 — always ask for the full list. */
        const val LIST_LIMIT = 200
    }
}
