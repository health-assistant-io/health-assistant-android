package io.healthassistant.android.data

import android.util.Log
import io.healthassistant.bridge.BridgeClient
import io.healthassistant.shared.data.PublicConfig
import io.healthassistant.shared.onboarding.ConnectionCredential

/** The result of a successful connect probe: a ready [client] plus the
 *  [credential] enriched with the server-resolved frontend/PWA origin. */
data class ConnectionProbe(
    val client: BridgeClient,
    val credential: ConnectionCredential,
)

/**
 * Probes `GET /status` (never signed — the connectivity + SDK-discovery probe)
 * with the supplied credential. The status response carries the server's
 * **frontend/PWA origin** (`frontend_base_url`), resolved the same way as
 * `GET /config/public`. The app reads it over the same ktor connection it
 * already uses — no second network stack (a raw `HttpURLConnection` would be
 * blocked by Android's cleartext policy on API 28+ even though ktor reaches the
 * same host). The advertised origin may be loopback; it is rewritten to the
 * connection's reachable host, keeping the frontend port.
 */
class ConnectionRepository {
    suspend fun probe(credential: ConnectionCredential): Result<ConnectionProbe> {
        val client =
            BridgeClient(
                baseUrl = credential.baseUrl,
                integrationId = credential.integrationId,
                apiSecret = credential.apiSecret,
            )
        return try {
            val status = client.getStatus()
            Result.success(ConnectionProbe(client, credential.withFrontendOrigin(status.frontendBaseUrl)))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Re-resolve the frontend origin for an already-saved connection (e.g. on
     *  app start) over a fresh `GET /status` and return the refreshed credential.
     *  Never throws — on any failure the existing [ConnectionCredential.frontendBaseUrl]
     *  is preserved. Lets connections saved before this field existed self-heal
     *  without a manual reconnect. */
    suspend fun refreshFrontendOrigin(credential: ConnectionCredential): ConnectionCredential {
        val client =
            BridgeClient(
                baseUrl = credential.baseUrl,
                integrationId = credential.integrationId,
                apiSecret = credential.apiSecret,
            )
        return try {
            val status = client.getStatus()
            credential.withFrontendOrigin(status.frontendBaseUrl)
        } catch (e: Exception) {
            Log.w("HAConnect", "status re-probe failed: ${e.message}")
            credential
        } finally {
            client.close()
        }
    }

    private fun ConnectionCredential.withFrontendOrigin(advertised: String?): ConnectionCredential {
        val resolved =
            PublicConfig.resolveFrontendOrigin(
                frontendBaseUrl = advertised,
                clientBaseUrl = null,
                connectionBaseUrl = baseUrl,
            )
        Log.i("HAConnect", "resolved frontend origin: $resolved (advertised=$advertised, base=$baseUrl)")
        return copy(frontendBaseUrl = resolved)
    }
}
