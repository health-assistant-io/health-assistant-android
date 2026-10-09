package io.healthassistant.shared.data.cache

import kotlinx.coroutines.flow.Flow

/**
 * The read-cache domains tracked by the staleness table (offline-first M9).
 * The [wire] strings are the exact `cache_meta.domain` values — the closed set
 * from the plan's §3.3; a stored value outside the set decodes to null and is
 * skipped by readers, so the enum can grow without breaking old rows.
 */
enum class CacheDomain(val wire: String) {
    OBSERVATIONS("observations"),
    BIOMARKERS("biomarkers"),
    EXAMINATIONS("examinations"),
    DOCUMENTS("documents"),
    MEDICATIONS("medications"),
    ALLERGIES("allergies"),
    VACCINES("vaccines"),
    CLINICAL_EVENTS("clinical_events"),
    NOTIFICATIONS("notifications"),

    ;

    companion object {
        fun fromWire(wire: String): CacheDomain? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The outcome of one refresh attempt to record on the domain's `cache_meta`
 * row. A success carries the epoch-ms timestamp (and clears any previous
 * error); a failure carries the error message and leaves the last success
 * timestamp untouched — "when did this data last refresh" and "why is it
 * stale" stay answerable independently.
 */
data class CacheRefreshMeta(
    val domain: CacheDomain,
    val successAtEpochMs: Long? = null,
    val error: String? = null,
) {
    companion object {
        fun success(
            domain: CacheDomain,
            atEpochMs: Long,
        ): CacheRefreshMeta = CacheRefreshMeta(domain = domain, successAtEpochMs = atEpochMs)

        fun failure(
            domain: CacheDomain,
            message: String?,
        ): CacheRefreshMeta = CacheRefreshMeta(domain = domain, error = message?.take(MAX_ERROR_LENGTH) ?: "unknown error")

        private const val MAX_ERROR_LENGTH = 200
    }
}

/** One domain's staleness row, decoded for the UI (the chip + Settings). */
data class CacheMetaState(
    val domain: CacheDomain,
    val lastSuccessAtEpochMs: Long,
    val lastError: String?,
    val rowCount: Int,
)

/**
 * Connection-scoped reader/writer for the `cache_meta` staleness table
 * (offline-first M9). Implementations bind a single [CacheDomain.wire] scope —
 * the active connection — so a staleness row can never describe another
 * patient's cache. Success rows for a refresh are written by the domain caches
 * themselves (same Room transaction as the upsert — see the `*Synced` write
 * methods); this store carries the standalone failure records + the reads.
 */
interface CacheMetaStore {
    /** Record a refresh outcome (failure paths; success paths ride the cache tx). */
    suspend fun recordRefresh(meta: CacheRefreshMeta)

    /** The domain's staleness row, or null when it never refreshed. Reactive. */
    fun observe(domain: CacheDomain): Flow<CacheMetaState?>

    /** Every domain row of the bound connection, domain-ascending. Reactive. */
    fun observeAll(): Flow<List<CacheMetaState>>

    /** Drop the domain's row (the repository clear action). */
    suspend fun clearDomain(domain: CacheDomain)

    /** Drop every row of the bound connection. */
    suspend fun clear()
}
