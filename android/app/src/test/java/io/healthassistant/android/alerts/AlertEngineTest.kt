package io.healthassistant.android.alerts

import io.healthassistant.bridge.ClientRecord
import io.healthassistant.bridge.SyncPayload
import io.healthassistant.shared.alerts.AlertOp
import io.healthassistant.shared.alerts.AlertRule
import io.healthassistant.shared.alerts.Breach
import io.healthassistant.shared.data.ObservationCode
import io.healthassistant.shared.data.ObservationPoint
import io.healthassistant.shared.data.cache.CacheRefreshMeta
import io.healthassistant.shared.data.cache.ObservationCache
import io.healthassistant.shared.sync.OutboxItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** M5 engine gate (fake flows): cache-write subscription with baseline,
 *  cooldown through the persisted lastFired stamp, and the onSyncedItems
 *  push-hook path. */
@OptIn(ExperimentalCoroutinesApi::class)
class AlertEngineTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(dispatcher + Job())
    private val rules = MutableStateFlow<List<AlertRule>>(emptyList())
    private val fired = mutableListOf<Pair<String, Long>>()
    private val notifier = RecordingNotifier()
    private var nowMs: Long = T0
    private val cache = FakeCache()

    private val engine =
        AlertEngine(
            cache = cache,
            rules = rules.asStateFlow(),
            persistFired = { id, at -> fired.add(id to at) },
            notifier = notifier,
            scope = scope,
            now = { nowMs },
        )

    private class RecordingNotifier : AlertEngine.AlertNotifier {
        val breaches = mutableListOf<Breach>()

        override fun onBreach(
            breach: Breach,
            rule: AlertRule,
        ) {
            breaches.add(breach)
        }
    }

    private class FakeCache : ObservationCache {
        val rows = MutableStateFlow<List<ObservationPoint>>(emptyList())

        override suspend fun store(points: List<ObservationPoint>) {
            rows.value = (rows.value.associateBy { it.id } + points.associateBy { it.id }).values.toList()
        }

        override suspend fun storeSynced(
            points: List<ObservationPoint>,
            meta: CacheRefreshMeta,
        ) = store(points)

        override fun seriesFor(
            code: String,
            sinceMs: Long?,
            untilMs: Long?,
            limit: Int,
        ): Flow<List<ObservationPoint>> =
            rows.asStateFlow().map { list ->
                list
                    .filter { it.primaryCode == code }
                    .mapNotNull { p ->
                        val epoch = p.effectiveDatetime?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                        epoch?.let { epoch to p }
                    }.filter { (epoch, _) -> (sinceMs == null || epoch >= sinceMs) && (untilMs == null || epoch <= untilMs) }
                    .sortedBy { it.first }
                    .take(limit)
                    .map { it.second }
            }

        override fun latestPerBiomarker(limit: Int): Flow<List<ObservationPoint>> =
            rows.asStateFlow().map { list ->
                list
                    .groupBy { it.primaryCode }
                    .values
                    .mapNotNull { group -> group.maxByOrNull { it.effectiveDatetime ?: "" } }
                    .take(limit)
            }

        override fun latestForCode(code: String): Flow<ObservationPoint?> =
            rows.asStateFlow().map { list -> list.filter { it.primaryCode == code }.maxByOrNull { it.effectiveDatetime ?: "" } }

        override fun previousForCode(code: String): Flow<ObservationPoint?> =
            rows.asStateFlow().map { list ->
                list.filter { it.primaryCode == code }.sortedByDescending { it.effectiveDatetime ?: "" }.getOrNull(1)
            }

        override fun previousPerBiomarker(limit: Int): Flow<List<ObservationPoint>> =
            rows.asStateFlow().map { list ->
                list
                    .groupBy { it.primaryCode }
                    .values
                    .mapNotNull { group -> group.sortedByDescending { it.effectiveDatetime ?: "" }.getOrNull(1) }
                    .take(limit)
            }

        override suspend fun clearForCode(code: String) {
            rows.value = rows.value.filterNot { it.primaryCode == code }
        }

        override suspend fun clear() {
            rows.value = emptyList()
        }
    }

    private fun rule(
        op: AlertOp = AlertOp.GT,
        threshold: Double? = 120.0,
        timeWindowSec: Long = 0,
        cooldownSec: Long = 30L * 60L,
        biomarkerCode: String = HR,
        enabled: Boolean = true,
    ) = AlertRule(
        id = "r1",
        biomarkerCode = biomarkerCode,
        biomarkerName = "Heart Rate",
        op = op,
        threshold = threshold,
        timeWindowSec = timeWindowSec,
        cooldownSec = cooldownSec,
        enabled = enabled,
    )

    private fun point(
        id: String,
        atMs: Long,
        value: Double,
        code: String = HR,
    ) = ObservationPoint(
        id = id,
        effectiveDatetime = Instant.ofEpochMilli(atMs).toString(),
        rawValue = value,
        code = ObservationCode(coding = listOf(ObservationCode.Coding(code = code))),
    )

    @Test
    fun cache_write_fires_a_breaching_rule_and_stamps_the_cooldown() =
        runTest(dispatcher) {
            rules.value = listOf(rule())
            engine.start()
            cache.store(listOf(point("old", T0 - HOUR, 130.0)))
            advanceUntilIdle()
            assertEquals("the baseline emission must not alert", 0, notifier.breaches.size)

            cache.store(listOf(point("new", T0, 125.0)))
            advanceUntilIdle()

            assertEquals(1, notifier.breaches.size)
            assertEquals(125.0, notifier.breaches.single().value, 0.0)
            assertEquals(listOf("r1" to T0), fired)
            engine.stop()
        }

    @Test
    fun rules_for_other_biomarkers_do_not_fire() =
        runTest(dispatcher) {
            rules.value = listOf(rule(biomarkerCode = "55423-8"))
            engine.start()
            cache.store(listOf(point("old", T0 - HOUR, 130.0)))
            advanceUntilIdle()
            cache.store(listOf(point("new", T0, 125.0)))
            advanceUntilIdle()

            assertEquals(0, notifier.breaches.size)
            engine.stop()
        }

    @Test
    fun disabled_rules_do_not_fire() =
        runTest(dispatcher) {
            rules.value = listOf(rule(enabled = false))
            engine.start()
            cache.store(listOf(point("old", T0 - HOUR, 130.0)))
            advanceUntilIdle()
            cache.store(listOf(point("new", T0, 125.0)))
            advanceUntilIdle()

            assertEquals(0, notifier.breaches.size)
            engine.stop()
        }

    @Test
    fun persisted_cooldown_stamp_suppresses_the_next_breach() =
        runTest(dispatcher) {
            rules.value = listOf(rule())
            engine.start()
            cache.store(listOf(point("baseline", T0 - 2 * HOUR, 70.0)))
            advanceUntilIdle()
            cache.store(listOf(point("first", T0 - HOUR, 125.0)))
            advanceUntilIdle()
            assertEquals(1, notifier.breaches.size)

            nowMs = T0 + 5 * MINUTE
            val stamped = fired.last().second
            rules.value = rules.value.map { it.copy(lastFiredEpochMs = stamped) }
            cache.store(listOf(point("second", T0, 130.0)))
            advanceUntilIdle()

            assertEquals("still inside the 30-min cooldown", 1, notifier.breaches.size)

            nowMs = T0 + 31 * MINUTE
            rules.value = rules.value.map { it.copy(lastFiredEpochMs = stamped) }
            cache.store(listOf(point("third", T0 + 40 * MINUTE, 131.0)))
            advanceUntilIdle()

            assertEquals("past the cooldown it re-fires", 2, notifier.breaches.size)
            engine.stop()
        }

    @Test
    fun two_breaching_points_in_one_batch_fire_once_via_the_in_memory_guard() =
        runTest(dispatcher) {
            rules.value = listOf(rule())
            val items =
                listOf(
                    outboxItem("i1", 130.0, T0),
                    outboxItem("i2", 135.0, T0 + MINUTE),
                )
            engine.onSyncedItems(items)
            advanceUntilIdle()

            assertEquals(1, notifier.breaches.size)
            assertEquals(listOf("r1" to T0), fired)
        }

    @Test
    fun onSyncedItems_evaluates_pushed_records_with_cached_history_for_windows() =
        runTest(dispatcher) {
            rules.value = listOf(rule(timeWindowSec = 5 * 60))
            cache.store(
                listOf(
                    point("h1", T0 - 5 * MINUTE, 125.0),
                    point("h2", T0 - 2 * MINUTE, 127.0),
                ),
            )
            engine.onSyncedItems(listOf(outboxItem("i1", 130.0, T0)))
            advanceUntilIdle()

            assertEquals(1, notifier.breaches.size)
        }

    @Test
    fun onSyncedItems_skips_items_without_evaluable_records() =
        runTest(dispatcher) {
            rules.value = listOf(rule())
            engine.onSyncedItems(
                listOf(
                    OutboxItem(id = "x", method = "POST", path = "/examinations", payload = "{}".encodeToByteArray()),
                    OutboxItem(id = "y", method = "POST", path = "/sync", payload = "not json".encodeToByteArray()),
                ),
            )
            advanceUntilIdle()

            assertEquals(0, notifier.breaches.size)
        }

    private fun outboxItem(
        id: String,
        value: Double,
        atMs: Long,
    ): OutboxItem {
        val payload =
            JsonConfig
                .encodeToString(
                    SyncPayload.serializer(),
                    SyncPayload(
                        clientVersion = "0.1",
                        sourceSystem = "android-app",
                        records =
                            listOf(
                                ClientRecord(
                                    type = "quantitative",
                                    code = HR,
                                    codingSystem = "loinc",
                                    name = "Heart Rate",
                                    value = value,
                                    unit = "bpm",
                                    timestamp = Instant.ofEpochMilli(atMs).toString(),
                                ),
                            ),
                    ),
                ).encodeToByteArray()
        return OutboxItem(id = id, method = "POST", path = "/sync", payload = payload)
    }

    private companion object {
        const val T0 = 1_800_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 3_600_000L
        const val HR = "8867-4"
        val JsonConfig = kotlinx.serialization.json.Json { encodeDefaults = false }
    }
}
