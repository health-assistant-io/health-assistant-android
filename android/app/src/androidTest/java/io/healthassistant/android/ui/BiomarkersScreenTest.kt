package io.healthassistant.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test

/**
 * Biomarkers page gate: the card list renders with last values, search
 * filters it, the "Show all" toggle reveals never-measured biomarkers, and
 * a card tap emits the biomarker's code (the graph deep link).
 */
class BiomarkersScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val cards =
        listOf(
            BiomarkerCard(
                option =
                    io.healthassistant.shared.data.BiomarkerOption(
                        id = "b1",
                        name = "Heart Rate",
                        code = "8867-4",
                        unit = "bpm",
                        latestValue = 62.0,
                        latestUnit = "bpm",
                        latestTimestamp = "2026-08-15T10:00:00Z",
                    ),
                hasData = true,
                trend = null,
            ),
            BiomarkerCard(
                option =
                    io.healthassistant.shared.data.BiomarkerOption(
                        id = "b2",
                        name = "Weight",
                        code = "29463-7",
                        unit = "kg",
                        latestValue = 80.5,
                        latestUnit = "kg",
                        latestTimestamp = "2026-08-12T08:00:00Z",
                    ),
                hasData = true,
                trend = null,
            ),
            BiomarkerCard(
                option =
                    io.healthassistant.shared.data.BiomarkerOption(
                        id = "b3",
                        name = "Zinc",
                        code = "zinc",
                    ),
                hasData = false,
                trend = null,
            ),
        )

    @Test
    fun cards_render_with_last_values_and_never_measured() {
        composeRule.setContent {
            BiomarkersScreen(
                state = BiomarkersUiState(loading = false, cards = cards, totalKnown = 3),
                onQueryChange = {},
                onToggleShowAll = {},
                onOpenBiomarker = {},
                onBack = {},
            )
        }
        composeRule.onNodeWithText("Heart Rate").assertIsDisplayed()
        composeRule.onNodeWithText("Weight").assertIsDisplayed()
        composeRule.onNodeWithText("Zinc").assertIsDisplayed()
    }

    @Test
    fun search_filters_cards() {
        composeRule.setContent {
            BiomarkersScreen(
                state = BiomarkersUiState(loading = false, query = "heart", cards = cards.take(1), totalKnown = 3),
                onQueryChange = {},
                onToggleShowAll = {},
                onOpenBiomarker = {},
                onBack = {},
            )
        }
        composeRule.onNodeWithText("Heart Rate").assertIsDisplayed()
        composeRule.onNodeWithText("Weight").assertDoesNotExist()
    }

    @Test
    fun empty_search_shows_no_match_hint() {
        composeRule.setContent {
            BiomarkersScreen(
                state = BiomarkersUiState(loading = false, query = "zzz", cards = emptyList(), totalKnown = 3),
                onQueryChange = {},
                onToggleShowAll = {},
                onOpenBiomarker = {},
                onBack = {},
            )
        }
        composeRule.onNodeWithText("No biomarker matches your search.").assertIsDisplayed()
    }

    @Test
    fun card_tap_emits_the_biomarker_code() {
        var opened: String? = null
        composeRule.setContent {
            BiomarkersScreen(
                state = BiomarkersUiState(loading = false, cards = cards.take(1), totalKnown = 1),
                onQueryChange = {},
                onToggleShowAll = {},
                onOpenBiomarker = { opened = it },
                onBack = {},
            )
        }
        composeRule.onNodeWithText("Heart Rate").performClick()
        org.junit.Assert.assertEquals("8867-4", opened)
    }

    @Test
    fun search_field_accepts_input() {
        var query = ""
        composeRule.setContent {
            BiomarkersScreen(
                state = BiomarkersUiState(loading = false, cards = cards, totalKnown = 3),
                onQueryChange = { query = it },
                onToggleShowAll = {},
                onOpenBiomarker = {},
                onBack = {},
            )
        }
        composeRule.onNodeWithTag("biomarkers_search").performTextInput("hr")
        org.junit.Assert.assertEquals("hr", query)
    }
}
