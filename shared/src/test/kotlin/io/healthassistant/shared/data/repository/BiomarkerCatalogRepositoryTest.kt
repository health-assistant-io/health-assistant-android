package io.healthassistant.shared.data.repository

import io.healthassistant.shared.data.BiomarkerSummary
import io.healthassistant.shared.data.cache.BiomarkerCache
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

/**
 * JVM test for the offline-first invariants of [BiomarkerCatalogRepository]:
 * cache-first observe (no gateway call), refresh swaps the snapshot (including
 * removals — the catalog is a full snapshot, not an upsert), and offline /
 * failed refreshes leave the saved catalog intact.
 */
class BiomarkerCatalogRepositoryTest {
    @Test
    fun `observeAll emits the cached catalog with no gateway call`() =
        runTest {
            val cache = FakeBiomarkerCache()
            cache.replaceAll(listOf(summary("8867-4", "Heart Rate")))
            val gateway = RecordingGateway()
            val repo = BiomarkerCatalogRepository(cache, gateway, ConnectivityProvider { true }, FakeCacheMetaStore())

            val catalog = repo.observeAll().first()

            assertEquals(listOf("Heart Rate"), catalog.map { it.name })
            assertEquals("gateway must not be called for a plain observe", 0, gateway.calls)
        }

    @Test
    fun `refresh swaps the snapshot including removals`() =
        runTest {
            val cache = FakeBiomarkerCache()
            cache.replaceAll(
                listOf(
                    summary("8867-4", "Heart Rate"),
                    summary("29463-7", "Weight"),
                ),
            )
            val gateway = RecordingGateway(catalog = listOf(summary("8867-4", "Heart Rate")))
            val repo = BiomarkerCatalogRepository(cache, gateway, ConnectivityProvider { true }, FakeCacheMetaStore())

            val outcome = repo.refresh()

            assertEquals(RefreshOutcome.REFRESHED, outcome)
            assertEquals(1, gateway.calls)
            val names = repo.observeAll().first().map { it.name }
            assertEquals("a definition removed server-side must disappear locally", listOf("Heart Rate"), names)
        }

    @Test
    fun `offline refresh returns OFFLINE and keeps the saved catalog`() =
        runTest {
            val cache = FakeBiomarkerCache()
            cache.replaceAll(listOf(summary("8867-4", "Heart Rate")))
            val gateway = RecordingGateway()
            val repo = BiomarkerCatalogRepository(cache, gateway, ConnectivityProvider { false }, FakeCacheMetaStore())

            val outcome = repo.refresh()

            assertEquals(RefreshOutcome.OFFLINE, outcome)
            assertEquals("offline must skip the network entirely", 0, gateway.calls)
            assertEquals(1, repo.observeAll().first().size)
        }

    @Test
    fun `failed refresh keeps the saved catalog`() =
        runTest {
            val cache = FakeBiomarkerCache()
            cache.replaceAll(listOf(summary("8867-4", "Heart Rate")))
            val gateway = RecordingGateway(throws = IllegalStateException("503"))
            val repo = BiomarkerCatalogRepository(cache, gateway, ConnectivityProvider { true }, FakeCacheMetaStore())

            val outcome = repo.refresh()

            assertEquals(RefreshOutcome.FAILED, outcome)
            assertEquals(1, repo.observeAll().first().size)
        }

    @Test
    fun `observeByCode finds and misses`() =
        runTest {
            val cache = FakeBiomarkerCache()
            cache.replaceAll(listOf(summary("8867-4", "Heart Rate")))
            val repo = BiomarkerCatalogRepository(cache, RecordingGateway(), ConnectivityProvider { true }, FakeCacheMetaStore())

            assertEquals("Heart Rate", repo.observeByCode("8867-4").first()?.name)
            assertNull(repo.observeByCode("nope").first())
        }

    @Test
    fun `clear drops every row`() =
        runTest {
            val cache = FakeBiomarkerCache()
            cache.replaceAll(listOf(summary("8867-4", "Heart Rate")))
            val repo = BiomarkerCatalogRepository(cache, RecordingGateway(), ConnectivityProvider { true }, FakeCacheMetaStore())

            repo.clear()

            assertTrue(repo.observeAll().first().isEmpty())
        }

    private fun summary(
        code: String,
        name: String,
    ): BiomarkerSummary = BiomarkerSummary(id = name, name = name, code = code)

    private class RecordingGateway(
        private val catalog: List<BiomarkerSummary> = emptyList(),
        private val throws: Throwable? = null,
    ) : BiomarkerGateway {
        var calls = 0
            private set

        override suspend fun catalog(limit: Int): List<BiomarkerSummary> {
            calls++
            throws?.let { throw it }
            return catalog
        }
    }

    /** In-memory [BiomarkerCache] mirroring the Room semantics (atomic
     *  full-snapshot swap, name-ascending observe). */
    private class FakeBiomarkerCache : BiomarkerCache {
        private val rows = MutableStateFlow<List<BiomarkerSummary>>(emptyList())

        override suspend fun replaceAll(catalog: List<BiomarkerSummary>) {
            rows.value = catalog.sortedBy { it.name }
        }

        override suspend fun replaceAllSynced(
            catalog: List<BiomarkerSummary>,
            meta: io.healthassistant.shared.data.cache.CacheRefreshMeta,
        ) = replaceAll(catalog)

        override fun observeAll(): Flow<List<BiomarkerSummary>> = rows.asStateFlow()

        override fun observeByCode(code: String): Flow<BiomarkerSummary?> =
            rows.map { list -> list.firstOrNull { it.code == code } }

        override suspend fun clear() {
            rows.value = emptyList()
        }
    }
}
