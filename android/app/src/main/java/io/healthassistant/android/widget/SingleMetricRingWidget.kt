package io.healthassistant.android.widget

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
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
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import org.koin.core.context.GlobalContext
import kotlin.math.min

/**
 * M4 — the 2x1 "Single metric" home-screen widget: one configurable
 * biomarker with its latest value, reference range and a value-vs-range
 * progress ring (a canvas bitmap — Glance has no determinate circular
 * indicator). The target biomarker is chosen when the widget is placed
 * ([SingleMetricRingWidgetConfigActivity]) and stored per-instance in the
 * Glance Preferences state; tapping opens the biomarker detail screen.
 */
class SingleMetricRingWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val strings = AndroidWidgetStrings(context)
        val repository = GlobalContext.getOrNull()?.get<WidgetDataRepository>()
        val prefs: Preferences = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val code = prefs[CODE_KEY]
        val ui = code?.let { repository?.ringUi(it) } ?: RingWidgetUi("", "", null, null, null, null, null, null, null)
        provideContent {
            GlanceTheme(WidgetTheme.colors) {
                val colors = GlanceTheme.colors
                val density = context.resources.displayMetrics.density
                val boxHeightDp = (LocalSize.current.height - CONTENT_PADDING).value
                val ringDp = min(boxHeightDp, RING_MAX_DP.value).coerceAtLeast(RING_MIN_DP.value)
                val ringPx = (ringDp * density).toInt().coerceAtLeast(1)
                val shell =
                    GlanceModifier
                        .fillMaxSize()
                        .background(colors.surface)
                        .cornerRadius(16.dp)
                        .padding(12.dp)
                val tapShell =
                    if (code != null) {
                        shell.clickable(actionStartActivity(WidgetDeepLinks.biomarkerDetailIntent(context, code)))
                    } else {
                        shell
                    }
                Box(tapShell) {
                    Row(
                        GlanceModifier
                            .fillMaxSize()
                            .padding(end = 8.dp)
                            .semantics { contentDescription = ringA11y(ui.name, ui.valueText, ui.unit, ui.statusText) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(
                            provider = ImageProvider(WidgetGraphics.ringBitmap(context, ringPx, ui.progress, ui.status)),
                            contentDescription = ui.rangeText ?: ui.valueText,
                            modifier = GlanceModifier.size(ringDp.dp),
                        )
                        Spacer(GlanceModifier.width(10.dp))
                        Column(GlanceModifier.fillMaxWidth()) {
                            Text(
                                ui.name.ifBlank { strings.noData() },
                                maxLines = 1,
                                style = TextStyle(color = colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                            )
                            if (ui.valueText != null) {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(
                                        ui.valueText,
                                        maxLines = 1,
                                        style =
                                            TextStyle(
                                                color = WidgetTheme.status(context, ui.status),
                                                fontSize = 26.sp,
                                                fontWeight = FontWeight.Bold,
                                            ),
                                    )
                                    if (!ui.unit.isNullOrBlank()) {
                                        Text(
                                            " ${ui.unit}",
                                            maxLines = 1,
                                            style = TextStyle(color = colors.outline, fontSize = 12.sp),
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    strings.noData(),
                                    maxLines = 1,
                                    style = TextStyle(color = colors.outline, fontSize = 14.sp),
                                )
                            }
                            if (ui.statusText != null) {
                                Text(
                                    "${ui.statusText}${if (ui.rangeText != null) " · " else ""}${ui.rangeText ?: ""}".trim(),
                                    maxLines = 1,
                                    style = TextStyle(color = WidgetTheme.status(context, ui.status), fontSize = 11.sp),
                                )
                            } else if (ui.rangeText != null) {
                                Text(ui.rangeText, maxLines = 1, style = TextStyle(color = colors.outline, fontSize = 11.sp))
                            }
                            ui.timeText?.let {
                                Text(it, maxLines = 1, style = TextStyle(color = colors.outline, fontSize = 11.sp))
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        val CODE_KEY = stringPreferencesKey("biomarker_code")

        private val CONTENT_PADDING = 24.dp

        private val RING_MAX_DP = 72.dp

        private val RING_MIN_DP = 40.dp
    }
}

/** The manifest entry for [SingleMetricRingWidget] (APPWIDGET_UPDATE → Glance). */
class SingleMetricRingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SingleMetricRingWidget()
}

/** M9 a11y: the widget's spoken label — "Weight 72 kg Normal" — composed from
 *  the already-localized state pieces. */
private fun ringA11y(
    name: String,
    valueText: String?,
    unit: String?,
    statusText: String?,
): String =
    listOfNotNull(
        name.takeIf { it.isNotBlank() },
        valueText,
        unit?.takeIf { it.isNotBlank() },
        statusText,
    ).joinToString(" ")
