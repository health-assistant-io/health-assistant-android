package io.healthassistant.android.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import io.healthassistant.android.data.ServerReachability
import io.healthassistant.android.monitoring.SourceState
import io.healthassistant.android.monitoring.SyncMonitor
import io.healthassistant.android.monitoring.SyncStatus
import io.healthassistant.shared.data.BiomarkerReading
import io.healthassistant.shared.data.HomeViewStyle
import io.healthassistant.shared.healthconnect.HcType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** R2 gate: the Home dashboard renders metric cards + sync status from a fake
 *  [SyncMonitor] (pure state + lambdas via `setContent`, per the repo's pattern).
 *  Cards are [BiomarkerReading]s — Health Connect types AND instance-only
 *  lab biomarkers render the same way. */
@RunWith(RobolectricTestRunner::class)
class HomeScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun monitor(
        synced: Int = 0,
        dead: Int = 0,
    ): SyncMonitor =
        SyncMonitor(
            sources =
                listOf(
                    SourceState(
                        sourceId = "health_connect",
                        displayName = "Health Connect",
                        available = true,
                        permissionsGranted = true,
                        totalSyncedPerType = if (synced > 0) mapOf(HcType.HEART_RATE to synced) else emptyMap(),
                        lastSyncStatus = SyncStatus.Success(synced),
                    ),
                ),
            outboxDeadLettered = dead,
        )

    private fun heartRateReading() =
        BiomarkerReading(
            code = HcType.HEART_RATE.code,
            displayName = "Heart Rate",
            value = 72.0,
            unit = "bpm",
            timestamp = "2026-08-10T09:00:00Z",
            hcType = HcType.HEART_RATE,
        )

    private fun glucoseReading() =
        BiomarkerReading(
            code = "2345-7",
            displayName = "Fasting Glucose",
            value = 5.5,
            unit = "mmol/L",
            timestamp = "2026-08-10T08:00:00Z",
        )

    @Test
    fun metric_card_shows_reading_value_and_unit() {
        composeRule.setContent {
            HomeScreen(
                monitor = monitor(),
                readings = listOf(heartRateReading()),
                reachability = ServerReachability.Online,
                onSyncNow = {},
            )
        }

        composeRule.onNodeWithText("Heart Rate").assertIsDisplayed()
        composeRule.onNodeWithText("72").assertIsDisplayed()
        composeRule.onNodeWithText("bpm").assertIsDisplayed()
    }

    @Test
    fun header_shows_brand_icon_and_app_name() {
        composeRule.setContent {
            HomeScreen(
                monitor = monitor(),
                readings = emptyList(),
                reachability = ServerReachability.Online,
                onSyncNow = {},
            )
        }

        // Brand header: app name + icon (content description = app name).
        composeRule.onNodeWithContentDescription("Health Assistant").assertIsDisplayed()
        composeRule.onNodeWithText("Health Assistant").assertIsDisplayed()
    }

    @Test
    fun instance_only_biomarker_renders_alongside_hc_card() {
        composeRule.setContent {
            HomeScreen(
                monitor = monitor(),
                readings = listOf(heartRateReading(), glucoseReading()),
                reachability = ServerReachability.Online,
                onSyncNow = {},
            )
        }

        composeRule.onNodeWithText("Heart Rate").assertIsDisplayed()
        composeRule.onNodeWithText("Fasting Glucose").assertIsDisplayed()
        composeRule.onNodeWithText("5.5").assertIsDisplayed()
    }

    @Test
    fun placeholder_shown_when_no_readings() {
        composeRule.setContent {
            HomeScreen(
                monitor = monitor(),
                readings = emptyList(),
                reachability = ServerReachability.Online,
                onSyncNow = {},
            )
        }

        composeRule.onNodeWithText("No readings yet — sync to see your health data.").assertIsDisplayed()
    }

    @Test
    fun offline_banner_shown_when_offline() {
        composeRule.setContent {
            HomeScreen(
                monitor = monitor(),
                readings = emptyList(),
                reachability = ServerReachability.NoInternet,
                onSyncNow = {},
            )
        }

        composeRule.onNodeWithText("You're offline — showing last saved data").assertIsDisplayed()
    }

    @Test
    fun status_menu_prefers_the_cache_last_updated_label() {
        // M9 pull-to-refresh polish: "Updated X ago" comes from the
        // observations cache meta (the StaleChip source), not the push-sync
        // monitor, so it advances on every pull refresh. Since the v1.3
        // header rework it lives in the status dropdown's header block.
        composeRule.setContent {
            HomeScreen(
                monitor = monitor(),
                readings = emptyList(),
                reachability = ServerReachability.Online,
                staleMeta =
                    io.healthassistant.shared.data.cache.CacheMetaState(
                        domain = io.healthassistant.shared.data.cache.CacheDomain.OBSERVATIONS,
                        lastSuccessAtEpochMs = System.currentTimeMillis() - 2 * 60_000L,
                        lastError = null,
                        rowCount = 12,
                    ),
                onSyncNow = {},
            )
        }

        composeRule.onNodeWithContentDescription("Status and dashboard options").performClick()
        composeRule.onNodeWithText("Updated 2 min ago").assertIsDisplayed()
    }

    @Test
    fun dead_lettered_items_surface_error_status() {
        composeRule.setContent {
            HomeScreen(
                monitor = monitor(dead = 2),
                readings = emptyList(),
                reachability = ServerReachability.Online,
                onSyncNow = {},
                onOpenSync = {},
            )
        }

        composeRule.onNodeWithText("Some readings couldn't sync").assertIsDisplayed()
    }

    @Test
    fun renders_without_layout_break_at_font_scale_2x() {
        // R8 a11y gate: the whole Home layout must not break at the OS "largest"
        // font scale. Simulated with a 2.0× fontScale (density 2x = hdpi baseline).
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 2f, fontScale = 2f)) {
                HomeScreen(
                    monitor = monitor(synced = 3),
                    readings = listOf(heartRateReading(), glucoseReading()),
                    reachability = ServerReachability.Online,
                    onSyncNow = {},
                )
            }
        }

        composeRule.onNodeWithText("Heart Rate").assertIsDisplayed()
        composeRule.onNodeWithText("Fasting Glucose").assertIsDisplayed()
    }

    @Test
    fun simple_view_style_renders_large_single_column_cards() {
        // Simple view: the large, single-column grid still shows every card.
        composeRule.setContent {
            HomeScreen(
                monitor = monitor(),
                readings = listOf(heartRateReading(), glucoseReading()),
                reachability = ServerReachability.Online,
                viewStyle = HomeViewStyle.SIMPLE,
                onSyncNow = {},
            )
        }

        composeRule.onNodeWithText("Heart Rate").assertIsDisplayed()
        composeRule.onNodeWithText("Fasting Glucose").assertIsDisplayed()
        composeRule.onNodeWithText("72").assertIsDisplayed()
    }

    @Test
    fun status_menu_layout_section_reports_change() {
        var chosen: HomeViewStyle? = null
        composeRule.setContent {
            HomeScreen(
                monitor = monitor(),
                readings = listOf(heartRateReading()),
                reachability = ServerReachability.Online,
                viewStyle = HomeViewStyle.GRID,
                onCycleViewStyle = { chosen = it },
                onSyncNow = {},
            )
        }

        composeRule.onNodeWithContentDescription("Status and dashboard options").performClick()
        composeRule.onNodeWithText("Layout").assertExists()
        composeRule.onNodeWithText("List").performClick()

        assertEquals(HomeViewStyle.LIST, chosen)
    }

    @Test
    fun status_menu_edit_item_opens_dashboard_editor() {
        var editOpen = false
        composeRule.setContent {
            HomeScreen(
                monitor = monitor(),
                readings = listOf(heartRateReading()),
                reachability = ServerReachability.Online,
                onOpenEdit = { editOpen = true },
                onSyncNow = {},
            )
        }

        composeRule.onNodeWithContentDescription("Status and dashboard options").performClick()
        composeRule.onNodeWithText("Edit dashboard").performClick()

        assertEquals(true, editOpen)
    }

    @Test
    fun ai_button_renders_and_fires_hand_off() {
        var opened = false
        composeRule.setContent {
            HomeScreen(
                monitor = monitor(),
                readings = emptyList(),
                reachability = ServerReachability.Online,
                onSyncNow = {},
                onOpenAssistant = { opened = true },
            )
        }

        composeRule.onNodeWithContentDescription("Ask the assistant").performClick()

        assertEquals(true, opened)
    }

    @Test
    fun assistant_card_hidden_without_callback() {
        composeRule.setContent {
            HomeScreen(
                monitor = monitor(),
                readings = emptyList(),
                reachability = ServerReachability.Online,
                onSyncNow = {},
            )
        }

        composeRule.onNodeWithText("Ask the assistant").assertDoesNotExist()
    }

    @Test
    fun checkin_sections_render_and_rows_fire_intents() {
        var examOpened: String? = null
        var inboxOpened = false
        composeRule.setContent {
            HomeScreen(
                monitor = monitor(),
                readings = listOf(heartRateReading()),
                reachability = ServerReachability.Online,
                medications =
                    listOf(
                        io.healthassistant.bridge.Medication(
                            id = "m1",
                            status = "ACTIVE",
                            dosage = "10 mg daily",
                        ),
                    ),
                recentExams =
                    listOf(
                        io.healthassistant.shared.data.ExaminationSummary(
                            id = "e1",
                            examinationDate = "2026-08-01",
                            notes = "<p>Lipid panel</p>",
                            extractionStatus = "completed",
                        ),
                    ),
                inbox =
                    listOf(
                        io.healthassistant.bridge.NotificationItem(
                            recipientId = "r1",
                            status = "unread",
                            notification =
                                io.healthassistant.bridge.NotificationEnvelope(
                                    id = "n1",
                                    title = "Glucose out of range",
                                    body = "Reading exceeds the upper reference.",
                                ),
                        ),
                    ),
                unreadCount = 2,
                onSyncNow = {},
                onOpenExam = { examOpened = it },
                onOpenInbox = { inboxOpened = true },
            )
        }

        composeRule.onNodeWithText("Medications").assertIsDisplayed()
        composeRule.onNodeWithText("10 mg daily").assertIsDisplayed()
        composeRule.onNodeWithText("Recent results").assertIsDisplayed()
        composeRule.onNodeWithText("Lipid panel · Ready").assertIsDisplayed()
        composeRule.onNodeWithText("Glucose out of range").assertIsDisplayed()
        composeRule.onNodeWithText("2").assertIsDisplayed()

        composeRule.onNodeWithText("2026-08-01").performClick()
        org.junit.Assert.assertEquals("e1", examOpened)
        composeRule.onNodeWithText("Inbox").performClick()
        org.junit.Assert.assertTrue(inboxOpened)
    }
}
