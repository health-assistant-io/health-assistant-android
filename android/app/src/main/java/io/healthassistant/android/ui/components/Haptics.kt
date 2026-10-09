package io.healthassistant.android.ui.components

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/** R8: a brief confirmation haptic for discrete actions (sync, toggles, upload
 *  success/failure). Returns a plain `() -> Unit` so it can be invoked from
 *  callbacks and coroutines (not just composable lambdas). No-op in tests /
 *  where haptics are disabled. */
@Composable
fun rememberActionHaptic(): () -> Unit = rememberHaptic(HapticFeedbackType.LongPress)

/** M9: [rememberActionHaptic]'s parameterized form — one haptic per gesture
 *  kind, still a plain `() -> Unit` for callback use. The chart marker tap
 *  passes [HapticFeedbackType.ContextClick] to read as "selected a value",
 *  not "confirmed an action". */
@Composable
fun rememberHaptic(type: HapticFeedbackType): () -> Unit {
    val haptic = LocalHapticFeedback.current
    return remember(haptic, type) {
        { haptic.performHapticFeedback(type) }
    }
}

/** M9: the threshold-breach fire haptic — a notification-style double tick
 *  from the alert notifier's non-Compose context (a `rememberHaptic` cannot
 *  reach there; this is the same feedback a system notification produces).
 *  Best-effort: silently skipped on devices without a vibrator. */
fun alertFireHaptic(context: Context) {
    runCatching {
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createWaveform(ALERT_HAPTIC_PATTERN, -1))
    }
}

private val ALERT_HAPTIC_PATTERN = longArrayOf(0, 40, 80, 40)
