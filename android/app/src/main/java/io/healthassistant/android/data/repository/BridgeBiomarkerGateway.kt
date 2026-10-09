package io.healthassistant.android.data.repository

import io.healthassistant.bridge.BridgeClient
import io.healthassistant.shared.data.BiomarkerSummary
import io.healthassistant.shared.data.BridgeReads
import io.healthassistant.shared.data.repository.BiomarkerGateway

/**
 * Android [BiomarkerGateway] — delegates to [BridgeReads] against the
 * per-connection [BridgeClient]. Constructed per active connection alongside
 * the repository that wraps it.
 */
class BridgeBiomarkerGateway(
    private val client: BridgeClient,
) : BiomarkerGateway {
    override suspend fun catalog(limit: Int): List<BiomarkerSummary> = BridgeReads.listBiomarkers(client, limit)
}
