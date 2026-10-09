package io.healthassistant.shared.data.repository

import io.healthassistant.shared.data.BiomarkerSummary

/**
 * Network read abstraction for the biomarker catalog, isolating
 * [BiomarkerCatalogRepository] from the SDK's ktor-dependent `BridgeClient`
 * so the repository stays pure-Kotlin + JVM-testable. Performs exactly one
 * bridge read (`GET /biomarkers`) and throws on a non-2xx response.
 */
interface BiomarkerGateway {
    /** The tenant's full biomarker catalog (single-page snapshot). */
    suspend fun catalog(limit: Int = 500): List<BiomarkerSummary>
}
