package io.healthassistant.android.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * K-simple-mode gate: the mode default rule (fresh install → SIMPLE,
 * existing install with saved prefs → ADVANCED) + the SIMPLE/ADVANCED
 * round-trip. Each test gets its own DataStore file (the production
 * `preferencesDataStore` delegate is a per-file singleton, so a fresh store
 * is injected through the repository's test constructor parameter).
 */
@RunWith(RobolectricTestRunner::class)
class UiPreferencesRepositoryTest {
    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder.builder().assureDeletion().build()

    private fun repository(): UiPreferencesRepository {
        val store =
            PreferenceDataStoreFactory.create(produceFile = { File(tmpFolder.newFolder(), "ui_prefs_test.preferences_pb") })
        return UiPreferencesRepository(ApplicationProvider.getApplicationContext<Context>(), store)
    }

    @Test
    fun fresh_install_with_no_saved_prefs_defaults_to_simple() =
        runBlocking {
            val repo = repository()

            assertEquals(UiMode.SIMPLE, repo.uiPreferences.first().mode)
        }

    @Test
    fun existing_install_with_saved_prefs_but_no_mode_defaults_to_advanced() =
        runBlocking {
            val store =
                PreferenceDataStoreFactory.create(produceFile = { File(tmpFolder.newFolder(), "ui_prefs_test.preferences_pb") })
            store.edit { it[booleanPreferencesKey("high_contrast")] = true }
            val repo = UiPreferencesRepository(ApplicationProvider.getApplicationContext<Context>(), store)

            assertEquals(UiMode.ADVANCED, repo.uiPreferences.first().mode)
        }

    @Test
    fun unknown_stored_mode_value_falls_back_to_advanced() =
        runBlocking {
            val store =
                PreferenceDataStoreFactory.create(produceFile = { File(tmpFolder.newFolder(), "ui_prefs_test.preferences_pb") })
            store.edit { it[stringPreferencesKey("mode")] = "bogus" }
            val repo = UiPreferencesRepository(ApplicationProvider.getApplicationContext<Context>(), store)

            assertEquals(UiMode.ADVANCED, repo.uiPreferences.first().mode)
        }

    @Test
    fun mode_round_trips_through_the_store() =
        runBlocking {
            val repo = repository()

            repo.setMode(UiMode.ADVANCED)
            assertEquals(UiMode.ADVANCED, repo.uiPreferences.first().mode)

            repo.setMode(UiMode.SIMPLE)
            assertEquals(UiMode.SIMPLE, repo.uiPreferences.first().mode)
        }

    @Test
    fun theme_defaults_to_aurora_and_round_trips() =
        runBlocking {
            val repo = repository()

            assertEquals(UiTheme.AURORA, repo.uiPreferences.first().theme)

            repo.setTheme(UiTheme.MATERIAL_YOU)
            assertEquals(UiTheme.MATERIAL_YOU, repo.uiPreferences.first().theme)

            repo.setTheme(UiTheme.TEAL)
            assertEquals(UiTheme.TEAL, repo.uiPreferences.first().theme)
        }

    @Test
    fun unknown_stored_theme_value_falls_back_to_aurora() =
        runBlocking {
            val store =
                PreferenceDataStoreFactory.create(produceFile = { File(tmpFolder.newFolder(), "ui_prefs_test.preferences_pb") })
            store.edit { it[stringPreferencesKey("theme_preset")] = "coral" }
            val repo = UiPreferencesRepository(ApplicationProvider.getApplicationContext<Context>(), store)

            assertEquals(UiTheme.AURORA, repo.uiPreferences.first().theme)
        }

    @Test
    fun setting_a_mode_preserves_the_other_prefs() =
        runBlocking {
            val repo = repository()

            repo.setHighContrast(true)
            repo.setMode(UiMode.SIMPLE)

            val prefs = repo.uiPreferences.first()
            assertEquals(UiMode.SIMPLE, prefs.mode)
            assertEquals(true, prefs.highContrast)
        }
}
