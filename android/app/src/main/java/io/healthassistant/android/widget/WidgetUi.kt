package io.healthassistant.android.widget

import io.healthassistant.android.ui.components.formatValue
import io.healthassistant.shared.data.BiomarkerReading
import io.healthassistant.shared.data.BiomarkerSummary
import io.healthassistant.shared.data.HomeDashboardBuilder
import io.healthassistant.shared.data.ObservationPoint
import io.healthassistant.shared.data.RangeStatus
import io.healthassistant.shared.data.ReferenceRange
import java.time.Instant

/**
 * M4 (widgets) — localized text the widget renderers need, resolved from
 * string resources on Android and from literals in JVM tests, so the whole
 * cache-rows-to-text mapping stays unit-testable.
 */
interface WidgetStrings {
    fun justNow(): String

    fun minutesAgo(count: Long): String

    fun hoursAgo(count: Long): String

    fun daysAgo(count: Long): String

    fun noData(): String

    fun statusHigh(): String

    fun statusLow(): String

    fun statusNormal(): String

    fun referenceRange(
        low: String,
        high: String,
    ): String

    fun heartRate(): String
}

/** One MetricCard-style row of the Latest vitals widget (2x2). */
data class WidgetMetricRow(
    val code: String,
    val name: String,
    val valueText: String,
    val unit: String?,
    val status: RangeStatus?,
    val statusText: String?,
)

/** The Latest vitals widget's whole state; [headerTimeText] is the newest row's reading time. */
data class LatestVitalsWidgetUi(
    val rows: List<WidgetMetricRow>,
    val headerTimeText: String?,
)

/** The Single metric widget's state; [progress] is the 0..1 position inside the reference range
 * (null when no range can bound the value — render a track-only ring). */
data class RingWidgetUi(
    val code: String,
    val name: String,
    val valueText: String?,
    val unit: String?,
    val status: RangeStatus?,
    val statusText: String?,
    val progress: Float?,
    val rangeText: String?,
    val timeText: String?,
)

/** The Live heart rate widget's state; [sparkline] holds the last hour's values (ascending,
 * null/NaN dropped, downsampled to [WidgetStateMapper.heartRate]'s max points) and
 * [sparklineLow]/[sparklineHigh] the reference band to shade behind them. */
data class HeartRateWidgetUi(
    val title: String,
    val valueText: String?,
    val unit: String?,
    val status: RangeStatus?,
    val statusText: String?,
    val timeText: String?,
    val sparkline: List<Double>,
    val sparklineLow: Double?,
    val sparklineHigh: Double?,
)

/**
 * M4 (widgets) — the pure cache-rows-to-widget-UI mapper. Everything the three Glance
 * widgets render (values, units, status labels, time-ago, ring fraction, sparkline points)
 * is computed here, JVM-testable with a fake [WidgetStrings]; the renderers only turn
 * these models into Glance composables and colors.
 */
object WidgetStateMapper {
    /** Rows for the Latest vitals widget: [HomeDashboardBuilder] merges the latest-per-biomarker
     * cache page with the biomarker catalog (newest first), capped at [maxRows]. */
    fun latestVitals(
        latest: List<ObservationPoint>,
        catalog: List<BiomarkerSummary>,
        strings: WidgetStrings,
        maxRows: Int = 4,
        nowMs: Long = System.currentTimeMillis(),
    ): LatestVitalsWidgetUi {
        val readings = HomeDashboardBuilder.fromServer(latest, catalog).take(maxRows)
        val rows = readings.map { it.toWidgetRow(strings) }
        val newestTs = readings.maxOfOrNull { it.timestamp.toEpochMs() ?: 0L }?.takeIf { it > 0L }
        return LatestVitalsWidgetUi(
            rows = rows,
            headerTimeText = relativeTime(newestTs, nowMs, strings),
        )
    }

    /** State for the Single metric widget: the latest cached point for [code], falling back to
     * the catalog entry for name/unit/range when the point carries none. */
    fun ring(
        code: String,
        point: ObservationPoint?,
        summary: BiomarkerSummary?,
        strings: WidgetStrings,
        nowMs: Long = System.currentTimeMillis(),
    ): RingWidgetUi {
        val range = point?.range ?: summary?.referenceRange
        val value = point?.chartValue
        val status = statusOf(value, range)
        return RingWidgetUi(
            code = code,
            name = summary?.name ?: point?.displayName ?: code,
            valueText = point?.valueString ?: value?.let(::formatValue),
            unit = point?.normalizedUnit ?: summary?.unit,
            status = status,
            statusText = status?.let { statusLabel(strings, it) },
            progress = value?.let { ringProgress(it, range?.low, range?.high) },
            rangeText =
                range?.takeIf { it.present }?.let {
                    strings.referenceRange(it.low?.let(::formatValue) ?: "—", it.high?.let(::formatValue) ?: "—")
                },
            timeText = relativeTime(point?.effectiveDatetime.toEpochMs(), nowMs, strings),
        )
    }

