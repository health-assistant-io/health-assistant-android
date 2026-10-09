package io.healthassistant.android.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.healthassistant.android.R
import io.healthassistant.shared.healthconnect.HcType

@Composable
private fun intervalLabel(minutes: Int): String =
    when (minutes) {
        0 -> stringResource(R.string.settings_interval_manual_only)
        15 -> stringResource(R.string.settings_interval_15)
        30 -> stringResource(R.string.settings_interval_30)
        60 -> stringResource(R.string.settings_interval_60)
        else -> stringResource(R.string.settings_interval_n, minutes)
    }

@Composable
private fun windowLabel(window: SyncHistoryWindow): String =
    when (window) {
        SyncHistoryWindow.LAST_7_DAYS -> stringResource(R.string.settings_window_7d)
        SyncHistoryWindow.LAST_30_DAYS -> stringResource(R.string.settings_window_30d)
        SyncHistoryWindow.LAST_90_DAYS -> stringResource(R.string.settings_window_90d)
        SyncHistoryWindow.LAST_YEAR -> stringResource(R.string.settings_window_year)
        SyncHistoryWindow.ALL -> stringResource(R.string.settings_window_all)
    }

/** Section header with a leading icon (R6 restyle — group + icon). */
@Composable
private fun SectionHeader(
    icon: ImageVector,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.size(8.dp))
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * R6 restyle of the Sync settings content, extracted so the Profile tab can
 * embed it inside an expandable section. A plain non-scrolling [Column] (no
 * nested fixed-height `LazyColumn` and no internal `verticalScroll`) — the
 * embedder owns scrolling: the Profile tab scrolls the whole page, and the
 * standalone [SyncSettingsScreen] supplies its own `verticalScroll` via the
 * [modifier] parameter. (An internal `verticalScroll` here crashed the
 * Profile embed: the section is measured with infinite height inside the
 * page's scrollable column.) Grouped sections with icons and "Advanced" as a
 * real expandable card. Pure state + lambdas — fully Compose-testable.
 *
 * Permission gate: toggling a type **on** calls [onRequestTypePermission] (the
 * owning Route launches the system dialog and, on grant, calls [onToggleType]
 * with `true`). Toggling a type **off** calls [onToggleType] directly.
 */
@Composable
fun SyncSettingsContent(
    settings: SyncSettings,
    sourceAvailable: Boolean,
    syncedCounts: Map<HcType, Int>,
    onToggleSource: (Boolean) -> Unit,
    onToggleType: (HcType, Boolean) -> Unit,
    onRequestTypePermission: (Set<String>) -> Unit,
    onSetInterval: (Int) -> Unit,
    onSetBackgroundReads: (Boolean) -> Unit,
    onSetBatteryWhitelist: (Boolean) -> Unit,
    onSetHistoryWindow: (SyncHistoryWindow) -> Unit,
    onResetCursors: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var advancedOpen by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    val resetBody = stringResource(R.string.settings_reread_history_body)

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.settings_reread_history_title)) },
            text = { Text(resetBody) },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirm = false
                    onResetCursors()
                }) { Text(stringResource(R.string.settings_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionHeader(Icons.Outlined.Dns, stringResource(R.string.settings_data_source))
        SourceCard(
            enabled = settings.healthConnectEnabled,
            available = sourceAvailable,
            enabledCount = settings.enabledTypes.size,
            onToggle = onToggleSource,
        )

        SectionHeader(Icons.Outlined.Schedule, stringResource(R.string.settings_sync_frequency))
        Column(Modifier.selectableGroup()) {
            SyncSettings.INTERVAL_CHOICES.forEach { minutes ->
                IntervalRow(
                    label = intervalLabel(minutes),
                    selected = settings.syncIntervalMinutes == minutes,
                    onSelect = { onSetInterval(minutes) },
                )
            }
        }

        SectionHeader(Icons.Outlined.History, stringResource(R.string.settings_history_heading))
        Text(
            stringResource(R.string.settings_history_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Column(Modifier.selectableGroup()) {
            SyncHistoryWindow.entries.forEach { window ->
                IntervalRow(
                    label = windowLabel(window),
                    selected = settings.historyWindow == window,
                    onSelect = { onSetHistoryWindow(window) },
                )
            }
        }

        SectionHeader(Icons.Outlined.Favorite, stringResource(R.string.settings_types_heading))
        Column(Modifier.fillMaxWidth()) {
            HcType.entries.forEach { type ->
                TypeRow(
                    type = type,
                    enabled = type in settings.enabledTypes,
                    synced = syncedCounts[type] ?: 0,
                    onToggle = { want ->
                        if (want) {
                            onRequestTypePermission(setOf(HealthConnectPermissions.forType(type)))
                        } else {
                            onToggleType(type, false)
                        }
                    },
                )
                HorizontalDivider()
            }
        }

        AdvancedCard(
            expanded = advancedOpen,
            onToggle = { advancedOpen = !advancedOpen },
            onSetBackgroundReads = onSetBackgroundReads,
            onSetBatteryWhitelist = onSetBatteryWhitelist,
            onRereadHistory = { showResetConfirm = true },
            backgroundReadsEnabled = settings.backgroundReadsEnabled,
            batteryWhitelisted = settings.batteryOptimizationWhitelisted,
        )
    }
}

/** R6: "Advanced" as a real expandable card (not a clickable `Text`). */
@Composable
private fun AdvancedCard(
    expanded: Boolean,
    onToggle: () -> Unit,
    onSetBackgroundReads: (Boolean) -> Unit,
    onSetBatteryWhitelist: (Boolean) -> Unit,
    onRereadHistory: () -> Unit,
    backgroundReadsEnabled: Boolean,
    batteryWhitelisted: Boolean,
) {
    Card(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onToggle)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.action_advanced), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                    ToggleRow(
                        label = stringResource(R.string.settings_background_reads),
                        checked = backgroundReadsEnabled,
                        onCheckedChange = onSetBackgroundReads,
                    )
                    ToggleRow(
                        label = stringResource(R.string.settings_battery_whitelist),
                        checked = batteryWhitelisted,
                        onCheckedChange = onSetBatteryWhitelist,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onRereadHistory) { Text(stringResource(R.string.settings_reread_history)) }
                    }
                }
            }
        }
    }
}

