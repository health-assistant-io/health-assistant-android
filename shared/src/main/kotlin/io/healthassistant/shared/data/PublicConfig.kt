package io.healthassistant.shared.data

import java.net.URL

/** Frontend/PWA-origin resolution for the mobile app's "Open in browser" deep
 *  links. The advertised origin (from the bridge `GET /status` response) may be
 *  loopback (the server's own `localhost:PORT`); it is rewritten to the
 *  connection's reachable host, keeping the advertised frontend port — so
 *  `http://localhost:3000` + connection `http://10.0.0.5:8000` resolves to
 *  `http://10.0.0.5:3000`. */
object PublicConfig {
    fun isLoopback(url: String): Boolean {
        val host = url.substringAfter("://").substringBefore("/").substringBefore(":").lowercase()
        return host == "localhost" || host == "::1" || host.startsWith("127.")
    }

    /**
     * Resolve a device-reachable frontend origin from the server's advertised
     * [frontendBaseUrl] (falling back to [clientBaseUrl]). A reachable
     * (non-loopback) origin is used as-is; a loopback origin is rewritten to the
     * connection's reachable host, keeping the advertised scheme + port. Returns
     * null when nothing usable is available.
     */
    fun resolveFrontendOrigin(
        frontendBaseUrl: String?,
        clientBaseUrl: String?,
        connectionBaseUrl: String,
    ): String? {
        val advertised = frontendBaseUrl?.trim()?.takeIf { it.isNotBlank() }
            ?: clientBaseUrl?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        if (!isLoopback(advertised)) return advertised.trimEnd('/')
        return runCatching {
            val advertisedUrl = URL(advertised)
            val connectionUrl = URL(connectionBaseUrl)
            val scheme = advertisedUrl.protocol
            val port = if (advertisedUrl.port in 1..65535) advertisedUrl.port else advertisedUrl.defaultPort
            val host = connectionUrl.host
            val portSuffix = if (port in 1..65535 && !(scheme == "http" && port == 80) && !(scheme == "https" && port == 443)) ":$port" else ""
            "$scheme://$host$portSuffix"
        }.getOrNull()
    }
}
