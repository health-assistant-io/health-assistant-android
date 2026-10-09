package io.healthassistant.android.data.cache

import androidx.room.withTransaction
import io.healthassistant.shared.data.DocumentSummary
import io.healthassistant.shared.data.ExaminationSummary
import io.healthassistant.shared.data.cache.CacheRefreshMeta
import io.healthassistant.shared.data.cache.DeltaRows
import io.healthassistant.shared.data.cache.DocumentCache
import io.healthassistant.shared.data.cache.DocumentDeltaRow
import io.healthassistant.shared.data.cache.ExaminationCache
import io.healthassistant.shared.data.cache.ExaminationDeltaRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Android Room implementations of the shared examination + document caches
 * (offline-first M3), bound to one bridge connection (offline-first M9): every
 * read, write, and reconcile is scoped by the connection id — a reconcile on
 * one connection can never drop another connection's rows.
 *
 * [RoomDocumentCache.storeAll] preserves the (M4) `local_*` manifest columns
 * across a metadata refresh by reading the existing rows first — a re-fetch
 * must not forget that a document's bytes are on disk. A `*Synced` write
 * records the cache_meta success row in the SAME Room transaction as the
 * upsert.
 */
class RoomExaminationCache(
    private val db: ObservationDatabase,
    private val connectionId: String,
) : ExaminationCache {
    private val dao get() = db.examinationDao()

    override suspend fun storeAll(exams: List<ExaminationSummary>) {
        if (exams.isEmpty()) return
        // Preserve detail-projection fields across a list-shape refresh
        // (impressions/diagnoses/category/lab are detail-only — a plain
        // upsert would null them out, mirroring RoomDocumentCache's local_*
        // preservation).
        val existing = dao.getByIds(connectionId, exams.map { it.id }).associateBy { it.id }
        dao.upsertAll(exams.map { it.toEntity(connectionId, existing[it.id]) })
    }

    override suspend fun storeAllSynced(
        exams: List<ExaminationSummary>,
        meta: CacheRefreshMeta,
    ) {
        if (exams.isEmpty()) return recordSuccess(meta)
        val existing = dao.getByIds(connectionId, exams.map { it.id }).associateBy { it.id }
        db.withTransaction {
            dao.upsertAll(exams.map { it.toEntity(connectionId, existing[it.id]) })
            db.cacheMetaDao().recordSuccess(connectionId, meta.domain.wire, meta.successAtEpochMs ?: 0L, dao.rowCount(connectionId))
        }
    }

    override suspend fun reconcile(ids: List<String>) {
        // An empty id list means the snapshot is empty — Room would bind an
        // empty collection as `NOT IN ()`, which SQLite rejects; clear instead.
        if (ids.isEmpty()) dao.clearAll(connectionId) else dao.reconcile(connectionId, ids)
    }

    override suspend fun delete(id: String) = dao.delete(connectionId, id)

    override fun observeAll(): Flow<List<ExaminationSummary>> = dao.observeAll(connectionId).map { rows -> rows.map { it.toSummary() } }

    override fun observeById(id: String): Flow<ExaminationSummary?> = dao.observeById(connectionId, id).map { row -> row?.toSummary() }

    override suspend fun applyDeltas(rows: List<ExaminationDeltaRow>) {
        if (rows.isEmpty()) return
        val existing = dao.getByIds(connectionId, rows.map { it.id }).associateBy { it.id }
        dao.upsertAll(rows.map { DeltaRows.merge(existing[it.id]?.toSummary(), it).toEntity(connectionId, existing[it.id]) })
    }

    override suspend fun clear() = dao.clearAll(connectionId)

    private suspend fun recordSuccess(meta: CacheRefreshMeta) {
        db.cacheMetaDao().recordSuccess(connectionId, meta.domain.wire, meta.successAtEpochMs ?: 0L, dao.rowCount(connectionId))
    }
}

