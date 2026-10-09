package io.healthassistant.android.ui

import io.healthassistant.android.ui.components.chartWindowStartMs
import io.healthassistant.android.ui.components.pickStepSeconds
import io.healthassistant.shared.data.ChartRange
import io.healthassistant.shared.data.ObservationPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic gate for the biomarker chart screen: ISO parsing, the
 *  auto-widen decision, the chart window/label-step math, and the
 *  state-change timeline derivation. */
class BiomarkerDetailLogicTest {
    @Test
    fun `parses full instants`() {
        assertEquals(
            1755475200000L,
            parseEpochMs("2025-08-18T00:00:00Z"),
        )
    }

    @Test
    fun `parses date-only strings as utc midnight`() {
        assertEquals(parseEpochMs("2025-08-18T00:00:00Z"), parseEpochMs("2025-08-18"))
    }

    @Test
    fun `returns null for junk and blank input`() {
        assertNull(parseEpochMs(null))
        assertNull(parseEpochMs(""))
        assertNull(parseEpochMs("   "))
        assertNull(parseEpochMs("not a date"))
    }

    @Test
    fun `trims whitespace before parsing`() {
        assertEquals(parseEpochMs("2025-08-18T00:00:00Z"), parseEpochMs(" 2025-08-18T00:00:00Z "))
    }

    @Test
    fun `auto-widen jumps empty non-widest window to one year`() {
        assertEquals(ChartRange.ONE_YEAR, autoWidenRange(ChartRange.ONE_WEEK, empty = true, userPicked = false, alreadyWidened = false))
        assertEquals(ChartRange.ONE_YEAR, autoWidenRange(ChartRange.THREE_MONTHS, empty = true, userPicked = false, alreadyWidened = false))
    }

    @Test
    fun `no widen when window has data`() {
        assertNull(autoWidenRange(ChartRange.ONE_WEEK, empty = false, userPicked = false, alreadyWidened = false))
    }

    @Test
    fun `no widen after the user picked a range`() {
        assertNull(autoWidenRange(ChartRange.ONE_WEEK, empty = true, userPicked = true, alreadyWidened = false))
    }

    @Test
    fun `no widen twice and no widen past one year`() {
        assertNull(autoWidenRange(ChartRange.ONE_WEEK, empty = true, userPicked = false, alreadyWidened = true))
        assertNull(autoWidenRange(ChartRange.ONE_YEAR, empty = true, userPicked = false, alreadyWidened = false))
    }

    @Test
    fun `window start anchors at now minus window`() {
        val now = 1_800_000_000_000L
        val week = 7L * 86_400_000
        val firstPoint = now - 86_400_000
        assertEquals(now - week, chartWindowStartMs(now, week, firstPoint))
    }

    @Test
    fun `window start never sits after the first record`() {
        val now = 1_800_000_000_000L
        val firstPoint = now - 30L * 86_400_000
        assertEquals(firstPoint, chartWindowStartMs(now, 7L * 86_400_000, firstPoint))
    }

    @Test
    fun `null window falls back to the first record`() {
        assertEquals(500L, chartWindowStartMs(nowMs = 1_000, windowMs = null, firstPointMs = 500))
    }

    @Test
    fun `label step targets about four labels across the domain`() {
        assertEquals(60.0, pickStepSeconds(3.0), 0.0)
        assertEquals(60.0, pickStepSeconds(240.0), 0.0)
        assertTrue(pickStepSeconds(31_536_000.0) >= 31_536_000.0 / 4)
    }

    @Test
    fun `tick ladder picks intraday steps for zoomed spans`() {
        assertEquals(900.0, pickStepSeconds(3_600.0), 0.0)
        assertEquals(900.0, pickStepSeconds(3_000.0), 0.0)
        assertEquals(3_600.0, pickStepSeconds(10_000.0), 0.0)
        assertEquals(86_400.0, pickStepSeconds(4.0 * 86_400), 0.0)
        assertEquals(365L * 86_400.toDouble(), pickStepSeconds(400.0 * 86_400), 0.0)
    }

    @Test
    fun `detail fetch fires only when the visible span halves`() {
        val week = 7L * 86_400_000
        assertTrue(needsDetailFetch(week / 2 - 1, week))
        assertFalse(needsDetailFetch(week / 2, week))
        assertFalse(needsDetailFetch(week, week))
        assertTrue(needsDetailFetch(week, Long.MAX_VALUE))
    }

    @Test
    fun `state intervals return null for empty or numeric series`() {
        assertNull(stateIntervals(emptyList()))
        assertNull(
            stateIntervals(
                listOf(
                    ObservationPoint(id = "a", rawValue = 70.0),
                ),
            ),
        )
        assertNull(
            "a single numeric point flips the window to the chart",
            stateIntervals(
                listOf(
                    ObservationPoint(id = "a", effectiveDatetime = "2026-08-10T09:00:00Z", valueString = "asleep"),
                    ObservationPoint(id = "b", effectiveDatetime = "2026-08-11T09:00:00Z", rawValue = 70.0),
                ),
            ),
        )
    }

    @Test
    fun `state intervals return null without parseable times or labels`() {
        assertNull(
            stateIntervals(
                listOf(
                    ObservationPoint(id = "a", effectiveDatetime = "junk", valueString = "asleep"),
                ),
            ),
        )
        assertNull(
            stateIntervals(
                listOf(
                    ObservationPoint(id = "a", effectiveDatetime = "2026-08-10T09:00:00Z"),
                ),
            ),
        )
    }

    @Test
    fun `consecutive same-state readings collapse into one run`() {
        val intervals =
            stateIntervals(
                listOf(
                    ObservationPoint(id = "a", effectiveDatetime = "2026-08-10T22:00:00Z", valueString = "deep sleep"),
                    ObservationPoint(id = "b", effectiveDatetime = "2026-08-10T22:30:00Z", valueString = "deep sleep"),
                    ObservationPoint(id = "c", effectiveDatetime = "2026-08-10T23:00:00Z", valueString = "light sleep"),
                    ObservationPoint(id = "d", effectiveDatetime = "2026-08-10T23:45:00Z", valueString = "Deep Sleep"),
                ),
            )

        assertEquals(3, intervals!!.size)
        assertEquals("deep sleep", intervals[0].label)
        assertEquals(parseEpochMs("2026-08-10T22:00:00Z"), intervals[0].startEpochMs)
        assertEquals(parseEpochMs("2026-08-10T22:30:00Z"), intervals[0].endEpochMs)
        assertEquals("light sleep", intervals[1].label)
        assertEquals("Deep Sleep", intervals[2].label)
        assertEquals(parseEpochMs("2026-08-10T23:45:00Z"), intervals[2].endEpochMs)
    }

    @Test
    fun `state intervals sort unordered input chronologically`() {
        val intervals =
            stateIntervals(
                listOf(
                    ObservationPoint(id = "b", effectiveDatetime = "2026-08-11T09:00:00Z", valueString = "awake"),
                    ObservationPoint(id = "a", effectiveDatetime = "2026-08-10T09:00:00Z", valueString = "asleep"),
                ),
            )

        assertEquals(listOf("asleep", "awake"), intervals!!.map { it.label })
    }
}
