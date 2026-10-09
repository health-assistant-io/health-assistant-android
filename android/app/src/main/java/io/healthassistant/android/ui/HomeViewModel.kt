package io.healthassistant.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.healthassistant.android.data.PullSyncRepository
import io.healthassistant.android.data.ServerReachability
import io.healthassistant.android.data.ServerReachabilityMonitor
import io.healthassistant.android.monitoring.SyncMonitor
import io.healthassistant.android.monitoring.SyncMonitorRepository
import io.healthassistant.android.settings.DashboardPrefsRepository
import io.healthassistant.bridge.Allergy
import io.healthassistant.bridge.BridgeClient
import io.healthassistant.bridge.Medication
import io.healthassistant.bridge.NotificationItem
import io.healthassistant.shared.data.BiomarkerOption
import io.healthassistant.shared.data.BiomarkerOptions
import io.healthassistant.shared.data.BiomarkerReading
import io.healthassistant.shared.data.BiomarkerSummary
import io.healthassistant.shared.data.DashboardLayout
import io.healthassistant.shared.data.ExaminationSummary
import io.healthassistant.shared.data.HomeDashboardBuilder
import io.healthassistant.shared.data.HomeViewStyle
import io.healthassistant.shared.data.ObservationPoint
import io.healthassistant.shared.data.cache.CacheMapper
import io.healthassistant.shared.data.repository.BiomarkerCatalogRepository
import io.healthassistant.shared.data.repository.ClinicalRecordRepository
import io.healthassistant.shared.data.repository.ExaminationRepository
import io.healthassistant.shared.data.repository.NotificationRepository
import io.healthassistant.shared.data.repository.ObservationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Immutable UI state for the Home tab. The screen renders this directly; no
 * business logic in the composable. The `editOpen` flag lives here rather than
 * in `remember` so it survives process death.
 */
data class HomeUiState(
    val loading: Boolean = true,
    val monitor: SyncMonitor = SyncMonitor(),
    val readings: List<BiomarkerReading> = emptyList(),
    val options: List<BiomarkerOption> = emptyList(),
    val viewStyle: HomeViewStyle = HomeViewStyle.GRID,
    val shownCodes: Set<String> = DashboardLayout.basicCodes(),
    val order: List<String> = emptyList(),
    val editOpen: Boolean = false,
    val reachability: ServerReachability = ServerReachability.Online,
    // Phase H — active allergies for the Home safety card (H.3).
    val allergies: List<Allergy> = emptyList(),
    // Today merge — the daily check-in sections (meds / exams / inbox).
    val medications: List<Medication> = emptyList(),
    val recentExams: List<ExaminationSummary> = emptyList(),
    val inbox: List<NotificationItem> = emptyList(),
    val unreadCount: Int = 0,
)

/**
 * Owns the Home tab's data flow + state.
 *
 * **Offline-first (M1):** the dashboard cards now read from the on-device
 * observation cache via [ObservationRepository.observeLatest] — a Room-backed
 * reactive `Flow` that emits the merged local (Health Connect) + server
 * (bridge) newest-per-biomarker snapshot, instantly and offline. The network
 * refresh ([reload]) only *writes* into the cache; it never gates whether the
 * cards render. So going offline keeps the saved snapshot on screen instead of
 * clearing to empty. This is the fix for "syncing without displaying".
 *
 * **M2:** the biomarker catalog is cache-backed too ([BiomarkerCatalogRepository]
 * observe + refresh), so display names / units / reference ranges and the edit
 * dialog's option list survive an offline fresh launch. **M6:** the allergies
 * safety card reads from the clinical-record cache
 * ([ClinicalRecordRepository.observeAllergies]) — offline keeps the saved
 * allergies on screen instead of blanking the card.
 */
