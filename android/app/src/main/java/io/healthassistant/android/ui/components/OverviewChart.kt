package io.healthassistant.android.ui.components

import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.insets
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
import io.healthassistant.android.ui.theme.LocalReduceMotion
import io.healthassistant.shared.data.SeriesScale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

/** One normalized series on the overview chart: display identity, the
 *  normalized points, and the scale that produced them (marker tooltips
 *  invert back to real units through it). */
data class OverviewSeries(
    val label: String,
    val unit: String?,
    val points: List<TimePoint>,
    val scale: SeriesScale? = null,
)

/** The overview's series swatch color for an index — shared by the chart
 *  lines and the legend rows so they can never disagree. Theme roles, not
 *  literals: primary → tertiary → secondary. */
@Composable
fun overviewSeriesColor(index: Int): Color =
    when (index % OVERVIEW_PALETTE) {
        0 -> MaterialTheme.colorScheme.primary
        1 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }

private val ReferenceBandKey = ExtraStore.Key<ClosedFloatingPointRange<Double>>()

/**
 * Multi-series normalized line chart for the wellness overview (monitoring
 * viz M6): up to three biomarkers with unlike units on one now-anchored time
 * axis, each series rescaled per-series by its own scale (see
 * `shared/data/Normalization.kt`) so shapes, not magnitudes, compare. The
 * y-axis is dimensionless. A [referenceBand] (already normalized) draws the
 * translucent reference-range box — only meaningful with a single series.
 */
@Composable
fun OverviewChart(
    series: List<OverviewSeries>,
    referenceBand: ClosedFloatingPointRange<Double>?,
    windowMs: Long?,
    a11yLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    val plottable = series.filter { it.points.isNotEmpty() }
    if (plottable.isEmpty()) return
    val reduceMotion = LocalReduceMotion.current
    val palette = plottable.mapIndexed { index, _ -> overviewSeriesColor(index) }
    val modelProducer = remember { CartesianChartModelProducer() }
    val bandColor = referenceBandColor(MaterialTheme.colorScheme)
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val startMs =
        remember(plottable, windowMs) {
            chartWindowStartMs(System.currentTimeMillis(), windowMs, plottable.minOf { s -> s.points.minOf { it.timeMs } })
        }
    val endMs =
        remember(plottable, windowMs) {
            max(System.currentTimeMillis(), plottable.maxOf { s -> s.points.maxOf { it.timeMs } })
        }
    val domainSeconds = (endMs - startMs) / 1000.0
    val zone = remember { ZoneId.systemDefault() }
    val dayFormat = remember { DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()) }
    val monthFormat = remember { DateTimeFormatter.ofPattern("MMM yy", Locale.getDefault()) }
    val timeOfDayFormat = remember { DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault()) }
    val markerTimeFormat = timeOfDayFormat
    val stepSeconds = remember(domainSeconds) { pickStepSeconds(domainSeconds) }
    val seriesByColor =
        remember(plottable, palette) {
            palette.mapIndexed { index, color -> color.toArgb() to plottable[index] }.toMap()
        }
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
                remember(startMs, seriesByColor, zone, markerTimeFormat) {
                    DefaultCartesianMarker.ValueFormatter { _, targets ->
                        val target = targets.firstOrNull() as? LineCartesianLayerMarkerTarget
                        val time =
                            markerTimeFormat.format(
                                Instant
                                    .ofEpochMilli(
                                        startMs +
                                            (
                                                (
                                                    target
                                                        ?.points
                                                        ?.firstOrNull()
                                                        ?.entry
                                                        ?.x ?: 0.0
                                                ) * 1000
                                            ).toLong(),
                                    ).atZone(zone),
                            )
                        val values =
                            target?.points.orEmpty().mapNotNull { point ->
                                val s = seriesByColor[point.color] ?: return@mapNotNull null
                                val raw = s.scale?.invert(point.entry.y.toDouble())
                                val text = raw?.let(::formatValue) ?: formatValue(point.entry.y.toDouble())
                                val unit = s.unit?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
                                "${s.label} $text$unit"
                            }
                        (values + time).joinToString(" · ")
                    }
                },
            guideline =
                rememberLineComponent(
                    fill =
                        Fill(
                            MaterialTheme.colorScheme.outline
                                .copy(alpha = 0.3f)
                                .toArgb(),
                        ),
                ),
        )
    val lineProvider =
        remember(palette) {
            LineCartesianLayer.LineProvider.series(
                palette.map { color ->
                    LineCartesianLayer.Line(
                        fill = LineCartesianLayer.LineFill.single(Fill(color.toArgb())),
                        pointProvider =
                            LineCartesianLayer.PointProvider.single(
                                LineCartesianLayer.Point(
                                    component = ShapeComponent(fill = Fill(color.toArgb()), shape = CorneredShape.rounded(2f)),
                                    sizeDp = 5f,
                                ),
                            ),
                    )
                },
            )
        }
    val band =
        remember(bandColor) {
            HorizontalBox(
                y = { it.getOrNull(ReferenceBandKey) ?: 0.0..0.0 },
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
                lineProvider = lineProvider,
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
                                    domainSeconds > 180L * 86_400 -> monthFormat
                                    domainSeconds > 36L * 86_400 -> dayFormat
                                    else -> timeOfDayFormat
                                }
                            formatter.format(instant.atZone(zone))
                        },
                ),
            getXStep = { _ -> stepSeconds },
            marker = marker,
            decorations = listOf(band),
        )

    LaunchedEffect(plottable, referenceBand, startMs) {
        // Integer-second x since the window start — Vico rejects x values with
        // more than four decimal places, and float division produces those.
        modelProducer.runTransaction {
            lineSeries {
                plottable.forEach { s ->
                    series(
                        x = s.points.map { ((it.timeMs - startMs) / 1000).toDouble() },
                        y = s.points.map { it.value.toDouble() },
                    )
                }
            }
            referenceBand?.let { range -> extras { store -> store.set(ReferenceBandKey, range) } }
        }
    }

    val tapHaptic = rememberHaptic(HapticFeedbackType.ContextClick)
    CartesianChartHost(
        chart = chart,
        modelProducer = modelProducer,
        animationSpec = if (reduceMotion) null else tween(CHART_ENTRY_ANIMATION_MS),
        animateIn = !reduceMotion,
        modifier =
            modifier
                .height(260.dp)
                .pointerInput(tapHaptic) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        tapHaptic()
                    }
                }.then(
                    if (a11yLabel == null) {
                        Modifier
                    } else {
                        Modifier.semantics { contentDescription = a11yLabel }
                    },
                ),
    )
}

private const val OVERVIEW_PALETTE = 3
private const val CHART_ENTRY_ANIMATION_MS = 300
