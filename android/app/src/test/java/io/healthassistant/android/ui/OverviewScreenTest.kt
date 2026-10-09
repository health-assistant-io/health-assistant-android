package io.healthassistant.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.healthassistant.android.ui.components.TimePoint
import io.healthassistant.shared.data.ChartRange
import io.healthassistant.shared.data.Normalization
import io.healthassistant.shared.data.NormalizationStrategy
import io.healthassistant.shared.data.ReferenceRange
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Monitoring-viz M6 gate: the overview screen renders the multi-series
 * chart + one legend row per picked biomarker, the single-series reference
 * range, the empty states, and the picker sheet (add → search → pick →
 * toggle), with range/scale chips firing. A picked biomarker's name renders
 * twice (selected chip + legend row), so text assertions that must not care
 * use counts and tag-scoped interactions.
 */
@RunWith(RobolectricTestRunner::class)
class OverviewScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun screen(
        state: OverviewUiState,
        onToggle: (String) -> Unit = {},
        onSelectRange: (ChartRange) -> Unit = {},
        onSelectStrategy: (NormalizationStrategy) -> Unit = {},
    ) {
        composeRule.setContent {
            OverviewScreen(
                state = state,
                onToggle = onToggle,
                onSelectRange = onSelectRange,
                onSelectStrategy = onSelectStrategy,
                onBack = {},
            )
        }
    }

    private fun series(
        code: String,
        name: String,
        unit: String?,
        vararg values: Double,
    ): OverviewSeriesUi {
        val scale = Normalization.fit(values.toList(), NormalizationStrategy.MIN_MAX)
        return OverviewSeriesUi(
            code = code,
            name = name,
            unit = unit,
            points =
                values
                    .mapIndexed { index, value ->
                        TimePoint(1_700_000_000_000L + index * 86_400_000L, scale?.apply(value)?.toFloat() ?: 0f)
                    }.toList(),
            scale = scale,
        )
    }

    private fun threeSeriesState() =
        OverviewUiState(
            loading = false,
            selection = listOf("8867-4", "29463-7", "sleep"),
            series =
                listOf(
                    series("8867-4", "Heart rate", "bpm", 60.0, 72.0, 80.0),
                    series("29463-7", "Weight", "kg", 78.0, 80.0, 82.0),
                    series("sleep", "Sleep duration", "h", 5.5, 6.5, 7.5),
                ),
        )

    private fun textCount(text: String): Int = composeRule.onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test
    fun three_fake_series_render_one_chart_and_a_legend_row_each() {
        screen(threeSeriesState())

        composeRule.onNodeWithTag("overview_chart").assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription("Chart of Heart rate, Weight, Sleep duration over last week")
            .assertIsDisplayed()
        assertEquals(3, composeRule.onAllNodesWithTag("overview_legend_row").fetchSemanticsNodes().size)
        assertEquals(3, composeRule.onAllNodesWithTag("overview_selected_chip").fetchSemanticsNodes().size)
        listOf("Heart rate", "Weight", "Sleep duration").forEach { name ->
            assertEquals("chip + legend row for $name", 2, textCount(name))
        }
        composeRule.onNodeWithText("bpm").assertIsDisplayed()
        composeRule.onNodeWithText("kg").assertIsDisplayed()
        composeRule.onNodeWithText("h").assertIsDisplayed()
        composeRule.onNodeWithTag("overview_reference_range").assertDoesNotExist()
    }

    @Test
    fun a_single_series_with_a_known_range_renders_the_reference_label() {
        screen(
            OverviewUiState(
                loading = false,
                selection = listOf("8867-4"),
                series = listOf(series("8867-4", "Heart rate", "bpm", 60.0, 80.0)),
                referenceRange = ReferenceRange(low = 60.0, high = 100.0),
                normalizedBand = 0.0..1.0,
            ),
        )

        composeRule.onNodeWithTag("overview_reference_range").assertIsDisplayed()
        composeRule.onNodeWithText("Reference range: 60–100 bpm").assertIsDisplayed()
    }

    @Test
    fun no_selection_renders_the_pick_hint_and_no_chart() {
        screen(OverviewUiState(loading = false))

        composeRule.onNodeWithText("Pick biomarkers to see them together on one chart.").assertIsDisplayed()
        composeRule.onNodeWithTag("overview_chart").assertDoesNotExist()
    }

    @Test
    fun a_series_without_readings_in_the_window_keeps_its_legend_hint() {
        val state =
            OverviewUiState(
                loading = false,
                selection = listOf("8867-4", "29463-7"),
                series =
                    listOf(
                        series("8867-4", "Heart rate", "bpm", 60.0, 80.0),
                        OverviewSeriesUi(code = "29463-7", name = "Weight", unit = "kg", points = emptyList(), scale = null),
                    ),
            )
        screen(state)

        assertEquals("chip + legend row for Weight", 2, textCount("Weight"))
        composeRule.onNodeWithText("No readings in this range. Try a wider range.").assertIsDisplayed()
    }

    @Test
    fun tapping_a_selected_chip_removes_it_via_toggle() {
        var toggled: String? = null
        screen(threeSeriesState(), onToggle = { toggled = it })

        composeRule.onAllNodesWithTag("overview_selected_chip")[0].performClick()

        assertEquals("8867-4", toggled)
    }

    @Test
    fun the_add_chip_opens_the_picker_and_a_row_pick_toggles() {
        var toggled: String? = null
        screen(
            OverviewUiState(
                loading = false,
                selection = listOf("8867-4"),
                series = listOf(series("8867-4", "Heart rate", "bpm", 60.0, 80.0)),
                options =
                    listOf(
                        OverviewPickerOption("8867-4", "Heart rate", "bpm", "2026-08-15T10:00:00Z"),
                        OverviewPickerOption("29463-7", "Weight", "kg", "2026-08-12T08:00:00Z"),
                        OverviewPickerOption("glucose", "Glucose", "mmol/L", null),
                    ),
            ),
            onToggle = { toggled = it },
        )

        composeRule.onNodeWithText("Add biomarker").performClick()
        composeRule.onNodeWithTag("overview_picker_search").assertIsDisplayed()
        composeRule.onAllNodesWithTag("overview_picker_row")[2].performClick()

        assertEquals("glucose", toggled)
    }

    @Test
    fun the_picker_search_field_filters_the_catalog() {
        screen(
            OverviewUiState(
                loading = false,
                selection = listOf("8867-4"),
                series = listOf(series("8867-4", "Heart rate", "bpm", 60.0, 80.0)),
                options =
                    listOf(
                        OverviewPickerOption("8867-4", "Heart rate", "bpm", "2026-08-15T10:00:00Z"),
                        OverviewPickerOption("29463-7", "Weight", "kg", "2026-08-12T08:00:00Z"),
                    ),
            ),
        )

        composeRule.onNodeWithText("Add biomarker").performClick()
        assertEquals(2, composeRule.onAllNodesWithTag("overview_picker_row").fetchSemanticsNodes().size)

        composeRule.onNodeWithTag("overview_picker_search").performTextInput("weigh")

        assertEquals(1, composeRule.onAllNodesWithTag("overview_picker_row").fetchSemanticsNodes().size)
        composeRule.onAllNodesWithTag("overview_picker_row")[0].assertExists()
    }

    @Test
    fun range_and_scale_chips_fire_their_callbacks() {
        var range: ChartRange? = null
        var strategy: NormalizationStrategy? = null
        screen(threeSeriesState(), onSelectRange = { range = it }, onSelectStrategy = { strategy = it })

        composeRule.onNodeWithText("Last year").performClick()
        assertEquals(ChartRange.ONE_YEAR, range)

        composeRule.onNodeWithText("Z-score").performClick()
        assertEquals(NormalizationStrategy.Z_SCORE, strategy)
    }

    @Test
    fun loading_state_renders_skeleton_not_chart() {
        screen(OverviewUiState(loading = true))

        composeRule.onNodeWithTag("chart_skeleton").assertIsDisplayed()
        composeRule.onNodeWithTag("overview_chart").assertDoesNotExist()
    }

    @Test
    fun a_full_selection_hides_the_add_chip() {
        screen(threeSeriesState())

        composeRule.onNodeWithText("Add biomarker").assertDoesNotExist()
    }
}
