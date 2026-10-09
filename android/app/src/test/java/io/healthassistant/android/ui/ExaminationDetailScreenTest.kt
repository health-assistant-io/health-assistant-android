package io.healthassistant.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.healthassistant.shared.data.ExaminationSummary
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** R2 gate: the exam detail renders doctor notes, patient notes, AI
 *  impressions, and diagnosis chips from the detail model. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExaminationDetailScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val exam =
        ExaminationSummary(
            id = "e1",
            examinationDate = "2026-08-01",
            notes = "<p>Fasting panel <strong>stable</strong></p>",
            patientNotes = "Felt **mild** fatigue",
            extractionStatus = "completed",
            diagnoses = listOf("Hypertension", "E66.9 Obesity"),
            impressions = "## Summary\nNo critical deltas vs last panel.",
        )

    @Test
    fun rendersAllNoteSections() {
        composeRule.setContent {
            ExaminationDetailScreen(
                exam = exam,
                documents = emptyList(),
                docThumbnails = emptyMap(),
                state = DocListState.Loaded,
                docText = null,
                extraction = null,
                onOpenDocument = {},
                onViewDocumentText = {},
                onCloseDocumentText = {},
                onOpenInBrowser = {},
                onRetry = {},
                onBack = {},
                onDeleteExam = {},
                onDeleteDocument = {},
                onReExtractDocument = {},
                onUpload = {},
            )
        }
        composeRule.onNodeWithText("Doctor's notes").assertIsDisplayed()
        composeRule.onNodeWithText("Your notes").assertIsDisplayed()
        composeRule.onNodeWithText("AI impressions").assertIsDisplayed()
    }

    @Test
    fun rendersDiagnosisChips() {
        composeRule.setContent {
            ExaminationDetailScreen(
                exam = exam,
                documents = emptyList(),
                docThumbnails = emptyMap(),
                state = DocListState.Loaded,
                docText = null,
                extraction = null,
                onOpenDocument = {},
                onViewDocumentText = {},
                onCloseDocumentText = {},
                onOpenInBrowser = {},
                onRetry = {},
                onBack = {},
                onDeleteExam = {},
                onDeleteDocument = {},
                onReExtractDocument = {},
                onUpload = {},
            )
        }
        composeRule.onNodeWithText("Hypertension").assertIsDisplayed()
        composeRule.onNodeWithText("E66.9 Obesity").assertIsDisplayed()
    }

    @Test
    fun missingOptionalSectionsStayHidden() {
        val bare = exam.copy(notes = null, patientNotes = null, impressions = null, diagnoses = emptyList())
        composeRule.setContent {
            ExaminationDetailScreen(
                exam = bare,
                documents = emptyList(),
                docThumbnails = emptyMap(),
                state = DocListState.Loaded,
                docText = null,
                extraction = null,
                onOpenDocument = {},
                onViewDocumentText = {},
                onCloseDocumentText = {},
                onOpenInBrowser = {},
                onRetry = {},
                onBack = {},
                onDeleteExam = {},
                onDeleteDocument = {},
                onReExtractDocument = {},
                onUpload = {},
            )
        }
        composeRule.onNodeWithText("Doctor's notes").assertDoesNotExist()
        composeRule.onNodeWithText("Your notes").assertDoesNotExist()
        composeRule.onNodeWithText("AI impressions").assertDoesNotExist()
    }
}
