package io.healthassistant.android.ui

import io.healthassistant.shared.data.ObservationCode
import io.healthassistant.shared.data.ObservationPoint
import io.healthassistant.shared.data.cache.ObservationCache
import io.healthassistant.shared.data.repository.ConnectivityProvider
import io.healthassistant.shared.data.repository.ObservationGateway
import io.healthassistant.shared.data.repository.ObservationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Zoom-detail pipeline gate: when the visible span halves, the VM fetches the
 * RAW window (gateway series with a narrow since/until, limit 500) and
 * re-renders the chart series from the cache. Uses in-memory fakes of the
 * repository's collaborators (cache/gateway/connectivity).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BiomarkerDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private class FakeCache : ObservationCache {
        val rows = MutableStateFlow<List<ObservationPoint>>(emptyList())

        override suspend fun store(points: List<ObservationPoint>) {
            rows.value = (rows.value.associateBy { it.id } + points.associateBy { it.id }).values.toList()
        }

        override suspend fun storeSynced(
            points: List<ObservationPoint>,
            meta: io.healthassistant.shared.data.cache.CacheRefreshMeta,
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
                        val epoch =
                            p.effectiveDatetime?.let {
                                runCatching {
                                    java.time.Instant
                                        .parse(it)
                                        .toEpochMilli()
                                }.getOrNull()
                            }
                        epoch?.let { epoch to p }
                    }.filter { (epoch, _) ->
                        (sinceMs == null || epoch >= sinceMs) && (untilMs == null || epoch <= untilMs)
                    }.sortedBy { it.first }
                    .take(limit)
                    .map { it.second }
            }

        override fun latestPerBiomarker(limit: Int): Flow<List<ObservationPoint>> =
            rows.asStateFlow().map { list ->
                list
                    .groupBy { it.primaryCode }
                    .values
                    .map { g -> g.maxByOrNull { it.effectiveDatetime ?: "" }!! }
                    .take(limit)
            }

        override fun latestForCode(code: String): Flow<ObservationPoint?> =
            rows.asStateFlow().map { list -> list.filter { it.primaryCode == code }.maxByOrNull { it.effectiveDatetime ?: "" } }

        override fun previousForCode(code: String): Flow<ObservationPoint?> =
            rows.asStateFlow().map { list ->
                list
                    .filter { it.primaryCode == code }
                    .sortedByDescending { it.effectiveDatetime ?: "" }
                    .getOrNull(1)
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

    private inner class RecordingGateway(
        private val cache: FakeCache,
        var online: Boolean = true,
    ) : ObservationGateway {
        val seriesCalls = mutableListOf<Triple<String, String?, String?>>()

        override suspend fun latest(limit: Int): List<ObservationPoint> = emptyList()

        override suspend fun series(
            code: String,
            since: String,
            until: String,
            limit: Int,
        ): List<ObservationPoint> {
            seriesCalls.add(Triple(code, since, until))
            // Newest 500 rows of the requested window — raw telemetry density.
            val sinceMs =
                java.time.Instant
                    .parse(since)
                    .toEpochMilli()
            val untilMs =
                java.time.Instant
                    .parse(until)
                    .toEpochMilli()
            return (0 until limit)
                .map { i -> untilMs - i * 60_000L }
                .map { ms ->
                    point(
                        "raw-$ms",
                        java.time.Instant
                            .ofEpochMilli(ms)
                            .toString(),
                        60.0 + (ms % 40),
                    )
                }.also { cache.store(it) }
        }
    }

    private fun point(
        id: String,
        at: String,
        value: Double,
    ) = ObservationPoint(
        id = id,
        effectiveDatetime = at,
        rawValue = value,
        code =
            ObservationCode(
                coding = listOf(ObservationCode.Coding(code = "8867-4", system = "http://loinc.org", display = "Heart rate")),
            ),
    )

    private lateinit var cache: FakeCache
    private lateinit var gateway: RecordingGateway

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        cache = FakeCache()
        gateway = RecordingGateway(cache)
    }

    private fun repo() = ObservationRepository(cache, gateway, ConnectivityProvider { gateway.online }, NoopMetaStore)

    private fun vm() = BiomarkerDetailViewModel(repo(), "failed", "8867-4")

    @Test
    fun zoom_span_halving_fetches_raw_window_and_rerenders() =
        runTest(dispatcher) {
            val now = System.currentTimeMillis()
            cache.store(
                listOf(
                    point(
                        "seed",
                        java.time.Instant
                            .ofEpochMilli(now - 3_600_000)
                            .toString(),
                        72.0,
                    ),
                ),
            )
            val vm = vm()
            advanceUntilIdle()
            assertEquals(1, gateway.seriesCalls.size)

            vm.inspectSpan(3_600_000) // 1 h visible < half of the 7-day fetched span
            advanceUntilIdle()

            assertEquals(2, gateway.seriesCalls.size)
            val (code, since, until) = gateway.seriesCalls.last()
            assertEquals("8867-4", code)
            val spanMs =
                java.time.Duration
                    .between(java.time.Instant.parse(since), java.time.Instant.parse(until))
                    .toMillis()
            assertEquals(2 * 3_600_000L, spanMs)
            val state = vm.series.value as InsightsUiState.Loaded
            assertTrue(state.points.any { it.id.startsWith("raw-") })
        }

    @Test
    fun small_span_changes_do_not_refetch() =
        runTest(dispatcher) {
            val vm = vm()
            advanceUntilIdle()
            val afterInit = gateway.seriesCalls.size

            vm.inspectSpan(6L * 86_400_000) // 6d visible vs 7d fetched — no halving
            advanceUntilIdle()
            vm.inspectSpan(3_600_000) // first halving → one fetch
            advanceUntilIdle()
            vm.inspectSpan(3_500_000) // still above half of 1h → no fetch
            advanceUntilIdle()

            assertEquals(afterInit + 1, gateway.seriesCalls.size)
        }

    @Test
    fun refresh_outcome_wiring_survives_offline_detail_fetch() =
        runTest(dispatcher) {
            gateway.online = false
            val vm = vm()
            advanceUntilIdle()
            val before = gateway.seriesCalls.size

            vm.inspectSpan(3_600_000)
            advanceUntilIdle()

            // Offline: refreshSeries is a no-op (no gateway call) and the
            // cached view is kept — no error, no crash.
            assertEquals(before, gateway.seriesCalls.size)
            assertTrue(vm.series.value is InsightsUiState.Loaded)
        }

    @Test
    fun loaded_state_carries_the_previous_reading_for_the_trend_chip() =
        runTest(dispatcher) {
            gateway.online = false
            val now = System.currentTimeMillis()
            cache.store(
                listOf(
                    point(
                        "prev",
                        java.time.Instant
                            .ofEpochMilli(now - 7_200_000)
                            .toString(),
                        70.0,
                    ),
                    point(
                        "latest",
                        java.time.Instant
                            .ofEpochMilli(now - 3_600_000)
                            .toString(),
                        75.0,
                    ),
                ),
            )
            val vm = vm()
            advanceUntilIdle()

            val state = vm.series.value as InsightsUiState.Loaded
            assertEquals("latest", state.latestOverall?.id)
            assertEquals("prev", state.previousOverall?.id)
            assertEquals(Trend.UP, trendOf(state.latestOverall?.chartValue, state.previousOverall?.chartValue)?.trend)
        }

    @Test
    fun single_cached_point_leaves_the_trend_chip_hidden() =
        runTest(dispatcher) {
            gateway.online = false
            val now = System.currentTimeMillis()
            cache.store(
                listOf(
                    point(
                        "only",
                        java.time.Instant
                            .ofEpochMilli(now - 3_600_000)
                            .toString(),
                        72.0,
                    ),
                ),
            )
            val vm = vm()
            advanceUntilIdle()

            val state = vm.series.value as InsightsUiState.Loaded
            assertEquals("only", state.latestOverall?.id)
            assertNull(state.previousOverall)
            assertNull(trendOf(state.latestOverall?.chartValue, state.previousOverall?.chartValue))
        }
}

private object NoopMetaStore : io.healthassistant.shared.data.cache.CacheMetaStore {
    override suspend fun recordRefresh(meta: io.healthassistant.shared.data.cache.CacheRefreshMeta) = Unit

    override fun observe(domain: io.healthassistant.shared.data.cache.CacheDomain) =
        kotlinx.coroutines.flow.MutableStateFlow<io.healthassistant.shared.data.cache.CacheMetaState?>(null)

    override fun observeAll() =
        kotlinx.coroutines.flow.MutableStateFlow<List<io.healthassistant.shared.data.cache.CacheMetaState>>(emptyList())

    override suspend fun clearDomain(domain: io.healthassistant.shared.data.cache.CacheDomain) = Unit

    override suspend fun clear() = Unit
}
