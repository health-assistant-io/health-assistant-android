package io.healthassistant.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.healthassistant.android.settings.UiMode
import io.healthassistant.shared.onboarding.ConnectionCredential
import io.healthassistant.shared.onboarding.Onboarding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Validation failures the onboarding form can surface, mapped to strings by
 *  the screen (no string resources in the VM). */
sealed interface OnboardingError {
    /** The pasted / scanned code is not a connection code or deep link. */
    data object InvalidCode : OnboardingError

    /** The manual fields don't form a valid connection. */
    data object ManualInvalid : OnboardingError

    /** The QR scanner failed; [detail] is the scanner's message. */
    data class ScanFailed(
        val detail: String,
    ) : OnboardingError
}

/** UI state for the onboarding form (mode pick + QR / paste-code / manual).
 *  The transient probe state (connecting spinner + server error) is AppRoot-
 *  owned and passed to the screen separately. */
data class OnboardingUiState(
    val code: String = "",
    val showManual: Boolean = false,
    val manualBase: String = "",
    val manualId: String = "",
    val manualSecret: String = "",
    val error: OnboardingError? = null,
    val mode: UiMode = UiMode.SIMPLE,
)

/**
 * Owns the onboarding form state + validation (Phase D migration; R7's three
 * tiers) plus the K.4 Simple/Advanced mode pick — the pick seeds from
 * [initialMode] (the stored pref) and persists on tap through [persistMode].
 * All parsing goes through the pure-Kotlin [Onboarding] parsers, so
 * the whole form — including a half-typed manual connection — survives
 * process death and config changes.
 *
 * The Android touchpoints stay outside: QR scanning (GmsBarcodeScanning,
 * needs an Activity) is launched by the host, which feeds results back via
 * [onScanResult] / [onScanFailed]; the connect flow (probe + credential
 * store, owned by AppRoot) is injected as the [connect] callback. The
 * transient probe state (connecting + server error) stays AppRoot-owned.
 */
class OnboardingViewModel(
    prefilled: ConnectionCredential?,
    private val connect: (ConnectionCredential) -> Unit,
    initialMode: UiMode = UiMode.SIMPLE,
    private val persistMode: (UiMode) -> Unit = {},
) : ViewModel() {
    private val _state =
        MutableStateFlow(
            OnboardingUiState(
                code =
                    prefilled?.let {
                        listOfNotNull(it.baseUrl, it.integrationId, it.apiSecret).joinToString("|")
                    } ?: "",
                manualBase = prefilled?.baseUrl ?: "",
                manualId = prefilled?.integrationId ?: "",
                manualSecret = prefilled?.apiSecret ?: "",
                mode = initialMode,
            ),
        )
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    /** K.4 — the Simple/Advanced pick; persisted on tap (instant, no restart). */
    fun onModeChange(mode: UiMode) {
        _state.value = _state.value.copy(mode = mode)
        persistMode(mode)
    }

    fun onCodeChange(code: String) {
        _state.value = _state.value.copy(code = code)
    }

    fun toggleManual() {
        _state.value = _state.value.copy(showManual = !_state.value.showManual)
    }

    fun onManualBaseChange(value: String) {
        _state.value = _state.value.copy(manualBase = value)
    }

    fun onManualIdChange(value: String) {
        _state.value = _state.value.copy(manualId = value)
    }

    fun onManualSecretChange(value: String) {
        _state.value = _state.value.copy(manualSecret = value)
    }

    /** QR scan succeeded — treat the raw payload exactly like a pasted code. */
    fun onScanResult(raw: String) {
        submitCode(raw)
    }

    fun onScanFailed(detail: String) {
        _state.value = _state.value.copy(error = OnboardingError.ScanFailed(detail))
    }

    fun submitCode(raw: String) {
        val cred = Onboarding.parseQr(raw) ?: Onboarding.parseDeepLink(raw)
        if (cred == null) {
            _state.value = _state.value.copy(error = OnboardingError.InvalidCode)
        } else {
            _state.value = _state.value.copy(error = null)
            connect(cred)
        }
    }

    fun submitManual() {
        val s = _state.value
        val cred = Onboarding.validateManual(s.manualBase, s.manualId, s.manualSecret.ifBlank { null })
        if (cred == null) {
            _state.value = s.copy(error = OnboardingError.ManualInvalid)
        } else {
            _state.value = s.copy(error = null)
            connect(cred)
        }
    }

    companion object {
        fun factory(
            prefilled: ConnectionCredential?,
            connect: (ConnectionCredential) -> Unit,
            initialMode: UiMode = UiMode.SIMPLE,
            persistMode: (UiMode) -> Unit = {},
        ) = viewModelFactory {
            initializer {
                OnboardingViewModel(prefilled, connect, initialMode, persistMode)
            }
        }
    }
}
