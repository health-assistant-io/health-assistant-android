package io.healthassistant.android.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing tokens — the single source of truth for paddings/gaps so no screen
 * owns magic `dp` literals. Use via `MaterialTheme` extension:
 *
 * ```
 * Modifier.padding(MaterialTheme.spacing.md)
 * ```
 */
data class HASpacing(
    val none: Dp = 0.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 24.dp,
    val xl: Dp = 32.dp,
)

val LocalHASpacing = staticCompositionLocalOf { HASpacing() }
