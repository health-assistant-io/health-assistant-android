package io.healthassistant.android.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.healthassistant.shared.healthconnect.HcType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.syncSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "ha_sync_settings")

/**
 * DataStore Preferences-backed persistence for [SyncSettings] + the per-type
 * read cursors (Phase B of the health-connect-sync plan). Exposes a reactive
 * [Flow] for Compose and a small write API. The cursors (last successful read
 * epoch-ms per [HcType]) are runtime state owned by the future enhanced
 * [io.healthassistant.android.work.SyncWorker] (Phase D); the Settings UI's
 * "Reset cursors" button clears them here.
 */
class SyncSettingsRepository(
    context: Context,
) {
    private val store = context.applicationContext.syncSettingsDataStore

    val settings: Flow<SyncSettings> =
        store.data.map { it.toSettings() }

    val cursors: Flow<Map<HcType, Long>> =
        store.data.map { prefs ->
            HcType.entries
                .mapNotNull { type ->
                    prefs[cursorKey(type)]?.let { type to it }
                }.toMap()
        }

    suspend fun current(): SyncSettings = settings.first()

    suspend fun setSourceEnabled(
        sourceId: String,
        enabled: Boolean,
    ) {
        store.edit { prefs ->
            prefs[sourceEnabledKey(sourceId)] = enabled
        }
    }

    suspend fun setEnabledTypes(types: Set<HcType>) {
        store.edit { prefs ->
            prefs[ENABLED_TYPES] = types.map { it.name }.toSet()
        }
    }

    suspend fun toggleType(
        type: HcType,
        enabled: Boolean,
    ) {
        val current = current()
        val updated =
            if (enabled) current.enabledTypes + type else current.enabledTypes - type
        setEnabledTypes(updated)
    }

    suspend fun setSyncIntervalMinutes(minutes: Int) {
        store.edit { prefs -> prefs[SYNC_INTERVAL] = minutes }
    }

    suspend fun setBackgroundReadsEnabled(enabled: Boolean) {
        store.edit { prefs -> prefs[BACKGROUND_READS] = enabled }
    }

    suspend fun setBatteryOptimizationWhitelisted(whitelisted: Boolean) {
        store.edit { prefs -> prefs[BATTERY_WHITELIST] = whitelisted }
    }

    suspend fun setHistoryWindow(window: SyncHistoryWindow) {
        store.edit { prefs -> prefs[HISTORY_WINDOW] = window.name }
    }

    /** Update the per-type cursor (= last successful read epoch-ms). */
    suspend fun setCursor(
        type: HcType,
        epochMs: Long,
    ) {
        store.edit { prefs -> prefs[cursorKey(type)] = epochMs }
    }

    /** Bulk-update cursors after a successful source read. */
    suspend fun setCursors(update: Map<HcType, Long>) {
        store.edit { prefs ->
            update.forEach { (type, ms) -> prefs[cursorKey(type)] = ms }
        }
    }

    /** Clear all per-type cursors — next sync re-reads from epoch (the Settings
     *  "Reset cursors" action). */
    suspend fun resetCursors() {
        store.edit { prefs -> HcType.entries.forEach { prefs.remove(cursorKey(it)) } }
    }

    private fun Preferences.toSettings(): SyncSettings {
        val hcKey = sourceEnabledKey(SyncSettings.SOURCE_HEALTH_CONNECT)
        val sourceMap =
            if (contains(hcKey)) {
                mapOf(SyncSettings.SOURCE_HEALTH_CONNECT to (this[hcKey] == true))
            } else {
                SyncSettings().sourceEnabled
            }
        val types =
            (this[ENABLED_TYPES] ?: emptySet())
                .mapNotNull { name -> runCatching { HcType.valueOf(name) }.getOrNull() }
                .toSet()
        return SyncSettings(
            sourceEnabled = sourceMap,
            enabledTypes = types,
            syncIntervalMinutes = this[SYNC_INTERVAL] ?: SyncSettings.INTERVAL_DEFAULT,
            backgroundReadsEnabled = this[BACKGROUND_READS] ?: false,
            batteryOptimizationWhitelisted = this[BATTERY_WHITELIST] ?: false,
            historyWindow =
                (
                    this[HISTORY_WINDOW]?.let { name -> runCatching { SyncHistoryWindow.valueOf(name) }.getOrNull() }
                        ?: SyncHistoryWindow.LAST_7_DAYS
                ),
        )
    }

    private fun cursorKey(type: HcType) = longPreferencesKey("cursor_${type.name}")

    private fun sourceEnabledKey(id: String) = booleanPreferencesKey("source_enabled_$id")

    private companion object {
        val ENABLED_TYPES = stringSetPreferencesKey("enabled_types")
        val SYNC_INTERVAL = intPreferencesKey("sync_interval_minutes")
        val BACKGROUND_READS = booleanPreferencesKey("background_reads")
        val BATTERY_WHITELIST = booleanPreferencesKey("battery_whitelist")
        val HISTORY_WINDOW = stringPreferencesKey("history_window")
    }
}
