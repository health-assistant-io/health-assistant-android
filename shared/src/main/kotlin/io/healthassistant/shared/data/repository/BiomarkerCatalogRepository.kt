package io.healthassistant.shared.data.repository

import io.healthassistant.shared.data.BiomarkerSummary
import io.healthassistant.shared.data.cache.BiomarkerCache
import io.healthassistant.shared.data.cache.CacheDomain
import io.healthassistant.shared.data.cache.CacheMetaStore
import io.healthassistant.shared.data.cache.CacheRefreshMeta
import kotlinx.coroutines.flow.Flow

/**
 * Single-Source-of-Truth repository for the biomarker catalog (offline-first
 * M2). The catalog drives the Insights dropdown, the Home edit dialog, and the
 * display names/units/ranges merged onto dashboard cards.
 *
 * The UI observes [observeAll] — a Room-backed, reactive, instant, offline
 * snapshot of `GET /biomarkers`. [refresh] decouples the network: on success it
 * swaps the cached snapshot (so a definition removed server-side disappears
 * locally too); offline or on failure the cache is left untouched and the user
 * keeps the saved catalog — a fresh launch with no network still shows every
 * biomarker the instance ever exposed.
 */
class BiomarkerCatalogRepository(
    private val cache: BiomarkerCache,
    private val gateway: BiomarkerGateway,
    private val connectivity: ConnectivityProvider,
    private val meta: CacheMetaStore,
) {
    /** The whole catalog, name-ascending. Reactive — re-emits on refresh. */
    fun observeAll(): Flow<List<BiomarkerSummary>> = cache.observeAll()

    /** The catalog entry for a LOINC/custom code, or null. Reactive. */
    fun observeByCode(code: String): Flow<BiomarkerSummary?> = cache.observeByCode(code)

    /** Best-effort full-catalog refresh. No-op when offline; on success the
     *  cached snapshot is swapped atomically. Never throws. */
    suspend fun refresh(): RefreshOutcome {
        if (!connectivity.isOnline()) return RefreshOutcome.OFFLINE
        return try {
            cache.replaceAllSynced(gateway.catalog(CATALOG_LIMIT), CacheRefreshMeta.success(CacheDomain.BIOMARKERS, System.currentTimeMillis()))
            RefreshOutcome.REFRESHED
        } catch (e: Exception) {
            meta.recordRefresh(CacheRefreshMeta.failure(CacheDomain.BIOMARKERS, e.message))
            RefreshOutcome.FAILED
        }
    }

    /** Drop every cached row + the domain's staleness row (the Settings
     *  "clear cached data" action). */
    suspend fun clear() {
        cache.clear()
        meta.clearDomain(CacheDomain.BIOMARKERS)
    }

    private companion object {
        /** `GET /biomarkers` caps at 1000 — always ask for the full catalog. */
        const val CATALOG_LIMIT = 1000
    }
}
