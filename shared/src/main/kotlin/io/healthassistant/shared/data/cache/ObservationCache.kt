package io.healthassistant.shared.data.cache

import io.healthassistant.shared.data.ObservationPoint
import kotlinx.coroutines.flow.Flow

/**
 * The on-device observation cache (M8). A reactive, offline-first store of
 * recently-fetched observations, fed by every successful bridge read and every
 * local Health Connect read. Pure-Kotlin interface so it lives in the KMP
 * `shared` core (JVM-testable with a fake, iOS-reusable); the Room
 * implementation is in `android/data/cache/`.
 *
 * Contract:
 * - [store] upserts by observation `id` (re-fetches update in place, never
 *   duplicate). Rows with no addressable biomarker code are dropped.
 * - [seriesFor] returns the time-ordered points for one biomarker within an
 *   optional epoch-ms window — what charts render.
 * - [latestPerBiomarker] returns the newest point per biomarker — what Home
 *   cards render. Merge with the biomarker catalog (via
 *   `HomeDashboardBuilder.fromServer`) for display names/units.
 * - [latestForCode] returns the single newest point for one biomarker.
 * - [previousForCode] / [previousPerBiomarker] return the reading immediately
 *   before the newest (per code, or cache-wide) — the baseline the trend
 *   chips compare the latest against.
 *
 * All reads are reactive ([Flow]); consumers observe the cache and re-render on
 * write. Network fetches populate the cache; the cache is the source of truth
 * for instant/offline rendering.
 */
interface ObservationCache {
    /** Upsert a page of points (keyed by id). Drops unaddressable rows. */
    suspend fun store(points: List<ObservationPoint>)

    /** [store] + the cache_meta success row for [meta.domain] in the SAME Room
     *  transaction (offline-first M9) — a crash can never split "data written"
     *  from "staleness recorded". */
    suspend fun storeSynced(
        points: List<ObservationPoint>,
        meta: CacheRefreshMeta,
    )

    /** Time-ordered (ascending) points for [code] within the epoch-ms window. */
    fun seriesFor(
        code: String,
        sinceMs: Long? = null,
        untilMs: Long? = null,
        limit: Int = DEFAULT_SERIES_LIMIT,
    ): Flow<List<ObservationPoint>>

    /** The newest point per biomarker (cache-wide), capped at [limit] codes. */
    fun latestPerBiomarker(limit: Int = DEFAULT_LATEST_LIMIT): Flow<List<ObservationPoint>>

    /** The single newest point for [code], or null when the cache is empty. */
    fun latestForCode(code: String): Flow<ObservationPoint?>

    /** The reading immediately before the newest for [code], or null when only
     *  one point is cached. */
    fun previousForCode(code: String): Flow<ObservationPoint?>

    /** The second-newest point per biomarker (cache-wide), capped at [limit]
     *  codes — the previous reading each latest is compared against. */
    fun previousPerBiomarker(limit: Int = DEFAULT_LATEST_LIMIT): Flow<List<ObservationPoint>>

    /** Drop all cached rows for [code]. */
    suspend fun clearForCode(code: String)

    /** Drop every cached row. */
    suspend fun clear()

    companion object {
        /** Bounded series size so multi-year ranges don't flood the chart. */
        const val DEFAULT_SERIES_LIMIT = 1000

        /** Bounded "latest" card count so Home stays scannable. */
        const val DEFAULT_LATEST_LIMIT = 50
    }
}
