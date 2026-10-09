package io.healthassistant.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.healthassistant.android.settings.UiMode
import io.healthassistant.android.settings.UiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Hub gate: the Profile tab renders the connection card + one tappable row
 *  per detail page, and each row fires its navigation intent. Pure state +
 *  lambdas via `setContent`. */
@RunWith(RobolectricTestRunner::class)
class ProfileScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun screen(
        onOpenSyncSettings: () -> Unit = {},
        onOpenAlerts: (() -> Unit)? = {},
        onOpenServerNotifications: () -> Unit = {},
        onOpenDeviceNotifications: () -> Unit = {},
        onOpenAccessibility: () -> Unit = {},
        onOpenPrivacy: () -> Unit = {},
        onOpenAbout: () -> Unit = {},
        onOpenSync: () -> Unit = {},
        onOpenWebApp: (() -> Unit)? = {},
    ) {
        composeRule.setContent {
            ProfileScreen(
                connectionLabel = "https://health.example",
                connectionId = "11111111-2222-3333-4444-555555555555",
                onSwitchConnection = null,
                onOpenWebApp = onOpenWebApp,
                onOpenSync = onOpenSync,
                onOpenSyncSettings = onOpenSyncSettings,
                onOpenDataStorage = {},
                onOpenAlerts = onOpenAlerts,
                onOpenServerNotifications = onOpenServerNotifications,
                onOpenDeviceNotifications = onOpenDeviceNotifications,
                onOpenAccessibility = onOpenAccessibility,
                onOpenPrivacy = onOpenPrivacy,
                onOpenAbout = onOpenAbout,
                onDisconnect = {},
            )
        }
    }

    @Test
    fun connection_card_shows_server_and_connection_id() {
        screen()

        composeRule.onNodeWithText("Server: https://health.example").assertIsDisplayed()
        composeRule.onNodeWithText("Connection: 11111111-2222-3333-4444-555555555555").assertIsDisplayed()
    }

    @Test
    fun every_row_fires_its_navigation_intent() {
        var syncSettings = 0
        var serverNotifications = 0
        var deviceNotifications = 0
        var accessibility = 0
        var privacy = 0
        var about = 0
        screen(
            onOpenSyncSettings = { syncSettings++ },
            onOpenServerNotifications = { serverNotifications++ },
            onOpenDeviceNotifications = { deviceNotifications++ },
            onOpenAccessibility = { accessibility++ },
            onOpenPrivacy = { privacy++ },
            onOpenAbout = { about++ },
        )

        composeRule.onNodeWithText("Sync settings").performScrollTo().performClick()
        composeRule.onNodeWithText("Server notifications").performScrollTo().performClick()
        composeRule.onNodeWithText("Notifications").performScrollTo().performClick()
        composeRule.onNodeWithText("Accessibility").performScrollTo().performClick()
        composeRule.onNodeWithText("Privacy & data").performScrollTo().performClick()
        composeRule.onNodeWithText("About").performScrollTo().performClick()

        assertEquals(1, syncSettings)
        assertEquals(1, serverNotifications)
        assertEquals(1, deviceNotifications)
        assertEquals(1, accessibility)
        assertEquals(1, privacy)
        assertEquals(1, about)
    }

    @Test
    fun readings_and_sync_row_navigates() {
        var opened = false
        screen(onOpenSync = { opened = true })

        composeRule.onNodeWithText("Readings & sync").performScrollTo().performClick()

        assertTrue(opened)
    }

    @Test
    fun web_app_row_fires_hand_off() {
        var opened = false
        screen(onOpenWebApp = { opened = true })

        composeRule.onNodeWithText("Open web dashboard").performScrollTo().performClick()

        assertTrue(opened)
    }

    @Test
    fun web_app_row_hidden_without_callback() {
        screen(onOpenWebApp = null)

        composeRule.onNodeWithText("Open web dashboard").assertDoesNotExist()
    }

    @Test
    fun alerts_row_navigates() {
        var opened = false
        screen(onOpenAlerts = { opened = true })

        composeRule.onNodeWithText("Alerts").performScrollTo().performClick()
        assertTrue(opened)
    }

    @Test
    fun alerts_row_hidden_without_callback() {
        screen(onOpenAlerts = null)

        composeRule.onNodeWithText("Alerts").assertDoesNotExist()
    }

    @Test
    fun server_notifications_row_hidden_without_connection() {
        composeRule.setContent {
            ProfileScreen(
                connectionLabel = null,
                connectionId = null,
                onSwitchConnection = null,
                onOpenSync = {},
                onOpenSyncSettings = {},
                onOpenDataStorage = {},
                onOpenServerNotifications = null,
                onOpenDeviceNotifications = {},
                onOpenAccessibility = {},
                onOpenPrivacy = {},
                onOpenAbout = {},
                onDisconnect = null,
            )
        }

        composeRule.onNodeWithText("Server notifications").assertDoesNotExist()
    }

    @Test
    fun mode_row_opens_a_dropdown_with_both_options() {
        screen()

        composeRule.onNodeWithText("How do you want to use the app?").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("How do you want to use the app?").performScrollTo().performClick()
        composeRule.onNodeWithText("Your daily readings, records and connection — big, calm screens.").assertIsDisplayed()
        composeRule
            .onNodeWithText("Everything, including the doctors directory, server notifications and dashboard editing.")
            .assertIsDisplayed()
    }

    @Test
    fun tapping_a_mode_option_fires_the_switch() {
        var picked: UiMode? = null
        composeRule.setContent {
            ProfileScreen(
                connectionLabel = "https://health.example",
                connectionId = "11111111-2222-3333-4444-555555555555",
                onSwitchConnection = null,
                onOpenWebApp = null,
                onOpenSync = {},
                onOpenSyncSettings = {},
                onOpenDataStorage = null,
                onOpenServerNotifications = null,
                onOpenDeviceNotifications = {},
                onOpenAccessibility = {},
                onOpenPrivacy = {},
                onOpenAbout = {},
                onDisconnect = {},
                mode = UiMode.SIMPLE,
                onSetMode = { picked = it },
            )
        }

        composeRule.onNodeWithText("How do you want to use the app?").performScrollTo().performClick()
        composeRule.onNodeWithText("Advanced (all features)").performClick()

        assertEquals(UiMode.ADVANCED, picked)
    }

    @Test
    fun appearance_row_opens_a_dropdown_and_picking_a_theme_fires() {
        var picked: UiTheme? = null
        composeRule.setContent {
            ProfileScreen(
                connectionLabel = null,
                connectionId = null,
                onSwitchConnection = null,
                onOpenWebApp = null,
                onOpenSync = {},
                onOpenSyncSettings = {},
                onOpenDataStorage = null,
                onOpenServerNotifications = null,
                onOpenDeviceNotifications = {},
                onOpenAccessibility = {},
                onOpenPrivacy = {},
                onOpenAbout = {},
                onDisconnect = null,
                theme = UiTheme.AURORA,
                onSetTheme = { picked = it },
            )
        }

        composeRule.onNodeWithText("Appearance").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Appearance").performScrollTo().performClick()
        composeRule.onNodeWithText("Classic teal").performClick()

        assertEquals(UiTheme.TEAL, picked)
    }

    @Test
    fun current_mode_option_is_selected() {
        composeRule.setContent {
            ProfileScreen(
                connectionLabel = null,
                connectionId = null,
                onSwitchConnection = null,
                onOpenSync = {},
                onOpenSyncSettings = {},
                onOpenDataStorage = null,
                onOpenServerNotifications = null,
                onOpenDeviceNotifications = {},
                onOpenAccessibility = {},
                onOpenPrivacy = {},
                onOpenAbout = {},
                onDisconnect = null,
                mode = UiMode.SIMPLE,
            )
        }

        composeRule.onNodeWithText("How do you want to use the app?").performScrollTo().performClick()
        composeRule.onNodeWithText("Your daily readings, records and connection — big, calm screens.").assertIsDisplayed()
    }
}
