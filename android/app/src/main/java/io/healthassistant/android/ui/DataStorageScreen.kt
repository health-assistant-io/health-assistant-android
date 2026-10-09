package io.healthassistant.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.healthassistant.android.R
import io.healthassistant.android.data.cache.ObservationDatabase
import io.healthassistant.android.data.cache.RoomCaches
import io.healthassistant.android.data.cache.RoomDocumentManifestCache
import io.healthassistant.android.data.repository.BridgeBiomarkerGateway
import io.healthassistant.android.data.repository.BridgeClinicalRecordGateway
import io.healthassistant.android.data.repository.BridgeExaminationGateway
import io.healthassistant.android.data.repository.BridgeNotificationGateway
import io.healthassistant.android.data.repository.BridgeObservationGateway
import io.healthassistant.bridge.BridgeClient
import io.healthassistant.shared.data.cache.CacheDomain
import io.healthassistant.shared.data.cache.DocumentByteStore
import io.healthassistant.shared.data.repository.BiomarkerCatalogRepository
import io.healthassistant.shared.data.repository.ClinicalRecordRepository
import io.healthassistant.shared.data.repository.ConnectivityProvider
import io.healthassistant.shared.data.repository.DocumentByteGateway
import io.healthassistant.shared.data.repository.DocumentByteRepository
import io.healthassistant.shared.data.repository.DocumentRepository
import io.healthassistant.shared.data.repository.ExaminationRepository
import io.healthassistant.shared.data.repository.NotificationRepository
import io.healthassistant.shared.data.repository.ObservationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One domain's row in the "Cached data" section: live row count + the
 *  cache_meta staleness snapshot. */
data class DomainUsageRow(
    val domain: CacheDomain,
    val rowCount: Int,
    val lastSuccessAtEpochMs: Long,
    val lastError: String?,
)

data class DataStorageUiState(
    val loading: Boolean = true,
    val rows: List<DomainUsageRow> = emptyList(),
    val documentBytes: Long = 0,
    val downloadedDocuments: Int = 0,
    val busy: Boolean = false,
)

/**
 * Offline-first M8 — the Settings "Data & storage" view-model: per-domain row
 * counts + last-refresh times of the ACTIVE connection's caches, the document
 * byte-cache size, and the two clear actions. Reads counts live from the DAOs
 * (not from cache_meta.row_count, so a clear is reflected immediately);
 * "Clear cached data" calls every repository's [io.healthassistant.shared.data.repository]
 * clear() (which also drops the domain's staleness row), "Clear downloaded
 * documents" empties the shared byte store + every connection's manifest
 * pointer.
 */
