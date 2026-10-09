package io.healthassistant.android.ui

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.healthassistant.android.data.DocumentOpener
import io.healthassistant.bridge.BridgeClient
import io.healthassistant.shared.data.BridgeReads
import io.healthassistant.shared.data.DocumentSummary
import io.healthassistant.shared.data.ExaminationSummary
import io.healthassistant.shared.data.repository.DocumentByteRepository
import io.healthassistant.shared.data.repository.DocumentRepository
import io.healthassistant.shared.data.repository.ExaminationRepository
import io.healthassistant.shared.data.repository.RefreshOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject

/**
 * Phase D — UI state for the ExaminationDetail screen. The fields mirror what
 * ExaminationDetailScreen renders; this is what [ExaminationDetailViewModel.state]
 * emits + what the unit tests assert against (via Turbine).
 */
data class ExaminationDetailUiState(
    val docState: DocListState = DocListState.Loading,
    val exam: ExaminationSummary? = null,
    val documents: List<DocumentSummary> = emptyList(),
    val thumbnails: Map<String, ByteArray> = emptyMap(),
    /** OCR-extracted Markdown of the doc whose text sheet is open (R4). */
    val docText: DocumentTextState? = null,
    /** Live extraction pipeline state while processing (R6). */
    val extraction: ExtractionStatusState? = null,
)

/** State of the per-document extracted-text sheet. */
data class DocumentTextState(
    val filename: String?,
    val loading: Boolean = true,
    val text: String? = null,
    val truncated: Boolean = false,
    val error: Boolean = false,
)

/** Live extraction pipeline snapshot (`GET /examinations/{id}/status`). */
data class ExtractionStatusState(
    val status: String? = null,
    val progress: Int = 0,
    val errorMessage: String? = null,
    val documentStatuses: List<Pair<String?, Int>> = emptyList(),
)

/** Loading state for the documents list inside the exam detail screen. */
enum class DocListState { Loading, Loaded, Error }

/**
 * Owns the Phase A examination-detail screen's data flow (Phase D refactor).
 *
 * What moved here from `ExaminationDetailRoute`:
 * - The exam-metadata projection + the document list — **offline-first (M3):**
 *   both read from the Room caches via [ExaminationRepository.observeById] /
 *   [DocumentRepository.observeForExam] (reactive, instant, offline). [reload]
 *   only refreshes the caches; offline keeps the saved exam + documents on
 *   screen. `Error` appears only when there is nothing cached AND the refresh
 *   can't succeed.
 * - The lazy thumbnail load (image docs only, capped at 8 to bound the burst;
 *   network-only — byte caching is M4).
 * - The document-open flow (image → onPreviewImage callback; PDF/other →
 *   cache + ACTION_VIEW + FileProvider + the system chooser).
 *
 * Constructed via [factory] so the per-connection [BridgeClient] flows in.
 * The "open document" callback needs a Context for the FileProvider + the
 * Toast, so [openDocument] takes a Context (the Route supplies LocalContext).
 */
