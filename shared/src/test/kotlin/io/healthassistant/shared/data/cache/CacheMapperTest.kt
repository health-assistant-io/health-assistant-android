package io.healthassistant.shared.data.cache

import io.healthassistant.shared.data.ObservationCode
import io.healthassistant.shared.data.ObservationPoint
import io.healthassistant.shared.data.ReferenceRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheMapperTest {
    private fun point(
        id: String = "obs-1",
        code: String = "8867-4",
        effectiveDatetime: String? = "2026-08-12T08:00:00Z",
        rawValue: Double? = 72.0,
        normalizedValue: Double? = null,
        valueString: String? = null,
        biomarkerSlug: String? = "heart_rate",
        referenceRange: ReferenceRange? = ReferenceRange(60.0, 100.0),
        relativeScore: Double? = 0.6,
        biomarkerValueType: String? = "quantity",
    ) = ObservationPoint(
        id = id,
        effectiveDatetime = effectiveDatetime,
        rawValue = rawValue,
        normalizedValue = normalizedValue,
        normalizedUnit = "beats/min",
        code = ObservationCode(coding = listOf(ObservationCode.Coding(code = code, system = "http://loinc.org", display = "Heart rate")), text = "Heart rate"),
        referenceRange = referenceRange,
        biomarkerId = "bm-1",
        biomarkerSlug = biomarkerSlug,
        biomarkerValueType = biomarkerValueType,
        valueString = valueString,
        interpretation = "normal",
        relativeScore = relativeScore,
    )

    @Test
    fun `toCached then toPoint round-trips the fields the UI uses`() {
        val original = point()

        val row = CacheMapper.toCached(original)
        assertNotNull(row)
        val restored = CacheMapper.toPoint(row!!)

        assertEquals(original.id, restored.id)
        assertEquals(original.effectiveDatetime, restored.effectiveDatetime)
        assertEquals(original.rawValue, restored.rawValue)
        assertEquals(original.normalizedUnit, restored.normalizedUnit)
        assertEquals(original.valueString, restored.valueString)
        assertEquals(original.interpretation, restored.interpretation)
        assertEquals(original.relativeScore, restored.relativeScore)
        assertEquals(original.biomarkerValueType, restored.biomarkerValueType)
        assertEquals(original.referenceRange, restored.referenceRange)
        assertEquals(original.primaryCode, restored.primaryCode)
    }

    @Test
    fun `toCached computes epoch ms from the ISO timestamp`() {
        val iso = "2026-08-12T08:00:00Z"
        val row = CacheMapper.toCached(point(effectiveDatetime = iso))!!
        assertEquals(java.time.Instant.parse(iso).toEpochMilli(), row.effectiveEpochMs)
    }

    @Test
    fun `toCached leaves epoch null for an unparseable timestamp`() {
        val row = CacheMapper.toCached(point(effectiveDatetime = "not-a-date"))!!
        assertNull(row.effectiveEpochMs)
    }

    @Test
    fun `toCached drops a point with no addressable code and no slug`() {
        val unaddressable =
            point().copy(
                code = ObservationCode(coding = emptyList(), text = null),
                biomarkerSlug = null,
            )
        assertNull(CacheMapper.toCached(unaddressable))
    }

    @Test
    fun `toCached falls back to biomarker slug when the coding code is absent`() {
        val slugOnly =
            point().copy(
                code = ObservationCode(coding = emptyList()),
                biomarkerSlug = "heart_rate",
            )
        val row = CacheMapper.toCached(slugOnly)
        assertNotNull(row)
        assertEquals("heart_rate", row!!.biomarkerCode)
    }

    @Test
    fun `toCached preserves a STATE biomarker carried in value_string`() {
        val state = point(valueString = "asleep", rawValue = null, biomarkerValueType = "state")
        val restored = CacheMapper.toPoint(CacheMapper.toCached(state)!!)

        assertEquals("asleep", restored.valueString)
        assertNull(restored.rawValue)
        assertEquals("state", restored.biomarkerValueType)
    }

    @Test
    fun `reference range round-trips through the row helper`() {
        val row = CacheMapper.toCached(point(referenceRange = ReferenceRange(50.0, 90.0)))!!
        assertEquals(ReferenceRange(50.0, 90.0), row.referenceRange)
        assertTrue(row.referenceRange!!.present)
    }
}
