package io.healthassistant.shared.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** M9 a11y gate: the TalkBack summary reduces a plotted series to
 *  min/max/last/count and stays null for an empty series. */
class ChartSummaryTest {
    @Test
    fun reduces_a_series_to_min_max_last_count() {
        val summary = ChartSummary.from(listOf(78.0, 58.0, 112.0, 72.0))!!

        assertEquals(58.0, summary.min, 0.0)
        assertEquals(112.0, summary.max, 0.0)
        assertEquals(72.0, summary.last, 0.0)
        assertEquals(4, summary.count)
    }

    @Test
    fun single_point_series_carries_the_same_value_throughout() {
        val summary = ChartSummary.from(listOf(72.0))!!

        assertEquals(72.0, summary.min, 0.0)
        assertEquals(72.0, summary.max, 0.0)
        assertEquals(72.0, summary.last, 0.0)
        assertEquals(1, summary.count)
    }

    @Test
    fun empty_series_has_no_summary() {
        assertNull(ChartSummary.from(emptyList()))
    }
}
