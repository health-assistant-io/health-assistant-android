package io.healthassistant.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.healthassistant.android.R
import io.healthassistant.android.data.cache.ObservationDatabase
import io.healthassistant.android.data.cache.RoomCaches
import io.healthassistant.android.data.repository.BridgeDocumentByteGateway
import io.healthassistant.android.data.repository.BridgeDocumentGateway
import io.healthassistant.android.data.repository.BridgeExaminationGateway
import io.healthassistant.android.ui.components.RichText
import io.healthassistant.bridge.BridgeClient
import io.healthassistant.shared.data.DocumentSummary
import io.healthassistant.shared.data.ExaminationSummary
import io.healthassistant.shared.data.cache.DocumentByteStore
import io.healthassistant.shared.data.repository.ConnectivityProvider
import io.healthassistant.shared.data.repository.DocumentByteRepository
import io.healthassistant.shared.data.repository.DocumentRepository
import io.healthassistant.shared.data.repository.ExaminationRepository
import org.koin.compose.koinInject

/**
 * Phase A documents fix — the native examination detail screen, replacing the
 * read-only `exam.html` WebView for the document-listing flow.
 *
 * Renders the exam metadata (date, notes, diagnoses) + the document list with
 * per-row file type icon, size, status chip, and tap-to-open behaviour:
 *  - Images render inline on a follow-up screen via Coil.
 *  - PDF / unknown types are written to the cache dir + handed to the system
 *    viewer via FileProvider + ACTION_VIEW.
 *
 * The full PWA record (rich edits, AI extraction, etc.) stays reachable via
 * the "Open in browser" action — same escape hatch the old WebView page had.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExaminationDetailScreen(
    exam: ExaminationSummary?,
    documents: List<DocumentSummary>,
    docThumbnails: Map<String, ByteArray>,
    state: DocListState,
    docText: DocumentTextState?,
    extraction: ExtractionStatusState?,
    onOpenDocument: (DocumentSummary) -> Unit,
    onViewDocumentText: (DocumentSummary) -> Unit,
    onCloseDocumentText: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onDeleteExam: () -> Unit,
    onDeleteDocument: (String) -> Unit,
    onReExtractDocument: (String) -> Unit,
    onUpload: () -> Unit,
) {
    var showDeleteExamDialog by remember { mutableStateOf(false) }
    var showDeleteDocDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    var docMenuFor by remember { mutableStateOf<String?>(null) }

    BackHandler(onBack = onBack)
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.records_exam_detail)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "More")
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Open in browser") },
                            onClick = {
                                menuExpanded = false
                                onOpenInBrowser()
                            },
                        )
                        androidx.compose.material3.HorizontalDivider()
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Delete exam") },
                            onClick = {
                                menuExpanded = false
                                showDeleteExamDialog = true
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            if (exam != null) {
                Text(
                    exam.examinationDate ?: exam.id,
                    style = MaterialTheme.typography.headlineSmall,
                )
                val meta =
                    buildList {
                        exam.category?.let { add(it) }
                        exam.labName?.let { add(it) }
                    }.joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (exam.diagnoses.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement =
                            Arrangement.spacedBy(
                                8.dp,
                            ),
                    ) {
                        exam.diagnoses.take(3).forEach { dx ->
                            DiagnosisChip(dx)
                        }
                    }
                    if (exam.diagnoses.size > 3) {
                        Text(
                            "+" + (exam.diagnoses.size - 3) + " more",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                exam.notes?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        "Doctor's notes",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    RichText(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                }
                exam.patientNotes?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        "Your notes",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    RichText(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                }
                exam.impressions?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        "AI impressions",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    RichText(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                }
            } else {
                Text("…", style = MaterialTheme.typography.headlineSmall)
            }

            extraction?.let { ext ->
                if (ext.status?.lowercase() in
                    setOf("pending", "processing", "aggregating", "analyzing_text", "defining_ontology", "persisting_results")
                ) {
                    Spacer(Modifier.height(12.dp))
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { (ext.progress.coerceIn(0, 100)) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.exam_extracting_progress, ext.progress.coerceIn(0, 100)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                } else if (ext.errorMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        ext.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.records_documents_section),
                    style = MaterialTheme.typography.titleMedium,
                )
                androidx.compose.material3.TextButton(onClick = onUpload) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Upload")
                }
            }
            Spacer(Modifier.height(8.dp))

            when (state) {
                DocListState.Loading ->
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                DocListState.Error ->
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.records_docs_load_failed),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onRetry) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                DocListState.Loaded ->
                    if (documents.isEmpty()) {
                        Text(
                            stringResource(R.string.records_no_documents),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    } else {
                        documents.forEach { doc ->
                            DocumentRow(
                                doc = doc,
                                thumbnail = docThumbnails[doc.id],
                                onTap = { onOpenDocument(doc) },
                                onDelete = { showDeleteDocDialog = doc.id to (doc.filename ?: "document") },
                                onReExtract = { onReExtractDocument(doc.id) },
                                onViewText = { onViewDocumentText(doc) },
                            )
                            HorizontalDivider()
                        }
                    }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // Delete-exam confirmation.
    if (showDeleteExamDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDeleteExamDialog = false },
            title = { Text("Delete examination?") },
            text = { Text("This examination and all its documents will be permanently deleted.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showDeleteExamDialog = false
                    onDeleteExam()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showDeleteExamDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
    // Delete-document confirmation.
    showDeleteDocDialog?.let { (docId, name) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDeleteDocDialog = null },
            title = { Text("Delete document?") },
            text = { Text("'$name' will be permanently deleted.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showDeleteDocDialog = null
                    onDeleteDocument(docId)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showDeleteDocDialog = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    docText?.let { textState ->
        DocumentTextSheet(
            state = textState,
            onDismiss = onCloseDocumentText,
        )
    }
}

/** R4 — the OCR-extracted Markdown of one document, rendered rich. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentTextSheet(
    state: DocumentTextState,
    onDismiss: () -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                state.filename ?: "Extracted text",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            when {
                state.loading ->
                    Box(
                        Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                state.error ->
                    Text(
                        "Couldn't load the extracted text. Check your connection and try again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                state.text == null ->
                    Text(
                        "No extracted text yet — extraction may still be running.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                else -> {
                    RichText(
                        state.text,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.truncated) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Text truncated — open in browser for the full document.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}

/** Loading state for the documents list. Lives in ExaminationDetailViewModel.kt. */

