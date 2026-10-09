package io.healthassistant.android.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.healthassistant.android.R
import io.healthassistant.android.data.MedicationReminderRepository
import io.healthassistant.android.settings.DashboardPrefsRepository
import io.healthassistant.android.settings.SyncSettingsRepository
import io.healthassistant.android.settings.UiPreferences
import io.healthassistant.android.settings.UiPreferencesRepository
import io.healthassistant.android.settings.rememberSyncSettingsController
import io.healthassistant.android.ui.components.rememberActionHaptic
import io.healthassistant.android.work.MedicationReminderScheduler
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Shared chrome for the Profile detail pages: Scaffold + back TopAppBar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileDetailScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
            content()
        }
    }
}

// --- Sync settings ---------------------------------------------------------

/** Profile › Sync settings — the standalone [io.healthassistant.android.settings.SyncSettingsScreen]. */
@Composable
fun SyncSettingsDetailRoute(onBack: () -> Unit) {
    val controller =
        rememberSyncSettingsController(
            repository = koinInject<SyncSettingsRepository>(),
        )
    io.healthassistant.android.settings.SyncSettingsScreen(
        settings = controller.settings,
        sourceAvailable = controller.sourceAvailable,
        syncedCounts = emptyMap(),
        onToggleSource = controller.onToggleSource,
        onToggleType = controller.onToggleType,
        onRequestTypePermission = controller.onRequestTypePermission,
        onSetInterval = controller.onSetInterval,
        onSetBackgroundReads = controller.onSetBackgroundReads,
        onSetBatteryWhitelist = controller.onSetBatteryWhitelist,
        onSetHistoryWindow = controller.onSetHistoryWindow,
        onResetCursors = controller.onResetCursors,
        onBack = onBack,
    )
}

// --- Accessibility ---------------------------------------------------------

/** Profile › Accessibility — display prefs (high contrast, reduce motion, simple mode). */
@Composable
fun AccessibilityRoute(onBack: () -> Unit) {
    val uiPrefsRepository: UiPreferencesRepository = koinInject()
    val dashboardPrefs: DashboardPrefsRepository = koinInject()
    val uiPreferences by uiPrefsRepository.uiPreferences.collectAsStateWithLifecycle(initialValue = UiPreferences())
    val viewStyle by dashboardPrefs.viewStyle.collectAsStateWithLifecycle(initialValue = io.healthassistant.shared.data.HomeViewStyle.GRID)
    val scope = rememberCoroutineScope()
    val actionHaptic = rememberActionHaptic()

    ProfileDetailScaffold(title = stringResource(R.string.profile_accessibility_section), onBack = onBack) {
        Text(
            stringResource(R.string.profile_accessibility_text_scale_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.size(12.dp))
        ToggleRow(
            label = stringResource(R.string.profile_accessibility_high_contrast),
            checked = uiPreferences.highContrast,
            onCheckedChange = { enabled ->
                actionHaptic()
                scope.launch { uiPrefsRepository.setHighContrast(enabled) }
            },
        )
        ToggleRow(
            label = stringResource(R.string.profile_accessibility_reduce_motion),
            checked = uiPreferences.reduceMotion,
            onCheckedChange = { enabled ->
                actionHaptic()
                scope.launch { uiPrefsRepository.setReduceMotion(enabled) }
            },
        )
        ToggleRow(
            label = stringResource(R.string.profile_accessibility_simple_mode),
            checked = viewStyle == io.healthassistant.shared.data.HomeViewStyle.SIMPLE,
            onCheckedChange = { enabled ->
                actionHaptic()
                val style =
                    if (enabled) {
                        io.healthassistant.shared.data.HomeViewStyle.SIMPLE
                    } else {
                        io.healthassistant.shared.data.HomeViewStyle.GRID
                    }
                scope.launch { dashboardPrefs.setViewStyle(style) }
            },
        )
    }
}

// --- Device notifications --------------------------------------------------

/** Profile › Notifications — on-device notification prefs: the system channel
 *  settings + the daily medication reminder. */
@Composable
fun DeviceNotificationsRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo: MedicationReminderRepository = koinInject()
    val prefs by repo.prefs.collectAsStateWithLifecycle(
        initialValue =
            io.healthassistant.shared.reminders
                .MedicationReminderPrefs(),
    )
    val scope = rememberCoroutineScope()

    fun update(mutate: suspend () -> Unit) {
        scope.launch {
            mutate()
            MedicationReminderScheduler.schedule(context, repo.currentPrefs())
        }
    }

    ProfileDetailScaffold(title = stringResource(R.string.profile_notifications_section), onBack = onBack) {
        Text(
            stringResource(R.string.profile_notifications_body),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.size(8.dp))
        OutlinedButton(
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.profile_notifications_open_settings))
        }
        Spacer(Modifier.size(12.dp))
        MedicationReminderSection(
            enabled = prefs.enabled,
            hour = prefs.hour,
            onSetEnabled = { enabled -> update { repo.setEnabled(enabled) } },
            onSetHour = { hour -> update { repo.setHour(hour) } },
        )
    }
}

