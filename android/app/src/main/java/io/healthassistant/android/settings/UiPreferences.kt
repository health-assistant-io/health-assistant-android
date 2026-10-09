package io.healthassistant.android.settings

/**
 * The app-wide disclosure level (K-simple-mode). One app, two surfaces:
 * [SIMPLE] keeps the daily essentials front and center, [ADVANCED] unlocks
 * every feature. Instant, re-changeable — never a restart.
 */
enum class UiMode {
    SIMPLE,
    ADVANCED,
}

/**
 * The app-wide color preset (Appearance, Profile). Mirrors
 * [io.healthassistant.android.ui.theme.ThemePreset]; declared here so the
 * settings layer stays theme-implementation-free.
 */
enum class UiTheme {
    AURORA,
    TEAL,
    MATERIAL_YOU,
}

/**
 * Appearance / accessibility preferences the user can override from Profile
 * (R6 + K-simple-mode). Backed by DataStore via [UiPreferencesRepository].
 * These ride on top of the OS settings: [highContrast] and [reduceMotion]
 * switch the theme scheme; [mode] is the app-wide Simple/Advanced level (the
 * dashboard's per-view "Simple" is the separate
 * [io.healthassistant.shared.data.HomeViewStyle] in DashboardPrefsRepository).
 */
data class UiPreferences(
    val highContrast: Boolean = false,
    val reduceMotion: Boolean = false,
    val mode: UiMode = UiMode.SIMPLE,
    val theme: UiTheme = UiTheme.AURORA,
)
