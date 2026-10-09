package io.healthassistant.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** R7 gate: the 3-slide welcome renders, advances via Next, skips, and finishes
 *  on the last slide. Pure state + lambdas via `setContent`. */
class WelcomeScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun first_slide_shows_and_skip_finishes() {
        var finished = false
        composeRule.setContent {
            WelcomeScreen(onFinish = { finished = true })
        }

        composeRule.onNodeWithText("Your health, in one place").assertIsDisplayed()
        composeRule.onNodeWithText("Skip").performClick()

        assertEquals(true, finished)
    }

    @Test
    fun next_advances_and_last_slide_finishes() {
        var finished = false
        composeRule.setContent {
            WelcomeScreen(onFinish = { finished = true })
        }

        composeRule.onNodeWithText("Next").performClick()
        composeRule.onNodeWithText("Your data stays yours").assertIsDisplayed()

        composeRule.onNodeWithText("Next").performClick()
        composeRule.onNodeWithText("See your trends").assertIsDisplayed()

        composeRule.onNodeWithText("Get started").performClick()

        assertEquals(true, finished)
    }
}
