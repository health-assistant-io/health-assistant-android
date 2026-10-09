package io.healthassistant.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import io.healthassistant.android.R
import io.healthassistant.android.ui.components.ChartSkeleton
import io.healthassistant.android.ui.components.OverviewChart
import io.healthassistant.android.ui.components.OverviewSeries
import io.healthassistant.android.ui.components.StaleChipSlot
import io.healthassistant.android.ui.components.biomarkerIcon
import io.healthassistant.android.ui.components.formatValue
import io.healthassistant.android.ui.components.overviewSeriesColor
import io.healthassistant.android.ui.components.relativeTime
import io.healthassistant.shared.data.ChartRange
import io.healthassistant.shared.data.NormalizationStrategy
import io.healthassistant.shared.data.cache.CacheMetaState
import io.healthassistant.shared.healthconnect.HcType

/**
 * The wellness overview (Records › Biomarkers › Overview, monitoring viz M6):
 * up to three biomarkers the user picks, each series normalized per-series
 * onto one now-anchored timeline so correlations read as shape, not
 * magnitude. Range chips reuse the biomarker detail's; the staleness row
 * reflects the observations domain. Pure state + lambdas.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun OverviewScreen(
    state: OverviewUiState,
    staleMeta: CacheMetaState? = null,
    online: Boolean = true,
    onRetry: () -> Unit = {},
    onToggle: (String) -> Unit,
    onSelectRange: (ChartRange) -> Unit,
    onSelectStrategy: (NormalizationStrategy) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.overview_title)) },
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
    ) { padding ->
        Column(
            modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            StaleChipSlot(meta = staleMeta, online = online, onRetry = onRetry)
            FlowRow(
                modifier = Modifier.fillMaxWidth().testTag("overview_selection"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.series.forEach { picked ->
                    FilterChip(
                        selected = true,
                        onClick = { onToggle(picked.code) },
                        label = { Text(picked.name) },
                        modifier = Modifier.testTag("overview_selected_chip"),
                        trailingIcon = {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.overview_remove, picked.name),
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                }
                if (state.selection.size < MAX_OVERVIEW_SERIES) {
                    FilterChip(
                        selected = false,
                        onClick = { showPicker = true },
                        label = { Text(stringResource(R.string.overview_add_biomarker)) },
                        leadingIcon = {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                    )
                }
            }
            Text(
                stringResource(R.string.overview_pick_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChartRange.entries.forEach { range ->
                    FilterChip(
                        selected = range == state.range,
                        onClick = { onSelectRange(range) },
                        label = { Text(biomarkerRangeLabel(range)) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.overview_scale_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilterChip(
                    selected = state.strategy == NormalizationStrategy.MIN_MAX,
                    onClick = { onSelectStrategy(NormalizationStrategy.MIN_MAX) },
                    label = { Text(stringResource(R.string.overview_scale_min_max)) },
                )
                FilterChip(
                    selected = state.strategy == NormalizationStrategy.Z_SCORE,
                    onClick = { onSelectStrategy(NormalizationStrategy.Z_SCORE) },
                    label = { Text(stringResource(R.string.overview_scale_z_score)) },
                )
            }

            state.referenceRange?.let { range ->
                val unit =
                    state.series
                        .firstOrNull()
                        ?.unit
                        .orEmpty()
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(
                        R.string.insights_reference_range,
                        range.low?.let(::formatValue) ?: "—",
                        range.high?.let(::formatValue) ?: "—",
                        unit,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.testTag("overview_reference_range"),
                )
            }

            Spacer(Modifier.height(16.dp))
            when {
                state.loading -> ChartSkeleton(Modifier.fillMaxWidth())

                state.selection.isEmpty() ->
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            stringResource(R.string.overview_empty_selection),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }

                else -> {
                    val seriesNames = state.series.map { it.name }.joinToString(", ")
                    OverviewChart(
                        series =
                            state.series.map { s ->
                                OverviewSeries(label = s.name, unit = s.unit, points = s.points, scale = s.scale)
                            },
                        referenceBand = state.normalizedBand,
                        windowMs = state.range.days * MILLIS_PER_DAY,
                        a11yLabel =
                            stringResource(
                                R.string.overview_a11y_summary,
                                seriesNames,
                                biomarkerRangeLabel(state.range).replaceFirstChar { it.lowercase() },
                            ),
                        modifier = Modifier.fillMaxWidth().testTag("overview_chart"),
                    )
                    Spacer(Modifier.height(8.dp))
                    state.series.forEachIndexed { index, s ->
                        OverviewLegendRow(
                            name = s.name,
                            unit = s.unit,
                            hasReadings = s.points.isNotEmpty(),
                            swatch = { Box(Modifier.size(12.dp).background(overviewSeriesColor(index), CircleShape)) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showPicker) {
        OverviewPickerSheet(
            options = state.options,
            selection = state.selection,
            onPick = { code ->
                onToggle(code)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

/** One legend row under the chart: color swatch + biomarker name + unit (or
 *  the empty-range hint when the window holds no readings for it). */
@Composable
private fun OverviewLegendRow(
    name: String,
    unit: String?,
    hasReadings: Boolean,
    swatch: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().testTag("overview_legend_row").padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        swatch()
        Spacer(Modifier.width(10.dp))
        Text(name, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        if (hasReadings) {
            unit?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text(
                stringResource(R.string.insights_empty_range),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** The biomarker picker sheet — the same searchable-list pattern as the
 *  Records "Add reading" sheet, over the cached catalog options. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OverviewPickerSheet(
    options: List<OverviewPickerOption>,
    selection: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered =
        remember(options, query) {
            val q = query.trim().lowercase()
            if (q.isEmpty()) {
                options
            } else {
                options.filter { it.name.lowercase().contains(q) || it.code.lowercase().contains(q) }
            }
        }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.overview_add_biomarker), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.overview_picker_search_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().testTag("overview_picker_search"),
            )
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
                items(filtered, key = { it.code }) { option ->
                    val picked = selection.contains(option.code)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .testTag("overview_picker_row")
                            .clickable(enabled = picked || selection.size < MAX_OVERVIEW_SERIES) { onPick(option.code) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = biomarkerIcon(HcType.byCode(option.code)),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(option.name, style = MaterialTheme.typography.bodyLarge)
                            OverviewPickerMeta(option)
                        }
                        if (picked) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.overview_remove, option.name),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun OverviewPickerMeta(option: OverviewPickerOption) {
    val parts =
        listOfNotNull(
            option.unit?.takeIf { it.isNotBlank() },
            option.latestTimestamp?.let { relativeTime(it) },
        )
    Text(
        parts.ifEmpty { listOf(stringResource(R.string.biomarkers_never_measured)) }.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
}

private const val MILLIS_PER_DAY = 86_400_000L
