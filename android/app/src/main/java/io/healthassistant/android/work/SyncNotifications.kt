package io.healthassistant.android.work

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.healthassistant.android.R

/** Local sync notifications (R8: friendly copy). No FCM — posted from the
 *  [SyncWorker] when items dead-letter or a sync pass succeeds. POST_NOTIFICATIONS
 *  is declared in the manifest; on Android 13+ a runtime grant is needed, so
 *  posts are best-effort. */
object SyncNotifications {
    private const val CHANNEL_ID = "ha_sync"
    private const val NOTIF_ID_DEAD = 1001
    private const val NOTIF_ID_SUCCESS = 1002

    fun createChannel(context: Context) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
                    .apply { description = context.getString(R.string.notif_channel_desc) },
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun postDeadLetters(
        context: Context,
        count: Int,
    ) {
        if (count <= 0) return
        val notif =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(context.getString(R.string.notif_dead_title))
                .setContentText(context.getString(R.string.notif_dead_body))
                .setAutoCancel(true)
                .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID_DEAD, notif) }
    }

    @SuppressLint("MissingPermission")
    fun postSyncSuccess(
        context: Context,
        synced: Int,
    ) {
        if (synced <= 0) return
        val notif =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(context.getString(R.string.notif_success_title))
                .setContentText(context.getString(R.string.notif_success_body, synced))
                .setAutoCancel(true)
                .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID_SUCCESS, notif) }
    }

    /** Phase G — post a pull-derived change on the given [channelId] (one of the
     *  [io.healthassistant.android.HAApplication] channel ids). Best-effort: the
     *  POST_NOTIFICATIONS runtime grant may be absent on Android 13+. */
    @SuppressLint("MissingPermission")
    fun postChangeResult(
        context: Context,
        channelId: String,
        title: String,
        text: String,
        notificationId: Int,
    ) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(channelId) == null) return
        val notif =
            NotificationCompat
                .Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build()
        runCatching { NotificationManagerCompat.from(context).notify(notificationId, notif) }
    }
}
