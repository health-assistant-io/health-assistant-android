package io.healthassistant.shared.data.repository

import io.healthassistant.shared.data.ObservationCode
import io.healthassistant.shared.data.ObservationPoint
import io.healthassistant.shared.data.cache.ObservationCache
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * JVM test for the offline-first invariants of [ObservationRepository]. These
 * are the three guarantees the whole M1 change rests on:
 *
 * 1. **cache-first** — [ObservationRepository.observeLatest] emits saved data
 *    even when no network refresh has run (and even when the gateway is never
 *    called). This is the literal fix for "syncing without displaying".
 * 2. **refresh writes** — a successful [ObservationRepository.refreshLatest]
 *    stores the fetched page into the cache so the next emission carries it.
 * 3. **offline keeps stale** — when the device is offline (or the call fails)
 *    the cache is left untouched and the outcome reflects it; the UI keeps the
 *    saved snapshot instead of going empty/error.
 */
class ObservationRepositoryTest {
    @Test
    fun `observeLatest emits cached data with no gateway call`() =
        runTest {
            val cache = FakeObservationCache()
            cache.store(listOf(point("a", "8867-4", 70.0, "2026-08-14T08:00:00Z")))
            val gateway = RecordingGateway()
            val repo = ObservationRepository(cache, gateway, ConnectivityProvider { true }, FakeCacheMetaStore())

            val latest = repo.observeLatest().first()

            assertEquals(1, latest.size)
            assertEquals("a", latest.single().id)
            assertEquals("gateway must not be called for a plain observe", 0, gateway.latestCalls)
        }

    @Test
    fun `refreshLatest writes gateway results into the cache`() =
        runTest {
            val cache = FakeObservationCache()
            val gateway =
                RecordingGateway(
                    latest = listOf(point("a", "8867-4", 70.0, "2026-08-14T08:00:00Z")),
                )
            val repo = ObservationRepository(cache, gateway, ConnectivityProvider { true }, FakeCacheMetaStore())

            assertTrue(cache.isEmpty())
            val outcome = repo.refreshLatest()

            assertEquals(RefreshOutcome.REFRESHED, outcome)
            assertEquals(1, gateway.latestCalls)
            val latest = repo.observeLatest().first()
            assertEquals(1, latest.size)
            assertEquals("a", latest.single().id)
        }

    @Test
    fun `refreshLatest offline leaves the cache intact and returns OFFLINE`() =
        runTest {
            val cache = FakeObservationCache()
            cache.store(listOf(point("a", "8867-4", 70.0, "2026-08-14T08:00:00Z")))
            val gateway = RecordingGateway()
            val repo = ObservationRepository(cache, gateway, ConnectivityProvider { false }, FakeCacheMetaStore())

            val outcome = repo.refreshLatest()

            assertEquals(RefreshOutcome.OFFLINE, outcome)
            assertEquals("offline must skip the network entirely", 0, gateway.latestCalls)
            assertEquals("saved snapshot must survive", 1, repo.observeLatest().first().size)
        }

    @Test
    fun `refreshLatest failure leaves the cache intact and returns FAILED`() =
        runTest {
            val cache = FakeObservationCache()
            cache.store(listOf(point("a", "8867-4", 70.0, "2026-08-14T08:00:00Z")))
            val gateway = RecordingGateway(latestThrows = IllegalStateException("500"))
            val repo = ObservationRepository(cache, gateway, ConnectivityProvider { true }, FakeCacheMetaStore())

            val outcome = repo.refreshLatest()

            assertEquals(RefreshOutcome.FAILED, outcome)
            assertEquals("saved snapshot must survive a failed refresh", 1, repo.observeLatest().first().size)
        }

