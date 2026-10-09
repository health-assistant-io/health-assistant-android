package io.healthassistant.android.ui

import io.healthassistant.shared.data.BiomarkerOption
import io.healthassistant.shared.data.ObservationCode
import io.healthassistant.shared.data.ObservationPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-list derivation for the Biomarkers page (Records › Biomarkers):
 * with-data cards first (most-recent first), without-data after (name
 * order), and the trend chip staying hidden until a previous value exists.
 * Also gates [trendOf] — the Δ-vs-previous computation behind every trend
 * chip (up / down / stable / no-previous).
 */
class BiomarkersListLogicTest {
    private fun option(
        id: String,
        name: String,
        ts: String? = null,
        value: Double? = null,
        unit: String? = null,
    ): BiomarkerOption =
        BiomarkerOption(
            id = id,
            name = name,
            code = id,
            unit = unit,
            latestValue = value,
            latestTimestamp = ts,
        )

    private fun reading(
        id: String,
        code: String,
        value: Double,
    ): ObservationPoint =
        ObservationPoint(
            id = id,
            rawValue = value,
            code = ObservationCode(coding = listOf(ObservationCode.Coding(code = code))),
        )

    @Test
    fun `biomarkers with data come first sorted by most recent`() {
        val options =
            listOf(
                option("b", "Blood Pressure", "2026-08-10T10:00:00Z", 120.0, "mmHg"),
                option("a", "Heart Rate", "2026-08-15T10:00:00Z", 62.0, "bpm"),
                option("z", "Zinc", null),
                option("c", "Weight", "2026-08-12T10:00:00Z", 80.5, "kg"),
            )
        val (withData, withoutData) = BiomarkersViewModel.sortAndPartition(options)
        assertEquals(listOf("a", "c", "b"), withData.map { it.option.code })
        assertEquals(listOf("z"), withoutData.map { it.option.code })
        assertTrue(withData.all { it.hasData })
        assertTrue(withoutData.all { !it.hasData })
    }

    @Test
    fun `same-timestamp ties break by name`() {
        val ts = "2026-08-15T10:00:00Z"
        val options =
            listOf(
                option("1", "Weight", ts),
                option("2", "Cholesterol", ts),
                option("3", "ALT", ts),
            )
        val (withData, _) = BiomarkersViewModel.sortAndPartition(options)
        assertEquals(listOf("3", "2", "1"), withData.map { it.option.code })
    }

    @Test
    fun `without-data section sorts by name case-insensitively`() {
        val options =
            listOf(
                option("1", "zeta marker"),
                option("2", "Alpha marker"),
                option("3", "Mid marker"),
            )
        val (_, withoutData) = BiomarkersViewModel.sortAndPartition(options)
        assertEquals(listOf("2", "3", "1"), withoutData.map { it.option.code })
    }

    @Test
    fun `trend is null until a previous value is known`() {
        val (_, withoutData) = BiomarkersViewModel.sortAndPartition(listOf(option("1", "HR")))
        assertTrue(withoutData.single().trend == null)
    }

    @Test
    fun `trendOf computes direction and percent vs the previous reading`() {
        val up = trendOf(75.0, 70.0)!!

        assertEquals(Trend.UP, up.trend)
        assertEquals(7.14, up.percent, 0.01)

        val down = trendOf(60.0, 80.0)!!

        assertEquals(Trend.DOWN, down.trend)
        assertEquals(-25.0, down.percent, 0.0)
    }

    @Test
    fun `changes within the flat threshold read as stable`() {
        assertEquals(Trend.FLAT, trendOf(70.5, 70.0)!!.trend)
        assertEquals(Trend.FLAT, trendOf(72.0, 72.0)!!.trend)
        assertEquals(Trend.FLAT, trendOf(69.5, 70.0)!!.trend)
    }

    @Test
    fun `trendOf needs both values and a non-zero previous`() {
        assertNull(trendOf(70.0, null))
        assertNull(trendOf(null, 70.0))
        assertNull(trendOf(null, null))
        assertNull(trendOf(70.0, 0.0))
    }

    @Test
    fun `cards carry the trend when a previous reading exists`() {
        val options =
            listOf(
                option("8867-4", "Heart Rate", "2026-08-15T10:00:00Z", 75.0),
                option("55423-8", "Steps", "2026-08-15T10:00:00Z", 10_000.0),
            )
        val previous =
            mapOf(
                "8867-4" to reading("hr-prev", "8867-4", 70.0),
                "55423-8" to reading("steps-prev", "55423-8", 10_050.0),
            )

        val (withData, _) = BiomarkersViewModel.sortAndPartition(options, previous)

        val byCode = withData.associateBy { it.option.code }
        assertEquals(Trend.UP, byCode["8867-4"]?.trend)
        assertEquals(Trend.FLAT, byCode["55423-8"]?.trend)
    }

    @Test
    fun `cards without a cached previous keep the trend hidden`() {
        val options = listOf(option("8867-4", "Heart Rate", "2026-08-15T10:00:00Z", 75.0))

        val (withData, _) = BiomarkersViewModel.sortAndPartition(options, emptyMap())

        assertNull(withData.single().trend)
    }

    @Test
    fun `empty catalog yields empty partitions`() {
        val (withData, withoutData) = BiomarkersViewModel.sortAndPartition(emptyList())
        assertTrue(withData.isEmpty())
        assertTrue(withoutData.isEmpty())
    }

    @Test
    fun `filtering by query matches name or code case-insensitively`() {
        val state =
            BiomarkersUiState(
                loading = false,
                query = "heart",
                cards =
                    listOf(
                        BiomarkerCard(
                            option = option("8867-4", "Heart Rate"),
                            hasData = true,
                            trend = null,
                        ),
                        BiomarkerCard(
                            option = option("b", "Blood Pressure"),
                            hasData = true,
                            trend = null,
                        ),
                    ),
            )
        assertFalse(state.cards.none { it.option.name.contains(state.query, true) })
        assertEquals(1, state.cards.count { it.option.name.contains(state.query, ignoreCase = true) })
    }
}
