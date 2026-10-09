package io.healthassistant.android.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.healthassistant.android.R
import io.healthassistant.android.data.cache.ObservationDatabase
import io.healthassistant.android.data.cache.RoomCaches
import io.healthassistant.android.data.repository.BridgeExaminationGateway
import io.healthassistant.android.ui.components.StaleChipSlot
import io.healthassistant.android.ui.theme.LocalDarkTheme
import io.healthassistant.android.work.SyncScheduler
import io.healthassistant.bridge.BridgeClient
import io.healthassistant.shared.data.ExaminationSummary
import io.healthassistant.shared.data.cache.CacheDomain
import io.healthassistant.shared.data.repository.ConnectivityProvider
import io.healthassistant.shared.data.repository.ExaminationRepository
import io.healthassistant.shared.healthconnect.HcType
import io.healthassistant.shared.richtext.toSnippet
import io.healthassistant.shared.source.ManualEntrySource
import org.koin.compose.koinInject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val PROCESSING_STATUSES =
    setOf(
        "processing",
        "aggregating",
        "analyzing_text",
        "defining_ontology",
        "persisting_results",
    )

/**
 * Stateful owner of the Examinations screen (a detail route reached from the
 * Records hub). Builds the [RecordsViewModel] (exam list + mutations) + wires
 * the FAB dropdown actions ("New examination" / "Add reading").
 */
@Composable
fun ExaminationsRoute(
    client: BridgeClient,
    onBack: () -> Unit,
    onOpenExam: (String) -> Unit,
) {
    val context = LocalContext.current
    val manualSource: ManualEntrySource = koinInject()
    val db: ObservationDatabase = koinInject()
    val connectivity: ConnectivityProvider = koinInject()
    val loadFailedMsg = stringResource(R.string.records_load_failed)
    val caches = remember(client) { RoomCaches(db, client.integrationId) }
    val examRepo =
        remember(client) {
            ExaminationRepository(caches.examinations, BridgeExaminationGateway(client), connectivity, caches.meta)
        }
    val vm: RecordsViewModel = viewModel(factory = RecordsViewModel.factory(client, manualSource, examRepo, loadFailedMsg))
    val state by vm.state.collectAsStateWithLifecycle()
    val staleMeta by caches.meta.observe(CacheDomain.EXAMINATIONS).collectAsStateWithLifecycle(initialValue = null)
    val online = rememberConnectivity() == Connectivity.Online

    ExaminationsScreen(
        state = state,
        staleMeta = staleMeta,
        online = online,
        onBack = onBack,
        onOpenExam = { exam -> onOpenExam(exam.id) },
        onRetry = { vm.reload() },
        onCreateExam = { date, notes -> vm.createExam(date, notes) },
        onAddReading = { type, value ->
            vm.addReading(type, value)
            SyncScheduler.syncNow(context)
        },
    )
}

