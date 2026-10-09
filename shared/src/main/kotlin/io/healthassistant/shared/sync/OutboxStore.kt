package io.healthassistant.shared.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persistence boundary for the outbox. Implementations:
 * - [InMemoryOutboxStore] — JVM tests.
 * - (Phase 5) an Android SQLDelight/Room-backed store on the device.
 *
 * The store owns the state-machine transitions; the coordinator only decides
 * WHICH transition each claimed item should take.
 */
interface OutboxStore {
    suspend fun enqueue(item: OutboxItem)
    suspend fun get(id: String): OutboxItem?

    /** Claim up to [limit] PENDING items in [lane] whose backoff has elapsed, marking them IN_FLIGHT. */
    suspend fun claim(lane: OutboxLane, limit: Int, now: Long = System.currentTimeMillis()): List<OutboxItem>

    /** Return claimed-but-unattempted IN_FLIGHT items to PENDING (attempts
     *  unchanged) with the given backoff — e.g. when a drain pass stops early
     *  on a 429 rate limit. */
    suspend fun release(
        ids: List<String>,
        nextAttemptAt: Long,
    )

    suspend fun markSynced(ids: List<String>)
    suspend fun markRetry(id: String, nextAttemptAt: Long)
    suspend fun markDead(id: String, reason: String)

    /** Revive a DEAD_LETTER item back to PENDING (resetting attempts), so the
     *  next drain retries it. Phase F dead-letter-retry. */
    suspend fun revive(id: String)

    /** Drop every item (all lanes, all statuses). Phase G "Clear outbox" — used
     *  when the backlog is unwanted (e.g. an accidental full-history flood). */
    suspend fun clear()

    suspend fun pendingCount(lane: OutboxLane = OutboxLane.DEFAULT): Int
    suspend fun deadLetters(): List<OutboxItem>

    /** Total DEAD_LETTER items without loading any payloads (Sync screen count). */
    suspend fun deadLetterCount(): Int

    /** Up to [limit] dead-letter previews (no payload bytes), oldest first. */
    suspend fun deadLetterPreviews(limit: Int): List<DeadLetterPreview>

    /** Revive every DEAD_LETTER item back to PENDING (attempts reset); returns how many. */
    suspend fun reviveAll(): Int
}

/** Payload-free projection of a dead-letter item for bounded UI lists. */
data class DeadLetterPreview(
    val id: String,
    val method: String,
    val path: String,
    val attempts: Int,
    val deadReason: String?,
)

/** In-memory store for JVM tests (and a reference for the state-machine semantics). */
class InMemoryOutboxStore : OutboxStore {
    private val mutex = Mutex()
    private val items = LinkedHashMap<String, OutboxItem>()

    override suspend fun enqueue(item: OutboxItem) = mutex.withLock { items[item.id] = item }

    override suspend fun get(id: String): OutboxItem? = mutex.withLock { items[id] }

    override suspend fun claim(lane: OutboxLane, limit: Int, now: Long): List<OutboxItem> = mutex.withLock {
        val claimed = items.values
            .filter { it.lane == lane && it.status == OutboxStatus.PENDING && it.nextAttemptAt <= now }
            .sortedBy { it.createdAt }
            .take(limit)
            .onEach { items[it.id] = it.copy(status = OutboxStatus.IN_FLIGHT) }
        claimed
    }

    override suspend fun release(
        ids: List<String>,
        nextAttemptAt: Long,
    ) = mutex.withLock {
        ids.forEach { id ->
            items[id]?.let { items[id] = it.copy(status = OutboxStatus.PENDING, nextAttemptAt = nextAttemptAt) }
        }
        Unit
    }

    override suspend fun markSynced(ids: List<String>) = mutex.withLock {
        ids.forEach { items.remove(it) }
    }

    override suspend fun markRetry(id: String, nextAttemptAt: Long) = mutex.withLock {
        items[id]?.let { items[id] = it.copy(status = OutboxStatus.PENDING, attempts = it.attempts + 1, nextAttemptAt = nextAttemptAt) }
        Unit
    }

    override suspend fun markDead(id: String, reason: String) = mutex.withLock {
        items[id]?.let { items[id] = it.copy(status = OutboxStatus.DEAD_LETTER, deadReason = reason) }
        Unit
    }

    override suspend fun revive(id: String) = mutex.withLock {
        items[id]?.let {
            items[id] = it.copy(status = OutboxStatus.PENDING, attempts = 0, nextAttemptAt = 0L, deadReason = null)
        }
        Unit
    }

    override suspend fun clear() = mutex.withLock { items.clear(); Unit }

    override suspend fun pendingCount(lane: OutboxLane): Int = mutex.withLock {
        items.values.count { it.lane == lane && it.status == OutboxStatus.PENDING }
    }

    override suspend fun deadLetters(): List<OutboxItem> = mutex.withLock {
        items.values.filter { it.status == OutboxStatus.DEAD_LETTER }
    }

    override suspend fun deadLetterCount(): Int = mutex.withLock {
        items.values.count { it.status == OutboxStatus.DEAD_LETTER }
    }

    override suspend fun deadLetterPreviews(limit: Int): List<DeadLetterPreview> =
        mutex.withLock {
            items.values
                .filter { it.status == OutboxStatus.DEAD_LETTER }
                .take(limit)
                .map { DeadLetterPreview(it.id, it.method, it.path, it.attempts, it.deadReason) }
        }

    override suspend fun reviveAll(): Int =
        mutex.withLock {
            var revived = 0
            items.values
                .filter { it.status == OutboxStatus.DEAD_LETTER }
                .forEach {
                    items[it.id] = it.copy(status = OutboxStatus.PENDING, attempts = 0, nextAttemptAt = 0L, deadReason = null)
                    revived++
                }
            revived
        }

    /** Snapshot of all items (test helper). */
    suspend fun snapshot(): List<OutboxItem> = mutex.withLock { items.values.toList() }
}
