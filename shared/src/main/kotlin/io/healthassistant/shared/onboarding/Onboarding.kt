package io.healthassistant.shared.onboarding

import java.net.URLDecoder

/** The credential triple all three onboarding tiers produce. Stored in EncryptedSharedPreferences + Keystore. */
data class ConnectionCredential(
    val baseUrl: String,
    val integrationId: String,
    val apiSecret: String? = null,
    /** The server's **frontend/PWA** origin (from `GET /config/public` →
     *  `client_base_url`). May differ from [baseUrl]'s port when the web frontend
     *  and the API backend run on separate ports; used for deep links like the
     *  full examination record. Resolved + persisted at connect time. */
    val frontendBaseUrl: String? = null,
) {
    /** True when this connection runs the unsigned/UUID-only mode (no transport-layer auth). */
    val hasSecret: Boolean get() = !apiSecret.isNullOrBlank()
}

/**
 * Parses the three onboarding tiers (mobile-app plan §2.6): QR scan,
 * "Open in app" deep link, and manual text entry. All produce a
 * [ConnectionCredential] or `null` on malformed/tampered input. Pure-Kotlin +
 * JVM-testable; the camera scan / Intent handling is Android-side (Phase 6 UI).
 *
 * Transport security (§2.4): `http://` is accepted ONLY for LAN/private IPs —
 * the Android layer enforces that; here we just require a scheme.
 */
object Onboarding {

    /** QR encodes `base_url|integration_id|api_secret` (api_secret optional → 2 or 3 fields). */
    fun parseQr(qr: String): ConnectionCredential? {
        val parts = qr.split("|")
        if (parts.size !in 2..3) return null
        return build(parts[0].trim(), parts[1].trim(), parts.getOrNull(2)?.takeIf { it.isNotBlank() }?.trim())
    }

    /** Deep link: `https://<host>/connect?base_url=...&integration_id=...&api_secret=...`
     *  (or a custom scheme) — query-encoded; `api_secret` optional. */
    fun parseDeepLink(uri: String): ConnectionCredential? {
        val query = uri.substringAfter("?", "")
        if (query.isBlank()) return null
        val map = query.split("&").mapNotNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2) decode(kv[0]) to decode(kv[1]) else null
        }.toMap()
        return build(map["base_url"] ?: return null, map["integration_id"] ?: return null, map["api_secret"]?.takeIf { it.isNotBlank() })
    }

    /** Manual entry form — paste three fields. */
    fun validateManual(baseUrl: String, integrationId: String, apiSecret: String?): ConnectionCredential? =
        build(baseUrl.trim(), integrationId.trim(), apiSecret?.trim()?.takeIf { it.isNotBlank() })

    private fun build(baseUrl: String, integrationId: String, apiSecret: String?): ConnectionCredential? {
        if (!isValidBaseUrl(baseUrl)) return null
        if (!isValidUuid(integrationId)) return null
        if (apiSecret != null && apiSecret.length < MIN_SECRET_LEN) return null
        return ConnectionCredential(baseUrl, integrationId, apiSecret)
    }

    private fun isValidBaseUrl(s: String): Boolean =
        s.isNotBlank() && (s.startsWith("https://", true) || s.startsWith("http://", true))

    private fun isValidUuid(s: String): Boolean =
        s.length == 36 && s.count { it == '-' } == 4 &&
            s.replace("-", "").all { it.isLetterOrDigit() }

    private fun decode(s: String): String = URLDecoder.decode(s, "UTF-8")

    private const val MIN_SECRET_LEN = 16 // matches config_flow.py api_secret minimum
}
