package io.healthassistant.android.data

import io.healthassistant.android.data.cache.OutboxDao
import io.healthassistant.android.data.cache.toEntity
import io.healthassistant.android.data.cache.toItem
import io.healthassistant.shared.sync.DeadLetterPreview
import io.healthassistant.shared.sync.OutboxItem
import io.healthassistant.shared.sync.OutboxLane
import io.healthassistant.shared.sync.OutboxStatus
import io.healthassistant.shared.sync.OutboxStore

/**
 * Phase C — the production [OutboxStore] backed by the SQLCipher-encrypted Room
 * database (replaces the plaintext hand-rolled `SqliteOutboxStore`). Implements
 * the exact same op semantics so the shared [io.healthassistant.shared.sync.SyncCoordinator]
 * drains identically:
 *
 * - `claim` flips PENDING → IN_FLIGHT inside the read (a transaction-wrapped
 *   variant lives in the DAO; here the candidates are read, then bulk-statused,
 *   which is atomic enough for a single-writer drain).
 * - `markRetry` advances `attempts` + backoff; `markDead`/`revive` flip state;
 *   `pendingCount`/`deadLetters` back the Sync screen.
 *
 * The one-shot plaintext→encrypted migration ([migrateFromPlaintextIfNeeded])
 * runs before this store is handed to the sync engine.
 */
class RoomOutboxStore(
    private val dao: OutboxDao,
) : OutboxStore {
    override suspend fun enqueue(item: OutboxItem) = dao.upsert(item.toEntity())

    override suspend fun get(id: String): OutboxItem? = dao.get(id)?.toItem()

    override suspend fun claim(
        lane: OutboxLane,
        limit: Int,
        now: Long,
    ): List<OutboxItem> {
        val candidates =
            dao.claimCandidates(lane, OutboxStatus.PENDING, now, limit)
        if (candidates.isEmpty()) return emptyList()
        // Flip PENDING → IN_FLIGHT; return the items as IN_FLIGHT (matches the
        // old store, which returned the in-flight copy).
        candidates.forEach { dao.setStatus(it.id, OutboxStatus.IN_FLIGHT) }
        return candidates.map { it.copy(status = OutboxStatus.IN_FLIGHT).toItem() }
    }

    override suspend fun release(
        ids: List<String>,
        nextAttemptAt: Long,
    ) {
        if (ids.isEmpty()) return
        dao.release(ids, OutboxStatus.PENDING, nextAttemptAt)
    }

    override suspend fun markSynced(ids: List<String>) {
        ids.forEach { dao.delete(it) }
    }

    override suspend fun markRetry(
        id: String,
        nextAttemptAt: Long,
    ) = dao.markRetry(id, OutboxStatus.PENDING, nextAttemptAt)

    override suspend fun markDead(
        id: String,
        reason: String,
    ) = dao.markDead(id, OutboxStatus.DEAD_LETTER, reason)

    override suspend fun revive(id: String) = dao.revive(id, OutboxStatus.PENDING)

    override suspend fun clear() {
        dao.clear()
    }

    override suspend fun pendingCount(lane: OutboxLane): Int = dao.count(lane, OutboxStatus.PENDING)

    override suspend fun deadLetters(): List<OutboxItem> = dao.deadLetters(OutboxStatus.DEAD_LETTER).map { it.toItem() }

    override suspend fun deadLetterCount(): Int = dao.countByStatus(OutboxStatus.DEAD_LETTER)

    override suspend fun deadLetterPreviews(limit: Int): List<DeadLetterPreview> =
        dao.deadLetterPreviews(OutboxStatus.DEAD_LETTER, limit).map {
            DeadLetterPreview(it.id, it.method, it.path, it.attempts, it.deadReason)
        }

    override suspend fun reviveAll(): Int = dao.reviveAll(OutboxStatus.DEAD_LETTER, OutboxStatus.PENDING)
}
