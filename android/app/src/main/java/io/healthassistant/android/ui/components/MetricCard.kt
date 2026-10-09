package io.healthassistant.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.healthassistant.android.R
import io.healthassistant.shared.data.BiomarkerReading
import io.healthassistant.shared.data.RangeStatus
import java.time.Instant

/** Reusable health-metric card: icon, display name, latest value + unit, and
 *  time-ago. Works for any [BiomarkerReading] — Health Connect types and
 *  instance-only lab biomarkers alike. Shows a "No data yet" placeholder when
 *  the biomarker has no reading. When [onClick] is provided the card acts as a
 *  button (e.g. open its chart). [large] is the R8 "Simple mode" variant:
 *  bigger value text for low-vision / elderly users. */
@Composable
fun MetricCard(
    reading: BiomarkerReading,
    onClick: (() -> Unit)? = null,
    large: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val cardModifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    Card(modifier = cardModifier, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(if (large) 20.dp else 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = biomarkerIcon(reading.hcType),
                    contentDescription = null,
                    modifier = Modifier.size(if (large) 28.dp else 22.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(reading.displayName, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.size(if (large) 16.dp else 12.dp))
            val valueText = reading.valueString ?: reading.value?.let(::formatValue)
            if (valueText != null) {
                val statusColor =
                    when (reading.rangeStatus) {
                        RangeStatus.HIGH -> MaterialTheme.colorScheme.error
                        RangeStatus.LOW -> MaterialTheme.colorScheme.tertiary
                        RangeStatus.NORMAL -> MaterialTheme.colorScheme.onSurface
                        null -> MaterialTheme.colorScheme.onSurface
                    }
                Row(verticalAlignment = Alignment.Bottom) {
                    AnimatedValueText(
                        text = valueText,
                        style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
                        color = statusColor,
                    )
                    if (reading.rangeStatus != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(
                                when (reading.rangeStatus) {
                                    RangeStatus.HIGH -> R.string.status_high
                                    RangeStatus.LOW -> R.string.status_low
                                    RangeStatus.NORMAL -> R.string.status_normal
                                    null -> R.string.status_normal
                                },
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                }
                Text(
                    reading.unit ?: "",
                    style = if (large) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            } else {
                Text(
                    "—",
                    style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Text(
                    stringResource(R.string.home_no_data_yet),
                    style = if (large) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.size(8.dp))
            reading.referenceRange?.takeIf { it.present }?.let { range ->
                Text(
                    stringResource(
                        R.string.home_reference_range,
                        range.low?.let(::formatValue) ?: "—",
                        range.high?.let(::formatValue) ?: "—",
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            reading.timestamp?.let {
                Text(
                    relativeTime(it),
                    style = if (large) MaterialTheme.typography.bodySmall else MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** Formats a Double value the health-app way: drop the decimal when integral. */
fun formatValue(v: Double): String = if (v == v.toInt().toDouble()) v.toInt().toString() else "%.1f".format(v)

/** "2 min ago" style relative time for an ISO-8601 timestamp (best-effort). */
@Composable
fun relativeTime(iso: String): String {
    val epoch = runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()
    return if (epoch == null) iso else relativeTimeMillis(epoch)
}

@Composable
fun relativeTimeMillis(epochMs: Long): String {
    val diff = System.currentTimeMillis() - epochMs
    return when {
        diff < 60_000 -> stringResource(R.string.time_just_now)
        diff < 3_600_000 -> stringResource(R.string.time_min_ago, diff / 60_000)
        diff < 86_400_000 -> stringResource(R.string.time_hour_ago, diff / 3_600_000)
        else -> stringResource(R.string.time_day_ago, diff / 86_400_000)
    }
}
