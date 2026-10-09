package io.healthassistant.shared.data

import java.time.Instant

/** Time ranges for the Insights charts. Pure Kotlin so the since/until mapping
 *  is JVM-testable; labels resolve to resources in the app layer. */
enum class ChartRange(val days: Long) {
    ONE_WEEK(7),
    ONE_MONTH(30),
    THREE_MONTHS(90),
    ONE_YEAR(365),
    ;

    /** ISO-8601 `since` floor for a "now" epoch-ms (inclusive of the range). */
    fun sinceIso(nowEpochMs: Long): String =
        Instant.ofEpochMilli(nowEpochMs - days * MILLIS_PER_DAY).toString()

    /** ISO-8601 `until` ceiling (now). */
    fun untilIso(nowEpochMs: Long): String = Instant.ofEpochMilli(nowEpochMs).toString()

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
