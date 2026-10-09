package io.healthassistant.android.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.onboardingPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "ha_onboarding_prefs")

/** Persists the first-run onboarding flag (R7): the 3-slide welcome is shown
 *  once, then never again. */
class OnboardingPrefsRepository(
    context: Context,
) {
    private val store = context.applicationContext.onboardingPrefsDataStore

    val welcomeShown: Flow<Boolean> =
        store.data.map { prefs -> prefs[WELCOME_SHOWN] ?: false }

    suspend fun setWelcomeShown() {
        store.edit { prefs -> prefs[WELCOME_SHOWN] = true }
    }

    private companion object {
        val WELCOME_SHOWN = booleanPreferencesKey("welcome_shown")
    }
}
