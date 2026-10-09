package io.healthassistant.android.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.healthassistant.android.R
import io.healthassistant.android.ui.components.AnimatedValueText
import io.healthassistant.android.ui.components.DetailSkeleton
import io.healthassistant.android.ui.components.InsightsChart
import io.healthassistant.android.ui.components.RichText
import io.healthassistant.android.ui.components.StaleChipSlot
import io.healthassistant.android.ui.components.TimePoint
import io.healthassistant.android.ui.components.formatValue
import io.healthassistant.android.ui.components.relativeTimeMillis
import io.healthassistant.android.ui.theme.spacing
import io.healthassistant.shared.data.ChartRange
import io.healthassistant.shared.data.ObservationPoint
import io.healthassistant.shared.data.SeriesStats
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Loaded/loading/error snapshot for the biomarker graph. */
sealed interface InsightsUiState {
    data object Loading : InsightsUiState

    data class Loaded(
        val points: List<ObservationPoint>,
        val latestOverall: ObservationPoint? = null,
        val previousOverall: ObservationPoint? = null,
    ) : InsightsUiState

    data class Error(
        val message: String,
    ) : InsightsUiState
}

/**
 * The biomarker graph detail (Records › Biomarkers › tap). Title = the
 * biomarker's display name; range chips; reference range; the Vico line
 * chart; latest line; loading/error/empty states. Pure state + lambdas —
 * the selection arrives via the nav route (no dropdown).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BiomarkerDetailScreen(
    title: String,
    unit: String?,
    referenceRangeLabel: String?,
    info: String?,
    staleMeta: io.healthassistant.shared.data.cache.CacheMetaState? = null,
    online: Boolean = true,
    onStaleRetry: () -> Unit = {},
    state: InsightsUiState,
    selectedRange: ChartRange,
    onSelectRange: (ChartRange) -> Unit,
    onRetry: () -> Unit,
    onZoomSpanChanged: ((Long) -> Unit)? = null,
    onSetAlert: (() -> Unit)? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
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
            StaleChipSlot(meta = staleMeta, online = online, onRetry = onStaleRetry)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChartRange.entries.forEach { range ->
                    FilterChip(
                        selected = range == selectedRange,
                        onClick = { onSelectRange(range) },
                        label = { Text(biomarkerRangeLabel(range)) },
                    )
                }
            }

            referenceRangeLabel?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            Spacer(Modifier.height(24.dp))
            when (val s = state) {
                InsightsUiState.Loading -> DetailSkeleton(Modifier.fillMaxWidth())

                is InsightsUiState.Error ->
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(s.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = onRetry) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }

                is InsightsUiState.Loaded -> {
                    val plot =
                        s.points
                            .mapNotNull { p ->
                                val value = p.chartValue
                                val epoch = parseEpochMs(p.effectiveDatetime)
                                if (value == null || epoch == null) return@mapNotNull null
                                epoch to p
                            }.sortedBy { it.first }
                    val timeline = stateIntervals(s.points)
                    when {
                        timeline != null -> {
                            Text(
                                stringResource(R.string.sync_n_readings, s.points.count { it.chartValue == null }),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Spacer(Modifier.height(8.dp))
                            StateTimeline(timeline)
                        }

                        plot.isEmpty() -> {
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    stringResource(R.string.insights_empty_range),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                            // A manually-picked empty window (e.g. "Last week" when
                            // the newest reading is 8 days old) must still surface
                            // the latest state — show the newest reading regardless
                            // of the window.
                            s.latestOverall?.let { latest ->
                                LatestCard(
                                    latest = latest,
                                    previous = s.previousOverall,
                                    fallbackUnit = unit,
                                )
                            }
                        }

                        else -> {
                            val chartPoints = plot.map { TimePoint(it.first, it.second.chartValue!!.toFloat()) }
                            val referenceRange =
                                plot.last().second.range?.let { r ->
                                    val low = r.low
                                    val high = r.high
                                    if (low != null && high != null && high > low) low..high else null
                                }
                            Text(
                                stringResource(R.string.sync_n_readings, plot.size),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Spacer(Modifier.height(8.dp))
                            InsightsChart(
                                points = chartPoints,
                                referenceRange = referenceRange,
                                windowMs = selectedRange.days * MILLIS_PER_DAY,
                                unit = plot.last().second.normalizedUnit ?: unit,
                                onVisibleSpanChanged = onZoomSpanChanged,
                                name = title,
                                rangeLabel = biomarkerRangeLabel(selectedRange),
                                modifier = Modifier.fillMaxWidth().testTag("biomarker_chart"),
                            )
                            SeriesStats.from(plot.map { it.second })?.let { stats ->
                                Spacer(Modifier.height(MaterialTheme.spacing.md))
                                StatsRow(stats)
                            }
                            Spacer(Modifier.height(12.dp))
                            LatestCard(
                                latest = plot.last().second,
                                previous = s.previousOverall,
                                fallbackUnit = unit,
                            )
                            onSetAlert?.let {
                                Spacer(Modifier.height(MaterialTheme.spacing.md))
                                OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth()) {
                                    Icon(
                                        Icons.Outlined.NotificationsActive,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(MaterialTheme.spacing.sm))
                                    Text(stringResource(R.string.alerts_set_for_biomarker))
                                }
                            }
                        }
                    }
                }
            }
            info?.takeIf { it.isNotBlank() }?.let { markdown ->
                Spacer(Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.biomarker_about),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                RichText(
                    markdown,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
internal fun biomarkerRangeLabel(range: ChartRange): String =
    when (range) {
        ChartRange.ONE_WEEK -> stringResource(R.string.chart_range_1w)
        ChartRange.ONE_MONTH -> stringResource(R.string.chart_range_1m)
        ChartRange.THREE_MONTHS -> stringResource(R.string.chart_range_3m)
        ChartRange.ONE_YEAR -> stringResource(R.string.chart_range_1y)
    }

/** Min / Max / Avg / Last over the selected range, one cell per stat. */
@Composable
private fun StatsRow(stats: SeriesStats) {
    Row(
        Modifier.fillMaxWidth().testTag("biomarker_stats"),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
    ) {
        StatCell(stringResource(R.string.biomarker_stat_min), formatValue(stats.min), Modifier.weight(1f))
        StatCell(stringResource(R.string.biomarker_stat_max), formatValue(stats.max), Modifier.weight(1f))
        StatCell(stringResource(R.string.biomarker_stat_avg), formatValue(stats.avg), Modifier.weight(1f))
        StatCell(stringResource(R.string.biomarker_stat_last), formatValue(stats.last), Modifier.weight(1f))
    }
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** The latest record, promoted: big tinted value + unit, relative time, and a
 *  trend chip vs the previous reading (direction icon + % change; hidden when
 *  no previous reading exists — color stays neutral because "up" is not
 *  always good). */
@Composable
private fun LatestCard(
    latest: ObservationPoint,
    previous: ObservationPoint?,
    fallbackUnit: String?,
) {
    val value = latest.chartValue
    if (value == null) return
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.insights_latest_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    AnimatedValueText(
                        text = formatValue(value),
                        style = MaterialTheme.typography.headlineMedium,
                        color = interpretationTint(latest.interpretation),
                    )
                    val unitText = latest.normalizedUnit ?: fallbackUnit
                    unitText?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                }
                parseEpochMs(latest.effectiveDatetime)?.let { epoch ->
                    Text(
                        relativeTimeMillis(epoch),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            trendOf(value, previous?.chartValue)?.let { delta ->
                TrendChip(delta)
            }
        }
    }
}