@Composable
private fun DocumentRow(
    doc: DocumentSummary,
    thumbnail: ByteArray?,
    onTap: () -> Unit,
    onDelete: () -> Unit,
    onReExtract: () -> Unit,
    onViewText: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (doc.isImage && thumbnail != null) {
            AsyncImage(
                model =
                    ImageRequest
                        .Builder(LocalContext.current)
                        .data(thumbnail)
                        .crossfade(true)
                        .build(),
                contentDescription = doc.filename,
                modifier = Modifier.size(48.dp),
            )
        } else {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = iconForDoc(doc),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                doc.filename ?: stringResource(R.string.records_unnamed_document),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub =
                buildList {
                    doc.displaySize?.let { add(it) }
                    prettyStatus(doc.status)?.let { add(it) }
                }.joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        // Per-document overflow: Re-extract + Delete (Phase F).
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "More", modifier = Modifier.size(20.dp))
            }
            androidx.compose.material3.DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("View extracted text") },
                    onClick = {
                        menuExpanded = false
                        onViewText()
                    },
                )
                androidx.compose.material3.HorizontalDivider()
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Re-extract") },
                    onClick = {
                        menuExpanded = false
                        onReExtract()
                    },
                )
                androidx.compose.material3.HorizontalDivider()
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    },
                )
            }
        }
    }
}

private fun iconForDoc(doc: DocumentSummary): ImageVector =
    when {
        doc.isImage -> Icons.Outlined.Image
        doc.isPdf -> Icons.Outlined.PictureAsPdf
        else -> Icons.Outlined.Description
    }

