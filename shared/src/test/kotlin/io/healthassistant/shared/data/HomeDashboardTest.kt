package io.healthassistant.shared.data

import io.healthassistant.shared.healthconnect.HcType
import io.healthassistant.shared.healthconnect.RawSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeDashboardTest {

    private val heartRateCatalog =
        BiomarkerSummary(
            id = "b1",
            name = "Heart Rate",
            slug = "heart-rate",
            code = "8867-4",
            unit = "beats/min",
            isTelemetry = true,
            referenceRangeMin = 60.0,
            referenceRangeMax = 100.0,
        )

    private val glucoseCatalog =
        BiomarkerSummary(
            id = "b2",
            name = "Fasting Glucose",
            slug = "glucose-fasting",
            code = "2345-7",
            unit = "mmol/L",
            isTelemetry = false,
        )

    @Test
    fun `fromServer builds one card per biomarker with a latest value`() {
        val latest =
            listOf(
                ObservationPoint(
                    id = "o1",
                    effectiveDatetime = "2026-08-10T09:00:00Z",
                    rawValue = 72.0,
                    normalizedUnit = "beats/min",
                    code = ObservationCode(coding = listOf(ObservationCode.Coding(system = "loinc", code = "8867-4"))),
                ),
                ObservationPoint(
                    id = "o2",
                    effectiveDatetime = "2026-08-10T08:00:00Z",
                    rawValue = 5.5,
                    normalizedUnit = "mmol/L",
                    code = ObservationCode(coding = listOf(ObservationCode.Coding(system = "loinc", code = "2345-7"))),
                ),
            )

        val cards = HomeDashboardBuilder.fromServer(latest, listOf(heartRateCatalog, glucoseCatalog))

        assertEquals(2, cards.size)
        val hr = cards.first { it.code == "8867-4" }
        assertEquals("Heart Rate", hr.displayName)
        assertEquals(72.0, hr.value)
        assertEquals("beats/min", hr.unit)
        assertEquals(60.0, hr.referenceRange?.low)
        assertEquals(HcType.HEART_RATE, hr.hcType)
        assert(hr.isTelemetry)

        val glucose = cards.first { it.code == "2345-7" }
        assertEquals("Fasting Glucose", glucose.displayName)
        assertEquals("mmol/L", glucose.unit)
        assertNull(glucose.hcType)
    }

    @Test
    fun `fromServer uses observation display name and skips unknown codes`() {
        val latest =
            listOf(
                ObservationPoint(
                    id = "o1",
                    rawValue = 5.0,
                    biomarkerSlug = "cholesterol-total",
                    code = ObservationCode(coding = listOf(ObservationCode.Coding(code = "2093-3"))),
                ),
                ObservationPoint(id = "o2", rawValue = 1.0),
            )

        val cards = HomeDashboardBuilder.fromServer(latest, emptyList())

        assertEquals(1, cards.size)
        assertEquals("cholesterol-total", cards[0].displayName)
        assertEquals("2093-3", cards[0].code)
    }

    @Test
    fun `fromLocal maps HC readings to cards`() {
        val local =
            mapOf(
                HcType.HEART_RATE to RawSample(hcType = HcType.HEART_RATE, value = 70.0, unit = "bpm", timestamp = "2026-08-10T09:00:00Z"),
            )

        val cards = HomeDashboardBuilder.fromLocal(local)

        assertEquals(1, cards.size)
        assertEquals("Heart Rate", cards[0].displayName)
        assertEquals(70.0, cards[0].value)
        assertEquals(HcType.HEART_RATE, cards[0].hcType)
    }

    @Test
    fun `merge keeps newer timestamp and unions distinct codes`() {
        val local =
            listOf(
                BiomarkerReading(code = "8867-4", displayName = "Heart Rate", value = 70.0, timestamp = "2026-08-10T09:00:00Z"),
                BiomarkerReading(code = "9999-9", displayName = "Local only", value = 1.0),
            )
        val server =
            listOf(
                // Newer → wins
                BiomarkerReading(code = "8867-4", displayName = "Heart Rate", value = 72.0, timestamp = "2026-08-10T10:00:00Z"),
                // Server only
                BiomarkerReading(code = "2345-7", displayName = "Fasting Glucose", value = 5.5, timestamp = "2026-08-10T08:00:00Z"),
            )

        val merged = HomeDashboardBuilder.merge(local, server)

        assertEquals(3, merged.size)
        val hr = merged.first { it.code == "8867-4" }
        assertEquals(72.0, hr.value)
        assertEquals("2026-08-10T10:00:00Z", hr.timestamp)
    }
}

/** RangeStatus: where the latest value sits vs the reference range. */
class RangeStatusTest {
    private fun reading(
        value: Double?,
        low: Double?,
        high: Double?,
    ) = BiomarkerReading(
        code = "x",
        displayName = "X",
        value = value,
        referenceRange =
            if (low == null && high == null) null else ReferenceRange(low = low, high = high),
    )

    @Test
    fun `value inside range is normal`() {
        assertEquals(RangeStatus.NORMAL, reading(5.0, 3.0, 6.0).rangeStatus)
        assertEquals(RangeStatus.NORMAL, reading(3.0, 3.0, 6.0).rangeStatus)
        assertEquals(RangeStatus.NORMAL, reading(6.0, 3.0, 6.0).rangeStatus)
    }

    @Test
    fun `above high is high and below low is low`() {
        assertEquals(RangeStatus.HIGH, reading(6.1, 3.0, 6.0).rangeStatus)
        assertEquals(RangeStatus.LOW, reading(2.9, 3.0, 6.0).rangeStatus)
    }

    @Test
    fun `one-sided ranges classify against the present bound`() {
        assertEquals(RangeStatus.LOW, reading(1.0, 3.0, null).rangeStatus)
        assertEquals(RangeStatus.NORMAL, reading(4.0, 3.0, null).rangeStatus)
        assertEquals(RangeStatus.HIGH, reading(2.0, null, 1.7).rangeStatus)
        assertEquals(RangeStatus.NORMAL, reading(1.5, null, 1.7).rangeStatus)
    }

    @Test
    fun `unknown without value or range`() {
        assertNull(reading(null, 3.0, 6.0).rangeStatus)
        assertNull(reading(5.0, null, null).rangeStatus)
    }
}
