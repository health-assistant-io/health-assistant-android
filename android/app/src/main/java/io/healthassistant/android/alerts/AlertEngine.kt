package io.healthassistant.android.alerts

import io.healthassistant.shared.alerts.AlertRule
import io.healthassistant.shared.alerts.Breach
import io.healthassistant.shared.alerts.outboxItemToPoint
import io.healthassistant.shared.data.ObservationPoint
import io.healthassistant.shared.data.cache.ObservationCache
import io.healthassistant.shared.sync.OutboxItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * M5 — evaluates enabled rules against new readings and hands each
 * [Breach] to [AlertNotifier] (which posts a notification). Pure Kotlin: the
 * collaborators are interfaces/flows, so the whole engine is JVM-testable
 * with fakes.
 *
 * Two trigger paths, both best-effort (an alerting failure must never break
 * the sync or UI path it rides on):
 * - [start] subscribes to the connection-scoped observation cache: after a
 *   baseline emission (existing rows are NOT re-alerted), every genuinely new
 *   latest-per-biomarker point is evaluated with its cached series as
 *   history. Covers local Health Connect reads, manual entries, and pull
 *   refreshes while the app is alive.
 * - [onSyncedItems] is wired into the push pipeline's
 *   `SyncCoordinator.Config.onSyncedItems` hook: freshly pushed pages are
 *   decoded (`outboxItemToPoint`) and evaluated with cached history, so
 *   alerts fire in the background too.
 *
 * Cooldown state persists through [persistFired] (the rules repository
 * stamps `lastFiredEpochMs`), so re-fires stay suppressed across process
 * death; concurrent evaluation paths are additionally de-duplicated in
 * memory per evaluate() call.
 */
class AlertEngine(
    private val cache: ObservationCache,
    private val rules: Flow<List<AlertRule>>,
    private val persistFired: suspend (id: String, firedAtEpochMs: Long) -> Unit,
    private val notifier: AlertNotifier,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Receives each fired breach (posts the notification). */
    fun interface AlertNotifier {
        fun onBreach(
            breach: Breach,
            rule: AlertRule,
        )
    }

    private val seen = mutableMapOf<String, String>()
    private var baselined = false
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job =
            scope.launch {
                combine(cache.latestPerBiomarker(), rules) { points, current -> points to current }
                    .collect { (points, current) -> runCatching { onLatest(points, current) } }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** The sync pipeline's `onSyncedItems` hook: evaluate a freshly pushed batch. */
    suspend fun onSyncedItems(items: List<OutboxItem>) {
        evaluate(items.mapNotNull(::outboxItemToPoint), rules.first())
    }

    internal suspend fun onLatest(
        points: List<ObservationPoint>,
        current: List<AlertRule>,
    ) {
        val latestByCode = points.mapNotNull { p -> p.primaryCode?.let { it to p } }.toMap()
        if (!baselined) {
            baselined = true
            seen.putAll(latestByCode.mapValues { it.value.id })
            return
        }
        val fresh = latestByCode.filter { (code, p) -> seen[code] != p.id }
        seen.putAll(latestByCode.mapValues { it.value.id })
        if (fresh.isNotEmpty()) evaluate(fresh.values.toList(), current)
    }

    internal suspend fun evaluate(
        points: List<ObservationPoint>,
        current: List<AlertRule>,
    ) {
        val live = current.filter { it.enabled && it.isValid() }.associateBy { it.id }.toMutableMap()
        if (live.isEmpty()) return
        for (point in points) {
            val code = point.primaryCode ?: continue
            val matching = live.values.filter { it.biomarkerCode == code }
            if (matching.isEmpty()) continue
            val history = historyFor(code, point, matching)
            for (rule in matching) {
                val breach = rule.evaluate(point, history, now()) ?: continue
                live[rule.id] = rule.copy(lastFiredEpochMs = breach.firedEpochMs)
                runCatching { persistFired(rule.id, breach.firedEpochMs) }
                runCatching { notifier.onBreach(breach, rule) }
            }
        }
    }

    private suspend fun historyFor(
        code: String,
        point: ObservationPoint,
        matching: List<AlertRule>,
    ): List<ObservationPoint> {
        val windowMs = matching.maxOf { it.timeWindowSec } * MILLIS_PER_SEC
        if (windowMs <= 0) return emptyList()
        val anchorMs = point.effectiveDatetime?.let(::parseEpochMs) ?: return emptyList()
        return cache.seriesFor(code, anchorMs - windowMs, anchorMs).first()
    }

    private companion object {
        const val MILLIS_PER_SEC = 1_000L

        fun parseEpochMs(iso: String): Long? =
            runCatching {
                java.time.Instant
                    .parse(iso)
                    .toEpochMilli()
            }.getOrNull()
    }
}
