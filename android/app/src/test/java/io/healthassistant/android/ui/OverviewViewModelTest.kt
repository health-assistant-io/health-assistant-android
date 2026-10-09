package io.healthassistant.android.ui

import io.healthassistant.shared.data.BiomarkerSummary
import io.healthassistant.shared.data.ChartRange
import io.healthassistant.shared.data.NormalizationStrategy
import io.healthassistant.shared.data.ObservationCode
import io.healthassistant.shared.data.ObservationPoint
import io.healthassistant.shared.data.ReferenceRange
import io.healthassistant.shared.data.cache.BiomarkerCache
import io.healthassistant.shared.data.cache.CacheDomain
import io.healthassistant.shared.data.cache.CacheMetaState
import io.healthassistant.shared.data.cache.CacheMetaStore
import io.healthassistant.shared.data.cache.CacheRefreshMeta
import io.healthassistant.shared.data.cache.ObservationCache
import io.healthassistant.shared.data.repository.BiomarkerCatalogRepository
import io.healthassistant.shared.data.repository.BiomarkerGateway
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * Monitoring-viz M6 gate for the overview owner: the selection default (3
 * most recently active), persistence round-trip + the 3-series cap, per-code
 * series loading from the cache, normalization wiring for both strategies,
 * and the single-series reference band. In-memory fakes of the repositories'
 * collaborators (cache/gateway/connectivity/selection store).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OverviewViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private class FakeObservationCache : ObservationCache {
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
                    .mapNotNull { p -> epochMs(p)?.let { it to p } }
                    .filter { (epoch, _) -> (sinceMs == null || epoch >= sinceMs) && (untilMs == null || epoch <= untilMs) }
                    .sortedBy { it.first }
                    .take(limit)
                    .map { it.second }
            }

        override fun latestPerBiomarker(limit: Int): Flow<List<ObservationPoint>> =
            rows.asStateFlow().map { list ->
                list
                    .groupBy { it.primaryCode }
                    .values
                    .map { group -> group.maxByOrNull { epochMs(it) ?: 0L }!! }
                    .sortedByDescending { epochMs(it) ?: 0L }
                    .take(limit)
            }

        override fun latestForCode(code: String): Flow<ObservationPoint?> =
            rows.asStateFlow().map { list -> list.filter { it.primaryCode == code }.maxByOrNull { epochMs(it) ?: 0L } }

        override fun previousForCode(code: String): Flow<ObservationPoint?> =
            rows.asStateFlow().map { list ->
                list
                    .filter { it.primaryCode == code }
                    .sortedByDescending { epochMs(it) ?: 0L }
                    .getOrNull(1)
            }

        override fun previousPerBiomarker(limit: Int): Flow<List<ObservationPoint>> =
            rows.asStateFlow().map { list ->
                list
                    .groupBy { it.primaryCode }
                    .values
                    .mapNotNull { group -> group.sortedByDescending { epochMs(it) ?: 0L }.getOrNull(1) }
                    .take(limit)
            }

        override suspend fun clearForCode(code: String) {
            rows.value = rows.value.filterNot { it.primaryCode == code }
        }

        override suspend fun clear() {
            rows.value = emptyList()
        }
    }

    private class RecordingGateway(
        var online: Boolean = true,
    ) : ObservationGateway {
        val seriesCalls = mutableListOf<Triple<String, String, String>>()

        override suspend fun latest(limit: Int): List<ObservationPoint> = emptyList()

        override suspend fun series(
            code: String,
            since: String,
            until: String,
            limit: Int,
        ): List<ObservationPoint> {
            seriesCalls.add(Triple(code, since, until))
            return emptyList()
        }
    }

    private class FakeBiomarkerCache : BiomarkerCache {
        val catalog = MutableStateFlow<List<BiomarkerSummary>>(emptyList())

        override suspend fun replaceAll(catalog: List<BiomarkerSummary>) {
            this.catalog.value = catalog
        }

        override suspend fun replaceAllSynced(
            catalog: List<BiomarkerSummary>,
            meta: CacheRefreshMeta,
        ) = replaceAll(catalog)

        override fun observeAll(): Flow<List<BiomarkerSummary>> = catalog.asStateFlow()

        override fun observeByCode(code: String): Flow<BiomarkerSummary?> =
            catalog.asStateFlow().map { list -> list.firstOrNull { it.code == code } }

        override suspend fun clear() {
            catalog.value = emptyList()
        }
    }

    private class FakeBiomarkerGateway : BiomarkerGateway {
        val catalog = MutableStateFlow<List<BiomarkerSummary>>(emptyList())

        override suspend fun catalog(limit: Int): List<BiomarkerSummary> = catalog.value
    }

    private class FakeSelectionStore : OverviewSelectionStore {
        val stored = MutableStateFlow<List<String>?>(null)
        val setCalls = mutableListOf<List<String>>()

        override val codes: Flow<List<String>?> = stored.asStateFlow()

        override suspend fun setCodes(codes: List<String>) {
            setCalls.add(codes)
            stored.value = codes
        }
    }

    private object NoopMetaStore : CacheMetaStore {
        override suspend fun recordRefresh(meta: CacheRefreshMeta) = Unit

        override fun observe(domain: CacheDomain): Flow<CacheMetaState?> = MutableStateFlow(null)

        override fun observeAll(): Flow<List<CacheMetaState>> = MutableStateFlow(emptyList())

        override suspend fun clearDomain(domain: CacheDomain) = Unit

        override suspend fun clear() = Unit
    }

    private lateinit var cache: FakeObservationCache
    private lateinit var gateway: RecordingGateway
    private lateinit var biomarkerCache: FakeBiomarkerCache
    private lateinit var biomarkerGateway: FakeBiomarkerGateway
    private lateinit var store: FakeSelectionStore

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        cache = FakeObservationCache()
        gateway = RecordingGateway()
        biomarkerCache = FakeBiomarkerCache()
        biomarkerGateway = FakeBiomarkerGateway()
        store = FakeSelectionStore()
    }

    private fun repo() = ObservationRepository(cache, gateway, ConnectivityProvider { gateway.online }, NoopMetaStore)

    private fun biomarkerRepo() = BiomarkerCatalogRepository(biomarkerCache, biomarkerGateway, ConnectivityProvider { true }, NoopMetaStore)

    private fun vm() = OverviewViewModel(repo(), biomarkerRepo(), store)

    private fun point(
        id: String,
        code: String,
        at: String,
        value: Double,
        low: Double? = null,
        high: Double? = null,
    ) = ObservationPoint(
        id = id,
        effectiveDatetime = at,
        rawValue = value,
        normalizedUnit = "u",
        code = ObservationCode(coding = listOf(ObservationCode.Coding(code = code, system = "http://loinc.org"))),
        referenceRange = if (low != null && high != null) ReferenceRange(low, high) else null,
    )

    private fun summary(
        code: String,
        name: String,
        unit: String = "u",
        min: Double? = null,
        max: Double? = null,
    ) = BiomarkerSummary(id = code, name = name, code = code, unit = unit, referenceRangeMin = min, referenceRangeMax = max)

    @Test
    fun nothing_stored_defaults_to_the_three_most_recently_active() =
        runTest(dispatcher) {
            val now = System.currentTimeMillis()
            cache.store(
                listOf(
                    point("a1", "8867-4", Instant.ofEpochMilli(now - 3_600_000).toString(), 70.0),
                    point("w1", "29463-7", Instant.ofEpochMilli(now - 86_400_000).toString(), 80.0),
                    point("s1", "55423-8", Instant.ofEpochMilli(now - 7_200_000).toString(), 100.0),
                    point("o1", "59408-5", Instant.ofEpochMilli(now - 172_800_000).toString(), 97.0),
                    point("h1", "8302-2", Instant.ofEpochMilli(now - 259_200_000).toString(), 1.8),
                ),
            )
            val vm = vm()
            advanceUntilIdle()

            assertEquals(listOf("8867-4", "55423-8", "29463-7"), vm.state.value.selection)
        }

    @Test
    fun stored_selection_wins_over_the_default() =
        runTest(dispatcher) {
            store.stored.value = listOf("8302-2")
            cache.store(listOf(point("a1", "8867-4", Instant.now().toString(), 70.0)))
            val vm = vm()
            advanceUntilIdle()

            assertEquals(listOf("8302-2"), vm.state.value.selection)
            assertEquals(
                "8302-2",
                vm.state.value.series
                    .single()
                    .code,
            )
        }

    @Test
    fun an_explicitly_emptied_selection_stays_empty() =
        runTest(dispatcher) {
            store.stored.value = emptyList()
            cache.store(listOf(point("a1", "8867-4", Instant.now().toString(), 70.0)))
            val vm = vm()
            advanceUntilIdle()

            assertTrue(
                vm.state.value.selection
                    .isEmpty(),
            )
            assertTrue(
                vm.state.value.series
                    .isEmpty(),
            )
        }

    @Test
    fun toggle_adds_then_removes_and_persists_through_the_store() =
        runTest(dispatcher) {
            store.stored.value = listOf("8867-4")
            val vm = vm()
            advanceUntilIdle()

            vm.toggle("29463-7")
            advanceUntilIdle()
            assertEquals(listOf("8867-4", "29463-7"), store.setCalls.single())
            assertEquals(listOf("8867-4", "29463-7"), vm.state.value.selection)

            vm.toggle("8867-4")
            advanceUntilIdle()
            assertEquals(listOf("29463-7"), store.setCalls.last())
            assertEquals(listOf("29463-7"), vm.state.value.selection)
        }

    @Test
    fun toggle_never_grows_past_three_series() =
        runTest(dispatcher) {
            store.stored.value = listOf("8867-4", "29463-7", "55423-8")
            val vm = vm()
            advanceUntilIdle()

            vm.toggle("59408-5")
            advanceUntilIdle()

            assertTrue(store.setCalls.isEmpty())
            assertEquals(3, vm.state.value.selection.size)
        }

    @Test
    fun series_load_per_code_and_normalize_min_max_onto_the_shared_axis() =
        runTest(dispatcher) {
            val now = System.currentTimeMillis()
            store.stored.value = listOf("8867-4", "29463-7")
            cache.store(
                listOf(
                    point("hr1", "8867-4", Instant.ofEpochMilli(now - 7_200_000).toString(), 60.0),
                    point("hr2", "8867-4", Instant.ofEpochMilli(now - 3_600_000).toString(), 80.0),
                    point("w1", "29463-7", Instant.ofEpochMilli(now - 86_400_000).toString(), 78.0),
                    point("w2", "29463-7", Instant.ofEpochMilli(now - 43_200_000).toString(), 82.0),
                ),
            )
            biomarkerGateway.catalog.value = listOf(summary("8867-4", "Heart rate", "bpm"), summary("29463-7", "Weight", "kg"))
            val vm = vm()
            advanceUntilIdle()

            val state = vm.state.value
            assertEquals(listOf("Heart rate", "Weight"), state.series.map { it.name })
            assertEquals(listOf("bpm", "kg"), state.series.map { it.unit })
            val heartRate = state.series[0]
            assertEquals(0f, heartRate.points.first().value)
            assertEquals(1f, heartRate.points.last().value)
            assertEquals(
                80.0,
                heartRate.scale?.invert(
                    heartRate.points
                        .last()
                        .value
                        .toDouble(),
                )!!,
                1e-9,
            )
            val weight = state.series[1]
            assertEquals(0f, weight.points.first().value)
            assertEquals(1f, weight.points.last().value)
        }

    @Test
    fun switching_strategy_renormalizes_the_same_series_as_z_scores() =
        runTest(dispatcher) {
            val now = System.currentTimeMillis()
            store.stored.value = listOf("8867-4")
            cache.store(
                listOf(
                    point("hr1", "8867-4", Instant.ofEpochMilli(now - 7_200_000).toString(), 60.0),
                    point("hr2", "8867-4", Instant.ofEpochMilli(now - 3_600_000).toString(), 70.0),
                    point("hr3", "8867-4", Instant.ofEpochMilli(now - 1_800_000).toString(), 80.0),
                ),
            )
            val vm = vm()
            advanceUntilIdle()
            assertEquals(NormalizationStrategy.MIN_MAX, vm.state.value.strategy)

            vm.selectStrategy(NormalizationStrategy.Z_SCORE)
            advanceUntilIdle()

            val series =
                vm.state.value.series
                    .single()
            assertEquals(NormalizationStrategy.Z_SCORE, series.scale?.strategy)
            assertEquals(0f, series.points[1].value)
            assertTrue(series.points[0].value < 0f)
            assertTrue(series.points[2].value > 0f)
        }

    @Test
    fun a_single_selection_draws_its_reference_band_normalized() =
        runTest(dispatcher) {
            val now = System.currentTimeMillis()
            store.stored.value = listOf("8867-4")
            cache.store(
                listOf(
                    point("hr1", "8867-4", Instant.ofEpochMilli(now - 7_200_000).toString(), 60.0, low = 65.0, high = 75.0),
                    point("hr2", "8867-4", Instant.ofEpochMilli(now - 3_600_000).toString(), 80.0, low = 65.0, high = 75.0),
                ),
            )
            val vm = vm()
            advanceUntilIdle()

            val band = vm.state.value.normalizedBand
            assertNotNull(band)
            assertEquals(0.25, band!!.start, 1e-6)
            assertEquals(0.75, band.endInclusive, 1e-6)
            assertEquals(
                65.0,
                vm.state.value.referenceRange
                    ?.low!!,
                1e-9,
            )
        }

    @Test
    fun multi_series_selections_never_draw_a_reference_band() =
        runTest(dispatcher) {
            val now = System.currentTimeMillis()
            store.stored.value = listOf("8867-4", "29463-7")
            cache.store(
                listOf(
                    point("hr1", "8867-4", Instant.ofEpochMilli(now - 7_200_000).toString(), 60.0, low = 50.0, high = 90.0),
                    point("w1", "29463-7", Instant.ofEpochMilli(now - 86_400_000).toString(), 78.0, low = 60.0, high = 90.0),
                ),
            )
            val vm = vm()
            advanceUntilIdle()

            assertNull(vm.state.value.referenceRange)
            assertNull(vm.state.value.normalizedBand)
        }

    @Test
    fun the_catalog_range_is_used_when_the_points_carry_none() =
        runTest(dispatcher) {
            val now = System.currentTimeMillis()
            store.stored.value = listOf("8867-4")
            cache.store(
                listOf(
                    point("hr1", "8867-4", Instant.ofEpochMilli(now - 7_200_000).toString(), 60.0),
                    point("hr2", "8867-4", Instant.ofEpochMilli(now - 3_600_000).toString(), 70.0),
                ),
            )
            biomarkerGateway.catalog.value = listOf(summary("8867-4", "Heart rate", "bpm", min = 65.0, max = 75.0))
            val vm = vm()
            advanceUntilIdle()

            assertEquals(
                65.0,
                vm.state.value.referenceRange
                    ?.low!!,
                1e-9,
            )
            assertEquals(
                75.0,
                vm.state.value.referenceRange
                    ?.high!!,
                1e-9,
            )
            assertNotNull(vm.state.value.normalizedBand)
        }

    @Test
    fun range_changes_refresh_every_selected_series_window() =
        runTest(dispatcher) {
            store.stored.value = listOf("8867-4", "29463-7")
            val vm = vm()
            advanceUntilIdle()

            vm.selectRange(ChartRange.ONE_YEAR)
            advanceUntilIdle()

            val lastCalls = gateway.seriesCalls.takeLast(2)
            assertEquals(setOf("8867-4", "29463-7"), lastCalls.map { it.first }.toSet())
            lastCalls.forEach { (_, since, until) ->
                val spanMs =
                    java.time.Duration
                        .between(Instant.parse(since), Instant.parse(until))
                        .toMillis()
                assertEquals(365L * 86_400_000L, spanMs)
            }
        }
}

private fun epochMs(point: ObservationPoint): Long? =
    point.effectiveDatetime?.let {
        runCatching {
            Instant.parse(it).toEpochMilli()
        }.getOrNull()
    }
