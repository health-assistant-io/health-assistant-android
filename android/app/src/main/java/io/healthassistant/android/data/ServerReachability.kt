package io.healthassistant.android.data

import io.healthassistant.bridge.BridgeClient
import io.healthassistant.shared.onboarding.ConnectionCredential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Whether the app can currently reach the user's health data. */
enum class ServerReachability {
    /** The bridge answered `GET /status` (or no connection is configured). */
    Online,

    /** The device itself has no internet (OS reports offline). */
    NoInternet,

    /** The device is online but the user's server did not answer. */
    ServerUnreachable,
}

/**
 * Process-wide reachability signal for the active connection — the honest
 * version of "offline" for a self-hosted app. OS connectivity
 * (`NET_CAPABILITY_INTERNET`) only says the device has *an* internet; the
 * common failure is the home server being down or the LAN having changed.
 * This monitor probes the bridge's unsigned `GET /status` and exposes a
 * [ServerReachability] state.
 *
 * Re-evaluated on: OS-online transitions, target changes ([setTarget] — the
 * connection switcher re-targets), manual [checkNow] (pull-to-refresh), and
 * a [recheckIntervalMs] ticker (bounded recovery detection while the user
 * stares at the banner). The probe uses a throwaway [BridgeClient] closed in
 * `finally` — this runs repeatedly, unlike the one-shot onboarding probe.
 */
class ServerReachabilityMonitor(
    private val scope: CoroutineScope,
    osOnline: StateFlow<Boolean>,
    private val probeStatus: suspend (ConnectionCredential) -> Boolean,
    private val recheckIntervalMs: Long = DEFAULT_RECHECK_MS,
) {
    private val osOnline =
        MutableStateFlow(osOnline.value).also { flow ->
            scope.launch { osOnline.collect { flow.value = it } }
        }
    private val target = MutableStateFlow<ConnectionCredential?>(null)

    private val _state = MutableStateFlow(ServerReachability.Online)
    val state: StateFlow<ServerReachability> = _state.asStateFlow()

    init {
        scope.launch {
            combine(this@ServerReachabilityMonitor.osOnline, target) { online, _ -> online }
                .collect { reevaluate() }
        }
        scope.launch {
            while (true) {
                delay(recheckIntervalMs)
                reevaluate()
            }
        }
    }

    /** Point the monitor at the active connection (or clear it with null). */
    fun setTarget(credential: ConnectionCredential?) {
        target.value = credential
        checkNow()
    }

    /** Re-probe immediately (user-initiated refresh). */
    fun checkNow() {
        scope.launch { reevaluate() }
    }

    private suspend fun reevaluate() {
        val t = target.value
        if (t == null) {
            _state.value = ServerReachability.Online
            return
        }
        if (!osOnline.value) {
            _state.value = ServerReachability.NoInternet
            return
        }
        _state.value = if (probeStatus(t)) ServerReachability.Online else ServerReachability.ServerUnreachable
    }

    companion object {
        const val DEFAULT_RECHECK_MS = 30_000L
        private const val PROBE_TIMEOUT_MS = 5_000L

        /** The production probe: `GET /status` (unsigned) on a throwaway client,
         *  hard-capped at [PROBE_TIMEOUT_MS] so a dead-but-firewalled host (silently
         *  dropped packets) can't stall subsequent state transitions. */
        suspend fun defaultProbe(credential: ConnectionCredential): Boolean =
            try {
                val client =
                    BridgeClient(
                        baseUrl = credential.baseUrl,
                        integrationId = credential.integrationId,
                        apiSecret = credential.apiSecret,
                    )
                try {
                    withTimeoutOrNull(PROBE_TIMEOUT_MS) { client.getStatus() } != null
                } finally {
                    client.close()
                }
            } catch (e: Exception) {
                false
            }
    }
}
