package io.healthassistant.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.healthassistant.shared.data.ExaminationSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Phase H — the Records tab hub lists every record type as a uniform row and
 *  deep-links to the native list routes; the Examinations detail screen renders
 *  exam cards with status chips + the empty state. Pure state + lambdas. */
class RecordsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun exams() =
        listOf(
            ExaminationSummary(id = "e1", examinationDate = "2026-08-01", notes = "Blood panel", extractionStatus = "completed"),
            ExaminationSummary(id = "e2", examinationDate = "2026-07-20", notes = "Follow-up", extractionStatus = "processing"),
            ExaminationSummary(id = "e3", examinationDate = "2026-06-10", notes = "Urine test", extractionStatus = null),
        )

    @Test
    fun hub_shows_all_record_types() {
        composeRule.setContent {
            RecordsScreen()
        }

        composeRule.onNodeWithText("Examinations").assertIsDisplayed()
        composeRule.onNodeWithText("Medications").assertIsDisplayed()
        composeRule.onNodeWithText("Allergies").assertIsDisplayed()
        composeRule.onNodeWithText("Vaccines").assertIsDisplayed()
        composeRule.onNodeWithText("Clinical events").assertIsDisplayed()
    }

    @Test
    fun hub_examinations_row_routes() {
        var opened = false
        composeRule.setContent {
            RecordsScreen(onOpenExaminations = { opened = true })
        }

        composeRule.onNodeWithText("Examinations").performClick()

        assertTrue(opened)
    }

    @Test
    fun hub_medications_row_routes() {
        var opened = false
        composeRule.setContent {
            RecordsScreen(onOpenMedications = { opened = true })
        }

        composeRule.onNodeWithText("Medications").performClick()

        assertTrue(opened)
    }

    @Test
    fun examinations_screen_shows_status_chips() {
        composeRule.setContent {
            ExaminationsScreen(
                state = RecordsUiState.Loaded(exams()),
                onBack = {},
                onOpenExam = {},
                onRetry = {},
                onCreateExam = { _, _ -> },
                onAddReading = { _, _ -> },
            )
        }

        composeRule.onNodeWithText("Blood panel").assertIsDisplayed()
        composeRule.onNodeWithText("Processed").assertIsDisplayed()
        composeRule.onNodeWithText("Processing").assertIsDisplayed()
        composeRule.onNodeWithText("Pending").assertIsDisplayed()
    }

    @Test
    fun examinations_exam_card_routes_exam() {
        var opened: String? = null
        composeRule.setContent {
            ExaminationsScreen(
                state = RecordsUiState.Loaded(exams()),
                onBack = {},
                onOpenExam = { opened = it.id },
                onRetry = {},
                onCreateExam = { _, _ -> },
                onAddReading = { _, _ -> },
            )
        }

        composeRule.onNodeWithText("2026-08-01").performClick()

        assertEquals("e1", opened)
    }

    @Test
    fun examinations_empty_state_guides_the_user() {
        composeRule.setContent {
            ExaminationsScreen(
                state = RecordsUiState.Loaded(emptyList()),
                onBack = {},
                onOpenExam = {},
                onRetry = {},
                onCreateExam = { _, _ -> },
                onAddReading = { _, _ -> },
            )
        }

        composeRule
            .onNodeWithText("No examinations yet — ask your clinic to send records, or add a reading yourself.")
            .assertIsDisplayed()
    }
}
