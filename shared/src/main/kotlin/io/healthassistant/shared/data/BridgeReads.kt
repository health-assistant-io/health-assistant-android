package io.healthassistant.shared.data

import io.healthassistant.bridge.BridgeClient
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Summary of an examination, matching the bridge `GET /examinations` item shape (provider.py). */
@Serializable
data class ExaminationSummary(
    val id: String,
    @SerialName("examination_date") val examinationDate: String? = null,
    val notes: String? = null,
    @SerialName("patient_notes") val patientNotes: String? = null,
    @SerialName("extraction_status") val extractionStatus: String? = null,
    val diagnoses: List<String> = emptyList(),
    val impressions: String? = null,
    val category: String? = null,
    @SerialName("lab_name") val labName: String? = null,
    @SerialName("external_id") val externalId: String? = null,
)

/** Read envelope `{ data, cursor, cached_at }` returned by the bridge read paths. */
@Serializable
data class ReadEnvelope<T>(
    val data: List<T> = emptyList(),
    val cursor: String? = null,
    @SerialName("cached_at") val cachedAt: String? = null,
)

/** `GET /documents/{id}/text` — the OCR-extracted Markdown of a document,
 *  capped at 512 KiB server-side (`truncated` flags the cut). */
@Serializable
data class DocumentText(
    val id: String,
    @SerialName("extracted_text") val extractedText: String? = null,
    val status: String? = null,
    val truncated: Boolean = false,
)

/** The FHIR `code` JSONB: a coding list, e.g. `{"coding":[{"system":"http://loinc.org","code":"8867-4"}]}`. */
@Serializable
data class ObservationCode(
    val coding: List<Coding> = emptyList(),
    val text: String? = null,
) {
    @Serializable
    data class Coding(
        val code: String? = null,
        val system: String? = null,
        val display: String? = null,
    )
}

/**
 * Flat reference range `{low, high}`. The bridge normalizes both the flat
 * bridge-push shape and the FHIR list shape `[{low: {value, unit}, high: …}]`
 * to this one. Deserializes tolerantly (see [fromJsonElement]) so the app never
 * crashes on a legacy / OCR / FHIR-imported row.
 */
@Serializable(with = ReferenceRange.Serializer::class)
data class ReferenceRange(
    val low: Double? = null,
    val high: Double? = null,
) {
    /** True when at least one bound is present. */
    val present: Boolean get() = low != null || high != null

    object Serializer : KSerializer<ReferenceRange> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("ReferenceRange", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: ReferenceRange) {
            val json = encoder as? JsonEncoder
            if (json != null) {
                json.encodeJsonElement(
                    buildJsonObject {
                        value.low?.let { put("low", it) }
                        value.high?.let { put("high", it) }
                    },
                )
            } else {
                encoder.encodeString("${value.low ?: ""}|${value.high ?: ""}")
            }
        }

        override fun deserialize(decoder: Decoder): ReferenceRange =
            (decoder as? JsonDecoder)?.let { fromJsonElement(it.decodeJsonElement()) } ?: ReferenceRange()
    }

    companion object {
        /** Tolerant parser: flat `{low, high}`, FHIR list, or an empty object. */
        fun fromJsonElement(element: JsonElement): ReferenceRange {
            var target: JsonElement = element
            if (target is JsonArray) target = target.firstOrNull() ?: JsonNull
            if (target !is JsonObject) return ReferenceRange()

            fun num(key: String): Double? {
                val raw = target[key] ?: return null
                val value = if (raw is JsonObject) raw["value"] else raw
                return (value as? JsonPrimitive)?.content?.toDoubleOrNull()
            }

            val low = num("low")
            val high = num("high")
            return ReferenceRange(low = low, high = high)
        }
    }
}

/** One observation of the time series (`GET /observations`), the FHIR
 *  Observation `to_dict()` projection. Enough fields to plot a chart, fill a
 *  dashboard card, or show a reference-range band. */
