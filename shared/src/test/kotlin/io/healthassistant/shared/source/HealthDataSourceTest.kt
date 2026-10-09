package io.healthassistant.shared.source

import io.healthassistant.shared.healthconnect.HcType
import io.healthassistant.shared.healthconnect.RawSample
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthDataSourceTest {

    private fun sample(type: HcType, value: Double) =
        RawSample(hcType = type, value = value, timestamp = "2026-08-09T10:00:00Z")

    @Test
    fun `fake source returns samples for requested types`() = runBlocking {
        val source = FakeHealthDataSource(
            samplesByType = mapOf(
                HcType.HEART_RATE to listOf(sample(HcType.HEART_RATE, 72.0)),
                HcType.STEPS to listOf(sample(HcType.STEPS, 1000.0)),
            ),
        )

        val result = source.read(setOf(HcType.HEART_RATE), mapOf(HcType.HEART_RATE to 0L))

        assertEquals(1, result.samples.size)
        assertEquals(HcType.HEART_RATE, result.samples[0].hcType)
        assertEquals(72.0, result.samples[0].value!!, 0.0)
        assertTrue(result.newCursors.containsKey(HcType.HEART_RATE))
    }

    @Test
    fun `fake source records read calls for verification`() = runBlocking {
        val source = FakeHealthDataSource()
        source.read(setOf(HcType.WEIGHT), mapOf(HcType.WEIGHT to 123L))

        assertEquals(1, source.readCalls.size)
        assertEquals(setOf(HcType.WEIGHT), source.readCalls[0].first)
        assertEquals(123L, source.readCalls[0].second[HcType.WEIGHT])
    }

    @Test
    fun `types not in the source produce no samples`() = runBlocking {
        val source = FakeHealthDataSource(samplesByType = emptyMap())

        val result = source.read(setOf(HcType.HEART_RATE, HcType.STEPS), emptyMap())

        assertTrue(result.samples.isEmpty())
        assertEquals(2, result.newCursors.size)
    }
}
