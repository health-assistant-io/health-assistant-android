package io.healthassistant.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import io.healthassistant.android.R
import io.healthassistant.android.alerts.AlertPhrases
import io.healthassistant.android.ui.components.formatValue
import io.healthassistant.android.ui.theme.spacing
import io.healthassistant.shared.alerts.AlertOp
import io.healthassistant.shared.alerts.AlertRule

/**
 * M5 — the alert rules list + the plain-language rule builder ("Alert me
 * when Heart rate is above 120 for 5 minutes"; op/threshold jargon stays
 * internal). Pure state + lambdas; the owning [AlertRulesRoute] wires the
 * view-model. `op`/`threshold` never surface: the builder speaks
 * "above/below/outside a value, for a duration".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertRulesScreen(
    state: AlertRulesUiState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onToggle: (id: String, enabled: Boolean) -> Unit,
    onRequestDelete: (rule: AlertRule) -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onDismissEditor: () -> Unit,
    onPickBiomarker: (option: AlertBiomarkerOption) -> Unit,
    onShowBiomarkerPicker: () -> Unit,
    onSetOp: (op: AlertOp) -> Unit,
    onSetThreshold: (text: String) -> Unit,
    onSetRangeLow: (text: String) -> Unit,
    onSetRangeHigh: (text: String) -> Unit,
    onSetWindowMinutes: (minutes: Long) -> Unit,
    onSave: () -> Unit,
    canSave: Boolean,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.alerts_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.editor == null) {
                ExtendedFloatingActionButton(
                    onClick = onAdd,
                    modifier = Modifier.testTag("alerts_add_fab"),
                    icon = { Icon(Icons.Outlined.NotificationsActive, contentDescription = null) },
                    text = { Text(stringResource(R.string.alerts_add)) },
                )
            }
        },
    ) { padding ->
        if (state.rules.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(MaterialTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
            ) {
                Text(
                    stringResource(R.string.alerts_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = MaterialTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
                contentPadding = PaddingValues(vertical = MaterialTheme.spacing.sm),
            ) {
                items(state.rules, key = { it.id }) { rule ->
                    RuleCard(
                        rule = rule,
                        name = rule.biomarkerName ?: state.options.firstOrNull { it.code == rule.biomarkerCode }?.name,
                        onToggle = onToggle,
                        onRequestDelete = onRequestDelete,
                    )
                }
            }
        }
    }

    state.deleting?.let { rule ->
        AlertDialog(
            onDismissRequest = onCancelDelete,
            title = { Text(stringResource(R.string.alerts_delete_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.alerts_delete_confirm_body,
                        rule.biomarkerName ?: rule.biomarkerCode,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmDelete) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = {
                TextButton(onClick = onCancelDelete) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    state.editor?.let { editor ->
        RuleEditorSheet(
            editor = editor,
            options = state.options,
            onDismiss = onDismissEditor,
            onPickBiomarker = onPickBiomarker,
            onShowBiomarkerPicker = onShowBiomarkerPicker,
            onSetOp = onSetOp,
            onSetThreshold = onSetThreshold,
            onSetRangeLow = onSetRangeLow,
            onSetRangeHigh = onSetRangeHigh,
            onSetWindowMinutes = onSetWindowMinutes,
            onSave = onSave,
            canSave = canSave,
        )
    }
}

/** The rule-builder sheet: picker step first, then the plain-language form. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RuleEditorSheet(
    editor: AlertEditorState,
    options: List<AlertBiomarkerOption>,
    onDismiss: () -> Unit,
    onPickBiomarker: (option: AlertBiomarkerOption) -> Unit,
    onShowBiomarkerPicker: () -> Unit,
    onSetOp: (op: AlertOp) -> Unit,
    onSetThreshold: (text: String) -> Unit,
    onSetRangeLow: (text: String) -> Unit,
    onSetRangeHigh: (text: String) -> Unit,
    onSetWindowMinutes: (minutes: Long) -> Unit,
    onSave: () -> Unit,
    canSave: Boolean,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        if (editor.pickingBiomarker || editor.code == null) {
            BiomarkerPicker(
                options = options,
                onPick = onPickBiomarker,
            )
        } else {
            AlertRuleEditorForm(
                editor = editor,
                onDismiss = onDismiss,
                onShowBiomarkerPicker = onShowBiomarkerPicker,
                onSetOp = onSetOp,
                onSetThreshold = onSetThreshold,
                onSetRangeLow = onSetRangeLow,
                onSetRangeHigh = onSetRangeHigh,
                onSetWindowMinutes = onSetWindowMinutes,
                onSave = onSave,
                canSave = canSave,
            )
        }
    }
}

@Composable
private fun RuleCard(
    rule: AlertRule,
    name: String?,
    onToggle: (id: String, enabled: Boolean) -> Unit,
    onRequestDelete: (rule: AlertRule) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = MaterialTheme.spacing.md, vertical = MaterialTheme.spacing.sm + MaterialTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    alertRuleSentence(rule, name) ?: rule.biomarkerCode,
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (!rule.enabled) {
                    Text(
                        stringResource(R.string.alerts_paused),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            Switch(
                checked = rule.enabled,
                onCheckedChange = { onToggle(rule.id, it) },
                modifier = Modifier.testTag("alert_toggle_${rule.id}"),
            )
            IconButton(onClick = { onRequestDelete(rule) }) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.action_remove),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@Composable
internal fun AlertRuleEditorForm(
    editor: AlertEditorState,
    onDismiss: () -> Unit,
    onShowBiomarkerPicker: () -> Unit,
    onSetOp: (op: AlertOp) -> Unit,
    onSetThreshold: (text: String) -> Unit,
    onSetRangeLow: (text: String) -> Unit,
    onSetRangeHigh: (text: String) -> Unit,
    onSetWindowMinutes: (minutes: Long) -> Unit,
    onSave: () -> Unit,
    canSave: Boolean,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MaterialTheme.spacing.lg)
            .padding(bottom = MaterialTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
    ) {
        Text(
            stringResource(
                if (editor.editingId == null) R.string.alerts_editor_new else R.string.alerts_editor_edit,
            ),
            style = MaterialTheme.typography.titleLarge,
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(MaterialTheme.spacing.md)) {
                Text(
                    editorSentence(editor),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    editor.name ?: editor.code ?: "",
                    style = MaterialTheme.typography.titleSmall,
                )
                editor.unit?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        stringResource(R.string.alerts_unit_label, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            TextButton(onClick = onShowBiomarkerPicker) {
                Text(stringResource(R.string.alerts_change_biomarker))
            }
        }
        Text(
            stringResource(R.string.alerts_field_direction),
            style = MaterialTheme.typography.titleSmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
            DirectionChip(AlertOp.GT, R.string.alerts_dir_above, editor.op, onSetOp)
            DirectionChip(AlertOp.LT, R.string.alerts_dir_below, editor.op, onSetOp)
            DirectionChip(AlertOp.GE, R.string.alerts_dir_at_or_above, editor.op, onSetOp)
            DirectionChip(AlertOp.LE, R.string.alerts_dir_at_or_below, editor.op, onSetOp)
            DirectionChip(AlertOp.OUT_OF_RANGE, R.string.alerts_dir_outside, editor.op, onSetOp)
        }
        if (editor.op == AlertOp.OUT_OF_RANGE) {
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
                OutlinedTextField(
                    value = editor.rangeLowText,
                    onValueChange = onSetRangeLow,
                    label = { Text(stringResource(R.string.alerts_range_low)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = editor.rangeHighText,
                    onValueChange = onSetRangeHigh,
                    label = { Text(stringResource(R.string.alerts_range_high)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            OutlinedTextField(
                value = editor.thresholdText,
                onValueChange = onSetThreshold,
                label = {
                    Text(
                        editor.unit?.takeIf { it.isNotBlank() }?.let {
                            stringResource(R.string.alerts_value_with_unit, it)
                        } ?: stringResource(R.string.alerts_value),
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            stringResource(R.string.alerts_field_duration),
            style = MaterialTheme.typography.titleSmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
            WINDOW_PRESETS.forEach { (minutes, labelRes) ->
                FilterChip(
                    selected = editor.windowMinutes == minutes,
                    onClick = { onSetWindowMinutes(minutes) },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.action_cancel)) }
            Button(
                onClick = onSave,
                enabled = canSave,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.alerts_save))
            }
        }
    }
}

@Composable
private fun DirectionChip(
    op: AlertOp,
    labelRes: Int,
    selectedOp: AlertOp,
    onSetOp: (AlertOp) -> Unit,
) {
    FilterChip(
        selected = op == selectedOp,
        onClick = { onSetOp(op) },
        label = { Text(stringResource(labelRes)) },
    )
}

@Composable
internal fun BiomarkerPicker(
    options: List<AlertBiomarkerOption>,
    onPick: (option: AlertBiomarkerOption) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    val filtered =
        remember(searchQuery, options) {
            val q = searchQuery.trim().lowercase()
            if (q.isEmpty()) options else options.filter { it.name.lowercase().contains(q) }
        }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.spacing.lg)
            .padding(bottom = MaterialTheme.spacing.lg),
    ) {
        Text(
            stringResource(R.string.alerts_pick_biomarker),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(MaterialTheme.spacing.md))
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text(stringResource(R.string.alerts_search_biomarkers)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(MaterialTheme.spacing.sm))
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            items(filtered, key = { it.code }) { option ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(option) }
                        .padding(vertical = MaterialTheme.spacing.sm + MaterialTheme.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(option.name, style = MaterialTheme.typography.bodyLarge)
                        option.unit?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                stringResource(R.string.alerts_unit_label, it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The plain-language sentence a saved rule renders: "Alert me when Heart
 *  rate is above 120 for 5 minutes". Null when the rule lost its bounds
 *  (decode already drops those — belt and braces). */
