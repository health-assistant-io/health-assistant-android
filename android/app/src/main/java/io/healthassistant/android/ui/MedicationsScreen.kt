package io.healthassistant.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import io.healthassistant.bridge.Medication
import io.healthassistant.shared.data.cache.CacheDomain
import io.healthassistant.shared.data.displayName
import io.healthassistant.shared.data.repository.ClinicalRecordRepository
import io.healthassistant.shared.data.repository.ConnectivityProvider
import org.koin.compose.koinInject

/**
 * Phase H — the Medications screen: a list of the patient's medications with a
 * FAB to add and a long-press / delete affordance. Pure state + lambdas; the
 * owning route ([MedicationsRoute]) builds the VM.
 */
@Composable
fun MedicationsRoute(
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
    val vm: MedicationsViewModel =
        viewModel(factory = MedicationsViewModel.factory(client, repo, pullSync))
    val state by vm.state.collectAsStateWithLifecycle()
    val staleMeta by caches.meta.observe(CacheDomain.MEDICATIONS).collectAsStateWithLifecycle(initialValue = null)
    val online = rememberConnectivity() == Connectivity.Online
    MedicationsScreen(
        state = state,
        staleMeta = staleMeta,
        online = online,
        onRetry = { vm.reload() },
        onBack = onBack,
        onAdd = { name, dosage -> vm.add(name, dosage) },
        onDelete = { vm.delete(it) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationsScreen(
    state: MedicationsUiState,
    staleMeta: io.healthassistant.shared.data.cache.CacheMetaState? = null,
    online: Boolean = true,
    onRetry: () -> Unit = {},
    onBack: () -> Unit,
    onAdd: (name: String, dosage: String?) -> Unit,
    onDelete: (id: String) -> Unit,
) {
    var showAdd by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Medication?>(null) }
    var selected by remember { mutableStateOf<Medication?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.medications_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Outlined.Medication, contentDescription = stringResource(R.string.medications_add_title))
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null && state.medications.isEmpty() ->
                    Text(
                        state.error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                state.medications.isEmpty() ->
                    Text(
                        stringResource(R.string.medications_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                else ->
                    LazyColumn(Modifier.fillMaxSize()) {
                        item { StaleChipSlot(meta = staleMeta, online = online, onRetry = onRetry) }
                        items(state.medications, key = { it.id }) { med ->
                            val name = med.displayName ?: med.id
                            ListItem(
                                headlineContent = {
                                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = {
                                    med.dosage?.takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 2) }
                                },
                                trailingContent = {
                                    IconButton(
                                        onClick = { pendingDelete = med },
                                        modifier = Modifier.size(40.dp),
                                    ) {
                                        Icon(
                                            Icons.Outlined.DeleteOutline,
                                            contentDescription = stringResource(R.string.action_remove),
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                },
                                modifier = Modifier.clickable { selected = med },
                            )
                        }
                    }
            }
            state.error?.let { err ->
                if (!state.loading && state.medications.isNotEmpty()) {
                    Text(
                        err,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
                    )
                }
            }
        }
    }

    if (showAdd) {
        MedicationAddDialog(
            title = stringResource(R.string.medications_add_title),
            nameLabel = stringResource(R.string.medications_name_label),
            nameHint = stringResource(R.string.medications_name_hint),
            dosageLabel = stringResource(R.string.medications_dosage_label),
            dosageHint = stringResource(R.string.medications_dosage_hint),
            cancelLabel = stringResource(R.string.action_cancel),
            addLabel = stringResource(R.string.action_add),
            busy = state.submitting,
            onDismiss = { showAdd = false },
            onSubmit = { name, dosage ->
                showAdd = false
                onAdd(name, dosage)
            },
        )
    }

    pendingDelete?.let { med ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.medications_delete_confirm, med.displayName ?: med.id)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDelete(med.id)
                    },
                ) {
                    Text(stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    selected?.let { med ->
        RecordDetailSheet(
            title = med.displayName ?: med.id,
            rows =
                listOf(
                    DetailRow(stringResource(R.string.detail_dosage), med.dosage),
                    DetailRow(stringResource(R.string.detail_reason), med.reason),
                    DetailRow(stringResource(R.string.detail_note), med.note),
                    DetailRow(stringResource(R.string.detail_status), med.status?.replaceFirstChar(Char::uppercase)),
                    DetailRow(stringResource(R.string.detail_start), med.startDate?.take(10)),
                    DetailRow(stringResource(R.string.detail_end), med.endDate?.take(10)),
                ),
            onDismiss = { selected = null },
        )
    }
}

/** Phase H — a simple two-field add dialog (name required, dosage optional). */
@Composable
fun MedicationAddDialog(
    title: String,
    nameLabel: String,
    nameHint: String,
    dosageLabel: String,
    dosageHint: String,
    cancelLabel: String,
    addLabel: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (name: String, dosage: String?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var dosage by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(nameLabel) },
                    placeholder = { Text(nameHint) },
                    singleLine = true,
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = dosage,
                    onValueChange = { dosage = it },
                    label = { Text(dosageLabel) },
                    placeholder = { Text(dosageHint) },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && !busy,
                onClick = { onSubmit(name.trim(), dosage.trim().takeIf { it.isNotEmpty() }) },
            ) {
                Text(addLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(cancelLabel) }
        },
    )
}
