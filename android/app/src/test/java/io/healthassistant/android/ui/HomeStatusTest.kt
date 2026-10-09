package io.healthassistant.android.ui

import io.healthassistant.android.data.ServerReachability
import io.healthassistant.android.monitoring.SyncMonitor
import io.healthassistant.android.monitoring.SyncProgress
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure derivation for the Home header status chip ([homeStatus]). */
class HomeStatusTest {
    private fun monitor(
        active: Boolean = false,
        dead: Int = 0,
    ): SyncMonitor =
        SyncMonitor(
            progress =
                if (active) {
                    SyncProgress(active = true, processed = 5, total = 10, perTypeSynced = emptyMap(), perTypeFailed = emptyMap())
                } else {
                    null
                },
            outboxDeadLettered = dead,
        )

    @Test
    fun `syncing wins over everything`() {
        assertEquals(HomeStatus.SYNCING, homeStatus(monitor(active = true), ServerReachability.ServerUnreachable))
        assertEquals(HomeStatus.SYNCING, homeStatus(monitor(active = true, dead = 5), ServerReachability.Online))
    }

    @Test
    fun `offline beats dead letters`() {
        assertEquals(HomeStatus.OFFLINE, homeStatus(monitor(dead = 3), ServerReachability.ServerUnreachable))
        assertEquals(HomeStatus.OFFLINE, homeStatus(monitor(), ServerReachability.NoInternet))
    }

    @Test
    fun `dead letters surface as attention`() {
        assertEquals(HomeStatus.NEEDS_ATTENTION, homeStatus(monitor(dead = 1), ServerReachability.Online))
    }

    @Test
    fun `otherwise up to date`() {
        assertEquals(HomeStatus.UP_TO_DATE, homeStatus(monitor(), ServerReachability.Online))
    }
}
