package io.healthassistant.android.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.healthassistant.android.settings.UiMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * K.4 gate: the first-run wizard leads with the "How do you want to use the
 * app?" pick — both options render, the default is marked selected, and a
 * tap updates the VM state + fires the persist intent. Pure state + lambdas
 * via `setContent` (the VM drives it, matching the Phase D pattern).
 */
@RunWith(RobolectricTestRunner::class)
class OnboardingModePickTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(initialMode: UiMode = UiMode.SIMPLE): OnboardingViewModel {
        val vm = OnboardingViewModel(null, {}, initialMode)
        composeRule.setContent {
            val state by vm.state.collectAsState()
            OnboardingScreen(
                state = state,
                onScan = {},
                onCodeChange = vm::onCodeChange,
                onSubmitCode = {},
                onToggleManual = vm::toggleManual,
                onManualBaseChange = vm::onManualBaseChange,
                onManualIdChange = vm::onManualIdChange,
                onManualSecretChange = vm::onManualSecretChange,
                onSubmitManual = vm::submitManual,
                onModeChange = vm::onModeChange,
            )
        }
        return vm
    }

    @Test
    fun wizard_shows_the_mode_pick_with_both_options() {
        setContent()

        composeRule.onNodeWithText("How do you want to use the app?").assertIsDisplayed()
        composeRule.onNodeWithText("Simple (just the essentials)").assertIsDisplayed()
        composeRule.onNodeWithText("Advanced (all features)").assertIsDisplayed()
    }

    @Test
    fun default_pick_is_simple() {
        setContent()

        composeRule.onNodeWithText("Simple (just the essentials)").assertIsSelected()
    }

    @Test
    fun picking_advanced_updates_state_and_marks_the_option() {
        val vm = setContent()

        composeRule.onNodeWithText("Advanced (all features)").performClick()

        assertEquals(UiMode.ADVANCED, vm.state.value.mode)
        composeRule.onNodeWithText("Advanced (all features)").assertIsSelected()
    }
}
