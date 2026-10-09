package io.healthassistant.android.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextStyle
import io.healthassistant.android.ui.theme.HATheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** M9 animation gate: the animated value text settles on the new value after
 *  a change, both with the default (animated) path and with reduce-motion
 *  forced on (instant swap). */
@RunWith(RobolectricTestRunner::class)
class AnimatedValueTextTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun renders_the_current_value_and_settles_on_the_new_one() {
        var value by mutableStateOf("72")
        composeRule.setContent {
            HATheme {
                AnimatedValueText(
                    text = value,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text("probe", style = TextStyle())
            }
        }

        composeRule.onNodeWithText("72").assertIsDisplayed()
        composeRule.runOnUiThread { value = "75" }
        composeRule.onNodeWithText("75").assertIsDisplayed()
    }

    @Test
    fun reduce_motion_swaps_values_instantly() {
        var value by mutableStateOf("58")
        composeRule.setContent {
            HATheme(reduceMotion = true) {
                AnimatedValueText(
                    text = value,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        composeRule.onNodeWithText("58").assertIsDisplayed()
        composeRule.runOnUiThread { value = "142" }
        composeRule.onNodeWithText("142").assertIsDisplayed()
    }
}