class ExaminationDetailViewModel(
    private val client: BridgeClient,
    private val examRepo: ExaminationRepository,
    private val docRepo: DocumentRepository,
    private val byteRepo: DocumentByteRepository,
    private val examId: String,
) : ViewModel() {
    // Outcome of the most recent document refresh; null until the first one
    // settles (drives Loading → Loaded/Error for an empty doc cache).
    private val docOutcome = MutableStateFlow<RefreshOutcome?>(null)
    private val thumbnails = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    private val docText = MutableStateFlow<DocumentTextState?>(null)
    private val extraction = MutableStateFlow<ExtractionStatusState?>(null)

    val state: StateFlow<ExaminationDetailUiState> =
        combine(
            examRepo.observeById(examId),
            docRepo.observeForExam(examId),
            docOutcome,
            thumbnails,
            docText,
            extraction,
        ) { values ->
            @Suppress("UNCHECKED_CAST")
            val exam = values[0] as ExaminationSummary?

            @Suppress("UNCHECKED_CAST")
            val docs = values[1] as List<DocumentSummary>
            val outcome = values[2] as RefreshOutcome?

            @Suppress("UNCHECKED_CAST")
            val thumbs = values[3] as Map<String, ByteArray>
            val text = values[4] as DocumentTextState?
            val extractionStatus = values[5] as ExtractionStatusState?
            val docState =
                when {
                    docs.isNotEmpty() -> DocListState.Loaded
                    outcome == null -> DocListState.Loading
                    outcome == RefreshOutcome.REFRESHED -> DocListState.Loaded
                    else -> DocListState.Error
                }
            ExaminationDetailUiState(
                docState = docState,
                exam = exam,
                documents = docs,
                thumbnails = thumbs,
                docText = text,
                extraction = extractionStatus,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ExaminationDetailUiState())

    init {
        reload()
    }

    /** Refresh the exam detail + document caches from the bridge (no-op on
     *  the network when offline). Also refreshes the thumbnails of image
     *  docs. Called on first load + "Retry" + after mutations. */
    fun reload() {
        viewModelScope.launch {
            docOutcome.value = docRepo.refreshForExam(examId)
            examRepo.refreshDetail(examId)
            pollExtractionOnce()
            // Lazy-load image thumbnails — bounded to the first 8 image docs.
            val imageDocs =
                docRepo
                    .observeForExam(examId)
                    .first()
                    .filter { it.isImage }
                    .take(8)
            val thumbs = mutableMapOf<String, ByteArray>()
            for (d in imageDocs) {
                byteRepo.preview(d.id)?.let { thumbs[d.id] = it }
            }
            thumbnails.value = thumbs
        }
    }

    /** R6 — one poll of `GET /examinations/{id}/status`; while the pipeline
     *  is pending/processing it re-polls every 4 s (bounded by viewModelScope)
     *  and refreshes the caches once it completes. */
    private fun pollExtractionOnce() {
        viewModelScope.launch {
            val next =
                runCatching { client.requestText("GET", "/examinations/$examId/status") }
                    .mapCatching(::parseExtractionStatus)
                    .getOrNull()
            extraction.value = next
            val active =
                next?.status?.lowercase() in
                    setOf("pending", "processing", "aggregating", "analyzing_text", "defining_ontology", "persisting_results")
            if (active) {
                kotlinx.coroutines.delay(4_000)
                pollExtractionOnce()
            } else if (next?.status?.lowercase() == "completed") {
                examRepo.refreshDetail(examId)
                docRepo.refreshForExam(examId)
            }
        }
    }

    private fun parseExtractionStatus(raw: String): ExtractionStatusState {
        val obj =
            kotlinx.serialization.json.Json
                .parseToJsonElement(raw)
                .jsonObject
        val docs =
            (obj["documents"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { d ->
                    val o = d as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                    val name = o.stringOrNull("filename")
                    val progress = o.stringOrNull("progress")?.toIntOrNull() ?: 0
                    name to progress
                } ?: emptyList()
        return ExtractionStatusState(
            status = obj.stringOrNull("extraction_status"),
            progress = obj.stringOrNull("extraction_progress")?.toIntOrNull() ?: 0,
            errorMessage = obj.stringOrNull("error_message"),
            documentStatuses = docs,
        )
    }

    /** JsonObject string accessor that treats JSON null as Kotlin null
     *  (JsonNull is a JsonPrimitive whose `.content` is the literal "null" —
     *  a plain `as? JsonPrimitive` surfaces the string "null" in the UI). */
    private fun kotlinx.serialization.json.JsonObject.stringOrNull(key: String): String? {
        val v = this[key] ?: return null
        if (v is kotlinx.serialization.json.JsonNull) return null
        return (v as? kotlinx.serialization.json.JsonPrimitive)?.content
    }

    // --- R4: document extracted text ------------------------------------

    /** Fetch `GET /documents/{id}/text` for the text sheet. Network-only;
     *  offline shows the error row. */
    fun openDocumentText(doc: DocumentSummary) {
        docText.value = DocumentTextState(filename = doc.filename)
        viewModelScope.launch {
            docText.value =
                runCatching { BridgeReads.getDocumentText(client, doc.id) }
                    .fold(
                        onSuccess = { t ->
                            DocumentTextState(
                                filename = doc.filename,
                                loading = false,
                                text = t.extractedText?.takeIf { it.isNotBlank() },
                                truncated = t.truncated,
                            )
                        },
                        onFailure = {
                            DocumentTextState(filename = doc.filename, loading = false, error = true)
                        },
                    )
        }
    }

    fun closeDocumentText() {
        docText.value = null
    }

    // --- Phase F: mutations ---------------------------------------------

    /** `DELETE /examinations/{id}` — hard delete + cascade. Drops the cached
     *  row so the Records list updates instantly, then [onDeleted] fires so
     *  the Route pops back to Records. */
    fun deleteExam(onDeleted: () -> Unit) {
        viewModelScope.launch {
            runCatching { client.requestText("DELETE", "/examinations/$examId") }
                .onSuccess {
                    examRepo.onDeleted(examId)
                    onDeleted()
                }
        }
    }

    /** `DELETE /documents/{id}` — hard delete. Drops the cached row + any
     *  downloaded bytes instantly, then refreshes (picks up extraction-status
     *  changes on the exam). */
    fun deleteDocument(docId: String) {
        viewModelScope.launch {
            runCatching { client.requestText("DELETE", "/documents/$docId") }
                .onSuccess {
                    docRepo.onDeleted(docId)
                    byteRepo.onDeleted(docId)
                    reload()
                }
        }
    }

    /** `POST /documents/{id}/extract` — trigger OCR/NLP. Reloads so the
     *  status chip reflects "Processing". */
    fun reExtractDocument(docId: String) {
        viewModelScope.launch {
            runCatching { client.requestText("POST", "/documents/$docId/extract") }
                .onSuccess { reload() }
        }
    }

    /**
     * Open a document. Image docs → [onPreviewImage] (the Route navigates to
     * the inline Coil preview). PDF / unknown → ACTION_VIEW + the system
     * chooser via [DocumentOpener].
     *
     * **Offline-first (M4):** the bytes come from [DocumentByteRepository] —
     * a previously opened document serves from the on-device store (works
     * offline); a first open fetches + persists. When the document is not
     * downloaded and the device is offline, a clear "connect to view" toast
     * replaces the old generic download failure.
     *
     * Side-effecting: writes to cacheDir, may fire an Intent, may show a
     * Toast on failure. Kept on the VM (rather than the Route) so the
     * behaviour is testable + the Route stays a thin shim — the Context +
     * Toast plumbing are the only thing the Route supplies.
     */
    fun openDocument(
        doc: DocumentSummary,
        context: Context,
        onPreviewImage: (docId: String, filename: String?) -> Unit,
    ) {
        if (doc.isImage) {
            onPreviewImage(doc.id, doc.filename)
            return
        }
        viewModelScope.launch {
            val bytes = byteRepo.content(doc.id)
            if (bytes == null) {
                val msg =
                    if (byteRepo.canDownload()) {
                        "Couldn't download document."
                    } else {
                        "Not downloaded yet — connect to view."
                    }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val file = DocumentOpener.cacheBytes(context, doc.id, bytes, doc.filename)
            when (DocumentOpener.launch(context, file, doc.effectiveContentType)) {
                is DocumentOpener.LaunchResult.Launched -> { /* success */ }
                is DocumentOpener.LaunchResult.NoViewer -> {
                    Toast.makeText(context, "No viewer for ${doc.effectiveContentType}.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Upload a picked file as a document attached to this exam. Called from
     *  the Route after the file picker returns a Uri. Does the ContentResolver
     *  read + the base64 upload + reloads the doc list on success. */
    fun uploadDocument(
        uri: android.net.Uri,
        context: Context,
    ) {
        viewModelScope.launch {
            val resolver = context.contentResolver
            val bytes =
                runCatching {
                    withContext(Dispatchers.IO) {
                        resolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: error("Couldn't read the file.")
                    }
                }.getOrNull()
            if (bytes == null) {
                Toast.makeText(context, "Couldn't read the file.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (bytes.size.toLong() > MAX_UPLOAD_BYTES) {
                Toast.makeText(context, "File too large (25 MB cap).", Toast.LENGTH_LONG).show()
                return@launch
            }
            val filename =
                withContext(Dispatchers.IO) {
                    resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }
                } ?: "upload.bin"
            val contentType = resolver.getType(uri)
            runCatching {
                io.healthassistant.shared.data.BridgeUploads.uploadDocument(
                    client = client,
                    examId = examId,
                    filename = filename,
                    content = bytes,
                    clientRequestId =
                        java.util.UUID
                            .randomUUID()
                            .toString(),
                    contentType = contentType,
                    includeInExtraction = false,
                )
            }.onSuccess {
                Toast.makeText(context, "Uploaded $filename", Toast.LENGTH_SHORT).show()
                reload()
            }.onFailure { e ->
                Toast.makeText(context, "Upload failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val MAX_UPLOAD_BYTES = 25L * 1024 * 1024

        fun factory(
            client: BridgeClient,
            examRepo: ExaminationRepository,
            docRepo: DocumentRepository,
            byteRepo: DocumentByteRepository,
            examId: String,
        ) = viewModelFactory {
            initializer { ExaminationDetailViewModel(client, examRepo, docRepo, byteRepo, examId) }
        }
    }
}
