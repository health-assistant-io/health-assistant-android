package io.healthassistant.android.data

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * Phase A documents + user-requested enhancement: opens a document the bridge
 * served as raw bytes, AND remembers the user's last-chosen viewer per MIME
 * type so subsequent opens of the same file type skip the chooser dialog
 * entirely (instant, like a native default-app association).
 *
 * The remember-mechanism works via:
 * 1. SharedPreferences (`mime → packageName`).
 * 2. On open: if a saved package exists + is still installed → launch directly
 *    with `intent.setPackage(savedPkg)` (no chooser, instant).
 * 3. If no saved package → show the system chooser with a `PendingIntent`
 *    callback (`createChooser(intent, title, intentSender)` — API 22+). When
 *    the user picks an app, the callback fires with
 *    `Intent.EXTRA_CHOSEN_COMPONENT`; we extract the package name + save it.
 * 4. Next open of the same MIME type → the saved package launches directly.
 *
 * The user never configures anything — the first PDF open shows the chooser;
 * every subsequent PDF open goes straight to the previously-picked viewer.
 */
object DocumentOpener {
    private const val CACHE_DIR = "documents"
    private const val PREFS_NAME = "ha_doc_viewer_prefs"
    private const val KEY_PREFIX = "viewer_"
    private const val ACTION_VIEWER_CHOSEN = "io.healthassistant.android.VIEWER_CHOSEN"
    private const val EXTRA_MIME = "mime"

    /** Write [bytes] to the documents cache + return the cache file. */
    fun cacheBytes(
        context: Context,
        docId: String,
        bytes: ByteArray,
        filename: String?,
    ): File {
        val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        val safeName = sanitizeFilename(filename) ?: "$docId.bin"
        val file = File(dir, safeName)
        file.writeBytes(bytes)
        return file
    }

    /**
     * Launch a document viewer for [file]. This is the single entry point —
     * it handles the saved-viewer lookup, direct launch, chooser fallback,
     * and choice-capture all internally.
     *
     * Returns a [LaunchResult] so the caller can surface the right UX:
     * - [LaunchResult.Launched] — a viewer opened (either directly or via chooser).
     * - [LaunchResult.NoViewer] — no app on the device handles this MIME type.
     */
    fun launch(
        context: Context,
        file: File,
        contentType: String?,
    ): LaunchResult {
        val mime = contentType ?: guessContentType(file.name) ?: "*/*"
        val baseIntent = buildBaseIntent(context, file, mime) ?: return LaunchResult.NoViewer

        // Check if there's a saved preference for this MIME type.
        val savedPkg =
            context
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_PREFIX + mime, null)

        if (savedPkg != null) {
            // Try to launch directly with the saved package — instant, no chooser.
            val directIntent = Intent(baseIntent).apply { setPackage(savedPkg) }
            val resolved = context.packageManager.resolveActivity(directIntent, PackageManager.MATCH_ALL)
            if (resolved != null) {
                return try {
                    context.startActivity(
                        directIntent.apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                    LaunchResult.Launched
                } catch (e: ActivityNotFoundException) {
                    // Package was uninstalled — clear the stale preference + fall through.
                    clearViewer(context, mime)
                    showChooserWithCallback(context, baseIntent, mime)
                }
            } else {
                clearViewer(context, mime)
            }
        }

        return showChooserWithCallback(context, baseIntent, mime)
    }

    /** Show the system chooser with a PendingIntent callback that captures
     *  the user's selection + saves it for next time. */
    private fun showChooserWithCallback(
        context: Context,
        baseIntent: Intent,
        mime: String,
    ): LaunchResult {
        // Register a one-shot receiver to capture the user's choice.
        var receiverRegistered = true
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context?,
                    intent: Intent?,
                ) {
                    val chosen =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent?.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent?.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT) as? ComponentName
                        }
                    if (chosen != null && ctx != null) {
                        saveViewer(ctx, mime, chosen.packageName)
                    }
                    try {
                        ctx?.unregisterReceiver(this)
                    } catch (_: Exception) {
                        // Already unregistered — ignore.
                    }
                    receiverRegistered = false
                }
            }

        // Build the PendingIntent that the chooser will fire when the user picks.
        val callbackIntent =
            Intent(ACTION_VIEWER_CHOSEN).apply {
                setPackage(context.packageName) // deliver only to our own app
                putExtra(EXTRA_MIME, mime)
            }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val pendingIntent =
            PendingIntent.getBroadcast(
                context,
                mime.hashCode(), // unique requestCode per MIME type
                callbackIntent,
                flags,
            )

        // Register the receiver dynamically. RECEIVER_NOT_EXPORTED: the matching
        // broadcast is package-scoped above, so it never crosses the app boundary.
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_VIEWER_CHOSEN),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // Build + fire the chooser.
        val chooserIntent =
            Intent.createChooser(baseIntent, null, pendingIntent.intentSender).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        return try {
            context.startActivity(chooserIntent)
            LaunchResult.Launched
        } catch (e: ActivityNotFoundException) {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
            LaunchResult.NoViewer
        }
    }

    private fun buildBaseIntent(
        context: Context,
        file: File,
        mime: String,
    ): Intent? {
        val authority = "${context.packageName}.fileprovider"
        val uri: Uri =
            try {
                FileProvider.getUriForFile(context, authority, file)
            } catch (e: IllegalArgumentException) {
                return null
            }
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        // Verify at least one activity can handle this MIME.
        val candidates = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        if (candidates.isEmpty()) return null
        return intent
    }

    private fun saveViewer(
        context: Context,
        mime: String,
        pkg: String,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREFIX + mime, pkg)
            .apply()
    }

    private fun clearViewer(
        context: Context,
        mime: String,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PREFIX + mime)
            .apply()
    }

    /** Best-effort MIME guess from a filename extension. */
    fun guessContentType(filename: String): String? {
        val ext = filename.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        if (ext.isEmpty()) return null
        return android.webkit.MimeTypeMap
            .getSingleton()
            .getMimeTypeFromExtension(ext)
    }

    private fun sanitizeFilename(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val base = name.substringAfterLast('/').trim()
        if (base.isEmpty()) return null
        return base.map { c -> if (c.isLetterOrDigit() || c in ".-_ ") c else '_' }.joinToString("").take(120)
    }

    /** Result of a [launch] call. */
    sealed interface LaunchResult {
        /** A viewer was launched (directly or via the system chooser). */
        data object Launched : LaunchResult

        /** No app on the device can handle the file's MIME type. */
        data object NoViewer : LaunchResult
    }
}
