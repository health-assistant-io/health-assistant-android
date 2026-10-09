package io.healthassistant.android.widget

import io.healthassistant.shared.data.BiomarkerSummary
import io.healthassistant.shared.data.ObservationCode
import io.healthassistant.shared.data.ObservationPoint
import io.healthassistant.shared.data.RangeStatus
import io.healthassistant.shared.data.ReferenceRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * M4 (widgets) — the pure cache-rows-to-widget-UI mapping: the Latest vitals
 * rows (catalog names, formatted values, status labels, newest-first cap),
 * the ring's value-in-range fraction, the heart-rate sparkline extraction,
 * and the time-ago buckets shared with the in-app MetricCard.
 */
class WidgetStateMapperTest {
    private val strings =
        object : WidgetStrings {
            override fun justNow(): String = "just now"

            override fun minutesAgo(count: Long): String = "$count min ago"

            override fun hoursAgo(count: Long): String = "$count h ago"

            override fun daysAgo(count: Long): String = "$count d ago"

            override fun noData(): String = "No readings yet"

            override fun statusHigh(): String = "High"

            override fun statusLow(): String = "Low"

            override fun statusNormal(): String = "Normal"

            override fun referenceRange(
                low: String,
                high: String,
            ): String = "Reference: $low–$high"

            override fun heartRate(): String = "Heart rate"
        }

    private val nowMs = Instant.parse("2026-10-09T08:00:00Z").toEpochMilli()

    private fun point(
        id: String,
        code: String,
        value: Double? = null,
        valueString: String? = null,
        unit: String? = null,
        iso: String? = null,
        range: ReferenceRange? = null,
    ): ObservationPoint =
        ObservationPoint(
            id = id,
            rawValue = value,
            normalizedUnit = unit,
            valueString = valueString,
            effectiveDatetime = iso,
            referenceRange = range,
            code = ObservationCode(coding = listOf(ObservationCode.Coding(code = code))),
        )

    private fun summary(
        code: String,
        name: String,
        unit: String? = null,
        low: Double? = null,
        high: Double? = null,
    ): BiomarkerSummary =
        BiomarkerSummary(
            id = "b-$code",
            name = name,
            code = code,
            unit = unit,
            referenceRangeMin = low,
            referenceRangeMax = high,
        )

    @Test
    fun `latest vitals rows map catalog names, formatted values and status labels newest first`() {
        val latest =
            listOf(
                point("1", "8867-4", value = 62.0, unit = "bpm", iso = "2026-10-09T08:00:00Z", range = ReferenceRange(50.0, 90.0)),
                point("2", "29463-7", value = 80.5, unit = "kg", iso = "2026-10-08T08:00:00Z"),
                point("3", "59408-5", value = 91.0, unit = "%", iso = "2026-10-07T08:00:00Z", range = ReferenceRange(94.0, 100.0)),
                point("4", "8480-6", value = 135.0, unit = "mmHg", iso = "2026-10-06T08:00:00Z", range = ReferenceRange(null, 120.0)),
                point("5", "55423-8", value = 9000.0, unit = "count", iso = "2026-10-05T08:00:00Z"),
            )
        val catalog =
            listOf(
                summary("8867-4", "Heart rate"),
                summary("29463-7", "Weight", unit = "kg"),
                summary("59408-5", "Oxygen saturation"),
                summary("8480-6", "BP systolic"),
                summary("55423-8", "Steps"),
            )

        val ui = WidgetStateMapper.latestVitals(latest, catalog, strings, maxRows = 4, nowMs = nowMs)

        assertEquals(listOf("8867-4", "29463-7", "59408-5", "8480-6"), ui.rows.map { it.code })
        val hr = ui.rows.first()
        assertEquals("Heart rate", hr.name)
        assertEquals("62", hr.valueText)
        assertEquals("bpm", hr.unit)
        assertEquals(RangeStatus.NORMAL, hr.status)
        assertEquals("Normal", hr.statusText)
        assertEquals(RangeStatus.LOW, ui.rows[2].status)
        assertEquals("Low", ui.rows[2].statusText)
        assertEquals(RangeStatus.HIGH, ui.rows[3].status)
        assertEquals("High", ui.rows[3].statusText)
    }

    @Test
    fun `latest vitals keeps state values and caps rows, header shows the newest reading time`() {
        val latest =
            listOf(
                point("1", "sleep", valueString = "7h 20m", iso = "2026-10-09T07:00:00Z"),
                point("2", "8867-4", value = 60.0, iso = "2026-10-09T06:00:00Z"),
            )

        val ui = WidgetStateMapper.latestVitals(latest, emptyList(), strings, maxRows = 1, nowMs = nowMs)

        assertEquals(1, ui.rows.size)
        assertEquals("7h 20m", ui.rows[0].valueText)
        assertNull(ui.rows[0].status)
        assertEquals("1 h ago", ui.headerTimeText)
    }

