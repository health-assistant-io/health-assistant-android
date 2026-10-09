package io.healthassistant.android.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.healthassistant.android.R
import io.healthassistant.android.settings.UiMode
import io.healthassistant.android.settings.UiTheme
import io.healthassistant.android.ui.theme.AuroraDarkColors
import io.healthassistant.android.ui.theme.AuroraLightColors
import io.healthassistant.android.ui.theme.HADarkColors
import io.healthassistant.android.ui.theme.HALightColors

/**
 * Profile tab — the settings hub. Each concern lives on its own detail page
 * (hub → page, replacing the old expandable cards which nested scrolling and
 * crashed on expand); this screen shows the connection card, the Simple /
 * Advanced mode pick (K-simple-mode), and one tappable row per destination.
 * Rows with a null callback are hidden (K.3: SIMPLE keeps connection, mode,
 * app-lock and About; the advanced surfaces — web dashboard, sync, server
 * notifications, device notifications, accessibility — drop out). Pure state
 * + lambdas; the owning [ProfileRoute] wires navigation.
 */
@Composable
fun ProfileScreen(
    connectionLabel: String?,
    connectionId: String?,
    onSwitchConnection: (() -> Unit)?,
    onOpenWebApp: (() -> Unit)? = null,
    onOpenSync: (() -> Unit)?,
    onOpenSyncSettings: (() -> Unit)?,
    onOpenDataStorage: (() -> Unit)?,
    onOpenAlerts: (() -> Unit)? = null,
    onOpenServerNotifications: (() -> Unit)?,
    onOpenDeviceNotifications: (() -> Unit)?,
    onOpenAccessibility: (() -> Unit)?,
    onOpenPrivacy: () -> Unit,
    onOpenAbout: () -> Unit,
    onDisconnect: (() -> Unit)?,
    mode: UiMode = UiMode.ADVANCED,
    onSetMode: (UiMode) -> Unit = {},
    theme: UiTheme = UiTheme.AURORA,
    onSetTheme: (UiTheme) -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Person,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(12.dp))
            Text(stringResource(R.string.profile_title), style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(4.dp))

        ConnectionSection(
            connectionLabel = connectionLabel,
            connectionId = connectionId,
            onSwitchConnection = onSwitchConnection,
            onDisconnect = onDisconnect,
        )
        Spacer(Modifier.height(16.dp))
        SettingsSection {
            ModeRowWithMenu(mode = mode, onSelect = onSetMode)
            HorizontalDivider(Modifier.padding(start = 40.dp), color = MaterialTheme.colorScheme.outlineVariant)
            ThemeRowWithMenu(theme = theme, onSelect = onSetTheme, showDivider = false)
        }
        onOpenWebApp?.let {
            Spacer(Modifier.height(16.dp))
            SettingsRow(
                icon = Icons.Outlined.Language,
                title = stringResource(R.string.profile_open_web),
                subtitle = stringResource(R.string.profile_open_web_subtitle),
                onClick = it,
                showDivider = false,
            )
        }
        Spacer(Modifier.height(16.dp))

        SettingsSection {
            onOpenSync?.let {
                SettingsRow(
                    icon = Icons.Outlined.Sync,
                    title = stringResource(R.string.profile_open_sync),
                    onClick = it,
                )
            }
            onOpenSyncSettings?.let {
                SettingsRow(
                    icon = Icons.Outlined.Settings,
                    title = stringResource(R.string.profile_settings_section),
                    subtitle = stringResource(R.string.profile_settings_subtitle),
                    onClick = it,
                )
            }
            onOpenDataStorage?.let {
                SettingsRow(
                    icon = Icons.Outlined.Storage,
                    title = stringResource(R.string.data_storage_title),
                    onClick = it,
                )
            }
            onOpenAlerts?.let {
                SettingsRow(
                    icon = Icons.Outlined.NotificationsActive,
                    title = stringResource(R.string.alerts_title),
                    subtitle = stringResource(R.string.profile_alerts_subtitle),
                    onClick = it,
                )
            }
            onOpenServerNotifications?.let {
                SettingsRow(
                    icon = Icons.Outlined.CloudSync,
                    title = stringResource(R.string.notifications_settings_title),
                    subtitle = stringResource(R.string.profile_server_notifications_subtitle),
                    onClick = it,
                )
            }
            onOpenDeviceNotifications?.let {
                SettingsRow(
                    icon = Icons.Outlined.Notifications,
                    title = stringResource(R.string.profile_notifications_section),
                    onClick = it,
                )
            }
            onOpenAccessibility?.let {
                SettingsRow(
                    icon = Icons.Outlined.Accessibility,
                    title = stringResource(R.string.profile_accessibility_section),
                    onClick = it,
                )
            }
            SettingsRow(
                icon = Icons.Outlined.Lock,
                title = stringResource(R.string.profile_privacy_section),
                onClick = onOpenPrivacy,
            )
            SettingsRow(
                icon = Icons.Outlined.Info,
                title = stringResource(R.string.profile_about_section),
                onClick = onOpenAbout,
                showDivider = false,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ConnectionSection(
    connectionLabel: String?,
    connectionId: String?,
    onSwitchConnection: (() -> Unit)?,
    onDisconnect: (() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.profile_connection_section),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        connectionLabel?.let {
            Text(stringResource(R.string.profile_server, it), style = MaterialTheme.typography.bodyMedium)
        }
        connectionId?.let {
            Text(
                stringResource(R.string.profile_connection_id, it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            onSwitchConnection?.let {
                OutlinedButton(onClick = it) {
                    Icon(Icons.Outlined.SwapHoriz, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.dashboard_switch))
                }
            }
            onDisconnect?.let {
                TextButton(onClick = it) {
                    Text(stringResource(R.string.dashboard_disconnect))
                }
            }
        }
    }
}

/** A transparent group of [SettingsRow]s — no card chrome; rows are
 *  separated by inset hairline dividers (the modern settings-list look). */
@Composable
private fun SettingsSection(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        content()
    }
}

/** Profile's Simple/Advanced pick, compact: one row showing the current
 *  level; tapping opens a dropdown with the two options (the expanded
 *  [AppModeSection] stays in the onboarding wizard, where it is the main
 *  content). */
@Composable
private fun ModeRowWithMenu(
    mode: UiMode,
    onSelect: (UiMode) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        SettingsRow(
            icon = Icons.Outlined.Tune,
            title = stringResource(R.string.mode_title),
            subtitle =
                stringResource(
                    if (mode == UiMode.SIMPLE) R.string.mode_simple else R.string.mode_advanced,
                ),
            onClick = { menuOpen = true },
            showDivider = false,
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            UiMode.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(stringResource(if (option == UiMode.SIMPLE) R.string.mode_simple else R.string.mode_advanced))
                            Text(
                                stringResource(if (option == UiMode.SIMPLE) R.string.mode_simple_hint else R.string.mode_advanced_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    trailingIcon = {
                        if (option == mode) {
                            Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    onClick = {
                        menuOpen = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

/** Profile's Appearance pick, compact: one row showing the current theme;
 *  tapping opens a dropdown with the three presets (swatch + name + check). */
@Composable
private fun ThemeRowWithMenu(
    theme: UiTheme,
    onSelect: (UiTheme) -> Unit,
    showDivider: Boolean = true,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        SettingsRow(
            icon = Icons.Outlined.Palette,
            title = stringResource(R.string.appearance_title),
            subtitle = currentThemeLabel(theme),
            onClick = { menuOpen = true },
            showDivider = showDivider,
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            UiTheme.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(currentThemeLabel(option)) },
                    leadingIcon = { ThemeSwatch(option) },
                    trailingIcon = {
                        if (option == theme) {
                            Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    onClick = {
                        menuOpen = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun currentThemeLabel(theme: UiTheme): String =
    when (theme) {
        UiTheme.AURORA -> stringResource(R.string.theme_aurora)
        UiTheme.TEAL -> stringResource(R.string.theme_teal)
        UiTheme.MATERIAL_YOU -> stringResource(R.string.theme_material_you)
    }

@Composable
private fun ThemeSwatch(theme: UiTheme) {
    val colors =
        when (theme) {
            UiTheme.AURORA -> Triple(AuroraLightColors.primary, AuroraLightColors.primaryContainer, AuroraDarkColors.primary)
            UiTheme.TEAL -> Triple(HALightColors.primary, HALightColors.primaryContainer, HADarkColors.primary)
            UiTheme.MATERIAL_YOU -> Triple(Color(0xFF5B69E0), Color(0xFFD8DEFF), Color(0xFFBEC2FF))
        }
    Canvas(Modifier.size(width = 34.dp, height = 20.dp)) {
        drawCircle(colors.third, radius = 9.dp.toPx(), center = center.copy(x = 9.dp.toPx()))
        drawCircle(colors.first, radius = 9.dp.toPx(), center = center.copy(x = 17.dp.toPx()))
        drawCircle(colors.second, radius = 5.dp.toPx(), center = center.copy(x = 17.dp.toPx()))
    }
}

/** A single tappable settings row: leading icon, title (+ optional subtitle),
 *  trailing chevron, and an inset divider underneath unless it is the group's
 *  last row ([showDivider] = false). */
@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    showDivider: Boolean = true,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 40.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

/** Plain-language OSS notice shown from About › Open-source licenses. */
@Composable
fun OpenSourceLicensesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_about_licenses)) },
        text = { Text(stringResource(R.string.profile_about_licenses_body)) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}
