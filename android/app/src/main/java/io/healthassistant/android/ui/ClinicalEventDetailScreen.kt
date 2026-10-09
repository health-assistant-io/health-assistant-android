package io.healthassistant.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.healthassistant.android.R
import io.healthassistant.android.ui.components.RichText
import io.healthassistant.bridge.BridgeClient
import io.healthassistant.shared.data.ClinicalEventDetail
import io.healthassistant.shared.data.parseClinicalEventDetail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** UI state for the clinical-event detail screen. */
data class ClinicalEventDetailUiState(
    val loading: Boolean = true,
    val event: ClinicalEventDetail? = null,
    val error: Boolean = false,
    val loggingOccurrence: Boolean = false,
)

/** R5 — owns the clinical-event detail flow: raw bridge detail → projection,
 *  occurrence logging with optimistic refetch. */
class ClinicalEventDetailViewModel(
    private val client: BridgeClient,
    private val eventId: String,
) : ViewModel() {
    private val _state = MutableStateFlow(ClinicalEventDetailUiState())
    val state: StateFlow<ClinicalEventDetailUiState> = _state

    init {
        reload()
    }

    fun reload() {
        _state.value = _state.value.copy(loading = _state.value.event == null, error = false)
        viewModelScope.launch {
            val next =
                runCatching { client.getClinicalEventRaw(eventId) }
                    .mapCatching { parseClinicalEventDetail(it) }
                    .fold(
                        onSuccess = { ClinicalEventDetailUiState(loading = false, event = it) },
                        onFailure = { ClinicalEventDetailUiState(loading = false, error = true) },
                    )
            _state.value = next
        }
    }

    /** `POST /clinical-events/{id}/occurrences` then refetch. */
    fun logOccurrence(
        occurredAt: String,
        severity: String?,
        intensity: Int?,
        notes: String?,
    ) {
        _state.value = _state.value.copy(loggingOccurrence = true)
        viewModelScope.launch {
            val payload =
                buildJsonObject {
                    put("occurred_at", occurredAt)
                    severity?.takeIf { it.isNotBlank() }?.let { put("severity", it) }
                    intensity?.let { put("intensity", it) }
                    notes?.takeIf { it.isNotBlank() }?.let { put("notes", it) }
                }
            runCatching { client.addClinicalEventOccurrenceRaw(eventId, payload) }
            _state.value = _state.value.copy(loggingOccurrence = false)
            reload()
        }
    }

    companion object {
        fun factory(
            client: BridgeClient,
            eventId: String,
        ) = viewModelFactory {
            initializer { ClinicalEventDetailViewModel(client, eventId) }
        }
    }
}

/** R5 — the clinical-event detail: rich description, status/dates, the
 *  occurrence timeline, and logging a new occurrence. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClinicalEventDetailRoute(
    client: BridgeClient,
    eventId: String,
    onBack: () -> Unit,
) {
    val vm: ClinicalEventDetailViewModel =
        viewModel(
            key = "event_detail_$eventId",
            factory = ClinicalEventDetailViewModel.factory(client, eventId),
        )
    val state by vm.state.collectAsStateWithLifecycle()

    ClinicalEventDetailScreen(
        state = state,
        onBack = onBack,
        onRetry = vm::reload,
        onLogOccurrence = vm::logOccurrence,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClinicalEventDetailScreen(
    state: ClinicalEventDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onLogOccurrence: (String, String?, Int?, String?) -> Unit,
) {
    var showLogDialog by remember { mutableStateOf(false) }
    val event = state.event

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        event?.typeDetails?.name ?: event?.title
                            ?: stringResource(R.string.events_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        floatingActionButton = {
            if (event != null) {
                FloatingActionButton(onClick = { showLogDialog = true }) {
                    Text("+", style = MaterialTheme.typography.headlineSmall)
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error || event == null ->
                    Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.events_detail_failed),
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                    }
                else ->
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    ) {
                        event.title?.let {
                            Text(it, style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.height(4.dp))
                        }
                        val meta =
                            buildList {
                                event.status?.let { add(it.replaceFirstChar(Char::uppercase)) }
                                event.onsetDate?.let { add(stringResource(R.string.events_since, it.take(10))) }
                                event.resolvedDate?.let { add(stringResource(R.string.events_resolved, it.take(10))) }
                            }
                        if (meta.isNotEmpty()) {
                            Text(
                                meta.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        event.description?.takeIf { it.isNotBlank() }?.let { desc ->
                            Spacer(Modifier.height(16.dp))
                            RichText(
                                desc,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        Spacer(Modifier.height(24.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.events_occurrences, event.occurrences.size),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        if (event.occurrences.isEmpty()) {
                            Text(
                                stringResource(R.string.events_no_occurrences),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        } else {
                            event.occurrences
                                .sortedByDescending { it.occurredAt ?: it.date ?: "" }
                                .forEach { occ ->
                                    OccurrenceRow(occ)
                                    HorizontalDivider()
                                }
                        }
                        Spacer(Modifier.height(96.dp))
                    }
            }
        }
    }

    if (showLogDialog) {
        LogOccurrenceDialog(
            busy = state.loggingOccurrence,
            onConfirm = { iso, severity, intensity, notes ->
                onLogOccurrence(iso, severity, intensity, notes)
                showLogDialog = false
            },
            onDismiss = { showLogDialog = false },
        )
    }
}

@Composable
private fun OccurrenceRow(occ: io.healthassistant.shared.data.EventOccurrence) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            (occ.occurredAt ?: occ.date ?: "—").take(16).replace('T', ' '),
            style = MaterialTheme.typography.bodyLarge,
        )
        val meta =
            buildList {
                occ.severity?.let { add(it.replaceFirstChar(Char::uppercase)) }
                occ.intensity?.let { add("$it/10") }
            }
        if (meta.isNotEmpty()) {
            Text(
                meta.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        occ.notes?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LogOccurrenceDialog(
    busy: Boolean,
    onConfirm: (iso: String, severity: String?, intensity: Int?, notes: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var notes by remember { mutableStateOf("") }
    var severity by remember { mutableStateOf("") }
    var intensityText by remember { mutableStateOf("") }
    val now =
        remember {
            java.time.OffsetDateTime
                .now()
                .withNano(0)
                .toString()
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.events_log_occurrence)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = severity,
                    onValueChange = { severity = it },
                    label = { Text(stringResource(R.string.events_severity)) },
                    placeholder = { Text("mild / moderate / severe") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = intensityText,
                    onValueChange = { intensityText = it.filter(Char::isDigit).take(2) },
                    label = { Text(stringResource(R.string.events_intensity)) },
                    placeholder = { Text("1–10") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.events_notes)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(now, severity, intensityText.toIntOrNull(), notes)
                },
                enabled = !busy,
            ) { Text(stringResource(R.string.events_log)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
