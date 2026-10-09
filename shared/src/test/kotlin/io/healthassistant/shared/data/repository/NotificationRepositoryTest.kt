package io.healthassistant.shared.data.repository

import io.healthassistant.bridge.NotificationEnvelope
import io.healthassistant.bridge.NotificationItem
import io.healthassistant.shared.data.cache.CachedNotificationRow
import io.healthassistant.shared.data.cache.NotificationCache
import io.healthassistant.shared.data.cache.NotificationCacheMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * JVM tests for the offline-first invariants of [NotificationRepository] (M6):
 * cache-first observe with no gateway call, refresh upserts + reconciles,
 * offline / failed refreshes leave the saved inbox intact, and the mark ops
 * apply cache updates only after a successful bridge call.
 */
class NotificationRepositoryTest {
    @Test
    fun `observe emits cached inbox with no gateway call`() =
        runTest {
            val cache = FakeNotificationCache()
            cache.storeAll(listOf(NotificationCacheMapper.toRow(item("r1", "Lab results ready"))))
            val gateway = RecordingGateway()
            val repo = NotificationRepository(cache, gateway, ConnectivityProvider { true }, FakeCacheMetaStore())

            val items = repo.observeAll().first()

            assertEquals(listOf("r1"), items.map { it.recipientId })
            assertEquals("gateway must not be called for a plain observe", 0, gateway.inboxCalls)
        }

    @Test
    fun `reconcile repulls wider page when server knows more unread`() =
        runTest {
            val cache = FakeNotificationCache()
            val gateway =
                RecordingGateway(
                    inbox = listOf(item("r1", "Critical", unread = true), item("r2", "Info")),
                    unreadCountValue = 3,
                )
            val repo = NotificationRepository(cache, gateway, ConnectivityProvider { true }, FakeCacheMetaStore())
            repo.refresh()
            assertEquals(1, gateway.inboxCalls)
            assertEquals(50, gateway.lastInboxLimit)

            val serverCount = repo.reconcileUnreadCount()

            assertEquals(3, serverCount)
            assertEquals("a higher server unread count triggers a wider re-pull", 2, gateway.inboxCalls)
            assertEquals(200, gateway.lastInboxLimit)
        }

    @Test
    fun `reconcile skips repull when cache agrees with server`() =
        runTest {
            val cache = FakeNotificationCache()
            val gateway =
                RecordingGateway(
                    inbox = listOf(item("r1", "Critical", unread = true), item("r2", "Info")),
                    unreadCountValue = 1,
                )
            val repo = NotificationRepository(cache, gateway, ConnectivityProvider { true }, FakeCacheMetaStore())
            repo.refresh()

            val serverCount = repo.reconcileUnreadCount()

            assertEquals(1, serverCount)
            assertEquals("no re-pull when the badge agrees", 1, gateway.inboxCalls)
        }

    @Test
    fun `reconcile returns -1 offline or on gateway failure`() =
        runTest {
            val offlineRepo = NotificationRepository(FakeNotificationCache(), RecordingGateway(), ConnectivityProvider { false }, FakeCacheMetaStore())
            assertEquals(-1, offlineRepo.reconcileUnreadCount())

            val failingRepo =
                NotificationRepository(FakeNotificationCache(), RecordingGateway(unreadCountThrows = Exception("net")), ConnectivityProvider { true }, FakeCacheMetaStore())
            assertEquals(-1, failingRepo.reconcileUnreadCount())
        }

    @Test
    fun `refresh upserts reconciles and round-trips the envelope`() =
        runTest {
            val cache = FakeNotificationCache()
            cache.storeAll(
                listOf(
                    NotificationCacheMapper.toRow(item("r1", "Old")),
                    NotificationCacheMapper.toRow(item("r2", "Vanishes")),
                ),
            )
            val gateway = RecordingGateway(inbox = listOf(item("r1", "Lab results ready", unread = true)))
            val repo = NotificationRepository(cache, gateway, ConnectivityProvider { true }, FakeCacheMetaStore())

            val outcome = repo.refresh()

            assertEquals(RefreshOutcome.REFRESHED, outcome)
            val items = repo.observeAll().first()
            assertEquals("an item gone server-side must disappear locally", listOf("r1"), items.map { it.recipientId })
            assertEquals("Lab results ready", items.single().notification?.title)
            assertEquals(1, repo.observeUnreadCount().first())
        }

    @Test
    fun `offline refresh returns OFFLINE and keeps the saved inbox`() =
        runTest {
            val cache = FakeNotificationCache()
            cache.storeAll(listOf(NotificationCacheMapper.toRow(item("r1", "Old"))))
            val gateway = RecordingGateway()
            val repo = NotificationRepository(cache, gateway, ConnectivityProvider { false }, FakeCacheMetaStore())

            assertEquals(RefreshOutcome.OFFLINE, repo.refresh())
            assertEquals("offline must skip the network entirely", 0, gateway.inboxCalls)
            assertEquals(1, repo.observeAll().first().size)
        }

    @Test
    fun `failed refresh keeps the saved inbox`() =
        runTest {
            val cache = FakeNotificationCache()
            cache.storeAll(listOf(NotificationCacheMapper.toRow(item("r1", "Old"))))
            val gateway = RecordingGateway(inboxThrows = IllegalStateException("503"))
            val repo = NotificationRepository(cache, gateway, ConnectivityProvider { true }, FakeCacheMetaStore())

            assertEquals(RefreshOutcome.FAILED, repo.refresh())
            assertEquals(1, repo.observeAll().first().size)
        }

