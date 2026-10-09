package io.healthassistant.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import io.healthassistant.bridge.BridgeClient
import io.healthassistant.bridge.NotificationKind
import io.healthassistant.bridge.NotificationTrigger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** UI state for the server-side notification preferences + triggers. */
data class NotificationsSettingsUiState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val preferences: List<NotificationKind> = emptyList(),
    val triggers: List<NotificationTrigger> = emptyList(),
    val busy: Boolean = false,
)

/** R6 — server notification preferences (`source:*`, `channel:*`, integration
 *  kinds) + biomarker-threshold triggers. Network-only (no cache — small
 *  payloads, rarely changed). */
class NotificationsSettingsViewModel(
    private val client: BridgeClient,
) : ViewModel() {
    private val _state = MutableStateFlow(NotificationsSettingsUiState())
    val state: StateFlow<NotificationsSettingsUiState> = _state

    init {
        reload()
    }

    fun reload() {
        _state.value = _state.value.copy(loading = _state.value.preferences.isEmpty() && _state.value.triggers.isEmpty(), failed = false)
        viewModelScope.launch {
            val prefs =
                runCatching { client.getNotificationPreferences().data }.getOrNull()
            val triggers =
                runCatching { client.getNotificationTriggers().data }.getOrNull()
            _state.value =
                if (prefs == null && triggers == null) {
                    NotificationsSettingsUiState(loading = false, failed = true)
                } else {
                    NotificationsSettingsUiState(
                        loading = false,
                        preferences = prefs.orEmpty(),
                        triggers = triggers.orEmpty(),
                    )
                }
        }
    }

    fun togglePreference(
        kind: NotificationKind,
        enabled: Boolean,
    ) {
        viewModelScope.launch {
            runCatching { client.setNotificationPreference(kind.kindId, enabled) }
                .onSuccess {
                    _state.value =
                        _state.value.copy(
                            preferences =
                                _state.value.preferences.map {
                                    if (it.kindId == kind.kindId) it.copy(enabled = enabled) else it
                                },
                        )
                }
        }
    }

    fun deleteTrigger(id: String) {
        viewModelScope.launch {
            runCatching { client.deleteNotificationTrigger(id) }
                .onSuccess {
                    _state.value = _state.value.copy(triggers = _state.value.triggers.filter { it.id != id })
                }
        }
    }

    fun createTrigger(
        ruleType: String,
        biomarkerId: String,
        operator: String,
        value: Double,
        severity: String?,
    ) {
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            val payload =
                buildJsonObject {
                    put("rule_type", ruleType)
                    put("biomarker_id", biomarkerId)
                    put("operator", operator)
                    put("value", value)
                    severity?.takeIf { it.isNotBlank() }?.let { put("severity", it) }
                }
            runCatching { client.createNotificationTrigger(payload) }
                .onSuccess { t ->
                    _state.value = _state.value.copy(triggers = _state.value.triggers + t, busy = false)
                }.onFailure { _state.value = _state.value.copy(busy = false) }
        }
    }

    companion object {
        fun factory(client: BridgeClient) =
            viewModelFactory {
                initializer { NotificationsSettingsViewModel(client) }
            }
    }
}

