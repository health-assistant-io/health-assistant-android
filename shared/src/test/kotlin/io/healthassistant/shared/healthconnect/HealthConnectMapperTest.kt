package io.healthassistant.shared.healthconnect

import io.healthassistant.bridge.ClientRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthConnectMapperTest {

    @Test
    fun `heart rate maps to LOINC 8867-4 quantitative bpm`() {
        val cr: ClientRecord = HealthConnectMapper.map(
            RawSample(HcType.HEART_RATE, value = 75.0, timestamp = "2026-08-08T10:00:00Z", performer = "Pixel Watch"),
        )
        assertEquals("quantitative", cr.type)
        assertEquals("8867-4", cr.code)
        assertEquals("loinc", cr.codingSystem)
        assertEquals("Heart Rate", cr.name)
        assertEquals(75.0, cr.value!!, 0.0)
        assertEquals("bpm", cr.unit)
        assertEquals("Pixel Watch", cr.performer)
    }

    @Test
    fun `steps use count unit`() {
        val cr = HealthConnectMapper.map(RawSample(HcType.STEPS, value = 12453.0))
        assertEquals("55423-8", cr.code)
        assertEquals("count", cr.unit)
    }

    @Test
    fun `unit override beats default`() {
        val cr = HealthConnectMapper.map(RawSample(HcType.WEIGHT, value = 175.0, unit = "lb"))
        assertEquals("29463-7", cr.code)
        assertEquals("lb", cr.unit)
    }

    @Test
    fun `sleep uses custom coding system to avoid colliding with clinical codes`() {
        val cr = HealthConnectMapper.map(RawSample(HcType.SLEEP_DURATION, value = 412.0))
        assertEquals("custom", cr.codingSystem)
        assertEquals("sleep-duration", cr.code)
    }

    @Test
    fun `categorical value string passes through`() {
        // A future categorical HcType would set recordType="categorical"; verify the
        // mapper carries valueString when value is null.
        val cr = HealthConnectMapper.map(RawSample(HcType.OXYGEN_SATURATION, value = 97.0))
        assertNull(cr.valueString)
        assertEquals(97.0, cr.value!!, 0.0)
    }

    @Test
    fun `byCode round-trips`() {
        assertEquals(HcType.HEART_RATE, HcType.byCode("8867-4"))
        assertNull(HcType.byCode("unknown-code"))
    }
}
