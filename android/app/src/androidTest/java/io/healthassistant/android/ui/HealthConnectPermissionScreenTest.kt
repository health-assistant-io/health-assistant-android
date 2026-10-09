package io.healthassistant.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** R7 gate: the post-connect Health Connect guided step renders the plain
 *  explanation and routes Grant / Not now to the callbacks. Pure state. */
class HealthConnectPermissionScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun renders_explanation_and_grant_routes() {
        var granted = false
        var skipped = false
        composeRule.setContent {
            HealthConnectPermissionScreen(onGrant = { granted = true }, onSkip = { skipped = true })
        }

        composeRule.onNodeWithText("Sync from Health Connect?").assertIsDisplayed()
        composeRule.onNodeWithText("Your readings are only ever sent to your own server.").assertIsDisplayed()

        composeRule.onNodeWithText("Allow Health Connect").performClick()
        assertEquals(true, granted)
        assertEquals(false, skipped)
    }

    @Test
    fun not_now_skips() {
        var skipped = false
        composeRule.setContent {
            HealthConnectPermissionScreen(onGrant = {}, onSkip = { skipped = true })
        }

        composeRule.onNodeWithText("Not now").performClick()

        assertEquals(true, skipped)
    }
}
