package io.healthassistant.shared.sync

import io.healthassistant.bridge.SyncPayload
import kotlinx.serialization.json.Json
import kotlin.math.min
import kotlin.random.Random

/**
 * Drains the outbox. DEFAULT lane: groups consecutive `/sync` POST items into a
 * single `SyncPayload` (idempotent client-UUIDs preserved); non-`/sync` paths go
 * individually. LARGE lane: drained one-at-a-time. Transient failures →
 * full-jitter backoff (cap 8s) + retry; permanent failures / max-attempts →
 * DEAD_LETTER.
 *
 * HTTP 429 (rate limit) is special-cased: it means "slow down", not "broken" —
 * so it backs off for [Config.rateLimitBackoffMs] (+ jitter), NEVER counts
 * toward [Config.maxAttempts], and stops the drain pass (further requests in
 * the same window would only burn quota). The caller schedules the next pass
 * after the returned [DrainResult.rateLimitedUntil] instant.
 *
 * The coordinator never sleeps — it computes `nextAttemptAt` (relative to [now],
 * so it is fully deterministic/testable) and leaves waiting to the scheduler
 * (WorkManager / BGTaskScheduler). Pure-Kotlin + JVM-testable.
 */
class SyncCoordinator(
    private val store: OutboxStore,
    private val sender: SyncSender,
    private val config: Config = Config(),
) {
    data class Config(
        val defaultBatchSize: Int = 1000,
        val maxAttempts: Int = 5,
        val backoffCeilingMs: Long = 8_000L,
        /** Floor for the 429 rate-limit backoff (server windows are typically 60s). */
        val rateLimitBackoffMs: Long = 90_000L,
        /** Extra full jitter on top of [rateLimitBackoffMs] to de-herd batches. */
        val rateLimitJitterMs: Long = 30_000L,
        /**
         * Invoked with each successfully synced batch (the raw [OutboxItem]s, before
         * deletion) so callers can tally per-biomarker progress. `null` = no-op.
         */
        val onSyncedItems: (suspend (List<OutboxItem>) -> Unit)? = null,
        /** Invoked with each newly dead-lettered item (for per-type failure tally). */
        val onDeadItems: (suspend (List<OutboxItem>) -> Unit)? = null,
    )

    data class DrainResult(
        val attempted: Int,
        val synced: Int,
        val retried: Int,
        val deadLettered: Int,
        /** Non-null when the server returned 429: earliest epoch-ms to drain again. */
        val rateLimitedUntil: Long? = null,
    ) {
        val isEmpty: Boolean get() = attempted == 0
    }

    suspend fun drain(now: Long = System.currentTimeMillis()): DrainResult {
        val d = drainLane(OutboxLane.DEFAULT, groupSync = true, limit = config.defaultBatchSize, now)
        if (d.rateLimitedUntil != null) return d
        val l = drainLane(OutboxLane.LARGE, groupSync = false, limit = 1, now)
        return DrainResult(
            attempted = d.attempted + l.attempted,
            synced = d.synced + l.synced,
            retried = d.retried + l.retried,
            deadLettered = d.deadLettered + l.deadLettered,
            rateLimitedUntil = l.rateLimitedUntil,
        )
    }

    private suspend fun drainLane(lane: OutboxLane, groupSync: Boolean, limit: Int, now: Long): DrainResult {
        val batch = store.claim(lane, limit, now)
        if (batch.isEmpty()) return DrainResult(0, 0, 0, 0)

        var synced = 0
        var retried = 0
        var dead = 0
        var rateLimitedUntil: Long? = null
        val attempted = mutableSetOf<String>()

        val isSync = { it: OutboxItem -> it.method == "POST" && it.path == "/sync" }

        if (groupSync) {
            val syncs = batch.filter(isSync)
            if (syncs.isNotEmpty()) {
                when (val outcome = sendGrouped(syncs, now)) {
                    is Outcome.Synced -> {
                        store.markSynced(syncs.map { it.id }); synced += syncs.size; config.onSyncedItems?.invoke(syncs)
                    }
                    is Outcome.Retry -> { syncs.forEach { store.markRetry(it.id, outcome.until) }; retried += syncs.size }
                    is Outcome.RateLimited -> {
                        syncs.forEach { store.markRetry(it.id, outcome.until) }
                        retried += syncs.size
                        rateLimitedUntil = outcome.until
                    }
                    is Outcome.Dead -> { syncs.forEach { store.markDead(it.id, outcome.reason) }; dead += syncs.size; config.onDeadItems?.invoke(syncs) }
                }
                attempted += syncs.map { it.id }
            }
            if (rateLimitedUntil == null) {
                for (item in batch.filterNot(isSync)) {
                    val (s, r, dd, rl) = sendSingle(item, now)
                    synced += s; retried += r; dead += dd
                    attempted += item.id
                    if (rl != null) { rateLimitedUntil = rl; break }
                }
            }
        } else {
            for (item in batch) {
                val (s, r, dd, rl) = sendSingle(item, now)
                synced += s; retried += r; dead += dd
                attempted += item.id
                if (rl != null) { rateLimitedUntil = rl; break }
            }
        }
        // Claimed but never attempted (early stop on a 429): return them to
        // PENDING paced at the rate-limit cooldown so they aren't stranded IN_FLIGHT.
        batch.filter { it.id !in attempted }.takeIf { it.isNotEmpty() }?.let { stranded ->
            store.release(stranded.map { it.id }, rateLimitedUntil ?: now)
        }
        return DrainResult(batch.size, synced, retried, dead, rateLimitedUntil)
    }

    private sealed class Outcome {
        data object Synced : Outcome()
        data class Retry(val until: Long) : Outcome()
        data class RateLimited(val until: Long) : Outcome()
        data class Dead(val reason: String) : Outcome()
    }

    private suspend fun sendGrouped(items: List<OutboxItem>, now: Long): Outcome =
        when (val r = sender.send("POST", "/sync", mergeSync(items))) {
            is SendResult.Success -> Outcome.Synced
            is SendResult.Transient -> classifyTransient(r, items.maxOf { it.attempts } + 1, now)
            is SendResult.Permanent -> Outcome.Dead("permanent (${r.statusCode}): ${r.message}")
        }

    private suspend fun sendSingle(item: OutboxItem, now: Long): Quad {
        var rateLimited: Long? = null
        val synced: Int
        val retried: Int
        val dead: Int
        when (val r = sender.send(item.method, item.path, item.payload)) {
            is SendResult.Success -> { store.markSynced(listOf(item.id)); config.onSyncedItems?.invoke(listOf(item)); synced = 1; retried = 0; dead = 0 }
            is SendResult.Transient ->
                when (val outcome = classifyTransient(r, item.attempts + 1, now)) {
                    is Outcome.RateLimited -> {
                        store.markRetry(item.id, outcome.until)
                        rateLimited = outcome.until
                        synced = 0; retried = 1; dead = 0
                    }
                    is Outcome.Retry -> { store.markRetry(item.id, outcome.until); synced = 0; retried = 1; dead = 0 }
                    is Outcome.Dead -> { store.markDead(item.id, outcome.reason); config.onDeadItems?.invoke(listOf(item)); synced = 0; retried = 0; dead = 1 }
                    is Outcome.Synced -> { synced = 0; retried = 0; dead = 0 }
                }
            is SendResult.Permanent -> { store.markDead(item.id, "permanent (${r.statusCode}): ${r.message}"); config.onDeadItems?.invoke(listOf(item)); synced = 0; retried = 0; dead = 1 }
        }
        return Quad(synced, retried, dead, rateLimited)
    }

    /** 429 → long [Outcome.RateLimited] backoff (never dead-letters, doesn't burn
     *  the max-attempts budget); other transients → jittered retry or dead at max attempts. */
    private fun classifyTransient(
        r: SendResult.Transient,
        nextAttempt: Int,
        now: Long,
    ): Outcome =
        if (r.statusCode == 429) {
            Outcome.RateLimited(now + config.rateLimitBackoffMs + Random.nextLong(0, config.rateLimitJitterMs + 1))
        } else if (nextAttempt >= config.maxAttempts) {
            Outcome.Dead("max attempts (${r.statusCode}): ${r.message}")
        } else {
            Outcome.Retry(backoffUntil(nextAttempt, now))
        }

    private data class Quad(val synced: Int, val retried: Int, val dead: Int, val rateLimitedUntil: Long?)

    private fun backoffUntil(attempt: Int, now: Long): Long {
        val max = min(config.backoffCeilingMs, 1L shl attempt)
        return now + Random.nextLong(0, max + 1)
    }

    private fun mergeSync(items: List<OutboxItem>): ByteArray {
        val payloads = items.map { JSON.decodeFromString(SyncPayload.serializer(), it.payload.decodeToString()) }
        val merged = SyncPayload(
            clientVersion = payloads.first().clientVersion,
            sourceSystem = payloads.first().sourceSystem,
            cursor = payloads.mapNotNull { it.cursor }.maxByOrNull { it },
            records = payloads.flatMap { it.records ?: emptyList() }.takeIf { it.isNotEmpty() },
            examinations = payloads.flatMap { it.examinations ?: emptyList() }.takeIf { it.isNotEmpty() },
        )
        return JSON.encodeToString(SyncPayload.serializer(), merged).encodeToByteArray()
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    }
}
