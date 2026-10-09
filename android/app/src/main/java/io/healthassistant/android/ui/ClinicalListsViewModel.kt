package io.healthassistant.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.healthassistant.android.data.PullSyncRepository
import io.healthassistant.bridge.ClinicalEvent
import io.healthassistant.bridge.Vaccine
import io.healthassistant.shared.data.repository.ClinicalRecordRepository
import io.healthassistant.shared.data.repository.RefreshOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Phase H — UI state for the Vaccines screen (list-only for v2). */
data class VaccinesUiState(
    val loading: Boolean = true,
    val vaccines: List<Vaccine> = emptyList(),
    val error: String? = null,
)

/** Phase H — UI state for the Clinical Events screen (list-only for v2). */
data class ClinicalEventsUiState(
    val loading: Boolean = true,
    val events: List<ClinicalEvent> = emptyList(),
    val error: String? = null,
)

/**
 * Phase H — owns the Vaccines screen (list-only for v2; the bridge CRUD paths
 * for vaccines exist, add/edit is a follow-up).
 *
 * **Offline-first (M5):** the list reads from the clinical-record cache via
 * [ClinicalRecordRepository.observeVaccines] (Room-backed, reactive, offline);
 * [reload] only refreshes the cache. Also reloads on the Phase G pull signal
 * so a vaccination logged on the PWA shows up here automatically.
 */
class VaccinesViewModel(
    private val repo: ClinicalRecordRepository,
    pullSync: PullSyncRepository? = null,
) : ViewModel() {
    private val refreshOutcome = MutableStateFlow<RefreshOutcome?>(null)

    val state: StateFlow<VaccinesUiState> =
        combine(repo.observeVaccines(), refreshOutcome) { vaccines, outcome ->
            when {
                vaccines.isNotEmpty() -> VaccinesUiState(loading = false, vaccines = vaccines)
                outcome == null -> VaccinesUiState(loading = true)
                outcome == RefreshOutcome.REFRESHED -> VaccinesUiState(loading = false, vaccines = vaccines)
                else -> VaccinesUiState(loading = false, error = "load failed")
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, VaccinesUiState())

    init {
        reload()
        if (pullSync != null) {
            viewModelScope.launch {
                pullSync.lastPullEpochMs.drop(1).collect { reload() }
            }
        }
    }

    /** Refresh the vaccine cache from the bridge (no-op on the network when
     *  offline). */
    fun reload() {
        viewModelScope.launch {
            refreshOutcome.value = repo.refreshVaccines()
        }
    }

    companion object {
        fun factory(
            repo: ClinicalRecordRepository,
            pullSync: PullSyncRepository? = null,
        ) = viewModelFactory {
            initializer { VaccinesViewModel(repo, pullSync) }
        }
    }
}

/**
 * Phase H — owns the Clinical Events screen (list-only for v2; the rich event
 * detail + occurrence logging stay in the PWA).
 *
 * **Offline-first (M5):** the list reads from the clinical-record cache via
 * [ClinicalRecordRepository.observeClinicalEvents] (Room-backed, reactive,
 * offline); [reload] only refreshes the cache. Also reloads on the Phase G
 * pull signal.
 */
class ClinicalEventsViewModel(
    private val repo: ClinicalRecordRepository,
    pullSync: PullSyncRepository? = null,
) : ViewModel() {
    private val refreshOutcome = MutableStateFlow<RefreshOutcome?>(null)

    val state: StateFlow<ClinicalEventsUiState> =
        combine(repo.observeClinicalEvents(), refreshOutcome) { events, outcome ->
            when {
                events.isNotEmpty() -> ClinicalEventsUiState(loading = false, events = events)
                outcome == null -> ClinicalEventsUiState(loading = true)
                outcome == RefreshOutcome.REFRESHED -> ClinicalEventsUiState(loading = false, events = events)
                else -> ClinicalEventsUiState(loading = false, error = "load failed")
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ClinicalEventsUiState())

    init {
        reload()
        if (pullSync != null) {
            viewModelScope.launch {
                pullSync.lastPullEpochMs.drop(1).collect { reload() }
            }
        }
    }

    /** Refresh the clinical-event cache from the bridge (no-op on the network
     *  when offline). */
    fun reload() {
        viewModelScope.launch {
            refreshOutcome.value = repo.refreshClinicalEvents()
        }
    }

    companion object {
        fun factory(
            repo: ClinicalRecordRepository,
            pullSync: PullSyncRepository? = null,
        ) = viewModelFactory {
            initializer { ClinicalEventsViewModel(repo, pullSync) }
        }
    }
}
