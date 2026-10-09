package io.healthassistant.android.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.healthassistant.shared.onboarding.ConnectionCredential
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores one or more bridge connections (multiple patients) in
 * [EncryptedSharedPreferences], with one marked active. The dashboard's
 * switcher lists them; onboarding a new QR adds it and makes it active
 * (plan §2.1 / §8 "multi-connection is free").
 */
class CredentialStore(
    context: Context,
) {
    private val prefs =
        EncryptedSharedPreferences.create(
            context,
            FILENAME,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    /** The active connection, or null if none. */
    fun load(): ConnectionCredential? {
        val all = loadAll()
        val activeId = prefs.getString(KEY_ACTIVE, null)
        return all.firstOrNull { it.integrationId == activeId } ?: all.firstOrNull()
    }

    /** All saved connections (active first). */
    fun loadAll(): List<ConnectionCredential> {
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { idx ->
                val o = arr.getJSONObject(idx)
                ConnectionCredential(
                    baseUrl = o.getString("base_url"),
                    integrationId = o.getString("integration_id"),
                    apiSecret = o.optString("api_secret").takeIf { it.isNotBlank() },
                    frontendBaseUrl = o.optString("frontend_base_url").takeIf { it.isNotBlank() },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Add (or replace by integrationId) and mark active. */
    fun save(credential: ConnectionCredential) {
        val list = (loadAll().filterNot { it.integrationId == credential.integrationId } + credential)
        prefs
            .edit()
            .putString(KEY_LIST, encode(list))
            .putString(KEY_ACTIVE, credential.integrationId)
            .apply()
    }

    fun setActive(integrationId: String) {
        prefs.edit().putString(KEY_ACTIVE, integrationId).apply()
    }

    fun remove(integrationId: String) {
        val remaining = loadAll().filterNot { it.integrationId == integrationId }
        val ed = prefs.edit().putString(KEY_LIST, encode(remaining))
        if (prefs.getString(KEY_ACTIVE, null) == integrationId) {
            ed.putString(KEY_ACTIVE, remaining.firstOrNull()?.integrationId)
        }
        ed.apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun encode(list: List<ConnectionCredential>): String {
        val arr = JSONArray()
        for (c in list) {
            val o = JSONObject()
            o.put("base_url", c.baseUrl)
            o.put("integration_id", c.integrationId)
            c.apiSecret?.let { o.put("api_secret", it) }
            c.frontendBaseUrl?.let { o.put("frontend_base_url", it) }
            arr.put(o)
        }
        return arr.toString()
    }

    private companion object {
        const val FILENAME = "ha_connection"
        const val KEY_LIST = "connections"
        const val KEY_ACTIVE = "active_integration_id"
    }
}
