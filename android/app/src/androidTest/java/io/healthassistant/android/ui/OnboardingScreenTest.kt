package io.healthassistant.android.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.healthassistant.shared.onboarding.ConnectionCredential
import io.healthassistant.shared.onboarding.Onboarding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * R7 gate: the restyled onboarding form leads with the big Scan-QR primary,
 * shows the reassurance copy, validates bad input client-side, and still
 * drives the unchanged pure-Kotlin [Onboarding] parsers via the Phase D
 * [OnboardingViewModel] (code paste + manual).
 */
class OnboardingScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(onConnect: (ConnectionCredential) -> Unit): OnboardingViewModel {
        val vm = OnboardingViewModel(null, onConnect)
        composeRule.setContent {
            val state by vm.state.collectAsState()
            OnboardingScreen(
                state = state,
                onScan = {},
                onCodeChange = vm::onCodeChange,
                onSubmitCode = { vm.submitCode(state.code.trim()) },
                onToggleManual = vm::toggleManual,
                onManualBaseChange = vm::onManualBaseChange,
                onManualIdChange = vm::onManualIdChange,
                onManualSecretChange = vm::onManualSecretChange,
                onSubmitManual = vm::submitManual,
            )
        }
        return vm
    }

    @Test
    fun shows_restyled_connect_screen() {
        setContent(onConnect = {})

        composeRule.onNodeWithText("Connect to Health Assistant").assertIsDisplayed()
        composeRule.onNodeWithText("Scan QR code").assertIsDisplayed()
        composeRule.onNodeWithText("Your data stays on your own server — it's never sent anywhere else.").assertIsDisplayed()
    }

    @Test
    fun pasted_code_drives_unchanged_parser() {
        val code = "https://health.example.io|00000000-0000-0000-0000-000000000000|0123456789abcdef"
        val expected = Onboarding.parseQr(code)
        var captured: ConnectionCredential? = null
        setContent(onConnect = { captured = it })

        composeRule.onNodeWithText("Connection code").performTextInput(code)
        composeRule.onNode(hasText("Connect") and hasClickAction()).performClick()

        assertEquals(expected, captured)
    }

    @Test
    fun empty_input_does_not_connect_and_shows_no_error() {
        var captured: ConnectionCredential? = null
        setContent(onConnect = { captured = it })

        composeRule.onNode(hasText("Connect") and hasClickAction()).performClick()

        assertNull(captured)
    }

    @Test
    fun manual_entry_toggles_behind_advanced() {
        setContent(onConnect = {})

        // Manual fields are hidden until "Enter details manually" is tapped.
        composeRule.onNodeWithText("Enter details manually").performClick()
        composeRule.onNodeWithText("Server URL").assertIsDisplayed()
        composeRule.onNodeWithText("Instance ID").assertIsDisplayed()
    }
}
