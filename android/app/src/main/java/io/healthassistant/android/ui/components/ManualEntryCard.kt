package io.healthassistant.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.healthassistant.android.R
import io.healthassistant.shared.healthconnect.HcType

/** R5: generalized manual reading entry — pick a type, enter a value, and the
 *  next sync sends it to the server. Replaces the Heart-Rate-only stub. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ManualEntryCard(
    onAdd: (HcType, Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedType by remember { mutableStateOf(HcType.HEART_RATE) }
    var value by remember { mutableStateOf(TextFieldValue("")) }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.records_manual_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.records_manual_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.records_manual_chooser_help), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HcType.entries.forEach { type ->
                    FilterChip(
                        selected = type == selectedType,
                        onClick = { selectedType = type },
                        label = { Text(type.display) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(stringResource(R.string.records_manual_value_label)) },
                supportingText = { Text(selectedType.defaultUnit) },
                keyboardOptions =
                    androidx.compose.foundation.text
                        .KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    value.text
                        .trim()
                        .toDoubleOrNull()
                        ?.let { v ->
                            onAdd(selectedType, v)
                            value = TextFieldValue("")
                        }
                },
                enabled = value.text.trim().toDoubleOrNull() != null,
            ) { Text(stringResource(R.string.records_manual_add)) }
        }
    }
}
