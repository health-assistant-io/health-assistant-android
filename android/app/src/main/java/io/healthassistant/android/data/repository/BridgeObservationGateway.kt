package io.healthassistant.android.data.repository

import io.healthassistant.bridge.BridgeClient
import io.healthassistant.shared.data.BridgeReads
import io.healthassistant.shared.data.ObservationPoint
import io.healthassistant.shared.data.repository.ObservationGateway

/**
 * Android [ObservationGateway] — delegates to [BridgeReads] against the
 * per-connection [BridgeClient]. Constructed per active connection (mirrors how
 * the client itself is built in `AppRoot`), so a connection switch rebuilds the
 * gateway and the repository that wraps it.
 */
class BridgeObservationGateway(
    private val client: BridgeClient,
) : ObservationGateway {
    override suspend fun latest(limit: Int): List<ObservationPoint> = BridgeReads.listLatestObservations(client, limit)

    override suspend fun series(
        code: String,
        sinceIso: String,
        untilIso: String,
        limit: Int,
    ): List<ObservationPoint> = BridgeReads.listObservations(client, code, sinceIso, untilIso, limit)
}
