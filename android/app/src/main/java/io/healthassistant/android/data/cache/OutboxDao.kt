package io.healthassistant.android.data.cache

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import io.healthassistant.shared.sync.OutboxLane
import io.healthassistant.shared.sync.OutboxStatus

/** Phase C — Room type converters for the outbox enums (stored as their names). */
class OutboxConverters {
    @TypeConverter fun laneToString(l: OutboxLane): String = l.name

    @TypeConverter fun stringToLane(s: String): OutboxLane = OutboxLane.valueOf(s)

    @TypeConverter fun statusToString(s: OutboxStatus): String = s.name

    @TypeConverter fun stringToStatus(s: String): OutboxStatus = OutboxStatus.valueOf(s)
}

/**
 * Phase C — Room DAO for the outbox. One method per `OutboxStore` op, mirroring
 * the hand-rolled `SqliteOutboxStore` SQL (the `claim` update + the lane/status
 * indices match exactly so drain behavior is unchanged). The `RoomOutboxStore`
 * wraps this in the `OutboxStore` interface the sync engine drains.
 */
@TypeConverters(OutboxConverters::class)
@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE id = :id")
    suspend fun get(id: String): OutboxEntity?

    @Query(
        """
        SELECT * FROM outbox
        WHERE lane = :lane AND status = :status AND next_attempt_at <= :now
        ORDER BY created_at ASC LIMIT :limit
        """,
    )
    suspend fun claimCandidates(
        lane: OutboxLane,
        status: OutboxStatus,
        now: Long,
        limit: Int,
    ): List<OutboxEntity>

    @Query("UPDATE outbox SET status = :status WHERE id = :id")
    suspend fun setStatus(
        id: String,
        status: OutboxStatus,
    )

    @Query("UPDATE outbox SET status = :status, next_attempt_at = :nextAttemptAt WHERE id IN (:ids)")
    suspend fun release(
        ids: List<String>,
        status: OutboxStatus,
        nextAttemptAt: Long,
    )

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: String)

    @Query(
        "UPDATE outbox SET status = :status, next_attempt_at = :nextAttemptAt, attempts = attempts + 1 WHERE id = :id",
    )
    suspend fun markRetry(
        id: String,
        status: OutboxStatus,
        nextAttemptAt: Long,
    )

    @Query("UPDATE outbox SET status = :status, dead_reason = :reason WHERE id = :id")
    suspend fun markDead(
        id: String,
        status: OutboxStatus,
        reason: String,
    )

    @Query(
        "UPDATE outbox SET status = :status, attempts = 0, next_attempt_at = 0, dead_reason = NULL WHERE id = :id",
    )
    suspend fun revive(
        id: String,
        status: OutboxStatus,
    )

    @Query("DELETE FROM outbox")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM outbox WHERE lane = :lane AND status = :status")
    suspend fun count(
        lane: OutboxLane,
        status: OutboxStatus,
    ): Int

    @Query("SELECT * FROM outbox WHERE status = :status")
    suspend fun deadLetters(status: OutboxStatus): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox WHERE status = :status")
    suspend fun countByStatus(status: OutboxStatus): Int

    @Query(
        """
        SELECT id, method, path, attempts, dead_reason FROM outbox
        WHERE status = :status ORDER BY created_at ASC LIMIT :limit
        """,
    )
    suspend fun deadLetterPreviews(
        status: OutboxStatus,
        limit: Int,
    ): List<DeadLetterPreviewRow>

    @Query(
        """
        UPDATE outbox SET status = :alive, attempts = 0, next_attempt_at = 0, dead_reason = NULL
        WHERE status = :dead
        """,
    )
    suspend fun reviveAll(
        dead: OutboxStatus,
        alive: OutboxStatus,
    ): Int

    /** Phase C migration: bulk-insert the old plaintext rows in one transaction. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<OutboxEntity>)
}

/** Payload-free projection row for the bounded dead-letter list on the Sync screen. */
data class DeadLetterPreviewRow(
    val id: String,
    val method: String,
    val path: String,
    val attempts: Int,
    @ColumnInfo(name = "dead_reason") val deadReason: String?,
)
