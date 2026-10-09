package io.healthassistant.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.healthassistant.android.R
import io.healthassistant.shared.onboarding.ConnectionCredential

/** Lists saved connections (multiple patients) and lets the user switch the
 *  active one or remove one (Phase 8 multi-connection switcher). */
@Composable
fun ConnectionSwitcherDialog(
    connections: List<ConnectionCredential>,
    activeId: String?,
    onPick: (ConnectionCredential) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.connections_title)) },
        text = {
            Column {
                connections.forEach { c ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = c.integrationId == activeId, onClick = { onPick(c) })
                        Column(Modifier.weight(1f)) {
                            Text(c.baseUrl, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                c.integrationId,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        TextButton(onClick = { onRemove(c.integrationId) }) { Text(stringResource(R.string.action_remove)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}
