package io.healthassistant.android.data.cache

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for the document-metadata cache (offline-first M3 + the M7 delta
 * hydration). Writes upsert by the (connection, id) primary key — the impl
 * layer preserves the (M4) `local_*` manifest columns across a metadata
 * refresh by reading existing rows first. [reconcileForExam] drops only the
 * given exam's vanished rows (of one connection); [reconcileAll] is the
 * connection-wide counterpart for the full re-snapshot.
 *
 * The two `*AllConnections` manifest methods are deliberately UNSCOPED: the
 * document byte store is a single shared on-disk LRU, so its bookkeeping
 * (eviction, "clear downloaded documents") must reach rows of every
 * connection.
 */
@Dao
interface DocumentCacheDao {
    @Query("SELECT * FROM document_cache WHERE connection_id = :connectionId AND id IN (:ids)")
    suspend fun getByIds(
        connectionId: String,
        ids: List<String>,
    ): List<CachedDocument>

    @Upsert
    suspend fun upsertAll(rows: List<CachedDocument>)

    @Query(
        """
        DELETE FROM document_cache
        WHERE connection_id = :connectionId AND examination_id = :examId AND id NOT IN (:ids)
        """,
    )
    suspend fun reconcileForExam(
        connectionId: String,
        examId: String,
        ids: List<String>,
    )

    /** [reconcileForExam] for an empty snapshot (avoids binding an empty
     *  collection as `NOT IN ()`, which SQLite rejects). */
    @Query("DELETE FROM document_cache WHERE connection_id = :connectionId AND examination_id = :examId")
    suspend fun reconcileForExamEmpty(
        connectionId: String,
        examId: String,
    )

    @Query("DELETE FROM document_cache WHERE connection_id = :connectionId AND id NOT IN (:ids)")
    suspend fun reconcileAll(
        connectionId: String,
        ids: List<String>,
    )

    @Query(
        """
        UPDATE document_cache
        SET local_path = :localPath, local_cached_at = :cachedAtEpochMs
        WHERE connection_id = :connectionId AND id = :docId
        """,
    )
    suspend fun setLocalManifest(
        connectionId: String,
        docId: String,
        localPath: String,
        cachedAtEpochMs: Long,
    )

    @Query("UPDATE document_cache SET local_path = NULL, local_cached_at = NULL WHERE connection_id = :connectionId AND id = :docId")
    suspend fun clearLocalManifest(
        connectionId: String,
        docId: String,
    )

    /** Byte-store bookkeeping (eviction): clear [docId]'s manifest across ALL
     *  connections — the shared on-disk file for that id is gone. */
    @Query("UPDATE document_cache SET local_path = NULL, local_cached_at = NULL WHERE id = :docId")
    suspend fun clearLocalManifestAllConnections(docId: String)

    /** Byte-store bookkeeping ("clear downloaded documents"): drop every
     *  manifest pointer, every connection — the disk store was emptied. */
    @Query("UPDATE document_cache SET local_path = NULL, local_cached_at = NULL")
    suspend fun clearAllLocalManifests()

    @Query("DELETE FROM document_cache WHERE connection_id = :connectionId AND id = :id")
    suspend fun delete(
        connectionId: String,
        id: String,
    )

    @Query(
        """
        SELECT * FROM document_cache
        WHERE connection_id = :connectionId AND examination_id = :examId
        ORDER BY created_at DESC, rowid DESC
        """,
    )
    fun observeForExam(
        connectionId: String,
        examId: String,
    ): Flow<List<CachedDocument>>

    @Query("DELETE FROM document_cache WHERE connection_id = :connectionId")
    suspend fun clearAll(connectionId: String)

    @Query("SELECT COUNT(*) FROM document_cache WHERE connection_id = :connectionId")
    suspend fun rowCount(connectionId: String): Int

    @Query("SELECT COUNT(*) FROM document_cache WHERE connection_id = :connectionId AND local_path IS NOT NULL")
    suspend fun downloadedCount(connectionId: String): Int
}
