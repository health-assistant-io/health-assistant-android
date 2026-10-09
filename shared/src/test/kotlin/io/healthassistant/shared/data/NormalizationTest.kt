package io.healthassistant.shared.data

import io.healthassistant.shared.data.NormalizationStrategy.MIN_MAX
import io.healthassistant.shared.data.NormalizationStrategy.Z_SCORE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Monitoring-viz M6 gate: per-series min-max + z-score normalization over
 * unlike-unit series — boundaries, constant/single-point series (no divide
 * by zero), negative values, and both strategies' invertibility.
 */
class NormalizationTest {
    private fun assertInRange(
        actual: Double,
        expected: Double,
        epsilon: Double = 1e-9,
    ) {
        assertEquals(
            "expected $expected but was $actual",
            expected,
            actual,
            epsilon,
        )
    }

    @Test
    fun min_max_maps_bounds_to_zero_and_one_and_mid_to_half() {
        val normalized = Normalization.apply(listOf(10.0, 55.0, 100.0), MIN_MAX)!!

        assertInRange(0.0, normalized[0])
        assertInRange(0.5, normalized[1])
        assertInRange(1.0, normalized[2])
    }

    @Test
    fun min_max_handles_negative_values() {
        val normalized = Normalization.apply(listOf(-10.0, -5.0, 0.0, 5.0), MIN_MAX)!!

        assertInRange(0.0, normalized[0])
        assertInRange(1.0 / 3.0, normalized[1])
        assertInRange(2.0 / 3.0, normalized[2])
        assertInRange(1.0, normalized[3])
    }

    @Test
    fun min_max_constant_series_maps_to_the_midline_not_nan() {
        val normalized = Normalization.apply(listOf(7.0, 7.0, 7.0), MIN_MAX)!!

        assertEquals(3, normalized.size)
        normalized.forEach { assertInRange(0.5, it) }
    }

    @Test
    fun min_max_single_point_maps_to_the_midline() {
        val normalized = Normalization.apply(listOf(42.0), MIN_MAX)!!

        assertInRange(0.5, normalized.single())
    }

    @Test
    fun z_score_maps_the_mean_to_zero() {
        val values = listOf(64.0, 72.0, 80.0)
        val scale = Normalization.fit(values, Z_SCORE)!!

        assertInRange(72.0, scale.mean)
        assertInRange(0.0, scale.apply(72.0))
        val normalized = Normalization.apply(values, Z_SCORE)!!
        assertInRange(0.0, normalized[1])
        assertInRange(0.0, normalized[0] + normalized[2])
    }

    @Test
    fun z_score_is_in_standard_deviations() {
        val values = listOf(1.0, 2.0, 3.0)
        val scale = Normalization.fit(values, Z_SCORE)!!

        val expectedDeviation = sqrt(2.0 / 3.0)
        assertInRange(expectedDeviation, scale.stdDev)
        assertInRange(1.0 / expectedDeviation, scale.apply(3.0))
        assertInRange(-1.0 / expectedDeviation, scale.apply(1.0))
    }

    @Test
    fun z_score_handles_negative_values() {
        val values = listOf(-10.0, 0.0, 10.0)
        val normalized = Normalization.apply(values, Z_SCORE)!!

        assertInRange(0.0, normalized[1])
        assertInRange(normalized[2], abs(normalized[0]))
        assertInRange(-normalized[2], normalized[0])
    }

    @Test
    fun z_score_constant_series_maps_to_zero_not_nan() {
        val normalized = Normalization.apply(listOf(3.0, 3.0), Z_SCORE)!!

        normalized.forEach { assertInRange(0.0, it) }
    }

    @Test
    fun z_score_single_point_maps_to_zero() {
        val normalized = Normalization.apply(listOf(9.0), Z_SCORE)!!

        assertInRange(0.0, normalized.single())
    }

    @Test
    fun empty_series_returns_null_under_both_strategies() {
        assertNull(Normalization.apply(emptyList(), MIN_MAX))
        assertNull(Normalization.apply(emptyList(), Z_SCORE))
        assertNull(Normalization.fit(emptyList(), MIN_MAX))
        assertNull(Normalization.fit(emptyList(), Z_SCORE))
    }

    @Test
    fun apply_preserves_order_and_size() {
        val values = listOf(50.0, 10.0, 90.0, 70.0)
        val normalized = Normalization.apply(values, MIN_MAX)!!

        assertEquals(values.size, normalized.size)
        assertEquals(Normalization.fit(values, MIN_MAX)!!.apply(10.0), normalized[1], 1e-12)
    }

    @Test
    fun invert_round_trips_under_both_strategies() {
        for (strategy in NormalizationStrategy.entries) {
            val values = listOf(-4.0, -1.0, 2.5, 6.0)
            val scale = Normalization.fit(values, strategy)!!
            values.forEach { value ->
                assertInRange(value, scale.invert(scale.apply(value)))
            }
        }
    }

    @Test
    fun invert_on_a_constant_series_returns_the_constant() {
        for (strategy in NormalizationStrategy.entries) {
            val scale = Normalization.fit(listOf(5.0, 5.0), strategy)!!

            assertInRange(5.0, scale.invert(scale.apply(5.0)))
        }
    }

    @Test
    fun unlike_unit_series_share_the_same_axis_range() {
        val weight = Normalization.apply(listOf(78.0, 80.0, 82.0), MIN_MAX)!!
        val heartRate = Normalization.apply(listOf(58.0, 62.0, 71.0), MIN_MAX)!!
        val sleepHours = Normalization.apply(listOf(5.5, 6.5, 7.5), MIN_MAX)!!

        listOf(weight, heartRate, sleepHours).forEach { series ->
            assertEquals(0.0, series.min(), 1e-9)
            assertEquals(1.0, series.max(), 1e-9)
        }
    }

    @Test
    fun the_strategies_disagree_on_the_same_series() {
        val values = listOf(0.0, 5.0, 20.0)
        val minMax = Normalization.apply(values, MIN_MAX)!!
        val zScore = Normalization.apply(values, Z_SCORE)!!

        assertInRange(minMax[2], 1.0)
        assertInRange(zScore[2], 1.372813, 1e-5)
    }
}
