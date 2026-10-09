package io.healthassistant.android.ui.components

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

/**
 * M9 dynamic-color refinement: the chart reference-range band fill. Under
 * Material You (API 31+) the system recolors `primary` to any wallpaper hue —
 * some (pale yellows on light surfaces) carry so little luminance difference
 * that the old fixed 10 % alpha band all but disappears. The alpha therefore
 * adapts per scheme: it starts at the subtle baseline and steps up until the
 * band composited over the surface is perceptibly distinct, capped so the
 * band always stays a background tint rather than a competing surface.
 */
fun referenceBandColor(scheme: ColorScheme): Color {
    var alpha = BASE_BAND_ALPHA
    while (alpha < MAX_BAND_ALPHA && bandContrast(scheme.primary, alpha, scheme.surface) < MIN_BAND_CONTRAST) {
        alpha += BAND_ALPHA_STEP
    }
    return scheme.primary.copy(alpha = alpha)
}

/** WCAG-style contrast ratio (1.0 = identical) between the [alpha]-tinted
 *  [primary] composited over [surface] and the bare [surface]. */
fun bandContrast(
    primary: Color,
    alpha: Float,
    surface: Color,
): Float {
    val band = primary.copy(alpha = alpha).compositeOver(surface)
    val l1 = band.luminance() + 0.05
    val l2 = surface.luminance() + 0.05
    return (max(l1, l2) / min(l1, l2)).toFloat()
}

/** Contrast of a full-strength foreground against [surface] (status colors). */
fun contrastOver(
    foreground: Color,
    surface: Color,
): Float {
    val l1 = foreground.luminance() + 0.05
    val l2 = surface.luminance() + 0.05
    return (max(l1, l2) / min(l1, l2)).toFloat()
}

private const val BASE_BAND_ALPHA = 0.10f
private const val BAND_ALPHA_STEP = 0.02f
private const val MAX_BAND_ALPHA = 0.24f
private const val MIN_BAND_CONTRAST = 1.1f
