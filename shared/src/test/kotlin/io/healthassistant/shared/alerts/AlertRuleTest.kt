package io.healthassistant.shared.alerts

import io.healthassistant.shared.data.ObservationCode
import io.healthassistant.shared.data.ObservationPoint
import io.healthassistant.shared.sync.OutboxItem
import io.healthassistant.bridge.ClientRecord
import io.healthassistant.bridge.SyncPayload
import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M5 evaluator gate: comparison ops, time-window evidence, cooldown, and the
 *  outbox-item → point mapping the sync hook feeds the engine with. */
class AlertRuleTest {
    private fun rule(
        op: AlertOp = AlertOp.GT,
        threshold: Double? = 120.0,
        rangeLow: Double? = null,
        rangeHigh: Double? = null,
        timeWindowSec: Long = 0,
        cooldownSec: Long = 0,
        enabled: Boolean = true,
        lastFiredEpochMs: Long? = null,
    ) = AlertRule(
        id = "r1",
        biomarkerCode = "8867-4",
        op = op,
        threshold = threshold,
        rangeLow = rangeLow,
        rangeHigh = rangeHigh,
        timeWindowSec = timeWindowSec,
        cooldownSec = cooldownSec,
        enabled = enabled,
        lastFiredEpochMs = lastFiredEpochMs,
    )

    private fun point(
        id: String,
        atMs: Long,
        value: Double,
    ) = ObservationPoint(
        id = id,
        effectiveDatetime = Instant.ofEpochMilli(atMs).toString(),
        rawValue = value,
        code = ObservationCode(coding = listOf(ObservationCode.Coding(code = "8867-4"))),
    )

    private val t0: Long = 1_800_000_000_000L
    private val min: Long = 60_000L

    @Test
    fun gt_breaches_only_strictly_above() {
        assertNull(rule(AlertOp.GT, 120.0).evaluate(point("p", t0, 120.0), emptyList(), t0))
        assertNotNull(rule(AlertOp.GT, 120.0).evaluate(point("p", t0, 120.1), emptyList(), t0))
    }

    @Test
    fun ge_breaches_at_the_threshold() {
        assertNotNull(rule(AlertOp.GE, 120.0).evaluate(point("p", t0, 120.0), emptyList(), t0))
        assertNull(rule(AlertOp.GE, 120.0).evaluate(point("p", t0, 119.9), emptyList(), t0))
    }

    @Test
    fun lt_and_le_boundaries() {
        assertNotNull(rule(AlertOp.LT, 94.0).evaluate(point("p", t0, 93.0), emptyList(), t0))
        assertNull(rule(AlertOp.LT, 94.0).evaluate(point("p", t0, 94.0), emptyList(), t0))
        assertNotNull(rule(AlertOp.LE, 94.0).evaluate(point("p", t0, 94.0), emptyList(), t0))
        assertNull(rule(AlertOp.LE, 94.0).evaluate(point("p", t0, 94.1), emptyList(), t0))
    }

    @Test
    fun out_of_range_breaches_strictly_outside_the_bounds() {
        val r = rule(AlertOp.OUT_OF_RANGE, rangeLow = 60.0, rangeHigh = 100.0)
        assertNotNull(r.evaluate(point("p", t0, 59.9), emptyList(), t0))
        assertNotNull(r.evaluate(point("p", t0, 100.1), emptyList(), t0))
        assertNull(r.evaluate(point("p", t0, 60.0), emptyList(), t0))
        assertNull(r.evaluate(point("p", t0, 100.0), emptyList(), t0))
        assertNull(r.evaluate(point("p", t0, 80.0), emptyList(), t0))
    }

    @Test
    fun disabled_rule_never_fires() {
        assertNull(rule(enabled = false).evaluate(point("p", t0, 200.0), emptyList(), t0))
    }

    @Test
    fun invalid_rule_never_fires() {
        assertNull(rule(threshold = null).evaluate(point("p", t0, 200.0), emptyList(), t0))
        assertNull(
            rule(AlertOp.OUT_OF_RANGE, rangeLow = 100.0, rangeHigh = 60.0).evaluate(point("p", t0, 80.0), emptyList(), t0),
        )
        assertNull(rule(timeWindowSec = -1).evaluate(point("p", t0, 200.0), emptyList(), t0))
        assertNull(rule(cooldownSec = -5).evaluate(point("p", t0, 200.0), emptyList(), t0))
    }

    @Test
    fun non_numeric_or_untimed_points_never_fire() {
        val noValue = ObservationPoint(id = "p", effectiveDatetime = Instant.ofEpochMilli(t0).toString())
        assertNull(rule().evaluate(noValue, emptyList(), t0))
        val noTime = ObservationPoint(id = "p", rawValue = 200.0)
        assertNull(rule().evaluate(noTime, emptyList(), t0))
    }

    @Test
    fun zero_window_fires_immediately_without_history() {
        val breach = rule(timeWindowSec = 0).evaluate(point("p", t0, 130.0), emptyList(), t0)
        assertNotNull(breach)
        assertEquals(t0, breach!!.readingEpochMs)
        assertEquals(t0, breach.firedEpochMs)
        assertEquals(130.0, breach.value, 0.0)
        assertEquals("r1", breach.ruleId)
    }

