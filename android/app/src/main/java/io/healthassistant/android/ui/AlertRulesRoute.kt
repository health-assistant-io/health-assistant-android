package io.healthassistant.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.healthassistant.android.alerts.AlertRulesRepository
import io.healthassistant.android.data.cache.ObservationDatabase
import io.healthassistant.android.data.cache.RoomCaches
import io.healthassistant.android.data.repository.BridgeBiomarkerGateway
import io.healthassistant.bridge.BridgeClient
import io.healthassistant.shared.data.repository.BiomarkerCatalogRepository
import io.healthassistant.shared.data.repository.ConnectivityProvider
import org.koin.compose.koinInject

/**
 * Stateful owner of the alert rules screen. Builds the per-connection
 * biomarker catalog repository (offline-first picker options; the HcType
 * table is the fallback inside the view-model) and delegates to the pure
 * [AlertRulesScreen]. [prefillCode] (the biomarker detail's "Set an alert")
 * opens the editor with the biomarker already fixed.
 */
@Composable
fun AlertRulesRoute(
    client: BridgeClient,
    prefillCode: String?,
    onBack: () -> Unit,
) {
    val db: ObservationDatabase = koinInject()
    val connectivity: ConnectivityProvider = koinInject()
    val rulesRepository: AlertRulesRepository = koinInject()
    val caches = remember(client) { RoomCaches(db, client.integrationId) }
    val biomarkerRepo =
        remember(client) {
            BiomarkerCatalogRepository(caches.biomarkers, BridgeBiomarkerGateway(client), connectivity, caches.meta)
        }
    LaunchedEffect(client) { biomarkerRepo.refresh() }
    val vm: AlertRulesViewModel =
        viewModel(
            key = "alert_rules_${client.integrationId}",
            factory = AlertRulesViewModel.factory(rulesRepository, biomarkerRepo, prefillCode),
        )

    val state by vm.state.collectAsStateWithLifecycle()

    AlertRulesScreen(
        state = state,
        onBack = onBack,
        onAdd = vm::openEditor,
        onToggle = vm::setEnabled,
        onRequestDelete = vm::requestDelete,
        onConfirmDelete = vm::confirmDelete,
        onCancelDelete = vm::cancelDelete,
        onDismissEditor = vm::dismissEditor,
        onPickBiomarker = vm::pickBiomarker,
        onShowBiomarkerPicker = vm::showBiomarkerPicker,
        onSetOp = vm::setOp,
        onSetThreshold = vm::setThreshold,
        onSetRangeLow = vm::setRangeLow,
        onSetRangeHigh = vm::setRangeHigh,
        onSetWindowMinutes = vm::setWindowMinutes,
        onSave = vm::save,
        canSave = vm.currentEditorRule() != null,
    )
}
