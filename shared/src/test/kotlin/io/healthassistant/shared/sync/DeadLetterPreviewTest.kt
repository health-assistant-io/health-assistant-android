package io.healthassistant.shared.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Bounded dead-letter surface for large backlogs: [OutboxStore.deadLetterCount],
 *  [OutboxStore.deadLetterPreviews] (payload-free, capped) + [OutboxStore.reviveAll]. */
class DeadLetterPreviewTest {
    private lateinit var store: InMemoryOutboxStore

    @Before
    fun setUp() {
        store = InMemoryOutboxStore()
    }

    private fun dead(
        id: String,
        attempts: Int = 3,
    ) = OutboxItem(
        id = id,
        method = "POST",
        path = "/sync",
        status = OutboxStatus.DEAD_LETTER,
        attempts = attempts,
        deadReason = "max attempts",
    )

    @Test
    fun deadLetterCount_counts_only_dead_items() =
        runBlocking {
            store.enqueue(OutboxItem(id = "p", method = "POST", path = "/sync"))
            store.enqueue(dead("d1"))
            store.enqueue(dead("d2"))

            assertEquals(2, store.deadLetterCount())
        }

    @Test
    fun deadLetterPreviews_is_capped_and_payload_free() =
        runBlocking {
            repeat(150) { store.enqueue(dead("d$it")) }

            val previews = store.deadLetterPreviews(100)

            assertEquals(100, previews.size)
            assertEquals(150, store.deadLetterCount())
            assertEquals("d0", previews[0].id)
            assertEquals("POST", previews[0].method)
            assertEquals("/sync", previews[0].path)
            assertEquals(3, previews[0].attempts)
            assertEquals("max attempts", previews[0].deadReason)
        }

    @Test
    fun reviveAll_moves_every_dead_letter_back_to_pending_and_reports_the_count() =
        runBlocking {
            store.enqueue(OutboxItem(id = "p", method = "POST", path = "/sync", attempts = 7))
            store.enqueue(dead("d1"))
            store.enqueue(dead("d2"))

            val revived = store.reviveAll()

            assertEquals(2, revived)
            assertEquals(0, store.deadLetterCount())
            assertEquals(3, store.pendingCount())
            val item = store.get("d1")
            assertEquals(OutboxStatus.PENDING, item?.status)
            assertEquals(0, item?.attempts)
            assertNull(item?.deadReason)
            val untouched = store.get("p")
            assertEquals(7, untouched?.attempts)
            assertTrue(store.claim(OutboxLane.DEFAULT, 10).any { it.id == "d2" })
        }
}
