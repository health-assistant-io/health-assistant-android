package io.healthassistant.android.data.cache

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for the notification-inbox cache (offline-first M6). Writes upsert
 * by the (connection, recipient_id) primary key; [reconcile] drops rows
 * missing from a fresh snapshot (one connection only); the mark ops apply the
 * optimistic post-success updates; reads are reactive.
 */
@Dao
interface NotificationCacheDao {
    @Upsert
    suspend fun upsertAll(rows: List<CachedNotification>)

    @Query("DELETE FROM notification_cache WHERE connectionId = :connectionId AND recipientId NOT IN (:ids)")
    suspend fun reconcile(
        connectionId: String,
        ids: List<String>,
    )

    @Query("UPDATE notification_cache SET readAt = :now, status = 'read' WHERE connectionId = :connectionId AND recipientId = :recipientId")
    suspend fun markRead(
        connectionId: String,
        recipientId: String,
        now: String,
    )

    @Query("UPDATE notification_cache SET dismissedAt = :now WHERE connectionId = :connectionId AND recipientId = :recipientId")
    suspend fun markDismissed(
        connectionId: String,
        recipientId: String,
        now: String,
    )

    @Query("UPDATE notification_cache SET readAt = COALESCE(readAt, :now), status = 'read' WHERE connectionId = :connectionId")
    suspend fun markAllRead(
        connectionId: String,
        now: String,
    )

    @Query("SELECT * FROM notification_cache WHERE connectionId = :connectionId ORDER BY createdAt IS NULL, createdAt DESC, rowid DESC")
    fun observeAll(connectionId: String): Flow<List<CachedNotification>>

    @Query("SELECT COUNT(*) FROM notification_cache WHERE connectionId = :connectionId AND readAt IS NULL")
    fun observeUnreadCount(connectionId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM notification_cache WHERE connectionId = :connectionId AND readAt IS NULL")
    suspend fun unreadCountSnapshot(connectionId: String): Int

    @Query("DELETE FROM notification_cache WHERE connectionId = :connectionId")
    suspend fun clearAll(connectionId: String)

    @Query("SELECT COUNT(*) FROM notification_cache WHERE connectionId = :connectionId")
    suspend fun rowCount(connectionId: String): Int
}
