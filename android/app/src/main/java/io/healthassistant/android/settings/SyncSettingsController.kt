package io.healthassistant.android.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.PermissionController
import io.healthassistant.android.source.HealthConnectSource
import io.healthassistant.shared.healthconnect.HcType
import kotlinx.coroutines.launch

/** The settings state + user-intent callbacks shared by the standalone Sync
 *  settings screen and the Profile tab's embedded settings section (R6).
 *  Collected from [SyncSettingsRepository] once, permission launcher included. */
class SyncSettingsController(
    val settings: SyncSettings,
    val sourceAvailable: Boolean,
    val onToggleSource: (Boolean) -> Unit,
    val onToggleType: (HcType, Boolean) -> Unit,
    val onRequestTypePermission: (Set<String>) -> Unit,
    val onSetInterval: (Int) -> Unit,
    val onSetBackgroundReads: (Boolean) -> Unit,
    val onSetBatteryWhitelist: (Boolean) -> Unit,
    val onSetHistoryWindow: (SyncHistoryWindow) -> Unit,
    val onResetCursors: () -> Unit,
)

/**
 * Owns the sync-settings flow + Health Connect availability probe + the
 * per-type permission launcher, so [SyncSettingsScreen] and the Profile tab
 * share one wiring. The permission gate:
 *
 * - Toggling a type **on** → [SyncSettingsController.onRequestTypePermission]
 *   optimistically enables the type (checkbox responds instantly) and launches
 *   the Health Connect permission request via
 *   [PermissionController.createRequestPermissionResultContract] (the correct
 *   contract on API 28-33, where HC permissions are managed by the HC app, not
 *   the platform controller). If denied, the type is reverted.
 * - Toggling a type **off** → persisted directly (no permission needed).
 */
@Composable
fun rememberSyncSettingsController(
    repository: SyncSettingsRepository,
    sourceId: String = SyncSettings.SOURCE_HEALTH_CONNECT,
): SyncSettingsController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val settings by repository.settings.collectAsState(initial = SyncSettings())
    var sourceAvailable by remember { mutableStateOf(false) }
    var pendingType by remember { mutableStateOf<HcType?>(null) }

    LaunchedEffect(Unit) {
        sourceAvailable = HealthConnectSource(context).isAvailable()
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            val type = pendingType
            pendingType = null
            if (type != null && HealthConnectPermissions.forType(type) !in granted) {
                scope.launch { repository.toggleType(type, false) }
            }
        }

    return SyncSettingsController(
        settings = settings,
        sourceAvailable = sourceAvailable,
        onToggleSource = { enabled ->
            scope.launch { repository.setSourceEnabled(sourceId, enabled) }
        },
        onToggleType = { type, enabled ->
            scope.launch { repository.toggleType(type, enabled) }
        },
        onRequestTypePermission = { perms ->
            val type = HcType.entries.firstOrNull { HealthConnectPermissions.forType(it) in perms }
            if (type != null) {
                scope.launch { repository.toggleType(type, true) }
                pendingType = type
                permissionLauncher.launch(perms)
            }
        },
        onSetInterval = { minutes ->
            scope.launch { repository.setSyncIntervalMinutes(minutes) }
        },
        onSetBackgroundReads = { enabled ->
            scope.launch { repository.setBackgroundReadsEnabled(enabled) }
        },
        onSetBatteryWhitelist = { whitelisted ->
            scope.launch { repository.setBatteryOptimizationWhitelisted(whitelisted) }
        },
        onSetHistoryWindow = { window ->
            scope.launch { repository.setHistoryWindow(window) }
        },
        onResetCursors = {
            scope.launch { repository.resetCursors() }
        },
    )
}
