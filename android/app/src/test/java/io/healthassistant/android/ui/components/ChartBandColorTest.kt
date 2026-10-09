package io.healthassistant.android.ui.components

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import io.healthassistant.android.ui.theme.HAAmoledColors
import io.healthassistant.android.ui.theme.HADarkColors
import io.healthassistant.android.ui.theme.HAHighContrastDark
import io.healthassistant.android.ui.theme.HAHighContrastLight
import io.healthassistant.android.ui.theme.HALightColors
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M9 dynamic-color gate: the chart reference-range band must stay a visible
 * tint under Material You recoloring (API 31+). Robolectric cannot read the
 * system wallpaper palettes, so the dynamic schemes are synthesized from
 * realistic tonal-palette pairs (tonal-40 primaries on light surfaces,
 * tonal-80 on dark) across the hues that historically degrade worst
 * (pale yellows). The status colors the charts/cards tint with (error,
 * tertiary) are verified to keep ≥ 3:1 over their surface.
 */
@RunWith(RobolectricTestRunner::class)
class ChartBandColorTest {
    @Test
    fun branded_schemes_keep_a_visible_band() {
        listOf(
            HALightColors,
            HADarkColors,
            HAAmoledColors,
            HAHighContrastLight,
            HAHighContrastDark,
            io.healthassistant.android.ui.theme.AuroraLightColors,
            io.healthassistant.android.ui.theme.AuroraDarkColors,
        ).forEach(::assertBandVisible)
    }

    @Test
    fun material_you_like_schemes_keep_a_visible_band() {
        materialYouLikeSchemes().forEach(::assertBandVisible)
    }

    @Test
    fun band_always_stays_a_background_tint() {
        (listOf(HALightColors, HADarkColors) + materialYouLikeSchemes()).forEach { scheme ->
            val alpha = referenceBandColor(scheme).alpha
            assertTrue("band alpha $alpha exceeds the background cap", alpha <= 0.24f)
        }
    }

    @Test
    fun status_colors_stay_readable_over_their_surfaces() {
        (listOf(HALightColors, HADarkColors, HAAmoledColors) + materialYouLikeSchemes()).forEach { scheme ->
            assertTrue(
                "error below 3:1 over surface",
                contrastOver(scheme.error, scheme.surface) >= 3f,
            )
            assertTrue(
                "tertiary below 3:1 over surface",
                contrastOver(scheme.tertiary, scheme.surface) >= 3f,
            )
        }
    }

    private fun assertBandVisible(scheme: ColorScheme) {
        val band = referenceBandColor(scheme)
        val ratio = bandContrast(scheme.primary, band.alpha, scheme.surface)
        assertTrue("band contrast $ratio not visible for primary ${scheme.primary}", ratio >= 1.1f)
    }

    private fun materialYouLikeSchemes(): List<ColorScheme> =
        listOf(
            lightColorScheme(
                primary = Color(0xFF7B6300),
                tertiary = Color(0xFF6B5777),
                error = Color(0xFFBA1A1A),
                surface = Color(0xFFFFF8E7),
            ),
            darkColorScheme(
                primary = Color(0xFFE3C648),
                tertiary = Color(0xFFD3BCE4),
                error = Color(0xFFFFB4AB),
                surface = Color(0xFF1D1B00),
            ),
            lightColorScheme(
                primary = Color(0xFF00687A),
                tertiary = Color(0xFF715573),
                error = Color(0xFFBA1A1A),
                surface = Color(0xFFF0FAFF),
            ),
            darkColorScheme(
                primary = Color(0xFF4FD8EB),
                tertiary = Color(0xFFDDB9E8),
                error = Color(0xFFFFB4AB),
                surface = Color(0xFF001F26),
            ),
            lightColorScheme(
                primary = Color(0xFF9C4146),
                tertiary = Color(0xFF7A5900),
                error = Color(0xFFBA1A1A),
                surface = Color(0xFFFFF8F7),
            ),
            darkColorScheme(
                primary = Color(0xFFFFB3B4),
                tertiary = Color(0xFFFFE08D),
                error = Color(0xFFFFB4AB),
                surface = Color(0xFF1F1111),
            ),
        )
}
