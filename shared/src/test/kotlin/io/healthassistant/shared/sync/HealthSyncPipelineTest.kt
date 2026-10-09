package io.healthassistant.shared.sync

import io.healthassistant.shared.healthconnect.HcType
import io.healthassistant.shared.healthconnect.RawSample
import io.healthassistant.shared.source.FakeHealthDataSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase D gate (pure-Kotlin): [HealthSyncPipeline] reads a fake source, maps to
 * bridge [io.healthassistant.bridge.ClientRecord]s, enqueues one `/sync` outbox
 * item per sample, and the [SyncCoordinator] groups them into a single
 * `SyncPayload` at drain time. No Android dependency — JVM only.
 */
class HealthSyncPipelineTest {
    private lateinit var outbox: InMemoryOutboxStore
    private lateinit var pipeline: HealthSyncPipeline

    @Before
    fun setUp() {
        outbox = InMemoryOutboxStore()
        pipeline = HealthSyncPipeline(outbox)
    }

    private fun sample(type: HcType, value: Double) =
        RawSample(hcType = type, value = value, unit = type.defaultUnit, timestamp = "2026-08-09T10:00:00Z")

    @Test
    fun enqueues_one_sync_item_per_sample_with_correct_codes() = runBlocking {
        val source =
            FakeHealthDataSource(
                samplesByType =
                    mapOf(
                        HcType.HEART_RATE to listOf(sample(HcType.HEART_RATE, 72.0), sample(HcType.HEART_RATE, 80.0)),
                        HcType.STEPS to listOf(sample(HcType.STEPS, 1000.0)),
                    ),
            )

        val result =
            pipeline.readAndEnqueue(
                source,
                setOf(HcType.HEART_RATE, HcType.STEPS),
                cursors = mapOf(HcType.HEART_RATE to 0L, HcType.STEPS to 0L),
            )

        assertEquals(3, result.enqueued)
        assertEquals(2, result.perTypeCounts[HcType.HEART_RATE])
        assertEquals(1, result.perTypeCounts[HcType.STEPS])
        assertTrue(result.newCursors.containsKey(HcType.HEART_RATE))

        val items = outbox.snapshot()
        assertEquals(3, items.size)
        items.forEach { item ->
            assertEquals("POST", item.method)
            assertEquals("/sync", item.path)
        }
        val codes = items.map { it.payload.decodePayload().records.orEmpty().map { r -> r.code } }.flatten()
        assertTrue(codes.contains("8867-4")) // Heart Rate
        assertTrue(codes.contains("55423-8")) // Steps
    }

    @Test
    fun no_enabled_types_is_a_noop() = runBlocking {
        val result = pipeline.readAndEnqueue(FakeHealthDataSource(), emptySet(), emptyMap())

        assertEquals(0, result.enqueued)
        assertTrue(outbox.snapshot().isEmpty())
    }

    @Test
    fun empty_read_enqueues_nothing_but_returns_cursors() = runBlocking {
        val source = FakeHealthDataSource(samplesByType = emptyMap())

        val result = pipeline.readAndEnqueue(source, setOf(HcType.HEART_RATE), emptyMap())

        assertEquals(0, result.enqueued)
        assertTrue(result.newCursors.isNotEmpty())
    }

    @Test
    fun coordinator_groups_enqueued_items_into_one_sync_payload() = runBlocking {
        val source =
            FakeHealthDataSource(
                samplesByType =
                    mapOf(
                        HcType.HEART_RATE to listOf(sample(HcType.HEART_RATE, 72.0)),
                        HcType.STEPS to listOf(sample(HcType.STEPS, 500.0)),
                    ),
            )
        pipeline.readAndEnqueue(source, setOf(HcType.HEART_RATE, HcType.STEPS), emptyMap())

        val captured = mutableListOf<ByteArray>()
        val coordinator = SyncCoordinator(outbox, capturingSender(captured))
        val drain = coordinator.drain()

        assertEquals(2, drain.synced)
        assertEquals(1, captured.size) // grouped into a single send
        val payload = captured.single().decodePayload()
        assertEquals(2, payload.records!!.size)
    }

    private fun capturingSender(captured: MutableList<ByteArray>): SyncSender =
        object : SyncSender {
            override suspend fun send(
                method: String,
                path: String,
                body: ByteArray,
            ): SendResult {
                captured += body
                return SendResult.Success
            }
        }

    private fun ByteArray.decodePayload(): io.healthassistant.bridge.SyncPayload =
        SYNC_JSON.decodeFromString(io.healthassistant.bridge.SyncPayload.serializer(), decodeToString())

    private companion object {
        val SYNC_JSON = Json { ignoreUnknownKeys = true }
    }
}
