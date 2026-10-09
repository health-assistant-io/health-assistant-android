package io.healthassistant.shared.data.cache

/**
 * The on-device document byte store (offline-first M4) — a bounded, LRU-evicted
 * file cache of downloaded document content. Pure-Kotlin interface (KMP
 * `shared` core, JVM-testable with an in-memory fake); the file-based Android
 * implementation lives in `android/data/`.
 *
 * Contract:
 * - [write] stores bytes atomically (tmp + rename) keyed by document id.
 * - [read] returns the stored bytes, or null on a miss.
 * - [evictLRU] deletes least-recently-written files until the store is at or
 *   under [capBytes], returning the ids of the evicted documents so callers can
 *   clear their metadata manifest rows.
 * - [totalBytes] is the current store size (for the Settings size display).
 */
interface DocumentByteStore {
    /** The stored bytes for [docId], or null on a miss. */
    fun read(docId: String): ByteArray?

    /** Atomically store [bytes] under [docId]. */
    fun write(
        docId: String,
        bytes: ByteArray,
    )

    /** Delete one document's bytes (after a remote delete). */
    fun delete(docId: String)

    /** Current total size in bytes. */
    fun totalBytes(): Long

    /** Delete least-recently-written files until size ≤ [capBytes]; returns
     *  the evicted document ids (oldest first). */
    fun evictLRU(capBytes: Long): List<String>

    /** Delete every stored file. */
    fun clear()
}
