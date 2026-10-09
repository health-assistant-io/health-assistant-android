package io.healthassistant.android.work

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.healthassistant.android.HAApplication
import io.healthassistant.android.data.CredentialStore
import io.healthassistant.android.data.MedicationReminderRepository
import io.healthassistant.bridge.BridgeClient
import io.healthassistant.shared.data.displayName
import io.healthassistant.shared.reminders.MedicationReminderPlanner
import io.healthassistant.shared.reminders.ReminderMedication
import kotlinx.coroutines.flow.first
import org.koin.core.context.GlobalContext
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Phase I (medication reminders) — the daily "time to take your medication"
 * worker. Runs once per day at the user's reminder hour (scheduled by
 * [MedicationReminderScheduler]):
 *
 * 1. reads the reminder prefs (no-op when disabled);
 * 2. fetches active medications from the bridge (falls back to the cached names
 *    in [MedicationReminderRepository] when offline / no connection — the
 *    reminder is local);
 * 3. asks [MedicationReminderPlanner.decide] whether to fire now (hour window +
 *    one-fire-per-day guard);
 * 4. posts one notification per medication + advances the day-guard ONLY after
 *    a successful post (crash-safe);
 * 5. re-schedules the next day's tick.
 */
class MedicationReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val koin = GlobalContext.getOrNull()
        val repo: MedicationReminderRepository = koin?.get() ?: return Result.failure()
        val prefs = repo.currentPrefs()
        if (!prefs.enabled) return Result.success()

        val meds = loadMedications(repo)
        val now = LocalDateTime.now()
        val decision =
            MedicationReminderPlanner.decide(
                now = now,
                prefs = prefs,
                lastNotifiedDay = repo.currentLastNotifiedDay(),
                medications = meds,
            )

        if (decision.shouldNotify) {
            Log.i(TAG, "posting ${decision.medications.size} medication reminder(s)")
            MedicationReminderNotifications.postReminders(applicationContext, decision.medications)
            repo.markNotified(MedicationReminderPlanner.dayKey(now))
        } else {
            Log.d(TAG, "no reminder this tick (enabled=${prefs.enabled} meds=${meds.size})")
        }

        // Always re-arm tomorrow's tick (idempotent — REPLACE).
        MedicationReminderScheduler.schedule(applicationContext, prefs)
        return Result.success()
    }

    private suspend fun loadMedications(repo: MedicationReminderRepository): List<ReminderMedication> {
        val credential = CredentialStore(applicationContext).load()
        if (credential != null) {
            val client =
                BridgeClient(
                    baseUrl = credential.baseUrl,
                    integrationId = credential.integrationId,
                    apiSecret = credential.apiSecret,
                )
            return try {
                val fetched =
                    client
                        .getMedications(limit = FETCH_LIMIT)
                        .data
                        .filter { it.status?.uppercase() == "ACTIVE" }
                        .map { ReminderMedication(it.id, it.displayName ?: it.id, it.dosage) }
                repo.cacheMedNames(fetched.map { it.name })
                fetched
            } catch (e: Exception) {
                Log.w(TAG, "fetch failed, falling back to cache: ${e.message}")
                cachedNames(repo)
            }
        }
        return cachedNames(repo)
    }

    private suspend fun cachedNames(repo: MedicationReminderRepository): List<ReminderMedication> =
        repo.cachedMedNames.first().map { ReminderMedication(id = it, name = it) }

    private companion object {
        const val TAG = "HAMedReminder"
        const val FETCH_LIMIT = 200
    }
}

/**
 * Phase I — posts the reminder notifications + defines the action intents. One
 * notification per medication on the high-importance `ha_medication` channel,
 * each with "Mark as taken" (dismiss) + "Snooze 15 min" (re-post via
 * [SnoozeMedicationReminderWorker]) actions delivered to
 * [MedicationReminderReceiver]. A stable per-medication notification id means
 * re-fires replace the previous one instead of stacking.
 */
object MedicationReminderNotifications {
    const val ACTION_MARK_TAKEN = "io.healthassistant.android.action.MED_MARK_TAKEN"
    const val ACTION_SNOOZE = "io.healthassistant.android.action.MED_SNOOZE"
    const val EXTRA_MED_ID = "med_id"
    const val EXTRA_MED_NAME = "med_name"
    const val EXTRA_NOTIF_ID = "notif_id"

