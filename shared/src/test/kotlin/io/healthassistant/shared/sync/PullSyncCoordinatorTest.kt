package io.healthassistant.shared.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase G — JVM tests for [PullSyncCoordinator]. The coordinator is the
 * crash-safe heart of two-way incremental sync: fetch → parse → apply → advance
 * cursor, where the cursor advances ONLY after a successful apply.
 *
 * Covers the plan §G test gate: enqueue a delta (cursor advances); inject a
 * failure mid-apply (cursor does NOT advance); incremental pulls use the new
 * cursor; a null server cursor (nothing changed) leaves the cursor untouched.
 */
class PullSyncCoordinatorTest {
    @Test
    fun `applies delta and advances cursor on success`() = runBlocking {
        val store = FakePullSyncStore()
        val fetch =
            fetchReturning(
                envelope(
                    cursor = "2026-08-12T10:00:00+00:00",
                    medications = listOf(row("m1"), row("m2")),
                    examinations = listOf(row("e1")),
                ),
            )
        val coord = PullSyncCoordinator(fetch, store)

        val result = coord.pull(now = 1L)

        assertEquals(3, result.totalChanged)
        assertEquals(2, result.counts[ChangeType.MEDICATIONS])
        assertEquals(1, result.counts[ChangeType.EXAMINATIONS])
        assertEquals("2026-08-12T10:00:00Z", result.newCursor)
        assertTrue(result.changed)
        assertEquals(1, store.applied.size)
        assertEquals(2, store.applied.single().medications.size)
        assertEquals("2026-08-12T10:00:00Z", store.savedCursor)
    }

    @Test
    fun `first pull uses null cursor when store has none`() = runBlocking {
        val store = FakePullSyncStore()
        val fetch = FetchRecorder(envelope(cursor = null))
        val coord = PullSyncCoordinator(fetch::fetch, store)

        coord.pull()

        assertNull(fetch.sinceArguments.single())
    }

    @Test
    fun `second pull passes the advanced cursor as since`() = runBlocking {
        val store = FakePullSyncStore()
        val fetch =
            FetchRecorder(
                envelope(cursor = "2026-08-12T10:00:00+00:00", medications = listOf(row("m1"))),
            )
        val coord = PullSyncCoordinator(fetch::fetch, store)

        coord.pull()
        coord.pull()

        assertEquals(listOf(null, "2026-08-12T10:00:00Z"), fetch.sinceArguments)
    }

    @Test
    fun `null server cursor does not advance and applies an empty delta`() = runBlocking {
        val store = FakePullSyncStore(initialCursor = "2026-08-10T00:00:00+00:00")
        val fetch = fetchReturning(envelope(cursor = null))
        val coord = PullSyncCoordinator(fetch, store)

        val result = coord.pull(now = 5L)

        assertFalse(result.changed)
        assertEquals(0, result.totalChanged)
        // The prior cursor is retained as newCursor (normalized to URL-safe Z form).
        assertEquals("2026-08-10T00:00:00Z", result.newCursor)
        // saveCursor is NOT called when nothing changed.
        assertNull(store.savedCursor)
        // applyDelta still fires (the delta is empty — the store may use it as a
        // heartbeat), but carries zero rows.
        assertEquals(1, store.applied.size)
        assertTrue(store.applied.single().isEmpty)
    }

    @Test
    fun `applyDelta failure does NOT advance cursor (crash-safe)`() = runBlocking {
        val store =
            FakePullSyncStore(
                initialCursor = "2026-08-10T00:00:00+00:00",
                applyBehavior = { throw RuntimeException("cache write failed") },
            )
        val fetch = fetchReturning(envelope(cursor = "2026-08-12T10:00:00+00:00", allergies = listOf(row("a1"))))
        val coord = PullSyncCoordinator(fetch, store)

        assertThrows(RuntimeException::class.java) {
            runBlocking { coord.pull() }
        }

        // Cursor unchanged — next pull re-fetches the same window (idempotent).
        assertEquals("2026-08-10T00:00:00+00:00", store.currentCursor())
        assertNull(store.savedCursor)
    }

