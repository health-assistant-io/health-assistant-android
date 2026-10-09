package io.healthassistant.shared.data.repository

import io.healthassistant.shared.data.DocumentSummary
import io.healthassistant.shared.data.cache.CacheDomain
import io.healthassistant.shared.data.cache.CacheMetaStore
import io.healthassistant.shared.data.cache.CacheRefreshMeta
import io.healthassistant.shared.data.cache.DeltaRows
import io.healthassistant.shared.data.cache.DocumentCache
import io.healthassistant.shared.sync.ChangesDelta
import kotlinx.coroutines.flow.Flow

/**
 * Single-Source-of-Truth repository for document metadata (offline-first M3;
 * byte caching is M4). The exam-detail document list observes [observeForExam]
 * — Room-backed, reactive, instant, offline. [refreshForExam] decouples the
 * network: on success it upserts the page and reconciles that exam's removals;
 * offline or on failure the cache is left untouched.
 *
 * Offline-first M9: refreshes record the cache_meta success row in the same
 * Room transaction as the upsert; failures record the error.
 */
class DocumentRepository(
    private val cache: DocumentCache,
    private val gateway: DocumentGateway,
    private val connectivity: ConnectivityProvider,
    private val meta: CacheMetaStore,
) {
    /** The documents attached to [examId], newest created first. Reactive. */
    fun observeForExam(examId: String): Flow<List<DocumentSummary>> = cache.observeForExam(examId)

    /** Best-effort refresh of one exam's document snapshot. */
    suspend fun refreshForExam(examId: String): RefreshOutcome {
        if (!connectivity.isOnline()) return RefreshOutcome.OFFLINE
        return try {
            val docs = gateway.listForExam(examId)
            cache.storeAllSynced(docs, CacheRefreshMeta.success(CacheDomain.DOCUMENTS, System.currentTimeMillis()))
            cache.reconcileForExam(examId, docs.map { it.id })
            RefreshOutcome.REFRESHED
        } catch (e: Exception) {
            meta.recordRefresh(CacheRefreshMeta.failure(CacheDomain.DOCUMENTS, e.message))
            RefreshOutcome.FAILED
        }
    }

    /** Drop one row after a successful remote delete so the UI updates
     *  instantly. */
    suspend fun onDeleted(docId: String) = cache.delete(docId)

    /** Hydrate `/changes` document deltas (offline-first M7): merge-upsert by
     *  id; deletions wait for the periodic full re-snapshot. Idempotent. */
    suspend fun applyDelta(delta: ChangesDelta) {
        val rows = DeltaRows.documents(delta)
        if (rows.isNotEmpty()) cache.applyDeltas(rows)
    }

    /** Patient-wide full re-snapshot (offline-first M7): upserts the fetched
     *  snapshot + reconciles deletions across ALL exams. Runs on the 24h
     *  re-sync cadence from PullSyncWorker (the per-exam [refreshForExam]
     *  stays the interactive path). */
    suspend fun refreshAll(): RefreshOutcome {
        if (!connectivity.isOnline()) return RefreshOutcome.OFFLINE
        return try {
            val docs = gateway.listAll()
            cache.storeAllSynced(docs, CacheRefreshMeta.success(CacheDomain.DOCUMENTS, System.currentTimeMillis()))
            cache.reconcileAll(docs.map { it.id })
            RefreshOutcome.REFRESHED
        } catch (e: Exception) {
            meta.recordRefresh(CacheRefreshMeta.failure(CacheDomain.DOCUMENTS, e.message))
            RefreshOutcome.FAILED
        }
    }

    /** Drop every cached row + the domain's staleness row. */
    suspend fun clear() {
        cache.clear()
        meta.clearDomain(CacheDomain.DOCUMENTS)
    }
}
