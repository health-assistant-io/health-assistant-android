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
import androidx.compose.material.icons.outlined.Warning
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
import io.healthassistant.bridge.Allergy
import io.healthassistant.bridge.BridgeClient
import io.healthassistant.shared.data.cache.CacheDomain
import io.healthassistant.shared.data.displayName
import io.healthassistant.shared.data.repository.ClinicalRecordRepository
import io.healthassistant.shared.data.repository.ConnectivityProvider
import org.koin.compose.koinInject

/**
 * Phase H — the Allergies screen: a list of the patient's allergies with a FAB
 * to add and a delete affordance. Safety-critical: the same data feeds the Home
 * allergies card. Pure state + lambdas; the owning route ([AllergiesRoute])
 * builds the VM.
 */
@Composable
fun AllergiesRoute(
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
    val vm: AllergiesViewModel =
        viewModel(factory = AllergiesViewModel.factory(client, repo, pullSync))
    val state by vm.state.collectAsStateWithLifecycle()
    val staleMeta by caches.meta.observe(CacheDomain.ALLERGIES).collectAsStateWithLifecycle(initialValue = null)
    val online = rememberConnectivity() == Connectivity.Online
    AllergiesScreen(
        state = state,
        staleMeta = staleMeta,
        online = online,
        onRetry = { vm.reload() },
        onBack = onBack,
        onAdd = { name, criticality -> vm.add(name, criticality) },
        onDelete = { vm.delete(it) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllergiesScreen(
    state: AllergiesUiState,
    staleMeta: io.healthassistant.shared.data.cache.CacheMetaState? = null,
    online: Boolean = true,
    onRetry: () -> Unit = {},
    onBack: () -> Unit,
    onAdd: (name: String, criticality: String?) -> Unit,
    onDelete: (id: String) -> Unit,
) {
    var showAdd by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Allergy?>(null) }
    var selected by remember { mutableStateOf<Allergy?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.allergies_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Outlined.Warning, contentDescription = stringResource(R.string.allergies_add_title))
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null && state.allergies.isEmpty() ->
                    Text(
                        state.error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                state.allergies.isEmpty() ->
                    Text(
                        stringResource(R.string.allergies_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                else ->
                    LazyColumn(Modifier.fillMaxSize()) {
                        item { StaleChipSlot(meta = staleMeta, online = online, onRetry = onRetry) }
                        items(state.allergies, key = { it.id }) { allergy ->
                            val name = allergy.displayName ?: allergy.id
                            ListItem(
                                headlineContent = {
                                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = {
                                    val meta =
                                        buildList {
                                            allergy.criticality?.takeIf { it.isNotBlank() }?.let {
                                                add(it.replaceFirstChar(Char::uppercase))
                                            }
                                            allergy.clinicalStatus?.takeIf { it.isNotBlank() && it != "active" }?.let {
                                                add(it.replaceFirstChar(Char::uppercase))
                                            }
                                        }
                                    if (meta.isNotEmpty()) Text(meta.joinToString(" · "), maxLines = 1)
                                },
                                modifier = Modifier.clickable { selected = allergy },
                                trailingContent = {
                                    IconButton(
                                        onClick = { pendingDelete = allergy },
                                        modifier = Modifier.size(40.dp),
                                    ) {
                                        Icon(
                                            Icons.Outlined.DeleteOutline,
                                            contentDescription = stringResource(R.string.action_remove),
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                },
                            )
                        }
                    }
            }
            state.error?.let { err ->
                if (!state.loading && state.allergies.isNotEmpty()) {
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
        AllergyAddDialog(
            title = stringResource(R.string.allergies_add_title),
            nameLabel = stringResource(R.string.allergies_name_label),
            nameHint = stringResource(R.string.allergies_name_hint),
            criticalityLabel = stringResource(R.string.allergies_criticality_label),
            cancelLabel = stringResource(R.string.action_cancel),
            addLabel = stringResource(R.string.action_add),
            busy = state.submitting,
            onDismiss = { showAdd = false },
            onSubmit = { name, criticality ->
                showAdd = false
                onAdd(name, criticality)
            },
        )
    }

    pendingDelete?.let { allergy ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.allergies_delete_confirm, allergy.displayName ?: allergy.id)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDelete(allergy.id)
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

    selected?.let { allergy ->
        RecordDetailSheet(
            title = allergy.displayName ?: allergy.id,
            rows =
                listOf(
                    DetailRow(stringResource(R.string.detail_criticality), allergy.criticality?.replaceFirstChar(Char::uppercase)),
                    DetailRow(stringResource(R.string.detail_status), allergy.clinicalStatus?.replaceFirstChar(Char::uppercase)),
                    DetailRow(stringResource(R.string.detail_category), allergy.category?.replaceFirstChar(Char::uppercase)),
                    DetailRow(stringResource(R.string.detail_onset), allergy.onsetDate?.take(10)),
                    DetailRow(stringResource(R.string.detail_resolved), allergy.resolvedDate?.take(10)),
                    DetailRow(stringResource(R.string.detail_note), allergy.note),
                ),
            onDismiss = { selected = null },
        )
    }
}

/** Phase H — a simple two-field add dialog (allergen required, severity optional). */
@Composable
fun AllergyAddDialog(
    title: String,
    nameLabel: String,
    nameHint: String,
    criticalityLabel: String,
    cancelLabel: String,
    addLabel: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (name: String, criticality: String?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var criticality by remember { mutableStateOf("") }
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
                    value = criticality,
                    onValueChange = { criticality = it },
                    label = { Text(criticalityLabel) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && !busy,
                onClick = { onSubmit(name.trim(), criticality.trim().takeIf { it.isNotEmpty() }) },
            ) {
                Text(addLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(cancelLabel) }
        },
    )
}