    @Test
    fun `fetch failure surfaces PullFetchException and keeps cursor`() = runBlocking {
        val store = FakePullSyncStore(initialCursor = "2026-08-10T00:00:00+00:00")
        val fetch = FetchFailing("server 503")
        val coord = PullSyncCoordinator(fetch::fetch, store)

        assertThrows(PullFetchException::class.java) {
            runBlocking { coord.pull() }
        }

        assertEquals("2026-08-10T00:00:00+00:00", store.currentCursor())
        assertEquals(0, store.applied.size)
        assertNull(store.savedCursor)
    }

    @Test
    fun `PullFetchException carries the cursor in use`() = runBlocking {
        val store = FakePullSyncStore(initialCursor = "cur-123")
        val fetch = FetchFailing("network")
        val coord = PullSyncCoordinator(fetch::fetch, store)

        val ex =
            assertThrows(PullFetchException::class.java) {
                runBlocking { coord.pull() }
            }
        assertEquals("cur-123", ex.cursor)
    }

    @Test
    fun `parses every type family from the envelope`() = runBlocking {
        val store = FakePullSyncStore()
        val fetch =
            fetchReturning(
                envelope(
                    cursor = "c1",
                    medications = listOf(row("m1")),
                    allergies = listOf(row("a1"), row("a2")),
                    vaccines = listOf(row("v1")),
                    clinicalEvents = listOf(row("ce1")),
                    documents = listOf(row("d1")),
                    examinations = listOf(row("e1")),
                ),
            )
        val coord = PullSyncCoordinator(fetch, store)

        val result = coord.pull()

        val counts = result.counts
        assertEquals(1, counts[ChangeType.MEDICATIONS])
        assertEquals(2, counts[ChangeType.ALLERGIES])
        assertEquals(1, counts[ChangeType.VACCINES])
        assertEquals(1, counts[ChangeType.CLINICAL_EVENTS])
        assertEquals(1, counts[ChangeType.DOCUMENTS])
        assertEquals(1, counts[ChangeType.EXAMINATIONS])
        assertEquals(7, result.totalChanged)
    }

    @Test
    fun `unknown per-type keys in data are ignored`() = runBlocking {
        val store = FakePullSyncStore()
        val fetch =
            fetchReturning(
                buildJsonObject {
                    putJsonArray("future_type") { add(buildJsonObject { put("id", "x") }) }
                    putJsonArray("medications") {}
                }.toString(),
            )
        val coord = PullSyncCoordinator(fetch, store)

        val result = coord.pull()

        assertEquals(0, result.totalChanged)
        assertEquals(0, result.delta.medications.size)
    }

    @Test
    fun `updatedAtEpochMs parses ISO offsets and naive timestamps`() {
        assertEquals(0L, row("x", "1970-01-01T00:00:00+00:00").updatedAtEpochMs())
        assertTrue(row("x", "2026-08-12T10:00:00").updatedAtEpochMs()!! > 0L)
        assertNull(row("x").updatedAtEpochMs())
    }

    @Test
    fun `clearCursor resets so next pull starts fresh`() = runBlocking {
        val store = FakePullSyncStore(initialCursor = "old")
        val fetch = FetchRecorder(envelope(cursor = "c1", medications = listOf(row("m1"))))
        val coord = PullSyncCoordinator(fetch::fetch, store)

        store.clearCursor()
        assertNull(store.currentCursor())
        coord.pull()
        // First fetch after a clear passes null.
        assertNull(fetch.sinceArguments.first())
    }

    @Test
    fun `pullAndSignal returns whether anything changed`() = runBlocking {
        assertFalse(
            PullSyncCoordinator(fetchReturning(envelope(cursor = null)), FakePullSyncStore()).pullAndSignal(),
        )
        assertTrue(
            PullSyncCoordinator(
                fetchReturning(envelope(cursor = "c1", medications = listOf(row("m1")))),
                FakePullSyncStore(),
            ).pullAndSignal(),
        )
    }

    @Test
    fun `malformed envelope body propagates the parse failure`() = runBlocking {
        val store = FakePullSyncStore()
        val fetch = fetchReturning("not json")
        val coord = PullSyncCoordinator(fetch, store)

        assertThrows(Exception::class.java) {
            runBlocking { coord.pull() }
        }
        // Cursor untouched, nothing applied.
        assertNull(store.currentCursor())
        assertEquals(0, store.applied.size)
    }

