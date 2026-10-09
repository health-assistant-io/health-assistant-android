package io.healthassistant.shared.data.repository

import io.healthassistant.bridge.NotificationItem
import io.healthassistant.shared.data.cache.CacheDomain
import io.healthassistant.shared.data.cache.CacheMetaStore
import io.healthassistant.shared.data.cache.CacheRefreshMeta
import io.healthassistant.shared.data.cache.NotificationCache
import io.healthassistant.shared.data.cache.NotificationCacheMapper
import kotlinx.coroutines.flow.Flow

/**
 * Network read abstraction for the notification inbox (offline-first M6),
 * isolating [NotificationRepository] from the SDK's ktor-dependent
 * `BridgeClient` so it stays pure-Kotlin + JVM-testable. Mutation methods
 * return true on success; the repository applies cache updates only then.
 */
interface NotificationGateway {
    /** `GET /notifications/inbox` — the owner-scoped inbox snapshot. */
    suspend fun inbox(limit: Int = 50): List<NotificationItem>

    /** `PATCH /notifications/{id}/read`. */
    suspend fun markRead(recipientId: String): Boolean

    /** `PATCH /notifications/{id}/dismiss`. */
    suspend fun markDismissed(recipientId: String): Boolean

    /** `POST /notifications/read-all`. Returns the number marked read. */
    suspend fun markAllRead(): Int

    /** `GET /notifications/unread-count` — the server-side badge truth. */
    suspend fun unreadCount(): Int
}

/**
 * Single-Source-of-Truth repository for the notification inbox (offline-first
 * M6). `observe*` reads the Room-backed cache (reactive, instant, offline);
 * [refresh] decouples the network (success → upsert + reconcile; offline /
 * failure → the cache is left untouched). Mark-read/dismiss/all apply the
 * cache update only after the bridge call succeeds, so the UI updates
 * instantly without a refetch round-trip and never drifts on failure.
 */
class NotificationRepository(
    private val cache: NotificationCache,
    private val gateway: NotificationGateway,
    private val connectivity: ConnectivityProvider,
    private val meta: CacheMetaStore,
) {
    /** The inbox, newest created first. Reactive. */
    fun observeAll(): Flow<List<NotificationItem>> = cache.observeAll()

    /** The unread badge count. Reactive. */
    fun observeUnreadCount(): Flow<Int> = cache.observeUnreadCount()

    /** Best-effort inbox refresh. No-op when offline; on success upserts +
     *  reconciles. Never throws. */
    suspend fun refresh(limit: Int = 50): RefreshOutcome {
        if (!connectivity.isOnline()) return RefreshOutcome.OFFLINE
        return try {
            val items = gateway.inbox(limit)
            cache.storeAllSynced(items.map(NotificationCacheMapper::toRow), CacheRefreshMeta.success(CacheDomain.NOTIFICATIONS, System.currentTimeMillis()))
            cache.reconcile(items.map { it.recipientId })
            RefreshOutcome.REFRESHED
        } catch (e: Exception) {
            meta.recordRefresh(CacheRefreshMeta.failure(CacheDomain.NOTIFICATIONS, e.message))
            RefreshOutcome.FAILED
        }
    }

    /**
     * Reconcile the cached unread badge with the server count: when the
     * server reports MORE unread than the cache knows, the first inbox page
     * missed rows (limit window / another device read them) — re-pull with a
     * higher limit. Returns the effective unread count (cache stays the
     * reactive source for the UI).
     */
    suspend fun reconcileUnreadCount(): Int {
        if (!connectivity.isOnline()) return -1
        val server =
            try {
                gateway.unreadCount()
            } catch (e: Exception) {
                return -1
            }
        val cached = observeUnreadCountSnapshot()
        if (server > cached) refresh(limit = 200)
        return server
    }

    private suspend fun observeUnreadCountSnapshot(): Int =
        cache.observeUnreadCountSnapshot()

    /** Mark one read (bridge first; the cache updates only on success). */
    suspend fun markRead(recipientId: String): Boolean {
        val ok = try {
            gateway.markRead(recipientId)
        } catch (e: Exception) {
            false
        }
        if (ok) cache.markRead(recipientId)
        return ok
    }

    /** Mark one dismissed (bridge first; the cache updates only on success). */
    suspend fun markDismissed(recipientId: String): Boolean {
        val ok = try {
            gateway.markDismissed(recipientId)
        } catch (e: Exception) {
            false
        }
        if (ok) cache.markDismissed(recipientId)
        return ok
    }

    /** Mark everything read (bridge first; the cache updates only on success). */
    suspend fun markAllRead(): Boolean {
        val ok =
            try {
                gateway.markAllRead() >= 0
            } catch (e: Exception) {
                false
            }
        if (ok) cache.markAllRead()
        return ok
    }

    /** Drop every cached row + the domain's staleness row (the Settings
     *  "clear cached data" action). */
    suspend fun clear() {
        cache.clear()
        meta.clearDomain(CacheDomain.NOTIFICATIONS)
    }
}
