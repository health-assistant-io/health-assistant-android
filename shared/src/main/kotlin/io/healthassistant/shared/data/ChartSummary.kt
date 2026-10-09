package io.healthassistant.shared.data

/**
 * M9 a11y: the numeric summary behind a chart's TalkBack fallback sentence —
 * min/max/last plus the point count over the plotted values. Pure Kotlin over
 * plain doubles so the sentence's data stays JVM-testable and chart-agnostic
 * (single-series and normalized multi-series charts alike); the app layers
 * localized text on top.
 */
data class ChartSummary(
    val min: Double,
    val max: Double,
    val last: Double,
    val count: Int,
) {
    companion object {
        /** Reduces the plotted values to their summary, or null when the
         *  series is empty (no sentence to speak). [last] is the final
         *  (newest) value. */
        fun from(values: List<Double>): ChartSummary? {
            if (values.isEmpty()) return null
            return ChartSummary(
                min = values.min(),
                max = values.max(),
                last = values.last(),
                count = values.size,
            )
        }
    }
}
