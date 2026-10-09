package io.healthassistant.android.data

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Phase C — the SQLCipher passphrase for the on-device Room databases
 * (observation cache + outbox). Generated once (32 random bytes), persisted in
 * `EncryptedSharedPreferences` whose values are sealed by a Keystore-backed
 * [MasterKey] (AES-256-GCM, no user-auth requirement — the app-lock gates app
 * entry, not each DB query). The same passphrase encrypts both DBs.
 *
 * The passphrase never leaves the Keystore envelope: EncryptedSharedPreferences
 * decrypts it in-memory on read, and it's handed straight to SQLCipher. A fresh
 * install generates a new passphrase; the DBs are created empty under it.
 */
object DatabaseKeyProvider {
    private const val PREFS = "ha_db_key"
    private const val KEY = "sqlcipher_passphrase"

    /** The raw SQLCipher passphrase, generating + persisting it on first call. */
    fun passphrase(context: Context): ByteArray {
        val prefs = encryptedPrefs(context)
        val existing = prefs.getString(KEY, null)
        if (existing != null) {
            return Base64.decode(existing, Base64.NO_WRAP)
        }
        val generated = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY, Base64.encodeToString(generated, Base64.NO_WRAP)).apply()
        return generated
    }

    private fun encryptedPrefs(context: Context) =
        runCatching {
            val masterKey =
                MasterKey
                    .Builder(context, "ha_db_master_key")
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse {
            // Fallback: plain SharedPreferences (only if Keystore is unavailable,
            // e.g. a corrupted Keystore on an emulator). The DBs still encrypt;
            // only the passphrase-at-rest protection degrades. Never throws.
            context.getSharedPreferences("${PREFS}_plain", Context.MODE_PRIVATE)
        }
}
