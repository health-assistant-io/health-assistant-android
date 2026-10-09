package io.healthassistant.android.work

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.healthassistant.shared.reminders.MedicationReminderPrefs
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Phase I (medication reminders) — schedules the [MedicationReminderWorker] as a
 * one-time, time-bound run that fires at the user's reminder hour (local time)
 * and re-schedules the next day from inside the worker. Precise + minimal wake
 * count (one tick per day + a retry window), unlike a periodic worker that
 * would poll every 15–30 min.
 *
 * [schedule] cancels any pending run then enqueues a fresh one at the next
 * target hour; it no-ops when reminders are disabled. Called from
 * `Profile › Notifications` on every toggle/time change + from the worker after
 * it fires + on app launch.
 */
object MedicationReminderScheduler {
    const val WORK_NAME = "ha_med_reminder"

    fun schedule(
        context: Context,
        prefs: MedicationReminderPrefs,
    ) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(WORK_NAME)
        if (!prefs.enabled) return
        val request =
            OneTimeWorkRequestBuilder<MedicationReminderWorker>()
                .setInitialDelay(delayToNextTarget(prefs.hourClamped), TimeUnit.MILLISECONDS)
                .build()
        wm.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}

/**
 * Milliseconds until the next occurrence of [hour]:00 (local time) — today if
 * the hour hasn't passed yet, else tomorrow. Pure + JVM-testable (the worker /
 * settings tap the phone clock via `LocalDateTime.now()`).
 */
internal fun delayToNextTarget(
    hour: Int,
    now: LocalDateTime = LocalDateTime.now(),
): Long {
    val today = now.toLocalDate().atTime(hour.coerceIn(0, 23), 0)
    val target = if (now.isBefore(today)) today else today.plusDays(1)
    return Duration.between(now, target).toMillis()
}
