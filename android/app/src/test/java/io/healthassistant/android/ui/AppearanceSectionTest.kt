package io.healthassistant.android.ui

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.healthassistant.android.settings.UiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Appearance section (Profile): preset pick renders + selects instantly. */
@RunWith(AndroidJUnit4::class)
class AppearanceSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun renders_all_three_presets_with_the_current_one_selected() {
        composeRule.setContent {
            AppearanceSection(theme = UiTheme.AURORA, onSelect = {})
        }

        composeRule.onNodeWithText("Aurora").assertIsSelected()
        composeRule.onNodeWithText("Classic teal").assertExists()
        composeRule.onNodeWithText("Material You").assertExists()
    }

    @Test
    fun tapping_a_preset_selects_it() {
        var picked: UiTheme? = null
        composeRule.setContent {
            AppearanceSection(theme = UiTheme.TEAL, onSelect = { picked = it })
        }

        composeRule.onNodeWithText("Material You").performClick()
        assertEquals(UiTheme.MATERIAL_YOU, picked)
    }
}
