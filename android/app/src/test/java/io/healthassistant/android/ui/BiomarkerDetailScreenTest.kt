package io.healthassistant.android.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import io.healthassistant.shared.data.ChartRange
import io.healthassistant.shared.data.ObservationPoint
import io.healthassistant.shared.data.ReferenceRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Chart-screen gate: states render, the latest card promotes the newest
 *  record, range chips fire, and the interpretation tint maps FHIR codes. */
@RunWith(RobolectricTestRunner::class)
class BiomarkerDetailScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun screen(
        state: InsightsUiState,
        selectedRange: ChartRange = ChartRange.ONE_WEEK,
        onSelectRange: (ChartRange) -> Unit = {},
        onRetry: () -> Unit = {},
    ) {
        composeRule.setContent {
            BiomarkerDetailScreen(
                title = "Heart Rate",
                unit = "bpm",
                referenceRangeLabel = "Reference range: 60–100 bpm",
                info = null,
                state = state,
                selectedRange = selectedRange,
                onSelectRange = onSelectRange,
                onRetry = onRetry,
                onBack = {},
            )
        }
    }

    private fun point(
        id: String,
        at: String,
        value: Double,
        unit: String? = "bpm",
        range: ReferenceRange? = null,
        interpretation: String? = null,
    ) = ObservationPoint(
        id = id,
        effectiveDatetime = at,
        rawValue = value,
        normalizedUnit = unit,
        referenceRange = range,
        interpretation = interpretation,
    )

    private fun assertTextCount(
        text: String,
        expected: Int,
    ) {
        assertEquals(expected, composeRule.onAllNodesWithText(text).fetchSemanticsNodes().size)
    }

    @Test
    fun loading_state_shows_skeleton_not_spinner() {
        screen(InsightsUiState.Loading)
        composeRule.onNodeWithTag("detail_skeleton").assertIsDisplayed()
        composeRule.onNodeWithTag("chart_skeleton").assertIsDisplayed()
        composeRule.onNodeWithTag("biomarker_chart").assertDoesNotExist()
    }

    @Test
    fun error_state_shows_message_and_retry_fires() {
        var retried = false
        screen(InsightsUiState.Error("Couldn't load readings."), onRetry = { retried = true })

        composeRule.onNodeWithText("Couldn't load readings.").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        assertTrue(retried)
    }

    @Test
    fun empty_window_shows_range_hint() {
        screen(InsightsUiState.Loaded(emptyList()))

        composeRule.onNodeWithText("No readings in this range. Try a wider range.").assertIsDisplayed()
        composeRule.onNodeWithText("Latest reading").assertDoesNotExist()
    }

    @Test
    fun empty_window_still_shows_the_latest_reading_card() {
        // "Last week" with the newest reading 8+ days old must surface the
        // latest state instead of only the empty hint.
        val state =
            InsightsUiState.Loaded(
                points = emptyList(),
                latestOverall = point("p1", "2026-08-06T08:00:00Z", 1.8, interpretation = "H"),
            )
        screen(state)

        composeRule.onNodeWithText("No readings in this range. Try a wider range.").assertIsDisplayed()
        composeRule.onNodeWithText("Latest reading").assertIsDisplayed()
        composeRule.onNodeWithText("1.8").assertIsDisplayed()
    }

    @Test
    fun loaded_state_renders_chart_and_latest_card() {
        val state =
            InsightsUiState.Loaded(
                points =
                    listOf(
                        point("p1", "2026-08-10T09:00:00Z", 70.0, range = ReferenceRange(low = 60.0, high = 100.0), interpretation = "N"),
                        point("p2", "2026-08-11T09:00:00Z", 75.0, range = ReferenceRange(low = 60.0, high = 100.0), interpretation = "H"),
                    ),
                previousOverall = point("p0", "2026-08-09T09:00:00Z", 70.0),
            )
        screen(state)

        composeRule.onNodeWithTag("biomarker_chart").assertIsDisplayed()
        composeRule.onNodeWithText("2 readings").assertIsDisplayed()
        composeRule.onNodeWithText("Latest reading").assertIsDisplayed()
        assertTextCount("75", 3)
        composeRule.onNodeWithText("bpm").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Trending up").assertIsDisplayed()
        composeRule.onNodeWithText("7.1%").assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription("Heart Rate over last week, ranging from 70 to 75, currently 75 bpm")
            .assertIsDisplayed()
    }

    @Test
    fun trend_chip_reads_stable_without_a_percent_when_change_is_small() {
        val state =
            InsightsUiState.Loaded(
                points =
                    listOf(
                        point("p1", "2026-08-10T09:00:00Z", 70.0),
                        point("p2", "2026-08-11T09:00:00Z", 70.5),
                    ),
                previousOverall = point("p0", "2026-08-09T09:00:00Z", 70.0),
            )
        screen(state)

        composeRule.onNodeWithContentDescription("Stable").assertIsDisplayed()
        composeRule.onNodeWithText("0.7%").assertDoesNotExist()
    }

    @Test
    fun trend_chip_is_hidden_without_a_previous_reading() {
        val state =
            InsightsUiState.Loaded(
                listOf(
                    point("p1", "2026-08-10T09:00:00Z", 70.0),
                    point("p2", "2026-08-11T09:00:00Z", 75.0),
                ),
            )
        screen(state)

        composeRule.onNodeWithContentDescription("Trending up").assertDoesNotExist()
        composeRule.onNodeWithText("7.1%").assertDoesNotExist()
    }

    @Test
    fun stats_row_renders_min_max_avg_last_over_the_loaded_window() {
        val state =
            InsightsUiState.Loaded(
                listOf(
                    point("p1", "2026-08-10T09:00:00Z", 65.0),
                    point("p2", "2026-08-11T09:00:00Z", 70.0),
                    point("p3", "2026-08-12T09:00:00Z", 78.0),
                ),
            )
        screen(state)

        composeRule.onNodeWithTag("biomarker_stats").assertIsDisplayed()
        composeRule.onNodeWithText("Min").assertIsDisplayed()
        composeRule.onNodeWithText("Max").assertIsDisplayed()
        composeRule.onNodeWithText("Average").assertIsDisplayed()
        composeRule.onNodeWithText("Last").assertIsDisplayed()
        assertTextCount("65", 1)
        assertTextCount("78", 3)
        assertTextCount("71", 1)
    }

    @Test
    fun stats_row_is_hidden_when_the_window_has_no_numeric_points() {
        screen(InsightsUiState.Loaded(emptyList()))

        composeRule.onNodeWithTag("biomarker_stats").assertDoesNotExist()
    }

    @Test
    fun chart_renders_with_entry_animation_disabled_under_reduce_motion() {
        composeRule.setContent {
            io.healthassistant.android.ui.theme.HATheme(reduceMotion = true) {
                BiomarkerDetailScreen(
                    title = "Heart Rate",
                    unit = "bpm",
                    referenceRangeLabel = null,
                    info = null,
                    state =
                        InsightsUiState.Loaded(
                            listOf(
                                point("p1", "2026-08-10T09:00:00Z", 70.0),
                                point("p2", "2026-08-11T09:00:00Z", 75.0),
                            ),
                        ),
                    selectedRange = ChartRange.ONE_WEEK,
                    onSelectRange = {},
                    onRetry = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("biomarker_chart").assertIsDisplayed()
    }

    @Test
    fun millisecond_precision_timestamps_do_not_crash_the_chart() {
        // Regression (on-device crash): float x values with >4 decimals are
        // rejected by Vico's xDeltaGcd — real telemetry carries ms offsets.
        val state =
            InsightsUiState.Loaded(
                listOf(
                    point("p1", "2026-08-10T09:00:00.123Z", 70.0),
                    point("p2", "2026-08-10T09:41:33.884Z", 71.5),
                    point("p3", "2026-08-10T11:02:07.007Z", 69.0),
                ),
            )
        screen(state)

        composeRule.onNodeWithTag("biomarker_chart").assertIsDisplayed()
        assertTextCount("69", 3)
    }

    @Test
    fun sparse_series_in_a_wide_window_renders_with_padded_domain() {
        // One lab result 12 days old inside a 1-year window: the chart domain
        // must stay anchored to the window (now-anchored), not collapse to the
        // single point's degenerate span.
        screen(InsightsUiState.Loaded(listOf(point("p1", "2026-08-06T08:00:00Z", 1.8))), selectedRange = ChartRange.ONE_YEAR)

        composeRule.onNodeWithTag("biomarker_chart").assertIsDisplayed()
        assertTextCount("1.8", 5)
    }

    @Test
    fun unparseable_datetimes_are_dropped_not_crashed() {
        val state =
            InsightsUiState.Loaded(
                listOf(
                    point("junk", "not a date", 99.0),
                    point("p1", "2026-08-11", 72.0),
                ),
            )
        screen(state)

        composeRule.onNodeWithText("1 readings").assertIsDisplayed()
        assertTextCount("72", 5)
    }

    @Test
    fun range_chip_selection_fires() {
        var picked: ChartRange? = null
        screen(InsightsUiState.Loaded(emptyList()), onSelectRange = { picked = it })

        composeRule.onNodeWithText("Last year").performClick()

        assertEquals(ChartRange.ONE_YEAR, picked)
    }

    @Test
    fun renders_without_layout_break_at_font_scale_2x() {
        // M9 a11y gate: the detail screen (chips + chart + stats row + latest
        // card) must not break at the OS "largest" font scale.
        val state =
            InsightsUiState.Loaded(
                points =
                    listOf(
                        point("p1", "2026-08-10T09:00:00Z", 65.0),
                        point("p2", "2026-08-11T09:00:00Z", 70.0),
                        point("p3", "2026-08-12T09:00:00Z", 78.0),
                    ),
                previousOverall = point("p0", "2026-08-09T09:00:00Z", 70.0),
            )
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 2f, fontScale = 2f)) {
                BiomarkerDetailScreen(
                    title = "Heart Rate",
                    unit = "bpm",
                    referenceRangeLabel = "Reference range: 60–100 bpm",
                    info = "Some **markdown** about heart rate.",
                    state = state,
                    selectedRange = ChartRange.ONE_WEEK,
                    onSelectRange = {},
                    onRetry = {},
                    onSetAlert = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("biomarker_chart").assertIsDisplayed()
        composeRule.onNodeWithTag("biomarker_stats").assertIsDisplayed()
        composeRule.onNodeWithText("Latest reading").assertIsDisplayed()
        composeRule.onNodeWithText("Set an alert").assertIsDisplayed()
    }

    @Test
    fun state_biomarkers_render_a_state_change_timeline_not_an_empty_chart() {
        val state = { id: String, at: String, label: String ->
            ObservationPoint(id = id, effectiveDatetime = at, valueString = label)
        }
        screen(
            InsightsUiState.Loaded(
                listOf(
                    state("p1", "2026-08-10T22:00:00Z", "Deep sleep"),
                    state("p2", "2026-08-10T23:00:00Z", "Light sleep"),
                ),
            ),
        )

        composeRule.onNodeWithTag("biomarker_state_timeline").assertIsDisplayed()
        composeRule.onNodeWithText("State changes").assertIsDisplayed()
        composeRule.onNodeWithText("Light sleep").assertIsDisplayed()
        composeRule.onNodeWithText("Deep sleep").assertIsDisplayed()
        composeRule.onNodeWithText("No readings in this range. Try a wider range.").assertDoesNotExist()
        composeRule.onNodeWithTag("biomarker_chart").assertDoesNotExist()
    }
}
