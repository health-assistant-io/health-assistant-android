package io.healthassistant.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.healthassistant.android.data.ServerReachability
import io.healthassistant.android.monitoring.SourceState
import io.healthassistant.android.monitoring.SyncMonitor
import io.healthassistant.android.monitoring.SyncStatus
import io.healthassistant.android.settings.UiMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * K.3 gate (re-scoped to the 3-tab shell): SIMPLE keeps the essentials and
 * hides the advanced surfaces — Records drops the Doctors directory row,
 * Profile keeps connection / mode / app-lock / about only, and Home drops
 * the dashboard editor + view-style menu. Pure state + lambdas via
 * `setContent`; the AppRoot wiring passes null/simpleMode for the same
 * shapes asserted here.
 */
@RunWith(RobolectricTestRunner::class)
class SimpleModeGatingTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun records_simple_mode_keeps_clinical_rows_and_hides_doctors() {
        composeRule.setContent {
            RecordsScreen(onOpenDoctors = {}, simpleMode = true)
        }

        composeRule.onNodeWithText("Biomarkers").assertIsDisplayed()
        composeRule.onNodeWithText("Examinations").assertIsDisplayed()
        composeRule.onNodeWithText("Medications").assertIsDisplayed()
        composeRule.onNodeWithText("Allergies").assertIsDisplayed()
        composeRule.onNodeWithText("Vaccines").assertIsDisplayed()
        composeRule.onNodeWithText("Clinical events").assertIsDisplayed()
        composeRule.onNodeWithText("Doctors").assertDoesNotExist()
    }

    @Test
    fun records_advanced_mode_shows_doctors() {
        composeRule.setContent {
            RecordsScreen(onOpenDoctors = {})
        }

        composeRule.onNodeWithText("Doctors").assertIsDisplayed()
    }

    @Test
    fun profile_simple_mode_keeps_connection_mode_lock_about_and_hides_advanced() {
        composeRule.setContent {
            ProfileScreen(
                connectionLabel = "https://health.example",
                connectionId = "11111111-2222-3333-4444-555555555555",
                onSwitchConnection = null,
                onOpenWebApp = null,
                onOpenSync = null,
                onOpenSyncSettings = null,
                onOpenDataStorage = null,
                onOpenServerNotifications = null,
                onOpenDeviceNotifications = null,
                onOpenAccessibility = null,
                onOpenPrivacy = {},
                onOpenAbout = {},
                onDisconnect = {},
                mode = UiMode.SIMPLE,
            )
        }

        composeRule.onNodeWithText("Server: https://health.example").assertIsDisplayed()
        composeRule.onNodeWithText("How do you want to use the app?").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Privacy & data").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("About").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("Open web dashboard").assertDoesNotExist()
        composeRule.onNodeWithText("Readings & sync").assertDoesNotExist()
        composeRule.onNodeWithText("Sync settings").assertDoesNotExist()
        composeRule.onNodeWithText("Data & storage").assertDoesNotExist()
        composeRule.onNodeWithText("Server notifications").assertDoesNotExist()
        composeRule.onNodeWithText("Notifications").assertDoesNotExist()
        composeRule.onNodeWithText("Accessibility").assertDoesNotExist()
    }

    @Test
    fun home_simple_mode_hides_dashboard_editor_and_view_style_menu() {
        composeRule.setContent {
            HomeScreen(
                monitor = homeMonitor(),
                readings = emptyList(),
                connectionLabel = null,
                reachability = ServerReachability.Online,
                onSyncNow = {},
                onOpenEdit = null,
                onCycleViewStyle = null,
            )
        }

        composeRule.onNodeWithContentDescription("Edit dashboard").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Dashboard view").assertDoesNotExist()
    }

    @Test
    fun biomarkers_advanced_mode_shows_and_opens_the_overview_entry() {
        var opened = false
        composeRule.setContent {
            BiomarkersScreen(
                state = biomarkersState(),
                onQueryChange = {},
                onToggleShowAll = {},
                onOpenBiomarker = {},
                showOverview = true,
                onOpenOverview = { opened = true },
                onBack = {},
            )
        }

        composeRule.onNodeWithText("Overview").assertIsDisplayed()
        composeRule.onNodeWithText("Compare up to 3 biomarkers on one chart").assertIsDisplayed()
        composeRule.onNodeWithTag("biomarkers_overview_entry").performClick()
        assertTrue(opened)
    }

    @Test
    fun biomarkers_simple_mode_hides_the_overview_entry() {
        composeRule.setContent {
            BiomarkersScreen(
                state = biomarkersState(),
                onQueryChange = {},
                onToggleShowAll = {},
                onOpenBiomarker = {},
                showOverview = false,
                onOpenOverview = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithTag("biomarkers_overview_entry").assertDoesNotExist()
    }

    private fun biomarkersState() =
        BiomarkersUiState(
            loading = false,
            cards =
                listOf(
                    BiomarkerCard(
                        option =
                            io.healthassistant.shared.data.BiomarkerOption(
                                id = "b1",
                                name = "Heart Rate",
                                code = "8867-4",
                                unit = "bpm",
                                latestValue = 62.0,
                                latestUnit = "bpm",
                                latestTimestamp = "2026-08-15T10:00:00Z",
                            ),
                        hasData = true,
                        trend = null,
                    ),
                ),
        )

    @Test
    fun home_view_style_helper_forces_large_print_style_in_simple_mode() {
        assertEquals(
            io.healthassistant.shared.data.HomeViewStyle.SIMPLE,
            effectiveHomeViewStyle(io.healthassistant.shared.data.HomeViewStyle.GRID, simpleMode = true),
        )
        assertEquals(
            io.healthassistant.shared.data.HomeViewStyle.GRID,
            effectiveHomeViewStyle(io.healthassistant.shared.data.HomeViewStyle.GRID, simpleMode = false),
        )
        assertEquals(
            io.healthassistant.shared.data.HomeViewStyle.SIMPLE,
            effectiveHomeViewStyle(io.healthassistant.shared.data.HomeViewStyle.SIMPLE, simpleMode = true),
        )
    }

    private fun homeMonitor(): SyncMonitor =
        SyncMonitor(
            sources =
                listOf(
                    SourceState(
                        sourceId = "health_connect",
                        displayName = "Health Connect",
                        available = true,
                        permissionsGranted = true,
                        totalSyncedPerType = emptyMap(),
                        lastSyncStatus = SyncStatus.Success(0),
                    ),
                ),
            outboxDeadLettered = 0,
        )
}
