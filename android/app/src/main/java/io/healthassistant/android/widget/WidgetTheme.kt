package io.healthassistant.android.widget

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.glance.color.ColorProviders
import androidx.glance.unit.ColorProvider
import io.healthassistant.android.ui.theme.HADarkColors
import io.healthassistant.android.ui.theme.HALightColors
import io.healthassistant.android.ui.theme.HealthAlertDark
import io.healthassistant.android.ui.theme.HealthAlertLight
import io.healthassistant.android.ui.theme.HealthGoodDark
import io.healthassistant.android.ui.theme.HealthGoodLight
import io.healthassistant.android.ui.theme.HealthWatchDark
import io.healthassistant.android.ui.theme.HealthWatchLight
import io.healthassistant.shared.data.RangeStatus

/**
 * M4 (widgets) — the Glance color mapping for the in-app design tokens
 * (`ui/theme/`). The material roles ride a day/night [ColorProviders] built
 * from the exact `HALightColors`/`HADarkColors` schemes the Compose UI uses
 * (wrapped in `GlanceTheme` at the widget root, resolved by the launcher);
 * the health-semantic status colors and the canvas renderers' argb values
 * resolve the same tokens against the current night mode per render. No
 * widget-local hex values.
 */
object WidgetTheme {
    /** The day/night Glance theme from the app's Material3 schemes. */
    val colors: ColorProviders = androidx.glance.material3.ColorProviders(HALightColors, HADarkColors)

    fun status(
        context: Context,
        status: RangeStatus?,
    ): ColorProvider =
        ColorProvider(
            when (status) {
                RangeStatus.NORMAL -> pick(context, HealthGoodLight, HealthGoodDark)
                RangeStatus.HIGH -> pick(context, HealthAlertLight, HealthAlertDark)
                RangeStatus.LOW -> pick(context, HealthWatchLight, HealthWatchDark)
                null -> pick(context, HALightColors.onSurface, HADarkColors.onSurface)
            },
        )

    fun statusArgb(
        context: Context,
        status: RangeStatus?,
    ): Int = status(context, status).getColor(context).toArgb()

    fun primaryArgb(context: Context): Int = pick(context, HALightColors.primary, HADarkColors.primary).toArgb()

    fun trackArgb(context: Context): Int = pick(context, HALightColors.surfaceVariant, HADarkColors.surfaceVariant).toArgb()

    fun onSurfaceArgb(context: Context): Int = pick(context, HALightColors.onSurface, HADarkColors.onSurface).toArgb()

    fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun pick(
        context: Context,
        day: Color,
        night: Color,
    ): Color = if (isNight(context)) night else day
}
