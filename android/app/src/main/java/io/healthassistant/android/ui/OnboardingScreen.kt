package io.healthassistant.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.healthassistant.android.R
import io.healthassistant.android.settings.UiMode

/**
 * R7 restyled onboarding (Phase D migration: pure state + lambdas). Big
 * "Scan QR" primary, paste-code secondary, manual entry behind "Advanced".
 * The [OnboardingViewModel] owns the form state + validation (all three tiers
 * reuse the unchanged pure-Kotlin `Onboarding` parsers); the host launches
 * the QR scanner and owns the connect/probe flow. K.4 adds the leading
 * Simple/Advanced mode pick. Reassurance copy keeps the value prop front and
 * center.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    connecting: Boolean = false,
    serverError: String? = null,
    onScan: () -> Unit,
    onCodeChange: (String) -> Unit,
    onSubmitCode: () -> Unit,
    onToggleManual: () -> Unit,
    onManualBaseChange: (String) -> Unit,
    onManualIdChange: (String) -> Unit,
    onManualSecretChange: (String) -> Unit,
    onSubmitManual: () -> Unit,
    onModeChange: (UiMode) -> Unit = {},
) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.onboarding_title)) }) }) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .padding(16.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.onboarding_heading), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.onboarding_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            AppModeSection(
                mode = state.mode,
                onSelect = onModeChange,
            )

            // Primary: big Scan QR.
            Button(
                onClick = onScan,
                enabled = !connecting,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    if (connecting) stringResource(R.string.onboarding_connecting) else stringResource(R.string.onboarding_scan),
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HorizontalDivider(Modifier.weight(1f))
                Text(
                    stringResource(R.string.onboarding_or),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                HorizontalDivider(Modifier.weight(1f))
            }

            // Secondary: paste the connection code.
            OutlinedTextField(
                value = state.code,
                onValueChange = onCodeChange,
                label = { Text(stringResource(R.string.onboarding_connection_code_label)) },
                placeholder = { Text(stringResource(R.string.onboarding_connection_code_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 1,
                maxLines = 3,
            )
            Button(
                onClick = onSubmitCode,
                enabled = !connecting && state.code.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.onboarding_connect)) }

            state.error?.let { err ->
                Text(
                    text =
                        when (err) {
                            OnboardingError.InvalidCode -> stringResource(R.string.onboarding_invalid_code)
                            OnboardingError.ManualInvalid -> stringResource(R.string.onboarding_manual_invalid)
                            is OnboardingError.ScanFailed -> stringResource(R.string.onboarding_scan_failed, err.detail)
                        },
                    color = MaterialTheme.colorScheme.error,
                )
            }
            serverError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            // Advanced: manual entry.
            TextButton(onClick = onToggleManual, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    if (state.showManual) {
                        stringResource(
                            R.string.onboarding_hide_manual,
                        )
                    } else {
                        stringResource(R.string.onboarding_show_manual)
                    },
                )
            }
            AnimatedVisibility(state.showManual) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        state.manualBase,
                        onManualBaseChange,
                        label = { Text(stringResource(R.string.onboarding_manual_server_url)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    OutlinedTextField(
                        state.manualId,
                        onManualIdChange,
                        label = { Text(stringResource(R.string.onboarding_manual_instance_id)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        state.manualSecret,
                        onManualSecretChange,
                        label = { Text(stringResource(R.string.onboarding_manual_api_secret)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    OutlinedButton(
                        onClick = onSubmitManual,
                        enabled = !connecting,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.onboarding_manual_connect)) }
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.onboarding_reassurance),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    stringResource(R.string.onboarding_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}
