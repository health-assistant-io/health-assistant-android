package io.healthassistant.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.healthassistant.shared.data.BiomarkerOption
import io.healthassistant.shared.data.BiomarkerOptions
import io.healthassistant.shared.data.ObservationPoint
import io.healthassistant.shared.data.repository.BiomarkerCatalogRepository
import io.healthassistant.shared.data.repository.ObservationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Direction of change between the last two readings of a biomarker. */
enum class Trend {
    UP,
    DOWN,
    FLAT,
}

/** Direction + percent change between the last two readings of a biomarker. */
data class TrendDelta(
    val trend: Trend,
    val percent: Double,
)

/** Percent-change threshold below which a delta reads as [Trend.FLAT]. */
internal const val TREND_FLAT_THRESHOLD_PERCENT = 1.0

/** Pure trend derivation: the % change of [latest] vs [previous] (the reading
 *  before it). Null when either value is missing or the previous value is 0
 *  (no meaningful ratio) — the chip stays hidden. Changes within
 *  [TREND_FLAT_THRESHOLD_PERCENT] read as [Trend.FLAT]. */
internal fun trendOf(
    latest: Double?,
    previous: Double?,
): TrendDelta? {
    if (latest == null || previous == null || previous == 0.0) return null
    val percent = (latest - previous) / kotlin.math.abs(previous) * 100.0
    val trend =
        when {
            percent > TREND_FLAT_THRESHOLD_PERCENT -> Trend.UP
            percent < -TREND_FLAT_THRESHOLD_PERCENT -> Trend.DOWN
            else -> Trend.FLAT
        }
    return TrendDelta(trend, percent)
}

/** One row/card in the Biomarkers list: the catalog option + presentation
 *  metadata derived in the VM (relative-time input + trend). */
data class BiomarkerCard(
    val option: BiomarkerOption,
    val hasData: Boolean,
    val trend: Trend?,
)

/** UI state for the Biomarkers list (Records › Biomarkers). */
data class BiomarkersUiState(
    val loading: Boolean = true,
    val query: String = "",
    val showAll: Boolean = false,
    val cards: List<BiomarkerCard> = emptyList(),
    val totalKnown: Int = 0,
)

/**
 * Owns the Biomarkers list (Records › Biomarkers). Offline-first: the list
 * merges the cached biomarker catalog with the cached latest-per-biomarker
 * snapshot — both Room-backed reactive flows — so it renders instantly and
 * offline. [reload] only refreshes the caches from the bridge (no-op on the
 * network when offline).
 *
 * Default view: only biomarkers with ≥1 reading, most-recent first. The
 * "Show all" toggle reveals the rest (name-sorted) for manual-entry use.
 */
class BiomarkersViewModel(
    private val repo: ObservationRepository,
    private val biomarkerRepo: BiomarkerCatalogRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val showAll = MutableStateFlow(false)
    private val loaded = MutableStateFlow(false)

    val state: StateFlow<BiomarkersUiState> =
        combine(
            combine(
                biomarkerRepo.observeAll(),
                repo.observeLatest(),
                repo.observePreviousPerBiomarker(),
            ) { catalog, latest, previous -> Triple(catalog, latest, previous) },
            query,
            showAll,
            loaded,
        ) { (catalog, latest, previous), q, all, loadedNow ->
            val options = BiomarkerOptions.from(catalog, latest)
            val previousByCode =
                previous
                    .mapNotNull { p ->
                        p.primaryCode?.let { it to p }
                    }.toMap()
            val (withData, withoutData) = sortAndPartition(options, previousByCode)
            val visible = if (all) withData + withoutData else withData
            val filtered =
                if (q.isBlank()) {
                    visible
                } else {
                    visible.filter {
                        it.option.name.contains(q, ignoreCase = true) ||
                            it.option.code?.contains(q, ignoreCase = true) == true
                    }
                }
            BiomarkersUiState(
                loading = !loadedNow && options.isEmpty(),
                query = q,
                showAll = all,
                cards = filtered,
                totalKnown = options.size,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, BiomarkersUiState())

    init {
        reload()
    }

    /** Refresh the latest snapshot + catalog caches from the bridge. */
    fun reload() {
        viewModelScope.launch {
            biomarkerRepo.refresh()
            repo.refreshLatest()
            loaded.value = true
        }
    }

    fun setQuery(q: String) {
        query.value = q
    }

    fun setShowAll(all: Boolean) {
        showAll.value = all
    }

    companion object {
        /** Pure list derivation: biomarkers with data first (most-recent
         *  reading first), then the rest (name-sorted). [previousByCode] maps
         *  a biomarker code to the reading before its latest — the trend chip
         *  baseline; a card without one renders no trend. Exposed for JVM
         *  tests. */
        fun sortAndPartition(
            options: List<BiomarkerOption>,
            previousByCode: Map<String, ObservationPoint> = emptyMap(),
        ): Pair<List<BiomarkerCard>, List<BiomarkerCard>> {
            val withData =
                options
                    .filter { it.latestTimestamp != null }
                    .sortedWith(
                        compareByDescending<BiomarkerOption> { it.latestTimestamp }
                            .thenBy { it.name.lowercase() },
                    ).map { it.toCard(hasData = true, previousByCode) }
            val withoutData =
                options
                    .filter { it.latestTimestamp == null }
                    .sortedBy { it.name.lowercase() }
                    .map { it.toCard(hasData = false, previousByCode) }
            return withData to withoutData
        }

        private fun BiomarkerOption.toCard(
            hasData: Boolean,
            previousByCode: Map<String, ObservationPoint>,
        ): BiomarkerCard =
            BiomarkerCard(
                option = this,
                hasData = hasData,
                trend = code?.let { trendOf(latestValue, previousByCode[it]?.chartValue)?.trend },
            )

        fun factory(
            repo: ObservationRepository,
            biomarkerRepo: BiomarkerCatalogRepository,
        ) = viewModelFactory {
            initializer { BiomarkersViewModel(repo, biomarkerRepo) }
        }
    }
}
