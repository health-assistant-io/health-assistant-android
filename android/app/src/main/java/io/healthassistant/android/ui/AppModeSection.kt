package io.healthassistant.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.healthassistant.android.R
import io.healthassistant.android.settings.UiMode

/**
 * The plain-language Simple / Advanced pick (K.4): one app, two levels of
 * disclosure. Shared by the first-run wizard and the Profile hub — a tap
 * takes effect instantly (the owner persists [UiMode]; no restart).
 */
@Composable
fun AppModeSection(
    mode: UiMode,
    onSelect: (UiMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.mode_title), style = MaterialTheme.typography.titleMedium)
            ModeOptionRow(
                label = stringResource(R.string.mode_simple),
                hint = stringResource(R.string.mode_simple_hint),
                selected = mode == UiMode.SIMPLE,
                onClick = { onSelect(UiMode.SIMPLE) },
            )
            ModeOptionRow(
                label = stringResource(R.string.mode_advanced),
                hint = stringResource(R.string.mode_advanced_hint),
                selected = mode == UiMode.ADVANCED,
                onClick = { onSelect(UiMode.ADVANCED) },
            )
        }
    }
}

@Composable
private fun ModeOptionRow(
    label: String,
    hint: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
