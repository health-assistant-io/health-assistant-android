package io.healthassistant.android.data.cache

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for the examination cache (offline-first M3). Writes upsert by the
 * (connection, id) primary key; [reconcile] drops rows that vanished from a
 * fresh snapshot so the cache mirrors the server list exactly — scoped to one
 * connection so a reconcile can never drop another connection's rows. Reads
 * are reactive.
 */
@Dao
interface ExaminationCacheDao {
    @Upsert
    suspend fun upsertAll(rows: List<CachedExamination>)

    @Query("DELETE FROM examination_cache WHERE connection_id = :connectionId AND id NOT IN (:ids)")
    suspend fun reconcile(
        connectionId: String,
        ids: List<String>,
    )

    @Query("DELETE FROM examination_cache WHERE connection_id = :connectionId AND id = :id")
    suspend fun delete(
        connectionId: String,
        id: String,
    )

    @Query("SELECT * FROM examination_cache WHERE connection_id = :connectionId ORDER BY examination_date DESC, rowid DESC")
    fun observeAll(connectionId: String): Flow<List<CachedExamination>>

    @Query("SELECT * FROM examination_cache WHERE connection_id = :connectionId AND id = :id LIMIT 1")
    fun observeById(
        connectionId: String,
        id: String,
    ): Flow<CachedExamination?>

    @Query("SELECT * FROM examination_cache WHERE connection_id = :connectionId AND id IN (:ids)")
    suspend fun getByIds(
        connectionId: String,
        ids: List<String>,
    ): List<CachedExamination>

    @Query("DELETE FROM examination_cache WHERE connection_id = :connectionId")
    suspend fun clearAll(connectionId: String)

    @Query("SELECT COUNT(*) FROM examination_cache WHERE connection_id = :connectionId")
    suspend fun rowCount(connectionId: String): Int
}
