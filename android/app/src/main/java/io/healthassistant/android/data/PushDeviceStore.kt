package io.healthassistant.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.util.UUID

private val Context.pushDeviceDataStore: DataStore<Preferences> by preferencesDataStore(name = "ha_push_device")

/**
 * Phase I — the per-install push identity + the last UnifiedPush endpoint the
 * user's chosen distributor handed us. The [deviceId] is a stable client UUID
 * (so re-registering with a new distributor endpoint upserts the same server
 * row instead of stacking duplicates). The [endpoint] is remembered so the
 * receiver can detect "the distributor gave a new endpoint" and re-register.
 */
class PushDeviceStore(
    context: Context,
) {
    private val store = context.applicationContext.pushDeviceDataStore

    suspend fun deviceId(): String {
        val current = store.data.first()[DEVICE_ID]
        if (current != null) return current
        val generated = UUID.randomUUID().toString()
        store.edit { it[DEVICE_ID] = generated }
        return generated
    }

    suspend fun endpoint(): String? = store.data.first()[ENDPOINT]

    suspend fun saveEndpoint(endpoint: String) {
        store.edit { it[ENDPOINT] = endpoint }
    }

    suspend fun clear() {
        store.edit {
            it.remove(ENDPOINT)
        }
    }

    private companion object {
        val DEVICE_ID = stringPreferencesKey("device_id")
        val ENDPOINT = stringPreferencesKey("endpoint_url")
    }
}
