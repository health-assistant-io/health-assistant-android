package io.healthassistant.shared.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class ChartRangeTest {

    private val now = Instant.parse("2026-08-10T12:00:00Z").toEpochMilli()

    @Test
    fun `one week floor is seven days before now`() {
        assertEquals("2026-08-03T12:00:00Z", ChartRange.ONE_WEEK.sinceIso(now))
    }

    @Test
    fun `one month floor is thirty days before now`() {
        assertEquals("2026-07-11T12:00:00Z", ChartRange.ONE_MONTH.sinceIso(now))
    }

    @Test
    fun `one year floor is 365 days before now`() {
        assertEquals("2025-08-10T12:00:00Z", ChartRange.ONE_YEAR.sinceIso(now))
    }

    @Test
    fun `until is now`() {
        assertEquals("2026-08-10T12:00:00Z", ChartRange.ONE_WEEK.untilIso(now))
    }
}