    @Test
    fun `ring maps point fields with catalog fallbacks for name, unit and range`() {
        val ui =
            WidgetStateMapper.ring(
                code = "8867-4",
                point = point("1", "8867-4", value = 95.0, iso = "2026-10-09T07:59:00Z"),
                summary = summary("8867-4", "Heart rate", unit = "bpm", low = 50.0, high = 90.0),
                strings = strings,
                nowMs = nowMs,
            )

        assertEquals("Heart rate", ui.name)
        assertEquals("95", ui.valueText)
        assertEquals("bpm", ui.unit)
        assertEquals(RangeStatus.HIGH, ui.status)
        assertEquals("High", ui.statusText)
        assertEquals(1f, ui.progress)
        assertEquals("Reference: 50–90", ui.rangeText)
        assertEquals("1 min ago", ui.timeText)
    }

    @Test
    fun `ring without any data keeps placeholders`() {
        val ui = WidgetStateMapper.ring("8867-4", point = null, summary = summary("8867-4", "Heart rate"), strings = strings, nowMs = nowMs)

        assertEquals("Heart rate", ui.name)
        assertNull(ui.valueText)
        assertNull(ui.status)
        assertNull(ui.progress)
        assertNull(ui.timeText)
    }

    @Test
    fun `ring progress places the value inside, below and above the range`() {
        assertEquals(0.5f, WidgetStateMapper.ringProgress(75.0, 50.0, 100.0)!!, 1e-6f)
        assertEquals(0f, WidgetStateMapper.ringProgress(10.0, 50.0, 100.0)!!, 1e-6f)
        assertEquals(1f, WidgetStateMapper.ringProgress(150.0, 50.0, 100.0)!!, 1e-6f)
        assertEquals(0.75f, WidgetStateMapper.ringProgress(90.0, high = 120.0, low = null)!!, 1e-6f)
        assertNull(WidgetStateMapper.ringProgress(60.0, low = 50.0, high = null))
        assertNull(WidgetStateMapper.ringProgress(60.0, low = null, high = null))
        assertNull(WidgetStateMapper.ringProgress(60.0, low = 100.0, high = 50.0))
    }

    @Test
    fun `heart rate maps value, status, time and the last-hour sparkline`() {
        val series =
            listOf(
                point("a", "8867-4", value = 58.0, iso = "2026-10-09T07:10:00Z"),
                point("b", "8867-4", value = 62.0, iso = "2026-10-09T07:20:00Z"),
                point("c", "8867-4", value = null, iso = "2026-10-09T07:30:00Z"),
                point("d", "8867-4", value = Double.NaN, iso = "2026-10-09T07:40:00Z"),
                point("e", "8867-4", value = 71.0, iso = "2026-10-09T07:50:00Z"),
            )
        val latest = point("e", "8867-4", value = 71.0, unit = "bpm", iso = "2026-10-09T07:59:00Z", range = ReferenceRange(50.0, 90.0))

        val ui = WidgetStateMapper.heartRate(latest, series, strings, nowMs = nowMs)

        assertEquals("Heart rate", ui.title)
        assertEquals("71", ui.valueText)
        assertEquals("bpm", ui.unit)
        assertEquals(RangeStatus.NORMAL, ui.status)
        assertEquals("Normal", ui.statusText)
        assertEquals("1 min ago", ui.timeText)
        assertEquals(listOf(58.0, 62.0, 71.0), ui.sparkline)
        assertEquals(50.0, ui.sparklineLow)
        assertEquals(90.0, ui.sparklineHigh)
    }

    @Test
    fun `sparkline downsamples evenly and always keeps the boundary values`() {
        val series = (0 until 100).map { point("p$it", "8867-4", value = it.toDouble()) }

        val values = WidgetStateMapper.sparklineValues(series, maxPoints = 10)

        assertEquals(10, values.size)
        assertEquals(0.0, values.first(), 1e-9)
        assertEquals(99.0, values.last(), 1e-9)
    }

    @Test
    fun `relative time uses the metric card buckets`() {
        fun text(minutesAgo: Long): String? = WidgetStateMapper.relativeTime(nowMs - minutesAgo * 60_000L, nowMs, strings)

        assertEquals("just now", text(0))
        assertEquals("5 min ago", text(5))
        assertEquals("1 h ago", text(90))
        assertEquals("3 d ago", text(60 * 24 * 3))
    }
}
