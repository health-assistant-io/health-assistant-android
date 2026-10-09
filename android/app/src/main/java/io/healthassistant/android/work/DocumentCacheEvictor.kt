package io.healthassistant.android.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.healthassistant.android.data.cache.RoomDocumentManifestCache
import io.healthassistant.shared.data.cache.DocumentByteStore
import io.healthassistant.shared.data.cache.DocumentCache
import io.healthassistant.shared.data.repository.DocumentByteGateway
import io.healthassistant.shared.data.repository.DocumentByteRepository
import org.koin.core.context.GlobalContext

/**
 * Offline-first M4 — the document byte-cache evictor. Runs daily (scheduled in
 * `HAApplication` next to the sync workers, no constraints — eviction is
 * disk-only) and enforces the 100 MiB cap: least-recently-written files are
 * deleted first, and each evicted document's manifest row is cleared so the
 * metadata stays truthful. A no-op when the store is already under the cap.
 */
class DocumentCacheEvictor(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val koin = GlobalContext.getOrNull() ?: return Result.success()
        val store: DocumentByteStore = koin.get()
        // Offline-first M9: the byte store is a single connection-agnostic
        // on-disk LRU, so eviction bookkeeping goes through the UNSCOPED
        // manifest façade — an evicted file invalidates the manifest pointer
        // of every connection that referenced it.
        val metaCache: DocumentCache = RoomDocumentManifestCache(koin.get())
        // The gateway is unreachable by construction (the offline provider
        // short-circuits every network path) — eviction is disk-only.
        val repo =
            DocumentByteRepository(
                store = store,
                metaCache = metaCache,
                gateway = EvictorGateway,
                connectivity = { false },
            )
        return try {
            repo.evictLRU(DocumentByteRepository.DEFAULT_CAP_BYTES)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private object EvictorGateway : DocumentByteGateway {
        override suspend fun content(docId: String): ByteArray = error("evictor never fetches")

        override suspend fun preview(
            docId: String,
            page: Int?,
        ): ByteArray = error("evictor never fetches")
    }
}
