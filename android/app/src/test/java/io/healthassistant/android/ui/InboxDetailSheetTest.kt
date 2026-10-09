package io.healthassistant.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.healthassistant.bridge.NotificationEnvelope
import io.healthassistant.bridge.NotificationItem
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** R3 gate: tapping an inbox row opens the full-content detail sheet (rich
 *  body, timestamp, chips, dismiss) instead of only marking it read. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InboxDetailSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val unread =
        NotificationItem(
            recipientId = "r1",
            status = "unread",
            notification =
                NotificationEnvelope(
                    id = "n1",
                    title = "Glucose out of range",
                    body = "Reading **7.9 mmol/L** exceeds the upper reference.\n\n- retest after fasting",
                    severity = "warning",
                    category = "biomarker",
                    createdAt = "2026-08-17T09:30:00Z",
                ),
        )

    @Test
    fun tapOpensDetailSheetWithFullBody() {
        var markedRead = false
        composeRule.setContent {
            NotificationDetailSheet(
                item = unread,
                onDismiss = {},
                onMarkDismissed = { markedRead = true },
            )
        }
        composeRule.onNodeWithText("Glucose out of range").assertIsDisplayed()
        composeRule
            .onNodeWithText("Reading 7.9 mmol/L exceeds the upper reference. - retest after fasting")
            .assertDoesNotExist()
        composeRule.onNodeWithText("Aug 17, 2026 · 09:30").assertIsDisplayed()
        composeRule.onNodeWithText("Warning").assertIsDisplayed()
        composeRule.onNodeWithText("Dismiss").assertIsDisplayed()
        composeRule.onNodeWithText("Dismiss").performClick()
        assertTrue(markedRead)
    }
}
