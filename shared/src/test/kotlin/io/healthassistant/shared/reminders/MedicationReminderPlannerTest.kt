package io.healthassistant.shared.reminders

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase I (medication reminders) — JVM tests for [MedicationReminderPlanner].
 * The one-fire-per-day guard + the catch-up window are the crash-safety
 * properties this covers: no duplicates, no missed days from Doze-delayed runs,
 * and a disabled toggle never fires.
 */
class MedicationReminderPlannerTest {

    private val onTheHour = LocalDateTime.of(2026, 8, 12, 9, 0)
    private val prefsOn = MedicationReminderPrefs(enabled = true, hour = 9)
    private val meds = listOf(ReminderMedication(id = "m1", name = "Aspirin", dosage = "100 mg"))

    @Test
    fun `fires on the reminder hour when enabled and never today`() {
        val d = MedicationReminderPlanner.decide(onTheHour, prefsOn, lastNotifiedDay = null, medications = meds)
        assertTrue(d.shouldNotify)
        assertEquals(meds, d.medications)
    }

    @Test
    fun `fires within the catch-up window after the hour`() {
        val late = LocalDateTime.of(2026, 8, 12, 14, 0) // 5h past 09:00
        val d = MedicationReminderPlanner.decide(late, prefsOn, lastNotifiedDay = null, medications = meds)
        assertTrue(d.shouldNotify)
    }

    @Test
    fun `does not fire beyond the catch-up window`() {
        val tooLate = LocalDateTime.of(2026, 8, 12, 16, 0) // 7h past 09:00
        val d = MedicationReminderPlanner.decide(tooLate, prefsOn, lastNotifiedDay = null, medications = meds)
        assertFalse(d.shouldNotify)
    }

    @Test
    fun `does not fire before the reminder hour`() {
        val early = LocalDateTime.of(2026, 8, 12, 8, 0)
        val d = MedicationReminderPlanner.decide(early, prefsOn, lastNotifiedDay = null, medications = meds)
        assertFalse(d.shouldNotify)
    }

    @Test
    fun `does not fire a second time on the same day`() {
        val alreadyNotified = "2026-08-12"
        val d = MedicationReminderPlanner.decide(onTheHour, prefsOn, lastNotifiedDay = alreadyNotified, medications = meds)
        assertFalse(d.shouldNotify)
    }

    @Test
    fun `fires again the next day`() {
        val nextDay = LocalDateTime.of(2026, 8, 13, 9, 0)
        val d = MedicationReminderPlanner.decide(nextDay, prefsOn, lastNotifiedDay = "2026-08-12", medications = meds)
        assertTrue(d.shouldNotify)
    }

    @Test
    fun `does not fire when reminders are disabled`() {
        val d = MedicationReminderPlanner.decide(
            onTheHour,
            MedicationReminderPrefs(enabled = false, hour = 9),
            lastNotifiedDay = null,
            medications = meds,
        )
        assertFalse(d.shouldNotify)
    }

    @Test
    fun `does not fire when there are no active medications`() {
        val d = MedicationReminderPlanner.decide(onTheHour, prefsOn, lastNotifiedDay = null, medications = emptyList())
        assertFalse(d.shouldNotify)
    }

    @Test
    fun `hour is clamped into the valid range`() {
        assertEquals(0, MedicationReminderPrefs(enabled = true, hour = -5).hourClamped)
        assertEquals(23, MedicationReminderPrefs(enabled = true, hour = 99).hourClamped)
    }

    @Test
    fun `dayKey is the ISO local date`() {
        assertEquals("2026-08-12", MedicationReminderPlanner.dayKey(onTheHour))
    }
}
