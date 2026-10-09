package io.healthassistant.android.widget

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.healthassistant.shared.healthconnect.HcType
import org.koin.core.context.GlobalContext

/**
 * M4 — the 4x2 "Live heart rate" showcase widget: the current heart rate
 * big, its in-range status, time-ago, and the last hour as an inline canvas
 * sparkline with the shaded reference band (deliberately NOT InsightsChart —
 * that Vico component is app-internal). Reads the ACTIVE connection's cache
 * only; refreshed on the sync cadence by [WidgetRefresher]. Tapping opens
 * the heart-rate biomarker detail screen.
 */
class LiveHeartRateWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val strings = AndroidWidgetStrings(context)
        val repository = GlobalContext.getOrNull()?.get<WidgetDataRepository>()
        val ui =
            repository?.heartRateUi(sinceMs = System.currentTimeMillis() - WINDOW_MS, maxSparkPoints = SPARK_POINTS)
                ?: WidgetStateMapper.heartRate(latest = null, series = emptyList(), strings = strings, maxSparkPoints = SPARK_POINTS)
        provideContent {
            GlanceTheme(WidgetTheme.colors) {
                val colors = GlanceTheme.colors
                val size = LocalSize.current
                val density = context.resources.displayMetrics.density
                val sparkDp = (size.height - CHROME_DP).value.coerceAtLeast(MIN_SPARK_DP)
                val sparkPxW = ((size.width - H_PADDING_DP).value * density).toInt().coerceAtLeast(1)
                val sparkPxH = (sparkDp * density).toInt().coerceAtLeast(1)
                val sparkline =
                    if (ui.sparkline.isNotEmpty()) {
                        WidgetGraphics.sparklineBitmap(
                            context = context,
                            widthPx = sparkPxW,
                            heightPx = sparkPxH,
                            values = ui.sparkline,
                            bandLow = ui.sparklineLow,
                            bandHigh = ui.sparklineHigh,
                            status = ui.status,
                        )
                    } else {
                        null
                    }
                Box(
                    GlanceModifier
                        .fillMaxSize()
                        .background(colors.surface)
                        .cornerRadius(16.dp)
                        .padding(12.dp)
                        .clickable(actionStartActivity(WidgetDeepLinks.biomarkerDetailIntent(context, HcType.HEART_RATE.code))),
                ) {
                    Column(GlanceModifier.fillMaxSize()) {
                        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                ui.title,
                                modifier = GlanceModifier.defaultWeight(),
                                maxLines = 1,
                                style = TextStyle(color = colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                            )
                            ui.statusText?.let {
                                Text(
                                    it,
                                    maxLines = 1,
                                    style = TextStyle(color = WidgetTheme.status(context, ui.status), fontSize = 12.sp),
                                )
                            }
                        }
                        Spacer(GlanceModifier.height(4.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                ui.valueText ?: strings.noData(),
                                maxLines = 1,
                                style =
                                    TextStyle(
                                        color = WidgetTheme.status(context, ui.status),
                                        fontSize = 46.sp,
                                        fontWeight = FontWeight.Bold,
                                    ),
                            )
                            if (!ui.unit.isNullOrBlank()) {
                                Text(
                                    " ${ui.unit}",
                                    maxLines = 1,
                                    style = TextStyle(color = colors.outline, fontSize = 14.sp),
                                )
                            }
                            Spacer(GlanceModifier.defaultWeight())
                            ui.timeText?.let {
                                Text(
                                    it,
                                    maxLines = 1,
                                    style = TextStyle(color = colors.outline, fontSize = 12.sp),
                                )
                            }
                        }
                        Spacer(GlanceModifier.height(6.dp))
                        sparkline?.let {
                            Image(
                                provider = ImageProvider(it),
                                contentDescription = sparklineA11y(ui.title, ui.valueText, ui.unit),
                                modifier = GlanceModifier.defaultWeight().fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val WINDOW_MS = 60L * 60 * 1000
        private const val SPARK_POINTS = 80
        private const val MIN_SPARK_DP = 24f
        private val H_PADDING_DP = 24.dp
        private val CHROME_DP = 110.dp
    }
}

/** The manifest entry for [LiveHeartRateWidget] (APPWIDGET_UPDATE → Glance). */
class LiveHeartRateWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LiveHeartRateWidget()
}

/** M9 a11y: the sparkline's spoken label — "Heart rate 72 bpm" — so the image
 *  reads as the current value, not just the metric name. */
private fun sparklineA11y(
    title: String,
    valueText: String?,
    unit: String?,
): String = listOfNotNull(title, valueText, unit?.takeIf { it.isNotBlank() }).joinToString(" ")
