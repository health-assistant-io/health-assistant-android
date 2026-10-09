package io.healthassistant.android.ui.theme

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Branded light scheme — calm clinical teal on warm neutrals. */
val HALightColors: ColorScheme =
    lightColorScheme(
        primary = Teal40,
        onPrimary = Color.White,
        primaryContainer = Teal90,
        onPrimaryContainer = Teal10,
        secondary = Teal30,
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFB4ECE8),
        onSecondaryContainer = Teal10,
        tertiary = Coral40,
        onTertiary = Color.White,
        tertiaryContainer = Coral90,
        onTertiaryContainer = Color(0xFF3F0414),
        error = Red40,
        onError = Color.White,
        errorContainer = Red90,
        onErrorContainer = Color(0xFF410E0B),
        background = NeutralWarm99,
        onBackground = NeutralWarm10,
        surface = NeutralWarm99,
        onSurface = NeutralWarm10,
        surfaceVariant = NeutralWarm96,
        onSurfaceVariant = NeutralWarm40,
        outline = NeutralWarm60,
        outlineVariant = NeutralWarm90,
        surfaceTint = Teal40,
        scrim = Color.Black,
    )

/** Branded dark scheme. */
val HADarkColors: ColorScheme =
    darkColorScheme(
        primary = Teal80,
        onPrimary = Teal20,
        primaryContainer = Teal30,
        onPrimaryContainer = Teal90,
        secondary = Teal80,
        onSecondary = Teal20,
        secondaryContainer = Teal30,
        onSecondaryContainer = Color(0xFFB4ECE8),
        tertiary = Coral80,
        onTertiary = Color(0xFF5F1414),
        tertiaryContainer = Coral40,
        onTertiaryContainer = Coral90,
        error = Red80,
        onError = Color(0xFF690005),
        errorContainer = Red40,
        onErrorContainer = Red90,
        background = NeutralDark99,
        onBackground = Color(0xFFE1E3E0),
        surface = NeutralDark99,
        onSurface = Color(0xFFE1E3E0),
        surfaceVariant = NeutralDark95,
        onSurfaceVariant = Color(0xFFBEC9C4),
        outline = Color(0xFF899390),
        outlineVariant = NeutralDark90,
        surfaceTint = Teal80,
        scrim = Color.Black,
    )

/** AMOLED variant — true-black surfaces for OLED battery + a bolder contrast. */
val HAAmoledColors: ColorScheme =
    HADarkColors.copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceVariant = Color(0xFF101413),
        surfaceTint = Teal80,
    )

/** High-contrast variant — stronger text/outline colors for low-vision users. */
val HAHighContrastLight: ColorScheme =
    HALightColors.copy(
        onSurface = Color.Black,
        onBackground = Color.Black,
        outline = Color(0xFF333333),
        onSurfaceVariant = Color(0xFF222222),
        primary = Teal30,
    )

val HAHighContrastDark: ColorScheme =
    HADarkColors.copy(
        background = Color.Black,
        surface = Color.Black,
        onSurface = Color.White,
        onBackground = Color.White,
        outline = Color(0xFFE6E6E6),
        onSurfaceVariant = Color(0xFFE6E6E6),
        primary = Teal80,
    )

/** Whether the user has disabled animation at the OS level (Developer Options). */
@Suppress("DEPRECATION")
fun Context.reduceMotion(): Boolean {
    val scale = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    return scale == 0f
}

val LocalReduceMotion = staticCompositionLocalOf { false }
val LocalHighContrast = compositionLocalOf { false }
val LocalDarkTheme = compositionLocalOf { false }

/** Named color presets (Appearance, Profile). [AURORA] is the default since
 *  v1.3 — one periwinkle-indigo family; [TEAL] is the original clinical
 *  teal/coral scheme; [MATERIAL_YOU] follows the wallpaper on Android 12+
 *  (falls back to Aurora below 12). */
enum class ThemePreset {
    AURORA,
    TEAL,
    MATERIAL_YOU,
}

/** The schemes per preset; amoled/high-contrast derive from the selected
 *  base so every preset supports the accessibility variants. */
private object ThemeSchemes {
    fun light(preset: ThemePreset): ColorScheme =
        when (preset) {
            ThemePreset.TEAL -> HALightColors
            else -> AuroraLightColors
        }

    fun dark(preset: ThemePreset): ColorScheme =
        when (preset) {
            ThemePreset.TEAL -> HADarkColors
            else -> AuroraDarkColors
        }
}

