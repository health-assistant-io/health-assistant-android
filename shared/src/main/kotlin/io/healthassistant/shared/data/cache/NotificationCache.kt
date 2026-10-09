package io.healthassistant.shared.data.cache

import io.healthassistant.bridge.NotificationEnvelope
import io.healthassistant.bridge.NotificationItem
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Flat, Room-friendly row for a notification-inbox item (offline-first M6),
 * mirroring the SDK's [NotificationItem] + its nested [NotificationEnvelope]
 * with the polymorphic `payload` encoded as a string. Pure-Kotlin so the cache
 * interface + this mapper live in the KMP `shared` core (JVM-testable); the
 * Android entity mirrors the row 1:1 — see
 * `android/data/cache/CachedNotification.kt`.
 */
data class CachedNotificationRow(
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

/** SDK model ⇄ cache row; the envelope is flattened onto the row. The payload
 *  decode is tolerant: a corrupt stored string decodes to null. */
object NotificationCacheMapper {
    private val json = Json { ignoreUnknownKeys = true }

    fun toRow(item: NotificationItem): CachedNotificationRow =
        CachedNotificationRow(
            recipientId = item.recipientId,
            status = item.status,
            readAt = item.readAt,
            dismissedAt = item.dismissedAt,
            notificationId = item.notification?.id,
            title = item.notification?.title,
            body = item.notification?.body,
            type = item.notification?.type,
            category = item.notification?.category,
            severity = item.notification?.severity,
            source = item.notification?.source,
            payloadJson = item.notification?.payload?.toString(),
            patientId = item.notification?.patientId,
            createdAt = item.notification?.createdAt,
        )

    fun toModel(r: CachedNotificationRow): NotificationItem =
        NotificationItem(
            recipientId = r.recipientId,
            status = r.status,
            readAt = r.readAt,
            dismissedAt = r.dismissedAt,
            notification =
                if (r.notificationId == null && r.title == null) {
                    null
                } else {
                    NotificationEnvelope(
                        id = r.notificationId ?: r.recipientId,
                        title = r.title.orEmpty(),
                        body = r.body,
                        type = r.type,
                        category = r.category,
                        severity = r.severity,
                        source = r.source,
                        payload = r.payloadJson?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() },
                        patientId = r.patientId,
                        createdAt = r.createdAt,
                    )
                },
        )
}

/**
 * The on-device notification-inbox cache (offline-first M6). The inbox is
 * owner-scoped (addressed to the integration owner, not the bound patient).
 * Writes upsert by `recipient_id`; a refresh follows with a reconcile that
 * drops rows missing from the snapshot. Reads are reactive so the Inbox + the
 * Today preview + the unread badge re-render the moment anything changes.
 *
 * Mark-read/dismiss apply optimistically via [markRead]/[markDismissed] so the
 * UI updates instantly after the bridge call succeeds, without a refetch.
 */
interface NotificationCache {
    /** Upsert rows by recipient id (a re-fetch updates in place). */
    suspend fun storeAll(rows: List<CachedNotificationRow>)

    /** [storeAll] + the cache_meta success row in the SAME Room transaction
     *  (offline-first M9). */
    suspend fun storeAllSynced(
        rows: List<CachedNotificationRow>,
        meta: CacheRefreshMeta,
    )

    /** Drop rows whose recipient id is not in [ids] (post-refresh reconcile). */
    suspend fun reconcile(ids: List<String>)

    /** Optimistically set read_at (after a successful remote mark-read). */
    suspend fun markRead(recipientId: String)

    /** Optimistically set dismissed_at (after a successful remote mark-dismiss). */
    suspend fun markDismissed(recipientId: String)

    /** Optimistically mark every row read (after a successful read-all). */
    suspend fun markAllRead()

    /** The inbox, newest created first. Reactive. */
    fun observeAll(): Flow<List<NotificationItem>>

    /** The count of rows with no read_at. Reactive. */
    fun observeUnreadCount(): Flow<Int>

    /** One-shot unread count (reconcile against the server count). */
    suspend fun observeUnreadCountSnapshot(): Int

    /** Drop every cached row. */
    suspend fun clear()
}