class HomeViewModel(
    private val client: BridgeClient,
    private val monitorRepo: SyncMonitorRepository,
    private val dashboardPrefs: DashboardPrefsRepository,
    private val repo: ObservationRepository,
    private val biomarkerRepo: BiomarkerCatalogRepository,
    private val recordRepo: ClinicalRecordRepository,
    private val examRepo: ExaminationRepository,
    private val notificationRepo: NotificationRepository,
    private val pullSync: PullSyncRepository,
    private val reachabilityMonitor: ServerReachabilityMonitor,
) : ViewModel() {
    private val refreshKey = MutableStateFlow(0)
    private val editOpen = MutableStateFlow(false)

    // The merged local + server snapshot, straight from the cache (reactive).
    private val cachedLatest: Flow<List<ObservationPoint>> = repo.observeLatest()

    // The cached biomarker catalog (reactive; swapped by refresh).
    private val cachedCatalog: Flow<List<BiomarkerSummary>> = biomarkerRepo.observeAll()

    // The cached allergies for the safety card (reactive).
    private val cachedAllergies: Flow<List<Allergy>> = recordRepo.observeAllergies()

    // Today merge — the daily check-in sections (reactive, cache-backed).
    private val cachedMedications: Flow<List<Medication>> = recordRepo.observeMedications()
    private val cachedExams: Flow<List<ExaminationSummary>> = examRepo.observeAll()
    private val cachedInbox: Flow<Pair<List<NotificationItem>, Int>> =
        combine(notificationRepo.observeAll(), notificationRepo.observeUnreadCount()) { items, unread -> items to unread }

    val state: StateFlow<HomeUiState> =
        combine(
            monitorRepo.monitor,
            dashboardPrefs.viewStyle,
            dashboardPrefs.shownCodes,
            dashboardPrefs.order,
            cachedLatest,
            cachedCatalog,
            cachedAllergies,
            cachedMedications,
            cachedExams,
            cachedInbox,
            editOpen,
            reachabilityMonitor.state,
        ) { values ->
            @Suppress("UNCHECKED_CAST")
            val monitor = values[0] as SyncMonitor
            val viewStyle = values[1] as HomeViewStyle
            val shownCodes = values[2] as Set<String>
            val order = values[3] as List<String>
            val cachedPoints = values[4] as List<ObservationPoint>
            val catalog = values[5] as List<BiomarkerSummary>
            val allergies = values[6] as List<Allergy>
            val medications = values[7] as List<Medication>
            val exams = values[8] as List<ExaminationSummary>
            val inboxPair = values[9] as Pair<List<NotificationItem>, Int>
            val editing = values[10] as Boolean
            val reachability = values[11] as ServerReachability

            val options = BiomarkerOptions.from(catalog, cachedPoints)
            val merged = HomeDashboardBuilder.fromServer(cachedPoints, catalog)
            val cardOptionsByCode = options.associateBy { it.code ?: it.id }
            val shownReadings =
                shownCodes.mapNotNull { code ->
                    val existing = merged.firstOrNull { it.code == code }
                    if (existing != null) {
                        existing
                    } else {
                        cardOptionsByCode[code]?.let { o ->
                            BiomarkerReading(
                                code = code,
                                displayName = o.name,
                                unit = o.unit,
                                referenceRange = o.referenceRange,
                                isTelemetry = o.isTelemetry,
                            )
                        }
                    }
                }
            HomeUiState(
                loading = false,
                monitor = monitor,
                readings = DashboardLayout.filterAndOrder(shownReadings, emptySet(), order),
                options = options,
                viewStyle = viewStyle,
                shownCodes = shownCodes,
                order = order,
                editOpen = editing,
                reachability = reachability,
                allergies = allergies,
                medications = medications.filter { it.status?.uppercase() == "ACTIVE" },
                recentExams = exams.take(3),
                inbox = inboxPair.first.take(3),
                unreadCount = inboxPair.second,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue =
                HomeUiState(
                    loading = true,
                    reachability = reachabilityMonitor.state.value,
                ),
        )

    init {
        // Initial load + every refreshKey bump (pull-to-refresh, "Sync now").
        viewModelScope.launch {
            refreshKey.collect { reload() }
        }
        // Write local Health Connect samples into the cache (the merged source)
        // so observeLatest reflects on-device reads alongside server data.
        viewModelScope.launch {
            monitorRepo.monitor.collect { m ->
                val samples = m.latestReadings.values.map(CacheMapper::fromSample)
                if (samples.isNotEmpty()) runCatching { repo.storeLocal(samples) }
            }
        }
        // Phase G — re-fetch when the server reports clinical mutations (the
        // pull worker bumped the signal). `drop(1)` skips the initial replay so
        // a fresh launch doesn't trigger an extra fetch beyond the one above.
        viewModelScope.launch {
            pullSync.lastPullEpochMs.drop(1).collect { forceRefresh() }
        }
        // Reachability recovery — when the server becomes reachable again
        // (network returned OR the server came back), re-run the network
        // refresh that was skipped (the banner clears via the reactive
        // reachability flow in the state). `drop(1)` skips the initial replay;
        // a StateFlow re-emits only on transitions.
        viewModelScope.launch {
            reachabilityMonitor.state.drop(1).collect { r -> if (r == ServerReachability.Online) reload() }
        }
    }

    /** Refresh the dashboard caches from the bridge (observations latest,
     *  catalog, allergies, medications, exams, inbox — in parallel, per-source
     *  outcomes tolerated). Called on first load + "Sync now" +
     *  pull-to-refresh. When offline the network reads are skipped; the caches
     *  keep rendering the saved snapshot. */
    fun reload() {
        // Re-probe on every user refresh so the banner updates promptly; skip
        // the network reads unless the server is actually reachable (a refresh
        // while unreachable would just time out against a dead host).
        reachabilityMonitor.checkNow()
        if (reachabilityMonitor.state.value != ServerReachability.Online) return
        viewModelScope.launch {
            kotlinx.coroutines.coroutineScope {
                launch { runCatching { biomarkerRepo.refresh() } }
                launch { runCatching { recordRepo.refreshAllergies() } }
                launch { runCatching { recordRepo.refreshMedications() } }
                launch { runCatching { repo.refreshLatest() } }
                launch { runCatching { examRepo.refresh() } }
                launch { runCatching { notificationRepo.refresh(limit = 10) } }
            }
        }
    }

    fun forceRefresh() {
        refreshKey.value++
    }

    fun openEdit() {
        editOpen.value = true
    }

    fun closeEdit() {
        editOpen.value = false
    }

    fun setViewStyle(style: HomeViewStyle) {
        viewModelScope.launch { dashboardPrefs.setViewStyle(style) }
    }

    fun toggleShown(
        code: String,
        shown: Boolean,
    ) {
        viewModelScope.launch {
            if (shown) dashboardPrefs.addCode(code) else dashboardPrefs.removeCode(code)
        }
    }

    fun moveCode(
        code: String,
        delta: Int,
    ) {
        viewModelScope.launch {
            // Re-derive the order from the current shown readings so a move
            // after the user has toggled cards reflects what's on screen.
            val current = state.value.readings.map { it.code }
            dashboardPrefs.setOrder(DashboardLayout.moveCode(current, code, delta))
        }
    }

    fun resetLayout() {
        viewModelScope.launch { dashboardPrefs.reset() }
    }

    companion object {
        /** The factory the Route uses to construct this VM with the
         *  per-connection [BridgeClient] + Koin-resolved deps. */
        fun factory(
            client: BridgeClient,
            monitorRepo: SyncMonitorRepository,
            dashboardPrefs: DashboardPrefsRepository,
            repo: ObservationRepository,
            biomarkerRepo: BiomarkerCatalogRepository,
            recordRepo: ClinicalRecordRepository,
            examRepo: ExaminationRepository,
            notificationRepo: NotificationRepository,
            pullSync: PullSyncRepository,
            reachabilityMonitor: ServerReachabilityMonitor,
        ) = viewModelFactory {
            initializer {
                HomeViewModel(
                    client,
                    monitorRepo,
                    dashboardPrefs,
                    repo,
                    biomarkerRepo,
                    recordRepo,
                    examRepo,
                    notificationRepo,
                    pullSync,
                    reachabilityMonitor,
                )
            }
        }
    }
}
