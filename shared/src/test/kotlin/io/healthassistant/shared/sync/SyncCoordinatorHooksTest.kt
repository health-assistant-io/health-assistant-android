package io.healthassistant.shared.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase G: the [SyncCoordinator.Config] `onSyncedItems` / `onDeadItems` hooks
 * fire with the right items so the worker can tally per-biomarker progress.
 * Also covers [SyncItems.recordCode] (the worker's decode helper).
 */
class SyncCoordinatorHooksTest {
    private lateinit var store: InMemoryOutboxStore

    @Before
    fun setUp() {
        store = InMemoryOutboxStore()
    }

    private fun hrItem(id: String) = SyncItems.heartRateItem().copy(id = id)

    @Test
    fun onSyncedItems_fires_with_the_synced_batch_on_success() = runBlocking {
        val syncedItems = mutableListOf<OutboxItem>()
        val coordinator =
            SyncCoordinator(
                store,
                succeedingSender(),
                SyncCoordinator.Config(onSyncedItems = { syncedItems.addAll(it) }),
            )
        store.enqueue(hrItem("a"))
        store.enqueue(hrItem("b"))

        val drain = coordinator.drain()

        assertEquals(2, drain.synced)
        assertEquals(2, syncedItems.size)
        assertEquals(setOf("a", "b"), syncedItems.map { it.id }.toSet())
    }

    @Test
    fun onDeadItems_fires_when_a_batch_permanently_fails() = runBlocking {
        val deadItems = mutableListOf<OutboxItem>()
        val coordinator =
            SyncCoordinator(
                store,
                permanentFailSender(),
                SyncCoordinator.Config(onDeadItems = { deadItems.addAll(it) }),
            )
        store.enqueue(hrItem("x"))

        val drain = coordinator.drain()

        assertEquals(1, drain.deadLettered)
        assertEquals(1, deadItems.size)
        assertEquals("x", deadItems[0].id)
    }

    @Test
    fun recordCode_extracts_the_loinc_code_from_a_sync_item() {
        val item = SyncItems.heartRateItem()

        val code = SyncItems.recordCode(item)

        assertEquals("8867-4", code)
    }

    @Test
    fun recordCode_returns_null_for_a_non_sync_payload() {
        val bogus = OutboxItem(id = "z", method = "POST", path = "/sync", payload = "not json".encodeToByteArray())

        assertEquals(null, SyncItems.recordCode(bogus))
    }

    @Test
    fun drain_loop_clears_a_backlog_in_one_pass() = runBlocking {
        val coordinator = SyncCoordinator(store, succeedingSender())
        repeat(500) { store.enqueue(hrItem("i$it")) }

        var remaining = 500
        var totalSynced = 0
        // The worker loops drain() until the store is empty.
        do {
            val drain = coordinator.drain()
            totalSynced += drain.synced
            remaining = store.pendingCount()
        } while (remaining > 0)

        assertEquals(500, totalSynced)
        assertEquals(0, store.pendingCount())
        assertTrue("no dead letters", store.deadLetters().isEmpty())
    }

    @Test
    fun default_batch_size_is_1000_for_higher_throughput() {
        // The bridge /sync has no per-request record cap, so the default batch is
        // large to minimize HTTP overhead on big backfills.
        assertEquals(1000, SyncCoordinator.Config().defaultBatchSize)
    }

    private fun succeedingSender(): SyncSender =
        SyncSender { _, _, _ -> SendResult.Success }

    private fun permanentFailSender(): SyncSender =
        SyncSender { _, _, _ -> SendResult.Permanent(statusCode = 422, message = "validation") }
}
