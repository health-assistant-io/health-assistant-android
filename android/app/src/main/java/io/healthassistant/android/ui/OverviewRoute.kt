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
import io.healthassistant.android.settings.DashboardPrefsRepository
import io.healthassistant.bridge.BridgeClient
import io.healthassistant.shared.data.cache.CacheDomain
import io.healthassistant.shared.data.repository.BiomarkerCatalogRepository
import io.healthassistant.shared.data.repository.ConnectivityProvider
import io.healthassistant.shared.data.repository.ObservationRepository
import org.koin.compose.koinInject

/**
 * Stateful owner of the wellness overview (Records › Biomarkers › Overview).
 * Builds the per-connection repositories + the [OverviewViewModel], adapts
 * the dashboard prefs store into the VM's [OverviewSelectionStore] seam (the
 * selection survives process death), and feeds the pure [OverviewScreen]
 * with the observations-domain staleness row.
 */
@Composable
fun OverviewRoute(
    client: BridgeClient,
    onBack: () -> Unit,
) {
    val db: ObservationDatabase = koinInject()
    val connectivity: ConnectivityProvider = koinInject()
    val dashboardPrefs: DashboardPrefsRepository = koinInject()
    val caches = remember(client) { RoomCaches(db, client.integrationId) }
    val repo =
        remember(client) {
            ObservationRepository(caches.observations, BridgeObservationGateway(client), connectivity, caches.meta)
        }
    val biomarkerRepo =
        remember(client) {
            BiomarkerCatalogRepository(caches.biomarkers, BridgeBiomarkerGateway(client), connectivity, caches.meta)
        }
    val selectionStore =
        remember(dashboardPrefs) {
            object : OverviewSelectionStore {
                override val codes = dashboardPrefs.overviewCodes

                override suspend fun setCodes(codes: List<String>) {
                    dashboardPrefs.setOverviewCodes(codes)
                }
            }
        }
    val vm: OverviewViewModel = viewModel(factory = OverviewViewModel.factory(repo, biomarkerRepo, selectionStore))

    val state by vm.state.collectAsStateWithLifecycle()
    val staleMeta by caches.meta.observe(CacheDomain.OBSERVATIONS).collectAsStateWithLifecycle(initialValue = null)
    val online = rememberConnectivity() == Connectivity.Online

    OverviewScreen(
        state = state,
        staleMeta = staleMeta,
        online = online,
        onRetry = vm::retry,
        onToggle = vm::toggle,
        onSelectRange = vm::selectRange,
        onSelectStrategy = vm::selectStrategy,
        onBack = onBack,
    )
}
