package io.healthassistant.android.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import io.healthassistant.android.BuildConfig
import io.healthassistant.android.R
import io.healthassistant.android.alerts.AlertEngineHost
import io.healthassistant.android.data.ConnectionRepository
import io.healthassistant.android.data.CredentialStore
import io.healthassistant.android.data.ServerReachabilityMonitor
import io.healthassistant.android.debug.DebugCommand
import io.healthassistant.android.debug.DebugDriver
import io.healthassistant.android.monitoring.SyncMonitorRepository
import io.healthassistant.android.monitoring.SyncRoute
import io.healthassistant.android.monitoring.SyncStatus
import io.healthassistant.android.settings.OnboardingPrefsRepository
import io.healthassistant.android.settings.SyncSettingsRepository
import io.healthassistant.android.settings.UiMode
import io.healthassistant.android.settings.UiPreferences
import io.healthassistant.android.settings.UiPreferencesRepository
import io.healthassistant.android.source.HealthConnectSource
import io.healthassistant.android.ui.navigation.HABottomBar
import io.healthassistant.android.ui.navigation.HARoutes
import io.healthassistant.android.ui.navigation.HATab
import io.healthassistant.android.ui.theme.LocalReduceMotion
import io.healthassistant.android.web.WebAppLauncher
import io.healthassistant.android.web.webAppUrl
import io.healthassistant.android.work.PushRegistration
import io.healthassistant.android.work.SyncScheduler
import io.healthassistant.bridge.BridgeClient
import io.healthassistant.shared.healthconnect.HcType
import io.healthassistant.shared.healthconnect.RawSample
import io.healthassistant.shared.onboarding.ConnectionCredential
import io.healthassistant.shared.source.ManualEntrySource
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Root nav. R1 replaces the old `enum Screen` + `when` switch with a Jetpack
 * Navigation [NavHost] + a bottom [HABottomBar] (Home, Insights, Records,
 * Profile) and detail destinations (examinations, charts, monitoring).
 * R6: Profile owns sync settings + accessibility (embedded sections) — the
 * standalone Settings route is gone.
 * R7: first-run welcome (3 slides, once) precedes onboarding; a successful
 * fresh connect shows the Health Connect permission step once before the app.
 *
 * Onboarding adds a connection and makes it active; the dashboard's "Switch"
 * picks another saved connection. The active credential drives the [BridgeClient]
 * and the sync worker. All existing state management is preserved — only the
 * navigation shell changed.
 */
