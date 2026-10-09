package io.healthassistant.shared.data

import kotlin.math.round

/**
 * Summary statistics over one biomarker's numeric points in a range — the
 * Min/Max/Avg/Last row under the biomarker detail chart. Pure Kotlin over the
 * cached [ObservationPoint]s, so it is JVM-testable and unit-agnostic.
 *
 * [avg] is rounded to one decimal to match the detail screen's value
 * formatting, so the stats row never shows more precision than the plotted
 * values. Points without a numeric value ([ObservationPoint.chartValue]) are
 * ignored — a state-only reading contributes nothing to a numeric summary.
 */
data class SeriesStats(
    val min: Double,
    val max: Double,
    val avg: Double,
    val last: Double,
) {
    companion object {
        /**
         * Reduces time-ordered (ascending) points to their numeric summary, or
         * null when no point carries a numeric value. [last] is the value of
         * the final (newest) point.
         */
        fun from(points: List<ObservationPoint>): SeriesStats? {
            val values = points.mapNotNull { it.chartValue }
            if (values.isEmpty()) return null
            return SeriesStats(
                min = values.min(),
                max = values.max(),
                avg = round(values.sum() / values.size * 10.0) / 10.0,
                last = values.last(),
            )
        }
    }
}
