package io.healthassistant.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.healthassistant.android.R
import io.healthassistant.android.settings.UiTheme
import io.healthassistant.android.ui.theme.AuroraDarkColors
import io.healthassistant.android.ui.theme.AuroraLightColors
import io.healthassistant.android.ui.theme.HADarkColors
import io.healthassistant.android.ui.theme.HALightColors

/**
 * The Appearance pick (Profile): the app-wide color preset. A tap takes
 * effect instantly (the owner persists [UiTheme]; no restart). AMOLED and
 * high-contrast stay under Accessibility — they layer on top of any preset.
 */
@Composable
fun AppearanceSection(
    theme: UiTheme,
    onSelect: (UiTheme) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.appearance_title), style = MaterialTheme.typography.titleMedium)
            ThemeOptionRow(
                label = stringResource(R.string.theme_aurora),
                hint = stringResource(R.string.theme_aurora_hint),
                swatch = SwatchColors(AuroraLightColors.primary, AuroraLightColors.primaryContainer, AuroraDarkColors.primary),
                selected = theme == UiTheme.AURORA,
                onClick = { onSelect(UiTheme.AURORA) },
            )
            ThemeOptionRow(
                label = stringResource(R.string.theme_teal),
                hint = stringResource(R.string.theme_teal_hint),
                swatch = SwatchColors(HALightColors.primary, HALightColors.primaryContainer, HADarkColors.primary),
                selected = theme == UiTheme.TEAL,
                onClick = { onSelect(UiTheme.TEAL) },
            )
            ThemeOptionRow(
                label = stringResource(R.string.theme_material_you),
                hint = stringResource(R.string.theme_material_you_hint),
                swatch = SwatchColors(Color(0xFF5B69E0), Color(0xFFD8DEFF), Color(0xFFBEC2FF)),
                selected = theme == UiTheme.MATERIAL_YOU,
                onClick = { onSelect(UiTheme.MATERIAL_YOU) },
            )
        }
    }
}

private data class SwatchColors(
    val primary: Color,
    val container: Color,
    val dark: Color,
)

@Composable
private fun ThemeOptionRow(
    label: String,
    hint: String,
    swatch: SwatchColors,
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
        Canvas(Modifier.size(width = 40.dp, height = 24.dp)) {
            drawCircle(swatch.dark, radius = 11.dp.toPx(), center = center.copy(x = 10.dp.toPx()))
            drawCircle(swatch.primary, radius = 11.dp.toPx(), center = center.copy(x = 20.dp.toPx()))
            drawCircle(swatch.container, radius = 6.dp.toPx(), center = center.copy(x = 20.dp.toPx()))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RadioButton(selected = selected, onClick = null)
    }
}
