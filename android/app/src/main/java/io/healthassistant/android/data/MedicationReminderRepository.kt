package io.healthassistant.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.healthassistant.shared.reminders.MedicationReminderPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.medReminderDataStore: DataStore<Preferences> by preferencesDataStore(name = "ha_med_reminders")

/**
 * Phase I (medication reminders) — DataStore-backed persistence for the daily
 * reminder. Holds:
 * - the [MedicationReminderPrefs] (enabled + local reminder hour);
 * - `lastNotifiedDay` — the ISO date guard the worker advances AFTER a
 *   successful notification post (crash-safe: one fire per day, never two);
 * - `cachedMedNames` — the last-known active medication names, so the reminder
 *   still fires offline (the worker falls back to these when the bridge fetch
 *   fails). Names are stored newline-delimited (the app has no JSON on its
 *   compile classpath).
 */
class MedicationReminderRepository(
    context: Context,
) {
    private val store = context.applicationContext.medReminderDataStore

    val prefs: Flow<MedicationReminderPrefs> =
        store.data.map { p ->
            MedicationReminderPrefs(
                enabled = p[ENABLED] ?: false,
                hour = p[HOUR] ?: MedicationReminderPrefs.DEFAULT_HOUR,
            )
        }

    val lastNotifiedDay: Flow<String?> =
        store.data.map { it[LAST_NOTIFIED_DAY] }

    val cachedMedNames: Flow<List<String>> =
        store.data.map { p ->
            p[CACHED_MED_NAMES]
                ?.lineSequence()
                ?.map(String::trim)
                ?.filter { it.isNotBlank() }
                ?.toList()
                ?: emptyList()
        }

    suspend fun currentPrefs(): MedicationReminderPrefs = prefs.first()

    suspend fun currentLastNotifiedDay(): String? = lastNotifiedDay.first()

    suspend fun setEnabled(enabled: Boolean) {
        store.edit { it[ENABLED] = enabled }
    }

    suspend fun setHour(hour: Int) {
        store.edit { it[HOUR] = hour.coerceIn(0, 23) }
    }

    /** Advance the day-guard. Called by the worker only after a successful post. */
    suspend fun markNotified(isoDay: String) {
        store.edit { it[LAST_NOTIFIED_DAY] = isoDay }
    }

    /** Cache the active med names for offline fallback (newline-delimited). */
    suspend fun cacheMedNames(names: List<String>) {
        store.edit { it[CACHED_MED_NAMES] = names.joinToString("\n") }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("enabled")
        val HOUR = intPreferencesKey("hour")
        val LAST_NOTIFIED_DAY = stringPreferencesKey("last_notified_day")
        val CACHED_MED_NAMES = stringPreferencesKey("cached_med_names")
    }
}
