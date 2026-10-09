package io.healthassistant.shared.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Phase G — the six clinical-record families the unified `/changes` delta covers.
 * Matches the backend `ALL_TYPES` in `provider.py::_changes_since`. Each enum
 * entry's [key] is the JSON key inside the response `data` object.
 */
enum class ChangeType(val key: String) {
    MEDICATIONS("medications"),
    ALLERGIES("allergies"),
    VACCINES("vaccines"),
    CLINICAL_EVENTS("clinical_events"),
    DOCUMENTS("documents"),
    EXAMINATIONS("examinations"),
}

/**
 * Phase G — one unified `/changes` delta, parsed into per-type raw-JSON rows.
 *
 * The rows are kept as [JsonObject]s (not decoded into typed SDK models) because
 * the bridge `/changes` projection is a *simplified* shape per type (e.g.
 * medications carry only `{id, updated_at, status, code_text, start_date}` — a
 * subset of the full `Medication`). Decoding into the rich models would either
 * drop fields or require a parallel set of delta models. Carrying the raw rows
 * keeps the coordinator agnostic; the store decides how much to hydrate. Phase H
 * (per-domain Room caches) will decode these into cache entities; Phase G only
 * needs counts + a refresh signal.
 */
data class ChangesDelta(
    val medications: List<JsonObject> = emptyList(),
    val allergies: List<JsonObject> = emptyList(),
    val vaccines: List<JsonObject> = emptyList(),
    val clinicalEvents: List<JsonObject> = emptyList(),
    val documents: List<JsonObject> = emptyList(),
    val examinations: List<JsonObject> = emptyList(),
) {
    /** Rows for [type]. */
    fun forType(type: ChangeType): List<JsonObject> =
        when (type) {
            ChangeType.MEDICATIONS -> medications
            ChangeType.ALLERGIES -> allergies
            ChangeType.VACCINES -> vaccines
            ChangeType.CLINICAL_EVENTS -> clinicalEvents
            ChangeType.DOCUMENTS -> documents
            ChangeType.EXAMINATIONS -> examinations
        }

    /** Per-type counts (zero-filled for types absent from the response). */
    fun counts(): Map<ChangeType, Int> = ChangeType.entries.associateWith { forType(it).size }

    /** Total rows across every type. */
    val totalCount: Int get() = ChangeType.entries.sumOf { forType(it).size }

    /** True when the delta carries nothing (the server's cursor was null). */
    val isEmpty: Boolean get() = totalCount == 0
}

/**
 * Phase G — the result of one [PullSyncCoordinator.pull] pass. Carries the
 * per-type counts (for notifications + monitor reporting) + the cursor that was
 * persisted (so the caller can log the advance) + whether anything changed.
 */
data class PullResult(
    val delta: ChangesDelta,
    val counts: Map<ChangeType, Int>,
    /** The cursor to use for the NEXT pull — the just-persisted value, or the
     *  unchanged prior cursor when the server returned nothing. */
    val newCursor: String?,
    /** Epoch-ms of when this pull completed. */
    val pulledAt: Long,
) {
    val totalChanged: Int get() = delta.totalCount
    val changed: Boolean get() = !delta.isEmpty
}

/**
 * Phase G — crash-safe persistence + delta-application sink for the pull
 * coordinator. Pure-Kotlin so it lives in the KMP `shared` core (JVM-testable
 * with a fake, iOS-reusable); the DataStore-backed implementation is in
 * `android/data/`.
 *
 * Contract:
 * - [currentCursor] is the `since` value for the next fetch. `null` = first
 *   pull (the server defaults the window to the last 7 days).
 * - [saveCursor] is the ONLY mutation that advances the high-water mark. It is
 *   called by the coordinator AFTER a successful [applyDelta] so a mid-apply
 *   crash leaves the cursor untouched and the next pull re-fetches the same
 *   window (idempotent — the backend dedups by id).
 * - [applyDelta] hydrates whatever caches/repos exist. Must be idempotent
 *   (re-applying the same delta is a no-op). A thrown exception aborts the pull
 *   and the cursor is NOT advanced.
 */