// --- Privacy & app lock ----------------------------------------------------

/** Profile › Privacy & data — the plain-language data notice + the app-lock controls. */
@Composable
fun PrivacyRoute(onBack: () -> Unit) {
    ProfileDetailScaffold(title = stringResource(R.string.profile_privacy_section), onBack = onBack) {
        Text(
            stringResource(R.string.profile_privacy_body),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.size(16.dp))
        AppLockSection()
    }
}

// --- About -----------------------------------------------------------------

/** Profile › About — version, open-source licenses, docs. */
@Composable
fun AboutRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    var showLicenses by remember { mutableStateOf(false) }
    val versionName = remember { context.appVersionName() }
    val docsUrl = stringResource(R.string.profile_about_docs_url)

    if (showLicenses) {
        OpenSourceLicensesDialog(onDismiss = { showLicenses = false })
    }

    ProfileDetailScaffold(title = stringResource(R.string.profile_about_section), onBack = onBack) {
        Text(
            stringResource(R.string.profile_about_version, versionName),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.size(8.dp))
        DetailActionRow(
            icon = Icons.Outlined.Science,
            label = stringResource(R.string.profile_about_licenses),
            onClick = { showLicenses = true },
        )
        DetailActionRow(
            icon = Icons.Outlined.Info,
            label = stringResource(R.string.profile_about_docs),
            onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(docsUrl))) }
            },
        )
    }
}

private fun Context.appVersionName(): String =
    runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

/** Full-width text-button row with icon + chevron (About page actions). */
@Composable
private fun DetailActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
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

/** Phase I (medication reminders) — enable toggle + reminder-time row with a
 *  Material3 [TimePickerDialog]. Pure state + lambdas. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MedicationReminderSection(
    enabled: Boolean,
    hour: Int,
    onSetEnabled: (Boolean) -> Unit,
    onSetHour: (Int) -> Unit,
) {
    Column {
        Text(
            stringResource(R.string.profile_med_reminders_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ToggleRow(
            label = stringResource(R.string.profile_med_reminders),
            checked = enabled,
            onCheckedChange = onSetEnabled,
        )
        if (enabled) {
            var showTimePicker by remember { mutableStateOf(false) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showTimePicker = true }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.profile_med_reminders_time), style = MaterialTheme.typography.bodyLarge)
                Text(
                    "%02d:%02d".format(hour.coerceIn(0, 23), 0),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (showTimePicker) {
                val timeState = rememberTimePickerState(initialHour = hour.coerceIn(0, 23), initialMinute = 0, is24Hour = true)
                TimePickerDialog(
                    onDismissRequest = { showTimePicker = false },
                    title = { Text(stringResource(R.string.profile_med_reminders_time)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                onSetHour(timeState.hour)
                                showTimePicker = false
                            },
                        ) {
                            Text(stringResource(R.string.med_reminder_ok))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showTimePicker = false }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    },
                ) {
                    TimePicker(state = timeState)
                }
            }
        }
    }
}
