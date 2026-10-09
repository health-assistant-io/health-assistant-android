package io.healthassistant.android.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.healthassistant.shared.sync.DeadLetterPreview
import io.healthassistant.shared.sync.OutboxItem
import io.healthassistant.shared.sync.OutboxLane
import io.healthassistant.shared.sync.OutboxStatus
import io.healthassistant.shared.sync.OutboxStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Persistent Android [OutboxStore] backed by SQLite (v1; Room/SQLDelight is the
 * production upgrade path — plan §2.2). Implements the shared interface the
 * [io.healthassistant.shared.sync.SyncCoordinator] drains. All ops run on IO.
 */
class SqliteOutboxStore(
    context: Context,
) : SQLiteOpenHelper(context, DB, null, VERSION),
    OutboxStore {
    init {
        writableDatabase // force onCreate
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE outbox (
              id TEXT PRIMARY KEY,
              method TEXT NOT NULL,
              path TEXT NOT NULL,
              payload BLOB NOT NULL,
              lane TEXT NOT NULL,
              content_ref TEXT,
              status TEXT NOT NULL,
              attempts INTEGER NOT NULL,
              next_attempt_at INTEGER NOT NULL,
              created_at INTEGER NOT NULL,
              dead_reason TEXT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_outbox_claim ON outbox(lane, status, next_attempt_at)")
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        db.execSQL("DROP TABLE IF EXISTS outbox")
        onCreate(db)
    }

    override suspend fun enqueue(item: OutboxItem) {
        withContext(Dispatchers.IO) {
            writableDatabase.insertWithOnConflict("outbox", null, item.toCV(), SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    override suspend fun get(id: String): OutboxItem? =
        withContext(Dispatchers.IO) {
            readableDatabase.query("outbox", null, "id=?", arrayOf(id), null, null, null).use { c ->
                if (c.moveToFirst()) c.toItem() else null
            }
        }

    override suspend fun claim(
        lane: OutboxLane,
        limit: Int,
        now: Long,
    ): List<OutboxItem> =
        withContext(Dispatchers.IO) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val items = mutableListOf<OutboxItem>()
                readableDatabase
                    .query(
                        "outbox",
                        null,
                        "lane=? AND status=? AND next_attempt_at<=?",
                        arrayOf(lane.name, OutboxStatus.PENDING.name, now.toString()),
                        null,
                        null,
                        "created_at ASC",
                        limit.toString(),
                    ).use { c ->
                        while (c.moveToNext()) items += c.toItem().copy(status = OutboxStatus.IN_FLIGHT)
                    }
                items.forEach {
                    db.execSQL(
                        "UPDATE outbox SET status=? WHERE id=?",
                        arrayOf(OutboxStatus.IN_FLIGHT.name, it.id),
                    )
                }
                db.setTransactionSuccessful()
                items
            } finally {
                db.endTransaction()
            }
        }

    override suspend fun release(
        ids: List<String>,
        nextAttemptAt: Long,
    ) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach {
                db.execSQL(
                    "UPDATE outbox SET status=?, next_attempt_at=? WHERE id=?",
                    arrayOf(OutboxStatus.PENDING.name, nextAttemptAt.toString(), it),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun markSynced(ids: List<String>) =
        withContext(Dispatchers.IO) {
            val db = writableDatabase
            ids.forEach { db.delete("outbox", "id=?", arrayOf(it)) }
        }

    override suspend fun markRetry(
        id: String,
        nextAttemptAt: Long,
    ) = withContext(Dispatchers.IO) {
        writableDatabase.execSQL(
            "UPDATE outbox SET status=?, next_attempt_at=?, attempts=attempts+1 WHERE id=?",
            arrayOf<Any?>(OutboxStatus.PENDING.name, nextAttemptAt, id),
        )
    }

    override suspend fun markDead(
        id: String,
        reason: String,
    ) {
        withContext(Dispatchers.IO) {
            val cv =
                ContentValues().apply {
                    put("status", OutboxStatus.DEAD_LETTER.name)
                    put("dead_reason", reason)
                }
            writableDatabase.update("outbox", cv, "id=?", arrayOf(id))
        }
    }

    override suspend fun revive(id: String) =
        withContext(Dispatchers.IO) {
            writableDatabase.execSQL(
                "UPDATE outbox SET status=?, attempts=0, next_attempt_at=0, dead_reason=NULL WHERE id=?",
                arrayOf<Any?>(OutboxStatus.PENDING.name, id),
            )
        }

    override suspend fun clear() =
        withContext(Dispatchers.IO) {
            writableDatabase.delete("outbox", null, null)
            Unit
        }

    override suspend fun pendingCount(lane: OutboxLane): Int =
        withContext(Dispatchers.IO) {
            readableDatabase
                .rawQuery(
                    "SELECT COUNT(*) FROM outbox WHERE lane=? AND status=?",
                    arrayOf(lane.name, OutboxStatus.PENDING.name),
                ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        }

    override suspend fun deadLetters(): List<OutboxItem> =
        withContext(Dispatchers.IO) {
            readableDatabase.query("outbox", null, "status=?", arrayOf(OutboxStatus.DEAD_LETTER.name), null, null, null).use { c ->
                val list = mutableListOf<OutboxItem>()
                while (c.moveToNext()) list += c.toItem()
                list
            }
        }

    override suspend fun deadLetterCount(): Int =
        withContext(Dispatchers.IO) {
            readableDatabase
                .rawQuery(
                    "SELECT COUNT(*) FROM outbox WHERE status=?",
                    arrayOf(OutboxStatus.DEAD_LETTER.name),
                ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        }

    override suspend fun deadLetterPreviews(limit: Int): List<DeadLetterPreview> =
        withContext(Dispatchers.IO) {
            readableDatabase
                .query(
                    "outbox",
                    arrayOf("id", "method", "path", "attempts", "dead_reason"),
                    "status=?",
                    arrayOf(OutboxStatus.DEAD_LETTER.name),
                    null,
                    null,
                    "created_at ASC",
                    limit.toString(),
                ).use { c ->
                    val list = mutableListOf<DeadLetterPreview>()
                    while (c.moveToNext()) {
                        list +=
                            DeadLetterPreview(
                                id = c.getString(0),
                                method = c.getString(1),
                                path = c.getString(2),
                                attempts = c.getInt(3),
                                deadReason = c.getString(4),
                            )
                    }
                    list
                }
        }

    override suspend fun reviveAll(): Int =
        withContext(Dispatchers.IO) {
            val revived = deadLetterCount()
            writableDatabase.execSQL(
                "UPDATE outbox SET status=?, attempts=0, next_attempt_at=0, dead_reason=NULL WHERE status=?",
                arrayOf<Any?>(OutboxStatus.PENDING.name, OutboxStatus.DEAD_LETTER.name),
            )
            revived
        }

    private fun OutboxItem.toCV() =
        ContentValues().apply {
            put("id", id)
            put("method", method)
            put("path", path)
            put("payload", payload)
            put("lane", lane.name)
            put("content_ref", contentRef)
            put("status", status.name)
            put("attempts", attempts)
            put("next_attempt_at", nextAttemptAt)
            put("created_at", createdAt)
            put("dead_reason", deadReason)
        }

    private fun Cursor.toItem() =
        OutboxItem(
            id = getString(getColumnIndexOrThrow("id")),
            method = getString(getColumnIndexOrThrow("method")),
            path = getString(getColumnIndexOrThrow("path")),
            payload = getBlob(getColumnIndexOrThrow("payload")),
            lane = OutboxLane.valueOf(getString(getColumnIndexOrThrow("lane"))),
            contentRef = getString(getColumnIndexOrThrow("content_ref")),
            status = OutboxStatus.valueOf(getString(getColumnIndexOrThrow("status"))),
            attempts = getInt(getColumnIndexOrThrow("attempts")),
            nextAttemptAt = getLong(getColumnIndexOrThrow("next_attempt_at")),
            createdAt = getLong(getColumnIndexOrThrow("created_at")),
            deadReason = getString(getColumnIndexOrThrow("dead_reason")),
        )

    private companion object {
        const val DB = "ha_outbox.db"
        const val VERSION = 1
    }
}
