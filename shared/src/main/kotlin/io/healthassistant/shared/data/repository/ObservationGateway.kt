package io.healthassistant.shared.data.repository

import io.healthassistant.shared.data.ObservationPoint

/**
 * Network read abstraction for observations. Isolates [ObservationRepository]
 * from the SDK's [io.healthassistant.bridge.BridgeClient] (which needs ktor and
 * is therefore Android-only) so the repository stays pure-Kotlin + JVM-testable
 * with a trivial fake. Each method performs exactly one bridge read and throws
 * on a non-2xx response.
 */
interface ObservationGateway {
    /** `GET /observations/latest` — the newest value per biomarker (FHIR +
     *  telemetry merged server-side). */
    suspend fun latest(limit: Int = 50): List<ObservationPoint>

    /** `GET /observations?biomarker=&since=&until=` — the time series for one
     *  biomarker within a window, newest first as the bridge returns them. */
    suspend fun series(
        code: String,
        sinceIso: String,
        untilIso: String,
        limit: Int = 200,
    ): List<ObservationPoint>
}
