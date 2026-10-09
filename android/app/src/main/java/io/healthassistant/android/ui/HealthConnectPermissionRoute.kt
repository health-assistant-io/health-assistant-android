package io.healthassistant.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.health.connect.client.PermissionController
import io.healthassistant.android.settings.HealthConnectPermissions
import io.healthassistant.android.settings.SyncSettings
import io.healthassistant.android.settings.SyncSettingsRepository
import io.healthassistant.shared.healthconnect.HcType
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Stateful owner of the post-connect Health Connect guided step (R7). Owns the
 * system permission launcher and persists the granted types to the settings
 * repository. [onDone] is invoked whether the user grants or skips.
 */
@Composable
fun HealthConnectPermissionRoute(onDone: () -> Unit) {
    val repository: SyncSettingsRepository = koinInject()
    val scope = rememberCoroutineScope()

    val launcher =
        rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            val enabledTypes = HcType.entries.filter { HealthConnectPermissions.forType(it) in granted }
            scope.launch {
                repository.setSourceEnabled(SyncSettings.SOURCE_HEALTH_CONNECT, enabledTypes.isNotEmpty())
                repository.setEnabledTypes(enabledTypes.toSet())
            }
            onDone()
        }

    HealthConnectPermissionScreen(
        onGrant = {
            launcher.launch(HealthConnectPermissions.forTypes(HcType.entries.toSet()))
        },
        onSkip = onDone,
    )
}
