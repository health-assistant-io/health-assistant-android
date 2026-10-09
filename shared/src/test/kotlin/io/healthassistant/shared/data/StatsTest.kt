package io.healthassistant.shared.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM gate for [SeriesStats.from] — the pure reduction behind the biomarker
 * detail stats row: empty input, single point, all-equal series, a typical
 * series (avg rounded to one decimal, matching the screen's value
 * formatting), and non-numeric points being skipped.
 */
class StatsTest {
    private fun point(
        id: String,
        value: Double?,
        normalized: Double? = null,
        valueString: String? = null,
    ): ObservationPoint =
        ObservationPoint(
            id = id,
            rawValue = value,
            normalizedValue = normalized,
            valueString = valueString,
        )

    @Test
    fun `empty points yield null`() {
        assertNull(SeriesStats.from(emptyList()))
    }

    @Test
    fun `points without numeric values yield null`() {
        assertNull(
            SeriesStats.from(
                listOf(point("a", null, valueString = "asleep")),
            ),
        )
    }

    @Test
    fun `single point is min max avg and last`() {
        val stats = SeriesStats.from(listOf(point("a", 70.0)))

        assertEquals(70.0, stats!!.min, 0.0)
        assertEquals(70.0, stats.max, 0.0)
        assertEquals(70.0, stats.avg, 0.0)
        assertEquals(70.0, stats.last, 0.0)
    }

    @Test
    fun `all-equal series stays flat across every stat`() {
        val stats = SeriesStats.from((1..5).map { point("p$it", 72.0) })

        assertEquals(72.0, stats!!.min, 0.0)
        assertEquals(72.0, stats.max, 0.0)
        assertEquals(72.0, stats.avg, 0.0)
        assertEquals(72.0, stats.last, 0.0)
    }

    @Test
    fun `typical series reduces to min max avg last`() {
        val stats =
            SeriesStats.from(
                listOf(
                    point("p1", 65.0),
                    point("p2", 72.0),
                    point("p3", 78.0),
                ),
            )

        assertEquals(65.0, stats!!.min, 0.0)
        assertEquals(78.0, stats.max, 0.0)
        assertEquals(71.7, stats.avg, 0.0)
        assertEquals(78.0, stats.last, 0.0)
    }

    @Test
    fun `avg of a whole-number mean stays whole`() {
        val stats =
            SeriesStats.from(
                listOf(
                    point("p1", 70.0),
                    point("p2", 75.0),
                    point("p3", 80.0),
                ),
            )

        assertEquals(75.0, stats!!.avg, 0.0)
    }

    @Test
    fun `non-numeric points are skipped not fatal`() {
        val stats =
            SeriesStats.from(
                listOf(
                    point("p1", 70.0),
                    point("state", null, valueString = "deep sleep"),
                    point("p2", 80.0),
                ),
            )

        assertEquals(70.0, stats!!.min, 0.0)
        assertEquals(80.0, stats.max, 0.0)
        assertEquals(75.0, stats.avg, 0.0)
        assertEquals(80.0, stats.last, 0.0)
    }

    @Test
    fun `raw value wins over normalized when both present`() {
        val stats = SeriesStats.from(listOf(point("p1", 70.0, normalized = 7.0)))

        assertEquals(70.0, stats!!.min, 0.0)
        assertEquals(70.0, stats.avg, 0.0)
    }
}
