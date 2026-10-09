package io.healthassistant.android.monitoring

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.healthassistant.shared.healthconnect.HcType
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * R3 gate: the plain-language Sync screen renders the source card, sync
 * controls, the "Couldn't send" failed-readings list with "Try again", and the
 * confirmed "Clear pending readings" flow. Pure state + lambdas via `setContent`.
 */
class SyncScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun monitor(
        synced: Int = 0,
        pending: Int = 0,
        dead: Int = 0,
        deadLetters: List<DeadLetterSummary> = emptyList(),
    ): SyncMonitor {
        val source =
            SourceState(
                sourceId = "health_connect",
                displayName = "Health Connect",
                available = true,
                permissionsGranted = true,
                totalSyncedPerType = if (synced > 0) mapOf(HcType.HEART_RATE to synced) else emptyMap(),
                lastSyncStatus = SyncStatus.Success(synced),
            )
        return SyncMonitor(
            sources = listOf(source),
            outboxPending = pending,
            outboxDeadLettered = dead,
            deadLetters = deadLetters,
        )
    }

    @Test
    fun shows_source_card_and_sync_controls() {
        composeRule.setContent {
            SyncScreen(
                monitor = monitor(synced = 1234),
                onSyncNow = {},
                onViewFailed = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithText("Sync").assertIsDisplayed()
        composeRule.onNodeWithText("Health Connect").assertIsDisplayed()
        composeRule.onNodeWithText("Connected").assertIsDisplayed()
        composeRule.onNodeWithText("1234 readings").assertIsDisplayed()
        composeRule.onNodeWithText("Sync now").assertIsDisplayed()
    }

    @Test
    fun pending_readings_are_surfaced_in_plain_language() {
        composeRule.setContent {
            SyncScreen(
                monitor = monitor(pending = 3),
                onSyncNow = {},
                onViewFailed = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithText("Waiting to send: 3").assertIsDisplayed()
    }

    @Test
    fun failed_readings_list_shows_plain_reasons_and_try_again() {
        val dead =
            listOf(
                DeadLetterSummary(id = "d1", method = "POST", path = "/sync", attempts = 5, reason = "Couldn't reach the server"),
            )
        composeRule.setContent {
            SyncScreen(
                monitor = monitor(dead = 1, deadLetters = dead),
                onSyncNow = {},
                onViewFailed = {},
                showFailed = true,
                onBack = {},
            )
        }

        composeRule.onNodeWithText("Couldn't send").assertIsDisplayed()
        composeRule.onNodeWithText("A health reading").assertIsDisplayed()
        composeRule.onNodeWithText("Tried 5 times").assertIsDisplayed()
        composeRule.onNodeWithText("Couldn't reach the server").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertIsDisplayed()
    }

    @Test
    fun empty_failed_list_shows_success_state() {
        composeRule.setContent {
            SyncScreen(
                monitor = monitor(dead = 0),
                onSyncNow = {},
                onViewFailed = {},
                showFailed = true,
                onBack = {},
            )
        }

        composeRule.onNodeWithText("Everything sent successfully.").assertIsDisplayed()
    }

    @Test
    fun clear_pending_requires_confirmation() {
        var cleared = false
        composeRule.setContent {
            SyncScreen(
                monitor = monitor(pending = 2, dead = 1),
                onSyncNow = {},
                onViewFailed = {},
                onClearPending = { cleared = true },
                onBack = {},
            )
        }

        composeRule.onNodeWithText("Clear pending readings").performClick()
        composeRule.onNodeWithText("Clear pending readings?").assertIsDisplayed()
        composeRule.onNodeWithText("Clear").performClick()

        assertTrue(cleared)
    }
}
