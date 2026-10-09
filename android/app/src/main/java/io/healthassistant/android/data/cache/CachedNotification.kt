package io.healthassistant.android.data.cache

import androidx.room.Entity
import androidx.room.Index
import io.healthassistant.shared.data.cache.CachedNotificationRow

/**
 * Room entity mirroring [CachedNotificationRow] 1:1 (offline-first M6). The
 * unread badge query filters on `readAt IS NULL` (indexed).
 *
 * Offline-first M9: rows are scoped by `connectionId` (part of the primary
 * key + the read index) — the owner's inbox is per bridge connection.
 */
@Entity(
    tableName = "notification_cache",
    primaryKeys = ["connectionId", "recipientId"],
    indices = [Index(value = ["connectionId", "readAt"])],
)
data class CachedNotification(
    val connectionId: String,
    val recipientId: String,
    val status: String?,
    val readAt: String?,
    val dismissedAt: String?,
    val notificationId: String?,
    val title: String?,
    val body: String?,
    val type: String?,
    val category: String?,
    val severity: String?,
    val source: String?,
    val payloadJson: String?,
    val patientId: String?,
    val createdAt: String?,
)

/** Entity ⇄ the shared row. */
fun CachedNotification.toRow(): CachedNotificationRow =
    CachedNotificationRow(
        recipientId = recipientId,
        status = status,
        readAt = readAt,
        dismissedAt = dismissedAt,
        notificationId = notificationId,
        title = title,
        body = body,
        type = type,
        category = category,
        severity = severity,
        source = source,
        payloadJson = payloadJson,
        patientId = patientId,
        createdAt = createdAt,
    )

/** Entity ⇄ the shared row. */
fun CachedNotificationRow.toEntity(connectionId: String): CachedNotification =
    CachedNotification(
        connectionId = connectionId,
        recipientId = recipientId,
        status = status,
        readAt = readAt,
        dismissedAt = dismissedAt,
        notificationId = notificationId,
        title = title,
        body = body,
        type = type,
        category = category,
        severity = severity,
        source = source,
        payloadJson = payloadJson,
        patientId = patientId,
        createdAt = createdAt,
    )