@Composable
private fun DiagnosisChip(diagnosis: String) {
    androidx.compose.material3.Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            diagnosis,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Human-readable status label, or null when the doc is "completed" (no chip). */
private fun prettyStatus(status: String?): String? {
    if (status.isNullOrBlank() || status == "completed") return null
    return when (status.lowercase()) {
        "pending" -> "Pending"
        "processing", "aggregating", "analyzing_text", "defining_ontology", "persisting_results" -> "Processing"
        "failed" -> "Failed"
        else -> status.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

/**
 * Stateful route (Phase D refactor): builds [ExaminationDetailViewModel] via
 * its factory + collects its single [ExaminationDetailUiState] flow. The
 * document-open intent (image → navigation; PDF/other → external viewer) is
 * delegated to the VM via [ExaminationDetailViewModel.openDocument], which
 * takes the Context for FileProvider + Toast plumbing. The pure-state
 * [ExaminationDetailScreen] composable is unchanged.
 */
@Composable
fun ExaminationDetailRoute(
    client: BridgeClient,
    examId: String,
    onBack: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onPreviewImage: (docId: String, filename: String?) -> Unit,
) {
    val context = LocalContext.current
    val db: ObservationDatabase = koinInject()
    val byteStore: DocumentByteStore = koinInject()
    val connectivity: ConnectivityProvider = koinInject()
    val caches = remember(client) { RoomCaches(db, client.integrationId) }
    val examRepo =
        remember(client) {
            ExaminationRepository(caches.examinations, BridgeExaminationGateway(client), connectivity, caches.meta)
        }
    val docRepo =
        remember(client) {
            DocumentRepository(caches.documents, BridgeDocumentGateway(client), connectivity, caches.meta)
        }
    val byteRepo =
        remember(client) {
            DocumentByteRepository(byteStore, caches.documents, BridgeDocumentByteGateway(client), connectivity)
        }
    val vm: ExaminationDetailViewModel =
        viewModel(
            factory = ExaminationDetailViewModel.factory(client, examRepo, docRepo, byteRepo, examId),
        )
    val state by vm.state.collectAsStateWithLifecycle()

    // Phase F: file picker for document upload — managed from the exam detail
    // page (not the Records card, per the user's simplification request).
    val picker =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts
                .OpenDocument(),
        ) { uri ->
            if (uri != null) vm.uploadDocument(uri, context)
        }

    ExaminationDetailScreen(
        exam = state.exam,
        documents = state.documents,
        docThumbnails = state.thumbnails,
        state = state.docState,
        docText = state.docText,
        extraction = state.extraction,
        onOpenDocument = { doc -> vm.openDocument(doc, context, onPreviewImage) },
        onViewDocumentText = { doc -> vm.openDocumentText(doc) },
        onCloseDocumentText = { vm.closeDocumentText() },
        onOpenInBrowser = onOpenInBrowser,
        onRetry = { vm.reload() },
        onBack = onBack,
        onDeleteExam = { vm.deleteExam(onBack) },
        onDeleteDocument = { docId -> vm.deleteDocument(docId) },
        onReExtractDocument = { docId -> vm.reExtractDocument(docId) },
        onUpload = { picker.launch(arrayOf("*/*")) },
    )
}

/**
 * Single-document preview screen — image inline via Coil. PDFs / DICOM / other
 * go through the external viewer (handled in [openDocument]); this screen is
 * reached only for image MIME types.
 *
 * **Offline-first (M4):** the bytes come from the [DocumentByteRepository] —
 * a previously viewed image serves from the on-device store (offline); a first
 * view fetches + persists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentPreviewRoute(
    client: BridgeClient,
    docId: String,
    filename: String?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val db: ObservationDatabase = koinInject()
    val byteStore: DocumentByteStore = koinInject()
    val connectivity: ConnectivityProvider = koinInject()
    val notDownloadedMsg = stringResource(R.string.records_doc_not_downloaded)
    val byteRepo =
        remember(client) {
            DocumentByteRepository(
                byteStore,
                RoomCaches(db, client.integrationId).documents,
                BridgeDocumentByteGateway(client),
                connectivity,
            )
        }
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    BackHandler(onBack = onBack)
    LaunchedEffect(docId) {
        bytes = byteRepo.content(docId)
        if (bytes == null) {
            error = notDownloadedMsg
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(filename ?: stringResource(R.string.records_document)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            val b = bytes
            val e = error
            when {
                e != null -> Text(e, color = MaterialTheme.colorScheme.error)
                b == null -> CircularProgressIndicator()
                else ->
                    AsyncImage(
                        model =
                            ImageRequest
                                .Builder(context)
                                .data(b)
                                .crossfade(true)
                                .build(),
                        contentDescription = filename,
                        modifier = Modifier.fillMaxSize(),
                    )
            }
        }
    }
}
