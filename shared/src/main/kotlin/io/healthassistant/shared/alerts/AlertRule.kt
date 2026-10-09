package io.healthassistant.shared.alerts

import io.healthassistant.bridge.SyncPayload
import io.healthassistant.shared.data.ObservationCode
import io.healthassistant.shared.data.ObservationPoint
import io.healthassistant.shared.sync.OutboxItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The comparison an [AlertRule] applies to each new reading. */
enum class AlertOp { GT, LT, GE, LE, OUT_OF_RANGE }

/**
 * One local threshold rule (M5): "alert me when [biomarkerCode] is [op]
 * [threshold] for [timeWindowSec]". Pure-Kotlin + JVM-testable; persisted as
 * JSON by the Android `AlertRulesRepository`, evaluated by the `AlertEngine`
 * on every new cached or freshly synced reading. [biomarkerName] denormalizes
 * the display name at rule-build time so headless consumers (notifications)
 * never need the catalog.
 *
 * [evaluate] semantics:
 * - The time window anchors on the NEW reading's own time, so a freshly
 *   synced historical page evaluates against the history around it, not
 *   against wall-clock "now".
 * - `timeWindowSec == 0` breaches the instant one reading satisfies the
 *   condition. Otherwise the condition must hold for EVERY reading inside the
 *   window AND the covered evidence (anchor − oldest in-window reading) must
 *   span the full window — a lone reading that arrived a minute ago is not
 *   "above 120 for 5 minutes".
 * - `cooldownSec` suppresses re-firing: while `now − [lastFiredEpochMs]` is
 *   inside the cooldown a satisfied condition returns null. The caller
 *   persists `lastFiredEpochMs = Breach.firedEpochMs` after each fire, so the
 *   cooldown survives process death.
 */
@Serializable
data class AlertRule(
    val id: String,
    @SerialName("biomarker_code") val biomarkerCode: String,
    @SerialName("biomarker_name") val biomarkerName: String? = null,
    val op: AlertOp = AlertOp.GT,
    val threshold: Double? = null,
    @SerialName("range_low") val rangeLow: Double? = null,
    @SerialName("range_high") val rangeHigh: Double? = null,
    @SerialName("time_window_sec") val timeWindowSec: Long = 0,
    @SerialName("cooldown_sec") val cooldownSec: Long = DEFAULT_COOLDOWN_SEC,
    val enabled: Boolean = true,
    @SerialName("last_fired_epoch_ms") val lastFiredEpochMs: Long? = null,
) {
    /** True when [value] satisfies the rule's comparison. */
    fun holdsFor(value: Double): Boolean =
        when (op) {
            AlertOp.GT -> threshold != null && value > threshold
            AlertOp.LT -> threshold != null && value < threshold
            AlertOp.GE -> threshold != null && value >= threshold
            AlertOp.LE -> threshold != null && value <= threshold
            AlertOp.OUT_OF_RANGE -> rangeLow != null && rangeHigh != null && (value < rangeLow || value > rangeHigh)
        }

    /** True when the op has the bounds it needs and the durations are sane. */
    fun isValid(): Boolean =
        biomarkerCode.isNotBlank() &&
            timeWindowSec >= 0 &&
            cooldownSec >= 0 &&
            when (op) {
                AlertOp.GT, AlertOp.LT, AlertOp.GE, AlertOp.LE -> threshold != null
                AlertOp.OUT_OF_RANGE -> rangeLow != null && rangeHigh != null && rangeLow < rangeHigh
            }

    /**
     * Evaluate the rule against one new [point] with the cached [history] for
     * the same biomarker. Returns the [Breach] to notify, or null when the
     * rule is disabled/invalid, the point carries no numeric value or
     * parseable time, the condition fails, the window is not fully covered,
     * or the cooldown suppresses the fire.
     */
    fun evaluate(
        point: ObservationPoint,
        history: List<ObservationPoint>,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Breach? {
        if (!enabled || !isValid()) return null
        val value = point.chartValue ?: return null
        val anchorMs = point.effectiveDatetime?.toEpochMsOrNull() ?: return null
        if (!holdsFor(value)) return null
        val lastFired = lastFiredEpochMs
        if (lastFired != null && cooldownSec > 0 && nowEpochMs - lastFired < cooldownSec * MILLIS_PER_SEC) return null
        if (timeWindowSec > 0) {
            val windowMs = timeWindowSec * MILLIS_PER_SEC
            val samples =
                (history + point)
                    .mapNotNull { p ->
                        val v = p.chartValue ?: return@mapNotNull null
                        val t = p.effectiveDatetime?.toEpochMsOrNull() ?: return@mapNotNull null
                        v to t
                    }.filter { (_, t) -> t in (anchorMs - windowMs)..anchorMs }
            val oldest = samples.minOfOrNull { it.second } ?: return null
            if (anchorMs - oldest < windowMs) return null
            if (samples.any { !holdsFor(it.first) }) return null
        }
        return Breach(
            ruleId = id,
            biomarkerCode = biomarkerCode,
            value = value,
            readingEpochMs = anchorMs,
            firedEpochMs = nowEpochMs,
        )
    }

    companion object {
        /** Default re-fire suppression: one notification per 30 minutes. */
        const val DEFAULT_COOLDOWN_SEC = 30L * 60L
    }
}

private const val MILLIS_PER_SEC = 1_000L

/** A rule that just fired — what the engine turns into a notification. */
@Serializable
data class Breach(
    val ruleId: String,
    @SerialName("biomarker_code") val biomarkerCode: String,
    val value: Double,
    @SerialName("reading_epoch_ms") val readingEpochMs: Long,
    @SerialName("fired_epoch_ms") val firedEpochMs: Long,
)

/** Best-effort ISO-8601 → epoch ms: full instants and date-only strings. */
internal fun String.toEpochMsOrNull(): Long? {
    runCatching { return Instant.parse(this).toEpochMilli() }
    runCatching {
        return LocalDate
            .parse(take(10))
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
    }
    return null
}

/**
 * Maps one pushed `/sync` outbox item to the synthetic [ObservationPoint] the
 * alert engine evaluates (the push pipeline's `onSyncedItems` hook hands raw
 * items to the engine). Non-`/sync` items, multi-record payloads, and records
 * without a biomarker code map to null. Pure-Kotlin + JVM-testable.
 */
fun outboxItemToPoint(item: OutboxItem): ObservationPoint? {
    if (item.method != "POST" || item.path != "/sync") return null
    val record =
        runCatching {
            JSON.decodeFromString(SyncPayload.serializer(), item.payload.decodeToString()).records?.firstOrNull()
        }.getOrNull() ?: return null
    val code = record.code ?: return null
    return ObservationPoint(
        id = item.id,
        effectiveDatetime = record.timestamp,
        rawValue = record.value,
        normalizedUnit = record.unit,
        code =
            ObservationCode(
                coding = listOf(ObservationCode.Coding(code = code, system = record.codingSystem, display = record.name)),
                text = record.name,
            ),
    )
}

private val JSON = Json { ignoreUnknownKeys = true }

