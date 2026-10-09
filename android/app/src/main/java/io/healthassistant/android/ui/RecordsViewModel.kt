package io.healthassistant.android.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.healthassistant.bridge.BridgeClient
import io.healthassistant.shared.data.BridgeUploads
import io.healthassistant.shared.data.ExaminationSummary
import io.healthassistant.shared.data.repository.ExaminationRepository
import io.healthassistant.shared.data.repository.RefreshOutcome
import io.healthassistant.shared.source.ManualEntrySource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Phase D — the Records tab state, owned by [RecordsViewModel]. Carries the
 * exam-list load state + the inline upload status string (separate concerns:
 * the list might be Loaded while an upload is in flight).
 *
 * Kept as a sealed interface so the screen's `when (state)` branch is
 * exhaustive — adding a new state (e.g. an "auth expired" branch) forces a
 * compile error everywhere it's consumed.
 */
sealed interface RecordsUiState {
    data object Loading : RecordsUiState

    data class Loaded(
        val exams: List<ExaminationSummary>,
    ) : RecordsUiState

    data class Error(
        val message: String,
    ) : RecordsUiState
}

/** Result of an upload (success/failure with the filename). Kept as a
 *  standalone type so it's testable without Compose. */
sealed interface UploadResult {
    data object None : UploadResult

    data object Uploading : UploadResult

    data class Uploaded(
        val filename: String,
    ) : UploadResult

    data class Failed(
        val message: String,
    ) : UploadResult

    data object TooLarge : UploadResult
}

/**
 * Owns the Records tab's data flow + the document-upload pipeline
 * (Phase D). What moved here from the old `RecordsRoute`:
 *
 * - The exam-list load — **offline-first (M3):** the list reads from the
 *   examination cache via [ExaminationRepository.observeAll] (Room-backed,
 *   reactive, offline). [reload] only refreshes the cache from the bridge; a
 *   failure or offline leaves the saved list on screen. The `Error` state
 *   appears only when there is nothing cached AND the refresh can't succeed.
 * - The ContentResolver filename + size queries (Phase A.5 — early
 *   rejection of > 25 MiB picks before `readBytes()` OOMs a low-end device).
 * - The base64 upload via `BridgeUploads.uploadDocument`.
 * - The manual-reading submit to `ManualEntrySource` (queued for the next
 *   sync — the sync scheduler fires separately).
 *
 * The Route builds this VM via [factory] (the per-connection [BridgeClient]
 * is the non-Koin dep; [ManualEntrySource] is Koin-resolved at the call site).
 */