@Composable
fun NotificationsSettingsRoute(
    client: BridgeClient,
    onBack: () -> Unit,
) {
    val vm: NotificationsSettingsViewModel =
        viewModel(factory = NotificationsSettingsViewModel.factory(client))
    val state by vm.state.collectAsStateWithLifecycle()
    val db: io.healthassistant.android.data.cache.ObservationDatabase = org.koin.compose.koinInject()
    val caches =
        remember(client) {
            io.healthassistant.android.data.cache
                .RoomCaches(db, client.integrationId)
        }
    val catalog by caches.biomarkers.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    NotificationsSettingsScreen(
        state = state,
        biomarkerNames = catalog.map { it.name to it.id },
        onBack = onBack,
        onRetry = vm::reload,
        onTogglePreference = vm::togglePreference,
        onDeleteTrigger = vm::deleteTrigger,
        onCreateTrigger = vm::createTrigger,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsSettingsScreen(
    state: NotificationsSettingsUiState,
    biomarkerNames: List<Pair<String, String>>,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onTogglePreference: (NotificationKind, Boolean) -> Unit,
    onDeleteTrigger: (String) -> Unit,
    onCreateTrigger: (ruleType: String, biomarkerId: String, operator: String, value: Double, severity: String?) -> Unit,
) {
    var showCreateTrigger by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notifications_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateTrigger = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.triggers_add))
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.failed ->
                    Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.notifications_settings_failed),
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                    }
                else ->
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        item {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                stringResource(R.string.notifications_preferences_section),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        if (state.preferences.isEmpty()) {
                            item {
                                Text(
                                    stringResource(R.string.notifications_preferences_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                        items(state.preferences, key = { it.kindId }) { kind ->
                            if (kind.mutable) {
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(kind.label ?: kind.kindId, style = MaterialTheme.typography.bodyLarge)
                                        kind.group?.let {
                                            Text(
                                                it,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.outline,
                                            )
                                        }
                                    }
                                    Switch(
                                        checked = kind.enabled == true,
                                        onCheckedChange = { onTogglePreference(kind, it) },
                                    )
                                }
                            }
                        }
                        item {
                            Spacer(Modifier.height(16.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(12.dp))
                            Text(
                                stringResource(R.string.triggers_section),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        if (state.triggers.isEmpty()) {
                            item {
                                Text(
                                    stringResource(R.string.triggers_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                        items(state.triggers, key = { it.id }) { trigger ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        triggerLabel(trigger),
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    trigger.severity?.let {
                                        Text(
                                            it.replaceFirstChar(Char::uppercase),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                TextButton(onClick = { onDeleteTrigger(trigger.id) }) {
                                    Text(stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        item { Spacer(Modifier.height(96.dp)) }
                    }
            }
        }
    }

    if (showCreateTrigger) {
        CreateTriggerDialog(
            busy = state.busy,
            biomarkerNames = biomarkerNames,
            onConfirm = { ruleType, biomarkerId, operator, value, severity ->
                onCreateTrigger(ruleType, biomarkerId, operator, value, severity)
                showCreateTrigger = false
            },
            onDismiss = { showCreateTrigger = false },
        )
    }
}

@Composable
private fun triggerLabel(trigger: NotificationTrigger): String =
    stringResource(
        R.string.triggers_rule_label,
        prettyOperator(trigger.operator ?: ">"),
        trigger.value ?: 0.0,
    )

private fun prettyOperator(op: String): String =
    when (op) {
        ">" -> "above"
        "<" -> "below"
        ">=" -> "at or above"
        else -> op
    }

@Composable
private fun CreateTriggerDialog(
    busy: Boolean,
    biomarkerNames: List<Pair<String, String>>,
    onConfirm: (ruleType: String, biomarkerId: String, operator: String, value: Double, severity: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var valueText by remember { mutableStateOf("") }
    var operator by remember { mutableStateOf(">") }
    var severity by remember { mutableStateOf("info") }
    var biomarkerId by remember { mutableStateOf(biomarkerNames.firstOrNull()?.second ?: "") }
    var biomarkerExpanded by remember { mutableStateOf(false) }
    val operators = listOf(">", "<", ">=", "<=")
    val severities = listOf("info", "warning", "critical")
    val canSubmit = valueText.toDoubleOrNull() != null && biomarkerId.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.triggers_add)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.triggers_biomarker), style = MaterialTheme.typography.titleSmall)
                Box {
                    OutlinedTextField(
                        value =
                            biomarkerNames.firstOrNull { it.second == biomarkerId }?.first
                                ?: stringResource(R.string.triggers_pick_biomarker),
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { biomarkerExpanded = true },
                    )
                    androidx.compose.material3.DropdownMenu(
                        expanded = biomarkerExpanded,
                        onDismissRequest = { biomarkerExpanded = false },
                    ) {
                        biomarkerNames.forEach { (name, id) ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    biomarkerId = id
                                    biomarkerExpanded = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.triggers_operator), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    operators.forEach { op ->
                        FilterChip(
                            selected = operator == op,
                            onClick = { operator = op },
                            label = { Text(op) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = valueText,
                    onValueChange = { valueText = it },
                    label = { Text(stringResource(R.string.triggers_value)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.triggers_severity), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    severities.forEach { sev ->
                        FilterChip(
                            selected = severity == sev,
                            onClick = { severity = sev },
                            label = { Text(sev) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit && !busy,
                onClick = {
                    onConfirm("biomarker_threshold", biomarkerId, operator, valueText.toDouble(), severity)
                },
            ) { Text(stringResource(R.string.action_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
