package io.healthassistant.android.data.cache

import androidx.room.withTransaction
import io.healthassistant.bridge.NotificationItem
import io.healthassistant.shared.data.cache.CacheRefreshMeta
import io.healthassistant.shared.data.cache.CachedNotificationRow
import io.healthassistant.shared.data.cache.NotificationCache
import io.healthassistant.shared.data.cache.NotificationCacheMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

/**
 * Android Room implementation of the shared [NotificationCache] (offline-first
 * M6), bound to one bridge connection (offline-first M9): every read, write,
 * mark op, and reconcile is scoped by the connection id. The mark ops stamp the
 * current time (matching the server's read_at / dismissed_at semantics) and
 * only run after the matching bridge call succeeded (the repository enforces
 * that ordering). A `*Synced` write records the cache_meta success row in the
 * SAME Room transaction as the upsert.
 */
class RoomNotificationCache(
    private val db: ObservationDatabase,
    private val connectionId: String,
) : NotificationCache {
    private val dao get() = db.notificationDao()

    override suspend fun storeAll(rows: List<CachedNotificationRow>) {
        if (rows.isEmpty()) return
        dao.upsertAll(rows.map { it.toEntity(connectionId) })
    }

    override suspend fun storeAllSynced(
        rows: List<CachedNotificationRow>,
        meta: CacheRefreshMeta,
    ) {
        db.withTransaction {
            if (rows.isNotEmpty()) dao.upsertAll(rows.map { it.toEntity(connectionId) })
            db.cacheMetaDao().recordSuccess(connectionId, meta.domain.wire, meta.successAtEpochMs ?: 0L, dao.rowCount(connectionId))
        }
    }

    override suspend fun reconcile(ids: List<String>) {
        // Guard the empty-collection bind (`NOT IN ()` is invalid SQLite).
        if (ids.isEmpty()) dao.clearAll(connectionId) else dao.reconcile(connectionId, ids)
    }

    override suspend fun markRead(recipientId: String) = dao.markRead(connectionId, recipientId, Instant.now().toString())

    override suspend fun markDismissed(recipientId: String) = dao.markDismissed(connectionId, recipientId, Instant.now().toString())

    override suspend fun markAllRead() = dao.markAllRead(connectionId, Instant.now().toString())

    override fun observeAll(): Flow<List<NotificationItem>> =
        dao.observeAll(connectionId).map { rows -> rows.map { NotificationCacheMapper.toModel(it.toRow()) } }

    override fun observeUnreadCount(): Flow<Int> = dao.observeUnreadCount(connectionId)

    override suspend fun observeUnreadCountSnapshot(): Int = dao.unreadCountSnapshot(connectionId)

    override suspend fun clear() = dao.clearAll(connectionId)
}
