package io.healthassistant.android.monitoring

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.healthassistant.android.source.HealthConnectSource
import io.healthassistant.android.work.SyncScheduler

/**
 * Stateful owner of the Sync screen (R3; was MonitoringRoute). Thin shim: the
 * [SyncViewModel] (Phase D migration) owns the monitor collection, the HC
 * availability probe, and the "Sync now" / "Couldn't send" / failed-retry /
 * clear-pending actions; this wires the Android touchpoints (HC probe +
 * WorkManager scheduling) into the VM's factory.
 */
@Composable
fun SyncRoute(
    repository: SyncMonitorRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val vm: SyncViewModel =
        viewModel(
            factory =
                SyncViewModel.factory(
                    repository = repository,
                    probeAvailability = { HealthConnectSource(context).isAvailable() },
                    syncNowTrigger = { SyncScheduler.syncNow(context) },
                ),
        )
    val state by vm.state.collectAsStateWithLifecycle()

    SyncScreen(
        monitor = state.monitor,
        onSyncNow = vm::syncNow,
        onViewFailed = vm::toggleFailed,
        showFailed = state.showFailed,
        onRetryFailed = vm::retryFailed,
        onRetryAllFailed = vm::retryAllFailed,
        onClearPending = vm::clearPending,
        onBack = onBack,
    )
}