class DataStorageViewModel(
    client: BridgeClient,
    private val caches: RoomCaches,
    private val db: ObservationDatabase,
    connectivity: ConnectivityProvider,
    byteStore: DocumentByteStore,
) : ViewModel() {
    private val observationRepo = ObservationRepository(caches.observations, BridgeObservationGateway(client), connectivity, caches.meta)
    private val biomarkerRepo = BiomarkerCatalogRepository(caches.biomarkers, BridgeBiomarkerGateway(client), connectivity, caches.meta)
    private val examRepo = ExaminationRepository(caches.examinations, BridgeExaminationGateway(client), connectivity, caches.meta)
    private val docRepo =
        DocumentRepository(
            caches.documents,
            io.healthassistant.android.data.repository
                .BridgeDocumentGateway(client),
            connectivity,
            caches.meta,
        )
    private val recordRepo = ClinicalRecordRepository(caches.records, BridgeClinicalRecordGateway(client), connectivity, caches.meta)
    private val notificationRepo =
        NotificationRepository(caches.notifications, BridgeNotificationGateway(client), connectivity, caches.meta)
    private val byteRepo =
        DocumentByteRepository(
            store = byteStore,
            metaCache = RoomDocumentManifestCache(db.documentDao()),
            gateway = OfflineByteGateway,
            connectivity = { false },
        )

    private val _state = MutableStateFlow(DataStorageUiState())
    val state = _state.asStateFlow()

    init {
        refresh()
    }

    /** Reload counts + staleness + byte-store size. */
    fun refresh() {
        viewModelScope.launch {
            val cid = caches.connectionId
            val meta =
                caches.meta
                    .observeAll()
                    .first()
                    .associateBy { it.domain }
            val rows =
                DOMAIN_ORDER.map { domain ->
                    DomainUsageRow(
                        domain = domain,
                        rowCount = countOf(domain, cid),
                        lastSuccessAtEpochMs = meta[domain]?.lastSuccessAtEpochMs ?: 0L,
                        lastError = meta[domain]?.lastError,
                    )
                }
            _state.value =
                DataStorageUiState(
                    loading = false,
                    rows = rows,
                    documentBytes = byteRepo.totalBytes(),
                    downloadedDocuments = db.documentDao().downloadedCount(cid),
                )
        }
    }

    /** The Settings "Clear cached data" action — every repository drops its
     *  domain's rows + staleness meta for the active connection. */
    fun clearCachedData() {
        runBusy {
            observationRepo.clear()
            biomarkerRepo.clear()
            examRepo.clear()
            docRepo.clear()
            recordRepo.clear()
            notificationRepo.clear()
        }
    }

    /** The Settings "Clear downloaded documents" action — the shared byte
     *  store + every connection's manifest pointer. */
    fun clearDocuments() {
        runBusy { byteRepo.clearAll() }
    }

    private fun runBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            runCatching { block() }
            _state.value = _state.value.copy(busy = false)
            refresh()
        }
    }

    private suspend fun countOf(
        domain: CacheDomain,
        cid: String,
    ): Int =
        when (domain) {
            CacheDomain.OBSERVATIONS -> db.cacheDao().rowCount(cid)
            CacheDomain.BIOMARKERS -> db.biomarkerDao().rowCount(cid)
            CacheDomain.EXAMINATIONS -> db.examinationDao().rowCount(cid)
            CacheDomain.DOCUMENTS -> db.documentDao().rowCount(cid)
            CacheDomain.MEDICATIONS -> db.clinicalRecordDao().medicationCount(cid)
            CacheDomain.ALLERGIES -> db.clinicalRecordDao().allergyCount(cid)
            CacheDomain.VACCINES -> db.clinicalRecordDao().vaccineCount(cid)
            CacheDomain.CLINICAL_EVENTS -> db.clinicalRecordDao().clinicalEventCount(cid)
            CacheDomain.NOTIFICATIONS -> db.notificationDao().rowCount(cid)
        }

    companion object {
        /** Display order mirrors the app's information architecture. */
        private val DOMAIN_ORDER =
            listOf(
                CacheDomain.OBSERVATIONS,
                CacheDomain.BIOMARKERS,
                CacheDomain.EXAMINATIONS,
                CacheDomain.DOCUMENTS,
                CacheDomain.MEDICATIONS,
                CacheDomain.ALLERGIES,
                CacheDomain.VACCINES,
                CacheDomain.CLINICAL_EVENTS,
                CacheDomain.NOTIFICATIONS,
            )

        fun factory(
            client: BridgeClient,
            caches: RoomCaches,
            db: ObservationDatabase,
            connectivity: ConnectivityProvider,
            byteStore: DocumentByteStore,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { DataStorageViewModel(client, caches, db, connectivity, byteStore) }
            }
    }

    private object OfflineByteGateway : DocumentByteGateway {
        override suspend fun content(docId: String): ByteArray = error("settings never fetches")

        override suspend fun preview(
            docId: String,
            page: Int?,
        ): ByteArray = error("settings never fetches")
    }
}