    @Test
    fun `refreshSeries writes the series page keyed by biomarker code`() =
        runTest {
            val cache = FakeObservationCache()
            val gateway =
                RecordingGateway(
                    series =
                        listOf(
                            point("s1", "8867-4", 68.0, "2026-08-12T08:00:00Z"),
                            point("s2", "8867-4", 72.0, "2026-08-13T08:00:00Z"),
                        ),
                )
            val repo = ObservationRepository(cache, gateway, ConnectivityProvider { true }, FakeCacheMetaStore())

            val outcome = repo.refreshSeries("8867-4", "2026-08-12T00:00:00Z", "2026-08-14T00:00:00Z")

            assertEquals(RefreshOutcome.REFRESHED, outcome)
            val series = repo.observeSeries("8867-4").first()
            assertEquals("ascending by time", listOf("s1", "s2"), series.map { it.id })
        }

    @Test
    fun `observeSeries respects the epoch-ms window`() =
        runTest {
            val cache = FakeObservationCache()
            cache.store(
                listOf(
                    point("a", "8867-4", 68.0, "2026-08-12T08:00:00Z"),
                    point("b", "8867-4", 70.0, "2026-08-13T08:00:00Z"),
                    point("c", "8867-4", 72.0, "2026-08-14T08:00:00Z"),
                ),
            )
            val repo = ObservationRepository(cache, RecordingGateway(), ConnectivityProvider { true }, FakeCacheMetaStore())

            val since = Instant.parse("2026-08-13T00:00:00Z").toEpochMilli()
            val until = Instant.parse("2026-08-14T00:00:00Z").toEpochMilli()
            val windowed = repo.observeSeries("8867-4", sinceMs = since, untilMs = until).first()

            assertEquals(listOf("b"), windowed.map { it.id })
        }

    @Test
    fun `storeLocal writes local samples into the same merged cache`() =
        runTest {
            val cache = FakeObservationCache()
            val repo = ObservationRepository(cache, RecordingGateway(), ConnectivityProvider { true }, FakeCacheMetaStore())

            repo.storeLocal(listOf(point("local-1", "8867-4", 71.0, "2026-08-14T09:00:00Z")))

            assertEquals(1, repo.observeLatest().first().size)
        }

    @Test
    fun `observePreviousForCode returns the reading before the newest`() =
        runTest {
            val cache = FakeObservationCache()
            cache.store(
                listOf(
                    point("a", "8867-4", 68.0, "2026-08-12T08:00:00Z"),
                    point("b", "8867-4", 70.0, "2026-08-13T08:00:00Z"),
                    point("c", "8867-4", 72.0, "2026-08-14T08:00:00Z"),
                    point("s1", "55423-8", 100.0, "2026-08-14T08:00:00Z"),
                ),
            )
            val repo = ObservationRepository(cache, RecordingGateway(), ConnectivityProvider { true }, FakeCacheMetaStore())

            assertEquals("b", repo.observePreviousForCode("8867-4").first()?.id)
            assertNull("single-point code has no previous", repo.observePreviousForCode("55423-8").first()?.id)
        }

    @Test
    fun `observePreviousPerBiomarker returns the second newest per code`() =
        runTest {
            val cache = FakeObservationCache()
            cache.store(
                listOf(
                    point("a", "8867-4", 68.0, "2026-08-12T08:00:00Z"),
                    point("b", "8867-4", 70.0, "2026-08-13T08:00:00Z"),
                    point("c", "8867-4", 72.0, "2026-08-14T08:00:00Z"),
                    point("s1", "55423-8", 100.0, "2026-08-14T08:00:00Z"),
                    point("s2", "55423-8", 110.0, "2026-08-15T08:00:00Z"),
                ),
            )
            val repo = ObservationRepository(cache, RecordingGateway(), ConnectivityProvider { true }, FakeCacheMetaStore())

            val previous = repo.observePreviousPerBiomarker().first().associateBy { it.primaryCode }

            assertEquals("b", previous["8867-4"]?.id)
            assertEquals("s1", previous["55423-8"]?.id)
        }

