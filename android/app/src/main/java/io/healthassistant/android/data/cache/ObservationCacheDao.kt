package io.healthassistant.android.data.cache

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for the observation cache (M8). Reads are reactive ([Flow]); writes
 * upsert by the (connection, id) primary key so a re-fetch updates in place
 * instead of duplicating.
 *
 * Offline-first M9: every query is scoped by `connection_id` — one
 * connection's biomarker series can never surface under another patient.
 *
 * The "latest per biomarker" pick uses a correlated subquery (no window
 * functions) so it works on every Android API level's SQLite — window functions
 * are not reliably available below API 30. `seriesFor` takes the NEWEST
 * `limit` rows of the window (inner `DESC LIMIT`) and returns them ascending —
 * dense telemetry must show "now and before", not the window's oldest slice.
 * The previous-value queries shift the same newest-first pick by one row
 * (`LIMIT 1 OFFSET 1`, per code) — the reading each latest is compared against
 * for the trend chips.
 */
@Dao
interface ObservationCacheDao {
    @Upsert
    suspend fun upsertAll(rows: List<CachedObservation>)

    @Query(
        """
        SELECT * FROM (
            SELECT * FROM observation_cache
            WHERE connection_id = :connectionId
              AND biomarker_code = :code
              AND (:sinceMs IS NULL OR (effective_epoch_ms IS NOT NULL AND effective_epoch_ms >= :sinceMs))
              AND(:untilMs IS NULL OR (effective_epoch_ms IS NOT NULL AND effective_epoch_ms <= :untilMs))
            ORDER BY effective_epoch_ms DESC, rowid DESC
            LIMIT :limit
        )
        ORDER BY effective_epoch_ms ASC
        """,
    )
    fun seriesFor(
        connectionId: String,
        code: String,
        sinceMs: Long?,
        untilMs: Long?,
        limit: Int,
    ): Flow<List<CachedObservation>>

    @Query(
        """
        SELECT * FROM observation_cache o
        WHERE o.connection_id = :connectionId
          AND o.id = (
            SELECT o2.id FROM observation_cache o2
            WHERE o2.connection_id = :connectionId
              AND o2.biomarker_code = o.biomarker_code
            ORDER BY o2.effective_epoch_ms DESC, o2.rowid DESC
            LIMIT 1
        )
        ORDER BY o.effective_epoch_ms DESC
        LIMIT :limit
        """,
    )
    fun latestPerBiomarker(
        connectionId: String,
        limit: Int,
    ): Flow<List<CachedObservation>>

    @Query(
        """
        SELECT * FROM observation_cache
        WHERE connection_id = :connectionId
          AND biomarker_code = :code
        ORDER BY effective_epoch_ms DESC, rowid DESC
        LIMIT 1
        """,
    )
    fun latestForCode(
        connectionId: String,
        code: String,
    ): Flow<CachedObservation?>

    @Query(
        """
        SELECT * FROM observation_cache
        WHERE connection_id = :connectionId
          AND biomarker_code = :code
        ORDER BY effective_epoch_ms DESC, rowid DESC
        LIMIT 1 OFFSET 1
        """,
    )
    fun previousForCode(
        connectionId: String,
        code: String,
    ): Flow<CachedObservation?>

    @Query(
        """
        SELECT * FROM observation_cache o
        WHERE o.connection_id = :connectionId
          AND o.id = (
            SELECT o2.id FROM observation_cache o2
            WHERE o2.connection_id = :connectionId
              AND o2.biomarker_code = o.biomarker_code
            ORDER BY o2.effective_epoch_ms DESC, o2.rowid DESC
            LIMIT 1 OFFSET 1
        )
        ORDER BY o.effective_epoch_ms DESC
        LIMIT :limit
        """,
    )
    fun previousPerBiomarker(
        connectionId: String,
        limit: Int,
    ): Flow<List<CachedObservation>>

    @Query("DELETE FROM observation_cache WHERE connection_id = :connectionId AND biomarker_code = :code")
    suspend fun clearForCode(
        connectionId: String,
        code: String,
    )

    @Query("DELETE FROM observation_cache WHERE connection_id = :connectionId")
    suspend fun clearAll(connectionId: String)

    @Query("SELECT COUNT(*) FROM observation_cache WHERE connection_id = :connectionId")
    suspend fun rowCount(connectionId: String): Int
}
