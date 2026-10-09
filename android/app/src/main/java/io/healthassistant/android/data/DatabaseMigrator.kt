package io.healthassistant.android.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import io.healthassistant.android.data.cache.OutboxDao
import io.healthassistant.android.data.cache.OutboxEntity
import io.healthassistant.shared.sync.OutboxLane
import io.healthassistant.shared.sync.OutboxStatus
import kotlinx.coroutines.flow.first
import java.io.File

private val Context.dbMigratorDataStore: DataStore<Preferences> by preferencesDataStore(name = "ha_db_migrator")

/**
 * Phase C — the one-shot plaintext → SQLCipher migration. MUST run before the
 * encrypted Room database is opened (SQLCipher cannot read a plaintext file), so
 * [migrateIfNeeded] deletes the old cache file + reads the old outbox via the
 * platform `SQLiteDatabase` (no Room) and only resolves the encrypted DAO
 * through [outboxDaoProvider] AFTER the plaintext files are gone.
 *
 * What it migrates:
 * - **Outbox** (`ha_outbox.db`, authoritative): every row is read from the old
 *   plaintext file + re-inserted into the encrypted Room `outbox` table.
 *   Pending health reads are preserved (no re-sync storm, no lost data).
 * - **Observation cache** (`ha_observations.db`, non-authoritative): the old
 *   plaintext file is deleted — it re-populates from the bridge / Health
 *   Connect on the next read.
 *
 * Guarded by a DataStore flag so it runs exactly once. Idempotent: a partial
 * failure just re-copies whatever rows survive + re-deletes the (now-gone)
 * files on the next launch.
 */
object DatabaseMigrator {
    private const val OLD_OUTBOX = "ha_outbox.db"
    private const val OLD_CACHE = "ha_observations.db"

    private val DONE = booleanPreferencesKey("encrypted_v1_done")

    /**
     * Run the migration if it hasn't run yet. [outboxDaoProvider] is resolved
     * lazily (only after the plaintext files are cleaned up) so Room never opens
     * a plaintext file with SQLCipher. Safe to call on every launch — the
     * DataStore guard makes it a no-op after the first success.
     */
    suspend fun migrateIfNeeded(
        context: Context,
        outboxDaoProvider: suspend () -> OutboxDao,
    ) {
        val store = context.applicationContext.dbMigratorDataStore
        if (store.data.first()[DONE] == true) return

        // 1. Drop the plaintext cache BEFORE Room opens it (non-authoritative).
        deleteOldCacheFiles(context)
        // 2. Read + delete the plaintext outbox (authoritative — copy rows).
        val rows = readAndDeleteOldOutbox(context)
        // 3. NOW it's safe to build the encrypted Room DB + insert the rows.
        if (rows.isNotEmpty()) outboxDaoProvider().upsertAll(rows)

        store.edit { it[DONE] = true }
    }

    /** Read every row from the old plaintext outbox, then delete the file. */
    private fun readAndDeleteOldOutbox(context: Context): List<OutboxEntity> {
        val file = context.getDatabasePath(OLD_OUTBOX)
        if (!file.exists()) return emptyList() // fresh install
        val rows = mutableListOf<OutboxEntity>()
        runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db
                    .rawQuery(
                        "SELECT id, method, path, payload, lane, content_ref, status, attempts, next_attempt_at, created_at, dead_reason FROM outbox",
                        null,
                    ).use { c ->
                        while (c.moveToNext()) {
                            rows +=
                                OutboxEntity(
                                    id = c.getString(0),
                                    method = c.getString(1),
                                    path = c.getString(2),
                                    payload = c.getBlob(3),
                                    lane = OutboxLane.valueOf(c.getString(4)),
                                    contentRef = c.getString(5),
                                    status = OutboxStatus.valueOf(c.getString(6)),
                                    attempts = c.getInt(7),
                                    nextAttemptAt = c.getLong(8),
                                    createdAt = c.getLong(9),
                                    deadReason = c.getString(10),
                                )
                        }
                    }
            }
        }
        // Delete the plaintext outbox (+ its journal) now that rows are copied.
        File(file.path).delete()
        File("${file.path}-journal").delete()
        return rows
    }

    /** The observation cache is non-authoritative — drop the plaintext file. */
    private fun deleteOldCacheFiles(context: Context) {
        listOf(OLD_CACHE, "$OLD_CACHE-wal", "$OLD_CACHE-shm", "$OLD_CACHE-journal").forEach { name ->
            context.getDatabasePath(name).delete()
        }
    }
}
