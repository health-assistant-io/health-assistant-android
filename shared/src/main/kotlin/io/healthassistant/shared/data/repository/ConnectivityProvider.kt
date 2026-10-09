package io.healthassistant.shared.data.repository

/**
 * Minimal connectivity probe so a Single-Source-of-Truth repository can short-
 * circuit a network refresh when the device is known-offline (avoids a
 * pointless dial-up timeout). Pure-Kotlin so it lives in the KMP `shared` core
 * and is JVM-testable with a lambda fake; the Android implementation
 * (`ConnectivityRepository`, ConnectivityManager-backed) is bound in Koin.
 */
fun interface ConnectivityProvider {
    fun isOnline(): Boolean
}

/**
 * Outcome of a repository refresh. Drives staleness/retry UX without forcing
 * the UI to handle thrown exceptions.
 *
 * - [REFRESHED] — network call succeeded; the cache was updated (UI re-renders
 *   automatically from the reactive cache `Flow`).
 * - [UP_TO_DATE] — refresh was skipped (throttled / no-op).
 * - [OFFLINE] — device is offline; the cache was left untouched, so the UI keeps
 *   showing the saved snapshot. Not an error.
 * - [FAILED] — network call failed (e.g. 5xx, timeout, auth); the cache was left
 *   untouched. Surface a retry affordance when the cache is empty.
 */
enum class RefreshOutcome { REFRESHED, UP_TO_DATE, OFFLINE, FAILED }
