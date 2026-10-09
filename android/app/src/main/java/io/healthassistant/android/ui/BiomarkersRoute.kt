package io.healthassistant.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.healthassistant.android.data.cache.ObservationDatabase
import io.healthassistant.android.data.cache.RoomCaches
import io.healthassistant.android.data.repository.BridgeBiomarkerGateway
import io.healthassistant.android.data.repository.BridgeObservationGateway
import io.healthassistant.bridge.BridgeClient
import io.healthassistant.shared.data.cache.CacheDomain
import io.healthassistant.shared.data.repository.BiomarkerCatalogRepository
import io.healthassistant.shared.data.repository.ConnectivityProvider
import io.healthassistant.shared.data.repository.ObservationRepository
import org.koin.compose.koinInject

/**
 * Stateful owner of the Biomarkers list (Records › Biomarkers). Thin shim:
 * builds the per-connection repositories (the gateways wrap the active
 * [BridgeClient], so a connection switch rebuilds them) + the
 * [BiomarkersViewModel] and collects its single state flow. The wellness
 * overview entry row (M6) shows in ADVANCED only (`showOverview`).
 */
@Composable
fun BiomarkersRoute(
    client: BridgeClient,
    onOpenBiomarker: (String) -> Unit,
    showOverview: Boolean = false,
    onOpenOverview: () -> Unit = {},
    onBack: () -> Unit,
) {
    val db: ObservationDatabase = koinInject()
    val connectivity: ConnectivityProvider = koinInject()
    val caches = remember(client) { RoomCaches(db, client.integrationId) }
    val repo =
        remember(client) {
            ObservationRepository(caches.observations, BridgeObservationGateway(client), connectivity, caches.meta)
        }
    val biomarkerRepo =
        remember(client) {
            BiomarkerCatalogRepository(caches.biomarkers, BridgeBiomarkerGateway(client), connectivity, caches.meta)
        }
    val vm: BiomarkersViewModel =
        viewModel(factory = BiomarkersViewModel.factory(repo, biomarkerRepo))
    val state by vm.state.collectAsStateWithLifecycle()
    val staleMeta by caches.meta.observe(CacheDomain.OBSERVATIONS).collectAsStateWithLifecycle(initialValue = null)
    val online = rememberConnectivity() == Connectivity.Online

    BiomarkersScreen(
        state = state,
        staleMeta = staleMeta,
        online = online,
        onRetry = vm::reload,
        onQueryChange = vm::setQuery,
        onToggleShowAll = vm::setShowAll,
        onOpenBiomarker = onOpenBiomarker,
        showOverview = showOverview,
        onOpenOverview = onOpenOverview,
        onBack = onBack,
    )
}
