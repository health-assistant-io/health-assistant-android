package io.healthassistant.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.StrictMode
import io.healthassistant.android.BuildConfig
import io.healthassistant.android.alerts.AlertNotifications
import io.healthassistant.android.data.AppLockManager
import io.healthassistant.android.data.DatabaseMigrator
import io.healthassistant.android.data.cache.ObservationDatabase
import io.healthassistant.android.di.appModule
import io.healthassistant.android.work.SyncNotifications
import io.healthassistant.android.work.SyncScheduler
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/** Starts Koin on launch and schedules the periodic background sync (Phase 5). */
class HAApplication : Application() {
    /**
     * Phase B.4 — eagerly grabbed from Koin in [MainActivity] so the
     * ProcessLifecycleObserver can feed the backgrounded/foreground hooks
     * without doing its own Koin resolution in the lifecycle callback.
     * Resolved lazily after [startKoin] runs in [onCreate].
     */
    lateinit var appLockManager: AppLockManager
        private set

    override fun onCreate() {
        super.onCreate()
        // Phase L.2 — StrictMode in debug only. Network access on the main
        // thread crashes (always a bug → ANR risk); main-thread disk WRITES are
        // logged (catches the one-time Phase C migration + future regressions;
        // disk reads are intentionally not flagged — the app legitimately reads
        // DataStore/SharedPreferences on the main thread at startup). Stripped
        // from release by BuildConfig.DEBUG (R8 drops the dead branch).
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy
                    .Builder()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .penaltyDeathOnNetwork()
                    .build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy
                    .Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build(),
            )
        }
        startKoin {
            androidContext(this@HAApplication)
            modules(appModule)
        }
        // Phase C — one-shot plaintext → SQLCipher migration. MUST run before
        // the encrypted Room DB is first opened (it deletes the old plaintext
        // cache file). runBlocking is fine: the DataStore guard makes it a
        // near-instant no-op after the first (upgrade) launch.
        runBlocking {
            DatabaseMigrator.migrateIfNeeded(this@HAApplication) {
                get<ObservationDatabase>().outboxDao()
            }
        }
        appLockManager = get()
        SyncScheduler.schedulePeriodic(this)
        SyncScheduler.schedulePullPeriodic(this)
        SyncScheduler.scheduleDocumentEvictor(this)
        SyncScheduler.scheduleWidgetRefresh(this)
        SyncNotifications.createChannel(this)
        AlertNotifications.createChannel(this)
        createNotificationChannels()
    }

    /** Phase I — create the 5 additional notification channels (sync already
     *  has one via [SyncNotifications]). Each channel is user-tunable in the
     *  system notification settings, giving the user per-category control
     *  without us building it. */
    private fun createNotificationChannels() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channels =
            listOf(
                NotificationChannel(
                    CHANNEL_MEDICATION,
                    "Medication reminders",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "Reminders to take your medications" },
                NotificationChannel(
                    CHANNEL_EXAMINATION,
                    "Examination results",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "Updates when your exam results are ready" },
                NotificationChannel(
                    CHANNEL_CLINICAL_ALERT,
                    "Clinical alerts",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "Important clinical notifications" },
                NotificationChannel(
                    CHANNEL_ANOMALY,
                    "Anomaly alerts",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "Alerts when a biomarker is out of range" },
                NotificationChannel(
                    CHANNEL_GENERAL,
                    "General",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "System notifications and announcements" },
            )
        channels.forEach { ch ->
            if (mgr.getNotificationChannel(ch.id) == null) mgr.createNotificationChannel(ch)
        }
    }

    companion object {
        const val CHANNEL_MEDICATION = "ha_medication"
        const val CHANNEL_EXAMINATION = "ha_examination"
        const val CHANNEL_CLINICAL_ALERT = "ha_clinical_alert"
        const val CHANNEL_ANOMALY = "ha_anomaly"
        const val CHANNEL_GENERAL = "ha_general"
    }
}