    const val CHANNEL = HAApplication.CHANNEL_MEDICATION

    fun postReminders(
        context: Context,
        meds: List<ReminderMedication>,
    ) {
        meds.forEach { postOne(context, it) }
    }

    @SuppressLint("MissingPermission")
    fun postOne(
        context: Context,
        med: ReminderMedication,
    ) {
        val notifId = notificationId(med.id)
        val contentIntent =
            PendingIntent.getActivity(
                context,
                notifId,
                context.packageManager.getLaunchIntentForPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notif =
            NotificationCompat
                .Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Time to take ${med.name}")
                .setContentText(med.dosage?.takeIf { it.isNotBlank() } ?: "Remember to take ${med.name}")
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .addAction(
                    0,
                    "Snooze 15 min",
                    actionIntent(context, ACTION_SNOOZE, med.id, med.name, notifId),
                ).addAction(
                    0,
                    "Mark as taken",
                    actionIntent(context, ACTION_MARK_TAKEN, med.id, med.name, notifId),
                ).build()
        runCatching { NotificationManagerCompat.from(context).notify(notifId, notif) }
    }

    @SuppressLint("MissingPermission")
    fun dismiss(
        context: Context,
        notificationId: Int,
    ) {
        runCatching { NotificationManagerCompat.from(context).cancel(notificationId) }
    }

    private fun actionIntent(
        context: Context,
        action: String,
        medId: String,
        medName: String,
        notifId: Int,
    ): PendingIntent {
        val intent =
            Intent(context, MedicationReminderReceiver::class.java).apply {
                this.action = action
                putExtra(EXTRA_MED_ID, medId)
                putExtra(EXTRA_MED_NAME, medName)
                putExtra(EXTRA_NOTIF_ID, notifId)
            }
        return PendingIntent.getBroadcast(
            context,
            notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Stable notification id per medication (collisions across meds are fine). */
    fun notificationId(medId: String): Int = MED_BASE + (medId.hashCode() and 0x7fffffff) % MED_RANGE

    private const val MED_BASE = 2000
    private const val MED_RANGE = 10_000
}

/**
 * Phase I — handles the notification actions. "Mark as taken" cancels the
 * notification (the app has no server-side taken-tracking in v2); "Snooze 15
 * min" enqueues a one-time [SnoozeMedicationReminderWorker] that re-posts it.
 * Registered in the manifest (exported=false).
 */
class MedicationReminderReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            MedicationReminderNotifications.ACTION_MARK_TAKEN -> {
                val notifId = intent.getIntExtra(MedicationReminderNotifications.EXTRA_NOTIF_ID, 0)
                if (notifId != 0) MedicationReminderNotifications.dismiss(context, notifId)
            }
            MedicationReminderNotifications.ACTION_SNOOZE -> {
                val medId = intent.getStringExtra(MedicationReminderNotifications.EXTRA_MED_ID) ?: return
                val medName = intent.getStringExtra(MedicationReminderNotifications.EXTRA_MED_NAME) ?: medId
                val notifId = intent.getIntExtra(MedicationReminderNotifications.EXTRA_NOTIF_ID, 0)
                MedicationReminderNotifications.dismiss(context, notifId)
                val request =
                    OneTimeWorkRequestBuilder<SnoozeMedicationReminderWorker>()
                        .setInputData(
                            Data
                                .Builder()
                                .putString("med_id", medId)
                                .putString("med_name", medName)
                                .build(),
                        ).setInitialDelay(SNOOZE_MINUTES, TimeUnit.MINUTES)
                        .build()
                WorkManager
                    .getInstance(context)
                    .enqueueUniqueWork(
                        "ha_med_snooze_$medId",
                        ExistingWorkPolicy.REPLACE,
                        request,
                    )
            }
        }
    }

    private companion object {
        const val SNOOZE_MINUTES = 15L
    }
}

/** Re-posts a single medication's reminder after the user snoozed. */
class SnoozeMedicationReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val medName = inputData.getString("med_name") ?: return Result.success()
        val medId = inputData.getString("med_id") ?: medName
        MedicationReminderNotifications.postOne(
            applicationContext,
            ReminderMedication(id = medId, name = medName),
        )
        return Result.success()
    }
}
