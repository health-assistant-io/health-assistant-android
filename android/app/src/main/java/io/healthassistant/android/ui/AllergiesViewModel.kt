package io.healthassistant.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.healthassistant.android.data.PullSyncRepository
import io.healthassistant.bridge.Allergy
import io.healthassistant.bridge.BridgeClient
import io.healthassistant.shared.data.ClinicalRecordBodies
import io.healthassistant.shared.data.repository.ClinicalRecordRepository
import io.healthassistant.shared.data.repository.RefreshOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Phase H — immutable UI state for the Allergies screen. Mirrors
 * [MedicationsUiState]: the list survives silent refreshes, [loading] is true
 * only for the first load, [error] holds a failure message.
 */
data class AllergiesUiState(
    val loading: Boolean = true,
    val allergies: List<Allergy> = emptyList(),
    val error: String? = null,
    val submitting: Boolean = false,
)

/**
 * Phase H — owns the Allergies screen (list + add + delete).
 *
 * **Offline-first (M5):** the list reads from the clinical-record cache via
 * [ClinicalRecordRepository.observeAllergies] (Room-backed, reactive,
 * offline). [reload] only refreshes the cache; offline or a failed refresh
 * keeps the saved list on screen. Mutations use the `org.json.JSONObject` +
 * `requestText` pattern; a successful delete drops the cached row instantly.
 *
 * Observes the Phase G [PullSyncRepository.lastPullEpochMs] signal so an allergy
 * added on the PWA surfaces here automatically.
 */
class AllergiesViewModel(
    private val client: BridgeClient,
    private val repo: ClinicalRecordRepository,
    pullSync: PullSyncRepository? = null,
) : ViewModel() {
    private val submitting = MutableStateFlow(false)
    private val refreshOutcome = MutableStateFlow<RefreshOutcome?>(null)

    val state: StateFlow<AllergiesUiState> =
        combine(repo.observeAllergies(), refreshOutcome, submitting) { allergies, outcome, submittingFlag ->
            when {
                allergies.isNotEmpty() -> AllergiesUiState(loading = false, allergies = allergies, submitting = submittingFlag)
                outcome == null -> AllergiesUiState(loading = true, submitting = submittingFlag)
                outcome == RefreshOutcome.REFRESHED ->
                    AllergiesUiState(
                        loading = false,
                        allergies = allergies,
                        submitting = submittingFlag,
                    )
                else -> AllergiesUiState(loading = false, error = "load failed", submitting = submittingFlag)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, AllergiesUiState())

    init {
        reload()
        if (pullSync != null) {
            viewModelScope.launch {
                pullSync.lastPullEpochMs.drop(1).collect { reload() }
            }
        }
    }

    /** Refresh the allergy cache from the bridge (no-op on the network when
     *  offline). */
    fun reload() {
        viewModelScope.launch {
            refreshOutcome.value = repo.refreshAllergies()
        }
    }

    /** Add an allergy. [name] is required; [criticality] optional. */
    fun add(
        name: String,
        criticality: String?,
    ) {
        if (name.isBlank()) return
        viewModelScope.launch {
            submitting.value = true
            val body = ClinicalRecordBodies.allergyCreateBody(name, criticality)
            val ok = runCatching { client.requestText("POST", "/allergies", body) }.isSuccess
            submitting.value = false
            if (!ok) refreshOutcome.value = RefreshOutcome.FAILED
            reload()
        }
    }

    /** Soft-delete an allergy, drop the cached row, then silently refresh. */
    fun delete(id: String) {
        viewModelScope.launch {
            val ok = runCatching { client.requestText("DELETE", "/allergies/$id") }.isSuccess
            if (ok) repo.onAllergyDeleted(id) else refreshOutcome.value = RefreshOutcome.FAILED
            reload()
        }
    }

    companion object {
        fun factory(
            client: BridgeClient,
            repo: ClinicalRecordRepository,
            pullSync: PullSyncRepository? = null,
        ) = viewModelFactory {
            initializer { AllergiesViewModel(client, repo, pullSync) }
        }
    }
}
