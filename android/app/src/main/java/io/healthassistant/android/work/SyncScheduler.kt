package io.healthassistant.android.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Schedules the [SyncWorker]: a periodic network-constrained drain + an on-demand
 * expedited "Sync now". Also schedules the [PullSyncWorker] (Phase G — two-way
 * incremental pull of server-side clinical mutations via `GET /changes`) and
 * the [DocumentCacheEvictor] (offline-first M4 — daily document byte-cache
 * size enforcement). */
object SyncScheduler {
    private const val PERIODIC = "ha_sync_periodic"
    private const val ON_DEMAND = "ha_sync_now"
    private const val PULL_PERIODIC = "ha_pull_periodic"
    private const val PULL_ON_DEMAND = "ha_pull_now"
    private const val DOC_EVICTOR_PERIODIC = "ha_doc_evictor_periodic"
    private const val WIDGET_REFRESH_PERIODIC = "ha_widget_refresh_periodic"

    private val networkConstraint =
        Constraints
            .Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    fun schedulePeriodic(context: Context) {
        val request =
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkConstraint)
                .build()
        WorkManager
            .getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun syncNow(context: Context) {
        // KEEP: a "Sync now" tap does NOT cancel an in-flight sync (the running
        // pass continues; the tap is a no-op while one is already in progress).
        enqueue<SyncWorker>(context, ON_DEMAND, ExistingWorkPolicy.KEEP)
    }

    /** Chain another pass from the worker itself (more backlog / more history to
     *  backfill). APPEND_OR_REPLACE attaches as a continuation of the running work
     *  so it never cancels the in-flight pass. [initialDelayMs] defers the chained
     *  pass (e.g. the 429 rate-limit cooldown). */
    fun syncContinue(
        context: Context,
        initialDelayMs: Long = 0L,
    ) {
        enqueue<SyncWorker>(context, ON_DEMAND, ExistingWorkPolicy.APPEND_OR_REPLACE, initialDelayMs)
    }

    /** Phase G — schedule the periodic `/changes` pull (30 min, network-constrained).
     *  KEEP so re-launching the app does not reset the cadence. The period is
     *  deliberately longer than the push worker's 15 min — the pull is a
     *  best-effort refresh, not a backlog drain. */
    fun schedulePullPeriodic(context: Context) {
        val request =
            PeriodicWorkRequestBuilder<PullSyncWorker>(30, TimeUnit.MINUTES)
                .setConstraints(networkConstraint)
                .build()
        WorkManager
            .getInstance(context)
            .enqueueUniquePeriodicWork(PULL_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Offline-first M4 — daily eviction of the document byte cache (no
     *  constraints: eviction is disk-only, never network). */
    fun scheduleDocumentEvictor(context: Context) {
        val request =
            PeriodicWorkRequestBuilder<DocumentCacheEvictor>(1, TimeUnit.DAYS).build()
        WorkManager
            .getInstance(context)
            .enqueueUniquePeriodicWork(DOC_EVICTOR_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** M4 (widgets) — periodic re-render of the home-screen widgets from the
     *  observation cache (30 min, KEEP, no constraints: cache reads are
     *  disk-only). Matches the pull cadence — the widgets can never be
     *  fresher than what the sync workers landed, so refreshing more often
     *  would only burn battery; the [WidgetRefresher] itself is additionally
     *  rate-capped to one pass per minute. */
    fun scheduleWidgetRefresh(context: Context) {
        val request =
            PeriodicWorkRequestBuilder<io.healthassistant.android.widget.WidgetRefresher>(30, TimeUnit.MINUTES).build()
        WorkManager
            .getInstance(context)
            .enqueueUniquePeriodicWork(WIDGET_REFRESH_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Phase G — on-demand pull (pull-to-refresh on Home/Today/Inbox). KEEP: a
     *  refresh tap does not cancel an in-flight pull. */
    fun pullNow(context: Context) {
        enqueue<PullSyncWorker>(context, PULL_ON_DEMAND, ExistingWorkPolicy.KEEP)
    }

    private inline fun <reified T : ListenableWorker> enqueue(
        context: Context,
        name: String,
        policy: ExistingWorkPolicy,
        initialDelayMs: Long = 0L,
    ) {
        val request =
            OneTimeWorkRequestBuilder<T>()
                .setConstraints(networkConstraint)
                .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name, policy, request)
    }
}
