package io.healthassistant.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.healthassistant.android.data.ConnectivityRepository
import io.healthassistant.android.data.PullSyncRepository
import io.healthassistant.android.data.ServerReachabilityMonitor
import io.healthassistant.android.data.cache.ObservationDatabase
import io.healthassistant.android.data.cache.RoomCaches
import io.healthassistant.android.data.repository.BridgeBiomarkerGateway
import io.healthassistant.android.data.repository.BridgeClinicalRecordGateway
import io.healthassistant.android.data.repository.BridgeExaminationGateway
import io.healthassistant.android.data.repository.BridgeNotificationGateway
import io.healthassistant.android.data.repository.BridgeObservationGateway
import io.healthassistant.android.monitoring.SyncMonitorRepository
import io.healthassistant.android.settings.DashboardPrefsRepository
import io.healthassistant.android.ui.components.rememberActionHaptic
import io.healthassistant.bridge.BridgeClient
import io.healthassistant.shared.data.cache.CacheDomain
import io.healthassistant.shared.data.repository.BiomarkerCatalogRepository
import io.healthassistant.shared.data.repository.ClinicalRecordRepository
import io.healthassistant.shared.data.repository.ExaminationRepository
import io.healthassistant.shared.data.repository.NotificationRepository
import io.healthassistant.shared.data.repository.ObservationRepository
import org.koin.compose.koinInject

/**
 * Stateful owner of the Home tab (Phase D refactor).
 *
 * Was: 166 lines of `LaunchedEffect` + `remember(mutableStateOf)` inside the
 * composable — state lost on process death, hard to unit-test, business logic
 * mixed with rendering.
 *
 * Now: a thin Compose shim that builds the [HomeViewModel] via its factory
 * (the per-connection [BridgeClient] flows in; the rest of the deps are
 * Koin-resolved here) + collects its single [HomeUiState] flow via
 * `collectAsStateWithLifecycle`. Every action is a one-liner delegate to the
 * VM. The pure-state [HomeScreen] composable is unchanged.
 *
 * K.3: in SIMPLE mode ([simpleMode]) the advanced dashboard controls (the
 * view-style menu + the edit dialog) are not passed down — the pure screen
 * already hides controls whose callbacks are null — and the cards render in
 * the large-print SIMPLE style via [effectiveHomeViewStyle].
 */
@Composable
fun HomeRoute(
    client: BridgeClient,
    repository: SyncMonitorRepository,
    connectionLabel: String?,
    onSyncNow: () -> Unit,
    onSwitchConnection: (() -> Unit)?,
    onOpenSync: () -> Unit,
    onOpenInsights: (String) -> Unit,
    onOpenExam: (String) -> Unit,
    onOpenInbox: () -> Unit,
    onOpenAssistant: (() -> Unit)? = null,
    simpleMode: Boolean = false,
) {
    val dashboardPrefs: DashboardPrefsRepository = koinInject()
    val db: ObservationDatabase = koinInject()
    val connectivity: ConnectivityRepository = koinInject()
    val reachabilityMonitor: ServerReachabilityMonitor = koinInject()
    val pullSync: PullSyncRepository = koinInject()
    // Offline-first M1/M2/M9: the repositories are the single read source for
    // the dashboard (observations + biomarker catalog + records). Built per
    // active connection — the gateways wrap `client`, the caches are scoped by
    // its `integrationId` — so a connection switch rebuilds them via
    // `remember(client)`.
    val caches = remember(client) { RoomCaches(db, client.integrationId) }
    val repo =
        remember(client) {
            ObservationRepository(caches.observations, BridgeObservationGateway(client), connectivity, caches.meta)
        }
    val biomarkerRepo =
        remember(client) {
            BiomarkerCatalogRepository(caches.biomarkers, BridgeBiomarkerGateway(client), connectivity, caches.meta)
        }
    val recordRepo =
        remember(client) {
            ClinicalRecordRepository(caches.records, BridgeClinicalRecordGateway(client), connectivity, caches.meta)
        }
    val examRepo =
        remember(client) {
            ExaminationRepository(caches.examinations, BridgeExaminationGateway(client), connectivity, caches.meta)
        }
    val notificationRepo =
        remember(client) {
            NotificationRepository(caches.notifications, BridgeNotificationGateway(client), connectivity, caches.meta)
        }
    val vm: HomeViewModel =
        viewModel(
            factory =
                HomeViewModel.factory(
                    client,
                    repository,
                    dashboardPrefs,
                    repo,
                    biomarkerRepo,
                    recordRepo,
                    examRepo,
                    notificationRepo,
                    pullSync,
                    reachabilityMonitor,
                    connectionLabel,
                ),
        )
    val state by vm.state.collectAsStateWithLifecycle()
    val actionHaptic = rememberActionHaptic()
    // Offline-first M8 — the saved-data banner source: the observations
    // staleness row of the active connection + the live connectivity flag.
    val staleMeta by caches.meta.observe(CacheDomain.OBSERVATIONS).collectAsStateWithLifecycle(initialValue = null)
    val online = rememberConnectivity() == Connectivity.Online

    if (state.editOpen) {
        DashboardEditDialog(
            options = state.options,
            shownCodes = state.shownCodes,
            order = state.order,
            onToggleShown = { code, shown -> vm.toggleShown(code, shown) },
            onMove = { code, delta -> vm.moveCode(code, delta) },
            onReset = { vm.resetLayout() },
            onDismiss = { vm.closeEdit() },
        )
    }

    HomeScreen(
        monitor = state.monitor,
        readings = state.readings,
        connectionLabel = state.connectionLabel,
        reachability = state.reachability,
        staleMeta = staleMeta,
        online = online,
        medications = state.medications,
        recentExams = state.recentExams,
        inbox = state.inbox,
        unreadCount = state.unreadCount,
        viewStyle = effectiveHomeViewStyle(state.viewStyle, simpleMode),
        allergies = state.allergies,
        onCycleViewStyle =
            if (simpleMode) {
                null
            } else {
                { style ->
                    actionHaptic()
                    vm.setViewStyle(style)
                }
            },
        onOpenEdit =
            if (simpleMode) {
                null
            } else {
                {
                    actionHaptic()
                    vm.openEdit()
                }
            },
        onSyncNow = {
            actionHaptic()
            vm.forceRefresh()
            onSyncNow()
        },
        onSwitchConnection = onSwitchConnection,
        onOpenSync = onOpenSync,
        onOpenInsights = onOpenInsights,
        onOpenExam = onOpenExam,
        onOpenInbox = onOpenInbox,
        onOpenAssistant = onOpenAssistant,
    )
}
