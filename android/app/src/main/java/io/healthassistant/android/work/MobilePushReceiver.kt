package io.healthassistant.android.work

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.healthassistant.android.HAApplication
import io.healthassistant.android.data.CredentialStore
import io.healthassistant.android.data.PushDeviceStore
import io.healthassistant.bridge.BridgeClient
import io.healthassistant.bridge.DeviceRegistration
import org.unifiedpush.android.connector.MessagingReceiver
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Phase I — the UnifiedPush receiver. The user's chosen distributor app (ntfy,
 * Gotify, …) hands us an endpoint; we register it with the bridge so the
 * backend's notification fan-out (`POST /notifications/register-device`) reaches
 * this install. Incoming pushes are decoded (`{id, type, title, body, payload}`)
 * and posted on the matching channel.
 *
 * Lifecycle (all driven by the distributor via UnifiedPush):
 * - [onNewEndpoint] — persist the endpoint + `register-device` (upsert on
 *   `(user, device)`). Fires on first register + whenever the distributor
 *   rotates the endpoint.
 * - [onMessage] — decode the JSON envelope → notification on the channel for
 *   `type` (medication / examination / clinical_alert / anomaly / general).
 * - [onUnregistered] — the distributor dropped the endpoint → clear + DELETE.
 * - [onRegistrationFailed] — no distributor / user declined → log (the app
 *   keeps working, just without push).
 *
 * The receiver builds its own [BridgeClient] from the saved credential (it runs
 * with no UI). Needs a UnifiedPush distributor installed for real delivery —
 * without one, `UnifiedPush.register` is a no-op and this receiver is never called.
 */
class MobilePushReceiver : MessagingReceiver() {
    override fun onNewEndpoint(
        context: Context,
        endpoint: PushEndpoint,
        instance: String,
    ) {
        Log.i(TAG, "new endpoint from distributor (instance=$instance)")
        val store = PushDeviceStore(context)
        kotlinx.coroutines.runBlocking {
            val url = endpoint.url
            store.saveEndpoint(url)
            registerWithBridge(context, store, url)
        }
    }

    override fun onMessage(
        context: Context,
        message: PushMessage,
        instance: String,
    ) {
        val text = String(message.content, Charsets.UTF_8)
        Log.i(TAG, "push message (${message.content.size} bytes)")
        PushNotifications.postFromEnvelope(context, text)
    }

    override fun onUnregistered(
        context: Context,
        instance: String,
    ) {
        Log.i(TAG, "unregistered from distributor (instance=$instance)")
        val store = PushDeviceStore(context)
        kotlinx.coroutines.runBlocking {
            val credential = CredentialStore(context).load()
            val deviceId = store.deviceId()
            if (credential != null) {
                val client =
                    BridgeClient(
                        baseUrl = credential.baseUrl,
                        integrationId = credential.integrationId,
                        apiSecret = credential.apiSecret,
                    )
                runCatching { client.unregisterDevice(deviceId) }
            }
            store.clear()
        }
    }

    override fun onRegistrationFailed(
        context: Context,
        reason: org.unifiedpush.android.connector.FailedReason,
        instance: String,
    ) {
        Log.w(TAG, "registration failed: $reason (instance=$instance) — push disabled")
    }

    private suspend fun registerWithBridge(
        context: Context,
        store: PushDeviceStore,
        endpoint: String,
    ) {
        val credential = CredentialStore(context).load() ?: return
        val client =
            BridgeClient(
                baseUrl = credential.baseUrl,
                integrationId = credential.integrationId,
                apiSecret = credential.apiSecret,
            )
        val registration =
            DeviceRegistration(
                deviceId = store.deviceId(),
                platform = PLATFORM,
                endpointUrl = endpoint,
                appVersion = appVersion(context),
            )
        runCatching { client.registerDevice(registration) }
            .onFailure { Log.w(TAG, "register-device failed: ${it.message}") }
            .onSuccess { Log.i(TAG, "device registered with bridge") }
    }

    private fun appVersion(context: Context): String =
        runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName
                .orEmpty()
        }.getOrDefault("")

    private companion object {
        const val TAG = "HAMobilePush"
        const val PLATFORM = "unifiedpush"
    }
}

/** Phase I — decodes the backend's push envelope + posts a notification. */
object PushNotifications {
    private const val NOTIF_ID = 3000

    @SuppressLint("MissingPermission")
    fun postFromEnvelope(
        context: Context,
        rawJson: String,
    ) {
        val envelope =
            runCatching { org.json.JSONObject(rawJson) }.getOrNull() ?: run {
                Log.w("HAMobilePush", "push payload not JSON, ignoring")
                return
            }
        val title = envelope.optString("title").ifBlank { "Health Assistant" }
        val body = envelope.optString("body")
        val channel = channelFor(envelope.optString("type"))
        val notif =
            NotificationCompat
                .Builder(context, channel)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setPriority(
                    if (channel ==
                        HAApplication.CHANNEL_ANOMALY
                    ) {
                        NotificationCompat.PRIORITY_HIGH
                    } else {
                        NotificationCompat.PRIORITY_DEFAULT
                    },
                ).build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID + (title.hashCode() and 0x3ff), notif) }
    }

    /** Map the backend notification `type` → one of the Phase I channels. */
    private fun channelFor(type: String): String =
        when (type.lowercase()) {
            "medication", "medication_reminder" -> HAApplication.CHANNEL_MEDICATION
            "examination", "exam" -> HAApplication.CHANNEL_EXAMINATION
            "clinical_alert", "alert" -> HAApplication.CHANNEL_CLINICAL_ALERT
            "anomaly", "biomarker_anomaly" -> HAApplication.CHANNEL_ANOMALY
            else -> HAApplication.CHANNEL_GENERAL
        }
}