    @Test
    fun window_requires_evidence_spanning_the_full_window() {
        val fiveMin = 5 * 60L
        val r = rule(timeWindowSec = fiveMin)

        assertNull("a lone reading covers no window", r.evaluate(point("p", t0, 130.0), emptyList(), t0))
        assertNull(
            "a run younger than the window is not enough",
            r.evaluate(point("p", t0, 130.0), listOf(point("h1", t0 - 4 * min, 130.0)), t0),
        )
        val breach = r.evaluate(point("p", t0, 130.0), listOf(point("h1", t0 - 5 * min, 130.0)), t0)
        assertNotNull("a run exactly spanning the window breaches", breach)
    }

    @Test
    fun window_blocks_when_any_in_window_reading_satisfies_not() {
        val r = rule(timeWindowSec = 5 * 60)
        val history =
            listOf(
                point("h1", t0 - 5 * min, 130.0),
                point("h2", t0 - 2 * min, 80.0),
            )
        assertNull(r.evaluate(point("p", t0, 130.0), history, t0))
    }

    @Test
    fun window_ignores_readings_before_the_window() {
        val r = rule(timeWindowSec = 5 * 60)
        val history =
            listOf(
                point("old-ok", t0 - 30 * min, 70.0),
                point("h1", t0 - 5 * min, 130.0),
            )
        assertNotNull(r.evaluate(point("p", t0, 130.0), history, t0))
    }

    @Test
    fun window_ignores_readings_after_the_anchor() {
        val r = rule(timeWindowSec = 5 * 60)
        val history =
            listOf(
                point("h1", t0 - 5 * min, 130.0),
                point("newer", t0 + 3 * min, 60.0),
            )
        assertNotNull(r.evaluate(point("p", t0, 130.0), history, t0))
    }

    @Test
    fun window_ignores_history_points_without_a_value_or_time() {
        val r = rule(timeWindowSec = 5 * 60)
        val noise =
            listOf(
                ObservationPoint(id = "novalue", effectiveDatetime = Instant.ofEpochMilli(t0 - 5 * min).toString()),
                ObservationPoint(id = "notime", rawValue = 999.0),
            )
        assertNotNull(r.evaluate(point("p", t0, 130.0), noise + listOf(point("h1", t0 - 5 * min, 130.0)), t0))
    }

    @Test
    fun cooldown_suppresses_refiring_until_it_expires() {
        val r = rule(cooldownSec = 30 * 60)
        assertNull(
            "a fire 10 min ago suppresses",
            r.copy(lastFiredEpochMs = t0 - 10 * min).evaluate(point("p", t0, 130.0), emptyList(), t0),
        )
        assertNotNull(
            "a fire 30 min ago (exactly the cooldown) re-fires",
            r.copy(lastFiredEpochMs = t0 - 30 * min).evaluate(point("p", t0, 130.0), emptyList(), t0),
        )
    }

    @Test
    fun zero_cooldown_never_suppresses() {
        val r = rule(cooldownSec = 0, lastFiredEpochMs = t0)
        assertNotNull(r.evaluate(point("p", t0, 130.0), emptyList(), t0))
    }

    @Test
    fun validity_rules() {
        assertTrue(rule().isValid())
        assertFalse(rule(threshold = null).isValid())
        assertFalse(rule(AlertOp.OUT_OF_RANGE, rangeLow = 60.0, rangeHigh = null).isValid())
        assertFalse(rule(AlertOp.OUT_OF_RANGE, rangeLow = 100.0, rangeHigh = 100.0).isValid())
        assertFalse(rule(cooldownSec = -1).isValid())
        assertFalse(AlertRule(id = "r", biomarkerCode = "", threshold = 1.0).isValid())
    }

    @Test
    fun outbox_item_maps_to_an_evaluable_point() {
        val payload =
            Json { encodeDefaults = false }
                .encodeToString(
                    SyncPayload.serializer(),
                    SyncPayload(
                        clientVersion = "0.1",
                        sourceSystem = "android-app",
                        records =
                            listOf(
                                ClientRecord(
                                    type = "quantitative",
                                    code = "8867-4",
                                    codingSystem = "loinc",
                                    name = "Heart Rate",
                                    value = 135.0,
                                    unit = "bpm",
                                    timestamp = Instant.ofEpochMilli(t0).toString(),
                                ),
                            ),
                    ),
                ).encodeToByteArray()
        val item = OutboxItem(id = "item-1", method = "POST", path = "/sync", payload = payload)

        val point = outboxItemToPoint(item)
        assertNotNull(point)
        assertEquals("8867-4", point!!.primaryCode)
        assertEquals(135.0, point.chartValue!!, 0.0)
        assertEquals(t0, Instant.parse(point.effectiveDatetime!!).toEpochMilli())
        assertNotNull(rule().evaluate(point, emptyList(), t0))
    }

    @Test
    fun non_sync_items_and_unparseable_payloads_map_to_null() {
        val payload = "{}".encodeToByteArray()
        assertNull(outboxItemToPoint(OutboxItem(id = "a", method = "DELETE", path = "/sync", payload = payload)))
        assertNull(outboxItemToPoint(OutboxItem(id = "b", method = "POST", path = "/examinations", payload = payload)))
        assertNull(outboxItemToPoint(OutboxItem(id = "c", method = "POST", path = "/sync", payload = "not json".encodeToByteArray())))
        val emptySync =
            Json { encodeDefaults = false }
                .encodeToString(SyncPayload.serializer(), SyncPayload(clientVersion = "0.1", sourceSystem = "android-app"))
                .encodeToByteArray()
        assertNull(outboxItemToPoint(OutboxItem(id = "d", method = "POST", path = "/sync", payload = emptySync)))
    }
}
