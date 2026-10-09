package io.healthassistant.shared.data.repository

import io.healthassistant.shared.data.ObservationPoint
import io.healthassistant.shared.data.cache.CacheDomain
import io.healthassistant.shared.data.cache.CacheMetaStore
import io.healthassistant.shared.data.cache.CacheRefreshMeta
import io.healthassistant.shared.data.cache.ObservationCache
import kotlinx.coroutines.flow.Flow

/**
 * Single-Source-of-Truth repository for observations (offline-first M1).
 *
 * The UI observes [observeLatest] / [observeSeries] — these are Room-backed,
 * reactive, instant, and work offline. Network refresh is decoupled into
 * [refreshLatest] / [refreshSeries]: on success they write the fetched page back
 * into the cache (the UI re-renders automatically from the reactive `Flow`); on
 * failure or when offline they leave the cache untouched, so the user keeps
 * seeing the saved snapshot instead of an empty/error screen.
 *
 * The cache is the merged source of truth for both server observations (written
 * here on refresh) and local Health Connect samples (written via [storeLocal]).
 * `ObservationCache.latestPerBiomarker` already picks the newest point per
 * biomarker regardless of source, so [observeLatest] returns the merged
 * local + server view the dashboard renders.
 *
 * Offline-first M9: a successful refresh records the cache_meta success row in
 * the same Room transaction as the upsert; a failed one records the error (the
 * staleness chip + Settings read it back through [CacheMetaStore]).
 */
class ObservationRepository(
    private val cache: ObservationCache,
    private val gateway: ObservationGateway,
    private val connectivity: ConnectivityProvider,
    private val meta: CacheMetaStore,
) {
    /** Newest point per biomarker (local + server, merged by the cache).
     *  Reactive — re-emits on every cache write (refresh or local sample). */
    fun observeLatest(limit: Int = ObservationCache.DEFAULT_LATEST_LIMIT): Flow<List<ObservationPoint>> =
        cache.latestPerBiomarker(limit)

    /** Time-ordered (ascending) points for [code] within the epoch-ms window.
     *  Reactive. */
    fun observeSeries(
        code: String,
        sinceMs: Long? = null,
        untilMs: Long? = null,
        limit: Int = ObservationCache.DEFAULT_SERIES_LIMIT,
    ): Flow<List<ObservationPoint>> = cache.seriesFor(code, sinceMs, untilMs, limit)

    /** The single newest point for [code] regardless of any window — powers
     *  the "latest state" fallback when the selected chart window is empty. */
    fun observeLatestForCode(code: String): Flow<ObservationPoint?> = cache.latestForCode(code)

    /** The reading immediately before the newest for [code] — the baseline the
     *  detail header's trend chip compares the latest against. Reactive. */
    fun observePreviousForCode(code: String): Flow<ObservationPoint?> = cache.previousForCode(code)

    /** The previous reading per biomarker (second-newest per code) — feeds the
     *  trend chips on the Biomarkers list. Reactive. */
    fun observePreviousPerBiomarker(limit: Int = ObservationCache.DEFAULT_LATEST_LIMIT): Flow<List<ObservationPoint>> =
        cache.previousPerBiomarker(limit)

    /** Best-effort refresh of the latest-per-biomarker snapshot. No-op when
     *  offline ([RefreshOutcome.OFFLINE]); on success upserts the page into the
     *  cache so the UI re-renders. Never throws. */
    suspend fun refreshLatest(limit: Int = 50): RefreshOutcome {
        if (!connectivity.isOnline()) return RefreshOutcome.OFFLINE
        return try {
            cache.storeSynced(gateway.latest(limit), CacheRefreshMeta.success(CacheDomain.OBSERVATIONS, System.currentTimeMillis()))
            RefreshOutcome.REFRESHED
        } catch (e: Exception) {
            meta.recordRefresh(CacheRefreshMeta.failure(CacheDomain.OBSERVATIONS, e.message))
            RefreshOutcome.FAILED
        }
    }

    /** Best-effort refresh of one biomarker's series for a window. */
    suspend fun refreshSeries(
        code: String,
        sinceIso: String,
        untilIso: String,
        limit: Int = 200,
    ): RefreshOutcome {
        if (!connectivity.isOnline()) return RefreshOutcome.OFFLINE
        return try {
            cache.storeSynced(gateway.series(code, sinceIso, untilIso, limit), CacheRefreshMeta.success(CacheDomain.OBSERVATIONS, System.currentTimeMillis()))
            RefreshOutcome.REFRESHED
        } catch (e: Exception) {
            meta.recordRefresh(CacheRefreshMeta.failure(CacheDomain.OBSERVATIONS, e.message))
            RefreshOutcome.FAILED
        }
    }

    /** Write local Health Connect samples into the cache (the merged source) so
     *  they appear in [observeLatest] / [observeSeries] alongside server data.
     *  Called by the Home view-model on every monitor emission. Local samples
     *  are not a refresh — the staleness row is left untouched. */
    suspend fun storeLocal(points: List<ObservationPoint>) = cache.store(points)

    /** Drop every cached row + the domain's staleness row (the Settings
     *  "clear cached data" action). */
    suspend fun clear() {
        cache.clear()
        meta.clearDomain(CacheDomain.OBSERVATIONS)
    }
}
