package io.healthassistant.android.ui.components

import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.insets
import com.patrykandpatrick.vico.core.cartesian.Zoom
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.decoration.HorizontalBox
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.core.cartesian.marker.LineCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.core.common.Fill
import com.patrykandpatrick.vico.core.common.component.ShapeComponent
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import io.healthassistant.android.R
import io.healthassistant.android.ui.theme.LocalReduceMotion
import io.healthassistant.shared.data.ChartSummary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** One record on the chart: the effective instant + the plotted value. */
data class TimePoint(
    val timeMs: Long,
    val value: Float,
)

private val ReferenceRangeKey = ExtraStore.Key<ClosedFloatingPointRange<Double>>()

/** X origin for the chart: the selected window's start ("now − window"), or
 *  the first record when no window is known. Never after the first record. */
internal fun chartWindowStartMs(
    nowMs: Long,
    windowMs: Long?,
    firstPointMs: Long,
): Long = min(windowMs?.let { nowMs - it } ?: firstPointMs, firstPointMs)

/** X-axis tick ladder: 1m, 5m, 15m, 1h, 6h, 12h, 1d, 1w, 1mo, 3mo, 1y.
 *  Picks the smallest tick ≥ span/4 so ~4 labels show at ANY zoom level. */
internal fun pickStepSeconds(spanSeconds: Double): Double {
    val ladder =
        longArrayOf(
            60,
            5 * 60,
            15 * 60,
            3_600,
            6 * 3_600,
            12 * 3_600,
            86_400,
            7 * 86_400L,
            30L * 86_400,
            90L * 86_400,
            365L * 86_400,
        )
    val target = spanSeconds / 4
    return ladder.firstOrNull { it >= target }?.toDouble() ?: ladder.last().toDouble()
}

/** Old signature kept for compatibility with existing tests: ~4 labels. */
internal fun labelStepSeconds(
    domainSeconds: Double,
    maxLabels: Int = 4,
): Double = max(1.0, domainSeconds / maxLabels)

/**
 * Native line chart (Vico) for a biomarker time series, anchored at NOW: with
 * [windowMs] (the selected "last X time") the x-domain spans
 * `now − window … now` — records sit where they happened and stale gaps read
 * as gaps, per the "show the last X time, not the whole history" requirement.
 * Every record gets a dot; a known reference range draws a translucent band.
 *
 * Pinch zoom expands time: the zoom ceiling is high ([MAX_ZOOM]×, so a month
 * domain can be zoomed to ~2 h), axis ticks + time formats adapt to the
 * visible span, and [onVisibleSpanChanged] lets the owner fetch RAW points for
 * the visible window — dense telemetry (many records per day) resolves into
 * individual intraday dots instead of a stacked column.
 */
