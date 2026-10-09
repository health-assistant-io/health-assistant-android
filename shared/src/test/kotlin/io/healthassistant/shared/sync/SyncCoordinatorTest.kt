package io.healthassistant.shared.sync

import io.healthassistant.bridge.ClientRecord
import io.healthassistant.bridge.SyncPayload
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCoordinatorTest {

    private val json = Json { encodeDefaults = false }

    private fun record(code: String, name: String, value: Double) =
        ClientRecord(type = "quantitative", code = code, codingSystem = "loinc", name = name, value = value)

    private fun syncItem(id: String, records: List<ClientRecord> = emptyList(), cursor: String? = null): OutboxItem {
        val payload = SyncPayload(clientVersion = "0.1", sourceSystem = "test", cursor = cursor, records = records.takeIf { it.isNotEmpty() })
        return OutboxItem(id = id, method = "POST", path = "/sync", payload = json.encodeToString(SyncPayload.serializer(), payload).encodeToByteArray())
    }

    private class FakeSender : SyncSender {
        val calls = mutableListOf<Triple<String, String, ByteArray>>()
        var result: (Triple<String, String, ByteArray>) -> SendResult = { SendResult.Success }
        override suspend fun send(method: String, path: String, body: ByteArray): SendResult {
            val rec = Triple(method, path, body)
            calls += rec
            return result(rec)
        }
    }

    @Test
    fun `groups consecutive sync items into one request`() = runBlocking {
        val store = InMemoryOutboxStore()
        val sender = FakeSender()
        val coord = SyncCoordinator(store, sender)
        listOf("a", "b", "c").forEach { store.enqueue(syncItem(it, records = listOf(record("8867-4", "Heart Rate", 75.0)))) }

        val r = coord.drain(now = 0L)

        assertEquals(3, r.synced)
        assertEquals(1, sender.calls.size)
        assertEquals("/sync", sender.calls[0].second)
        assertEquals(0, store.pendingCount())
    }

    @Test
    fun `non-sync paths sent individually`() = runBlocking {
        val store = InMemoryOutboxStore()
        val sender = FakeSender()
        val coord = SyncCoordinator(store, sender)
        store.enqueue(syncItem("a"))
        store.enqueue(syncItem("b"))
        store.enqueue(OutboxItem(id = "e", method = "POST", path = "/examinations", payload = "{}".encodeToByteArray()))

        coord.drain(now = 0L)

        assertEquals(2, sender.calls.size)
        assertEquals(setOf("/sync", "/examinations"), sender.calls.map { it.second }.toSet())
        assertEquals(0, store.pendingCount())
    }

    @Test
    fun `transient failure retries then succeeds`() = runBlocking {
        val store = InMemoryOutboxStore()
        var call = 0
        val sender = SyncSender { _, _, _ ->
            call++
            if (call == 1) SendResult.Transient(503) else SendResult.Success
        }
        val coord = SyncCoordinator(store, sender)
        store.enqueue(syncItem("a"))

        val r1 = coord.drain(now = 1_000L)
        assertEquals(0, r1.synced)
        assertEquals(1, r1.retried)

        val item = store.get("a")!!
        assertEquals(OutboxStatus.PENDING, item.status)
        assertEquals(1, item.attempts)
        // full-jitter can yield 0, so nextAttemptAt may equal `now`; bounded by the ceiling.
        assertTrue("backoff not in the past", item.nextAttemptAt >= 1_000L)
        assertTrue("backoff within ceiling", item.nextAttemptAt <= 1_000L + 8_000L)

        val r2 = coord.drain(now = item.nextAttemptAt + 1)
        assertEquals(1, r2.synced)
        assertEquals(0, store.pendingCount())
    }

    @Test
    fun `permanent failure dead-letters`() = runBlocking {
        val store = InMemoryOutboxStore()
        val sender = FakeSender().apply { result = { SendResult.Permanent(400, "bad payload") } }
        val coord = SyncCoordinator(store, sender)
        store.enqueue(syncItem("a"))

        coord.drain(now = 1_000L)

        val dl = store.deadLetters()
        assertEquals(1, dl.size)
        assertEquals("a", dl[0].id)
    }

    @Test
    fun `re-drain after sync is a no-op`() = runBlocking {
        val store = InMemoryOutboxStore()
        val sender = FakeSender()
        val coord = SyncCoordinator(store, sender)
        store.enqueue(syncItem("a"))

        coord.drain(now = 0L)
        val r2 = coord.drain(now = 0L)

        assertTrue(r2.isEmpty)
    }

    @Test
    fun `large lane drained one at a time`() = runBlocking {
        val store = InMemoryOutboxStore()
        val sender = FakeSender()
        val coord = SyncCoordinator(store, sender)
        store.enqueue(OutboxItem(id = "d1", method = "POST", path = "/examinations/x/documents", lane = OutboxLane.LARGE, contentRef = "/cache/doc1.pdf"))
        store.enqueue(OutboxItem(id = "d2", method = "POST", path = "/examinations/x/documents", lane = OutboxLane.LARGE, contentRef = "/cache/doc2.pdf"))

        coord.drain(now = 0L)
        assertEquals(1, sender.calls.size)
        coord.drain(now = 0L)
        assertEquals(2, sender.calls.size)
        assertEquals(0, store.pendingCount(OutboxLane.LARGE))
    }

    @Test
    fun `max attempts dead-letters on repeated transient failures`() = runBlocking {
        val store = InMemoryOutboxStore()
        val sender = FakeSender().apply { result = { SendResult.Transient(503) } }
        val coord = SyncCoordinator(store, sender, SyncCoordinator.Config(maxAttempts = 2))
        store.enqueue(syncItem("a"))

        coord.drain(now = 1L) // attempts 0→1, retry
        val item = store.get("a")!!
        assertEquals(1, item.attempts)
        coord.drain(now = item.nextAttemptAt + 1) // attempts 1→2 == maxAttempts → dead
        assertEquals(1, store.deadLetters().size)
    }

    @Test
    fun `429 rate limit never dead-letters regardless of attempts`() = runBlocking {
        val store = InMemoryOutboxStore()
        val sender = FakeSender().apply { result = { SendResult.Transient(429) } }
        val coord =
            SyncCoordinator(
                store,
                sender,
                SyncCoordinator.Config(maxAttempts = 2, rateLimitBackoffMs = 90_000L, rateLimitJitterMs = 30_000L),
            )
        store.enqueue(syncItem("a"))

        var now = 1_000L
        repeat(10) {
            val r = coord.drain(now = now)
            assertEquals(1, r.retried)
            assertEquals(0, r.deadLettered)
            assertTrue("drain flags rate-limit", r.rateLimitedUntil != null)
            val item = store.get("a")!!
            assertEquals(OutboxStatus.PENDING, item.status)
            assertTrue("backoff floors at rateLimitBackoffMs", item.nextAttemptAt >= now + 90_000L)
            assertTrue("backoff bounded by floor+jitter", item.nextAttemptAt <= now + 120_000L)
            now = item.nextAttemptAt + 1
        }
        assertEquals("still nothing dead-lettered", 0, store.deadLetters().size)
    }

    @Test
    fun `429 stops the drain without claiming the rest of the lane`() = runBlocking {
        val store = InMemoryOutboxStore()
        val sender = FakeSender().apply { result = { SendResult.Transient(429) } }
        val coord = SyncCoordinator(store, sender)
        repeat(5) { store.enqueue(OutboxItem(id = "e$it", method = "POST", path = "/examinations", payload = "{}".encodeToByteArray())) }

        val r = coord.drain(now = 1_000L)

        assertEquals("one request then stop", 1, sender.calls.size)
        assertTrue(r.rateLimitedUntil != null)
        assertEquals("rest of the lane untouched", 5, store.pendingCount())
    }

    @Test
    fun `429 on the default lane skips the large lane`() = runBlocking {
        val store = InMemoryOutboxStore()
        val sender = FakeSender().apply { result = { SendResult.Transient(429) } }
        val coord = SyncCoordinator(store, sender)
        store.enqueue(syncItem("a"))
        store.enqueue(OutboxItem(id = "d1", method = "POST", path = "/examinations/x/documents", lane = OutboxLane.LARGE, contentRef = "/cache/doc1.pdf"))

        val r = coord.drain(now = 1_000L)

        assertEquals("large lane not touched after the 429", 1, sender.calls.size)
        assertEquals("/sync", sender.calls[0].second)
        assertTrue(r.rateLimitedUntil != null)
        assertEquals(1, store.pendingCount(OutboxLane.LARGE))
    }

    @Test
    fun `429 recovery syncs on the next window`() = runBlocking {
        val store = InMemoryOutboxStore()
        var call = 0
        val sender = SyncSender { _, _, _ ->
            call++
            if (call == 1) SendResult.Transient(429) else SendResult.Success
        }
        val coord = SyncCoordinator(store, sender)
        store.enqueue(syncItem("a"))

        val r1 = coord.drain(now = 1_000L)
        assertTrue(r1.rateLimitedUntil != null)
        val item = store.get("a")!!
        val r2 = coord.drain(now = item.nextAttemptAt + 1)
        assertEquals(1, r2.synced)
        assertEquals(0, store.pendingCount())
    }
}