/**
 * The Examinations screen — a tappable list of examinations + a FAB that opens
 * a dropdown with "New examination" and "Add reading". Pure state + lambdas.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExaminationsScreen(
    state: RecordsUiState,
    staleMeta: io.healthassistant.shared.data.cache.CacheMetaState? = null,
    online: Boolean = true,
    onBack: () -> Unit,
    onOpenExam: (ExaminationSummary) -> Unit,
    onRetry: () -> Unit = {},
    onCreateExam: (String, String) -> Unit,
    onAddReading: (HcType, Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    var fabMenuExpanded by remember { mutableStateOf(false) }
    var showExamDialog by remember { mutableStateOf(false) }
    var showReadingDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.records_examinations)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { fabMenuExpanded = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Create")
            }
        },
    ) { padding ->
        Box(
            modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                StaleChipSlot(meta = staleMeta, online = online, onRetry = onRetry)
                when (val s = state) {
                    RecordsUiState.Loading ->
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) { CircularProgressIndicator() }

                    is RecordsUiState.Error ->
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(s.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = onRetry) {
                                Text(stringResource(R.string.action_retry))
                            }
                        }

                    is RecordsUiState.Loaded ->
                        if (s.exams.isEmpty()) {
                            Text(
                                stringResource(R.string.records_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        } else {
                            Column {
                                s.exams.forEach { exam ->
                                    ExamCard(exam, onOpenExam)
                                    HorizontalDivider()
                                }
                            }
                        }
                }
                Spacer(Modifier.height(80.dp))
            }

            // FAB dropdown anchored bottom-end.
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                DropdownMenu(
                    expanded = fabMenuExpanded,
                    onDismissRequest = { fabMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("New examination") },
                        onClick = {
                            fabMenuExpanded = false
                            showExamDialog = true
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Add reading") },
                        onClick = {
                            fabMenuExpanded = false
                            showReadingDialog = true
                        },
                    )
                }
            }
        }
    }

    if (showExamDialog) {
        NewExamDialog(
            onConfirm = { date, notes ->
                onCreateExam(date, notes)
                showExamDialog = false
            },
            onDismiss = { showExamDialog = false },
        )
    }
    if (showReadingDialog) {
        AddReadingDialog(
            onConfirm = { type, value ->
                onAddReading(type, value)
                showReadingDialog = false
            },
            onDismiss = { showReadingDialog = false },
        )
    }
}

@Composable
private fun ExamCard(
    exam: ExaminationSummary,
    onOpenExam: (ExaminationSummary) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onOpenExam(exam) }
            .padding(vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    exam.examinationDate ?: exam.id,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                exam.notes?.let { notes ->
                    toSnippet(notes, 100)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            ExtractionStatusChip(exam.extractionStatus)
        }
    }
}

// ---------------------------------------------------------------------------
// New examination dialog — date picker + notes text area.
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewExamDialog(
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var notes by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }

    if (showDatePicker) {
        val datePickerState =
            rememberDatePickerState(
                initialSelectedDateMillis = selectedDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        selectedDate =
                            Instant
                                .ofEpochMilli(millis)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New examination") },
        text = {
            Column {
                OutlinedTextField(
                    value = selectedDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Date") },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { showDatePicker = true },
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(selectedDate.format(DateTimeFormatter.ISO_LOCAL_DATE), notes)
            }) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ---------------------------------------------------------------------------
// Add reading dialog — searchable biomarker dropdown + value input.
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddReadingDialog(
    onConfirm: (HcType, Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf<HcType?>(null) }
    var valueText by remember { mutableStateOf("") }

    val filtered =
        remember(searchQuery) {
            val q = searchQuery.trim().lowercase()
            if (q.isEmpty()) HcType.entries else HcType.entries.filter { it.display.lowercase().contains(q) }
        }

    ModalBottomSheet(
        onDismissRequest = {
            selectedType = null
            valueText = ""
            onDismiss()
        },
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Text("Add a reading", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            if (selectedType == null) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search biomarkers") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                LazyColumn(
                    Modifier.fillMaxWidth().height(320.dp),
                ) {
                    items(filtered) { type ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { selectedType = type }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector =
                                    io.healthassistant.android.ui.components
                                        .biomarkerIcon(type),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(type.display, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "Unit: ${type.defaultUnit}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }
            } else {
                val type = selectedType!!
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(type.display, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        selectedType = null
                        valueText = ""
                    }) { Text("Change") }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = valueText,
                    onValueChange = { valueText = it },
                    label = { Text("Value (${type.defaultUnit})") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                androidx.compose.material3.Button(
                    onClick = {
                        valueText.trim().toDoubleOrNull()?.let { v ->
                            onConfirm(type, v)
                        }
                    },
                    enabled = valueText.trim().toDoubleOrNull() != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add ${type.display}") }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Status chip (unchanged from before).
// ---------------------------------------------------------------------------

@Composable
private fun ExtractionStatusChip(status: String?) {
    val dark = LocalDarkTheme.current
    val (label, container, content) =
        when {
            status == null || status == "pending" ->
                Triple(
                    stringResource(R.string.exam_status_pending),
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )

            status in PROCESSING_STATUSES ->
                Triple(
                    stringResource(R.string.exam_status_processing),
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                )

            status == "completed" ->
                Triple(
                    stringResource(R.string.exam_status_processed),
                    if (dark) Color(0xFF1B3A22) else Color(0xFFD7F2DC),
                    if (dark) Color(0xFFB9F2C4) else Color(0xFF0E4A1A),
                )

            else ->
                Triple(
                    stringResource(R.string.exam_status_failed),
                    MaterialTheme.colorScheme.errorContainer,
                    MaterialTheme.colorScheme.onErrorContainer,
                )
        }
    Surface(shape = MaterialTheme.shapes.small, color = container, contentColor = content) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
