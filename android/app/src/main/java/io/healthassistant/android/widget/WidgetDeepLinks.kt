package io.healthassistant.android.widget

import android.content.Context
import android.content.Intent
import io.healthassistant.android.MainActivity
import io.healthassistant.android.ui.navigation.HARoutes

/**
 * M4 (widgets) — how a widget tap re-enters the app: an explicit
 * [MainActivity] intent carrying the destination route in [EXTRA_OPEN_ROUTE]
 * (SINGLE_TOP so a tap while the app is open reuses the running task and
 * fires `onNewIntent` instead of stacking a second activity).
 */
object WidgetDeepLinks {
    const val EXTRA_OPEN_ROUTE: String = "io.healthassistant.android.extra.OPEN_ROUTE"

    /** Launch intent into the biomarker detail screen for [code]. */
    fun biomarkerDetailIntent(
        context: Context,
        code: String,
    ): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_ROUTE, HARoutes.biomarkerDetail(code))
        }
}
