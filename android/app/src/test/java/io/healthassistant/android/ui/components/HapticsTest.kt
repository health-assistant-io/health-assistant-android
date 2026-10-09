package io.healthassistant.android.ui.components

import android.content.Context
import android.os.Vibrator
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** M9 haptics gate: the alert-fire haptic is best-effort — it must drive the
 *  vibrator when one exists and stay silent (not throw) when none does. */
@RunWith(RobolectricTestRunner::class)
class HapticsTest {
    @Test
    fun alert_fire_haptic_vibrates_the_default_vibrator() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vibrator = context.getSystemService(Vibrator::class.java)
        vibrator?.let { shadowOf(it).setHasVibrator(true) }

        alertFireHaptic(context)
    }

    @Test
    fun alert_fire_haptic_is_a_no_op_without_a_vibrator() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSystemService(Vibrator::class.java)?.let { shadowOf(it).setHasVibrator(false) }

        alertFireHaptic(context)
    }
}
