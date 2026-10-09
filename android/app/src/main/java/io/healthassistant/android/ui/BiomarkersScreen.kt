package io.healthassistant.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.healthassistant.android.R
import io.healthassistant.android.ui.components.ListSkeleton
import io.healthassistant.android.ui.components.StaleChipSlot
import io.healthassistant.android.ui.components.biomarkerIcon
import io.healthassistant.android.ui.components.formatValue
import io.healthassistant.android.ui.components.relativeTime
import io.healthassistant.shared.data.BiomarkerOption
import io.healthassistant.shared.healthconnect.HcType

/**
 * Records › Biomarkers — the list of all biomarker items as cards. An
 * "Overview" entry row (M6, ADVANCED only) pins to the top and opens the
 * multi-metric correlation chart. Default view: only biomarkers with
 * readings, most-recent first; each card shows the last value + unit +
 * relative time + trend and opens the graph on tap. A search field + "Show
 * all" toggle cover the full catalog. Pure state + lambdas
 * (Compose-testable).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BiomarkersScreen(
    state: BiomarkersUiState,
    staleMeta: io.healthassistant.shared.data.cache.CacheMetaState? = null,
    online: Boolean = true,
    onRetry: () -> Unit = {},
    onQueryChange: (String) -> Unit,
    onToggleShowAll: (Boolean) -> Unit,
    onOpenBiomarker: (String) -> Unit,
    showOverview: Boolean = false,
    onOpenOverview: () -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.biomarkers_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.biomarkers_search_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().testTag("biomarkers_search"),
            )
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = state.showAll,
                    onClick = { onToggleShowAll(!state.showAll) },
                    label = {
                        Text(
                            stringResource(
                                R.string.biomarkers_show_all,
                                countWithAndWithoutData(state),
                            ),
                        )
                    },
                )
                if (state.cards.isNotEmpty()) {
                    Text(
                        stringResource(R.string.biomarkers_count, state.cards.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            when {
                state.loading -> ListSkeleton(rows = LIST_SKELETON_ROWS, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))

                else ->
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        item { StaleChipSlot(meta = staleMeta, online = online, onRetry = onRetry) }
                        if (showOverview) {
                            item { OverviewEntryRow(onOpenOverview) }
                        }
                        when {
                            state.cards.isEmpty() && state.query.isNotBlank() ->
                                item { EmptyHint(stringResource(R.string.biomarkers_no_match)) }

                            state.cards.isEmpty() && state.showAll ->
                                item { EmptyHint(stringResource(R.string.biomarkers_empty_all)) }

                            state.cards.isEmpty() ->
                                item { EmptyHint(stringResource(R.string.biomarkers_empty)) }

                            else ->
                                items(state.cards, key = { it.option.id }) { card ->
                                    BiomarkerCardRow(
                                        card = card.option,
                                        hasData = card.hasData,
                                        trend = card.trend,
                                        onClick = {
                                            card.option.code?.let(onOpenBiomarker)
                                        },
                                    )
                                }
                        }
                    }
            }
        }
    }
}

@Composable
private fun countWithAndWithoutData(state: BiomarkersUiState): Int = state.totalKnown

/** The pinned wellness-overview entry (Records › Biomarkers › Overview,
 *  monitoring viz M6): a highlighted row above the with-data cards. */
@Composable
private fun OverviewEntryRow(onOpenOverview: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .testTag("biomarkers_overview_entry")
            .clickable(onClick = onOpenOverview),
    ) {
        ListItem(
            leadingContent = {
                Icon(
                    Icons.Outlined.Insights,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            },
            headlineContent = { Text(stringResource(R.string.overview_title)) },
            supportingContent = {
                Text(
                    stringResource(R.string.overview_entry_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            },
            trailingContent = {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/** One biomarker card: icon + name + reference-range badge, last value +
 *  unit + relative time + trend. Tappable → the graph. */
@Composable
private fun BiomarkerCardRow(
    card: BiomarkerOption,
    hasData: Boolean,
    trend: Trend?,
    onClick: () -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .testTag("biomarker_card")
            .clickable(onClick = onClick),
    ) {
        ListItem(
            leadingContent = {
                Icon(
                    biomarkerIcon(HcType.byCode(card.code ?: "")),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            },
            headlineContent = { Text(card.name) },
            supportingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val valueText = card.latestValueString ?: card.latestValue?.let(::formatValue)
                    if (valueText != null) {
                        Text(
                            stringResource(
                                R.string.biomarkers_last_value,
                                valueText,
                                card.latestUnit ?: card.unit.orEmpty(),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        card.latestTimestamp?.let { ts ->
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "· " + relativeTime(ts),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    } else {
                        Text(
                            stringResource(R.string.insights_no_latest),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            },
            trailingContent = {
                if (hasData) {
                    TrendIcon(trend)
                } else {
                    Text(
                        stringResource(R.string.biomarkers_never_measured),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            },
        )
    }
}

/** The trend arrow icon: up / down / stable (null reads as stable). */
@Composable
internal fun TrendIcon(
    trend: Trend?,
    modifier: Modifier = Modifier,
) {
    when (trend) {
        Trend.UP ->
            Icon(
                Icons.AutoMirrored.Filled.TrendingUp,
                contentDescription = stringResource(R.string.biomarkers_trend_up),
                tint = MaterialTheme.colorScheme.error,
                modifier = modifier.size(20.dp),
            )

        Trend.DOWN ->
            Icon(
                Icons.AutoMirrored.Filled.TrendingDown,
                contentDescription = stringResource(R.string.biomarkers_trend_down),
                tint = MaterialTheme.colorScheme.primary,
                modifier = modifier.size(20.dp),
            )

        Trend.FLAT, null ->
            Icon(
                Icons.AutoMirrored.Filled.TrendingFlat,
                contentDescription = stringResource(R.string.biomarkers_trend_flat),
                tint = MaterialTheme.colorScheme.outline,
                modifier = modifier.size(20.dp),
            )
    }
}

private const val LIST_SKELETON_ROWS = 6
