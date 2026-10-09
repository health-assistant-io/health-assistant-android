package io.healthassistant.android.web

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Joins a web-app origin with an in-app path, or `null` when no origin is
 * known. `"https://host/"` + `"ai-assistant"` → `"https://host/ai-assistant"`.
 */
fun webAppUrl(
    origin: String?,
    path: String = "",
): String? {
    if (origin.isNullOrBlank()) return null
    val base = origin.trim().trimEnd('/')
    if (base.startsWith("http").not()) return null
    if (path.isBlank()) return base
    return "$base/${path.trimStart('/')}"
}

/**
 * Opens web-app URLs in a Chrome Custom Tab (warmed via [warmUp]), falling
 * back to a plain browser view intent when no Custom Tabs provider answers.
 * The app never holds the web session — login happens in the browser.
 */
object WebAppLauncher {
    private const val WEB_THEME_COLOR = 0xFF3B82F6.toInt()

    fun warmUp(context: Context) {
        runCatching {
            val browserPackage = CustomTabsClient.getPackageName(context, emptyList()) ?: return
            CustomTabsClient.connectAndInitialize(context, browserPackage)
        }
    }

    fun launch(
        context: Context,
        url: String,
    ) {
        val uri = Uri.parse(url)
        val customTabsLaunched =
            runCatching {
                CustomTabsIntent
                    .Builder()
                    .setShowTitle(true)
                    .setDefaultColorSchemeParams(
                        CustomTabColorSchemeParams
                            .Builder()
                            .setToolbarColor(WEB_THEME_COLOR)
                            .build(),
                    ).build()
                    .launchUrl(context, uri)
            }.isSuccess
        if (!customTabsLaunched) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        }
    }
}
