package io.healthassistant.android.data.cache

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import io.healthassistant.shared.data.DocumentSummary

/**
 * Room entity mirroring [DocumentSummary] (offline-first M3) plus the (M4)
 * offline byte-manifest columns — nullable and unused until the document byte
 * cache lands, but declared now so M4 needs no second migration.
 *
 * Offline-first M9: rows are scoped by `connection_id` (part of the primary
 * key + the exam index).
 */
@Entity(
    tableName = "document_cache",
    primaryKeys = ["connection_id", "id"],
    indices = [Index(value = ["connection_id", "examination_id", "created_at"])],
)
data class CachedDocument(
    @ColumnInfo(name = "connection_id") val connectionId: String,
    val id: String,
    val filename: String?,
    val status: String?,
    val progress: Double?,
    @ColumnInfo(name = "external_id") val externalId: String?,
    @ColumnInfo(name = "created_at") val createdAt: String?,
    @ColumnInfo(name = "content_type") val contentType: String?,
    @ColumnInfo(name = "file_size") val fileSize: Long?,
    @ColumnInfo(name = "examination_id") val examinationId: String?,
    @ColumnInfo(name = "local_path") val localPath: String?,
    @ColumnInfo(name = "local_cached_at") val localCachedAt: Long?,
)

/** Entity ⇄ the shared read model. */
fun CachedDocument.toSummary(): DocumentSummary =
    DocumentSummary(
        id = id,
        filename = filename,
        status = status,
        progress = progress,
        externalId = externalId,
        createdAt = createdAt,
        contentType = contentType,
        fileSize = fileSize,
        examinationId = examinationId,
    )

/** Entity ⇄ the shared read model. */
fun DocumentSummary.toEntity(
    connectionId: String,
    existing: CachedDocument? = null,
): CachedDocument =
    CachedDocument(
        connectionId = connectionId,
        id = id,
        filename = filename,
        status = status,
        progress = progress,
        externalId = externalId,
        createdAt = createdAt,
        contentType = contentType,
        fileSize = fileSize,
        examinationId = examinationId,
        localPath = existing?.localPath,
        localCachedAt = existing?.localCachedAt,
    )
