package io.healthassistant.android.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.healthassistant.shared.data.cache.CacheDomain
import io.healthassistant.shared.data.cache.CacheMetaState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The staleness chip's contract (offline-first M8, plan §5): "Updated X ago"
 * from cache_meta while fresh, "Showing saved data · offline" after a failed
 * refresh with no network, a clickable "Couldn't refresh — tap to retry" once
 * online again, and nothing at all for a never-refreshed/null row.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class StaleChipTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun fresh_row_renders_updated_with_relative_time() {
        composeRule.setContent {
            StaleChip(
                meta = state(lastSuccessAt = System.currentTimeMillis(), error = null),
                online = true,
                onRetry = {},
            )
        }

        composeRule.onNodeWithText("Updated just now").assertIsDisplayed()
    }

    @Test
    fun failed_refresh_offline_renders_the_saved_data_label() {
        composeRule.setContent {
            StaleChip(
                meta = state(lastSuccessAt = System.currentTimeMillis() - 3_600_000, error = "timeout"),
                online = false,
                onRetry = {},
            )
        }

        composeRule.onNodeWithText("Showing saved data · offline").assertIsDisplayed()
    }

    @Test
    fun failed_refresh_online_is_clickable_and_fires_retry() {
        var retries = 0
        composeRule.setContent {
            StaleChip(
                meta = state(lastSuccessAt = System.currentTimeMillis() - 3_600_000, error = "500"),
                online = true,
                onRetry = { retries++ },
            )
        }

        composeRule.onNodeWithText("Couldn't refresh — tap to retry").assertIsDisplayed()
        composeRule.onNodeWithText("Couldn't refresh — tap to retry").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun null_and_never_refreshed_rows_render_nothing() {
        composeRule.setContent {
            StaleChip(meta = null, online = true, onRetry = {})
            StaleChip(meta = state(lastSuccessAt = 0L, error = null), online = true, onRetry = {})
        }

        composeRule.onNodeWithText("Showing saved data · offline").assertDoesNotExist()
        composeRule.onNodeWithText("Couldn't refresh — tap to retry").assertDoesNotExist()
    }

    @Test
    fun saved_data_banner_matches_the_offline_state_and_is_not_clickable_when_offline() {
        var retries = 0
        composeRule.setContent {
            SavedDataBanner(
                meta = state(lastSuccessAt = System.currentTimeMillis(), error = "no route"),
                online = false,
                onRetry = { retries++ },
            )
        }

        composeRule.onNodeWithText("Showing saved data · offline").assertIsDisplayed()
        composeRule.onNodeWithText("Showing saved data · offline").performClick()
        assertEquals("offline banner must not be clickable", 0, retries)
    }

    private fun state(
        lastSuccessAt: Long,
        error: String?,
    ): CacheMetaState =
        CacheMetaState(
            domain = CacheDomain.OBSERVATIONS,
            lastSuccessAtEpochMs = lastSuccessAt,
            lastError = error,
            rowCount = 12,
        )
}
