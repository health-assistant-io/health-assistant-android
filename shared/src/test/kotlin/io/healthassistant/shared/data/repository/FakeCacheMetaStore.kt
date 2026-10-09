package io.healthassistant.shared.data.repository

import io.healthassistant.shared.data.cache.CacheDomain
import io.healthassistant.shared.data.cache.CacheMetaState
import io.healthassistant.shared.data.cache.CacheMetaStore
import io.healthassistant.shared.data.cache.CacheRefreshMeta
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [CacheMetaStore] for the repository JVM tests (offline-first M9):
 * mirrors the Room semantics — a success stamps the timestamp + clears the
 * error, a failure keeps the last success — so the tests can assert what the
 * staleness chip will render.
 */
class FakeCacheMetaStore : CacheMetaStore {
    private val rows = MutableStateFlow<Map<CacheDomain, CacheMetaState>>(emptyMap())
    val recorded = mutableListOf<CacheRefreshMeta>()

    override suspend fun recordRefresh(meta: CacheRefreshMeta) {
        recorded += meta
        if (meta.successAtEpochMs != null) {
            rows.value = rows.value + (meta.domain to CacheMetaState(meta.domain, meta.successAtEpochMs, null, rows.value[meta.domain]?.rowCount ?: 0))
        } else {
            val existing = rows.value[meta.domain] ?: CacheMetaState(meta.domain, 0, null, 0)
            rows.value = rows.value + (meta.domain to existing.copy(lastError = meta.error))
        }
    }

    fun recordSuccess(
        domain: CacheDomain,
        atEpochMs: Long,
        rowCount: Int,
    ) {
        rows.value = rows.value + (domain to CacheMetaState(domain, atEpochMs, null, rowCount))
    }

    override fun observe(domain: CacheDomain): Flow<CacheMetaState?> = rows.map { it[domain] }

    override fun observeAll(): Flow<List<CacheMetaState>> = rows.map { it.values.sortedBy { state -> state.domain.wire } }

    override suspend fun clearDomain(domain: CacheDomain) {
        rows.value = rows.value - domain
    }

    override suspend fun clear() {
        rows.value = emptyMap()
    }
}
