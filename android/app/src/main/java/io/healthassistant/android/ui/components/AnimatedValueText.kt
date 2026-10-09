package io.healthassistant.android.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import io.healthassistant.android.ui.theme.LocalReduceMotion

/** M9 polish: the animated number transition behind the Home `MetricCard`
 *  values and the biomarker detail's Latest reading — a short upward slide +
 *  fade to the changed value (≤ 300 ms), replaced by an instant swap when the
 *  user has reduce-motion on (the same [LocalReduceMotion] token that drives
 *  the nav transitions). */
@Composable
fun AnimatedValueText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    val duration = if (reduceMotion) 0 else VALUE_ANIMATION_MS
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            if (reduceMotion) {
                fadeIn(tween(0)) togetherWith fadeOut(tween(0))
            } else {
                (slideInVertically(tween(duration)) { it / 3 } + fadeIn(tween(duration))) togetherWith
                    (slideOutVertically(tween(duration)) { -it / 3 } + fadeOut(tween(duration)))
            }
        },
        modifier = modifier,
        label = "animated_value",
    ) { value ->
        Text(value, style = style, color = color)
    }
}

private const val VALUE_ANIMATION_MS = 200
