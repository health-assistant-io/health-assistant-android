package io.healthassistant.android.settings

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.healthassistant.shared.healthconnect.HcType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase B gate: DataStore round-trip for [SyncSettingsRepository]. Runs on a
 * device/emulator (DataStore needs a real `Context`). Each test starts from a
 * clean DataStore file so they are order-independent.
 */
@RunWith(AndroidJUnit4::class)
class SyncSettingsRepositoryTest {
    private lateinit var repo: SyncSettingsRepository

    @Before
    fun setUp() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            repo = SyncSettingsRepository(context)
            // Start clean.
            repo.resetCursors()
            repo.setEnabledTypes(emptySet())
            repo.setSourceEnabled(SyncSettings.SOURCE_HEALTH_CONNECT, true)
            repo.setSyncIntervalMinutes(SyncSettings.INTERVAL_DEFAULT)
            repo.setBackgroundReadsEnabled(false)
            repo.setBatteryOptimizationWhitelisted(false)
        }

    @After
    fun tearDown() =
        runBlocking {
            repo.resetCursors()
            repo.setEnabledTypes(emptySet())
        }

    @Test
    fun defaults_are_applied_on_first_read() =
        runBlocking {
            // After setUp we reset to defaults; current() should reflect them.
            val settings = repo.current()
            assertTrue(settings.healthConnectEnabled)
            assertEquals(SyncSettings.INTERVAL_DEFAULT, settings.syncIntervalMinutes)
            assertFalse(settings.backgroundReadsEnabled)
            assertFalse(settings.batteryOptimizationWhitelisted)
        }

    @Test
    fun toggle_source_persists_and_round_trips() =
        runBlocking {
            repo.setSourceEnabled(SyncSettings.SOURCE_HEALTH_CONNECT, false)

            val reloaded = repo.current()
            assertFalse(reloaded.healthConnectEnabled)
        }

    @Test
    fun toggle_type_adds_and_removes() =
        runBlocking {
            repo.setEnabledTypes(emptySet())

            repo.toggleType(HcType.HEART_RATE, true)
            assertTrue(HcType.HEART_RATE in repo.current().enabledTypes)

            repo.toggleType(HcType.STEPS, true)
            assertEquals(setOf(HcType.HEART_RATE, HcType.STEPS), repo.current().enabledTypes)

            repo.toggleType(HcType.HEART_RATE, false)
            assertFalse(HcType.HEART_RATE in repo.current().enabledTypes)
            assertTrue(HcType.STEPS in repo.current().enabledTypes)
        }

    @Test
    fun interval_choice_round_trips() =
        runBlocking {
            for (minutes in SyncSettings.INTERVAL_CHOICES) {
                repo.setSyncIntervalMinutes(minutes)
                assertEquals(minutes, repo.current().syncIntervalMinutes)
            }
        }

    @Test
    fun advanced_flags_round_trip() =
        runBlocking {
            repo.setBackgroundReadsEnabled(true)
            repo.setBatteryOptimizationWhitelisted(true)
            val reloaded = repo.current()
            assertTrue(reloaded.backgroundReadsEnabled)
            assertTrue(reloaded.batteryOptimizationWhitelisted)
        }

    @Test
    fun cursors_round_trip_and_reset() =
        runBlocking {
            repo.setCursor(HcType.HEART_RATE, 1_000L)
            repo.setCursors(mapOf(HcType.STEPS to 2_000L, HcType.WEIGHT to 3_000L))

            val cursors = repo.cursors.first()
            assertEquals(1_000L, cursors[HcType.HEART_RATE])
            assertEquals(2_000L, cursors[HcType.STEPS])
            assertEquals(3_000L, cursors[HcType.WEIGHT])

            repo.resetCursors()
            val cleared = repo.cursors.first()
            assertTrue(cleared.isEmpty())
        }
}
