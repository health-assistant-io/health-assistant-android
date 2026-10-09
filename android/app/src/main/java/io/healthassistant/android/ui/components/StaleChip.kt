package io.healthassistant.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.healthassistant.android.R
import io.healthassistant.shared.data.cache.CacheDomain
import io.healthassistant.shared.data.cache.CacheMetaState
import io.healthassistant.shared.data.cache.CacheMetaStore
import kotlinx.coroutines.flow.Flow

/**
 * The staleness chip (offline-first M8, plan §5): renders what the cache_meta
 * row says about a domain — "Updated X ago" while fresh, "Showing saved data ·
 * offline" after a failed refresh with no network, and a clickable "Couldn't
 * refresh — tap to retry" once back online. Never blocks the cached content
 * behind it; a null/never-refreshed state renders nothing.
 *
 * Pure: the Route feeds the [meta] row (via [rememberStaleMeta]) + the
 * connectivity flag; [onRetry] is the screen's existing reload action.
 */
@Composable
fun StaleChip(
    meta: CacheMetaState?,
    online: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = staleLabel(meta, online) ?: return
    val failing = meta?.lastError != null
    Row(
        modifier =
            modifier.then(
                if (failing && online) {
                    Modifier.clickable(onClick = onRetry)
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector =
                when {
                    failing && online -> Icons.Outlined.Refresh
                    failing -> Icons.Outlined.CloudOff
                    else -> Icons.Outlined.Schedule
                },
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint =
                if (failing) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.outline
                },
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color =
                if (failing) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.outline
                },
        )
    }
}

/**
 * The banner variant of [StaleChip] for screens with a cached snapshot (Home):
 * replaces the plain offline banner — it says "Showing saved data · offline"
 * once a failed refresh leaves the saved data on screen, and offers the retry
 * tap when online again. Nothing renders while the cache is fresh.
 */
@Composable
fun SavedDataBanner(
    meta: CacheMetaState?,
    online: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = staleLabel(meta, online) ?: return
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .then(
                    if (meta?.lastError != null && online) {
                        Modifier.clickable(onClick = onRetry)
                    } else {
                        Modifier
                    },
                ),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (meta?.lastError != null && online) Icons.Outlined.Refresh else Icons.Outlined.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The chip/banner text for a staleness row, or null when there is nothing
 *  worth saying (never refreshed, no error). */
@Composable
fun staleLabel(
    meta: CacheMetaState?,
    online: Boolean,
): String? =
    when {
        meta == null -> null
        meta.lastError != null && online -> stringResource(R.string.stale_retry)
        meta.lastError != null -> stringResource(R.string.stale_saved_offline)
        meta.lastSuccessAtEpochMs == 0L -> null
        else -> stringResource(R.string.stale_updated, relativeTimeMillis(meta.lastSuccessAtEpochMs))
    }

/** Route-side collector: the domain's staleness row, or null before the first
 *  emission (renders nothing). */
@Composable
fun rememberStaleMeta(
    store: CacheMetaStore,
    domain: CacheDomain,
): CacheMetaState? = staleMetaFlow(store, domain).collectAsStateWithLifecycle(initialValue = null).value

private fun staleMetaFlow(
    store: CacheMetaStore,
    domain: CacheDomain,
): Flow<CacheMetaState?> = store.observe(domain)

/**
 * Small column helper for screens that show the chip under a header: hides
 * itself (zero height) when there is no staleness to report.
 */
@Composable
fun StaleChipSlot(
    meta: CacheMetaState?,
    online: Boolean,
    onRetry: () -> Unit,
) {
    if (staleLabel(meta, online) == null) return
    Column {
        Spacer(Modifier.height(4.dp))
        StaleChip(meta = meta, online = online, onRetry = onRetry)
    }
}
