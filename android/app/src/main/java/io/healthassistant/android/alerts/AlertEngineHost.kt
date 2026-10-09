package io.healthassistant.android.alerts

import io.healthassistant.android.data.cache.ObservationDatabase
import io.healthassistant.android.data.cache.RoomCaches
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * M5 — owns the foreground [AlertEngine] lifecycle, one engine per ACTIVE
 * bridge connection (the observation cache is connection-scoped, so switching
 * patients swaps the whole alert scope too). AppRoot calls [start]/[stop] on
 * connection changes; [workerEngine] hands background workers (the push
 * pipeline's `onSyncedItems` hook) a one-shot engine for their connection
 * without subscribing to the cache.
 */
class AlertEngineHost(
    private val db: ObservationDatabase,
    private val rulesRepository: AlertRulesRepository,
    private val notifier: AlertEngine.AlertNotifier,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private var connectionId: String? = null
    private var engine: AlertEngine? = null

    @Synchronized
    fun start(connectionId: String) {
        if (connectionId == this.connectionId && engine != null) return
        stopInternal()
        this.connectionId = connectionId
        engine = build(connectionId).also { it.start() }
    }

    @Synchronized
    fun stop() {
        stopInternal()
        connectionId = null
    }

    fun workerEngine(connectionId: String): AlertEngine = build(connectionId)

    private fun stopInternal() {
        engine?.stop()
        engine = null
    }

    private fun build(connectionId: String) =
        AlertEngine(
            cache = RoomCaches(db, connectionId).observations,
            rules = rulesRepository.rules,
            persistFired = { id, at -> rulesRepository.markFired(id, at) },
            notifier = notifier,
            scope = scope,
        )
}
