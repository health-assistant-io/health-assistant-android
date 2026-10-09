package io.healthassistant.android.alerts

import io.healthassistant.android.R
import io.healthassistant.shared.alerts.AlertOp
import io.healthassistant.shared.alerts.AlertRule

/** Plain-language phrase pieces for an [AlertRule] — the rules UI and the
 *  breach notification build the same sentence from these resources, so the
 *  wording never drifts between the two. */
object AlertPhrases {
    /** A string resource + its format args (resolvable via `stringResource`
     *  or `Context.getString`). */
    data class Phrase(
        val res: Int,
        val args: List<Any> = emptyList(),
    )

    /** The comparison half: "above 120" / "outside 60–100" (null when the rule
     *  lost its bounds — callers skip unusable rules). */
    fun condition(
        rule: AlertRule,
        formatValue: (Double) -> String,
    ): Phrase? =
        when (rule.op) {
            AlertOp.GT -> rule.threshold?.let { Phrase(R.string.alerts_phrase_above, listOf(formatValue(it))) }
            AlertOp.LT -> rule.threshold?.let { Phrase(R.string.alerts_phrase_below, listOf(formatValue(it))) }
            AlertOp.GE -> rule.threshold?.let { Phrase(R.string.alerts_phrase_at_or_above, listOf(formatValue(it))) }
            AlertOp.LE -> rule.threshold?.let { Phrase(R.string.alerts_phrase_at_or_below, listOf(formatValue(it))) }
            AlertOp.OUT_OF_RANGE ->
                rule.rangeLow?.let { low ->
                    rule.rangeHigh?.let { high ->
                        Phrase(R.string.alerts_phrase_outside, listOf(formatValue(low), formatValue(high)))
                    }
                }
        }

    /** The sustained-for half: "for 5 minutes" (null for an instant rule). */
    fun duration(timeWindowSec: Long): Phrase? =
        when {
            timeWindowSec <= 0 -> null
            timeWindowSec < SECONDS_PER_MINUTE -> Phrase(R.string.alerts_phrase_for_one_minute)
            timeWindowSec < SECONDS_PER_HOUR -> Phrase(R.string.alerts_phrase_for_minutes, listOf(timeWindowSec / SECONDS_PER_MINUTE))
            timeWindowSec == SECONDS_PER_HOUR -> Phrase(R.string.alerts_phrase_for_one_hour)
            else -> Phrase(R.string.alerts_phrase_for_hours, listOf(timeWindowSec / SECONDS_PER_HOUR))
        }

    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3_600L
}
