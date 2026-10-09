package io.healthassistant.android.data.cache

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.healthassistant.shared.data.cache.CacheMetaState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room DAO for the `cache_meta` staleness table (offline-first M9, plan §3.3).
 * Reads are reactive (the staleness chip + Settings re-render the moment a
 * refresh lands). Writes go through the [record] transaction so a success
 * timestamp never lands without its row-count, and so callers that write
 * domain rows + meta together (the Room cache impls, via `withTransaction`)
 * get a single atomic unit.
 */
@Dao
interface CacheMetaDao {
    @Query("SELECT * FROM cache_meta WHERE connection_id = :connectionId AND domain = :domain AND filter_key = '' LIMIT 1")
    fun observe(
        connectionId: String,
        domain: String,
    ): Flow<CacheMetaEntity?>

    @Query("SELECT * FROM cache_meta WHERE connection_id = :connectionId ORDER BY domain ASC")
    fun observeAll(connectionId: String): Flow<List<CacheMetaEntity>>

    @Query("SELECT * FROM cache_meta WHERE connection_id = :connectionId AND domain = :domain AND filter_key = '' LIMIT 1")
    suspend fun get(
        connectionId: String,
        domain: String,
    ): CacheMetaEntity?

    @Upsert
    suspend fun upsert(row: CacheMetaEntity)

    /** Record one refresh outcome: a success stamps the timestamp, clears the
     *  error, and stores the post-write row count; a failure keeps the last
     *  success + count and stores only the error. Idempotent upsert on the
     *  (connection, domain) key. */
    @Transaction
    suspend fun record(
        connectionId: String,
        domain: String,
        successAtEpochMs: Long?,
        error: String?,
        rowCount: Int?,
    ) {
        val existing = get(connectionId, domain)
        upsert(
            CacheMetaEntity(
                connectionId = connectionId,
                domain = domain,
                lastSuccessAtEpochMs = successAtEpochMs ?: existing?.lastSuccessAtEpochMs ?: 0L,
                lastError = error,
                rowCount = rowCount ?: existing?.rowCount ?: 0,
            ),
        )
    }

    /** The success path used by the domain caches (inside their write
     *  transaction): exact row count computed by the caller in-transaction. */
    @Transaction
    suspend fun recordSuccess(
        connectionId: String,
        domain: String,
        successAtEpochMs: Long,
        rowCount: Int,
    ) {
        upsert(
            CacheMetaEntity(
                connectionId = connectionId,
                domain = domain,
                lastSuccessAtEpochMs = successAtEpochMs,
                lastError = null,
                rowCount = rowCount,
            ),
        )
    }

    @Query("DELETE FROM cache_meta WHERE connection_id = :connectionId AND domain = :domain")
    suspend fun clearDomain(
        connectionId: String,
        domain: String,
    )

    @Query("DELETE FROM cache_meta WHERE connection_id = :connectionId")
    suspend fun clear(connectionId: String)

    /** Reactive decoded read for one domain (null when never refreshed). */
    fun observeState(
        connectionId: String,
        domain: String,
    ): Flow<CacheMetaState?> = observe(connectionId, domain).map { it?.toState() }

    /** Reactive decoded read of the connection's every domain row. */
    fun observeStates(connectionId: String): Flow<List<CacheMetaState>> =
        observeAll(connectionId).map { rows -> rows.mapNotNull { it.toState() } }
}