/**
 * R6 standalone detail screen (kept for tests + the standalone route): the
 * [SyncSettingsContent] inside a Scaffold with a back TopAppBar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsScreen(
    settings: SyncSettings,
    sourceAvailable: Boolean,
    syncedCounts: Map<HcType, Int>,
    onToggleSource: (Boolean) -> Unit,
    onToggleType: (HcType, Boolean) -> Unit,
    onRequestTypePermission: (Set<String>) -> Unit,
    onSetInterval: (Int) -> Unit,
    onSetBackgroundReads: (Boolean) -> Unit,
    onSetBatteryWhitelist: (Boolean) -> Unit,
    onSetHistoryWindow: (SyncHistoryWindow) -> Unit,
    onResetCursors: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        SyncSettingsContent(
            settings = settings,
            sourceAvailable = sourceAvailable,
            syncedCounts = syncedCounts,
            onToggleSource = onToggleSource,
            onToggleType = onToggleType,
            onRequestTypePermission = onRequestTypePermission,
            onSetInterval = onSetInterval,
            onSetBackgroundReads = onSetBackgroundReads,
            onSetBatteryWhitelist = onSetBatteryWhitelist,
            onSetHistoryWindow = onSetHistoryWindow,
            onResetCursors = onResetCursors,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
        )
    }
}

@Composable
private fun SourceCard(
    enabled: Boolean,
    available: Boolean,
    enabledCount: Int,
    onToggle: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_health_connect), style = MaterialTheme.typography.bodyLarge)
                val status =
                    if (!available) {
                        stringResource(R.string.settings_source_unavailable)
                    } else if (enabled) {
                        stringResource(R.string.settings_source_connected, enabledCount)
                    } else {
                        stringResource(R.string.settings_source_off)
                    }
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            Switch(checked = enabled && available, onCheckedChange = onToggle, enabled = available)
        }
    }
}

@Composable
private fun IntervalRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onSelect).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun TypeRow(
    type: HcType,
    enabled: Boolean,
    synced: Int,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Switch) { onToggle(!enabled) }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(checked = enabled, onCheckedChange = onToggle)
            Column {
                Text(type.display, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${type.code} · ${type.codingSystem}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        Text(
            if (synced > 0) stringResource(R.string.settings_synced_count, synced) else stringResource(R.string.settings_none_dash),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
