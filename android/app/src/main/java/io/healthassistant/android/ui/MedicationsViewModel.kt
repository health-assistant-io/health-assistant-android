package io.healthassistant.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.healthassistant.android.data.PullSyncRepository
import io.healthassistant.bridge.BridgeClient
import io.healthassistant.bridge.Medication
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
 * Phase H — immutable UI state for the Medications screen. [medications] keeps
 * its last value across refreshes (a silent reload after add/delete doesn't
 * blank the list); [loading] is true only for the first load; [error] holds a
 * human-readable failure message.
 */
data class MedicationsUiState(
    val loading: Boolean = true,
    val medications: List<Medication> = emptyList(),
    val error: String? = null,
    val submitting: Boolean = false,
)

/**
 * Phase H — owns the Medications screen (list + add + delete).
 *
 * **Offline-first (M5):** the list reads from the clinical-record cache via
 * [ClinicalRecordRepository.observeMedications] (Room-backed, reactive,
 * offline). [reload] only refreshes the cache; offline or a failed refresh
 * keeps the saved list on screen (error only when the cache is empty AND the
 * refresh can't succeed). Mutations build an `org.json.JSONObject` body and go
 * through the generic `requestText` (kotlinx-serialization-json is not on
 * :app's compile classpath, only the SDK's impl); a successful delete drops
 * the cached row instantly.
 *
 * Observes the Phase G [PullSyncRepository.lastPullEpochMs] signal so a med
 * added on the PWA surfaces here automatically (`drop(1)` skips the initial
 * replay — first load is the explicit [reload] in `init`).
 */
class MedicationsViewModel(
    private val client: BridgeClient,
    private val repo: ClinicalRecordRepository,
    pullSync: PullSyncRepository? = null,
) : ViewModel() {
    private val submitting = MutableStateFlow(false)
    private val refreshOutcome = MutableStateFlow<RefreshOutcome?>(null)

    val state: StateFlow<MedicationsUiState> =
        combine(repo.observeMedications(), refreshOutcome, submitting) { meds, outcome, submittingFlag ->
            when {
                meds.isNotEmpty() -> MedicationsUiState(loading = false, medications = meds, submitting = submittingFlag)
                outcome == null -> MedicationsUiState(loading = true, submitting = submittingFlag)
                outcome == RefreshOutcome.REFRESHED ->
                    MedicationsUiState(
                        loading = false,
                        medications = meds,
                        submitting = submittingFlag,
                    )
                else -> MedicationsUiState(loading = false, error = "load failed", submitting = submittingFlag)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, MedicationsUiState())

    init {
        reload()
        if (pullSync != null) {
            viewModelScope.launch {
                pullSync.lastPullEpochMs.drop(1).collect { reload() }
            }
        }
    }

    /** Refresh the medication cache from the bridge (no-op on the network when
     *  offline — the cache keeps rendering the saved list). */
    fun reload() {
        viewModelScope.launch {
            refreshOutcome.value = repo.refreshMedications()
        }
    }

    /** Add a medication. [name] is required; [dosage] optional. On success the
     *  list silently reloads. */
    fun add(
        name: String,
        dosage: String?,
    ) {
        if (name.isBlank()) return
        viewModelScope.launch {
            submitting.value = true
            val body = ClinicalRecordBodies.medicationCreateBody(name, dosage)
            val ok = runCatching { client.requestText("POST", "/medications", body) }.isSuccess
            submitting.value = false
            if (!ok) refreshOutcome.value = RefreshOutcome.FAILED
            reload()
        }
    }

    /** Soft-delete a medication, drop the cached row, then silently refresh. */
    fun delete(id: String) {
        viewModelScope.launch {
            val ok = runCatching { client.requestText("DELETE", "/medications/$id") }.isSuccess
            if (ok) repo.onMedicationDeleted(id) else refreshOutcome.value = RefreshOutcome.FAILED
            reload()
        }
    }

    companion object {
        fun factory(
            client: BridgeClient,
            repo: ClinicalRecordRepository,
            pullSync: PullSyncRepository? = null,
        ) = viewModelFactory {
            initializer { MedicationsViewModel(client, repo, pullSync) }
        }
    }
}