interface PullSyncStore {
    suspend fun currentCursor(): String?

    suspend fun saveCursor(isoCursor: String)

    suspend fun applyDelta(delta: ChangesDelta)

    /** Reset the cursor so the next pull re-fetches the full default window. */
    suspend fun clearCursor()
}

/**
 * Phase G — the two-way incremental sync engine. Fetches the unified `/changes`
 * delta for the connection, parses it, hands it to [store] for cache hydration,
 * then advances the cursor — in that exact order, so a failure at any step
 * leaves the cursor unchanged and the next pull re-runs the same window safely.
 *
 * Pure-Kotlin + JVM-testable (inject a fake `fetch` that returns canned JSON +
 * an in-memory [PullSyncStore]). The fetch function mirrors the SDK's
 * `BridgeClient.getChangesRaw(since, types, limit)` signature — the worker
 * wires `client::getChangesRaw` (with a bound `types`/`limit`) into it.
 *
 * Conflict policy (plan §G.3): the server wins for clinical writes — this is a
 * one-way *pull* of server-side state into the local cache. Local-only writes
 * (HC reads, manual entries) push first via the existing [SyncCoordinator]; this
 * pull only applies to the medication/allergy/vaccine/event/document/exam
 * families that the PWA can mutate.
 */
class PullSyncCoordinator(
    private val fetch: suspend (since: String?) -> String,
    private val store: PullSyncStore,
) {
    /**
     * Pull one batch. Steps (crash-safe):
     * 1. `GET /changes?since=<cursor>`
     * 2. parse the envelope → [ChangesDelta]
     * 3. `store.applyDelta(delta)` (hydrate caches; idempotent)
     * 4. `store.saveCursor(envelope.cursor)` ONLY when the server returned a
     *    non-null cursor AND step 3 did not throw. A null cursor means nothing
     *    changed since the prior cursor → keep the prior cursor.
     *
     * Returns the [PullResult]. Never throws for a network/parse failure on the
     * *fetch* side — that surfaces as a `null` result so the worker can retry on
     * the next schedule without losing the cursor. An [applyDelta] failure
     * propagates (the cursor is left intact by design).
     */
    suspend fun pull(now: Long = System.currentTimeMillis()): PullResult {
        // Normalize on read too: a cursor persisted by an older client (or one
        // received as `+00:00`) would otherwise be re-sent verbatim and the `+`
        // gets URL-decoded to a space by the server → HTTP 400. See
        // [String.toUrlSafeCursor].
        val priorCursor = store.currentCursor()?.toUrlSafeCursor()
        val raw =
            try {
                fetch(priorCursor)
            } catch (e: Exception) {
                throw PullFetchException(priorCursor, e)
            }

        val envelope = ChangesEnvelope.parse(raw)
        val delta = envelope.toDelta()

        // Apply BEFORE advancing — a crash here keeps the cursor pointing at the
        // pre-pull window so the next pass re-fetches the same rows (idempotent).
        store.applyDelta(delta)

        // Only persist when the server returns a fresh high-water mark. A null
        // cursor ⇒ nothing changed since `priorCursor` ⇒ leave the stored cursor
        // untouched (no write at all) and report the unchanged prior value.
        // Normalize before storing so the next pull re-sends a URL-safe cursor
        // (the server emits `+00:00`; the SDK does not URL-encode query values).
        val serverCursor = envelope.cursor?.toUrlSafeCursor()
        if (serverCursor != null) store.saveCursor(serverCursor)

        return PullResult(
            delta = delta,
            counts = delta.counts(),
            newCursor = serverCursor ?: priorCursor,
            pulledAt = now,
        )
    }

    /** Convenience: pull, then discard the result — returns whether anything changed. */
    suspend fun pullAndSignal(now: Long = System.currentTimeMillis()): Boolean = pull(now).changed
}

