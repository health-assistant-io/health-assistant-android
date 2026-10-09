package io.healthassistant.shared.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiomarkerOptionsTest {

    private fun catalog() =
        listOf(
            BiomarkerSummary(id = "b1", name = "Heart Rate", slug = "heart-rate", code = "8867-4", unit = "beats/min", isTelemetry = true),
            BiomarkerSummary(id = "b2", name = "Fasting Glucose", slug = "glucose-fasting", code = "2345-7", unit = "mmol/L", isTelemetry = false),
        )

    private fun latest() =
        listOf(
            ObservationPoint(
                "o1",
                effectiveDatetime = "2026-08-10T09:00:00Z",
                rawValue = 72.0,
                normalizedUnit = "beats/min",
                code = ObservationCode(coding = listOf(ObservationCode.Coding(code = "8867-4"))),
            ),
        )

    @Test
    fun `merges catalog with latest value per code`() {
        val options = BiomarkerOptions.from(catalog(), latest())

        assertEquals(2, options.size)
        val heart = options.first { it.code == "8867-4" }
        assertEquals(72.0, heart.latestValue)
        assertEquals("beats/min", heart.latestUnit)
        assertEquals("2026-08-10T09:00:00Z", heart.latestTimestamp)
        assertTrue(heart.isTelemetry)

        val glucose = options.first { it.code == "2345-7" }
        assertNull(glucose.latestValue)
        assertFalse(glucose.isTelemetry)
        assertEquals("mmol/L", glucose.unit)
    }

    @Test
    fun `keeps every catalog entry even without a latest value`() {
        val options = BiomarkerOptions.from(catalog(), emptyList())
        assertEquals(2, options.size)
        assertTrue(options.all { it.latestValue == null })
    }
}

class DashboardLayoutTest {

    private fun reading(code: String) =
        BiomarkerReading(
            code = code,
            displayName = code,
            value = 1.0,
        )

    @Test
    fun `filters to shown codes`() {
        val readings = listOf(reading("a"), reading("b"), reading("c"))
        val visible = DashboardLayout.filterAndOrder(readings, setOf("a", "c"), emptyList())
        assertEquals(listOf("a", "c"), visible.map { it.code })
    }

    @Test
    fun `empty show set keeps all`() {
        val readings = listOf(reading("a"), reading("b"))
        assertEquals(2, DashboardLayout.filterAndOrder(readings, emptySet(), emptyList()).size)
    }

    @Test
    fun `applies custom order and sinks unknown codes`() {
        val readings = listOf(reading("a"), reading("b"), reading("c"))
        val ordered = DashboardLayout.filterAndOrder(readings, setOf("a", "b", "c"), listOf("c", "a"))
        assertEquals(listOf("c", "a", "b"), ordered.map { it.code })
    }

    @Test
    fun `basic codes include the everyday metrics`() {
        val basic = DashboardLayout.basicCodes()
        assertTrue("8867-4" in basic) // heart rate
        assertTrue("55423-8" in basic) // steps
        assertTrue("29463-7" in basic) // weight
        assertTrue("59408-5" in basic) // SpO₂
        assertTrue("sleep-duration" in basic)
    }

    @Test
    fun `move code within order`() {
        assertEquals(listOf("b", "a", "c"), DashboardLayout.moveCode(listOf("a", "b", "c"), "a", 1))
        assertEquals(listOf("a", "c", "b"), DashboardLayout.moveCode(listOf("a", "b", "c"), "c", -1))
        // Bounds are respected.
        assertEquals(listOf("a", "b", "c"), DashboardLayout.moveCode(listOf("a", "b", "c"), "a", -1))
        assertEquals(listOf("a", "b", "c"), DashboardLayout.moveCode(listOf("a", "b", "c"), "c", 1))
        // Unknown code is a no-op.
        assertEquals(listOf("a", "b", "c"), DashboardLayout.moveCode(listOf("a", "b", "c"), "x", 1))
    }
}
