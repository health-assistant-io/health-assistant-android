package io.healthassistant.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.healthassistant.android.R
import io.healthassistant.android.ui.components.formatValue
import io.healthassistant.shared.data.BiomarkerOption

/** Dashboard editor: a catalog of every available biomarker card, each with an
 *  Add/Remove button, plus reorder arrows for the shown ones. Pure state. */
@Composable
fun DashboardEditDialog(
    options: List<BiomarkerOption>,
    shownCodes: Set<String>,
    order: List<String>,
    onToggleShown: (String, Boolean) -> Unit,
    onMove: (String, Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val shownList = shownCodes.toList()
    val shownIndex = shownList.withIndex().associate { (index, code) -> code to index }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_edit_dashboard)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(options, key = { it.id }) { option ->
                    val code = option.code ?: option.id
                    val shown = code in shownCodes
                    val index = shownIndex[code]
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(option.name, style = MaterialTheme.typography.bodyLarge)
                            val valueText = option.latestValueString ?: option.latestValue?.let(::formatValue)
                            Text(
                                if (valueText != null) {
                                    "$valueText ${(option.latestUnit ?: option.unit).orEmpty()}".trim()
                                } else {
                                    stringResource(R.string.insights_no_latest)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        if (shown) {
                            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                                IconButton(
                                    onClick = { onMove(code, -1) },
                                    enabled = index != null && index > 0,
                                ) {
                                    Icon(
                                        Icons.Outlined.ArrowUpward,
                                        contentDescription = stringResource(R.string.home_move_up),
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                                IconButton(
                                    onClick = { onMove(code, 1) },
                                    enabled = index != null && index < shownList.lastIndex,
                                ) {
                                    Icon(
                                        Icons.Outlined.ArrowDownward,
                                        contentDescription = stringResource(R.string.home_move_down),
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.size(8.dp))
                        if (shown) {
                            OutlinedButton(
                                onClick = { onToggleShown(code, false) },
                                modifier = Modifier.padding(start = 4.dp),
                            ) {
                                Text(stringResource(R.string.action_remove))
                            }
                        } else {
                            FilledTonalButton(
                                onClick = { onToggleShown(code, true) },
                                modifier = Modifier.padding(start = 4.dp),
                            ) {
                                Text(stringResource(R.string.action_add))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
        dismissButton = {
            TextButton(onClick = onReset) { Text(stringResource(R.string.home_reset_dashboard)) }
        },
    )
}