    @Test
    fun `markRead applies the cache update only on bridge success`() =
        runTest {
            val cache = FakeNotificationCache()
            cache.storeAll(listOf(NotificationCacheMapper.toRow(item("r1", "Unread", unread = true))))
            val gatewayOk = RecordingGateway()
            val gatewayFails = RecordingGateway(markReadThrows = IllegalStateException("offline"))
            val repo =
                NotificationRepository(
                    cache,
                    gatewayFails,
                    ConnectivityProvider { true },
                    FakeCacheMetaStore(),
                )

            assertFalse(repo.markRead("r1"))
            assertEquals("a failed remote mark must not touch the cache", 1, repo.observeUnreadCount().first())

            val repoOk = NotificationRepository(cache, gatewayOk, ConnectivityProvider { true }, FakeCacheMetaStore())
            assertTrue(repoOk.markRead("r1"))
            assertEquals(0, repo.observeUnreadCount().first())
        }

    @Test
    fun `markAllRead clears the unread badge`() =
        runTest {
            val cache = FakeNotificationCache()
            cache.storeAll(
                listOf(
                    NotificationCacheMapper.toRow(item("r1", "A", unread = true)),
                    NotificationCacheMapper.toRow(item("r2", "B", unread = true)),
                ),
            )
            val repo = NotificationRepository(cache, RecordingGateway(), ConnectivityProvider { true }, FakeCacheMetaStore())

            assertTrue(repo.markAllRead())

            assertEquals(0, repo.observeUnreadCount().first())
        }

    private fun item(
        recipientId: String,
        title: String,
        unread: Boolean = false,
    ): NotificationItem =
        NotificationItem(
            recipientId = recipientId,
            status = if (unread) "unread" else "read",
            readAt = if (unread) null else Instant.now().toString(),
            notification =
                NotificationEnvelope(
                    id = "n-$recipientId",
                    title = title,
                    createdAt = "2026-08-14T10:00:00Z",
                ),
        )

    private class RecordingGateway(
        private val inbox: List<NotificationItem> = emptyList(),
        private val inboxThrows: Throwable? = null,
        private val markReadThrows: Throwable? = null,
        private val unreadCountValue: Int = 0,
        private val unreadCountThrows: Throwable? = null,
    ) : NotificationGateway {
        var inboxCalls = 0
            private set
        var lastInboxLimit = 0
            private set

        override suspend fun inbox(limit: Int): List<NotificationItem> {
            inboxCalls++
            lastInboxLimit = limit
            inboxThrows?.let { throw it }
            return inbox
        }

        override suspend fun markRead(recipientId: String): Boolean {
            markReadThrows?.let { throw it }
            return true
        }

        override suspend fun markDismissed(recipientId: String): Boolean = true

        override suspend fun markAllRead(): Int = 5

        override suspend fun unreadCount(): Int {
            unreadCountThrows?.let { throw it }
            return unreadCountValue
        }
    }

    /** In-memory [NotificationCache] mirroring the Room semantics. */
    private class FakeNotificationCache : NotificationCache {
        private val rows = MutableStateFlow<List<CachedNotificationRow>>(emptyList())
        private val tick = MutableStateFlow(0)

        override suspend fun storeAll(newRows: List<CachedNotificationRow>) {
            val byId = rows.value.associateBy { it.recipientId }.toMutableMap()
            newRows.forEach { byId[it.recipientId] = it }
            rows.value = byId.values.sortedByDescending { it.createdAt ?: "" }
        }

        override suspend fun storeAllSynced(
            newRows: List<CachedNotificationRow>,
            meta: io.healthassistant.shared.data.cache.CacheRefreshMeta,
        ) = storeAll(newRows)

        override suspend fun reconcile(ids: List<String>) {
            rows.value = rows.value.filter { it.recipientId in ids }
        }

        override suspend fun markRead(recipientId: String) = mutate(recipientId) { it.copy(readAt = Instant.now().toString(), status = "read") }

        override suspend fun markDismissed(recipientId: String) = mutate(recipientId) { it.copy(dismissedAt = Instant.now().toString()) }

        override suspend fun markAllRead() {
            rows.value = rows.value.map { it.copy(readAt = it.readAt ?: Instant.now().toString(), status = "read") }
            tick.value++
        }

        override fun observeAll(): Flow<List<NotificationItem>> =
            combine(rows, tick) { list, _ -> list }.map { list -> list.map(NotificationCacheMapper::toModel) }

        override fun observeUnreadCount(): Flow<Int> =
            combine(rows, tick) { list, _ -> list }.map { list -> list.count { it.readAt == null } }

        override suspend fun observeUnreadCountSnapshot(): Int = rows.value.count { it.readAt == null }

        override suspend fun clear() {
            rows.value = emptyList()
        }

        private fun mutate(
            recipientId: String,
            transform: (CachedNotificationRow) -> CachedNotificationRow,
        ) {
            rows.value = rows.value.map { if (it.recipientId == recipientId) transform(it) else it }
        }
    }
}