@Serializable
data class ObservationPoint(
    val id: String,
    @SerialName("effective_datetime") val effectiveDatetime: String? = null,
    @SerialName("raw_value") val rawValue: Double? = null,
    @SerialName("normalized_value") val normalizedValue: Double? = null,
    @SerialName("normalized_unit") val normalizedUnit: String? = null,
    val code: ObservationCode? = null,
    @SerialName("reference_range") val referenceRange: ReferenceRange? = null,
    @SerialName("biomarker_id") val biomarkerId: String? = null,
    @SerialName("biomarker_slug") val biomarkerSlug: String? = null,
    @SerialName("biomarker_value_type") val biomarkerValueType: String? = null,
    @SerialName("value_string") val valueString: String? = null,
    val interpretation: String? = null,
    @SerialName("relative_score") val relativeScore: Double? = null,
    @SerialName("biomarker_reference_range_min") val biomarkerReferenceRangeMin: Double? = null,
    @SerialName("biomarker_reference_range_max") val biomarkerReferenceRangeMax: Double? = null,
) {
    /** The value to plot: raw when present, else the normalized value. */
    val chartValue: Double? get() = rawValue ?: normalizedValue

    /** The biomarker code (LOINC or custom) — matches [io.healthassistant.shared.healthconnect.HcType.code]. */
    val primaryCode: String? get() = code?.coding?.firstOrNull()?.code

    /** Best display name: FHIR code text, coding display, else the biomarker slug. */
    val displayName: String?
        get() =
            code?.text
                ?: code?.coding?.firstOrNull()?.display
                ?: biomarkerSlug
                ?: primaryCode

    /** Reference range for display: the flat row value, else the definition's min/max. */
    val range: ReferenceRange?
        get() =
            referenceRange
                ?: if (biomarkerReferenceRangeMin != null || biomarkerReferenceRangeMax != null) {
                    ReferenceRange(biomarkerReferenceRangeMin, biomarkerReferenceRangeMax)
                } else {
                    null
                }
}

/** A catalog biomarker (`GET /biomarkers`) — the full list of biomarkers the
 *  instance knows about (telemetry-flagged HC types + instance-only lab
 *  definitions). Drives the Home dashboard + the Insights chooser. */
@Serializable
data class BiomarkerSummary(
    val id: String,
    val name: String,
    val slug: String? = null,
    val code: String? = null,
    @SerialName("coding_system") val codingSystem: String? = null,
    val unit: String? = null,
    @SerialName("is_telemetry") val isTelemetry: Boolean = false,
    @SerialName("reference_range_min") val referenceRangeMin: Double? = null,
    @SerialName("reference_range_max") val referenceRangeMax: Double? = null,
    @SerialName("value_type") val valueType: String? = null,
    val info: String? = null,
) {
    /** Reference range from the definition's min/max, when both present. */
    val referenceRange: ReferenceRange?
        get() =
            if (referenceRangeMin != null || referenceRangeMax != null) {
                ReferenceRange(referenceRangeMin, referenceRangeMax)
            } else {
                null
            }
}

/** Typed reads against the bridge via [BridgeClient.requestText] (no ktor leak). */
object BridgeReads {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    suspend fun listExaminations(client: BridgeClient, limit: Int = 50): List<ExaminationSummary> {
        val text = client.requestText("GET", "/examinations?limit=$limit")
        return json.decodeFromString(
            ReadEnvelope.serializer(ExaminationSummary.serializer()),
            text,
        ).data
    }

    /** One examination's detail (`GET /examinations/{id}`) — a bare object
     *  (NOT the read envelope) carrying the list fields plus `diagnoses` +
     *  `impressions`. */
    suspend fun examinationDetail(
        client: BridgeClient,
        examId: String,
    ): ExaminationSummary = json.decodeFromString(ExaminationSummary.serializer(), client.requestText("GET", "/examinations/$examId"))

    /** Time series for one biomarker (`GET /observations`), newest first as the
     *  bridge returns them. [since]/[until] are ISO-8601; [limit] ≤ 500. */
    suspend fun listObservations(
        client: BridgeClient,
        biomarker: String,
        since: String,
        until: String,
        limit: Int = 200,
    ): List<ObservationPoint> {
        val text =
            client.requestText(
                "GET",
                "/observations?biomarker=$biomarker&since=$since&until=$until&limit=$limit",
            )
        return decodeEnvelope(text, ObservationPoint.serializer())
    }

    /** Latest value per biomarker (`GET /observations/latest`) for the Home
     *  dashboard cards — merged FHIR + telemetry by the bridge. */
    suspend fun listLatestObservations(
        client: BridgeClient,
        limit: Int = 50,
    ): List<ObservationPoint> {
        val text = client.requestText("GET", "/observations/latest?limit=$limit")
        return decodeEnvelope(text, ObservationPoint.serializer())
    }

    /** The patient's biomarker catalog (`GET /biomarkers`) — full list incl.
     *  instance-only lab definitions + telemetry flags + reference ranges. */
    suspend fun listBiomarkers(
        client: BridgeClient,
        limit: Int = 500,
    ): List<BiomarkerSummary> {
        val text = client.requestText("GET", "/biomarkers?limit=$limit")
        return decodeEnvelope(text, BiomarkerSummary.serializer())
    }

