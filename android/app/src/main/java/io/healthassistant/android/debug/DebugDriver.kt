package io.healthassistant.android.debug

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Debug-only command channel driven over adb by
 * `am broadcast -a io.healthassistant.DEBUG_DRIVE --es cmd <cmd> [--es arg <arg>]`
 * (receiver registered in the debug manifest only — release builds never wire
 * it). Exists because MIUI blocks shell input injection (INJECT_EVENTS) and
 * background activity starts from instrumentation, so on-device verification
 * drives the app through its own code: real navController, real repositories.
 */
object DebugDriver {
    val commands: SharedFlow<DebugCommand> =
        MutableSharedFlow(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun emit(command: DebugCommand) {
        (commands as MutableSharedFlow).tryEmit(command)
    }
}

sealed interface DebugCommand {
    data class Navigate(
        val route: String,
        val popToStart: Boolean = false,
    ) : DebugCommand

    data object Back : DebugCommand

    data class SetMode(
        val simple: Boolean,
    ) : DebugCommand

    data object SyncNow : DebugCommand

    data class Reading(
        val code: String,
        val value: Double,
        val daysAgo: Long = 0,
    ) : DebugCommand

    data object EnableAllHcTypes : DebugCommand
}
