package io.healthassistant.shared.data.cache

import io.healthassistant.shared.data.DocumentSummary
import kotlinx.coroutines.flow.Flow

/**
 * The on-device document-metadata cache (offline-first M3; byte caching is
 * M4). `GET /examinations/{id}/documents` is a single-page snapshot, so a
 * refresh upserts the page and reconciles that exam's rows — deleted documents
 * disappear locally too.
 *
 * Rows also carry the (M4) offline-manifest columns (local path + cached-at +
 * byte size); they are nullable and unused until M4 lands.
 *
 * Pure-Kotlin interface (KMP `shared` core, JVM-testable with a fake); the Room
 * implementation is in `android/data/cache/`. Reads are reactive so the exam
 * detail document list re-renders the moment a refresh lands.
 */
interface DocumentCache {
    /** Upsert rows by id (a re-fetch updates in place, never duplicates). */
    suspend fun storeAll(docs: List<DocumentSummary>)

    /** [storeAll] + the cache_meta success row in the SAME Room transaction
     *  (offline-first M9). */
    suspend fun storeAllSynced(
        docs: List<DocumentSummary>,
        meta: CacheRefreshMeta,
    )

    /** Drop rows of [examId] whose id is not in [ids] (post-refresh
     *  reconciliation). Rows of other exams are untouched. */
    suspend fun reconcileForExam(
        examId: String,
        ids: List<String>,
    )

    /** Remove one row (after a successful remote delete). */
    suspend fun delete(id: String)

    /** The documents attached to [examId], newest created first. Reactive. */
    fun observeForExam(examId: String): Flow<List<DocumentSummary>>

    /** Merge-upsert `/changes` document deltas by id (offline-first M7): the
     *  delta carries {id, filename, status, examination_id}; everything else
     *  (incl. the local byte-manifest columns) keeps its cached value. */
    suspend fun applyDeltas(rows: List<DocumentDeltaRow>)

    /** Drop rows whose id is not in [ids] across ALL exams (the patient-wide
     *  counterpart of [reconcileForExam], used by the full re-snapshot). */
    suspend fun reconcileAll(ids: List<String>)

    /** Record that [docId]'s bytes are on disk at [localPath] (M4 manifest). */
    suspend fun setLocalManifest(
        docId: String,
        localPath: String,
        cachedAtEpochMs: Long,
    )

    /** Clear [docId]'s byte-manifest columns (after an eviction or delete). */
    suspend fun clearLocalManifest(docId: String)

    /** Drop every cached row. */
    suspend fun clear()
}
