package io.healthassistant.android.source

import io.healthassistant.android.settings.SyncSettings
import io.healthassistant.shared.healthconnect.HcType
import io.healthassistant.shared.source.FakeHealthDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase E gate (JVM): the [SourceRegistry] exposes every registered source and
 * [SourceRegistry.syncEnabled] filters by the user's per-source toggle — `manual`
 * is always-on (stub), every other source respects [SyncSettings.sourceEnabled].
 * Future sources (Fitbit, Withings) register identically and appear automatically.
 */
class SourceRegistryTest {
    private fun hc() = FakeHealthDataSource(id = "health_connect", displayName = "Health Connect")

    private fun manual() = FakeHealthDataSource(id = "manual", displayName = "Manual Entry")

    private fun fitbit() = FakeHealthDataSource(id = "fitbit", displayName = "Fitbit")

    @Test
    fun registry_lists_all_registered_sources() {
        val registry = SourceRegistry(listOf(hc(), manual(), fitbit()))

        assertEquals(3, registry.all().size)
        assertEquals("health_connect", registry.byId("health_connect")?.id)
        assertEquals("manual", registry.byId("manual")?.id)
        assertEquals("fitbit", registry.byId("fitbit")?.id)
    }

    @Test
    fun syncEnabled_includes_manual_always_and_user_toggled_sources() {
        val registry = SourceRegistry(listOf(hc(), manual(), fitbit()))
        val settings =
            SyncSettings(
                sourceEnabled = mapOf("health_connect" to true, "fitbit" to false),
                enabledTypes = setOf(HcType.HEART_RATE),
            )

        val enabled = registry.syncEnabled(settings).map { it.id }.toSet()

        assertTrue("manual is always-on", "manual" in enabled)
        assertTrue("health_connect is toggled on", "health_connect" in enabled)
        assertFalse("fitbit is toggled off", "fitbit" in enabled)
    }

    @Test
    fun syncEnabled_excludes_disabled_health_connect() {
        val registry = SourceRegistry(listOf(hc(), manual()))
        val settings = SyncSettings(sourceEnabled = mapOf("health_connect" to false))

        val enabled = registry.syncEnabled(settings).map { it.id }.toSet()

        assertFalse("health_connect is disabled", "health_connect" in enabled)
        assertTrue("manual still on", "manual" in enabled)
    }
}
