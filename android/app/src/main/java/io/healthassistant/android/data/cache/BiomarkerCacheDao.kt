package io.healthassistant.android.data.cache

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for the biomarker catalog cache (offline-first M2). The bridge's
 * `GET /biomarkers` is a single-page full snapshot, so [replaceAll] swaps the
 * connection's whole catalog slice in one transaction — removals propagate
 * without a reconciliation pass. Reads are reactive. Offline-first M9: every
 * query is scoped by `connection_id`.
 */
@Dao
interface BiomarkerCacheDao {
    @Upsert
    suspend fun upsertAll(rows: List<CachedBiomarker>)

    @Query("DELETE FROM biomarker_cache WHERE connection_id = :connectionId")
    suspend fun clear(connectionId: String)

    /** Atomic full-snapshot swap for one connection (delete + insert in one
     *  transaction; leaves other connections' rows untouched). */
    @Transaction
    suspend fun replaceAll(
        connectionId: String,
        rows: List<CachedBiomarker>,
    ) {
        clear(connectionId)
        if (rows.isNotEmpty()) upsertAll(rows)
    }

    @Query("SELECT * FROM biomarker_cache WHERE connection_id = :connectionId ORDER BY name ASC")
    fun observeAll(connectionId: String): Flow<List<CachedBiomarker>>

    @Query("SELECT * FROM biomarker_cache WHERE connection_id = :connectionId AND code = :code LIMIT 1")
    fun observeByCode(
        connectionId: String,
        code: String,
    ): Flow<CachedBiomarker?>

    @Query("SELECT COUNT(*) FROM biomarker_cache WHERE connection_id = :connectionId")
    suspend fun rowCount(connectionId: String): Int
}
