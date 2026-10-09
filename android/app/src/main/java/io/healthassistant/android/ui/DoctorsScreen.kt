package io.healthassistant.android.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.healthassistant.android.R
import io.healthassistant.bridge.BridgeClient
import io.healthassistant.bridge.Doctor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class DoctorsUiState(
    val loading: Boolean = true,
    val doctors: List<Doctor> = emptyList(),
    val failed: Boolean = false,
)

/** R6 — the tenant doctor directory (`GET /doctors`, tenant-scoped). */
class DoctorsViewModel(
    private val client: BridgeClient,
) : ViewModel() {
    private val _state = MutableStateFlow(DoctorsUiState())
    val state: StateFlow<DoctorsUiState> = _state

    init {
        reload()
    }

    fun reload() {
        _state.value = _state.value.copy(loading = _state.value.doctors.isEmpty(), failed = false)
        viewModelScope.launch {
            val docs = runCatching { client.getDoctors().data }.getOrNull()
            _state.value =
                if (docs == null) {
                    DoctorsUiState(loading = false, failed = true)
                } else {
                    DoctorsUiState(loading = false, doctors = docs)
                }
        }
    }

    companion object {
        fun factory(client: BridgeClient) =
            viewModelFactory {
                initializer { DoctorsViewModel(client) }
            }
    }
}

@Composable
fun DoctorsRoute(
    client: BridgeClient,
    onBack: () -> Unit,
) {
    val vm: DoctorsViewModel = viewModel(factory = DoctorsViewModel.factory(client))
    val state by vm.state.collectAsStateWithLifecycle()
    DoctorsScreen(state = state, onBack = onBack, onRetry = vm::reload)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoctorsScreen(
    state: DoctorsUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.doctors_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.failed ->
                    Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.doctors_failed), color = MaterialTheme.colorScheme.error)
                        androidx.compose.material3.TextButton(onClick = onRetry) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                state.doctors.isEmpty() ->
                    Text(
                        stringResource(R.string.doctors_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                else ->
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(state.doctors, key = { it.id }) { doctor ->
                            ListItem(
                                leadingContent = {
                                    Icon(
                                        Icons.Outlined.LocalHospital,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                headlineContent = {
                                    Text(doctor.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                supportingContent = {
                                    doctor.specialty?.let {
                                        Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                },
                                trailingContent = {
                                    androidx.compose.foundation.layout.Row {
                                        doctor.email?.let { email ->
                                            IconButton(onClick = {
                                                runCatching {
                                                    context.startActivity(
                                                        Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email")),
                                                    )
                                                }
                                            }) { Icon(Icons.Outlined.Email, contentDescription = stringResource(R.string.doctors_email)) }
                                        }
                                        doctor.phone?.let { phone ->
                                            IconButton(onClick = {
                                                runCatching {
                                                    context.startActivity(
                                                        Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")),
                                                    )
                                                }
                                            }) { Icon(Icons.Outlined.Phone, contentDescription = stringResource(R.string.doctors_call)) }
                                        }
                                    }
                                },
                            )
                        }
                    }
            }
        }
    }
}