@Composable
fun InsightsChart(
    points: List<TimePoint>,
    referenceRange: ClosedFloatingPointRange<Double>?,
    windowMs: Long?,
    unit: String?,
    onVisibleSpanChanged: ((spanMs: Long) -> Unit)? = null,
    name: String? = null,
    rangeLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty()) return
    val modelProducer = remember { CartesianChartModelProducer() }
    val reduceMotion = LocalReduceMotion.current
    val lineColor = MaterialTheme.colorScheme.primary
    val bandColor = referenceBandColor(MaterialTheme.colorScheme)
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val startMs =
        remember(points, windowMs) {
            chartWindowStartMs(System.currentTimeMillis(), windowMs, points.minOf { it.timeMs })
        }
    val endMs =
        remember(points, windowMs) {
            max(System.currentTimeMillis(), points.maxOf { it.timeMs })
        }
    val domainSeconds = (endMs - startMs) / 1000.0
    val zone = remember { ZoneId.systemDefault() }
    val dayFormat = remember { DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()) }
    val monthFormat = remember { DateTimeFormatter.ofPattern("MMM yy", Locale.getDefault()) }
    val timeOfDayFormat = remember { DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault()) }
    val markerTimeFormat = timeOfDayFormat
    val zoomState =
        rememberVicoZoomState(
            maxZoom = remember { Zoom.max(Zoom.fixed(MAX_ZOOM), Zoom.Content) },
        )
    var visibleSpanMs by remember(windowMs) { mutableLongStateOf(windowMs ?: (endMs - startMs)) }
    LaunchedEffect(zoomState, domainSeconds, onVisibleSpanChanged) {
        snapshotFlow { zoomState.value }
            .collectLatest { zoom ->
                delay(ZOOM_SETTLE_MS)
                val spanMs = (domainSeconds * 1000 / zoom).toLong().coerceAtLeast(1)
                if (spanMs < visibleSpanMs || spanMs >= visibleSpanMs * 2) visibleSpanMs = spanMs
                onVisibleSpanChanged?.invoke(visibleSpanMs)
            }
    }
    val stepSeconds = remember(visibleSpanMs) { pickStepSeconds(visibleSpanMs / 1000.0) }
    val marker =
        rememberDefaultCartesianMarker(
            label =
                rememberTextComponent(
                    color = onSurfaceColor,
                    background =
                        rememberShapeComponent(
                            fill = Fill(surfaceColor.toArgb()),
                            shape = CorneredShape.Pill,
                        ),
                    padding = insets(horizontal = 8.dp, vertical = 4.dp),
                ),
            valueFormatter =
                remember(startMs, unit, zone, markerTimeFormat) {
                    DefaultCartesianMarker.ValueFormatter { _, targets ->
                        val target = targets.firstOrNull() as? LineCartesianLayerMarkerTarget
                        val entry = target?.points?.firstOrNull()?.entry ?: return@ValueFormatter ""
                        val instant = Instant.ofEpochMilli(startMs + (entry.x * 1000).toLong())
                        val unitText = unit?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
                        "${formatValue(entry.y)}$unitText · ${markerTimeFormat.format(instant.atZone(zone))}"
                    }
                },
            guideline =
                rememberLineComponent(
                    fill = Fill(lineColor.copy(alpha = 0.3f).toArgb()),
                ),
        )

    val pointProvider =
        remember(lineColor) {
            LineCartesianLayer.PointProvider.single(
                LineCartesianLayer.Point(
                    component = ShapeComponent(fill = Fill(lineColor.toArgb()), shape = CorneredShape.rounded(2f)),
                    sizeDp = 5f,
                ),
            )
        }
    val band =
        remember(bandColor) {
            HorizontalBox(
                y = { it.getOrNull(ReferenceRangeKey) ?: 0.0..0.0 },
                box = ShapeComponent(fill = Fill(bandColor.toArgb())),
            )
        }
    val rangeProvider =
        remember(domainSeconds) {
            object : CartesianLayerRangeProvider {
                override fun getMinX(
                    minX: Double,
                    maxX: Double,
                    extraStore: ExtraStore,
                ): Double = 0.0

                override fun getMaxX(
                    minX: Double,
                    maxX: Double,
                    extraStore: ExtraStore,
                ): Double = max(maxX, domainSeconds)
            }
        }
    val chart =
        rememberCartesianChart(
            rememberLineCartesianLayer(
                lineProvider =
                    LineCartesianLayer.LineProvider.series(
                        LineCartesianLayer.rememberLine(pointProvider = pointProvider),
                    ),
                rangeProvider = rangeProvider,
            ),
            startAxis =
                VerticalAxis.rememberStart(
                    valueFormatter = CartesianValueFormatter { _, value, _ -> formatValue(value) },
                ),
            bottomAxis =
                HorizontalAxis.rememberBottom(
                    valueFormatter =
                        CartesianValueFormatter { _, value, _ ->
                            val instant = Instant.ofEpochSecond(startMs / 1000 + value.toLong())
                            val formatter =
                                when {
                                    visibleSpanMs > 180L * 86_400_000 -> monthFormat
                                    visibleSpanMs > 36L * 86_400_000 -> dayFormat
                                    else -> timeOfDayFormat
                                }
                            formatter.format(instant.atZone(zone))
                        },
                ),
            getXStep = { _ -> stepSeconds },
            marker = marker,
            decorations = listOf(band),
        )

    LaunchedEffect(points, referenceRange, startMs) {
        // Integer-second x since the window start — Vico rejects x values with
        // more than four decimal places, and float division produces those.
        modelProducer.runTransaction {
            lineSeries {
                series(
                    x = points.map { ((it.timeMs - startMs) / 1000).toDouble() },
                    y = points.map { it.value.toDouble() },
                )
            }
            referenceRange?.let { range -> extras { store -> store.set(ReferenceRangeKey, range) } }
        }
    }

    val tapHaptic = rememberHaptic(HapticFeedbackType.ContextClick)
    val a11yDescription =
        chartA11yDescription(
            name = name,
            rangeLabel = rangeLabel,
            unit = unit,
            summary = remember(points) { ChartSummary.from(points.map { it.value.toDouble() }) },
        )
    CartesianChartHost(
        chart = chart,
        modelProducer = modelProducer,
        zoomState = zoomState,
        animationSpec = if (reduceMotion) null else tween(CHART_ENTRY_ANIMATION_MS),
        animateIn = !reduceMotion,
        modifier =
            modifier
                .height(260.dp)
                .markerPressHaptic(tapHaptic)
                .then(
                    if (a11yDescription == null) {
                        Modifier
                    } else {
                        Modifier.semantics { contentDescription = a11yDescription }
                    },
                ),
    )
}

/** M9: haptic when a touch engages the chart (the marker appears/selects) —
 *  observed on the Initial pass so Vico's own gesture handling is untouched. */
private fun Modifier.markerPressHaptic(haptic: () -> Unit): Modifier =
    pointerInput(haptic) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            haptic()
        }
    }

/** M9 a11y: the chart's TalkBack sentence — "Heart rate over last week,
 *  ranging from 58 to 142, currently 72 bpm" — from the loaded series
 *  ([ChartSummary] keeps the reduction pure/JVM-tested); null when the
 *  identity is unknown (no sentence better than a wrong one). */
@Composable
internal fun chartA11yDescription(
    name: String?,
    rangeLabel: String?,
    unit: String?,
    summary: ChartSummary?,
): String? {
    if (name == null || rangeLabel == null || summary == null) return null
    val unitSuffix = unit?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
    return stringResource(
        R.string.chart_a11y_summary,
        name,
        rangeLabel.replaceFirstChar { it.lowercase() },
        formatValue(summary.min),
        formatValue(summary.max),
        formatValue(summary.last) + unitSuffix,
    )
}

private const val MAX_ZOOM = 300f
private const val ZOOM_SETTLE_MS = 250L
private const val CHART_ENTRY_ANIMATION_MS = 300