@Composable
internal fun alertRuleSentence(
    rule: AlertRule,
    fallbackName: String?,
): String? {
    val name = rule.biomarkerName ?: fallbackName ?: rule.biomarkerCode
    val condition = AlertPhrases.condition(rule, ::formatValue) ?: return null
    val duration = AlertPhrases.duration(rule.timeWindowSec)
    return stringResource(
        R.string.alerts_sentence,
        name,
        stringResource(condition.res, *condition.args.toTypedArray()),
        duration?.let { stringResource(it.res, *it.args.toTypedArray()) } ?: "",
    ).trim()
}

/** The builder's live preview — same sentence, from the raw editor text so it
 *  renders before the value is parseable. */
@Composable
internal fun editorSentence(editor: AlertEditorState): String {
    val name = editor.name ?: editor.code ?: stringResource(R.string.alerts_pick_biomarker)
    val valueText = editor.thresholdText.ifBlank { stringResource(R.string.alerts_value_pending) }
    val condition =
        when (editor.op) {
            AlertOp.OUT_OF_RANGE ->
                stringResource(
                    R.string.alerts_phrase_outside,
                    editor.rangeLowText.ifBlank { stringResource(R.string.alerts_value_pending) },
                    editor.rangeHighText.ifBlank { stringResource(R.string.alerts_value_pending) },
                )
            else -> stringResource(directionPhraseRes(editor.op), valueText)
        }
    val duration =
        AlertPhrases
            .duration(editor.windowMinutes * SECONDS_PER_MINUTE)
            ?.let { stringResource(it.res, *it.args.toTypedArray()) }
            ?: ""
    return stringResource(R.string.alerts_sentence, name, condition, duration).trim()
}

@Composable
private fun directionPhraseRes(op: AlertOp): Int =
    when (op) {
        AlertOp.GT -> R.string.alerts_phrase_above
        AlertOp.LT -> R.string.alerts_phrase_below
        AlertOp.GE -> R.string.alerts_phrase_at_or_above
        AlertOp.LE -> R.string.alerts_phrase_at_or_below
        AlertOp.OUT_OF_RANGE -> R.string.alerts_phrase_outside
    }

private val WINDOW_PRESETS =
    listOf(
        0L to R.string.alerts_window_now,
        5L to R.string.alerts_window_5m,
        15L to R.string.alerts_window_15m,
        30L to R.string.alerts_window_30m,
        60L to R.string.alerts_window_1h,
    )

private const val SECONDS_PER_MINUTE = 60L
