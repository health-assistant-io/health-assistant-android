package io.healthassistant.shared.sync

import io.healthassistant.bridge.BridgeClient
import java.io.IOException

/**
 * Production [SyncSender] that wraps a [BridgeClient] and classifies each HTTP
 * outcome for the [SyncCoordinator]: 2xx → [SendResult.Success]; 429/5xx and
 * network errors → [SendResult.Transient] (retryable); other 4xx →
 * [SendResult.Permanent] (dead-letter).
 *
 * Uses [BridgeClient.statusOf] so ktor types stay inside the SDK (this module
 * does not depend on ktor). Handles the DEFAULT lane (body bytes ready in the
 * [OutboxItem]); LARGE-lane items (document uploads with a
 * [OutboxItem.contentRef]) are read into bytes by an Android-side wrapper before
 * delegating here.
 */
class BridgeSyncSender(private val client: BridgeClient) : SyncSender {

    override suspend fun send(method: String, path: String, body: ByteArray): SendResult = try {
        when (val code = client.statusOf(method, path, body)) {
            in 200..299 -> SendResult.Success
            429, in 500..599 -> SendResult.Transient(code)
            else -> SendResult.Permanent(code)
        }
    } catch (e: IOException) {
        SendResult.Transient(message = e.message)
    }
}
