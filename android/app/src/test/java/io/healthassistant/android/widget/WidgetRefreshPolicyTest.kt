package io.healthassistant.android.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M4 (widgets) — the one-pass-per-minute cap that keeps the widget refresh
 * path battery-conscious: first run always allowed, sub-minute repeats
 * skipped, the boundary lands on the next pass, and the interval is
 * overridable for tests.
 */
class WidgetRefreshPolicyTest {
    private val now = 1_790_532_800_000L

    @Test
    fun `first run always refreshes`() {
        assertTrue(WidgetRefreshPolicy.shouldRefresh(lastMs = 0L, nowMs = now))
    }

    @Test
    fun `sub-minute repeats are skipped`() {
        assertFalse(WidgetRefreshPolicy.shouldRefresh(lastMs = now - 59_999L, nowMs = now))
    }

    @Test
    fun `a full minute since the last pass refreshes`() {
        assertTrue(WidgetRefreshPolicy.shouldRefresh(lastMs = now - 60_000L, nowMs = now))
    }

    @Test
    fun `interval is overridable`() {
        assertFalse(WidgetRefreshPolicy.shouldRefresh(lastMs = now - 5_000L, nowMs = now, minIntervalMs = 10_000L))
        assertTrue(WidgetRefreshPolicy.shouldRefresh(lastMs = now - 10_000L, nowMs = now, minIntervalMs = 10_000L))
    }
}
