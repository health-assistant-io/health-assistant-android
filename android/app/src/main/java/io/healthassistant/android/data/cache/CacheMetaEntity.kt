package io.healthassistant.android.data.cache

import androidx.room.ColumnInfo
import androidx.room.Entity
import io.healthassistant.shared.data.cache.CacheDomain
import io.healthassistant.shared.data.cache.CacheMetaState

/**
 * The `cache_meta` staleness row (offline-first M9, plan §3.3): one row per
 * (connection, domain) recording when that domain's cache last refreshed
 * successfully, the error of the most recent failed attempt (null when the
 * last attempt succeeded), and the row count at the last success. The UI's
 * staleness chip + the Settings "Data & storage" screen read it back through
 * [CacheMetaDao]; the domain caches write the success row in the same Room
 * transaction as their upserts.
 */
@Entity(
    tableName = "cache_meta",
    primaryKeys = ["connection_id", "domain", "filter_key"],
)
data class CacheMetaEntity(
    @ColumnInfo(name = "connection_id") val connectionId: String,
    val domain: String,
    @ColumnInfo(name = "filter_key", defaultValue = "") val filterKey: String = "",
    @ColumnInfo(name = "last_success_at", defaultValue = "0") val lastSuccessAtEpochMs: Long = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "row_count", defaultValue = "0") val rowCount: Int = 0,
)

/** Entity → the shared read model; an unknown domain wire value decodes to
 *  null (the enum may grow between app versions). */
fun CacheMetaEntity.toState(): CacheMetaState? {
    val domain = CacheDomain.fromWire(domain) ?: return null
    return CacheMetaState(
        domain = domain,
        lastSuccessAtEpochMs = lastSuccessAtEpochMs,
        lastError = lastError,
        rowCount = rowCount,
    )
}
