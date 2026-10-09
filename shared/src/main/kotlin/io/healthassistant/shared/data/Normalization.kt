package io.healthassistant.shared.data

import kotlin.math.sqrt

/**
 * How one biomarker series is rescaled onto the shared axis of the
 * multi-metric overview chart, so unlike units (kg, bpm, h) can be plotted
 * together. Pure Kotlin — the strategy is chosen by the user, the scale is
 * fitted per series by [Normalization].
 */
enum class NormalizationStrategy {
    /** Maps the series' observed min–max onto 0–1. */
    MIN_MAX,

    /** Maps values to standard deviations from the series' mean. */
    Z_SCORE,
}

/**
 * The scale fitted to one series under a [NormalizationStrategy]. Carried
 * next to the normalized points so real-unit values can still be shown
 * (marker tooltips invert through it) and the mapping is deterministic.
 * A series with no spread (constant, or a single point) maps onto the
 * strategy's midline instead of dividing by zero.
 */
data class SeriesScale(
    val strategy: NormalizationStrategy,
    val min: Double,
    val max: Double,
    val mean: Double,
    val stdDev: Double,
) {
    /** Maps a raw series value onto the normalized axis. */
    fun apply(value: Double): Double =
        when (strategy) {
            NormalizationStrategy.MIN_MAX ->
                if (max > min) (value - min) / (max - min) else FLAT_MIN_MAX

            NormalizationStrategy.Z_SCORE ->
                if (stdDev > 0.0) (value - mean) / stdDev else FLAT_Z_SCORE
        }

    /** Maps a normalized value back to the series' real unit. */
    fun invert(normalized: Double): Double =
        when (strategy) {
            NormalizationStrategy.MIN_MAX ->
                if (max > min) normalized * (max - min) + min else mean

            NormalizationStrategy.Z_SCORE ->
                if (stdDev > 0.0) normalized * stdDev + mean else mean
        }

    private companion object {
        const val FLAT_MIN_MAX = 0.5
        const val FLAT_Z_SCORE = 0.0
    }
}

/**
 * Per-series normalization for the wellness overview (monitoring viz M6).
 * Each series is fitted independently — unlike units never share a scale,
 * only an axis. Pure Kotlin, JVM-tested.
 */
object Normalization {
    /** Fits the scale of a series, or null for an empty one. The standard
     *  deviation is the population one, so a single point yields 0. */
    fun fit(
        values: List<Double>,
        strategy: NormalizationStrategy,
    ): SeriesScale? {
        if (values.isEmpty()) return null
        val min = values.min()
        val max = values.max()
        val mean = values.sum() / values.size
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return SeriesScale(
            strategy = strategy,
            min = min,
            max = max,
            mean = mean,
            stdDev = sqrt(variance),
        )
    }

    /** Fits and maps a series in one step, preserving order; null when
     *  empty. */
    fun apply(
        values: List<Double>,
        strategy: NormalizationStrategy,
    ): List<Double>? = fit(values, strategy)?.let { scale -> values.map(scale::apply) }
}