class RoomDocumentCache(
    private val db: ObservationDatabase,
    private val connectionId: String,
) : DocumentCache {
    private val dao get() = db.documentDao()

    override suspend fun storeAll(docs: List<DocumentSummary>) {
        if (docs.isEmpty()) return
        val existing = dao.getByIds(connectionId, docs.map { it.id }).associateBy { it.id }
        dao.upsertAll(docs.map { it.toEntity(connectionId, existing[it.id]) })
    }

    override suspend fun storeAllSynced(
        docs: List<DocumentSummary>,
        meta: CacheRefreshMeta,
    ) {
        if (docs.isEmpty()) return recordSuccess(meta)
        val existing = dao.getByIds(connectionId, docs.map { it.id }).associateBy { it.id }
        db.withTransaction {
            dao.upsertAll(docs.map { it.toEntity(connectionId, existing[it.id]) })
            db.cacheMetaDao().recordSuccess(connectionId, meta.domain.wire, meta.successAtEpochMs ?: 0L, dao.rowCount(connectionId))
        }
    }

    override suspend fun reconcileForExam(
        examId: String,
        ids: List<String>,
    ) {
        // Guard the empty-collection bind (see RoomExaminationCache.reconcile).
        if (ids.isEmpty()) dao.reconcileForExamEmpty(connectionId, examId) else dao.reconcileForExam(connectionId, examId, ids)
    }

    override suspend fun delete(id: String) = dao.delete(connectionId, id)

    override fun observeForExam(examId: String): Flow<List<DocumentSummary>> =
        dao.observeForExam(connectionId, examId).map { rows -> rows.map { it.toSummary() } }

    override suspend fun setLocalManifest(
        docId: String,
        localPath: String,
        cachedAtEpochMs: Long,
    ) = dao.setLocalManifest(connectionId, docId, localPath, cachedAtEpochMs)

    override suspend fun clearLocalManifest(docId: String) = dao.clearLocalManifest(connectionId, docId)

    override suspend fun applyDeltas(rows: List<DocumentDeltaRow>) {
        if (rows.isEmpty()) return
        val existing = dao.getByIds(connectionId, rows.map { it.id }).associateBy { it.id }
        val merged =
            rows.map { delta ->
                val cached = existing[delta.id]
                DeltaRows
                    .merge(
                        cached?.let {
                            io.healthassistant.shared.data.cache.CachedDocumentRow(
                                id = it.id,
                                filename = it.filename,
                                status = it.status,
                                progress = it.progress,
                                externalId = it.externalId,
                                createdAt = it.createdAt,
                                contentType = it.contentType,
                                fileSize = it.fileSize,
                                examinationId = it.examinationId,
                                localPath = it.localPath,
                                localCachedAt = it.localCachedAt,
                            )
                        },
                        delta,
                    ).let { mergedRow ->
                        CachedDocument(
                            connectionId = connectionId,
                            id = mergedRow.id,
                            filename = mergedRow.filename,
                            status = mergedRow.status,
                            progress = mergedRow.progress,
                            externalId = mergedRow.externalId,
                            createdAt = mergedRow.createdAt,
                            contentType = mergedRow.contentType,
                            fileSize = mergedRow.fileSize,
                            examinationId = mergedRow.examinationId,
                            localPath = mergedRow.localPath,
                            localCachedAt = mergedRow.localCachedAt,
                        )
                    }
            }
        dao.upsertAll(merged)
    }

    override suspend fun reconcileAll(ids: List<String>) {
        if (ids.isEmpty()) dao.clearAll(connectionId) else dao.reconcileAll(connectionId, ids)
    }

    override suspend fun clear() = dao.clearAll(connectionId)

    private suspend fun recordSuccess(meta: CacheRefreshMeta) {
        db.cacheMetaDao().recordSuccess(connectionId, meta.domain.wire, meta.successAtEpochMs ?: 0L, dao.rowCount(connectionId))
    }
}

/**
 * Cross-connection [DocumentCache] façade for the shared document byte store
 * (offline-first M9): the on-disk `ha_docs` LRU is connection-agnostic, so its
 * bookkeeping — manifest clears after an eviction, the "clear downloaded
 * documents" action — must reach rows of EVERY connection. Only the manifest
 * surface is implemented; the metadata read/store paths are unreachable from
 * its callers (the evictor + the Settings clear action) and throw to prove it.
 */
class RoomDocumentManifestCache(
    private val dao: DocumentCacheDao,
) : DocumentCache {
    override suspend fun storeAll(docs: List<DocumentSummary>): Unit = unsupported()

    override suspend fun storeAllSynced(
        docs: List<DocumentSummary>,
        meta: CacheRefreshMeta,
    ): Unit = unsupported()

    override suspend fun reconcileForExam(
        examId: String,
        ids: List<String>,
    ): Unit = unsupported()

    override suspend fun delete(id: String): Unit = unsupported()

    override fun observeForExam(examId: String): Flow<List<DocumentSummary>> = unsupported()

    override suspend fun setLocalManifest(
        docId: String,
        localPath: String,
        cachedAtEpochMs: Long,
    ): Unit = unsupported()

    override suspend fun clearLocalManifest(docId: String) = dao.clearLocalManifestAllConnections(docId)

    override suspend fun applyDeltas(rows: List<DocumentDeltaRow>): Unit = unsupported()

    override suspend fun reconcileAll(ids: List<String>): Unit = unsupported()

    override suspend fun clear() = dao.clearAllLocalManifests()

    private fun unsupported(): Nothing = error("the manifest cache only manages byte-store bookkeeping")
}
