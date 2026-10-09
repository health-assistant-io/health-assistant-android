package io.healthassistant.android.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.uiPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "ha_ui_prefs")

/**
 * DataStore-backed persistence for [UiPreferences] (Profile › Accessibility,
 * R6 + the K-simple-mode switch). The [UiPreferences.mode] default rule is
 * conservative for existing installs: a stored `mode` key wins; a completely
 * empty store (fresh install) starts SIMPLE; an install that ever saved any
 * ui pref without a mode keeps the full ADVANCED surface unchanged. The
 * [store] parameter lets JVM tests substitute a fresh per-test DataStore.
 */
class UiPreferencesRepository(
    context: Context,
    private val store: DataStore<Preferences> = context.applicationContext.uiPrefsDataStore,
) {
    val uiPreferences: Flow<UiPreferences> =
        store.data.map { prefs ->
            UiPreferences(
                highContrast = prefs[HIGH_CONTRAST] ?: false,
                reduceMotion = prefs[REDUCE_MOTION] ?: false,
                mode = prefs[MODE].toUiMode() ?: if (prefs.asMap().isEmpty()) UiMode.SIMPLE else UiMode.ADVANCED,
            )
        }

    suspend fun setHighContrast(enabled: Boolean) {
        store.edit { prefs -> prefs[HIGH_CONTRAST] = enabled }
    }

    suspend fun setReduceMotion(enabled: Boolean) {
        store.edit { prefs -> prefs[REDUCE_MOTION] = enabled }
    }

    suspend fun setMode(mode: UiMode) {
        store.edit { prefs -> prefs[MODE] = mode.name }
    }

    private fun String?.toUiMode(): UiMode? = this?.let { value -> UiMode.entries.firstOrNull { it.name == value } }

    private companion object {
        val HIGH_CONTRAST = booleanPreferencesKey("high_contrast")
        val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
        val MODE = stringPreferencesKey("mode")
    }
}
