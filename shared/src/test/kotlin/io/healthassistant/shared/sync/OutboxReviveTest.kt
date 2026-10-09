package io.healthassistant.shared.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Phase F: [OutboxStore.revive] returns a DEAD_LETTER item to PENDING
 *  (resetting attempts + clearing the dead reason) so the next drain retries it. */
class OutboxReviveTest {
    private lateinit var store: InMemoryOutboxStore

    @Before
    fun setUp() {
        store = InMemoryOutboxStore()
    }

    @Test
    fun revive_moves_dead_letter_back_to_pending_and_clears_reason() = runBlocking {
        store.enqueue(OutboxItem(id = "x", method = "POST", path = "/sync"))
        store.markDead("x", "permanent (422): validation")

        store.revive("x")

        val revived = store.get("x")
        assertEquals(OutboxStatus.PENDING, revived?.status)
        assertNull(revived?.deadReason)

        // And it is claimable again on the next drain.
        val claimed = store.claim(OutboxLane.DEFAULT, 10)
        assertEquals(1, claimed.size)
        assertEquals("x", claimed[0].id)
    }

    @Test
    fun revive_resets_attempts_to_zero() = runBlocking {
        store.enqueue(OutboxItem(id = "x", method = "POST", path = "/sync", attempts = 5, status = OutboxStatus.DEAD_LETTER, deadReason = "max"))

        store.revive("x")

        val item = store.get("x")
        assertEquals(0, item?.attempts)
        assertEquals(OutboxStatus.PENDING, item?.status)
    }

    @Test
    fun clear_drops_every_item() = runBlocking {
        store.enqueue(OutboxItem(id = "a", method = "POST", path = "/sync", status = OutboxStatus.PENDING))
        store.enqueue(OutboxItem(id = "b", method = "POST", path = "/sync", status = OutboxStatus.DEAD_LETTER, deadReason = "x"))
        assertEquals(2, store.snapshot().size)

        store.clear()

        assertEquals(0, store.snapshot().size)
        assertEquals(0, store.pendingCount())
        assertEquals(0, store.deadLetters().size)
    }
}
