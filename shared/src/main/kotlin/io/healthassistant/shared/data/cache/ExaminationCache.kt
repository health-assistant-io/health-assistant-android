package io.healthassistant.shared.data.cache

import io.healthassistant.shared.data.ExaminationSummary
import kotlinx.coroutines.flow.Flow

/**
 * The on-device examination cache (offline-first M3). The bridge's
 * `GET /examinations` is a single-page snapshot (capped at 200), so a refresh
 * upserts the fetched page and then reconciles — rows whose ids disappeared
 * server-side are dropped — keeping the cache an exact mirror of the list.
 *
 * One table holds both the list and the detail projection: the list shape is a
 * subset of `{id, examination_date, notes, patient_notes, extraction_status,
 * diagnoses, impressions}`, so a detail fetch just fills more columns on the
 * same row.
 *
 * Pure-Kotlin interface (KMP `shared` core, JVM-testable with a fake); the Room
 * implementation is in `android/data/cache/`. Reads are reactive so the Records
 * list and the exam detail header re-render the moment a refresh lands.
 */
interface ExaminationCache {
    /** Upsert rows by id (a re-fetch updates in place, never duplicates). */
    suspend fun storeAll(exams: List<ExaminationSummary>)

    /** [storeAll] + the cache_meta success row in the SAME Room transaction
     *  (offline-first M9). */
    suspend fun storeAllSynced(
        exams: List<ExaminationSummary>,
        meta: CacheRefreshMeta,
    )

    /** Drop rows whose id is not in [ids] (post-refresh reconciliation). */
    suspend fun reconcile(ids: List<String>)

    /** Remove one row (after a successful remote delete). */
    suspend fun delete(id: String)

    /** Every cached exam, newest examination-date first. Reactive. */
    fun observeAll(): Flow<List<ExaminationSummary>>

    /** One exam by id (list or detail projection), or null. Reactive. */
    fun observeById(id: String): Flow<ExaminationSummary?>

    /** Merge-upsert `/changes` examination deltas by id (offline-first M7):
     *  fields the delta carries win; omitted fields keep their cached value. */
    suspend fun applyDeltas(rows: List<ExaminationDeltaRow>)

    /** Drop every cached row. */
    suspend fun clear()
}
