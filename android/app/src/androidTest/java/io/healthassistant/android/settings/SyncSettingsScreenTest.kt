package io.healthassistant.android.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.healthassistant.shared.healthconnect.HcType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Phase B gate: the Sync settings Compose screen renders all controls (plan §4)
 * and routes toggle intent correctly — enabling a type requests its Health
 * Connect permission; disabling a type persists directly. Uses `setContent` on
 * `MainActivity` (the repo's established Compose-test pattern, see
 * `DashboardScreenTest`/`OnboardingScreenTest`) so it stays independent of
 * AppRoot's nav state. NOTE: on MIUI/POCO this requires the "background
 * activity launch" / MIUI-optimization toggle to be enabled, otherwise
 * `ActivityScenario.launch` is blocked — same constraint as the other Compose
 * UI tests in this module.
 */
class SyncSettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun sampleSettings() =
        SyncSettings(
            sourceEnabled = mapOf(SyncSettings.SOURCE_HEALTH_CONNECT to true),
            enabledTypes = setOf(HcType.HEART_RATE),
            syncIntervalMinutes = 60,
            backgroundReadsEnabled = false,
            batteryOptimizationWhitelisted = false,
        )

    @Test
    fun shows_title_and_all_controls() {
        composeRule.setContent {
            SyncSettingsScreen(
                settings = sampleSettings(),
                sourceAvailable = true,
                syncedCounts = mapOf(HcType.HEART_RATE to 42),
                onToggleSource = {},
                onToggleType = { _, _ -> },
                onRequestTypePermission = {},
                onSetInterval = {},
                onSetBackgroundReads = {},
                onSetBatteryWhitelist = {},
                onSetHistoryWindow = {},
                onResetCursors = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithText("Sync settings").assertIsDisplayed()
        // Source card.
        composeRule.onNodeWithText("Health Connect").assertIsDisplayed()
        composeRule.onNodeWithText("Connected · 1 types enabled").assertIsDisplayed()
        // Frequency.
        composeRule.onNodeWithText("Sync frequency").assertIsDisplayed()
        composeRule.onNodeWithText("Every hour").assertIsDisplayed()
        composeRule.onNodeWithText("Manual only").assertIsDisplayed()
        // Data types — HR enabled with count, Weight off.
        composeRule.onNodeWithText("Data types (Health Connect)").assertIsDisplayed()
        composeRule.onNodeWithText("42 synced").assertIsDisplayed()
        composeRule.onNodeWithText("8867-4 · loinc").assertIsDisplayed()
        // Advanced section header.
        composeRule.onNodeWithText("Advanced").assertIsDisplayed()
    }

    @Test
    fun toggling_disabled_type_requests_permission() {
        val requested = mutableListOf<Set<String>>()
        var toggled: Pair<HcType, Boolean>? = null

        composeRule.setContent {
            SyncSettingsScreen(
                settings = sampleSettings(), // Weight is OFF
                sourceAvailable = true,
                syncedCounts = emptyMap(),
                onToggleSource = {},
                onToggleType = { type, on -> toggled = type to on },
                onRequestTypePermission = { requested += it },
                onSetInterval = {},
                onSetBackgroundReads = {},
                onSetBatteryWhitelist = {},
                onSetHistoryWindow = {},
                onResetCursors = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithText("Weight").performClick()

        assertEquals(1, requested.size)
        assertTrue(HealthConnectPermissions.forType(HcType.WEIGHT) in requested.first())
        // Toggling ON must NOT persist directly — the Route owns that after grant.
        assertNull(toggled)
    }

    @Test
    fun toggling_enabled_type_off_persists_directly() {
        val requested = mutableListOf<Set<String>>()
        var toggled: Pair<HcType, Boolean>? = null

        composeRule.setContent {
            SyncSettingsScreen(
                settings = sampleSettings(), // Heart Rate is ON
                sourceAvailable = true,
                syncedCounts = emptyMap(),
                onToggleSource = {},
                onToggleType = { type, on -> toggled = type to on },
                onRequestTypePermission = { requested += it },
                onSetInterval = {},
                onSetBackgroundReads = {},
                onSetBatteryWhitelist = {},
                onSetHistoryWindow = {},
                onResetCursors = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithText("Heart Rate").performClick()

        assertEquals(HcType.HEART_RATE to false, toggled)
        // Disabling does not need a permission request.
        assertTrue(requested.isEmpty())
    }

    @Test
    fun selecting_interval_invokes_callback() {
        var chosen: Int? = null
        composeRule.setContent {
            SyncSettingsScreen(
                settings = sampleSettings().copy(syncIntervalMinutes = 15),
                sourceAvailable = true,
                syncedCounts = emptyMap(),
                onToggleSource = {},
                onToggleType = { _, _ -> },
                onRequestTypePermission = {},
                onSetInterval = { chosen = it },
                onSetBackgroundReads = {},
                onSetBatteryWhitelist = {},
                onSetHistoryWindow = {},
                onResetCursors = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithText("Every 30 minutes").performClick()

        assertEquals(30, chosen)
    }
}
