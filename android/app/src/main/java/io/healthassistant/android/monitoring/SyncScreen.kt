package io.healthassistant.android.monitoring

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.healthassistant.android.R
import io.healthassistant.shared.healthconnect.HcType
import java.text.DateFormat
import java.util.Date

/**
 * R3: the plain-language Sync screen (replaces the developer-flavoured
 * "Monitoring" screen). Latest readings now live on Home; per-type chart counts
 * move to Insights (R4); manual reading entry moved to Records (R5). This screen
 * keeps: source status, sync progress, a "Couldn't send" (failed readings) list
 * with "Try again", and a confirmed "Clear pending readings". Pure state + lambdas.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    monitor: SyncMonitor,
    onSyncNow: () -> Unit,
    onViewFailed: () -> Unit,
    showFailed: Boolean = false,
    onRetryFailed: (String) -> Unit = {},
    onRetryAllFailed: (() -> Unit)? = null,
    onClearPending: (() -> Unit)? = null,
    onBack: () -> Unit,
) {
    var showDetails by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    val clearBody = stringResource(R.string.sync_clear_pending_body, monitor.outboxPending, monitor.outboxDeadLettered)

    if (showDetails) {
        PerTypeDialog(monitor = monitor, onDismiss = { showDetails = false })
    }
    if (showClearConfirm && onClearPending != null) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.sync_clear_pending_title)) },
            text = { Text(clearBody) },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    onClearPending()
                }) { Text(stringResource(R.string.sync_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sync_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            monitor.sources.forEach { source -> SourceCard(source) }

            if (monitor.outboxPending > 0) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.sync_pending_line, monitor.outboxPending),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            Spacer(Modifier.height(16.dp))
            ProgressCard(monitor)

            Spacer(Modifier.height(16.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onSyncNow) {
                    Text(
                        if (monitor.progress?.active ==
                            true
                        ) {
                            stringResource(R.string.sync_syncing)
                        } else {
                            stringResource(R.string.sync_sync_now)
                        },
                    )
                }
                OutlinedButton(onClick = { showDetails = true }, enabled = monitor.progress != null) {
                    Text(stringResource(R.string.sync_details))
                }
                if (monitor.outboxDeadLettered > 0) {
                    OutlinedButton(onClick = onViewFailed) {
                        Text(stringResource(R.string.sync_view_failed, monitor.outboxDeadLettered))
                    }
                }
                if (onClearPending != null) {
                    OutlinedButton(onClick = { showClearConfirm = true }) { Text(stringResource(R.string.sync_clear_pending)) }
                }
            }

            if (showFailed) {
                Spacer(Modifier.height(16.dp))
                FailedReadingsSection(
                    deadLetters = monitor.deadLetters,
                    totalFailed = monitor.outboxDeadLettered,
                    onRetry = onRetryFailed,
                    onRetryAll = onRetryAllFailed,
                )
            }
        }
    }
}

@Composable
private fun SourceCard(source: SourceState) {
    val lastSyncLabel = source.lastSyncAt?.let { stringResource(R.string.sync_last_sync, formatTime(it)) }
    val statusStr = statusLabel(source.lastSyncStatus)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(source.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (source.available) {
                        stringResource(
                            R.string.sync_source_connected,
                        )
                    } else {
                        stringResource(R.string.sync_source_unavailable)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (source.available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.height(4.dp))
            val statusText =
                buildString {
                    lastSyncLabel?.let { append(it) }
                    if (source.lastSyncAt != null) append(" · ")
                    append(statusStr)
                }
            Text(statusText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.sync_readings_synced), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            val total = source.totalSyncedPerType.values.sum()
            Text(
                if (total == 0) stringResource(R.string.sync_no_readings) else stringResource(R.string.sync_n_readings, total),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun statusLabel(status: SyncStatus): String =
    when (status) {
        is SyncStatus.Success -> stringResource(R.string.sync_status_synced, status.count)
        is SyncStatus.Error -> stringResource(R.string.sync_status_error, status.message)
        SyncStatus.Idle -> stringResource(R.string.sync_status_idle)
        SyncStatus.Reading -> stringResource(R.string.sync_status_reading)
        SyncStatus.Syncing -> stringResource(R.string.sync_status_syncing)
    }

private fun formatTime(epochMs: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochMs))

@Composable
private fun ProgressCard(monitor: SyncMonitor) {
    val progress = monitor.progress
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.sync_progress), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (progress == null) {
                Text(
                    stringResource(R.string.sync_progress_idle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            } else {
                val pct = (progress.fraction * 100).toInt()
                monitor.readingWindow?.let {
                    Text(
                        stringResource(R.string.sync_reading_window, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    if (progress.active) {
                        stringResource(
                            R.string.sync_progress_active,
                            pct,
                        )
                    } else {
                        stringResource(R.string.sync_progress_last_pass, pct)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.sync_processed_of_total, progress.processed, progress.total),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                val eta = progress.etaMs()
                if (progress.active && eta != null) {
                    Text(
                        stringResource(R.string.sync_eta_left, formatDuration(eta)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    return when {
        totalSec < 60 -> "${totalSec}s"
        totalSec < 3600 -> "${totalSec / 60}m"
        else -> "${totalSec / 3600}h ${(totalSec % 3600) / 60}m"
    }
}

@Composable
private fun PerTypeDialog(
    monitor: SyncMonitor,
    onDismiss: () -> Unit,
) {
    val progress = monitor.progress
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_details)) },
        text = {
            Column {
                if (progress == null) {
                    Text(stringResource(R.string.sync_no_sync_run))
                } else {
                    Text(
                        stringResource(
                            R.string.sync_processed_pct,
                            progress.processed,
                            progress.total,
                            (progress.fraction * 100).toInt(),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    HcType.entries.forEach { type ->
                        val synced = progress.perTypeSynced[type] ?: 0
                        val failed = progress.perTypeFailed[type] ?: 0
                        if (synced > 0 || failed > 0) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(type.display, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (failed >
                                        0
                                    ) {
                                        stringResource(R.string.sync_synced_with_failed, synced, failed)
                                    } else {
                                        stringResource(R.string.settings_synced_count, synced)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                    if (progress.perTypeSynced.values.all { it == 0 } && progress.perTypeFailed.values.all { it == 0 }) {
                        Text(
                            stringResource(R.string.sync_waiting_first_batch),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun FailedReadingsSection(
    deadLetters: List<DeadLetterSummary>,
    totalFailed: Int,
    onRetry: (String) -> Unit,
    onRetryAll: (() -> Unit)?,
) {
    Text(stringResource(R.string.sync_failed_heading), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    if (deadLetters.isEmpty()) {
        Text(
            stringResource(R.string.sync_failed_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        return
    }
    if (onRetryAll != null) {
        OutlinedButton(onClick = onRetryAll) { Text(stringResource(R.string.sync_retry_all)) }
        Spacer(Modifier.height(8.dp))
    }
    if (totalFailed > deadLetters.size) {
        Text(
            stringResource(R.string.sync_failed_showing_first, deadLetters.size, totalFailed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(8.dp))
    }
    Column {
        deadLetters.forEach { item ->
            FailedItemRow(item, onRetry)
            HorizontalDivider()
        }
    }
}

@Composable
private fun FailedItemRow(
    item: DeadLetterSummary,
    onRetry: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(failedItemName(item), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.sync_tried_n_times, item.attempts),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Text(
            item.reason ?: stringResource(R.string.sync_failed_unknown_reason),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = { onRetry(item.id) }) { Text(stringResource(R.string.sync_try_again)) }
    }
}

@Composable
private fun failedItemName(item: DeadLetterSummary): String =
    when {
        item.path == "/sync" -> stringResource(R.string.sync_item_health_reading)
        item.path.startsWith("/examinations") -> stringResource(R.string.sync_item_examination)
        else -> item.path
    }
