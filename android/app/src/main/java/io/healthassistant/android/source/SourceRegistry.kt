package io.healthassistant.android.source

import io.healthassistant.android.settings.SyncSettings
import io.healthassistant.shared.source.HealthDataSource

/**
 * Phase E: the pluggable source registry. Each [HealthDataSource] implementation
 * registers here (via Koin); the enhanced [io.healthassistant.android.work.SyncWorker]
 * iterates the registered sources, the [io.healthassistant.android.monitoring.SyncMonitorRepository]
 * derives its source list from it, and the Monitoring dashboard renders them all.
 *
 * A source is synced when it is [isSyncEnabled]: `manual` is always-on (a stub
 * demo source), every other source respects the user's per-source toggle in
 * [SyncSettings.sourceEnabled]. Future sources (Fitbit, Withings) register the
 * same way and appear automatically.
 */
class SourceRegistry(
    private val sources: List<HealthDataSource>,
) {
    fun all(): List<HealthDataSource> = sources

    fun byId(id: String): HealthDataSource? = sources.firstOrNull { it.id == id }

    /** The sources the worker should read from given the user's [settings]. */
    fun syncEnabled(settings: SyncSettings): List<HealthDataSource> = sources.filter { isSyncEnabled(it, settings) }

    companion object {
        /** A source is read if `manual` (stub, always-on) or enabled by the user. */
        fun isSyncEnabled(
            source: HealthDataSource,
            settings: SyncSettings,
        ): Boolean = source.id == "manual" || settings.sourceEnabled[source.id] == true
    }
}
