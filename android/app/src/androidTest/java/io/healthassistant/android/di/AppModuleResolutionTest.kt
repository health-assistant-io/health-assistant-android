package io.healthassistant.android.di

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.healthassistant.shared.source.ManualEntrySource
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/**
 * Resolves every root the app touches at startup (HAApplication + AppRoot)
 * from the already-started module graph (HAApplication.onCreate starts Koin in
 * this same process). Koin resolves definitions only at runtime, so an
 * interface-vs-concrete binding miss (the M5 AlertEngine.AlertNotifier crash
 * class) keeps compile + unit gates green while the app crash-loops on launch —
 * this test fails on-device instead. No activity launch, so MIUI's
 * background-activity block does not apply.
 */
@RunWith(AndroidJUnit4::class)
class AppModuleResolutionTest {
    @Test
    fun startup_roots_resolve() =
        runTest {
            val koin = GlobalContext.get()
            koin.get<io.healthassistant.android.data.CredentialStore>()
            koin.get<io.healthassistant.android.data.ConnectionRepository>()
            koin.get<io.healthassistant.android.monitoring.SyncMonitorRepository>()
            koin.get<io.healthassistant.android.data.ServerReachabilityMonitor>()
            koin.get<io.healthassistant.android.settings.OnboardingPrefsRepository>()
            koin.get<io.healthassistant.android.settings.UiPreferencesRepository>()
            koin.get<io.healthassistant.android.settings.SyncSettingsRepository>()
            koin.get<ManualEntrySource>()
            koin.get<io.healthassistant.android.alerts.AlertRulesRepository>()
            koin.get<io.healthassistant.android.alerts.AlertEngineHost>()
            koin.get<io.healthassistant.android.widget.WidgetDataRepository>()
        }
}