    /** State for the Live heart rate widget: the latest cached heart-rate reading plus the
     * last hour's series (already ascending from the cache) downsampled for the sparkline. */
    fun heartRate(
        latest: ObservationPoint?,
        series: List<ObservationPoint>,
        strings: WidgetStrings,
        maxSparkPoints: Int = 60,
        nowMs: Long = System.currentTimeMillis(),
    ): HeartRateWidgetUi {
        val range = latest?.range
        val status = statusOf(latest?.chartValue, range)
        return HeartRateWidgetUi(
            title = strings.heartRate(),
            valueText = latest?.valueString ?: latest?.chartValue?.let(::formatValue),
            unit = latest?.normalizedUnit,
            status = status,
            statusText = status?.let { statusLabel(strings, it) },
            timeText = relativeTime(latest?.effectiveDatetime.toEpochMs(), nowMs, strings),
            sparkline = sparklineValues(series, maxSparkPoints),
            sparklineLow = range?.low,
            sparklineHigh = range?.high,
        )
    }

    /** The ring's sweep fraction: the value's position inside [low, high], clamped to 0..1;
     * with only a ceiling, the distance below it; null when no bound can place the value
     * (or the bounds are inverted — a data bug rendered as a track-only ring). */
    fun ringProgress(
        value: Double,
        low: Double?,
        high: Double?,
    ): Float? =
        when {
            low != null && high != null && high > low -> ((value - low) / (high - low)).coerceIn(0.0, 1.0).toFloat()
            low == null && high != null && high > 0 -> (value / high).coerceIn(0.0, 1.0).toFloat()
            else -> null
        }

    /** Sparkline ordinates: chart values with nulls/NaN dropped, evenly downsampled to
     * [maxPoints] (first + last always kept — they carry the window's boundary values). */
    fun sparklineValues(
        series: List<ObservationPoint>,
        maxPoints: Int,
    ): List<Double> {
        val values = series.mapNotNull { point -> point.chartValue?.takeIf(Double::isFinite) }
        if (values.size <= maxPoints || maxPoints < 2) return values
        val last = values.size - 1
        val indices = (0 until maxPoints).map { step -> (step.toLong() * last / (maxPoints - 1)).toInt() }
        return indices.distinct().map(values::get)
    }

    /** "2 min ago"-style text for a reading timestamp, using the same buckets as the
     * in-app MetricCard time-ago. */
    fun relativeTime(
        epochMs: Long?,
        nowMs: Long,
        strings: WidgetStrings,
    ): String? =
        epochMs?.let { ts ->
            val diff = nowMs - ts
            when {
                diff < 60_000L -> strings.justNow()
                diff < 3_600_000L -> strings.minutesAgo(diff / 60_000L)
                diff < 86_400_000L -> strings.hoursAgo(diff / 3_600_000L)
                else -> strings.daysAgo(diff / 86_400_000L)
            }
        }

    private fun statusOf(
        value: Double?,
        range: ReferenceRange?,
    ): RangeStatus? =
        BiomarkerReading(
            code = "",
            displayName = "",
            value = value,
            referenceRange = range,
        ).rangeStatus

    private fun statusLabel(
        strings: WidgetStrings,
        status: RangeStatus,
    ): String =
        when (status) {
            RangeStatus.HIGH -> strings.statusHigh()
            RangeStatus.LOW -> strings.statusLow()
            RangeStatus.NORMAL -> strings.statusNormal()
        }

    private fun BiomarkerReading.toWidgetRow(strings: WidgetStrings): WidgetMetricRow =
        WidgetMetricRow(
            code = code,
            name = displayName,
            valueText = valueString ?: value?.let(::formatValue) ?: "—",
            unit = unit,
            status = rangeStatus,
            statusText = rangeStatus?.let { statusLabel(strings, it) },
        )
}

private fun String?.toEpochMs(): Long? = this?.let { iso -> runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull() }
