package io.healthassistant.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.healthassistant.shared.data.ChartRange
import io.healthassistant.shared.data.repository.ObservationRepository
import io.healthassistant.shared.data.repository.RefreshOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Stateful owner of the biomarker graph detail (Records › Biomarkers › tap).
 * Reworked from the old `InsightsViewModel` (cache-first M1 machinery kept
 * verbatim; the dropdown/selection state is gone — the biomarker arrives via
 * the nav route).
 *
 * **Offline-first:** the chart reads from the on-device observation cache via
 * [ObservationRepository.observeSeries] — instant and offline. On range change
 * the cached series renders first, then a background
 * [ObservationRepository.refreshSeries] writes the fresh page into the cache
 * and the chart re-renders. An error is shown only when a refresh fails AND
 * the cache is empty; with cached data the user keeps seeing the saved chart.
 */
class BiomarkerDetailViewModel(
    private val repo: ObservationRepository,
    private val loadFailedMsg: String,
    internal val biomarkerCode: String,
) : ViewModel() {
    private val _selectedRange = MutableStateFlow(ChartRange.ONE_WEEK)
    val selectedRange: StateFlow<ChartRange> = _selectedRange.asStateFlow()
    private val reloadKey = MutableStateFlow(0)
    private var userPickedRange = false
    private var autoWidened = false
    private var fetchedDetailSpanMs = Long.MAX_VALUE
    private var detailFetchInFlight = false

    private val _series = MutableStateFlow<InsightsUiState>(InsightsUiState.Loading)
    val series: StateFlow<InsightsUiState> = _series.asStateFlow()

    init {
        viewModelScope.launch {
            combine(_selectedRange, reloadKey) { range, _ -> range }
                .collect { range -> renderAndRefresh(range) }
        }
    }

    private suspend fun renderAndRefresh(range: ChartRange) {
        fetchedDetailSpanMs = range.days * MILLIS_PER_DAY
        val now = System.currentTimeMillis()
        val sinceMs = now - range.days * MILLIS_PER_DAY
        // 1. Cache-first render — instant, works offline.
        _series.value =
            InsightsUiState.Loaded(
                points = repo.observeSeries(biomarkerCode, sinceMs, now).first(),
                latestOverall = repo.observeLatestForCode(biomarkerCode).first(),
                previousOverall = repo.observePreviousForCode(biomarkerCode).first(),
            )
        // 2. Background refresh from the bridge (no-op when offline).
        val outcome = repo.refreshSeries(biomarkerCode, range.sinceIso(now), range.untilIso(now))
        // 3. Re-render with the refreshed cache; surface an error only when the
        //    refresh failed AND there is still nothing to show.
        val after = repo.observeSeries(biomarkerCode, sinceMs, now).first()
        if (after.isEmpty() && outcome == RefreshOutcome.FAILED) {
            _series.value = InsightsUiState.Error(loadFailedMsg)
            return
        }
        _series.value =
            InsightsUiState.Loaded(
                points = after,
                latestOverall = repo.observeLatestForCode(biomarkerCode).first(),
                previousOverall = repo.observePreviousForCode(biomarkerCode).first(),
            )
        // 4. "Latest state" fallback: a lab biomarker whose newest reading is
        //    older than the selected window renders an empty chart (reads as
        //    broken). Before the user picks a range manually, widen to the
        //    maximum window once so the chart shows what exists.
        autoWidenRange(range, after.isEmpty(), userPickedRange, autoWidened)?.let { widened ->
            autoWidened = true
            _selectedRange.value = widened
        }
    }

    fun selectRange(range: ChartRange) {
        userPickedRange = true
        _selectedRange.value = range
    }

    /**
     * The chart was zoomed until [spanMs] is visible. When the visible span
     * halves below what the cache was fetched at, pull RAW points for the
     * newest `2 × spanMs` window (bridge serves unaggregated rows) so dense
     * telemetry expands into intraday dots instead of a stacked column. Best
     * effort — offline zoom keeps the cached resolution.
     */
    fun inspectSpan(spanMs: Long) {
        if (detailFetchInFlight) return
        if (!needsDetailFetch(spanMs, fetchedDetailSpanMs)) return
        detailFetchInFlight = true
        viewModelScope.launch {
            try {
                fetchedDetailSpanMs = spanMs
                val now = System.currentTimeMillis()
                val sinceIso =
                    java.time.Instant
                        .ofEpochMilli(now - spanMs * 2)
                        .toString()
                val untilIso =
                    java.time.Instant
                        .ofEpochMilli(now)
                        .toString()
                repo.refreshSeries(biomarkerCode, sinceIso, untilIso, limit = DETAIL_LIMIT)
                // Re-render the current window from the cache — the fetch
                // upserted raw rows; the chart domain is window-pinned so the
                // zoom position is preserved and new dots simply appear.
                val range = _selectedRange.value
                val windowMs = range.days * MILLIS_PER_DAY
                val now2 = System.currentTimeMillis()
                _series.value =
                    InsightsUiState.Loaded(
                        points = repo.observeSeries(biomarkerCode, now2 - windowMs, now2).first(),
                        latestOverall = repo.observeLatestForCode(biomarkerCode).first(),
                        previousOverall = repo.observePreviousForCode(biomarkerCode).first(),
                    )
            } finally {
                detailFetchInFlight = false
            }
        }
    }

    fun retry() {
        reloadKey.value++
    }

    companion object {
        private const val MILLIS_PER_DAY = 86_400_000L
        private const val DETAIL_LIMIT = 500

        fun factory(
            repo: ObservationRepository,
            loadFailedMsg: String,
            biomarkerCode: String,
        ) = viewModelFactory {
            initializer { BiomarkerDetailViewModel(repo, loadFailedMsg, biomarkerCode) }
        }
    }
}

/** Pure widen decision: when the current window is empty, the user hasn't
 *  picked a range, and we haven't widened before — jump to the widest window. */
internal fun autoWidenRange(
    current: ChartRange,
    empty: Boolean,
    userPicked: Boolean,
    alreadyWidened: Boolean,
): ChartRange? =
    if (empty && !userPicked && !alreadyWidened && current != ChartRange.ONE_YEAR) {
        ChartRange.ONE_YEAR
    } else {
        null
    }

/** Pure zoom-detail gate: fetch only when the visible span has HALVED below
 *  the span the cache was last fetched at — one fetch per zoom step, no loop. */
internal fun needsDetailFetch(
    visibleSpanMs: Long,
    fetchedSpanMs: Long,
): Boolean = fetchedSpanMs == Long.MAX_VALUE || visibleSpanMs < fetchedSpanMs / 2
