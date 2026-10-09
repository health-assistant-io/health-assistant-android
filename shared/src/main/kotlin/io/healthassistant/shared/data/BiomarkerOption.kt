package io.healthassistant.shared.data

/** A biomarker the user can select/view — the catalog entry plus its latest
 *  known value. Built by [BiomarkerOptions] from `GET /biomarkers` (the
 *  instance catalog) + `GET /observations/latest` so both the Insights dropdown
 *  and the Home dashboard editor show one consistent, source-agnostic row. */
data class BiomarkerOption(
    val id: String,
    val name: String,
    val slug: String? = null,
    val code: String? = null,
    val unit: String? = null,
    val isTelemetry: Boolean = false,
    val referenceRange: ReferenceRange? = null,
    val latestValue: Double? = null,
    val latestValueString: String? = null,
    val latestUnit: String? = null,
    val latestTimestamp: String? = null,
)

/** Merges the biomarker catalog with the latest-per-code observations. Pure
 *  Kotlin and JVM-testable. */
object BiomarkerOptions {
    fun from(
        catalog: List<BiomarkerSummary>,
        latest: List<ObservationPoint>,
    ): List<BiomarkerOption> {
        val latestByCode = latest.mapNotNull { obs -> obs.primaryCode?.let { it to obs } }.toMap()
        return catalog.map { summary ->
            val obs = summary.code?.let { latestByCode[it] }
            BiomarkerOption(
                id = summary.id,
                name = summary.name,
                slug = summary.slug,
                code = summary.code,
                unit = summary.unit,
                isTelemetry = summary.isTelemetry,
                referenceRange = summary.referenceRange ?: obs?.range,
                latestValue = obs?.chartValue,
                latestValueString = obs?.valueString,
                latestUnit = obs?.normalizedUnit,
                latestTimestamp = obs?.effectiveDatetime,
            )
        }
    }
}
