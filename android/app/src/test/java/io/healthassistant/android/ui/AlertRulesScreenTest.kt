package io.healthassistant.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.healthassistant.shared.alerts.AlertOp
import io.healthassistant.shared.alerts.AlertRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M5 screen gate (pure state + lambdas): the plain-language sentence, the
 * toggle/remove affordances, and the rule-builder form + biomarker picker
 * (composed directly — the ModalBottomSheet host is a separate semantics
 * window that JVM text queries can't reliably reach in this Robolectric
 * setup, so the form is extracted as [AlertRuleEditorForm] and tested solo).
 */
@RunWith(RobolectricTestRunner::class)
class AlertRulesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val rule =
        AlertRule(
            id = "r1",
            biomarkerCode = "8867-4",
            biomarkerName = "Heart rate",
            op = AlertOp.GT,
            threshold = 120.0,
            timeWindowSec = 300,
        )

    private fun screen(
        state: AlertRulesUiState,
        onToggle: (String, Boolean) -> Unit = { _, _ -> },
        onRequestDelete: (AlertRule) -> Unit = { _ -> },
    ) {
        composeRule.setContent {
            AlertRulesScreen(
                state = state,
                onBack = {},
                onAdd = {},
                onToggle = onToggle,
                onRequestDelete = onRequestDelete,
                onConfirmDelete = {},
                onCancelDelete = {},
                onDismissEditor = {},
                onPickBiomarker = {},
                onShowBiomarkerPicker = {},
                onSetOp = {},
                onSetThreshold = {},
                onSetRangeLow = {},
                onSetRangeHigh = {},
                onSetWindowMinutes = {},
                onSave = {},
                canSave = false,
            )
        }
    }

    @Test
    fun empty_state_shows_the_hint_and_the_add_button() {
        screen(AlertRulesUiState())

        composeRule.onNodeWithText("No alerts yet. Add one and we'll watch your readings for you.").assertIsDisplayed()
        composeRule.onNodeWithTag("alerts_add_fab").assertIsDisplayed()
    }

    @Test
    fun the_add_fab_hides_while_the_editor_is_open() {
        screen(AlertRulesUiState(editor = AlertEditorState(code = "8867-4")))

        composeRule.onNodeWithTag("alerts_add_fab").assertDoesNotExist()
    }

    @Test
    fun a_saved_rule_renders_as_a_plain_language_sentence() {
        screen(AlertRulesUiState(rules = listOf(rule)))

        composeRule
            .onNodeWithText("Alert me when Heart rate is above 120 for 5 minutes")
            .assertIsDisplayed()
    }

    @Test
    fun a_paused_rule_says_so() {
        screen(AlertRulesUiState(rules = listOf(rule.copy(enabled = false))))

        composeRule.onNodeWithText("Paused").assertIsDisplayed()
    }

    @Test
    fun toggling_the_switch_fires_with_the_id() {
        var toggled: Pair<String, Boolean>? = null
        screen(AlertRulesUiState(rules = listOf(rule)), onToggle = { id, on -> toggled = id to on })

        composeRule.onNodeWithTag("alert_toggle_r1").performClick()

        assertEquals("r1" to false, toggled)
    }

    @Test
    fun the_remove_icon_fires_the_delete_request() {
        var requested: AlertRule? = null
        screen(AlertRulesUiState(rules = listOf(rule)), onRequestDelete = { requested = it })

        composeRule.onNodeWithContentDescription("Remove").performClick()

        assertEquals(rule, requested)
    }

    @Test
    fun the_picker_lists_options_and_picking_fires() {
        var picked: AlertBiomarkerOption? = null
        composeRule.setContent {
            BiomarkerPicker(
                options =
                    listOf(
                        AlertBiomarkerOption("8867-4", "Heart Rate", "bpm"),
                        AlertBiomarkerOption("59408-5", "Oxygen Saturation", "%"),
                    ),
                onPick = { picked = it },
            )
        }

        composeRule.onNodeWithText("What should we watch?").assertIsDisplayed()
        composeRule.onNodeWithText("Search biomarkers").assertIsDisplayed()
        composeRule.onNodeWithText("Heart Rate").performClick()
        assertEquals("8867-4", picked?.code)
    }

    @Test
    fun the_picker_search_narrows_the_list() {
        composeRule.setContent {
            BiomarkerPicker(
                options =
                    listOf(
                        AlertBiomarkerOption("8867-4", "Heart Rate", "bpm"),
                        AlertBiomarkerOption("59408-5", "Oxygen Saturation", "%"),
                    ),
                onPick = {},
            )
        }

        composeRule.onNodeWithText("Search biomarkers").performTextInput("oxygen")

        composeRule.onNodeWithText("Heart Rate").assertDoesNotExist()
        composeRule.onNodeWithText("Oxygen Saturation").assertIsDisplayed()
    }

    private fun form(
        editor: AlertEditorState,
        onSetOp: (AlertOp) -> Unit = {},
        onSetThreshold: (String) -> Unit = {},
        onSetWindowMinutes: (Long) -> Unit = {},
        onSave: () -> Unit = {},
        onDismiss: () -> Unit = {},
        canSave: Boolean = false,
    ) {
        composeRule.setContent {
            AlertRuleEditorForm(
                editor = editor,
                onDismiss = onDismiss,
                onShowBiomarkerPicker = {},
                onSetOp = onSetOp,
                onSetThreshold = onSetThreshold,
                onSetRangeLow = {},
                onSetRangeHigh = {},
                onSetWindowMinutes = onSetWindowMinutes,
                onSave = onSave,
                canSave = canSave,
            )
        }
    }

    @Test
    fun the_form_previews_the_sentence_live() {
        form(AlertEditorState(code = "8867-4", name = "Heart rate", unit = "bpm", thresholdText = "120", windowMinutes = 5))

        composeRule.onNodeWithText("New alert").assertIsDisplayed()
        composeRule.onNodeWithText("Alert me when Heart rate is above 120 for 5 minutes").assertIsDisplayed()
    }

    @Test
    fun the_pending_value_renders_before_one_is_entered() {
        form(AlertEditorState(code = "8867-4", name = "Heart rate"))

        composeRule.onNodeWithText("Alert me when Heart rate is above …").assertIsDisplayed()
    }

    @Test
    fun out_of_range_direction_swaps_in_the_two_bound_fields() {
        form(
            AlertEditorState(
                code = "8867-4",
                name = "Heart rate",
                op = AlertOp.OUT_OF_RANGE,
                rangeLowText = "60",
                rangeHighText = "100",
            ),
        )

        composeRule.onNodeWithText("Alert me when Heart rate is outside 60–100").assertIsDisplayed()
        composeRule.onNodeWithText("Bottom of range").assertIsDisplayed()
        composeRule.onNodeWithText("Top of range").assertIsDisplayed()
    }

    @Test
    fun direction_and_duration_chips_fire_their_callbacks() {
        var op: AlertOp? = null
        var minutes: Long? = null
        form(
            AlertEditorState(code = "8867-4", name = "Heart rate", thresholdText = "120"),
            onSetOp = { op = it },
            onSetWindowMinutes = { minutes = it },
            canSave = true,
        )

        composeRule.onNodeWithText("Below").performClick()
        composeRule.onNodeWithText("Right away").performClick()
        composeRule.onNodeWithText("1 hour").assertIsDisplayed()

        assertEquals(AlertOp.LT, op)
        assertEquals(0L, minutes)
    }

    @Test
    fun threshold_typing_and_save_fire_the_builder_callbacks() {
        var threshold: String? = null
        var saved = false
        form(
            AlertEditorState(code = "8867-4", name = "Heart rate", unit = "bpm"),
            onSetThreshold = { threshold = it },
            onSave = { saved = true },
            canSave = false,
        )

        composeRule.onNodeWithText("Value (bpm)").performTextInput("130")
        assertEquals("130", threshold)

        composeRule.onNodeWithText("Save alert").performClick()
        assertFalse("save must be disabled while canSave is false", saved)
    }

    @Test
    fun a_one_hour_window_renders_as_one_hour_not_sixty_minutes() {
        form(AlertEditorState(code = "8867-4", name = "Heart rate", thresholdText = "120", windowMinutes = 60))

        composeRule.onNodeWithText("Alert me when Heart rate is above 120 for 1 hour").assertIsDisplayed()
        composeRule.onNodeWithText("for 60 minutes").assertDoesNotExist()
    }
}
