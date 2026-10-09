package io.healthassistant.android.monitoring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** UI state for the Sync screen (plain-language Monitoring, R3). */
data class SyncUiState(
    val monitor: SyncMonitor = SyncMonitor(),
    val showFailed: Boolean = false,
)

/**
 * Owns the Sync screen's data flow + state (Phase D migration). Collects the
 * [SyncMonitorRepository.monitor] snapshot, probes Health Connect
 * availability once, and drives the "Sync now" / "Couldn't send" /
 * failed-retry / clear-pending actions.
 *
 * Android touchpoints are injected as lambdas so the VM stays JVM-pure:
 * [probeAvailability] wraps the `HealthConnectSource.isAvailable()` probe and
 * [syncNowTrigger] wraps `SyncScheduler.syncNow(context)` (WorkManager).
 */
class SyncViewModel(
    private val repository: SyncMonitorRepository,
    private val probeAvailability: suspend () -> Boolean,
    private val syncNowTrigger: () -> Unit,
) : ViewModel() {
    private val showFailed = MutableStateFlow(false)

    val state: StateFlow<SyncUiState> =
        combine(repository.monitor, showFailed) { monitor, failed ->
            SyncUiState(monitor = monitor, showFailed = failed)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SyncUiState())

    init {
        viewModelScope.launch {
            val available = probeAvailability()
            repository.setSourceAvailability("health_connect", available, available)
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            repository.recordSyncResult("health_connect", emptyMap(), SyncStatus.Syncing)
            syncNowTrigger()
            repository.refresh()
        }
    }

    fun toggleFailed() {
        showFailed.value = !showFailed.value
        viewModelScope.launch { repository.refresh() }
    }

    fun retryFailed(id: String) {
        viewModelScope.launch {
            repository.retryDeadLetter(id)
            syncNowTrigger()
        }
    }

    fun retryAllFailed() {
        viewModelScope.launch {
            repository.retryAllDeadLetters()
            syncNowTrigger()
        }
    }

    fun clearPending() {
        viewModelScope.launch { repository.clearOutbox() }
    }

    companion object {
        fun factory(
            repository: SyncMonitorRepository,
            probeAvailability: suspend () -> Boolean,
            syncNowTrigger: () -> Unit,
        ) = viewModelFactory {
            initializer { SyncViewModel(repository, probeAvailability, syncNowTrigger) }
        }
    }
}
