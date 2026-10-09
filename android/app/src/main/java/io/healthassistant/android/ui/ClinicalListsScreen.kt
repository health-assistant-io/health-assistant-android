package io.healthassistant.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.healthassistant.android.R
import io.healthassistant.android.data.PullSyncRepository
import io.healthassistant.android.data.cache.ObservationDatabase
import io.healthassistant.android.data.cache.RoomCaches
import io.healthassistant.android.data.repository.BridgeClinicalRecordGateway
import io.healthassistant.android.ui.components.DetailRow
import io.healthassistant.android.ui.components.RecordDetailSheet
import io.healthassistant.android.ui.components.StaleChipSlot
import io.healthassistant.bridge.BridgeClient
import io.healthassistant.bridge.Vaccine
import io.healthassistant.shared.data.cache.CacheDomain
import io.healthassistant.shared.data.displayName
import io.healthassistant.shared.data.repository.ClinicalRecordRepository
import io.healthassistant.shared.data.repository.ConnectivityProvider
import org.koin.compose.koinInject

/**
 * Phase H — the Vaccines screen (list-only for v2). Renders each vaccination's
 * name + administered date. Pure state + lambdas.
 */
@Composable
fun VaccinesRoute(
    client: BridgeClient,
    onBack: () -> Unit,
) {
    val pullSync: PullSyncRepository = koinInject()
    val db: ObservationDatabase = koinInject()
    val connectivity: ConnectivityProvider = koinInject()
    val caches = remember(client) { RoomCaches(db, client.integrationId) }
    val repo =
        remember(client) {
            ClinicalRecordRepository(caches.records, BridgeClinicalRecordGateway(client), connectivity, caches.meta)
        }
    val vm: VaccinesViewModel = viewModel(factory = VaccinesViewModel.factory(repo, pullSync))
    val state by vm.state.collectAsStateWithLifecycle()
    val staleMeta by caches.meta.observe(CacheDomain.VACCINES).collectAsStateWithLifecycle(initialValue = null)
    val online = rememberConnectivity() == Connectivity.Online
    VaccinesScreen(state = state, staleMeta = staleMeta, online = online, onRetry = { vm.reload() }, onBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaccinesScreen(
    state: VaccinesUiState,
    staleMeta: io.healthassistant.shared.data.cache.CacheMetaState? = null,
    online: Boolean = true,
    onRetry: () -> Unit = {},
    onBack: () -> Unit,
) {
    var selected by remember { mutableStateOf<Vaccine?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.vaccines_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null && state.vaccines.isEmpty() ->
                    Text(
                        state.error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                state.vaccines.isEmpty() ->
                    Text(
                        stringResource(R.string.vaccines_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                else ->
                    LazyColumn(Modifier.fillMaxSize()) {
                        item { StaleChipSlot(meta = staleMeta, online = online, onRetry = onRetry) }
                        items(state.vaccines, key = { it.id }) { vaccine ->
                            ListItem(
                                leadingContent = {
                                    Icon(
                                        Icons.Outlined.Shield,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                headlineContent = {
                                    Text(
                                        vaccine.displayName ?: stringResource(R.string.vaccines_unknown),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                supportingContent = {
                                    vaccine.administeredAt?.takeIf { it.isNotBlank() }?.let {
                                        Text(shortDate(it), maxLines = 1)
                                    }
                                },
                                modifier = Modifier.clickable { selected = vaccine },
                            )
                        }
                    }
            }
        }
    }

    selected?.let { vaccine ->
        RecordDetailSheet(
            title = vaccine.displayName ?: stringResource(R.string.vaccines_unknown),
            rows =
                listOf(
                    DetailRow(stringResource(R.string.detail_administered), vaccine.administeredAt?.take(10)),
                    DetailRow(stringResource(R.string.detail_dose), vaccine.doseNumber),
                    DetailRow(stringResource(R.string.detail_lot), vaccine.lotNumber),
                    DetailRow(stringResource(R.string.detail_manufacturer), vaccine.manufacturer),
                    DetailRow(stringResource(R.string.detail_location), vaccine.location),
                    DetailRow(stringResource(R.string.detail_note), vaccine.note),
                ),
            onDismiss = { selected = null },
        )
    }
}

/**
 * Phase H — the Clinical Events screen (list-only for v2; the rich detail +
 * recurrence logging stay in the PWA). Renders each event's type name + status.
 */
@Composable
fun ClinicalEventsRoute(
    client: BridgeClient,
    onBack: () -> Unit,
    onOpenEvent: (String) -> Unit = {},
) {
    val pullSync: PullSyncRepository = koinInject()
    val db: ObservationDatabase = koinInject()
    val connectivity: ConnectivityProvider = koinInject()
    val caches = remember(client) { RoomCaches(db, client.integrationId) }
    val repo =
        remember(client) {
            ClinicalRecordRepository(caches.records, BridgeClinicalRecordGateway(client), connectivity, caches.meta)
        }
    val vm: ClinicalEventsViewModel = viewModel(factory = ClinicalEventsViewModel.factory(repo, pullSync))
    val state by vm.state.collectAsStateWithLifecycle()
    val staleMeta by caches.meta.observe(CacheDomain.CLINICAL_EVENTS).collectAsStateWithLifecycle(initialValue = null)
    val online = rememberConnectivity() == Connectivity.Online
    ClinicalEventsScreen(state = state, staleMeta = staleMeta, online = online, onRetry = {
        vm.reload()
    }, onBack = onBack, onOpenEvent = onOpenEvent)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClinicalEventsScreen(
    state: ClinicalEventsUiState,
    staleMeta: io.healthassistant.shared.data.cache.CacheMetaState? = null,
    online: Boolean = true,
    onRetry: () -> Unit = {},
    onBack: () -> Unit,
    onOpenEvent: (String) -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.events_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null && state.events.isEmpty() ->
                    Text(
                        state.error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                state.events.isEmpty() ->
                    Text(
                        stringResource(R.string.events_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                else ->
                    LazyColumn(Modifier.fillMaxSize()) {
                        item { StaleChipSlot(meta = staleMeta, online = online, onRetry = onRetry) }
                        items(state.events, key = { it.id }) { event ->
                            ListItem(
                                leadingContent = {
                                    Icon(
                                        Icons.Outlined.LocalHospital,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                headlineContent = {
                                    Text(
                                        event.typeName ?: event.title ?: event.typeSlug ?: event.id,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                supportingContent = {
                                    val meta =
                                        buildList {
                                            event.status?.takeIf { it.isNotBlank() }?.let {
                                                add(it.replaceFirstChar(Char::uppercase))
                                            }
                                            event.onsetDate?.takeIf { it.isNotBlank() }?.let { add(shortDate(it)) }
                                        }
                                    if (meta.isNotEmpty()) Text(meta.joinToString(" · "), maxLines = 1)
                                },
                                modifier = Modifier.clickable { onOpenEvent(event.id) },
                            )
                        }
                    }
            }
        }
    }
}

/** Best-effort `YYYY-MM-DD` (or `YYYY-MM-DDTHH:MM…`) → a short date label. */
internal fun shortDate(iso: String): String {
    val date = iso.takeIf { it.length >= 10 }?.take(10) ?: return iso
    return date
}
