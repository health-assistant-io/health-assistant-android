package io.healthassistant.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.healthassistant.android.settings.UiPreferences
import io.healthassistant.android.settings.UiPreferencesRepository
import io.healthassistant.shared.onboarding.ConnectionCredential
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Stateful owner of the Profile tab. Thin shim: the hub screen is pure state
 * + navigation lambdas — every section lives on its own detail page
 * (see [ProfileDetailScreens.kt]); this route just forwards the nav intents
 * and owns the Simple/Advanced mode switch (K-simple-mode: instant, no
 * restart — the DataStore flow re-emits and the shell recomposes).
 */
@Composable
fun ProfileRoute(
    credential: ConnectionCredential?,
    onSwitchConnection: (() -> Unit)?,
    onOpenWebApp: (() -> Unit)? = null,
    onOpenSync: (() -> Unit)?,
    onOpenSyncSettings: (() -> Unit)?,
    onOpenDataStorage: (() -> Unit)?,
    onOpenAlerts: (() -> Unit)? = null,
    onOpenServerNotifications: (() -> Unit)?,
    onOpenDeviceNotifications: (() -> Unit)?,
    onOpenAccessibility: (() -> Unit)?,
    onOpenPrivacy: () -> Unit,
    onOpenAbout: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val uiPrefsRepository: UiPreferencesRepository = koinInject()
    val uiPreferences by uiPrefsRepository.uiPreferences.collectAsStateWithLifecycle(initialValue = UiPreferences())
    val scope = rememberCoroutineScope()

    ProfileScreen(
        connectionLabel = credential?.baseUrl,
        connectionId = credential?.integrationId,
        onSwitchConnection = onSwitchConnection,
        onOpenWebApp = onOpenWebApp,
        onOpenSync = onOpenSync,
        onOpenSyncSettings = onOpenSyncSettings,
        onOpenDataStorage = onOpenDataStorage,
        onOpenAlerts = onOpenAlerts,
        onOpenServerNotifications = onOpenServerNotifications,
        onOpenDeviceNotifications = onOpenDeviceNotifications,
        onOpenAccessibility = onOpenAccessibility,
        onOpenPrivacy = onOpenPrivacy,
        onOpenAbout = onOpenAbout,
        onDisconnect = onDisconnect,
        mode = uiPreferences.mode,
        onSetMode = { mode ->
            scope.launch { uiPrefsRepository.setMode(mode) }
        },
        theme = uiPreferences.theme,
        onSetTheme = { theme ->
            scope.launch { uiPrefsRepository.setTheme(theme) }
        },
    )
}
