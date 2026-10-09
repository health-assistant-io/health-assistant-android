package io.healthassistant.android.widget

import android.content.Context
import io.healthassistant.android.R

/** M4 (widgets) — [WidgetStrings] from the app's string resources (the same
 * keys the MetricCard uses for status + time-ago), so the pure mapper's output
 * stays locale-correct inside widgets. */
class AndroidWidgetStrings(
    private val context: Context,
) : WidgetStrings {
    override fun justNow(): String = context.getString(R.string.time_just_now)

    override fun minutesAgo(count: Long): String = context.getString(R.string.time_min_ago, count)

    override fun hoursAgo(count: Long): String = context.getString(R.string.time_hour_ago, count)

    override fun daysAgo(count: Long): String = context.getString(R.string.time_day_ago, count)

    override fun noData(): String = context.getString(R.string.widget_no_data)

    override fun statusHigh(): String = context.getString(R.string.status_high)

    override fun statusLow(): String = context.getString(R.string.status_low)

    override fun statusNormal(): String = context.getString(R.string.status_normal)

    override fun referenceRange(
        low: String,
        high: String,
    ): String = context.getString(R.string.home_reference_range, low, high)

    override fun heartRate(): String = context.getString(R.string.widget_hr_title)
}
