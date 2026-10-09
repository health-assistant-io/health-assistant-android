package io.healthassistant.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.healthassistant.android.R
import io.healthassistant.android.data.cache.ObservationDatabase
import io.healthassistant.android.data.cache.RoomCaches
import io.healthassistant.android.data.repository.BridgeNotificationGateway
import io.healthassistant.android.ui.components.RichText
import io.healthassistant.android.ui.components.StaleChipSlot
import io.healthassistant.bridge.BridgeClient
import io.healthassistant.bridge.NotificationItem
import io.healthassistant.shared.data.cache.CacheDomain
import io.healthassistant.shared.data.repository.ConnectivityProvider
import io.healthassistant.shared.data.repository.NotificationRepository
import io.healthassistant.shared.richtext.toSnippet
import org.koin.compose.koinInject

/**
 * Phase I — the notification inbox. Shows the owner's notifications with
 * mark-read / mark-dismissed / mark-all-read. Reachable as a detail route
 * from the Today screen's Inbox section + Profile's Notifications section.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxRoute(
    client: BridgeClient,
    onBack: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
) {
    val db: ObservationDatabase = koinInject()
    val connectivity: ConnectivityProvider = koinInject()
    val caches = remember(client) { RoomCaches(db, client.integrationId) }
    val repo =
        remember(client) {
            NotificationRepository(caches.notifications, BridgeNotificationGateway(client), connectivity, caches.meta)
        }
    val vm: InboxViewModel = viewModel(factory = InboxViewModel.factory(repo))
    val state by vm.state.collectAsStateWithLifecycle()
    val staleMeta by caches.meta.observe(CacheDomain.NOTIFICATIONS).collectAsStateWithLifecycle(initialValue = null)
    val online = rememberConnectivity() == Connectivity.Online

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Inbox" + if (state.unreadCount > 0) " (${state.unreadCount})" else "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    onOpenSettings?.let { openSettings ->
                        IconButton(onClick = openSettings) {
                            Icon(
                                Icons.Outlined.Settings,
                                contentDescription = stringResource(R.string.notifications_settings_title),
                            )
                        }
                    }
                    if (state.unreadCount > 0) {
                        TextButton(onClick = { vm.markAllRead() }) {
                            Icon(Icons.Outlined.DoneAll, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("Mark all read")
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading ->
                Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            state.error != null ->
                Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error)
                }
            state.items.isEmpty() ->
                Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.Notifications,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("No notifications", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "You're all caught up.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            else ->
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding),
                ) {
                    item { StaleChipSlot(meta = staleMeta, online = online, onRetry = { vm.reload() }) }
                    items(state.items, key = { it.recipientId }) { item ->
                        InboxRow(
                            item = item,
                            onTap = { vm.openDetail(item) },
                        )
                        HorizontalDivider()
                    }
                }
        }
    }

    state.selected?.let { selected ->
        NotificationDetailSheet(
            item = selected,
            onDismiss = { vm.closeDetail() },
            onMarkDismissed = { vm.dismissSelected() },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotificationDetailSheet(
    item: NotificationItem,
    onDismiss: () -> Unit,
    onMarkDismissed: () -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val n = item.notification
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                n?.title ?: "Notification",
                style = MaterialTheme.typography.titleLarge,
            )
            n?.createdAt?.let { created ->
                Spacer(Modifier.height(4.dp))
                Text(
                    prettyTimestamp(created),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            val chips =
                buildList {
                    n?.severity?.let { add(it.replaceFirstChar { c -> c.uppercase() }) }
                    n?.category?.let { add(it.replaceFirstChar { c -> c.uppercase() }) }
                    item.status?.let { if (it == "unread") add("Unread") }
                }
            if (chips.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement =
                        androidx.compose.foundation.layout.Arrangement
                            .spacedBy(8.dp),
                ) {
                    chips.forEach { label -> NotificationChip(label) }
                }
            }
            n?.body?.takeIf { it.isNotBlank() }?.let { body ->
                Spacer(Modifier.height(16.dp))
                RichText(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(24.dp))
            androidx.compose.material3.OutlinedButton(
                onClick = onMarkDismissed,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Dismiss", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun NotificationChip(label: String) {
    androidx.compose.material3.Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

private fun prettyTimestamp(iso: String): String =
    runCatching {
        val t = java.time.OffsetDateTime.parse(iso)
        t.format(
            java.time.format.DateTimeFormatter
                .ofPattern("MMM d, yyyy · HH:mm"),
        )
    }.getOrElse {
        runCatching {
            java.time.LocalDateTime
                .parse(iso)
                .format(
                    java.time.format.DateTimeFormatter
                        .ofPattern("MMM d, yyyy · HH:mm"),
                )
        }.getOrDefault(iso)
    }

@Composable
private fun InboxRow(
    item: NotificationItem,
    onTap: () -> Unit,
) {
    val isUnread = item.status == "unread"
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Unread dot.
        Box(Modifier.size(8.dp).padding(top = 6.dp)) {
            if (isUnread) {
                Box(
                    Modifier.size(8.dp),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                    )
                }
            }
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.notification?.title ?: "Notification",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isUnread) FontWeight.Bold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.notification?.body?.let { body ->
                toSnippet(body, 100)?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
