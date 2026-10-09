package io.healthassistant.android.data

import io.healthassistant.shared.onboarding.ConnectionCredential
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ServerReachabilityMonitor logic: no-target pass-through, the OS-offline
 * short-circuit, probe success/failure mapping, ticker re-checks (recovery
 * detection), and manual checkNow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerReachabilityMonitorTest {
    private val cred = ConnectionCredential("https://ha.example.com", "123e4567-e89b-42d3-a456-426614174000", null)

    private fun TestScope.monitor(
        osOnline: MutableStateFlow<Boolean>,
        probe: suspend (ConnectionCredential) -> Boolean,
    ): ServerReachabilityMonitor =
        ServerReachabilityMonitor(
            scope = backgroundScope,
            osOnline = osOnline,
            probeStatus = probe,
            recheckIntervalMs = 30_000L,
        )

    @Test
    fun no_target_stays_online() =
        runTest(StandardTestDispatcher()) {
            val m = monitor(MutableStateFlow(true)) { false }
            runCurrent()
            assertEquals(ServerReachability.Online, m.state.value)
        }

    @Test
    fun os_offline_short_circuits_without_probing() =
        runTest(StandardTestDispatcher()) {
            var probed = 0
            val m =
                monitor(MutableStateFlow(false)) {
                    probed++
                    true
                }
            m.setTarget(cred)
            runCurrent()
            assertEquals(ServerReachability.NoInternet, m.state.value)
            assertEquals(0, probed)
        }

    @Test
    fun unreachable_server_maps_to_ServerUnreachable() =
        runTest(StandardTestDispatcher()) {
            val m = monitor(MutableStateFlow(true)) { false }
            m.setTarget(cred)
            runCurrent()
            assertEquals(ServerReachability.ServerUnreachable, m.state.value)
        }

    @Test
    fun reachable_server_maps_to_Online() =
        runTest(StandardTestDispatcher()) {
            val m = monitor(MutableStateFlow(true)) { true }
            m.setTarget(cred)
            runCurrent()
            assertEquals(ServerReachability.Online, m.state.value)
        }

    @Test
    fun ticker_detects_recovery() =
        runTest(StandardTestDispatcher()) {
            var up = false
            val m = monitor(MutableStateFlow(true)) { up }
            m.setTarget(cred)
            runCurrent()
            assertEquals(ServerReachability.ServerUnreachable, m.state.value)

            up = true
            advanceTimeBy(30_500L)
            assertEquals(ServerReachability.Online, m.state.value)
        }

    @Test
    fun os_online_transition_reevaluates() =
        runTest(StandardTestDispatcher()) {
            val osOnline = MutableStateFlow(false)
            val m = monitor(osOnline) { true }
            m.setTarget(cred)
            runCurrent()
            assertEquals(ServerReachability.NoInternet, m.state.value)

            osOnline.value = true
            runCurrent()
            assertEquals(ServerReachability.Online, m.state.value)
        }

    @Test
    fun checkNow_reprobes_on_demand() =
        runTest(StandardTestDispatcher()) {
            var up = false
            val m = monitor(MutableStateFlow(true)) { up }
            m.setTarget(cred)
            runCurrent()
            assertEquals(ServerReachability.ServerUnreachable, m.state.value)

            up = true
            m.checkNow()
            runCurrent()
            assertEquals(ServerReachability.Online, m.state.value)
        }

    @Test
    fun setTarget_null_clears_back_to_online() =
        runTest(StandardTestDispatcher()) {
            val m = monitor(MutableStateFlow(true)) { false }
            m.setTarget(cred)
            runCurrent()
            assertEquals(ServerReachability.ServerUnreachable, m.state.value)

            m.setTarget(null)
            runCurrent()
            assertEquals(ServerReachability.Online, m.state.value)
        }
}
