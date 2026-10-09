package io.healthassistant.android.data

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom

private val Context.appLockDataStore: DataStore<Preferences> by preferencesDataStore(name = "ha_app_lock")
private const val PIN_PREFS_FILE = "ha_app_lock_pin"

/**
 * Phase B.4 app-lock — the security gate that wraps the whole UI.
 *
 * State machine:
 * - When the user toggles "App lock" on, they set a 4-digit PIN as the
 *   fallback (biometrics, when enrolled, are the primary auth).
 * - `isLocked` flips to true on cold start (if enabled) AND when the app
 *   returns to the foreground after the grace window (default 60s) has
 *   elapsed since it was backgrounded.
 * - The UI observes [isLocked] and substitutes the lock screen for the
 *   main content while locked.
 *
 * Storage:
 * - The "enabled" flag + timestamps live in plain DataStore (no secret).
 * - The PIN hash lives in EncryptedSharedPreferences (Keystore-backed master
 *   key, AES-GCM values, AES256-SIV keys) — same envelope as
 *   [CredentialStore]. The PIN itself is never stored; we hash it with
 *   SHA-256 + a per-install random salt before persisting.
 *
 * Grace window: 60s default — short enough that a quick tab switch (copy a
 * code from Messages, return) doesn't re-prompt, long enough that turning
 * the screen off + back on does. Configurable via [graceMillis].
 */
class AppLockManager(
    context: Context,
    private val graceMillis: Long = 60_000L,
) {
    private val appContext = context.applicationContext
    private val store = appContext.appLockDataStore

    private val pinPrefs =
        EncryptedSharedPreferences.create(
            appContext,
            PIN_PREFS_FILE,
            MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    /** The current lock state — observed by the UI gate. True = show the
     *  lock screen; false = show the main app. */
    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    /** Whether the user has enabled app lock at all. False by default. */
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Whether a PIN has been set (the fallback for when biometrics fail or
     *  aren't enrolled). Drives the "set PIN" prompt in the Security UI. */
    private val _hasPin = MutableStateFlow(false)
    val hasPin: StateFlow<Boolean> = _hasPin.asStateFlow()

    /** Whether the device has biometrics enrolled (face/fingerprint). Polled
     *  lazily — the BiometricPrompt API is the real source of truth at prompt
     *  time, but this drives the "Set up PIN" copy ("required" vs "optional"). */
    private val _biometricsAvailable = MutableStateFlow(false)
    val biometricsAvailable: StateFlow<Boolean> = _biometricsAvailable.asStateFlow()

    init {
        // Seed the in-memory state from disk synchronously so the very first
        // composition sees the right value (avoids a flash of unlocked UI).
        val enabled = pinPrefs.getBoolean(KEY_ENABLED, false)
        _enabled.value = enabled
        _hasPin.value = pinPrefs.contains(KEY_PIN_HASH)
        _isLocked.value = enabled
    }

    // --------------------------------------------------------------------
    // Public API — UI calls these
    // --------------------------------------------------------------------

    /** Refresh the "biometrics enrolled" probe. Cheap; call on resume. */
    fun refreshBiometricsAvailability(canAuthenticate: Int) {
        _biometricsAvailable.value = canAuthenticate == 0
    }

    /** Enable the app lock + set the initial PIN (the required fallback). */
    suspend fun enable(pin: String) {
        require(pin.length == PIN_LENGTH && pin.all { it.isDigit() }) {
            "PIN must be $PIN_LENGTH digits."
        }
        ensureSalt()
        pinPrefs
            .edit()
            .apply {
                putString(KEY_PIN_HASH, hashPin(pin))
                putBoolean(KEY_ENABLED, true)
            }.apply()
        _hasPin.value = true
        _enabled.value = true
        _isLocked.value = true // lock immediately so the user sees it works
    }

    /** Disable the app lock + clear the stored PIN. */
    suspend fun disable() {
        pinPrefs
            .edit()
            .apply {
                remove(KEY_PIN_HASH)
                remove(KEY_SALT)
                putBoolean(KEY_ENABLED, false)
            }.apply()
        _hasPin.value = false
        _enabled.value = false
        _isLocked.value = false
    }

    /** Change the PIN (requires the lock to already be enabled). */
    suspend fun changePin(newPin: String) {
        require(newPin.length == PIN_LENGTH && newPin.all { it.isDigit() }) {
            "PIN must be $PIN_LENGTH digits."
        }
        ensureSalt()
        pinPrefs.edit().putString(KEY_PIN_HASH, hashPin(newPin)).apply()
    }

    /** Verify a PIN entry. Returns true on match. Does NOT auto-unlock — the
     *  caller calls [markUnlocked] on success so the lifecycle is explicit. */
    fun verifyPin(pin: String): Boolean {
        val stored = pinPrefs.getString(KEY_PIN_HASH, null) ?: return false
        return constantTimeEquals(hashPin(pin), stored)
    }

    /** Mark the app as authenticated. Called by AppLockScreen after a
     *  successful biometric prompt or PIN entry. */
    fun markUnlocked() {
        _isLocked.value = false
    }

    /** Force-lock now (Profile › Security › "Lock now"). */
    fun lockNow() {
        if (_enabled.value) _isLocked.value = true
    }

    // --------------------------------------------------------------------
    // Lifecycle hooks — called by the ProcessLifecycleOwner observer
    // --------------------------------------------------------------------

    /** Called when the app moves to the background. Records the timestamp so
     *  [onForeground] can decide whether the grace window has elapsed. */
    fun onBackgrounded() {
        if (!_enabled.value) return
        val now = System.currentTimeMillis()
        pinPrefs.edit().putLong(KEY_LAST_BG_MS, now).apply()
    }

    /** Called when the app returns to the foreground. Locks if the grace
     *  window has elapsed since [onBackgrounded]. */
    fun onForeground() {
        if (!_enabled.value) return
        val lastBg = pinPrefs.getLong(KEY_LAST_BG_MS, 0L)
        if (lastBg == 0L) {
            _isLocked.value = true
            return
        }
        val elapsed = System.currentTimeMillis() - lastBg
        if (elapsed >= graceMillis) _isLocked.value = true
    }

    // --------------------------------------------------------------------
    // Internals
    // --------------------------------------------------------------------

    private fun ensureSalt() {
        if (!pinPrefs.contains(KEY_SALT)) {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            pinPrefs.edit().putString(KEY_SALT, Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
        }
    }

    private fun hashPin(pin: String): String {
        val saltB64 = pinPrefs.getString(KEY_SALT, null) ?: ""
        val salt = Base64.decode(saltB64, Base64.NO_WRAP)
        val md = MessageDigest.getInstance("SHA-256")
        // Stretch: 10k rounds — slows a brute-force on a leaked hash.
        var digest = md.digest(salt + pin.toByteArray(Charsets.UTF_8))
        repeat(9_999) { digest = md.digest(digest) }
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    private fun constantTimeEquals(
        a: String,
        b: String,
    ): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private companion object {
        const val PIN_LENGTH = 4
        const val KEY_ENABLED = "app_lock_enabled"
        const val KEY_PIN_HASH = "pin_hash"
        const val KEY_SALT = "pin_salt"
        const val KEY_LAST_BG_MS = "last_backgrounded_ms"
    }
}