    private fun point(
        id: String,
        code: String,
        value: Double,
        iso: String,
    ): ObservationPoint =
        ObservationPoint(
            id = id,
            effectiveDatetime = iso,
            rawValue = value,
            code = ObservationCode(coding = listOf(ObservationCode.Coding(code = code))),
        )

    private class RecordingGateway(
        private val latest: List<ObservationPoint> = emptyList(),
        private val series: List<ObservationPoint> = emptyList(),
        private val latestThrows: Throwable? = null,
        private val seriesThrows: Throwable? = null,
    ) : ObservationGateway {
        var latestCalls = 0
            private set
        var seriesCalls = 0
            private set

        override suspend fun latest(limit: Int): List<ObservationPoint> {
            latestCalls++
            latestThrows?.let { throw it }
            return latest
        }

        override suspend fun series(
            code: String,
            sinceIso: String,
            untilIso: String,
            limit: Int,
        ): List<ObservationPoint> {
            seriesCalls++
            seriesThrows?.let { throw it }
            return series
        }
    }

    /** In-memory [ObservationCache] mirroring the Room semantics (upsert-by-id,
     *  drop unaddressable rows, ascending series, newest-per-code). Reactive via
     *  a [MutableStateFlow] so `.first()` collects the current snapshot. */
    private class FakeObservationCache : ObservationCache {
        private val rows = MutableStateFlow<List<ObservationPoint>>(emptyList())

        suspend fun isEmpty(): Boolean = rows.value.isEmpty()

        override suspend fun store(points: List<ObservationPoint>) {
            val addressable = points.filter { codeOf(it) != null }
            val byId = rows.value.associateBy { it.id }.toMutableMap()
            addressable.forEach { byId[it.id] = it }
            rows.value = byId.values.toList()
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
            rows.map { list ->
                list
                    .asSequence()
                    .filter { codeOf(it) == code }
                    .filter { p ->
                        val epoch = epochOf(p)
                        when {
                            epoch == null -> sinceMs == null && untilMs == null
                            sinceMs != null && epoch < sinceMs -> false
                            untilMs != null && epoch > untilMs -> false
                            else -> true
                        }
                    }.sortedBy { epochOf(it) ?: Long.MAX_VALUE }
                    .take(limit)
                    .toList()
            }

        override fun latestPerBiomarker(limit: Int): Flow<List<ObservationPoint>> =
            rows.map { list ->
                list
                    .groupBy { codeOf(it)!! }
                    .values
                    .map { group -> group.maxByOrNull { epochOf(it) ?: Long.MIN_VALUE }!! }
                    .sortedByDescending { epochOf(it) ?: Long.MIN_VALUE }
                    .take(limit)
            }

        override fun latestForCode(code: String): Flow<ObservationPoint?> =
            rows.map { list -> list.filter { codeOf(it) == code }.maxByOrNull { epochOf(it) ?: Long.MIN_VALUE } }

        override fun previousForCode(code: String): Flow<ObservationPoint?> =
            rows.map { list ->
                list
                    .filter { codeOf(it) == code }
                    .sortedByDescending { epochOf(it) ?: Long.MIN_VALUE }
                    .getOrNull(1)
            }

        override fun previousPerBiomarker(limit: Int): Flow<List<ObservationPoint>> =
            rows.map { list ->
                list
                    .groupBy { codeOf(it)!! }
                    .values
                    .mapNotNull { group -> group.sortedByDescending { epochOf(it) ?: Long.MIN_VALUE }.getOrNull(1) }
                    .sortedByDescending { epochOf(it) ?: Long.MIN_VALUE }
                    .take(limit)
            }

        override suspend fun clearForCode(code: String) {
            rows.value = rows.value.filterNot { codeOf(it) == code }
        }

        override suspend fun clear() {
            rows.value = emptyList()
        }

        private fun codeOf(p: ObservationPoint): String? =
            p.code?.coding?.firstOrNull()?.code ?: p.biomarkerSlug

        private fun epochOf(p: ObservationPoint): Long? =
            p.effectiveDatetime?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    }
}
