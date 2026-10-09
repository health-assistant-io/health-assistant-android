package io.healthassistant.android.monitoring

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.healthassistant.shared.healthconnect.HcType
import io.healthassistant.shared.sync.InMemoryOutboxStore
import io.healthassistant.shared.sync.OutboxItem
import io.healthassistant.shared.sync.OutboxLane
import io.healthassistant.shared.sync.OutboxStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase C gate: [SyncMonitorRepository] emits the correct outbox counts +
 * dead-letter reasons through its StateFlow after a refresh, and accumulates
 * per-type synced counts after [recordSyncResult]. Uses an [InMemoryOutboxStore]
 * for deterministic outbox state; the DataStore-backed counters need a real
 * Context so this is instrumented.
 */
@RunWith(AndroidJUnit4::class)
class SyncMonitorRepositoryTest {
    private lateinit var outbox: InMemoryOutboxStore
    private lateinit var repo: SyncMonitorRepository

    @Before
    fun setUp() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            outbox = InMemoryOutboxStore()
            repo = SyncMonitorRepository(context, outbox)
            repo.resetCounts()
        }

    private fun pendingItem(id: String) = OutboxItem(id = id, method = "POST", path = "/sync", lane = OutboxLane.DEFAULT)

    private fun deadItem(
        id: String,
        reason: String,
    ) = OutboxItem(
        id = id,
        method = "POST",
        path = "/sync",
        lane = OutboxLane.DEFAULT,
        status = OutboxStatus.DEAD_LETTER,
        attempts = 5,
        deadReason = reason,
    )

    @Test
    fun refresh_emits_pending_and_dead_counts() =
        runBlocking {
            outbox.enqueue(pendingItem("p1"))
            outbox.enqueue(pendingItem("p2"))
            outbox.enqueue(deadItem("d1", "HTTP 422 validation"))

            repo.refresh()

            val monitor = repo.monitor.value
            assertEquals(2, monitor.outboxPending)
            assertEquals(1, monitor.outboxDeadLettered)
        }

    @Test
    fun refresh_surfaces_dead_letter_reasons() =
        runBlocking {
            outbox.enqueue(deadItem("d1", "HTTP 422 validation"))

            repo.refresh()

            val dead = repo.monitor.value.deadLetters
            assertEquals(1, dead.size)
            assertEquals("d1", dead[0].id)
            assertEquals("POST /sync", dead[0].method + " " + dead[0].path)
            assertEquals(5, dead[0].attempts)
            assertEquals("HTTP 422 validation", dead[0].reason)
        }

    @Test
    fun recordSyncResult_accumulates_per_type_counts_and_status() =
        runBlocking {
            repo.recordSyncResult("health_connect", mapOf(HcType.HEART_RATE to 10), SyncStatus.Success(10))
            repo.recordSyncResult("health_connect", mapOf(HcType.HEART_RATE to 5, HcType.STEPS to 20), SyncStatus.Success(25))

            val source =
                repo.monitor.value.sources
                    .single { it.sourceId == "health_connect" }
            assertEquals(15, source.totalSyncedPerType[HcType.HEART_RATE])
            assertEquals(20, source.totalSyncedPerType[HcType.STEPS])
            assertTrue(source.lastSyncAt != null)
            assertTrue(source.lastSyncStatus is SyncStatus.Success)
        }

    @Test
    fun recordSyncResult_records_error_status() =
        runBlocking {
            repo.recordSyncResult("health_connect", emptyMap(), SyncStatus.Error("network timeout"))

            val source =
                repo.monitor.value.sources
                    .single { it.sourceId == "health_connect" }
            val status = source.lastSyncStatus
            assertTrue(status is SyncStatus.Error)
            assertEquals("network timeout", (status as SyncStatus.Error).message)
        }

    @Test
    fun refresh_is_idempotent_and_reflects_outbox_drain() =
        runBlocking {
            outbox.enqueue(pendingItem("p1"))
            repo.refresh()
            assertEquals(1, repo.monitor.value.outboxPending)

            // Simulate a successful drain → item removed.
            outbox.markSynced(listOf("p1"))
            repo.refresh()

            assertEquals(0, repo.monitor.value.outboxPending)
        }

    @Test
    fun retryDeadLetter_revives_the_item_and_clears_it_from_the_snapshot() =
        runBlocking {
            outbox.enqueue(deadItem("d1", "HTTP 422 validation"))
            repo.refresh()
            assertEquals(1, repo.monitor.value.outboxDeadLettered)

            repo.retryDeadLetter("d1")

            // The item is back to PENDING (not dead) → dead-letter snapshot clears.
            assertEquals(0, repo.monitor.value.outboxDeadLettered)
            assertEquals(1, repo.monitor.value.outboxPending)
        }
}
