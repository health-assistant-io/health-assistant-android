package io.healthassistant.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LockClock
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.healthassistant.android.R
import io.healthassistant.android.data.AppLockManager
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Phase B.4 — the "App lock" section in Profile › Privacy.
 *
 * UX:
 * - Toggle ON → opens the PIN-setup dialog (a 4-digit PIN is the required
 *   fallback for when biometrics fail or aren't enrolled). On confirm →
 *   [AppLockManager.enable] persists the hashed PIN + flips enabled on,
 *   and the manager immediately locks so the user sees the gate in action.
 * - Toggle OFF → confirmation dialog → [AppLockManager.disable] clears the
 *   PIN hash + the enabled flag.
 * - "Lock now" button — visible only when enabled — forces a lock so the
 *   user sees the lock screen without leaving the app.
 * - "Change PIN" — visible only when enabled — opens the PIN-setup dialog
 *   with a different title; on confirm calls [AppLockManager.changePin].
 *
 * The biometric-vs-PIN choice at unlock time is handled by [AppLockRoute];
 * this section only configures the lock.
 */
@Composable
fun AppLockSection() {
    val manager: AppLockManager = koinInject()
    val scope = rememberCoroutineScope()
    val enabled by manager.enabled.collectAsState()
    var showSetupDialog by remember { mutableStateOf(false) }
    var showDisableDialog by remember { mutableStateOf(false) }
    var setupMode by remember { mutableStateOf(PinSetupMode.ENABLE) }

    Column {
        Text(
            stringResource(R.string.app_lock_section_blurb),
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(12.dp))

        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.app_lock_toggle),
                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = enabled,
                onCheckedChange = { turnOn ->
                    if (turnOn) {
                        setupMode = PinSetupMode.ENABLE
                        showSetupDialog = true
                    } else {
                        showDisableDialog = true
                    }
                },
            )
        }

        if (enabled) {
            AppLockRowAction(
                icon = Icons.Outlined.LockClock,
                label = stringResource(R.string.app_lock_lock_now),
                onClick = { manager.lockNow() },
            )
            AppLockRowAction(
                icon = Icons.Outlined.Password,
                label = stringResource(R.string.app_lock_change_pin),
                onClick = {
                    setupMode = PinSetupMode.CHANGE
                    showSetupDialog = true
                },
            )
        }
    }

    if (showSetupDialog) {
        PinSetupDialog(
            mode = setupMode,
            onConfirm = { pin ->
                scope.launch {
                    when (setupMode) {
                        PinSetupMode.ENABLE -> manager.enable(pin)
                        PinSetupMode.CHANGE -> manager.changePin(pin)
                    }
                    showSetupDialog = false
                }
            },
            onDismiss = { showSetupDialog = false },
        )
    }
    if (showDisableDialog) {
        AlertDialog(
            onDismissRequest = { showDisableDialog = false },
            title = { Text(stringResource(R.string.app_lock_disable_title)) },
            text = { Text(stringResource(R.string.app_lock_disable_body)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { manager.disable() }
                    showDisableDialog = false
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDisableDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

private enum class PinSetupMode { ENABLE, CHANGE }

@Composable
private fun AppLockRowAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        androidx.compose.material3.Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(12.dp))
        Text(label, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

/** PIN entry dialog. Two phases: ENTER (first entry) + CONFIRM (re-entry).
 *  Both must match before [onConfirm] fires. Validates 4 digits. */
@Composable
private fun PinSetupDialog(
    mode: PinSetupMode,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var phase by remember { mutableStateOf(Phase.ENTER) }
    var firstEntry by remember { mutableStateOf("") }
    var current by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val title =
        when (mode) {
            PinSetupMode.ENABLE -> stringResource(R.string.app_lock_setup_title)
            PinSetupMode.CHANGE -> stringResource(R.string.app_lock_change_title)
        }
    val label =
        when (phase) {
            Phase.ENTER -> stringResource(R.string.app_lock_pin_enter)
            Phase.CONFIRM -> stringResource(R.string.app_lock_pin_confirm)
        }

    // Auto-advance: when the field hits 4 digits, validate + step.
    LaunchedEffect(current) {
        if (current.length == 4) {
            if (phase == Phase.ENTER) {
                firstEntry = current
                current = ""
                phase = Phase.CONFIRM
                error = null
            } else {
                if (current == firstEntry) {
                    onConfirm(current)
                } else {
                    error = "PINs don't match. Try again."
                    firstEntry = ""
                    current = ""
                    phase = Phase.ENTER
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(label, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = current,
                    onValueChange = { new ->
                        // Numeric only, max 4 chars.
                        val digits = new.filter { it.isDigit() }.take(4)
                        current = digits
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                error?.let {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        it,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private enum class Phase { ENTER, CONFIRM }
