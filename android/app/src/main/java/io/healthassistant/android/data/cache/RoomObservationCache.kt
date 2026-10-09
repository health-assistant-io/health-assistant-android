package io.healthassistant.android.data.cache

import androidx.room.withTransaction
import io.healthassistant.shared.data.ObservationPoint
import io.healthassistant.shared.data.cache.CacheMapper
import io.healthassistant.shared.data.cache.CacheRefreshMeta
import io.healthassistant.shared.data.cache.ObservationCache
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Android Room implementation of the shared [ObservationCache] (M8), bound to
 * one bridge connection (offline-first M9): every read + write is scoped by
 * the connection id, so two saved patients sharing a biomarker code — or even
 * an observation id — can never see each other's rows.
 *
 * Writes drop unaddressable points (no biomarker code) — see [CacheMapper.toCached].
 * Reads are pure transforms over the DAO's reactive [Flow]s. A `*Synced` write
 * records the cache_meta success row in the SAME Room transaction as the
 * upsert (plan §10: a crash between the two must not be possible).
 */
class RoomObservationCache(
    private val db: ObservationDatabase,
    private val connectionId: String,
) : ObservationCache {
    private val dao get() = db.cacheDao()

    override suspend fun store(points: List<ObservationPoint>) {
        val rows = points.mapNotNull(CacheMapper::toCached).map { it.toEntity(connectionId) }
        if (rows.isNotEmpty()) dao.upsertAll(rows)
    }

    override suspend fun storeSynced(
        points: List<ObservationPoint>,
        meta: CacheRefreshMeta,
    ) {
        val rows = points.mapNotNull(CacheMapper::toCached).map { it.toEntity(connectionId) }
        db.withTransaction {
            if (rows.isNotEmpty()) dao.upsertAll(rows)
            db.cacheMetaDao().recordSuccess(connectionId, meta.domain.wire, meta.successAtEpochMs ?: 0L, dao.rowCount(connectionId))
        }
    }

    override fun seriesFor(
        code: String,
        sinceMs: Long?,
        untilMs: Long?,
        limit: Int,
    ): Flow<List<ObservationPoint>> =
        dao
            .seriesFor(connectionId, code, sinceMs, untilMs, limit)
            .map { rows -> rows.map { CacheMapper.toPoint(it.toRow()) } }

    override fun latestPerBiomarker(limit: Int): Flow<List<ObservationPoint>> =
        dao
            .latestPerBiomarker(connectionId, limit)
            .map { rows -> rows.map { CacheMapper.toPoint(it.toRow()) } }

    override fun latestForCode(code: String): Flow<ObservationPoint?> =
        dao.latestForCode(connectionId, code).map { row -> row?.let { CacheMapper.toPoint(it.toRow()) } }

    override fun previousForCode(code: String): Flow<ObservationPoint?> =
        dao.previousForCode(connectionId, code).map { row -> row?.let { CacheMapper.toPoint(it.toRow()) } }

    override fun previousPerBiomarker(limit: Int): Flow<List<ObservationPoint>> =
        dao.previousPerBiomarker(connectionId, limit).map { rows -> rows.map { CacheMapper.toPoint(it.toRow()) } }

    override suspend fun clearForCode(code: String) = dao.clearForCode(connectionId, code)

    override suspend fun clear() = dao.clearAll(connectionId)
}
