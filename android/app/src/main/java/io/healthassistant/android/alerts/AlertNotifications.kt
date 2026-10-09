package io.healthassistant.android.alerts

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.healthassistant.android.R
import io.healthassistant.android.ui.components.alertFireHaptic
import io.healthassistant.android.ui.components.formatValue
import io.healthassistant.shared.alerts.AlertRule
import io.healthassistant.shared.alerts.Breach
import io.healthassistant.shared.healthconnect.HcType

/** The M5 `ha_alert` channel + breach notifications, following the
 *  [io.healthassistant.android.work.SyncNotifications] pattern: POST_NOTIFICATIONS
 *  is declared in the manifest; on Android 13+ a runtime grant is needed, so
 *  posts are best-effort. One stable notification id per rule — a re-fire
 *  replaces the previous notification instead of stacking. */
object AlertNotifications {
    const val CHANNEL_ID = "ha_alert"
    private const val NOTIF_ID_BASE = 3000
    private const val NOTIF_ID_SPREAD = 100_000

    fun createChannel(context: Context) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.alert_channel_name), NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = context.getString(R.string.alert_channel_desc) },
            )
        }
    }

    fun notificationId(ruleId: String): Int = NOTIF_ID_BASE + Math.floorMod(ruleId.hashCode(), NOTIF_ID_SPREAD)

    @SuppressLint("MissingPermission")
    fun postBreach(
        context: Context,
        title: String,
        text: String,
        ruleId: String,
    ) {
        val notif =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
        runCatching { NotificationManagerCompat.from(context).notify(notificationId(ruleId), notif) }
    }
}

/** The engine's notification side: renders a breach in plain language —
 *  "Heart rate is above 120 for 5 minutes. Latest reading: 135 bpm." — using
 *  the rule's denormalized name (catalog name at rule-build time) with the
 *  HcType table as fallback. */
class AndroidAlertNotifier(
    private val context: Context,
) : AlertEngine.AlertNotifier {
    override fun onBreach(
        breach: Breach,
        rule: AlertRule,
    ) {
        val condition = AlertPhrases.condition(rule, ::formatValue) ?: return
        val duration = AlertPhrases.duration(rule.timeWindowSec)
        val conditionText =
            joinNotNull(
                context.getString(condition.res, *condition.args.toTypedArray()),
                duration?.let { context.getString(it.res, *it.args.toTypedArray()) },
            )
        val type = HcType.byCode(breach.biomarkerCode)
        val latest =
            joinNotNull(
                formatValue(breach.value),
                type?.defaultUnit,
            )
        alertFireHaptic(context)
        AlertNotifications.postBreach(
            context,
            title = context.getString(R.string.alert_notif_title),
            text = context.getString(R.string.alert_notif_body, displayName(breach, rule), conditionText, latest),
            ruleId = rule.id,
        )
    }

    private fun displayName(
        breach: Breach,
        rule: AlertRule,
    ): String = rule.biomarkerName ?: HcType.byCode(breach.biomarkerCode)?.display ?: breach.biomarkerCode

    private fun joinNotNull(
        first: String,
        second: String?,
    ): String = if (second == null) first else "$first $second"
}
