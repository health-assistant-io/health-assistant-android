package io.healthassistant.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.healthassistant.android.R
import io.healthassistant.android.data.ServerReachability
import io.healthassistant.android.monitoring.SyncMonitor
import io.healthassistant.android.ui.components.MetricCard
import io.healthassistant.android.ui.components.SavedDataBanner
import io.healthassistant.android.ui.components.relativeTimeMillis
import io.healthassistant.shared.data.BiomarkerReading
import io.healthassistant.shared.data.HomeViewStyle
import io.healthassistant.shared.data.displayName
import io.healthassistant.shared.richtext.toSnippet

/**
 * K.3/K.5 — the effective Home view style under the app-wide mode. SIMPLE
 * forces the large-print single-column card style (the R1 large `MetricCard`
 * variant) regardless of the stored dashboard pref, which stays untouched.
 */
fun effectiveHomeViewStyle(
    stored: HomeViewStyle,
    simpleMode: Boolean,
): HomeViewStyle = if (simpleMode) HomeViewStyle.SIMPLE else stored

/**
 * The Home tab — the single daily check-in surface (Home + Today merged).
 * Brand header, a quiet sync-status row, configurable "Today" metric cards,
 * then the check-in sections: active medications, recent results, and an
 * inbox preview — every row tappable into its native detail. Pull-to-refresh
 * triggers a sync. Pure state + lambdas so it is Compose-testable with a fake
 * [SyncMonitor].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    monitor: SyncMonitor,
    readings: List<BiomarkerReading>,
    connectionLabel: String?,
    reachability: ServerReachability,
    staleMeta: io.healthassistant.shared.data.cache.CacheMetaState? = null,
    online: Boolean = true,
    medications: List<io.healthassistant.bridge.Medication> = emptyList(),
    recentExams: List<io.healthassistant.shared.data.ExaminationSummary> = emptyList(),
    inbox: List<io.healthassistant.bridge.NotificationItem> = emptyList(),
    unreadCount: Int = 0,
    viewStyle: HomeViewStyle = HomeViewStyle.GRID,
    allergies: List<io.healthassistant.bridge.Allergy> = emptyList(),
    onCycleViewStyle: ((HomeViewStyle) -> Unit)? = null,
    onOpenEdit: (() -> Unit)? = null,
    onSyncNow: () -> Unit,
    onSwitchConnection: (() -> Unit)? = null,
    onOpenSync: (() -> Unit)? = null,
    onOpenInsights: ((String) -> Unit)? = null,
    onOpenMedication: ((io.healthassistant.bridge.Medication) -> Unit)? = null,
    onOpenExam: ((String) -> Unit)? = null,
    onOpenInbox: (() -> Unit)? = null,
    onOpenAssistant: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val isRefreshing = monitor.progress?.active == true
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onSyncNow,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            HomeHeader(
                connectionLabel = connectionLabel,
                onSwitchConnection = onSwitchConnection,
                viewStyle = viewStyle,
                onCycleViewStyle = onCycleViewStyle,
                onOpenEdit = onOpenEdit,
            )
            // Offline-first M8 — the saved-data banner replaces the plain
            // offline banner whenever there is a cached snapshot behind it
            // ("Showing saved data · offline" / "Couldn't refresh — tap to
            // retry"). An empty cache offline keeps the plain banner.
            val hasSavedData = staleMeta != null && (staleMeta.rowCount > 0 || staleMeta.lastSuccessAtEpochMs > 0L)
            if (reachability != ServerReachability.Online) {
                Spacer(Modifier.height(12.dp))
                if (hasSavedData) {
                    SavedDataBanner(meta = staleMeta, online = online, onRetry = onSyncNow)
                } else {
                    OfflineBanner(reachability)
                }
            } else if (staleMeta?.lastError != null) {
                Spacer(Modifier.height(12.dp))
                SavedDataBanner(meta = staleMeta, online = online, onRetry = onSyncNow)
            }
            Spacer(Modifier.height(12.dp))
            SyncStatusRow(
                monitor = monitor,
                staleMeta = staleMeta,
                onOpenSync = onOpenSync,
            )

            if (onOpenAssistant != null) {
                Spacer(Modifier.height(12.dp))
                AssistantCard(onOpen = onOpenAssistant)
            }

            // Phase H (H.3) — safety-critical: the patient's active allergies are
            // always visible on Home, in both modes, above the metric cards.
            if (allergies.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                AllergiesSafetyCard(allergies)
            }

            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.home_today), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (readings.isEmpty()) {
                Text(
                    stringResource(R.string.home_empty_dashboard),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            MetricGrid(readings, onOpenInsights, viewStyle)

            if (medications.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                SectionHeader(icon = Icons.Outlined.Medication, title = stringResource(R.string.home_medications))
                Spacer(Modifier.height(4.dp))
                medications.forEachIndexed { index, med ->
                    MedicationRow(med, onOpenMedication)
                    if (index < medications.lastIndex) HorizontalDivider()
                }
            }

            if (recentExams.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                SectionHeader(icon = Icons.Outlined.CalendarMonth, title = stringResource(R.string.home_recent_results))
                Spacer(Modifier.height(4.dp))
                recentExams.forEachIndexed { index, exam ->
                    ExamRow(exam, onOpenExam)
                    if (index < recentExams.lastIndex) HorizontalDivider()
                }
            }

            if (onOpenInbox != null) {
                Spacer(Modifier.height(24.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenInbox)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionHeader(icon = Icons.Outlined.Notifications, title = stringResource(R.string.home_inbox))
                    Spacer(Modifier.weight(1f))
                    if (unreadCount > 0) {
                        Badge {
                            Text(
                                unreadCount.coerceAtMost(99).toString(),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                if (inbox.isEmpty()) {
                    Text(
                        stringResource(R.string.home_inbox_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                } else {
                    inbox.forEachIndexed { index, item ->
                        InboxPreviewRow(item)
                        if (index < inbox.lastIndex) HorizontalDivider()
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HomeHeader(
    connectionLabel: String?,
    onSwitchConnection: (() -> Unit)?,
    viewStyle: HomeViewStyle,
    onCycleViewStyle: ((HomeViewStyle) -> Unit)?,
    onOpenEdit: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.ha_brand_icon),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            connectionLabel?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                )
            }
        }
        if (onSwitchConnection != null) {
            SuggestionChip(
                onClick = onSwitchConnection,
                label = { Text(stringResource(R.string.dashboard_switch)) },
            )
        }
        if (onCycleViewStyle != null) {
            Spacer(Modifier.width(4.dp))
            ViewStyleMenu(
                current = viewStyle,
                onChange = onCycleViewStyle,
            )
        }
        if (onOpenEdit != null) {
            IconButton(onClick = onOpenEdit) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.home_edit_dashboard),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ViewStyleMenu(
    current: HomeViewStyle,
    onChange: (HomeViewStyle) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    IconButton(onClick = { menuOpen = true }) {
        Icon(
            Icons.Outlined.Dashboard,
            contentDescription = stringResource(R.string.home_view_style),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        HomeViewStyle.entries.forEach { style ->
            DropdownMenuItem(
                text = { Text(viewStyleLabel(style)) },
                onClick = {
                    menuOpen = false
                    onChange(style)
                },
            )
        }
    }
}

@Composable
fun viewStyleLabel(style: HomeViewStyle): String =
    when (style) {
        HomeViewStyle.GRID -> stringResource(R.string.home_view_grid)
        HomeViewStyle.LIST -> stringResource(R.string.home_view_list)
        HomeViewStyle.SIMPLE -> stringResource(R.string.home_view_simple)
    }

/** Entry point into the web app's AI assistant (`/ai-assistant`) — the full
 *  chat (tools, citations, HITL proposal cards) lives on the PWA side; the
 *  native app hands off to it in a Custom Tab. */
