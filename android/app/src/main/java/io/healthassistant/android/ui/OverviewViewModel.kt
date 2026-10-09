package io.healthassistant.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.healthassistant.android.ui.components.TimePoint
import io.healthassistant.shared.data.BiomarkerSummary
import io.healthassistant.shared.data.ChartRange
import io.healthassistant.shared.data.Normalization
import io.healthassistant.shared.data.NormalizationStrategy
import io.healthassistant.shared.data.ObservationPoint
import io.healthassistant.shared.data.ReferenceRange
import io.healthassistant.shared.data.SeriesScale
import io.healthassistant.shared.data.repository.BiomarkerCatalogRepository
import io.healthassistant.shared.data.repository.ObservationRepository
import io.healthassistant.shared.healthconnect.HcType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Persistence seam for the overview's biomarker selection so it survives
 *  process death. `null` codes = nothing stored yet → the default selection
 *  (the most recently active biomarkers) applies. */
interface OverviewSelectionStore {
    val codes: Flow<List<String>?>

    suspend fun setCodes(codes: List<String>)
}

/** One biomarker the picker offers: identity + when it was last active. */
data class OverviewPickerOption(
    val code: String,
    val name: String,
    val unit: String?,
    val latestTimestamp: String?,
)

/** One biomarker's series on the overview chart, already normalized onto the
 *  shared axis by its own [scale]. */
data class OverviewSeriesUi(
    val code: String,
    val name: String,
    val unit: String?,
    val points: List<TimePoint>,
    val scale: SeriesScale?,
)

/** UI state for the wellness overview (Records › Biomarkers › Overview). */
data class OverviewUiState(
    val loading: Boolean = true,
    val selection: List<String> = emptyList(),
    val options: List<OverviewPickerOption> = emptyList(),
    val range: ChartRange = ChartRange.ONE_WEEK,
    val strategy: NormalizationStrategy = NormalizationStrategy.MIN_MAX,
    val series: List<OverviewSeriesUi> = emptyList(),
    val referenceRange: ReferenceRange? = null,
    val normalizedBand: ClosedFloatingPointRange<Double>? = null,
)

/** How many biomarkers the overview compares at once. */
internal const val MAX_OVERVIEW_SERIES = 3

/**
 * Stateful owner of the wellness overview (monitoring viz M6). Offline-first,
 * like the biomarker detail: each selected series renders from the cached
 * observation window instantly (Room-backed reactive flows), then a
 * best-effort background refresh rewrites the page into the cache.
 *
 * The selection (up to [MAX_OVERVIEW_SERIES] biomarker codes) persists
 * through [OverviewSelectionStore]; with nothing stored it defaults to the
 * [defaultOverviewSelection] most recently active biomarkers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OverviewViewModel(
    private val repo: ObservationRepository,
    private val biomarkerRepo: BiomarkerCatalogRepository,
    private val selectionStore: OverviewSelectionStore,
) : ViewModel() {
    private val range = MutableStateFlow(ChartRange.ONE_WEEK)
    private val strategy = MutableStateFlow(NormalizationStrategy.MIN_MAX)
    private val loaded = MutableStateFlow(false)

    private val catalogAndLatest =
        combine(biomarkerRepo.observeAll(), repo.observeLatest()) { catalog, latest -> catalog to latest }

    private val selection: Flow<List<String>> =
        combine(selectionStore.codes, repo.observeLatest()) { stored, latest ->
            stored ?: defaultOverviewSelection(latest)
        }

    private val selectionAndRange: Flow<Pair<List<String>, ChartRange>> =
        combine(selection, range) { codes, chartRange -> codes to chartRange }

    private val seriesByCode: Flow<Map<String, List<ObservationPoint>>> =
        selectionAndRange.flatMapLatest { (codes, chartRange) ->
            if (codes.isEmpty()) {
                flowOf(emptyMap())
            } else {
                val now = System.currentTimeMillis()
                val sinceMs = now - chartRange.days * MILLIS_PER_DAY
                combine(codes.map { code -> repo.observeSeries(code, sinceMs, now) }) { lists ->
                    codes.zip(lists).toMap()
                }
            }
        }

    val state: StateFlow<OverviewUiState> =
        combine(
            selectionAndRange,
            strategy,
            combine(seriesByCode, catalogAndLatest, loaded) { series, catalog, loadedNow ->
                Triple(series, catalog, loadedNow)
            },
        ) { (codes, chartRange), normalizationStrategy, (seriesByCode, catalogAndLatest, loadedNow) ->
            val (catalog, latest) = catalogAndLatest
            val catalogByCode = catalog.byCodeIndex()
            val referenceRange = overviewReferenceRange(codes, seriesByCode, catalogByCode)
            OverviewUiState(
                loading = !loadedNow && codes.isEmpty() && catalog.isEmpty(),
                selection = codes,
                options = overviewPickerOptions(catalog, latest),
                range = chartRange,
                strategy = normalizationStrategy,
                series =
                    codes.map { code ->
                        overviewSeriesUi(code, seriesByCode[code] ?: emptyList(), catalogByCode, normalizationStrategy)
                    },
                referenceRange = referenceRange,
                normalizedBand =
                    normalizedReferenceBand(
                        referenceRange,
                        seriesByCode[codes.singleOrNull()] ?: emptyList(),
                        normalizationStrategy,
                    ),
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, OverviewUiState())

    init {
        retry()
        viewModelScope.launch {
            selectionAndRange.collect { (codes, chartRange) ->
                val now = System.currentTimeMillis()
                codes.forEach { code ->
                    repo.refreshSeries(code, chartRange.sinceIso(now), chartRange.untilIso(now))
                }
            }
        }
    }

    /** Add/remove a picked biomarker (no-op past [MAX_OVERVIEW_SERIES]) and
     *  persist the new selection. */
    fun toggle(code: String) {
        viewModelScope.launch {
            val current = selection.first()
            val next =
                when {
                    current.contains(code) -> current - code
                    current.size < MAX_OVERVIEW_SERIES -> current + code
                    else -> return@launch
                }
            selectionStore.setCodes(next)
        }
    }

    fun selectRange(next: ChartRange) {
        range.value = next
    }

    fun selectStrategy(next: NormalizationStrategy) {
        strategy.value = next
    }

    /** Best-effort refresh: catalog + latest snapshot + every selected
     *  series' window. No-op on the network when offline — the cached view
     *  keeps rendering. */
    fun retry() {
        viewModelScope.launch {
            biomarkerRepo.refresh()
            repo.refreshLatest()
            loaded.value = true
            val (codes, chartRange) = selectionAndRange.first()
            val now = System.currentTimeMillis()
            codes.forEach { code ->
                repo.refreshSeries(code, chartRange.sinceIso(now), chartRange.untilIso(now))
            }
        }
    }

    companion object {
        private const val MILLIS_PER_DAY = 86_400_000L

        fun factory(
            repo: ObservationRepository,
            biomarkerRepo: BiomarkerCatalogRepository,
            selectionStore: OverviewSelectionStore,
        ) = viewModelFactory {
            initializer { OverviewViewModel(repo, biomarkerRepo, selectionStore) }
        }
    }
}

