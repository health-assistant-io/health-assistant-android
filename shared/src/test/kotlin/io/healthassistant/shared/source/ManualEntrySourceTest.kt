package io.healthassistant.shared.source

import io.healthassistant.shared.healthconnect.HcType
import io.healthassistant.shared.healthconnect.RawSample
import io.healthassistant.shared.sync.HealthSyncPipeline
import io.healthassistant.shared.sync.InMemoryOutboxStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase E gate (pure-Kotlin): the [ManualEntrySource] stub implements the same
 * [HealthDataSource] contract as Health Connect — submitted readings are drained
 * by [read] and flow through the [HealthSyncPipeline] into the outbox exactly
 * like a real source. Validates the pluggable-source path end-to-end.
 */
class ManualEntrySourceTest {
    private lateinit var source: ManualEntrySource
    private lateinit var outbox: InMemoryOutboxStore
    private lateinit var pipeline: HealthSyncPipeline

    @Before
    fun setUp() {
        source = ManualEntrySource()
        outbox = InMemoryOutboxStore()
        pipeline = HealthSyncPipeline(outbox)
    }

    @Test
    fun is_always_available_and_advertises_all_types() = runBlocking {
        assertTrue(source.isAvailable())
        assertEquals(HcType.entries, source.availableTypes)
        assertEquals("manual", source.id)
    }

    @Test
    fun submitted_reading_is_drained_and_enqueued_through_the_pipeline() = runBlocking {
        source.submit(RawSample(hcType = HcType.HEART_RATE, value = 72.0, unit = "bpm", timestamp = "2026-08-09T10:00:00Z"))

        val result = pipeline.readAndEnqueue(source, source.availableTypes.toSet(), emptyMap())

        assertEquals(1, result.enqueued)
        assertEquals(1, result.perTypeCounts[HcType.HEART_RATE])
        assertEquals(1, outbox.snapshot().size)
        assertEquals("8867-4", outbox.snapshot().first().payload.decodeToString().let { payload ->
            SYNC_JSON.decodeFromString(io.healthassistant.bridge.SyncPayload.serializer(), payload).records!!.first().code
        })

        // The queue is drained — a second read returns nothing.
        val second = pipeline.readAndEnqueue(source, source.availableTypes.toSet(), emptyMap())
        assertEquals(0, second.enqueued)
    }

    @Test
    fun read_returns_empty_cursors_so_it_does_not_advance_health_connect_state() = runBlocking {
        source.submit(RawSample(hcType = HcType.STEPS, value = 100.0, unit = "count"))

        val result = source.read(setOf(HcType.STEPS), emptyMap())

        assertEquals(1, result.samples.size)
        assertTrue(result.newCursors.isEmpty())
    }

    private companion object {
        val SYNC_JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }
}
