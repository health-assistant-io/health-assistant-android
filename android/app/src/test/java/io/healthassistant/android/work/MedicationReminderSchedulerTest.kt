package io.healthassistant.android.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** Phase I (medication reminders) — JVM tests for the time-bound scheduling math. */
class MedicationReminderSchedulerTest {
    @Test
    fun `delay lands later the same day when the hour is ahead`() {
        val now = LocalDateTime.of(2026, 8, 12, 7, 30)
        val ms = delayToNextTarget(hour = 9, now = now)
        // 09:00 - 07:30 = 1.5 h
        assertEquals(90 * 60 * 1000L, ms)
    }

    @Test
    fun `delay lands tomorrow when the hour has already passed`() {
        val now = LocalDateTime.of(2026, 8, 12, 11, 0)
        val ms = delayToNextTarget(hour = 9, now = now)
        // tomorrow 09:00 - today 11:00 = 22 h
        assertEquals(22 * 60 * 60 * 1000L, ms)
    }

    @Test
    fun `delay is exactly the hour when it is on the hour`() {
        val now = LocalDateTime.of(2026, 8, 12, 9, 0)
        // Now is NOT before the target (equal), so it rolls to tomorrow.
        val ms = delayToNextTarget(hour = 9, now = now)
        assertEquals(24 * 60 * 60 * 1000L, ms)
    }

    @Test
    fun `out-of-range hours are clamped before scheduling`() {
        val now = LocalDateTime.of(2026, 8, 12, 7, 0)
        assertTrue(delayToNextTarget(hour = -3, now = now) > 0L)
        assertTrue(delayToNextTarget(hour = 30, now = now) > 0L)
    }
}