/** The default overview selection: the [MAX_OVERVIEW_SERIES] most recently
 *  active biomarkers (the latest snapshot is most-recent-first). */
internal fun defaultOverviewSelection(latest: List<ObservationPoint>): List<String> =
    latest.mapNotNull { it.primaryCode }.distinct().take(MAX_OVERVIEW_SERIES)

/** Picker rows: with-data biomarkers first (most recent first), then the
 *  never-measured ones (name-ascending) for manual-entry use. Pure. */
internal fun overviewPickerOptions(
    catalog: List<BiomarkerSummary>,
    latest: List<ObservationPoint>,
): List<OverviewPickerOption> {
    val latestByCode = latest.mapNotNull { obs -> obs.primaryCode?.let { it to obs } }.toMap()
    return catalog
        .mapNotNull { summary ->
            summary.code?.let { code ->
                OverviewPickerOption(
                    code = code,
                    name = summary.name,
                    unit = summary.unit,
                    latestTimestamp = latestByCode[code]?.effectiveDatetime,
                )
            }
        }.sortedWith(
            compareByDescending<OverviewPickerOption> { it.latestTimestamp ?: "" }
                .thenBy { it.name.lowercase() },
        )
}

/** The reference range shown with the overview: only when exactly one series
 *  is selected — per-observation range first, else the catalog's. Pure. */
internal fun overviewReferenceRange(
    selection: List<String>,
    seriesByCode: Map<String, List<ObservationPoint>>,
    catalogByCode: Map<String, BiomarkerSummary>,
): ReferenceRange? {
    val code = selection.singleOrNull() ?: return null
    val points = seriesByCode[code].orEmpty()
    val pointRange = points.firstNotNullOfOrNull { it.range?.takeIf { r -> r.plotRange() != null } }
    return pointRange ?: catalogByCode[code]?.referenceRange?.takeIf { it.plotRange() != null }
}

/** The single-series reference range mapped onto the normalized axis, for
 *  the chart's band. Null with no range, no points, or a flat scale. Pure. */
internal fun normalizedReferenceBand(
    referenceRange: ReferenceRange?,
    points: List<ObservationPoint>,
    strategy: NormalizationStrategy,
): ClosedFloatingPointRange<Double>? {
    val range = referenceRange?.plotRange() ?: return null
    val scale = Normalization.fit(timedValues(points).map { it.second }, strategy) ?: return null
    val low = scale.apply(range.start)
    val high = scale.apply(range.endInclusive)
    return minOf(low, high)..maxOf(low, high)
}

/** One biomarker's series fitted + normalized: name/unit from the catalog
 *  (HcType LOINC table fallback), points onto the shared axis. A series with
 *  no plottable readings stays present (empty) so its legend row renders. */
internal fun overviewSeriesUi(
    code: String,
    points: List<ObservationPoint>,
    catalogByCode: Map<String, BiomarkerSummary>,
    strategy: NormalizationStrategy,
): OverviewSeriesUi {
    val timed = timedValues(points)
    val scale = Normalization.fit(timed.map { it.second }, strategy)
    return OverviewSeriesUi(
        code = code,
        name = catalogByCode[code]?.name ?: HcType.byCode(code)?.display ?: code,
        unit = catalogByCode[code]?.unit ?: HcType.byCode(code)?.defaultUnit,
        points = timed.map { (epochMs, value) -> TimePoint(epochMs, scale?.apply(value)?.toFloat() ?: 0f) },
        scale = scale,
    )
}

/** (epochMs → numeric value) pairs, time-ascending; unparseable times and
 *  state-only readings drop out. */
internal fun timedValues(points: List<ObservationPoint>): List<Pair<Long, Double>> =
    points
        .mapNotNull { point ->
            val value = point.chartValue ?: return@mapNotNull null
            val epochMs = parseEpochMs(point.effectiveDatetime) ?: return@mapNotNull null
            epochMs to value
        }.sortedBy { it.first }

private fun List<BiomarkerSummary>.byCodeIndex(): Map<String, BiomarkerSummary> =
    mapNotNull { summary -> summary.code?.let { it to summary } }.toMap()

private fun ReferenceRange.plotRange(): ClosedFloatingPointRange<Double>? {
    val low = low ?: return null
    val high = high ?: return null
    return if (high > low) low..high else null
}
