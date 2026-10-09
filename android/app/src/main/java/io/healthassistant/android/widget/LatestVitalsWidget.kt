package io.healthassistant.android.widget

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.healthassistant.android.R
import org.koin.core.context.GlobalContext

/**
 * M4 — the 2x2 "Latest vitals" home-screen widget: a stack of the newest
 * MetricCard-style rows (name · value · unit · status), read from the active
 * connection's observation cache — no network, no app launch. Tapping a row
 * opens the biomarker detail screen via [WidgetDeepLinks]. Refreshed by
 * [WidgetRefresher] on the sync cadence; renders the empty state while the
 * cache has no readings.
 */
class LatestVitalsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val strings = AndroidWidgetStrings(context)
        val repository = GlobalContext.getOrNull()?.get<WidgetDataRepository>()
        val ui = repository?.latestVitalsUi(MAX_ROWS) ?: LatestVitalsWidgetUi(emptyList(), null)
        val title = context.getString(R.string.widget_latest_vitals_label)
        provideContent {
            val rows = if (LocalSize.current.height < FOUR_ROWS_MIN_HEIGHT) ui.rows.dropLast(1) else ui.rows
            GlanceTheme(WidgetTheme.colors) {
                val colors = GlanceTheme.colors
                Box(
                    GlanceModifier
                        .fillMaxSize()
                        .background(colors.surface)
                        .cornerRadius(16.dp)
                        .padding(12.dp),
                ) {
                    if (rows.isEmpty()) {
                        Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(strings.noData(), style = TextStyle(color = colors.outline, fontSize = 13.sp))
                        }
                    } else {
                        Column(GlanceModifier.fillMaxSize()) {
                            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    title,
                                    modifier = GlanceModifier.defaultWeight(),
                                    maxLines = 1,
                                    style = TextStyle(color = colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                                )
                                Text(
                                    ui.headerTimeText ?: "",
                                    maxLines = 1,
                                    style = TextStyle(color = colors.outline, fontSize = 11.sp),
                                )
                            }
                            Spacer(GlanceModifier.height(4.dp))
                            rows.forEachIndexed { index, row ->
                                if (index > 0) Spacer(GlanceModifier.height(4.dp))
                                Row(
                                    GlanceModifier
                                        .fillMaxWidth()
                                        .defaultWeight()
                                        .semantics { contentDescription = rowA11y(row) }
                                        .clickable(actionStartActivity(WidgetDeepLinks.biomarkerDetailIntent(context, row.code))),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        row.name,
                                        modifier = GlanceModifier.defaultWeight().padding(end = 6.dp),
                                        maxLines = 1,
                                        style = TextStyle(color = colors.onSurface, fontSize = 13.sp),
                                    )
                                    Text(
                                        row.valueText,
                                        maxLines = 1,
                                        style =
                                            TextStyle(
                                                color = WidgetTheme.status(context, row.status),
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                            ),
                                    )
                                    if (!row.unit.isNullOrBlank()) {
                                        Text(
                                            " ${row.unit}",
                                            maxLines = 1,
                                            style = TextStyle(color = colors.outline, fontSize = 11.sp),
                                        )
                                    }
                                    if (row.statusText != null) {
                                        Text(
                                            " · ${row.statusText}",
                                            maxLines = 1,
                                            style = TextStyle(color = WidgetTheme.status(context, row.status), fontSize = 11.sp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val MAX_ROWS = 4

        val FOUR_ROWS_MIN_HEIGHT = 160.dp
    }
}

/** M9 a11y: one row's spoken label — "Heart rate 72 bpm High" — composed
 *  from the already-localized row pieces. */
private fun rowA11y(row: WidgetMetricRow): String =
    listOfNotNull(
        row.name,
        row.valueText,
        row.unit?.takeIf { it.isNotBlank() },
        row.statusText,
    ).joinToString(" ")

/** The manifest entry for [LatestVitalsWidget] (APPWIDGET_UPDATE → Glance). */
class LatestVitalsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LatestVitalsWidget()
}
