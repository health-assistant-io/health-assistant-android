package io.healthassistant.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.healthassistant.android.data.AppLockManager
import io.healthassistant.android.settings.UiPreferences
import io.healthassistant.android.settings.UiPreferencesRepository
import io.healthassistant.android.ui.AppLockRoute
import io.healthassistant.android.ui.AppRoot
import io.healthassistant.android.ui.theme.HATheme
import io.healthassistant.android.ui.theme.ThemePreset
import io.healthassistant.android.widget.WidgetDeepLinks
import io.healthassistant.shared.onboarding.Onboarding
import org.koin.compose.koinInject

/**
 * Phase B.4 — extends [AppCompatActivity] (was ComponentActivity) so
 * androidx.biometric.BiometricPrompt can attach its headless fragment for
 * the system biometric UI. AppCompatActivity is a strict superset of
 * ComponentActivity, so the existing Compose + activity-result contracts
 * still bind cleanly.
 *
 * Also wires a [ProcessLifecycleOwner] observer that feeds the
 * [AppLockManager.onBackgrounded] / [onForeground] hooks — that's how the
 * 60s grace window is enforced app-wide (not per-activity).
 */
class MainActivity : AppCompatActivity() {
    /** M4 (widgets) — the route a widget tap asked to open, observed by
     *  [AppRoot]'s navigation. Compose-state (not a field read once) so a tap
     *  on an already-open app re-fires through [onNewIntent]. */
    private val widgetOpenRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetOpenRoute.value = routeFromWidgetIntent(intent)
        // R8: keep the branded launch screen visible until Compose's first frame.
        installSplashScreen()
        enableEdgeToEdge()
        // "Open in app" deep link (healthassistant://connect?base_url=…&integration_id=…).
        val deepLink = intent?.dataString?.let(Onboarding::parseDeepLink)

        // App-wide background/foreground → AppLockManager grace-window logic.
        // ProcessLifecycleOwner sees the whole app process (not just this
        // activity), so a quick hop to Messages and back is one ON_STOP /
        // ON_START pair regardless of which activity was foreground.
        val app = applicationContext as HAApplication
        val lock = app.appLockManager
        val observer =
            LifecycleEventObserver { _: LifecycleOwner, event: Lifecycle.Event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> lock.onBackgrounded()
                    Lifecycle.Event.ON_START -> lock.onForeground()
                    else -> Unit
                }
            }
        ProcessLifecycleOwner.get().lifecycle.addObserver(observer)

        setContent {
            val uiPrefsRepository: UiPreferencesRepository = koinInject()
            val uiPrefs by uiPrefsRepository.uiPreferences.collectAsState(initial = UiPreferences())
            // The lock gate — when locked, AppLockRoute substitutes for the
            // whole AppRoot so the navigation state behind the lock can't be
            // observed.
            val lockManager: AppLockManager = koinInject()
            val isLocked by lockManager.isLocked.collectAsState()
            HATheme(
                highContrast = uiPrefs.highContrast,
                preset =
                    when (uiPrefs.theme) {
                        io.healthassistant.android.settings.UiTheme.TEAL -> ThemePreset.TEAL
                        io.healthassistant.android.settings.UiTheme.MATERIAL_YOU -> ThemePreset.MATERIAL_YOU
                        io.healthassistant.android.settings.UiTheme.AURORA -> ThemePreset.AURORA
                    },
                // Only an explicit "on" forces reduce-motion; otherwise follow the
                // OS Developer-Options setting (null = system).
                reduceMotion = uiPrefs.reduceMotion.takeIf { it },
            ) {
                if (isLocked) {
                    AppLockRoute()
                } else {
                    AppRoot(prefilled = deepLink, openRoute = widgetOpenRoute.value)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeFromWidgetIntent(intent)?.let { widgetOpenRoute.value = it }
    }

    private fun routeFromWidgetIntent(intent: Intent?): String? =
        intent?.getStringExtra(WidgetDeepLinks.EXTRA_OPEN_ROUTE)?.takeIf { it.isNotBlank() }
}