/**
 * Phase G — the envelope shape of `GET /changes`:
 * `{ data: {medications:[…], …}, cursor: "ISO"|null, cached_at: "ISO", since: "ISO" }`.
 *
 * Not the standard `ReadEnvelope<List<T>>` (the `data` is a per-type map, not a
 * list), so it gets its own parser. Tolerant: unknown top-level keys + unknown
 * per-type keys are ignored (forward-compat with new types added server-side).
 */
@Serializable
data class ChangesEnvelope(
    val data: JsonObject = JsonObject(emptyMap()),
    val cursor: String? = null,
    @SerialName("cached_at") val cachedAt: String? = null,
    val since: String? = null,
) {
    /** Build the typed [ChangesDelta] from the raw `data` object. Types absent
     *  from the response default to empty lists. */
    fun toDelta(): ChangesDelta =
        ChangesDelta(
            medications = data.array(ChangeType.MEDICATIONS.key),
            allergies = data.array(ChangeType.ALLERGIES.key),
            vaccines = data.array(ChangeType.VACCINES.key),
            clinicalEvents = data.array(ChangeType.CLINICAL_EVENTS.key),
            documents = data.array(ChangeType.DOCUMENTS.key),
            examinations = data.array(ChangeType.EXAMINATIONS.key),
        )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(raw: String): ChangesEnvelope = json.decodeFromString(serializer(), raw)

        /** Extract a list of JSON objects for [key] from a [JsonObject]; missing
         *  or non-array keys yield an empty list. Array elements that are not
         *  objects are dropped. */
        internal fun JsonObject.array(key: String): List<JsonObject> {
            val node = this[key] as? JsonArray ?: return emptyList()
            return node.mapNotNull { it as? JsonObject }
        }
    }
}

/** The `updated_at` field on a `/changes` row, parsed to epoch-ms when present.
 *  Used for ordering + for the store to skip already-applied (older) rows. */
fun JsonObject.updatedAtEpochMs(): Long? =
    (this["updated_at"] as? JsonPrimitive)?.content?.let { iso ->
        runCatching {
            java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        }.recoverCatching {
            java.time.LocalDateTime.parse(iso)
                .toInstant(java.time.ZoneOffset.UTC)
                .toEpochMilli()
        }.getOrNull()
    }

/** The `id` field on a `/changes` row, as a string. */
fun JsonObject.idString(): String? = (this["id"] as? JsonPrimitive)?.contentOrNull

/**
 * Normalize a `/changes` cursor to a URL-safe ISO-8601 form. The server emits
 * `max(updated_at).isoformat()` which is always UTC (`…+00:00`). The `+` is
 * decoded as a space by the server's query-string parser (the Kotlin SDK does
 * not URL-encode query values), so re-sending `…+00:00` fails with HTTP 400.
 * Converting the trailing `+00:00` (and the rare `+0000`) to `Z` sidesteps the
 * issue entirely — the server parses `Z` explicitly (`fromisoformat(.. replace
 * "Z", "+00:00"))`). Non-UTC offsets are returned unchanged (the bridge never
 * emits them, but if it did, the `+`-as-space problem would surface as a clear
 * 400 rather than silent data skew).
 */
internal fun String.toUrlSafeCursor(): String =
    when {
        this.endsWith("+00:00") -> dropLast(6) + "Z"
        this.endsWith("+0000") -> dropLast(5) + "Z"
        else -> this
    }

/** Phase G — fetch failed. Carries the cursor that was in use so the worker can
 *  log/trace which window failed. The cause is the underlying transport error. */
class PullFetchException(
    val cursor: String?,
    cause: Throwable,
) : RuntimeException("pull fetch failed (cursor=$cursor): ${cause.message}", cause)
