package io.healthassistant.android.ui

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import io.healthassistant.android.R
import io.healthassistant.android.data.AppLockManager
import org.koin.compose.koinInject

/**
 * Phase B.4 app-lock — the full-screen gate shown while [AppLockManager.isLocked]
 * is true. Owns the auth UX:
 *
 * - **BiometricPrompt** (face/fingerprint) auto-launches when the device has
 *   a biometric enrolled. On success → [AppLockManager.markUnlocked].
 * - **4-digit PIN pad** is the always-available fallback. Used when biometrics
 *   fail, aren't enrolled, or the user taps "Use PIN". On 4-digit completion
 *   the PIN is verified; on match → markUnlocked, on mismatch → shake + clear.
 * - The user can swap between the two with a button ("Use biometric" / "Use PIN").
 *
 * The host (MainActivity) renders this *instead of* AppRoot while locked, so
 * the navigation state isn't observable behind the lock — important for the
 * "switch tabs and see behind the lock" failure mode.
 *
 * Pure state + lambdas; the stateful entry point is [AppLockRoute].
 */
@Composable
fun AppLockScreen(
    showBiometricByDefault: Boolean,
    onBiometricPrompt: () -> Unit,
    onPinSubmit: (String) -> Boolean,
    onSwitchToPin: () -> Unit,
    onSwitchToBiometric: (() -> Unit)?,
    showPin: Boolean,
    errorMessage: String?,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(56.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.app_lock_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.app_lock_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            if (showPin) {
                PinPad(onSubmit = onPinSubmit)
            } else {
                OutlinedButton(
                    onClick = onBiometricPrompt,
                    modifier = Modifier.fillMaxWidth(0.7f).height(56.dp),
                ) {
                    Icon(
                        Icons.Filled.Fingerprint,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.app_lock_use_biometric))
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                errorMessage?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (showPin && onSwitchToBiometric != null) {
                    OutlinedButton(onClick = onSwitchToBiometric) {
                        Text(stringResource(R.string.app_lock_switch_to_biometric))
                    }
                } else if (!showPin) {
                    OutlinedButton(onClick = onSwitchToPin) {
                        Text(stringResource(R.string.app_lock_switch_to_pin))
                    }
                }
            }
        }
    }
}

/** 4-digit numeric PIN entry. Shows dots as the user types, fires [onSubmit]
 *  at 4 chars. [onSubmit] returns true on match (the route unlocks + clears
 *  the buffer) or false (route keeps the buffer + shows the error). */
@Composable
private fun PinPad(onSubmit: (String) -> Boolean) {
    var buffer by remember { mutableStateOf("") }
    val onDigit: (Char) -> Unit = { ch ->
        if (buffer.length < PIN_LEN) {
            buffer += ch
            if (buffer.length == PIN_LEN) {
                val ok = onSubmit(buffer)
                if (!ok) buffer = ""
            }
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(PIN_LEN) { idx ->
                val filled = idx < buffer.length
                Box(
                    Modifier
                        .size(16.dp)
                        .background(
                            if (filled) MaterialTheme.colorScheme.primary else Color.Transparent,
                            CircleShape,
                        ).then(
                            if (!filled) {
                                Modifier
                                    .background(MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            } else {
                                Modifier
                            },
                        ),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        // Numeric keypad — three rows of 1-9, then a row with empty + 0 + backspace.
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("123", "456", "789").forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { ch -> KeyCap(ch.toString()) { onDigit(ch) } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(56.dp))
                KeyCap("0") { onDigit('0') }
                androidx.compose.material3.IconButton(onClick = { if (buffer.isNotEmpty()) buffer = buffer.dropLast(1) }) {
                    Icon(Icons.Filled.Backspace, contentDescription = stringResource(R.string.app_lock_backspace))
                }
            }
        }
    }
}

@Composable
private fun KeyCap(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(56.dp)
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.headlineMedium)
    }
}

private const val PIN_LEN = 4

/**
 * Stateful entry: drives the BiometricPrompt lifecycle, decides whether to
 * show the PIN pad vs the biometric button, and wires verifyPin +
 * markUnlocked through [AppLockManager].
 */
@Composable
fun AppLockRoute() {
    val context = LocalContext.current
    val manager: AppLockManager = koinInject()

    val biometricsAvailable by manager.biometricsAvailable.collectAsState()
    val showBiometricByDefault = biometricsAvailable
    var showPin by remember { mutableStateOf(!showBiometricByDefault) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Hoist the resource reads to composable scope so the BiometricPrompt
    // builder + the PIN callback never query resources via LocalContext
    // (Phase K lint: LocalContextGetResourceValueCall) and recompose on locale.
    val biometricTitle = stringResource(R.string.app_lock_biometric_title)
    val biometricSubtitle = stringResource(R.string.app_lock_biometric_subtitle)
    val biometricNegative = stringResource(R.string.app_lock_biometric_negative)
    val pinWrong = stringResource(R.string.app_lock_pin_wrong)

    // Refresh the biometric availability probe on every entry.
    LaunchedEffect(Unit) {
        val bm = BiometricManager.from(context)
        manager.refreshBiometricsAvailability(
            bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK),
        )
    }

    val showBiometricPrompt: () -> Unit = {
        val activity = context as? FragmentActivity
        if (activity == null) {
            errorMessage = "Biometric prompt unavailable."
            showPin = true
        } else {
            val executor =
                androidx.core.content.ContextCompat
                    .getMainExecutor(context)
            val callback =
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        manager.markUnlocked()
                    }

                    override fun onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence,
                    ) {
                        // User cancelled (errors 5, 10, 13) → fall back to PIN.
                        // Hardware unavailable (errors 6, 12) → fall back to PIN.
                        errorMessage = errString.toString()
                        showPin = true
                    }
                }
            val prompt = BiometricPrompt(activity, executor, callback)
            val info =
                BiometricPrompt.PromptInfo
                    .Builder()
                    .setTitle(biometricTitle)
                    .setSubtitle(biometricSubtitle)
                    .setNegativeButtonText(biometricNegative)
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                    .build()
            prompt.authenticate(info)
        }
    }

    // Auto-fire the biometric prompt when we land on this screen + biometrics
    // are available + the user hasn't switched to PIN yet.
    LaunchedEffect(showBiometricByDefault) {
        if (showBiometricByDefault && !showPin) showBiometricPrompt()
    }

    AppLockScreen(
        showBiometricByDefault = showBiometricByDefault,
        onBiometricPrompt = showBiometricPrompt,
        onPinSubmit = { pin ->
            val ok = manager.verifyPin(pin)
            if (ok) {
                manager.markUnlocked()
                errorMessage = null
            } else {
                errorMessage = pinWrong
            }
            ok
        },
        onSwitchToPin = {
            showPin = true
            errorMessage = null
        },
        onSwitchToBiometric =
            if (biometricsAvailable) {
                (
                    {
                        showPin = false
                        errorMessage = null
                        showBiometricPrompt()
                    }
                )
            } else {
                null
            },
        showPin = showPin,
        errorMessage = errorMessage,
    )
}