/**
 * Profile › Data & storage (offline-first M8). Pure state + lambdas: the
 * per-domain cached-row counts + last refresh, the document byte-cache size,
 * and the two destructive clear actions behind confirmation dialogs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataStorageScreen(
    state: DataStorageUiState,
    onClearCachedData: () -> Unit,
    onClearDocuments: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmClearCache by remember { mutableStateOf(false) }
    var confirmClearDocs by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.data_storage_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                stringResource(R.string.data_storage_cached_section),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            if (state.loading) {
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator(Modifier.size(24.dp))
            } else {
                state.rows.forEachIndexed { index, row ->
                    DomainRow(row)
                    if (index != state.rows.lastIndex) HorizontalDivider()
                }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { confirmClearCache = true },
                enabled = !state.busy && !state.loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.FolderOff, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.data_storage_clear))
            }

            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.data_storage_documents_section),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Description,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.data_storage_documents_size, formatBytes(state.documentBytes)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { confirmClearDocs = true },
                enabled = !state.busy && state.documentBytes > 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.FolderOff, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.data_storage_clear_documents))
            }
        }
    }

    if (confirmClearCache) {
        AlertDialog(
            onDismissRequest = { confirmClearCache = false },
            title = { Text(stringResource(R.string.data_storage_clear_confirm_title)) },
            text = { Text(stringResource(R.string.data_storage_clear_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearCache = false
                        onClearCachedData()
                    },
                ) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearCache = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    if (confirmClearDocs) {
        AlertDialog(
            onDismissRequest = { confirmClearDocs = false },
            title = { Text(stringResource(R.string.data_storage_clear_documents)) },
            text = { Text(stringResource(R.string.data_storage_clear_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearDocs = false
                        onClearDocuments()
                    },
                ) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearDocs = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun DomainRow(row: DomainUsageRow) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(domainLabel(row.domain), style = MaterialTheme.typography.bodyLarge)
            Text(
                text =
                    if (row.lastSuccessAtEpochMs == 0L) {
                        stringResource(R.string.data_storage_never)
                    } else {
                        stringResource(
                            R.string.data_storage_last_refresh,
                            io.healthassistant.android.ui.components
                                .relativeTimeMillis(row.lastSuccessAtEpochMs),
                        )
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Text(
            text = stringResource(R.string.data_storage_rows, row.rowCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun domainLabel(domain: CacheDomain): String =
    when (domain) {
        CacheDomain.OBSERVATIONS -> stringResource(R.string.data_domain_observations)
        CacheDomain.BIOMARKERS -> stringResource(R.string.data_domain_biomarkers)
        CacheDomain.EXAMINATIONS -> stringResource(R.string.data_domain_examinations)
        CacheDomain.DOCUMENTS -> stringResource(R.string.data_domain_documents)
        CacheDomain.MEDICATIONS -> stringResource(R.string.data_domain_medications)
        CacheDomain.ALLERGIES -> stringResource(R.string.data_domain_allergies)
        CacheDomain.VACCINES -> stringResource(R.string.data_domain_vaccines)
        CacheDomain.CLINICAL_EVENTS -> stringResource(R.string.data_domain_clinical_events)
        CacheDomain.NOTIFICATIONS -> stringResource(R.string.data_domain_notifications)
    }

/** 1.2 KB / 3.4 MB style byte sizing (KiB/MiB units, one decimal trimmed). */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return trim(kb) + " KB"
    val mb = kb / 1024.0
    if (mb < 1024.0) return trim(mb) + " MB"
    return trim(mb / 1024.0) + " GB"
}

private fun trim(v: Double): String =
    if (v >= 100.0) {
        v.toInt().toString()
    } else {
        io.healthassistant.android.ui.components
            .formatValue(v)
    }

/** Thin shim (offline-first M8): builds the per-connection caches + the
 *  [DataStorageViewModel] and collects its state. */
@Composable
fun DataStorageRoute(
    client: BridgeClient,
    onBack: () -> Unit,
) {
    val db: ObservationDatabase = org.koin.compose.koinInject()
    val connectivity: ConnectivityProvider = org.koin.compose.koinInject()
    val byteStore: DocumentByteStore = org.koin.compose.koinInject()
    val caches = remember(client) { RoomCaches(db, client.integrationId) }
    val vm: DataStorageViewModel =
        viewModel(factory = DataStorageViewModel.factory(client, caches, db, connectivity, byteStore))
    val state by vm.state.collectAsStateWithLifecycle()

    DataStorageScreen(
        state = state,
        onClearCachedData = vm::clearCachedData,
        onClearDocuments = vm::clearDocuments,
        onBack = onBack,
    )
}
