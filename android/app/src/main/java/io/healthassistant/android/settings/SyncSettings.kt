package io.healthassistant.android.settings

import io.healthassistant.shared.healthconnect.HcType

/**
 * The user's sync preferences (Phase B of the health-connect-sync plan). Backed
 * by Jetpack DataStore via [SyncSettingsRepository]. Pure data — no Android deps
 * — so it can flow into the Compose UI and the future enhanced [io.healthassistant.android.work.SyncWorker].
 *
 * @param sourceEnabled per-source on/off keyed by [io.healthassistant.shared.source.HealthDataSource.id].
 *   Defaults to Health Connect on.
 * @param enabledTypes the subset of [HcType]s the user wants to sync. Toggling
 *   a type on at runtime requests its `HealthPermission`.
 * @param syncIntervalMinutes periodic sync cadence in minutes; `0` = manual only
 *   (no periodic [io.healthassistant.android.work.SyncWorker]).
 * @param backgroundReadsEnabled Android 14+ Health Connect background-read API
 *   toggle. Below API 34 the UI disables this.
 * @param batteryOptimizationWhitelisted whether the user granted the Doze
 *   whitelist (avoids aggressive OEM kills of the periodic worker).
 * @param historyWindow how far back the first sync reads from Health Connect
 *   (default [SyncHistoryWindow.LAST_7_DAYS]). After the first sync the cursor
 *   handles incremental "new data only" regardless of this setting.
 */
data class SyncSettings(
    val sourceEnabled: Map<String, Boolean> = mapOf(SOURCE_HEALTH_CONNECT to true),
    val enabledTypes: Set<HcType> = setOf(HcType.HEART_RATE, HcType.STEPS),
    val syncIntervalMinutes: Int = INTERVAL_DEFAULT,
    val backgroundReadsEnabled: Boolean = false,
    val batteryOptimizationWhitelisted: Boolean = false,
    val historyWindow: SyncHistoryWindow = SyncHistoryWindow.LAST_7_DAYS,
) {
    /** Is the Health Connect source enabled? Convenience for the UI. */
    val healthConnectEnabled: Boolean
        get() = sourceEnabled[SOURCE_HEALTH_CONNECT] == true

    companion object {
        const val SOURCE_HEALTH_CONNECT = "health_connect"

        /** Valid periodic-interval choices (minutes); `0` = manual only. */
        val INTERVAL_CHOICES: List<Int> = listOf(15, 30, 60, 0)

        /** Default interval (minutes) when the user hasn't chosen yet. */
        const val INTERVAL_DEFAULT = 15
    }
}

/**
 * How far back the first Health Connect sync reads. [floorMs] is the offset from
 * "now" (or null = from epoch / all history). Default is [LAST_7_DAYS] — recent
 * enough to be useful, small enough to sync fast; the cursor keeps new data
 * flowing after that without re-reading history.
 */
enum class SyncHistoryWindow(
    val label: String,
    val floorMs: Long?,
) {
    LAST_7_DAYS("Last 7 days", 7L * 24 * 60 * 60 * 1000),
    LAST_30_DAYS("Last 30 days", 30L * 24 * 60 * 60 * 1000),
    LAST_90_DAYS("Last 3 months", 90L * 24 * 60 * 60 * 1000),
    LAST_YEAR("Last year", 365L * 24 * 60 * 60 * 1000),
    ALL("All history", null),
    ;

    /** Resolve to an epoch-ms floor for a "now" timestamp (null → from epoch). */
    fun floorEpochMs(nowMs: Long): Long = floorMs?.let { nowMs - it } ?: 0L
}
