package io.healthassistant.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug-build-only adb entry point (see the debug AndroidManifest). Translates
 * broadcast extras into [DebugDriver] commands; AppRoot executes them.
 */
class DebugDriverReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.getStringExtra("cmd")) {
            "nav" -> {
                val dest = intent.getStringExtra("dest") ?: return
                android.util.Log.i("DebugDriver", "recv nav $dest")
                DebugDriver.emit(DebugCommand.Navigate(dest, intent.getBooleanExtra("popToStart", false)))
            }
            "back" -> {
                android.util.Log.i("DebugDriver", "recv back")
                DebugDriver.emit(DebugCommand.Back)
            }
            "mode" -> {
                android.util.Log.i("DebugDriver", "recv mode ${intent.getStringExtra("arg")}")
                DebugDriver.emit(DebugCommand.SetMode(intent.getStringExtra("arg") == "simple"))
            }
            "sync" -> {
                android.util.Log.i("DebugDriver", "recv sync")
                DebugDriver.emit(DebugCommand.SyncNow)
            }
            "reading" -> {
                val code = intent.getStringExtra("code") ?: return
                val value = intent.getStringExtra("value")?.toDoubleOrNull()
                if (value != null) {
                    DebugDriver.emit(
                        DebugCommand.Reading(code, value, intent.getLongExtra("daysAgo", 0)),
                    )
                }
            }
            "hctypes" -> {
                if (intent.getStringExtra("arg") == "all") {
                    DebugDriver.emit(DebugCommand.EnableAllHcTypes)
                }
            }
        }
    }
}
