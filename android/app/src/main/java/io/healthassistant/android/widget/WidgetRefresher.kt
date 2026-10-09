package io.healthassistant.android.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * The M4 widget refresh rate cap: at most one widget-render pass per minute,
 * so bursts of triggers (sync completions, config edits, overlapping worker
 * runs) can never turn the widgets into a battery drain. Pure + JVM-tested.
 */
object WidgetRefreshPolicy {
    const val MIN_INTERVAL_MS: Long = 60_000L

    fun shouldRefresh(
        lastMs: Long,
        nowMs: Long,
        minIntervalMs: Long = MIN_INTERVAL_MS,
    ): Boolean = nowMs - lastMs >= minIntervalMs
}

/**
 * M4 — pushes a fresh cache read into every placed widget. Battery-conscious
 * by construction: scheduled periodic (30 min, [io.healthassistant.android.work.SyncScheduler]
 * — KEEP, no constraints because cache reads are disk-only), rate-capped by
 * [WidgetRefreshPolicy], and it never touches the network — the push/pull
 * sync workers own the data cadence, the refresher only re-renders what they
 * landed in the cache. Each widget's `provideGlance` reads the ACTIVE
 * connection's cache via [WidgetDataRepository].
 */
class WidgetRefresher(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!WidgetRefreshPolicy.shouldRefresh(prefs.getLong(KEY_LAST_MS, 0L), now)) {
            return Result.success()
        }
        LatestVitalsWidget().updateAll(context)
        SingleMetricRingWidget().updateAll(context)
        LiveHeartRateWidget().updateAll(context)
        prefs.edit().putLong(KEY_LAST_MS, now).apply()
        return Result.success()
    }

    private companion object {
        const val PREFS_FILE = "ha_widget_refresh"
        const val KEY_LAST_MS = "last_refresh_ms"
    }
}