@Composable
fun AppRoot(
    prefilled: ConnectionCredential? = null,
    openRoute: String? = null,
) {
    val store: CredentialStore = koinInject()
    val repository: ConnectionRepository = koinInject()
    val monitorRepository: SyncMonitorRepository = koinInject()
    val onboardingPrefs: OnboardingPrefsRepository = koinInject()
    val uiPrefsRepository: UiPreferencesRepository = koinInject()
    val syncSettingsRepository: SyncSettingsRepository = koinInject()
    val manualSource: ManualEntrySource = koinInject()
    val alertEngineHost: AlertEngineHost = koinInject()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var activeCred by remember { mutableStateOf(store.load()) }
    var connections by remember { mutableStateOf(store.loadAll()) }
    var connecting by remember { mutableStateOf(false) }
    var serverError by remember { mutableStateOf<String?>(null) }
    var showSwitcher by remember { mutableStateOf(false) }
    var showPostConnectHc by remember { mutableStateOf(false) }
    val welcomeShown by onboardingPrefs.welcomeShown.collectAsState(initial = false)
    val uiPrefs by uiPrefsRepository.uiPreferences.collectAsState(initial = UiPreferences())
    val simpleMode = uiPrefs.mode == UiMode.SIMPLE
    val connectionFailedMsg = stringResource(R.string.onboarding_connection_failed)

    val client =
        remember(activeCred) {
            activeCred?.let { BridgeClient(it.baseUrl, it.integrationId, it.apiSecret) }
        }
    val connected = activeCred != null

    val online = rememberConnectivity() == Connectivity.Online
    val reduceMotion = LocalReduceMotion.current
    val reachability: ServerReachabilityMonitor = koinInject()
    LaunchedEffect(activeCred) {
        // Point the reachability monitor at the active connection (drives the
        // Home banner's Online / NoInternet / ServerUnreachable states).
        reachability.setTarget(activeCred)
    }
    LaunchedEffect(activeCred?.integrationId) {
        // M5 — the alert engine's cache subscription follows the active
        // connection (connection-scoped observation cache); no connection,
        // no evaluation.
        activeCred?.let { alertEngineHost.start(it.integrationId) } ?: alertEngineHost.stop()
    }
    LaunchedEffect(connected, online) {
        if (connected) {
            monitorRepository.refresh()
            // Phase I — register for native push on the active connection. No-op
            // until the user installs a UnifiedPush distributor; the endpoint
            // arrives async in MobilePushReceiver.onNewEndpoint.
            PushRegistration.register(context)
        }
    }

    LaunchedEffect(Unit) {
        WebAppLauncher.warmUp(context)
    }

    // Connections saved before frontend-origin resolution carry a null — or a
    // backend-baked — frontendBaseUrl (the pre-fix code stored baseUrl on probe
    // failure). Re-resolve once per launch so "Open in browser" targets the PWA
    // origin even for pre-existing connections.
    LaunchedEffect(activeCred?.integrationId, connected) {
        val cred = activeCred ?: return@LaunchedEffect
        if (!cred.frontendBaseUrl.isNullOrBlank() && cred.frontendBaseUrl != cred.baseUrl) return@LaunchedEffect
        runCatching {
            val refreshed = repository.refreshFrontendOrigin(cred)
            if (refreshed.frontendBaseUrl != null && refreshed.frontendBaseUrl != cred.frontendBaseUrl) {
                store.save(refreshed)
                activeCred = refreshed
            }
        }
    }

    if (showSwitcher && connections.isNotEmpty()) {
        ConnectionSwitcherDialog(
            connections = connections,
            activeId = activeCred?.integrationId,
            onPick = {
                store.setActive(it.integrationId)
                activeCred = store.load()
                showSwitcher = false
            },
            onRemove = { id ->
                store.remove(id)
                connections = store.loadAll()
                activeCred = store.load()
                if (activeCred == null) showSwitcher = false
            },
            onDismiss = { showSwitcher = false },
        )
    }

    if (!connected) {
        // Phase D migration — the onboarding form (paste-code + manual fields +
        // validation errors) lives in the VM so it survives process death. The
        // probe + credential-store flow stays here (it mutates AppRoot state),
        // driven through the VM's connect callback. QR scanning needs an
        // Activity, so it is launched here and fed back into the VM.
        val onboardingVm: OnboardingViewModel =
            viewModel(
                factory =
                    OnboardingViewModel.factory(
                        prefilled,
                        connect = { cred ->
                            scope.launch {
                                connecting = true
                                serverError = null
                                val result = repository.probe(cred)
                                if (result.isSuccess) {
                                    val probe = result.getOrThrow()
                                    store.save(probe.credential)
                                    activeCred = probe.credential
                                    connections = store.loadAll()
                                    showPostConnectHc = HealthConnectSource(context).isAvailable()
                                } else {
                                    serverError = result.exceptionOrNull()?.message ?: connectionFailedMsg
                                }
                                connecting = false
                            }
                        },
                        initialMode = uiPrefs.mode,
                        persistMode = { mode ->
                            scope.launch { uiPrefsRepository.setMode(mode) }
                        },
                    ),
            )
        val onboardingState by onboardingVm.state.collectAsStateWithLifecycle()
        if (!welcomeShown && prefilled == null) {
            WelcomeScreen(
                onFinish = { scope.launch { onboardingPrefs.setWelcomeShown() } },
            )
        } else {
            OnboardingScreen(
                state = onboardingState,
                connecting = connecting,
                serverError = serverError,
                onScan = {
                    GmsBarcodeScanning
                        .getClient(context)
                        .startScan()
                        .addOnSuccessListener { barcode ->
                            barcode?.rawValue?.let { onboardingVm.onScanResult(it) }
                        }.addOnFailureListener { e -> onboardingVm.onScanFailed(e.message ?: "") }
                },
                onCodeChange = onboardingVm::onCodeChange,
                onSubmitCode = { onboardingVm.submitCode(onboardingState.code.trim()) },
                onToggleManual = onboardingVm::toggleManual,
                onManualBaseChange = onboardingVm::onManualBaseChange,
                onManualIdChange = onboardingVm::onManualIdChange,
                onManualSecretChange = onboardingVm::onManualSecretChange,
                onSubmitManual = onboardingVm::submitManual,
                onModeChange = onboardingVm::onModeChange,
            )
        }
        return
    }

    if (showPostConnectHc) {
        HealthConnectPermissionRoute(
            onDone = { showPostConnectHc = false },
        )
        return
    }

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = HATab.entries.any { it.matches(currentRoute) }

    // M4 (widgets) — a widget tap's deep link (e.g. the biomarker detail
    // route) navigates once per new value; guarded so an unknown route can
    // never crash the shell. Behind the app-lock gate by construction — the
    // lock screen substitutes for AppRoot until the user unlocks.
    LaunchedEffect(openRoute) {
        openRoute?.let { route ->
            runCatching {
                navController.navigate(route) { launchSingleTop = true }
            }
        }
    }

    if (BuildConfig.DEBUG) {
        LaunchedEffect(Unit) {
            android.util.Log.i("DebugDriver", "collector up")
            DebugDriver.commands.collect { command ->
                android.util.Log.i("DebugDriver", "collect $command")
                runCatching {
                    when (command) {
                        is DebugCommand.Navigate ->
                            navController.navigate(
                                when (command.route) {
                                    "home" -> HATab.Home.route
                                    "records" -> HATab.Records.route
                                    "profile" -> HATab.Profile.route
                                    else -> command.route
                                },
                            ) {
                                if (command.popToStart) popUpTo(navController.graph.findStartDestination().id)
                                launchSingleTop = true
                            }
                        DebugCommand.Back -> navController.popBackStack()
                        is DebugCommand.SetMode ->
                            scope.launch {
                                uiPrefsRepository.setMode(
                                    if (command.simple) UiMode.SIMPLE else UiMode.ADVANCED,
                                )
                            }
                        DebugCommand.SyncNow -> {
                            SyncScheduler.syncNow(context)
                            SyncScheduler.pullNow(context)
                        }
                        is DebugCommand.Reading -> {
                            val type = HcType.entries.firstOrNull { it.code == command.code }
                            if (type != null) {
                                scope.launch {
                                    manualSource.submit(
                                        RawSample(
                                            hcType = type,
                                            value = command.value,
                                            unit = type.defaultUnit,
                                            timestamp =
                                                java.time.Instant
                                                    .now()
                                                    .minus(
                                                        java.time.Duration.ofDays(command.daysAgo),
                                                    ).toString(),
                                        ),
                                    )
                                    SyncScheduler.syncNow(context)
                                }
                            }
                        }
                        DebugCommand.EnableAllHcTypes ->
                            scope.launch {
                                syncSettingsRepository.setEnabledTypes(HcType.entries.toSet())
                            }
                    }
                }
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                HABottomBar(
                    currentRoute = currentRoute,
                    onTabSelected = { tab ->
                        navController.navigate(tab.route) {
                            popUpTo(navController.graph.findStartDestination().id)
                            launchSingleTop = true
                        }
                    },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = HATab.Home.route,
            modifier = Modifier.padding(padding),
            enterTransition = { fadeIn(tween(if (reduceMotion) 0 else 220)) },
            exitTransition = { fadeOut(tween(if (reduceMotion) 0 else 220)) },
            popEnterTransition = { fadeIn(tween(if (reduceMotion) 0 else 220)) },
            popExitTransition = { fadeOut(tween(if (reduceMotion) 0 else 220)) },
        ) {
            composable(HATab.Home.route) {
                HomeRoute(
                    client = client ?: return@composable,
                    repository = monitorRepository,
                    connectionLabel = activeCred?.baseUrl,
                    onSyncNow = {
                        scope.launch {
                            monitorRepository.recordSyncResult(
                                "health_connect",
                                emptyMap(),
                                SyncStatus.Syncing,
                            )
                            SyncScheduler.syncNow(context)
                            // Phase G — "Sync now" also pulls server-side clinical
                            // edits (the user expects a tap to be a full refresh).
                            SyncScheduler.pullNow(context)
                        }
                    },
                    onSwitchConnection = if (connections.size > 1) ({ showSwitcher = true }) else null,
                    onOpenSync = { navController.navigate(HARoutes.SYNC) },
                    onOpenInsights = { code ->
                        navController.navigate(HARoutes.biomarkerDetail(code)) {
                            popUpTo(navController.graph.findStartDestination().id)
                        }
                    },
                    onOpenExam = { examId ->
                        navController.navigate(HARoutes.examDetail(examId))
                    },
                    onOpenInbox = { navController.navigate(HARoutes.INBOX) },
                    onOpenAssistant = {
                        val origin = activeCred?.frontendBaseUrl ?: activeCred?.baseUrl
                        webAppUrl(origin, "ai-assistant")?.let { WebAppLauncher.launch(context, it) }
                    },
                    simpleMode = simpleMode,
                )
            }
            composable(HATab.Records.route) {
                RecordsRoute(
                    onOpenBiomarkers = { navController.navigate(HARoutes.BIOMARKERS) },
                    onOpenExaminations = { navController.navigate(HARoutes.EXAMINATIONS) },
                    onOpenMedications = { navController.navigate(HARoutes.MEDICATIONS) },
                    onOpenAllergies = { navController.navigate(HARoutes.ALLERGIES) },
                    onOpenVaccines = { navController.navigate(HARoutes.VACCINES) },
                    onOpenEvents = { navController.navigate(HARoutes.EVENTS) },
                    onOpenDoctors = { navController.navigate(HARoutes.DOCTORS) },
                    simpleMode = simpleMode,
                )
            }
            composable(HATab.Profile.route) {
                ProfileRoute(
                    credential = activeCred,
                    onSwitchConnection = if (connections.size > 1) ({ showSwitcher = true }) else null,
                    onOpenWebApp =
                        if (!simpleMode) {
                            {
                                val origin = activeCred?.frontendBaseUrl ?: activeCred?.baseUrl
                                webAppUrl(origin)?.let { WebAppLauncher.launch(context, it) }
                            }
                        } else {
                            null
                        },
                    onOpenSync = if (!simpleMode) ({ navController.navigate(HARoutes.SYNC) }) else null,
                    onOpenSyncSettings = if (!simpleMode) ({ navController.navigate(HARoutes.SYNC_SETTINGS) }) else null,
                    onOpenDataStorage = if (!simpleMode) ({ navController.navigate(HARoutes.DATA_STORAGE) }) else null,
                    onOpenAlerts = if (!simpleMode) ({ navController.navigate(HARoutes.alertRules()) }) else null,
                    onOpenServerNotifications =
                        if (!simpleMode) {
                            activeCred?.let {
                                { navController.navigate(HARoutes.NOTIFICATIONS_SETTINGS) }
                            }
                        } else {
                            null
                        },
                    onOpenDeviceNotifications = if (!simpleMode) ({ navController.navigate(HARoutes.DEVICE_NOTIFICATIONS) }) else null,
                    onOpenAccessibility = if (!simpleMode) ({ navController.navigate(HARoutes.ACCESSIBILITY) }) else null,
                    onOpenPrivacy = { navController.navigate(HARoutes.PRIVACY) },
                    onOpenAbout = { navController.navigate(HARoutes.ABOUT) },
                    onDisconnect = {
                        activeCred?.let { store.remove(it.integrationId) }
                        connections = store.loadAll()
                        activeCred = store.load()
                    },
                )
            }
            composable(
                route = HARoutes.EXAM_DETAIL_WITH_ARG,
                arguments =
                    listOf(
                        navArgument(HARoutes.EXAM_DETAIL_ARG) {
                            type = NavType.StringType
                        },
                    ),
            ) { entry ->
                val examId = entry.arguments?.getString(HARoutes.EXAM_DETAIL_ARG)
                if (examId == null) {
                    navController.popBackStack()
                    return@composable
                }
                // Phase A documents fix — native exam detail (metadata +
                // document list + per-row open). The full PWA record stays
                // reachable via the screen's "Open in browser" action.
                ExaminationDetailRoute(
                    client = client ?: return@composable,
                    examId = examId,
                    onBack = { navController.popBackStack() },
                    onOpenInBrowser = {
                        runCatching {
                            val url = webAppUrl(activeCred?.frontendBaseUrl ?: activeCred?.baseUrl, "examinations/$examId")
                            url?.let { WebAppLauncher.launch(context, it) }
                        }
                    },
                    onPreviewImage = { docId, filename ->
                        navController.navigate(HARoutes.docPreview(docId, filename))
                    },
                )
            }
            composable(
                route = HARoutes.DOC_PREVIEW_WITH_ARG,
                arguments =
                    listOf(
                        navArgument(HARoutes.DOC_ID_ARG) { type = NavType.StringType },
                        navArgument(HARoutes.DOC_NAME_ARG) {
                            type = NavType.StringType
                            defaultValue = ""
                            nullable = true
                        },
                    ),
            ) { entry ->
                val docId = entry.arguments?.getString(HARoutes.DOC_ID_ARG)
                if (docId == null) {
                    navController.popBackStack()
                    return@composable
                }
                val filename = entry.arguments?.getString(HARoutes.DOC_NAME_ARG)?.takeIf { it.isNotBlank() }
                DocumentPreviewRoute(
                    client = client ?: return@composable,
                    docId = docId,
                    filename = filename,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(HARoutes.SYNC) {
                SyncRoute(
                    repository = monitorRepository,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(HARoutes.BIOMARKERS) {
                BiomarkersRoute(
                    client = client ?: return@composable,
                    onOpenBiomarker = { code ->
                        navController.navigate(HARoutes.biomarkerDetail(code))
                    },
                    showOverview = !simpleMode,
                    onOpenOverview = { navController.navigate(HARoutes.OVERVIEW) },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(HARoutes.OVERVIEW) {
                OverviewRoute(
                    client = client ?: return@composable,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = HARoutes.BIOMARKER_DETAIL_WITH_ARG,
                arguments =
                    listOf(
                        navArgument(HARoutes.BIOMARKER_ARG) {
                            type = NavType.StringType
                        },
                    ),
            ) { entry ->
                val code = entry.arguments?.getString(HARoutes.BIOMARKER_ARG)
                if (code == null) {
                    navController.popBackStack()
                    return@composable
                }
                BiomarkerDetailRoute(
                    client = client ?: return@composable,
                    biomarkerCode = code,
                    onSetAlert = {
                        navController.navigate(HARoutes.alertRules(code))
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(HARoutes.INBOX) {
                InboxRoute(
                    client = client ?: return@composable,
                    onBack = { navController.popBackStack() },
                    onOpenSettings =
                        if (!simpleMode) {
                            {
                                navController.navigate(HARoutes.NOTIFICATIONS_SETTINGS)
                            }
                        } else {
                            null
                        },
                )
            }
            // Phase H — the native clinical-record list screens, reached from the
            // Records tab hub.
            composable(HARoutes.EXAMINATIONS) {
                ExaminationsRoute(
                    client = client ?: return@composable,
                    onBack = { navController.popBackStack() },
                    onOpenExam = { examId ->
                        navController.navigate(HARoutes.examDetail(examId)) {
                            popUpTo(navController.graph.findStartDestination().id)
                        }
                    },
                )
            }
            composable(HARoutes.MEDICATIONS) {
                MedicationsRoute(
                    client = client ?: return@composable,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(HARoutes.ALLERGIES) {
                AllergiesRoute(
                    client = client ?: return@composable,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(HARoutes.VACCINES) {
                VaccinesRoute(
                    client = client ?: return@composable,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(HARoutes.EVENTS) {
                ClinicalEventsRoute(
                    client = client ?: return@composable,
                    onBack = { navController.popBackStack() },
                    onOpenEvent = { eventId ->
                        navController.navigate(HARoutes.eventDetail(eventId))
                    },
                )
            }
            composable(
                route = HARoutes.EVENT_DETAIL_WITH_ARG,
                arguments =
                    listOf(
                        navArgument(HARoutes.EVENT_DETAIL_ARG) {
                            type = NavType.StringType
                        },
                    ),
            ) { entry ->
                val eventId = entry.arguments?.getString(HARoutes.EVENT_DETAIL_ARG)
                if (eventId == null) {
                    navController.popBackStack()
                    return@composable
                }
                ClinicalEventDetailRoute(
                    client = client ?: return@composable,
                    eventId = eventId,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(HARoutes.DOCTORS) {
                DoctorsRoute(
                    client = client ?: return@composable,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(HARoutes.NOTIFICATIONS_SETTINGS) {
                NotificationsSettingsRoute(
                    client = client ?: return@composable,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = HARoutes.ALERT_RULES_WITH_ARG,
                arguments =
                    listOf(
                        navArgument(HARoutes.ALERT_RULE_ARG) {
                            type = NavType.StringType
                            defaultValue = ""
                            nullable = true
                        },
                    ),
            ) { entry ->
                val prefillCode = entry.arguments?.getString(HARoutes.ALERT_RULE_ARG)?.takeIf { it.isNotBlank() }
                AlertRulesRoute(
                    client = client ?: return@composable,
                    prefillCode = prefillCode,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(HARoutes.SYNC_SETTINGS) {
                SyncSettingsDetailRoute(onBack = { navController.popBackStack() })
            }
            composable(HARoutes.DATA_STORAGE) {
                DataStorageRoute(
                    client = client ?: return@composable,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(HARoutes.DEVICE_NOTIFICATIONS) {
                DeviceNotificationsRoute(onBack = { navController.popBackStack() })
            }
            composable(HARoutes.ACCESSIBILITY) {
                AccessibilityRoute(onBack = { navController.popBackStack() })
            }
            composable(HARoutes.PRIVACY) {
                PrivacyRoute(onBack = { navController.popBackStack() })
            }
            composable(HARoutes.ABOUT) {
                AboutRoute(onBack = { navController.popBackStack() })
            }
        }
    }
}
