package io.healthassistant.android.data.cache

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.healthassistant.shared.sync.OutboxItem
import io.healthassistant.shared.sync.OutboxLane
import io.healthassistant.shared.sync.OutboxStatus

/**
 * Phase C — the Room entity for the offline outbox, mirroring the columns the
 * old hand-rolled `SqliteOutboxStore` used (so the one-shot row migration is a
 * 1:1 copy). Keyed by the client UUID [id]; the compound
 * `(lane, status, next_attempt_at)` index backs the `claim` query exactly as
 * the old `idx_outbox_claim` did.
 *
 * `payload` is a BLOB (DEFAULT lane body bytes); `lane`/`status` are stored as
 * their enum names (matches the old store + survives KSP type-safe rewrites).
 */
@Entity(
    tableName = "outbox",
    indices = [Index(value = ["lane", "status", "next_attempt_at"])],
)
data class OutboxEntity(
    @PrimaryKey val id: String,
    val method: String,
    val path: String,
    val payload: ByteArray,
    @ColumnInfo(name = "lane") val lane: OutboxLane,
    @ColumnInfo(name = "content_ref") val contentRef: String?,
    @ColumnInfo(name = "status") val status: OutboxStatus,
    @ColumnInfo(name = "attempts") val attempts: Int,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "dead_reason") val deadReason: String?,
) {
    override fun equals(other: Any?): Boolean = other is OutboxEntity && other.id == id

    override fun hashCode(): Int = id.hashCode()
}

/** Entity ⇄ the shared [OutboxItem] (pure data, no Room types). */
fun OutboxEntity.toItem() =
    OutboxItem(
        id = id,
        method = method,
        path = path,
        payload = payload,
        lane = lane,
        contentRef = contentRef,
        status = status,
        attempts = attempts,
        nextAttemptAt = nextAttemptAt,
        createdAt = createdAt,
        deadReason = deadReason,
    )

/** Entity ⇄ the shared [OutboxItem] (pure data, no Room types). */
fun OutboxItem.toEntity() =
    OutboxEntity(
        id = id,
        method = method,
        path = path,
        payload = payload,
        lane = lane,
        contentRef = contentRef,
        status = status,
        attempts = attempts,
        nextAttemptAt = nextAttemptAt,
        createdAt = createdAt,
        deadReason = deadReason,
    )
