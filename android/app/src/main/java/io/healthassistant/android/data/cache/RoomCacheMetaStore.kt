package io.healthassistant.android.data.cache

import io.healthassistant.shared.data.cache.BiomarkerCache
import io.healthassistant.shared.data.cache.CacheDomain
import io.healthassistant.shared.data.cache.CacheMetaState
import io.healthassistant.shared.data.cache.CacheMetaStore
import io.healthassistant.shared.data.cache.CacheRefreshMeta
import io.healthassistant.shared.data.cache.ClinicalRecordCache
import io.healthassistant.shared.data.cache.DocumentCache
import io.healthassistant.shared.data.cache.ExaminationCache
import io.healthassistant.shared.data.cache.NotificationCache
import io.healthassistant.shared.data.cache.ObservationCache
import kotlinx.coroutines.flow.Flow

/**
 * Android Room implementation of the shared [CacheMetaStore] (offline-first
 * M9), bound to one bridge connection: the staleness rows it reads and writes
 * belong to that connection only, so a chip can never describe another
 * patient's cache. [recordRefresh] is the standalone (failure) path — success
 * rows are written by the domain caches inside their own write transactions.
 */
class RoomCacheMetaStore(
    private val dao: CacheMetaDao,
    private val connectionId: String,
) : CacheMetaStore {
    override suspend fun recordRefresh(meta: CacheRefreshMeta) =
        dao.record(
            connectionId = connectionId,
            domain = meta.domain.wire,
            successAtEpochMs = meta.successAtEpochMs,
            error = meta.error,
            rowCount = null,
        )

    override fun observe(domain: CacheDomain): Flow<CacheMetaState?> = dao.observeState(connectionId, domain.wire)

    override fun observeAll(): Flow<List<CacheMetaState>> = dao.observeStates(connectionId)

    override suspend fun clearDomain(domain: CacheDomain) = dao.clearDomain(connectionId, domain.wire)

    override suspend fun clear() = dao.clear(connectionId)
}

/**
 * The per-connection cache bundle (offline-first M9). One instance binds every
 * shared cache interface + the meta store to a single bridge connection id;
 * the Routes + workers build it `remember(client)`-style next to the
 * per-connection gateways, so a connection switch swaps the whole cache scope
 * atomically and no screen can mix two patients' caches. The Room database
 * handle itself stays a Koin singleton — one encrypted file, many scopes.
 */
class RoomCaches(
    private val db: ObservationDatabase,
    val connectionId: String,
) {
    val meta: CacheMetaStore by lazy { RoomCacheMetaStore(db.cacheMetaDao(), connectionId) }

    val observations: ObservationCache by lazy { RoomObservationCache(db, connectionId) }

    val biomarkers: BiomarkerCache by lazy { RoomBiomarkerCache(db, connectionId) }

    val examinations: ExaminationCache by lazy { RoomExaminationCache(db, connectionId) }

    val documents: DocumentCache by lazy { RoomDocumentCache(db, connectionId) }

    val records: ClinicalRecordCache by lazy { RoomClinicalRecordCache(db, connectionId) }

    val notifications: NotificationCache by lazy { RoomNotificationCache(db, connectionId) }
}
