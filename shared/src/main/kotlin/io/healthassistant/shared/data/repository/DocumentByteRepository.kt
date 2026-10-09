package io.healthassistant.shared.data.repository

import io.healthassistant.shared.data.cache.DocumentByteStore
import io.healthassistant.shared.data.cache.DocumentCache

/**
 * Network byte-read abstraction for document content (offline-first M4),
 * isolating [DocumentByteRepository] from the SDK's ktor-dependent
 * `BridgeClient` so it stays pure-Kotlin + JVM-testable. Throws on non-2xx.
 */
interface DocumentByteGateway {
    /** `GET /documents/{id}/content` — the file as uploaded. */
    suspend fun content(docId: String): ByteArray

    /** `GET /documents/{id}/preview?page=N` — a JPEG page render (thumbnails). */
    suspend fun preview(
        docId: String,
        page: Int? = null,
    ): ByteArray
}

/**
 * Single-Source-of-Truth repository for document bytes (offline-first M4).
 * A read is disk-first: a hit returns instantly (offline-capable); a miss
 * fetches from the bridge when online, writes the bytes into the bounded
 * [DocumentByteStore], records the manifest on the metadata row, and returns
 * them. Offline + miss returns null so the UI can show "Not downloaded —
 * connect to view" instead of an error.
 *
 * Previews (JPEG page renders for thumbnails) are transient — fetched via
 * [preview] without persistence (small + cheap + regenerated per visit).
 */
class DocumentByteRepository(
    private val store: DocumentByteStore,
    private val metaCache: DocumentCache,
    private val gateway: DocumentByteGateway,
    private val connectivity: ConnectivityProvider,
) {
    /** The document's content bytes — disk hit, else fetch + persist. Null
     *  when not downloaded and offline (or the fetch failed). */
    suspend fun content(docId: String): ByteArray? {
        store.read(docId)?.let { return it }
        if (!connectivity.isOnline()) return null
        return try {
            val bytes = gateway.content(docId)
            store.write(docId, bytes)
            runCatching { metaCache.setLocalManifest(docId, docId, System.currentTimeMillis()) }
            bytes
        } catch (e: Exception) {
            null
        }
    }

    /** A JPEG page render for thumbnails / inline preview (never persisted). */
    suspend fun preview(
        docId: String,
        page: Int? = null,
    ): ByteArray? =
        try {
            gateway.preview(docId, page)
        } catch (e: Exception) {
            null
        }

    /** Whether the document's bytes are already on disk. */
    fun isDownloaded(docId: String): Boolean = store.read(docId) != null

    /** True when a fresh download is possible right now. */
    fun canDownload(): Boolean = connectivity.isOnline()

    /** Enforce the size cap: evict LRU files + clear their manifest rows.
     *  Returns the number of evicted documents. */
    suspend fun evictLRU(capBytes: Long): Int {
        val evicted = store.evictLRU(capBytes)
        evicted.forEach { runCatching { metaCache.clearLocalManifest(it) } }
        return evicted.size
    }

    /** Delete one document's bytes + manifest (after a remote delete). */
    suspend fun onDeleted(docId: String) {
        store.delete(docId)
        runCatching { metaCache.clearLocalManifest(docId) }
    }

    /** Drop every stored file + manifest (the Settings clear action). */
    suspend fun clearAll() {
        store.clear()
        runCatching { metaCache.clear() }
    }

    /** Current store size in bytes (for the Settings display). */
    fun totalBytes(): Long = store.totalBytes()

    companion object {
        /** The default store cap (100 MiB) — the evictor keeps the store at or
         *  under this. Matches the plan's §4.3. */
        const val DEFAULT_CAP_BYTES: Long = 100L * 1024 * 1024
    }
}
