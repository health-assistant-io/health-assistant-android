package io.healthassistant.shared.data.repository

import io.healthassistant.shared.data.cache.DocumentByteStore
import io.healthassistant.shared.data.cache.DocumentCache
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the offline-first invariants of [DocumentByteRepository] (M4):
 * disk hit serves without a gateway call, a miss + online fetches + persists +
 * records the manifest, a miss + offline returns null (never throws), LRU
 * eviction clears the manifest rows of the evicted documents.
 */
class DocumentByteRepositoryTest {
    @Test
    fun `disk hit serves instantly with no gateway call`() =
        runTest {
            val store = FakeByteStore()
            store.write("d1", byteArrayOf(1, 2, 3))
            val gateway = RecordingByteGateway()
            val repo = DocumentByteRepository(store, FakeMetaCache(), gateway, ConnectivityProvider { false })

            val bytes = repo.content("d1")

            assertEquals(3, bytes?.size)
            assertEquals("a disk hit must not touch the network", 0, gateway.contentCalls)
        }

    @Test
    fun `miss online fetches persists and records the manifest`() =
        runTest {
            val store = FakeByteStore()
            val meta = FakeMetaCache()
            val gateway = RecordingByteGateway(content = byteArrayOf(7, 8, 9))
            val repo = DocumentByteRepository(store, meta, gateway, ConnectivityProvider { true })

            val bytes = repo.content("d1")

            assertEquals(1, gateway.contentCalls)
            assertEquals(3, bytes?.size)
            assertTrue("bytes must be persisted for the next offline open", repo.isDownloaded("d1"))
            assertTrue(meta.manifests.containsKey("d1"))
        }

    @Test
    fun `miss offline returns null without touching the network`() =
        runTest {
            val store = FakeByteStore()
            val gateway = RecordingByteGateway()
            val repo = DocumentByteRepository(store, FakeMetaCache(), gateway, ConnectivityProvider { false })

            assertNull(repo.content("d1"))
            assertEquals("offline + miss must skip the network entirely", 0, gateway.contentCalls)
        }

    @Test
    fun `failed fetch returns null and persists nothing`() =
        runTest {
            val store = FakeByteStore()
            val gateway = RecordingByteGateway(contentThrows = IllegalStateException("500"))
            val repo = DocumentByteRepository(store, FakeMetaCache(), gateway, ConnectivityProvider { true })

            assertNull(repo.content("d1"))
            assertFalse(repo.isDownloaded("d1"))
        }

    @Test
    fun `second read after a fetch is a disk hit`() =
        runTest {
            val store = FakeByteStore()
            val gateway = RecordingByteGateway(content = byteArrayOf(1))
            val repo = DocumentByteRepository(store, FakeMetaCache(), gateway, ConnectivityProvider { true })

            repo.content("d1")
            repo.content("d1")

            assertEquals("the network is fetched exactly once", 1, gateway.contentCalls)
        }

    @Test
    fun `evictLRU evicts oldest first and clears their manifests`() =
        runTest {
            val store = FakeByteStore()
            val meta = FakeMetaCache()
            val gateway = RecordingByteGateway()
            val repo = DocumentByteRepository(store, meta, gateway, ConnectivityProvider { true })

            store.write("old", ByteArray(100))
            store.write("new", ByteArray(100))
            meta.manifests["old"] = "old"
            meta.manifests["new"] = "new"

            val evicted = repo.evictLRU(capBytes = 100)

            assertEquals(1, evicted)
            assertFalse(repo.isDownloaded("old"))
            assertTrue(repo.isDownloaded("new"))
            assertFalse("the evicted document's manifest must be cleared", meta.manifests.containsKey("old"))
        }

    @Test
    fun `onDeleted removes bytes and manifest`() =
        runTest {
            val store = FakeByteStore()
            val meta = FakeMetaCache()
            store.write("d1", byteArrayOf(1))
            meta.manifests["d1"] = "d1"
            val repo = DocumentByteRepository(store, meta, RecordingByteGateway(), ConnectivityProvider { true })

            repo.onDeleted("d1")

            assertFalse(repo.isDownloaded("d1"))
            assertFalse(meta.manifests.containsKey("d1"))
        }

    private class RecordingByteGateway(
        private val content: ByteArray = ByteArray(0),
        private val contentThrows: Throwable? = null,
    ) : DocumentByteGateway {
        var contentCalls = 0
            private set

        override suspend fun content(docId: String): ByteArray {
            contentCalls++
            contentThrows?.let { throw it }
            return content
        }

        override suspend fun preview(
            docId: String,
            page: Int?,
        ): ByteArray = ByteArray(0)
    }

    /** In-memory [DocumentByteStore] tracking write order for LRU. */
    private class FakeByteStore : DocumentByteStore {
        private val files = LinkedHashMap<String, Pair<ByteArray, Long>>()
        private var clock = 0L

        override fun read(docId: String): ByteArray? = files[docId]?.first

        override fun write(
            docId: String,
            bytes: ByteArray,
        ) {
            files.remove(docId)
            files[docId] = bytes to clock++
        }

        override fun delete(docId: String) {
            files.remove(docId)
        }

        override fun totalBytes(): Long = files.values.sumOf { it.first.size.toLong() }

        override fun evictLRU(capBytes: Long): List<String> {
            val evicted = mutableListOf<String>()
            var total = totalBytes()
            val it = files.entries.iterator()
            while (total > capBytes && it.hasNext()) {
                val entry = it.next()
                total -= entry.value.first.size
                evicted += entry.key
                it.remove()
            }
            return evicted
        }

        override fun clear() = files.clear()
    }

    private class FakeMetaCache : DocumentCache by NoopMetaCache() {
        val manifests = mutableMapOf<String, String>()

        override suspend fun setLocalManifest(
            docId: String,
            localPath: String,
            cachedAtEpochMs: Long,
        ) {
            manifests[docId] = localPath
        }

        override suspend fun clearLocalManifest(docId: String) {
            manifests.remove(docId)
        }
    }

    /** Delegate source for the metadata ops the byte tests don't exercise. */
    private open class NoopMetaCache : DocumentCache {
        override suspend fun storeAll(docs: List<io.healthassistant.shared.data.DocumentSummary>) = Unit

        override suspend fun storeAllSynced(
            docs: List<io.healthassistant.shared.data.DocumentSummary>,
            meta: io.healthassistant.shared.data.cache.CacheRefreshMeta,
        ) = Unit

        override suspend fun reconcileForExam(
            examId: String,
            ids: List<String>,
        ) = Unit

        override suspend fun delete(id: String) = Unit

        override fun observeForExam(examId: String) = kotlinx.coroutines.flow.MutableStateFlow(
            emptyList<io.healthassistant.shared.data.DocumentSummary>(),
        )

        override suspend fun setLocalManifest(
            docId: String,
            localPath: String,
            cachedAtEpochMs: Long,
        ) = Unit

        override suspend fun clearLocalManifest(docId: String) = Unit

        override suspend fun applyDeltas(rows: List<io.healthassistant.shared.data.cache.DocumentDeltaRow>) = Unit

        override suspend fun reconcileAll(ids: List<String>) = Unit

        override suspend fun clear() = Unit
    }
}