val AuroraLightColors: ColorScheme =
    lightColorScheme(
        primary = AuroraPrimaryLight,
        onPrimary = Color.White,
        primaryContainer = AuroraPrimaryContainerLight,
        onPrimaryContainer = AuroraOnPrimaryContainerLight,
        secondary = AuroraSecondaryLight,
        onSecondary = Color.White,
        secondaryContainer = AuroraSecondaryContainerLight,
        onSecondaryContainer = AuroraOnSecondaryContainerLight,
        tertiary = AuroraTertiaryLight,
        onTertiary = Color.White,
        tertiaryContainer = AuroraTertiaryContainerLight,
        onTertiaryContainer = AuroraOnTertiaryContainerLight,
        error = Red40,
        onError = Color.White,
        errorContainer = Red90,
        onErrorContainer = Color(0xFF410E0B),
        background = AuroraSurfaceLight,
        onBackground = AuroraOnSurfaceLight,
        surface = AuroraSurfaceLight,
        onSurface = AuroraOnSurfaceLight,
        surfaceVariant = AuroraSurfaceVariantLight,
        onSurfaceVariant = AuroraOnSurfaceVariantLight,
        outline = AuroraOutlineLight,
        outlineVariant = AuroraOutlineVariantLight,
        surfaceTint = AuroraPrimaryLight,
        scrim = Color.Black,
    )

val AuroraDarkColors: ColorScheme =
    darkColorScheme(
        primary = AuroraPrimaryDark,
        onPrimary = AuroraOnPrimaryDark,
        primaryContainer = AuroraPrimaryContainerDark,
        onPrimaryContainer = AuroraOnPrimaryContainerDark,
        secondary = AuroraSecondaryDark,
        onSecondary = Color(0xFF1A1B23),
        secondaryContainer = AuroraSecondaryContainerDark,
        onSecondaryContainer = AuroraOnSecondaryContainerDark,
        tertiary = AuroraTertiaryDark,
        onTertiary = Color(0xFF3E1D68),
        tertiaryContainer = AuroraTertiaryContainerDark,
        onTertiaryContainer = AuroraOnTertiaryContainerDark,
        error = Red80,
        onError = Color(0xFF690005),
        errorContainer = Red40,
        onErrorContainer = Red90,
        background = AuroraSurfaceDark,
        onBackground = AuroraOnSurfaceDark,
        surface = AuroraSurfaceDark,
        onSurface = AuroraOnSurfaceDark,
        surfaceVariant = AuroraSurfaceVariantDark,
        onSurfaceVariant = AuroraOnSurfaceVariantDark,
        outline = AuroraOutlineDark,
        outlineVariant = AuroraOutlineVariantDark,
        surfaceTint = AuroraPrimaryDark,
        scrim = Color.Black,
    )

/** Theme entry point. Honors dark mode, the color preset (Appearance,
 *  Profile), dynamic color via the [ThemePreset.MATERIAL_YOU] preset,
 *  AMOLED, high-contrast, and reduce-motion. [reduceMotion] lets the user
 *  force the reduce-motion value (Profile › Accessibility); null falls back
 *  to the OS Developer-Options setting. */
@Composable
fun HATheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    preset: ThemePreset = ThemePreset.AURORA,
    amoled: Boolean = false,
    highContrast: Boolean = false,
    reduceMotion: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemReduceMotion = remember { context.reduceMotion() }
    val effectiveReduceMotion = reduceMotion ?: systemReduceMotion

    val base =
        if (darkTheme) {
            ThemeSchemes.dark(preset)
        } else {
            ThemeSchemes.light(preset)
        }
    val colorScheme =
        when {
            preset == ThemePreset.MATERIAL_YOU && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            highContrast && darkTheme ->
                base.copy(
                    background = Color.Black,
                    surface = Color.Black,
                    onSurface = Color.White,
                    onBackground = Color.White,
                    outline = Color(0xFFE6E6E6),
                    onSurfaceVariant = Color(0xFFE6E6E6),
                )
            highContrast ->
                base.copy(
                    onSurface = Color.Black,
                    onBackground = Color.Black,
                    outline = Color(0xFF333333),
                    onSurfaceVariant = Color(0xFF222222),
                )
            amoled && darkTheme ->
                base.copy(
                    background = Color.Black,
                    surface = Color.Black,
                    surfaceVariant = Color(0xFF101014),
                )
            else -> base
        }

    CompositionLocalProvider(
        LocalReduceMotion provides effectiveReduceMotion,
        LocalHighContrast provides highContrast,
        LocalDarkTheme provides darkTheme,
        LocalHASpacing provides HASpacing(),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = HATypography,
            shapes = HAShapes,
            content = content,
        )
    }
}

/** Convenience accessor for spacing tokens: `MaterialTheme.spacing.md`. */
val MaterialTheme.spacing: HASpacing
    @Composable
    @ReadOnlyComposable
    get() = LocalHASpacing.current

/** Health semantic colors resolved to the current scheme. */
object HAHealthColors {
    val good: Color
        @Composable get() = if (LocalDarkTheme.current) HealthGoodDark else HealthGoodLight
    val watch: Color
        @Composable get() = if (LocalDarkTheme.current) HealthWatchDark else HealthWatchLight
    val alert: Color
        @Composable get() = if (LocalDarkTheme.current) HealthAlertDark else HealthAlertLight
    val syncActive: Color
        @Composable get() = if (LocalDarkTheme.current) SyncActiveDark else SyncActiveLight
    val syncIdle: Color
        @Composable get() = if (LocalDarkTheme.current) SyncIdleDark else SyncIdleLight
}