/** Direction icon + % change vs the previous reading; the icon alone when the
 *  change reads as stable. */
@Composable
private fun TrendChip(delta: TrendDelta) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TrendIcon(delta.trend, Modifier.size(20.dp))
        if (delta.trend != Trend.FLAT) {
            Spacer(Modifier.width(4.dp))
            Text(
                "${formatValue(kotlin.math.abs(delta.percent))}%",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Newest-first timeline of state changes for a categorical biomarker —
 *  consecutive same-state readings collapse into one dated run. */
@Composable
private fun StateTimeline(intervals: List<StateInterval>) {
    val zone = remember { ZoneId.systemDefault() }
    val timeFormat = remember { DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault()) }
    Column(Modifier.fillMaxWidth().testTag("biomarker_state_timeline")) {
        Text(
            stringResource(R.string.biomarker_state_changes),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(MaterialTheme.spacing.sm))
        intervals
            .asReversed()
            .take(STATE_TIMELINE_LIMIT)
            .forEach { interval ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.xs),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        interval.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(MaterialTheme.spacing.sm))
                    Text(
                        stateIntervalTime(interval, timeFormat, zone),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
    }
}

private fun stateIntervalTime(
    interval: StateInterval,
    format: DateTimeFormatter,
    zone: ZoneId,
): String {
    val start = format.format(Instant.ofEpochMilli(interval.startEpochMs).atZone(zone))
    if (interval.endEpochMs <= interval.startEpochMs) return start
    val end = format.format(Instant.ofEpochMilli(interval.endEpochMs).atZone(zone))
    return "$start – $end"
}

/** FHIR interpretation code → color: high/critical-high/abnormal tint red,
 *  low tints blue-ish, normal/unknown stay default. */
@Composable
internal fun interpretationTint(code: String?): androidx.compose.ui.graphics.Color =
    when (code?.trim()?.uppercase()) {
        null, "", "N" -> MaterialTheme.colorScheme.onSurface
        "H", "HH", "A", "AA" -> MaterialTheme.colorScheme.error
        "L", "LL" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }

/** Best-effort ISO-8601 → epoch ms. Accepts full instants and date-only
 *  strings (exams often carry plain dates); null when unparseable. */
internal fun parseEpochMs(iso: String?): Long? {
    val raw = iso?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    runCatching { return Instant.parse(raw).toEpochMilli() }
    runCatching {
        return java.time.LocalDate
            .parse(raw.take(10))
            .atStartOfDay(java.time.ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
    }
    return null
}

/** One run of a categorical biomarker holding the same state value, from its
 *  first reading ([startEpochMs]) to its last ([endEpochMs]). */
internal data class StateInterval(
    val label: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
)

/** Pure derivation of the state-change timeline for a categorical biomarker
 *  (`value_type == "state"` / `value_string` present, no numeric value):
 *  consecutive readings with the same label collapse into one run (newest
 *  last). Null when the window is not a pure state series — any numeric point
 *  renders the chart instead — or when no state point carries a parseable
 *  time. */
internal fun stateIntervals(points: List<ObservationPoint>): List<StateInterval>? {
    if (points.any { it.chartValue != null }) return null
    val states =
        points
            .mapNotNull { p ->
                val label = p.valueString?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val epoch = parseEpochMs(p.effectiveDatetime) ?: return@mapNotNull null
                epoch to label
            }.sortedBy { it.first }
    if (states.isEmpty()) return null
    val intervals = mutableListOf<StateInterval>()
    for ((epoch, label) in states) {
        val last = intervals.lastOrNull()
        if (last != null && last.label.equals(label, ignoreCase = true)) {
            intervals[intervals.size - 1] = last.copy(endEpochMs = epoch)
        } else {
            intervals.add(StateInterval(label = label, startEpochMs = epoch, endEpochMs = epoch))
        }
    }
    return intervals
}

private const val MILLIS_PER_DAY = 86_400_000L
private const val STATE_TIMELINE_LIMIT = 50