@Composable
private fun AssistantCard(onOpen: () -> Unit) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors =
            androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.home_ask_assistant),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    stringResource(R.string.home_ask_assistant_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun OfflineBanner(reachability: ServerReachability) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(
                    if (reachability == ServerReachability.NoInternet) R.string.home_offline else R.string.home_server_unreachable,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun SyncStatusRow(
    monitor: SyncMonitor,
    staleMeta: io.healthassistant.shared.data.cache.CacheMetaState?,
    onOpenSync: (() -> Unit)?,
) {
    val progress = monitor.progress
    val hasErrors = monitor.outboxDeadLettered > 0
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (progress?.active == true) {
                Icon(Icons.Outlined.Sync, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.home_syncing, (progress.fraction * 100).toInt()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                val statusIcon =
                    if (hasErrors) Icons.Outlined.ErrorOutline else Icons.Outlined.CloudDone
                val title =
                    if (hasErrors) stringResource(R.string.home_couldnt_sync) else stringResource(R.string.home_up_to_date)
                val tint =
                    if (hasErrors) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                val sub =
                    if (hasErrors) {
                        stringResource(R.string.home_tap_to_review)
                    } else {
                        cacheUpdatedLabel(staleMeta) ?: lastSyncLabel(monitor)
                    }
                Icon(statusIcon, contentDescription = null, tint = tint)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium)
                    if (sub != null) {
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
                if (hasErrors && onOpenSync != null) {
                    TextButton(onClick = onOpenSync) {
                        Text(stringResource(R.string.home_tap_to_review))
                    }
                }
            }
        }
    }
}

@Composable
private fun lastSyncLabel(monitor: SyncMonitor): String? {
    val lastSyncAt = monitor.sources.mapNotNull { it.lastSyncAt }.maxOrNull()
    return lastSyncAt?.let { stringResource(R.string.home_last_sync, relativeTimeMillis(it)) }
}

/** M9 pull-to-refresh polish: the synced "Updated X ago" line prefers the
 *  observations cache's last-success stamp (the same StaleChip source) — it
 *  updates on every pull refresh, not only when a push sync runs. */
@Composable
private fun cacheUpdatedLabel(meta: io.healthassistant.shared.data.cache.CacheMetaState?): String? =
    meta?.lastSuccessAtEpochMs?.takeIf { it > 0L }?.let {
        stringResource(R.string.stale_updated, relativeTimeMillis(it))
    }

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MetricGrid(
    readings: List<BiomarkerReading>,
    onOpenInsights: ((String) -> Unit)?,
    viewStyle: HomeViewStyle,
) {
    when (viewStyle) {
        HomeViewStyle.GRID ->
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                maxItemsInEachRow = 2,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                readings.forEach { reading ->
                    MetricCard(
                        reading = reading,
                        onClick = onOpenInsights?.let { cb -> { cb(reading.code) } },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

        HomeViewStyle.LIST ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                readings.forEach { reading ->
                    MetricCard(
                        reading = reading,
                        onClick = onOpenInsights?.let { cb -> { cb(reading.code) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

        HomeViewStyle.SIMPLE ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                readings.forEach { reading ->
                    MetricCard(
                        reading = reading,
                        large = true,
                        onClick = onOpenInsights?.let { cb -> { cb(reading.code) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
    }
}

@Composable
private fun SectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun MedicationRow(
    med: io.healthassistant.bridge.Medication,
    onOpenMedication: ((io.healthassistant.bridge.Medication) -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .let { m -> if (onOpenMedication != null) m.clickable(onClick = { onOpenMedication(med) }) else m }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Medication,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val name = med.displayName ?: med.dosage ?: stringResource(R.string.home_medication_fallback)
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            // Dosage as subtitle only when it isn't already the title (no
            // displayName → the title IS the dosage; don't render it twice).
            med.dosage?.takeIf { it.isNotBlank() && it != name }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ExamRow(
    exam: io.healthassistant.shared.data.ExaminationSummary,
    onOpenExam: ((String) -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .let { m -> if (onOpenExam != null) m.clickable(onClick = { onOpenExam(exam.id) }) else m }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.CalendarMonth,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                exam.examinationDate ?: exam.id,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
            )
            val sub =
                buildList {
                    exam.notes?.let { notes -> toSnippet(notes, 80)?.let { add(it) } }
                    exam.extractionStatus?.let { add(prettyExamStatus(it)) }
                }.joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun InboxPreviewRow(item: io.healthassistant.bridge.NotificationItem) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(
            item.notification?.title ?: stringResource(R.string.home_notification_fallback),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            fontWeight =
                if (item.status ==
                    "unread"
                ) {
                    androidx.compose.ui.text.font.FontWeight.Bold
                } else {
                    androidx.compose.ui.text.font.FontWeight.Normal
                },
        )
        item.notification?.body?.let { body ->
            toSnippet(body, 80)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun prettyExamStatus(status: String): String =
    when (status.lowercase()) {
        "completed" -> "Ready"
        "failed" -> "Failed"
        "pending" -> "Pending"
        else -> "Processing"
    }

/** Phase H (H.3) — the safety-critical allergies card. Lists the active
 *  allergies in plain language ("Allergies: Penicillin, Latex") so a care
 *  giver or the user themselves never has to dig for them. Shown whenever the
 *  list is non-empty (in both Simple and Advanced mode). */
@Composable
private fun AllergiesSafetyCard(allergies: List<io.healthassistant.bridge.Allergy>) {
    val names = allergies.mapNotNull { it.displayName }.filter { it.isNotBlank() }
    if (names.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.home_allergies_safety, names.take(3).joinToString(", ")),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}
