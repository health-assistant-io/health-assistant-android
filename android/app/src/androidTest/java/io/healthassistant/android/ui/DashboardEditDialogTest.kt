package io.healthassistant.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.healthassistant.shared.data.BiomarkerOption
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Dashboard editor: the catalog lists every card with an Add/Remove button;
 *  adding/removing and reordering route to the callbacks. */
class DashboardEditDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun options() =
        listOf(
            BiomarkerOption(id = "b1", name = "Heart Rate", code = "8867-4", unit = "bpm", latestValue = 72.0, latestUnit = "bpm"),
            BiomarkerOption(id = "b2", name = "Fasting Glucose", code = "2345-7", unit = "mmol/L"),
            BiomarkerOption(id = "b3", name = "Cholesterol", code = "cholesterol-total", unit = "mmol/L"),
        )

    @Test
    fun shows_all_options_with_latest_values_and_add_for_not_shown() {
        composeRule.setContent {
            DashboardEditDialog(
                options = options(),
                shownCodes = setOf("8867-4"),
                order = listOf("8867-4"),
                onToggleShown = { _, _ -> },
                onMove = { _, _ -> },
                onReset = {},
                onDismiss = {},
            )
        }

        composeRule.onNodeWithText("Edit dashboard").assertIsDisplayed()
        composeRule.onNodeWithText("Heart Rate").assertIsDisplayed()
        composeRule.onNodeWithText("72 bpm").assertIsDisplayed()
        composeRule.onNodeWithText("Fasting Glucose").assertIsDisplayed()
        // Heart Rate is shown → Remove; the others offer Add.
        composeRule.onNodeWithText("Remove").assertIsDisplayed()
        composeRule.onNodeWithText("Add").assertIsDisplayed()
    }

    @Test
    fun add_button_routes_callback() {
        var toggle: Pair<String, Boolean>? = null
        composeRule.setContent {
            DashboardEditDialog(
                options = options(),
                shownCodes = emptySet(),
                order = emptyList(),
                onToggleShown = { code, shown -> toggle = code to shown },
                onMove = { _, _ -> },
                onReset = {},
                onDismiss = {},
            )
        }

        composeRule.onNodeWithText("Fasting Glucose").assertIsDisplayed()
        composeRule.onAllNodesWithText("Add")[0].performClick()

        assertEquals("8867-4" to true, toggle)
    }

    @Test
    fun move_up_routes_callback() {
        var move: Pair<String, Int>? = null
        composeRule.setContent {
            DashboardEditDialog(
                options = options(),
                shownCodes = setOf("8867-4", "2345-7", "cholesterol-total"),
                order = listOf("8867-4", "2345-7", "cholesterol-total"),
                onToggleShown = { _, _ -> },
                onMove = { code, d -> move = code to d },
                onReset = {},
                onDismiss = {},
            )
        }

        // Second row (Fasting Glucose) — the first row's Move up is disabled.
        composeRule.onAllNodesWithContentDescription("Move up")[1].performClick()

        assertEquals("2345-7" to -1, move)
    }
}
