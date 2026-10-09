package io.healthassistant.android.widget

import android.content.Context
import io.healthassistant.android.data.CredentialStore
import io.healthassistant.android.data.cache.ObservationDatabase
import io.healthassistant.android.data.cache.RoomCaches
import io.healthassistant.android.ui.components.formatValue
import io.healthassistant.shared.data.BiomarkerSummary
import io.healthassistant.shared.data.HomeDashboardBuilder
import io.healthassistant.shared.healthconnect.HcType
import kotlinx.coroutines.flow.first
import org.koin.core.Koin
import org.koin.core.context.GlobalContext

/**
 * M4 (widgets) — the widgets' read path into the offline-first caches, and
 * nothing else: no network, no gateways. Every call resolves the ACTIVE
 * connection the same way the Routes and [io.healthassistant.android.work.PullSyncWorker]
 * do — [CredentialStore.load] → `RoomCaches(db, integrationId)` — so a widget
 * can never surface another patient's rows, and a connection switch is picked
 * up on the next refresh pass. The [WidgetStateMapper] calls keep this layer
 * thin; the mapping itself stays pure + JVM-tested.
 */
class WidgetDataRepository(
    context: Context,
) {
    private val strings = AndroidWidgetStrings(context)

    /** The Latest vitals widget's model (newest-first, capped rows). */
    suspend fun latestVitalsUi(maxRows: Int): LatestVitalsWidgetUi =
        withCaches { caches ->
            val latest = caches.observations.latestPerBiomarker(maxRows).first()
            val catalog = caches.biomarkers.observeAll().first()
            WidgetStateMapper.latestVitals(latest, catalog, strings, maxRows)
        } ?: WidgetStateMapper.latestVitals(emptyList(), emptyList(), strings, maxRows)

    /** The Single metric widget's model for [code] (catalog fallbacks apply). */
    suspend fun ringUi(code: String): RingWidgetUi =
        withCaches { caches ->
            WidgetStateMapper.ring(
                code = code,
                point = caches.observations.latestForCode(code).first(),
                summary = caches.biomarkers.observeByCode(code).first(),
                strings = strings,
            )
        } ?: WidgetStateMapper.ring(code, point = null, summary = null, strings = strings)

    /** The Live heart rate widget's model: latest cached heart-rate point + the
     * window's series for the sparkline. */
    suspend fun heartRateUi(
        sinceMs: Long,
        maxSparkPoints: Int,
    ): HeartRateWidgetUi =
        withCaches { caches ->
            val code = HcType.HEART_RATE.code
            WidgetStateMapper.heartRate(
                latest = caches.observations.latestForCode(code).first(),
                series = caches.observations.seriesFor(code, sinceMs = sinceMs, untilMs = null, limit = SERIES_LIMIT).first(),
                strings = strings,
                maxSparkPoints = maxSparkPoints,
            )
        } ?: WidgetStateMapper.heartRate(latest = null, series = emptyList(), strings = strings, maxSparkPoints = maxSparkPoints)

    /** The ring configuration picker's list: cached biomarkers, data-first
     * (most recent reading first), then the rest of the catalog by name. */
    suspend fun ringOptions(): List<WidgetRingOption> =
        withCaches { caches ->
            val latest = caches.observations.latestPerBiomarker(OPTIONS_LIMIT).first()
            val catalog = caches.biomarkers.observeAll().first()
            val readings = HomeDashboardBuilder.fromServer(latest, catalog).associateBy { it.code }
            val withData =
                readings.values.map { reading ->
                    WidgetRingOption(
                        code = reading.code,
                        name = reading.displayName,
                        valueText = reading.valueString ?: reading.value?.let(::formatValue) ?: "—",
                        unit = reading.unit,
                    )
                }
            val withoutData =
                catalog
                    .mapNotNull { summary -> summary.code?.takeIf { it !in readings }?.let { summary to it } }
                    .sortedBy { (summary, _) -> summary.name.lowercase() }
                    .map { (summary, code) -> WidgetRingOption(code, summary.name, null, summary.unit) }
            withData + withoutData
        } ?: emptyList()

    /** The biomarker catalog entry for [code], for the ring's config preview. */
    suspend fun biomarkerForCode(code: String): BiomarkerSummary? =
        withCaches { caches ->
            caches.biomarkers.observeByCode(code).first()
        }

    private suspend fun <T> withCaches(block: suspend (RoomCaches) -> T): T? {
        val koin: Koin = GlobalContext.getOrNull() ?: return null
        val credential = koin.get<CredentialStore>().load() ?: return null
        val db = koin.get<ObservationDatabase>()
        return block(RoomCaches(db, credential.integrationId))
    }

    private companion object {
        const val SERIES_LIMIT = 600
        const val OPTIONS_LIMIT = 200
    }
}

/** One ring-configuration picker row: a catalog biomarker with its latest
 * cached value (null when it has no reading yet). */
data class WidgetRingOption(
    val code: String,
    val name: String,
    val valueText: String?,
    val unit: String?,
)
