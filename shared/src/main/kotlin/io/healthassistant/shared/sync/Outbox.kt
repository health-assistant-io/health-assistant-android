package io.healthassistant.shared.sync

/** Outbox lane: DEFAULT = small JSON payloads (batched); LARGE = doc uploads (one-at-a-time, content ref). */
enum class OutboxLane { DEFAULT, LARGE }

/** Per-item state machine: PENDING → IN_FLIGHT → SYNCED | DEAD_LETTER (PENDING on transient retry). */
enum class OutboxStatus { PENDING, IN_FLIGHT, SYNCED, DEAD_LETTER }

/**
 * One queued local event. `id` is a client-generated UUID used as the
 * record/exam `id` so retries are idempotent (the backend dedups grouped exams
 * on `(tenant, patient, integration_id, examination.id)`).
 *
 * DEFAULT lane: [payload] holds the body bytes. LARGE lane: [payload] is empty
 * and [contentRef] points at the bytes (local file path / content hash) so the
 * row stays small and retries don't reload megabytes into memory.
 */
data class OutboxItem(
    val id: String,
    val method: String,
    val path: String,
    val payload: ByteArray = ByteArray(0),
    val lane: OutboxLane = OutboxLane.DEFAULT,
    val contentRef: String? = null,
    val status: OutboxStatus = OutboxStatus.PENDING,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val deadReason: String? = null,
) {
    override fun equals(other: Any?): Boolean = other is OutboxItem && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

/** Outcome of one send attempt, as classified by the [SyncSender] (HTTP status aware). */
sealed class SendResult {
    data object Success : SendResult()
    data class Transient(val statusCode: Int? = null, val message: String? = null) : SendResult()
    data class Permanent(val statusCode: Int? = null, val message: String? = null) : SendResult()
}