class RecordsViewModel(
    private val client: BridgeClient,
    private val manualSource: ManualEntrySource,
    private val examRepo: ExaminationRepository,
    private val loadFailedMsg: String,
    private val uploadCapBytes: Long = MAX_UPLOAD_BYTES,
) : ViewModel() {
    private val _upload = MutableStateFlow<UploadResult>(UploadResult.None)
    val upload: StateFlow<UploadResult> = _upload.asStateFlow()

    // The outcome of the most recent refresh attempt; null until the first one
    // settles (drives Loading → Loaded/Error for an empty cache).
    private val refreshOutcome = MutableStateFlow<RefreshOutcome?>(null)

    val state: StateFlow<RecordsUiState> =
        combine(examRepo.observeAll(), refreshOutcome) { exams, outcome ->
            when {
                exams.isNotEmpty() -> RecordsUiState.Loaded(exams)
                outcome == null -> RecordsUiState.Loading
                outcome == RefreshOutcome.REFRESHED -> RecordsUiState.Loaded(exams)
                else -> RecordsUiState.Error(loadFailedMsg)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, RecordsUiState.Loading)

    init {
        reload()
    }

    /** Refresh the exam list cache from the bridge. Called on first load + the
     *  screen's "Retry" button + after every successful upload / create (so
     *  the new doc's parent exam surfaces). No-op on the network when offline —
     *  the cache keeps rendering the saved list. */
    fun reload() {
        viewModelScope.launch {
            refreshOutcome.value = examRepo.refresh()
        }
    }

    /** Phase A.5 — upload a picked document. The size pre-check happens via
     *  OpenableColumns.SIZE so a 100 MB pick never reaches readBytes() and
     *  OOMs a low-end device. The bridge's 25 MiB cap is enforced server-side
     *  too; this is the early client-side guard. */
    fun uploadDocument(
        exam: ExaminationSummary,
        uri: Uri,
        resolver: ContentResolver,
    ) {
        viewModelScope.launch {
            _upload.value = UploadResult.Uploading
            val sizeBytes = resolver.querySize(uri) ?: -1L
            if (sizeBytes > uploadCapBytes) {
                _upload.value = UploadResult.TooLarge
                return@launch
            }
            val outcome =
                runCatching {
                    val bytes =
                        resolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: error("Couldn't read the file.")
                    if (bytes.size.toLong() > uploadCapBytes) {
                        _upload.value = UploadResult.TooLarge
                        return@launch
                    }
                    val filename = resolver.queryFileName(uri) ?: "upload.bin"
                    val contentType = resolver.getType(uri)
                    BridgeUploads.uploadDocument(
                        client = client,
                        examId = exam.id,
                        filename = filename,
                        content = bytes,
                        clientRequestId = UUID.randomUUID().toString(),
                        contentType = contentType,
                        includeInExtraction = false,
                    )
                    UploadResult.Uploaded(filename)
                }.getOrElse { UploadResult.Failed(it.message ?: "Upload failed.") }
            _upload.value = outcome
            // A successful upload should surface the new doc on the parent
            // exam — reload the list.
            if (outcome is UploadResult.Uploaded) reload()
        }
    }

    /** Submit a manual reading (queued for the next sync via [ManualEntrySource]). */
    fun addReading(
        type: io.healthassistant.shared.healthconnect.HcType,
        value: Double,
    ) {
        viewModelScope.launch {
            manualSource.submit(
                io.healthassistant.shared.healthconnect.RawSample(
                    hcType = type,
                    value = value,
                    unit = type.defaultUnit,
                    timestamp =
                        java.time.Instant
                            .now()
                            .toString(),
                ),
            )
        }
    }

    /** Create a new examination via `POST /examinations`. On success the
     *  list reloads so the new exam appears. */
    fun createExam(
        date: String?,
        notes: String?,
    ) {
        viewModelScope.launch {
            // Use Android's built-in org.json (kotlinx-serialization-json is
            // not on :app's compile classpath — it's an impl dep of the SDK).
            val body =
                org.json
                    .JSONObject()
                    .apply {
                        date?.takeIf { it.isNotBlank() }?.let { put("date", it) }
                        notes?.takeIf { it.isNotBlank() }?.let { put("notes", it) }
                        put("auto_extract_metadata", false)
                    }.toString()
                    .encodeToByteArray()
            runCatching {
                client.requestText("POST", "/examinations", body)
            }.onSuccess { reload() }
        }
    }

    /** Surface the upload status as a single human-readable string for the
     *  Compose status line (kept in the VM so the test gate asserts it
     *  instead of needing a Compose test). Delegates to the pure
     *  [formatUploadStatus] so the mapping is unit-testable without a VM. */
    fun uploadStatusText(
        uploading: String,
        uploadedTemplate: String,
        failedTemplate: String,
        tooLarge: String,
    ): String? = formatUploadStatus(_upload.value, uploading, uploadedTemplate, failedTemplate, tooLarge)

    companion object {
        /** The bridge upload cap (provider.py MAX_UPLOAD_BYTES). Mirrored
         *  locally so the client rejects an oversized pick BEFORE reading it
         *  into memory. */
        const val MAX_UPLOAD_BYTES = 25L * 1024 * 1024

        fun factory(
            client: BridgeClient,
            manualSource: ManualEntrySource,
            examRepo: ExaminationRepository,
            loadFailedMsg: String,
        ) = viewModelFactory {
            initializer { RecordsViewModel(client, manualSource, examRepo, loadFailedMsg) }
        }
    }
}

/** Pure mapping from an [UploadResult] to the user-facing status line. Top-level
 *  so it's unit-testable without instantiating the VM (which would need a fake
 *  BridgeClient + ManualEntrySource — tracked as a follow-up after mockk or
 *  an interface extraction lands). */
fun formatUploadStatus(
    result: UploadResult,
    uploading: String,
    uploadedTemplate: String,
    failedTemplate: String,
    tooLarge: String,
): String? =
    when (result) {
        UploadResult.None -> null
        UploadResult.Uploading -> uploading
        is UploadResult.Uploaded -> uploadedTemplate.format(result.filename)
        is UploadResult.Failed -> failedTemplate.format(result.message)
        UploadResult.TooLarge -> tooLarge
    }

/** Best-effort filename via OpenableColumns.DISPLAY_NAME. */
private fun ContentResolver.queryFileName(uri: Uri): String? =
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }

/** Best-effort byte size via OpenableColumns.SIZE. Returns null when the
 *  provider doesn't expose it (some cloud Pickers); the caller re-checks
 *  the actual ByteArray.size after readBytes(). */
private fun ContentResolver.querySize(uri: Uri): Long? =
    query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
        if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
    }