    @Test
    fun `cursor plus-offset is normalized to Z on store (URL-safe)`() = runBlocking {
        val store = FakePullSyncStore()
        val fetch =
            fetchReturning(envelope(cursor = "2026-08-09T03:30:32.268286+00:00", medications = listOf(row("m1"))))
        val coord = PullSyncCoordinator(fetch, store)

        coord.pull()

        // The stored cursor must not contain a bare `+` (it decodes to a space
        // server-side → HTTP 400 on the next pull).
        assertEquals("2026-08-09T03:30:32.268286Z", store.savedCursor)
        assertEquals("2026-08-09T03:30:32.268286Z", store.currentCursor())
    }

    @Test
    fun `legacy plus-offset cursor in store is normalized on read before fetch`() = runBlocking {
        val store = FakePullSyncStore(initialCursor = "2026-08-09T03:30:32.268286+00:00")
        val fetch = FetchRecorder(envelope(cursor = null))
        val coord = PullSyncCoordinator(fetch::fetch, store)

        coord.pull()

        // The value passed to fetch must be the Z form — the prior cursor is
        // sent as the `since` query param and must survive URL decoding.
        assertEquals("2026-08-09T03:30:32.268286Z", fetch.sinceArguments.single())
    }

    @Test
    fun `toUrlSafeCursor leaves non-UTC offsets and Z values untouched`() {
        assertEquals("2026-08-09T03:30:32Z", "2026-08-09T03:30:32+00:00".toUrlSafeCursor())
        assertEquals("2026-08-09T03:30:32Z", "2026-08-09T03:30:32+0000".toUrlSafeCursor())
        assertEquals("2026-08-09T03:30:32Z", "2026-08-09T03:30:32Z".toUrlSafeCursor())
        assertEquals("2026-08-09T03:30:32+03:00", "2026-08-09T03:30:32+03:00".toUrlSafeCursor())
        assertEquals("not-a-cursor", "not-a-cursor".toUrlSafeCursor())
    }

    // --- helpers -----------------------------------------------------------

    private fun fetchReturning(vararg responses: String): suspend (String?) -> String {
        val queue = responses.toMutableList()
        return { _ -> queue.removeFirstOrNull() ?: responses.last() }
    }

    private fun envelope(
        cursor: String? = null,
        medications: List<JsonObject> = emptyList(),
        allergies: List<JsonObject> = emptyList(),
        vaccines: List<JsonObject> = emptyList(),
        clinicalEvents: List<JsonObject> = emptyList(),
        documents: List<JsonObject> = emptyList(),
        examinations: List<JsonObject> = emptyList(),
    ): String =
        buildJsonObject {
            put("data", buildJsonObject {
                putArray("medications", medications)
                putArray("allergies", allergies)
                putArray("vaccines", vaccines)
                putArray("clinical_events", clinicalEvents)
                putArray("documents", documents)
                putArray("examinations", examinations)
            })
            cursor?.let { put("cursor", it) }
        }.toString()

    private fun kotlinx.serialization.json.JsonObjectBuilder.putArray(
        key: String,
        rows: List<JsonObject>,
    ) {
        putJsonArray(key) { rows.forEach { add(it) } }
    }

    private fun row(
        id: String,
        updatedAt: String? = null,
    ): JsonObject =
        buildJsonObject {
            put("id", id)
            updatedAt?.let { put("updated_at", it) }
        }

    private class FakePullSyncStore(
        initialCursor: String? = null,
        val applyBehavior: suspend (ChangesDelta) -> Unit = {},
    ) : PullSyncStore {
        private var cursor: String? = initialCursor
        val applied = mutableListOf<ChangesDelta>()
        var savedCursor: String? = null

        override suspend fun currentCursor(): String? = cursor

        override suspend fun saveCursor(isoCursor: String) {
            cursor = isoCursor
            savedCursor = isoCursor
        }

        override suspend fun applyDelta(delta: ChangesDelta) {
            applyBehavior(delta)
            applied += delta
        }

        override suspend fun clearCursor() {
            cursor = null
        }
    }

    private class FetchRecorder(vararg responses: String) {
        private val all = responses.toList()
        private val queue = responses.toMutableList()
        val sinceArguments = mutableListOf<String?>()

        suspend fun fetch(since: String?): String {
            sinceArguments += since
            return queue.removeFirstOrNull() ?: all.last()
        }
    }

    @Suppress("unused")
    private class FetchFailing(val message: String) {
        suspend fun fetch(since: String?): String = throw RuntimeException(message)
    }
}
