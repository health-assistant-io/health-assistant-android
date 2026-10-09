package io.healthassistant.android.work

import android.content.Context
import org.unifiedpush.android.connector.UnifiedPush

/**
 * Phase I — kicks off UnifiedPush registration when a connection is active.
 * `UnifiedPush.register` asks the OS for the user's chosen distributor; the
 * distributor (an app like ntfy/Gotify) then broadcasts the endpoint back to
 * [MobilePushReceiver.onNewEndpoint], which registers it with the bridge.
 *
 * Idempotent: re-registering the same instance just refreshes the endpoint. If
 * no distributor is installed, the user is prompted to pick one (UnifiedPush's
 * default UX); if they decline, [MobilePushReceiver.onRegistrationFailed] fires
 * and the app keeps working without push. FCM is a separate build flavor (not
 * in this APK).
 */
object PushRegistration {
    private const val INSTANCE = "health-assistant"

    /** Ask the distributor for an endpoint (no-op until the user picks one). */
    fun register(context: Context) {
        runCatching {
            UnifiedPush.register(context, INSTANCE, "", "")
        }
    }

    /** Drop the endpoint (on disconnect / connection removal). */
    fun unregister(context: Context) {
        runCatching { UnifiedPush.unregister(context, INSTANCE) }
    }
}