    // --- Phase A: documents -------------------------------------------
    //
    //  Bridge ships `GET /examinations/{id}/documents` (enriched with
    //  content_type + file_size + examination_id) and the content paths
    //  `GET /documents/{id}/content` (binary) + `GET /documents/{id}/preview`
    //  (JPEG page render). These wrappers decode the metadata envelope and
    //  surface the bytes primitives the UI renders.

    /** Documents attached to an exam (`GET /examinations/{exam_id}/documents`). */
    suspend fun listDocumentsForExam(
        client: BridgeClient,
        examId: String,
    ): List<DocumentSummary> =
        decodeEnvelope(
            client.requestText("GET", "/examinations/$examId/documents"),
            DocumentSummary.serializer(),
        )

    /** Patient-wide document list (`GET /documents?examination_id=&limit=`). */
    suspend fun listDocuments(
        client: BridgeClient,
        examinationId: String? = null,
        limit: Int = 100,
    ): List<DocumentSummary> {
        val qs = buildList {
            examinationId?.let { add("examination_id=$it") }
            add("limit=$limit")
        }.joinToString("&")
        return decodeEnvelope(
            client.requestText("GET", "/documents?$qs"),
            DocumentSummary.serializer(),
        )
    }

    /** Raw bytes of a document (`GET /documents/{id}/content`) — the file as
     *  uploaded. Use for rendering images inline or handing to a system viewer
     *  via FileProvider. Throws [io.healthassistant.bridge.BridgeException] on
     *  non-2xx (e.g. 404 cross-patient, 410 deleted). */
    suspend fun getDocumentBytes(client: BridgeClient, docId: String): ByteArray =
        client.requestBytes("GET", "/documents/$docId/content")

    /** JPEG preview of a document page (`GET /documents/{id}/preview?page=N`).
     *  For images this is the stored bytes; for PDFs/DICOMs it's a server-side
     *  page render. [page] defaults to 0 (the first page). */
    suspend fun getDocumentPreview(
        client: BridgeClient,
        docId: String,
        page: Int? = null,
    ): ByteArray =
        client.requestBytes(
            "GET",
            "/documents/$docId/preview" + (page?.let { "?page=$it" } ?: ""),
        )

    /** OCR-extracted Markdown text of a document (`GET /documents/{id}/text`),
     *  capped at 512 KiB server-side (`truncated` flags the cut). */
    suspend fun getDocumentText(
        client: BridgeClient,
        docId: String,
    ): DocumentText =
        json.decodeFromString(
            DocumentText.serializer(),
            client.requestText("GET", "/documents/$docId/text"),
        )

    /** Decode helper exposed for tests / other read paths. */
    fun <T> decodeEnvelope(text: String, element: kotlinx.serialization.KSerializer<T>): List<T> =
        json.decodeFromString(ReadEnvelope.serializer(element), text).data
}

/** Metadata for one document attached to an exam — matches the bridge's
 *  `GET /examinations/{id}/documents` item shape (enriched with content_type +
 *  file_size + examination_id in Phase 1 of the cross-repo plan). */
@Serializable
data class DocumentSummary(
    val id: String,
    val filename: String? = null,
    val status: String? = null,
    val progress: Double? = null,
    @SerialName("external_id") val externalId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("content_type") val contentType: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
    @SerialName("examination_id") val examinationId: String? = null,
) {
    /** Best-effort MIME-type guess: the explicit content_type, else guess from
     *  the filename extension. Used to pick the renderer (image inline vs PDF
     *  external viewer vs DICOM → WebView). */
    val effectiveContentType: String
        get() {
            contentType?.let { return it }
            val ext = filename?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase()
            return when (ext) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "bmp" -> "image/bmp"
                "pdf" -> "application/pdf"
                "txt" -> "text/plain"
                "html", "htm" -> "text/html"
                else -> "application/octet-stream"
            }
        }

    /** True for image MIME types — Coil renders these inline. */
    val isImage: Boolean get() = effectiveContentType.startsWith("image/")

    /** True for PDF — handed to the system PDF viewer via FileProvider. */
    val isPdf: Boolean get() = effectiveContentType == "application/pdf"

    /** Human-readable size label ("2.4 MB"), rounded to one decimal for MB/GB
     *  and to the integer for KB. */
    val displaySize: String?
        get() = fileSize?.let { bytes ->
            val kib = 1024.0
            when {
                bytes < kib -> "${bytes} B"
                bytes < kib * kib -> "${(bytes / kib).toInt()} KB"
                bytes < kib * kib * kib -> "%.1f MB".format(bytes / (kib * kib))
                else -> "%.1f GB".format(bytes / (kib * kib * kib))
            }
        }
}
