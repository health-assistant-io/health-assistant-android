package io.healthassistant.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.healthassistant.android.R
import io.healthassistant.android.data.cache.ObservationDatabase
import io.healthassistant.android.data.cache.RoomCaches
import io.healthassistant.android.data.repository.BridgeBiomarkerGateway
import io.healthassistant.android.data.repository.BridgeObservationGateway
import io.healthassistant.android.ui.components.formatValue
import io.healthassistant.bridge.BridgeClient
import io.healthassistant.shared.data.cache.CacheDomain
import io.healthassistant.shared.data.repository.BiomarkerCatalogRepository
import io.healthassistant.shared.data.repository.ConnectivityProvider
import io.healthassistant.shared.data.repository.ObservationRepository
import io.healthassistant.shared.healthconnect.HcType
import org.koin.compose.koinInject

/**
 * Stateful owner of the biomarker graph detail. Builds the per-connection
 * observation repository + the [BiomarkerDetailViewModel] for the nav-arg
 * code, resolves the title/unit/reference-range from the cached catalog
 * (with the HcType LOINC table as fallback), and delegates to the pure
 * [BiomarkerDetailScreen].
 */
@Composable
fun BiomarkerDetailRoute(
    client: BridgeClient,
    biomarkerCode: String,
    onSetAlert: (() -> Unit)? = null,
    onBack: () -> Unit,
) {
    val loadFailedMsg = stringResource(R.string.insights_load_failed)
    val db: ObservationDatabase = koinInject()
    val connectivity: ConnectivityProvider = koinInject()
    val caches = remember(client) { RoomCaches(db, client.integrationId) }
    val repo =
        remember(client) {
            ObservationRepository(caches.observations, BridgeObservationGateway(client), connectivity, caches.meta)
        }
    val biomarkerRepo =
        remember(client) {
            BiomarkerCatalogRepository(caches.biomarkers, BridgeBiomarkerGateway(client), connectivity, caches.meta)
        }
    val vm: BiomarkerDetailViewModel =
        viewModel(
            key = "biomarker_detail_$biomarkerCode",
            factory = BiomarkerDetailViewModel.factory(repo, loadFailedMsg, biomarkerCode),
        )

    val catalog by biomarkerRepo.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val selectedRange by vm.selectedRange.collectAsStateWithLifecycle()
    val state by vm.series.collectAsStateWithLifecycle()

    val summary = catalog.firstOrNull { it.code == biomarkerCode }
    val fallbackType = HcType.byCode(biomarkerCode)
    val title = summary?.name ?: fallbackType?.display ?: biomarkerCode
    val unit = summary?.unit ?: fallbackType?.defaultUnit
    val referenceRangeLabel =
        (summary?.referenceRange)?.takeIf { it.present }?.let { range ->
            stringResource(
                R.string.insights_reference_range,
                range.low?.let(::formatValue) ?: "—",
                range.high?.let(::formatValue) ?: "—",
                summary.unit.orEmpty(),
            )
        }

    val staleMeta by caches.meta.observe(CacheDomain.OBSERVATIONS).collectAsStateWithLifecycle(initialValue = null)
    val online = rememberConnectivity() == Connectivity.Online

    BiomarkerDetailScreen(
        title = title,
        unit = unit,
        referenceRangeLabel = referenceRangeLabel,
        info = summary?.info,
        staleMeta = staleMeta,
        online = online,
        onStaleRetry = vm::retry,
        state = state,
        selectedRange = selectedRange,
        onSelectRange = vm::selectRange,
        onRetry = vm::retry,
        onZoomSpanChanged = vm::inspectSpan,
        onSetAlert = onSetAlert,
        onBack = onBack,
    )
}
