package io.healthassistant.shared.sync

/**
 * Sends one request to the bridge. The real implementation (Phase 5) wraps
 * `BridgeClient` and classifies the HTTP outcome into a [SendResult]
 * (4xx → Permanent; 429/5xx/network → Transient; 2xx → Success).
 */
fun interface SyncSender {
    suspend fun send(method: String, path: String, body: ByteArray): SendResult
}
