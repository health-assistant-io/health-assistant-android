package io.healthassistant.shared.reminders

import java.time.LocalDateTime

/**
 * Phase I (medication reminders) — one medication the daily reminder should
 * fire for. Built from the bridge `Medication` model (id + display name +
 * optional dosage line); pure data so the planner stays JVM-testable.
 */
data class ReminderMedication(
    val id: String,
    val name: String,
    val dosage: String? = null,
)

/**
 * Phase I — user preference for the daily medication reminder. [hour] is the
 * local hour (0–23) at which the reminder fires (with a catch-up window), and
 * is clamped on construction so a bad persisted value can never spin the
 * worker.
 */
data class MedicationReminderPrefs(
    val enabled: Boolean = false,
    val hour: Int = DEFAULT_HOUR,
) {
    val hourClamped: Int get() = hour.coerceIn(0, 23)

    companion object {
        const val DEFAULT_HOUR = 9
    }
}

/**
 * Phase I — the deterministic "fire now?" decision for one worker tick.
 * [shouldNotify] is true only when the reminder should be posted; the worker
 * then posts one notification per [medications].
 */
data class ReminderDecision(
    val shouldNotify: Boolean,
    val medications: List<ReminderMedication>,
)

/**
 * Phase I (medication reminders) — pure decision logic for when to fire the
 * daily "time to take your medication" reminder. Fully deterministic + JVM
 * tested; the Android worker just maps bridge rows → [ReminderMedication]s,
 * calls [decide], and posts the notifications.
 *
 * Fires when ALL of:
 * - reminders are [MedicationReminderPrefs.enabled];
 * - at least one active medication exists;
 * - it is the reminder [MedicationReminderPrefs.hour] (local time) OR within
 *   [CATCHUP_HOURS] past it — so a Doze-delayed run still catches the dose
 *   without missing the whole day;
 * - [lastNotifiedDay] (an ISO date key, e.g. "2026-08-12") is not today — the
 *   day-guard guarantees at most one fire per calendar day even if the worker
 *   runs several times inside the window.
 *
 * The one-fire-per-day guarantee is the crash-safety property: it is keyed on
 * the persisted [lastNotifiedDay], which the worker advances ONLY after a
 * successful notification post.
 */
object MedicationReminderPlanner {

    /** Late-run grace: fire up to this many hours past the target hour. */
    const val CATCHUP_HOURS = 6

    fun decide(
        now: LocalDateTime,
        prefs: MedicationReminderPrefs,
        lastNotifiedDay: String?,
        medications: List<ReminderMedication>,
    ): ReminderDecision {
        val hour = prefs.hourClamped
        val today = now.toLocalDate().toString()
        val inWindow =
            now.hour == hour ||
                (now.hour > hour && now.hour - hour <= CATCHUP_HOURS)
        val shouldNotify =
            prefs.enabled && medications.isNotEmpty() && inWindow && lastNotifiedDay != today
        return ReminderDecision(shouldNotify = shouldNotify, medications = medications)
    }

    /** The ISO date key for [now]'s day — what the worker persists as the guard. */
    fun dayKey(now: LocalDateTime): String = now.toLocalDate().toString()
}
