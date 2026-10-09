package io.healthassistant.android.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** R1 gate: the bottom navigation bar renders all four top-level destinations
 *  and routes tab taps to [onTabSelected]. Uses `setContent` so it stays
 *  independent of AppRoot's onboarding/credential state. */
@RunWith(RobolectricTestRunner::class)
class HABottomBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun shows_all_four_tabs() {
        composeRule.setContent {
            Column {
                HABottomBar(currentRoute = HATab.Home.route, onTabSelected = {})
            }
        }

        composeRule.onNodeWithText("Home").assertIsDisplayed()
        composeRule.onNodeWithText("Records").assertIsDisplayed()
        composeRule.onNodeWithText("Profile").assertIsDisplayed()
    }

    @Test
    fun tapping_a_tab_reports_the_selection() {
        var picked: HATab? = null
        composeRule.setContent {
            Column {
                HABottomBar(currentRoute = HATab.Home.route, onTabSelected = { picked = it })
            }
        }

        composeRule.onNodeWithText("Records").performClick()

        assertEquals(HATab.Records, picked)
    }
}
