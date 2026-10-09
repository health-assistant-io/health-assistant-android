package io.healthassistant.shared.data.cache

import io.healthassistant.shared.data.BiomarkerSummary
import kotlinx.coroutines.flow.Flow

/**
 * The on-device biomarker catalog cache (offline-first M2). The bridge's
 * `GET /biomarkers` is a single-page full-snapshot read (no pagination), so the
 * cache stores the whole catalog and [replaceAll] swaps it atomically — removed
 * server-side definitions disappear locally too, without a separate
 * reconciliation pass.
 *
 * Pure-Kotlin interface (KMP `shared` core, JVM-testable with a fake); the Room
 * implementation is in `android/data/cache/`. Reads are reactive so the Home
 * edit dialog + the Insights dropdown repopulate the moment a refresh lands.
 */
interface BiomarkerCache {
    /** Atomically swap the whole catalog (delete-then-insert in one
     *  transaction). A snapshot may be partial — only call with a successful
     *  fetch's full page. */
    suspend fun replaceAll(catalog: List<BiomarkerSummary>)

    /** [replaceAll] + the cache_meta success row in the SAME Room transaction
     *  (offline-first M9). */
    suspend fun replaceAllSynced(
        catalog: List<BiomarkerSummary>,
        meta: CacheRefreshMeta,
    )

    /** The whole catalog, name-ascending. Reactive. */
    fun observeAll(): Flow<List<BiomarkerSummary>>

    /** The catalog entry for a LOINC/custom [code], or null. Reactive. */
    fun observeByCode(code: String): Flow<BiomarkerSummary?>

    /** Drop every cached row. */
    suspend fun clear()
}
