package io.healthassistant.android.data.cache

import androidx.room.withTransaction
import io.healthassistant.shared.data.BiomarkerSummary
import io.healthassistant.shared.data.cache.BiomarkerCache
import io.healthassistant.shared.data.cache.CacheRefreshMeta
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Android Room implementation of the shared [BiomarkerCache] (offline-first
 * M2), bound to one bridge connection (offline-first M9): the atomic snapshot
 * swap + every read are scoped by the connection id, so each patient sees only
 * their instance's catalog. A `*Synced` swap records the cache_meta success
 * row in the SAME Room transaction.
 */
class RoomBiomarkerCache(
    private val db: ObservationDatabase,
    private val connectionId: String,
) : BiomarkerCache {
    private val dao get() = db.biomarkerDao()

    override suspend fun replaceAll(catalog: List<BiomarkerSummary>) =
        dao.replaceAll(connectionId, catalog.map { it.toEntity(connectionId) })

    override suspend fun replaceAllSynced(
        catalog: List<BiomarkerSummary>,
        meta: CacheRefreshMeta,
    ) {
        db.withTransaction {
            dao.replaceAll(connectionId, catalog.map { it.toEntity(connectionId) })
            db.cacheMetaDao().recordSuccess(connectionId, meta.domain.wire, meta.successAtEpochMs ?: 0L, dao.rowCount(connectionId))
        }
    }

    override fun observeAll(): Flow<List<BiomarkerSummary>> = dao.observeAll(connectionId).map { rows -> rows.map { it.toSummary() } }

    override fun observeByCode(code: String): Flow<BiomarkerSummary?> =
        dao.observeByCode(connectionId, code).map { row -> row?.toSummary() }

    override suspend fun clear() = dao.clear(connectionId)
}
