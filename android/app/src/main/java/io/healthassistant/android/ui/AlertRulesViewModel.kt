package io.healthassistant.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.healthassistant.android.alerts.AlertRulesRepository
import io.healthassistant.shared.alerts.AlertOp
import io.healthassistant.shared.alerts.AlertRule
import io.healthassistant.shared.data.BiomarkerSummary
import io.healthassistant.shared.data.repository.BiomarkerCatalogRepository
import io.healthassistant.shared.healthconnect.HcType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** One pickable biomarker in the rule builder (catalog entry, HcType fallback). */
data class AlertBiomarkerOption(
    val code: String,
    val name: String,
    val unit: String?,
)

/** The plain-language rule builder's form state (op/threshold stay internal —
 *  the screen speaks "above 120 for 5 minutes"). */
data class AlertEditorState(
    val editingId: String? = null,
    val code: String? = null,
    val name: String? = null,
    val unit: String? = null,
    val pickingBiomarker: Boolean = false,
    val op: AlertOp = AlertOp.GT,
    val thresholdText: String = "",
    val rangeLowText: String = "",
    val rangeHighText: String = "",
    val windowMinutes: Long = 0,
)

data class AlertRulesUiState(
    val rules: List<AlertRule> = emptyList(),
    val options: List<AlertBiomarkerOption> = emptyList(),
    val editor: AlertEditorState? = null,
    val deleting: AlertRule? = null,
)

/**
 * M5 — owns the alert rules list + the plain-language rule builder. The rules
 * come from the DataStore-backed [AlertRulesRepository]; the biomarker picker
 * merges the offline-first catalog with the HcType table (so a fresh install
 * with no cached catalog can still build rules for the 13 Health Connect
 * types). Reached from Profile › Alerts and from the biomarker detail's
 * "Set an alert" (which prefills [prefillCode]).
 */
class AlertRulesViewModel(
    private val repository: AlertRulesRepository,
    biomarkerRepo: BiomarkerCatalogRepository,
    prefillCode: String?,
) : ViewModel() {
    private val _state =
        MutableStateFlow(
            AlertRulesUiState(
                editor =
                    prefillCode?.takeIf { it.isNotBlank() }?.let {
                        AlertEditorState(code = it, name = HcType.byCode(it)?.display, unit = HcType.byCode(it)?.defaultUnit)
                    },
            ),
        )
    val state: StateFlow<AlertRulesUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.rules.collect { rules -> _state.update { it.copy(rules = rules) } }
        }
        viewModelScope.launch {
            biomarkerRepo.observeAll().collect { catalog ->
                _state.update { s ->
                    s.copy(
                        options = pickerOptions(catalog),
                        editor = s.editor?.let { e -> resolveEditorBiomarker(e, catalog) },
                    )
                }
            }
        }
    }

    fun openEditor() = _state.update { it.copy(editor = AlertEditorState(pickingBiomarker = true)) }

    fun dismissEditor() = _state.update { it.copy(editor = null) }

    fun pickBiomarker(option: AlertBiomarkerOption) =
        _state.update { s ->
            s.copy(
                editor =
                    s.editor?.copy(
                        code = option.code,
                        name = option.name,
                        unit = option.unit,
                        pickingBiomarker = false,
                    ),
            )
        }

    fun showBiomarkerPicker() = _state.update { s -> s.copy(editor = s.editor?.copy(pickingBiomarker = true)) }

    fun setOp(op: AlertOp) = _state.update { s -> s.copy(editor = s.editor?.copy(op = op)) }

    fun setThreshold(text: String) = _state.update { s -> s.copy(editor = s.editor?.copy(thresholdText = text)) }

    fun setRangeLow(text: String) = _state.update { s -> s.copy(editor = s.editor?.copy(rangeLowText = text)) }

    fun setRangeHigh(text: String) = _state.update { s -> s.copy(editor = s.editor?.copy(rangeHighText = text)) }

    fun setWindowMinutes(minutes: Long) = _state.update { s -> s.copy(editor = s.editor?.copy(windowMinutes = minutes)) }

    /** Builds the rule the editor currently describes, or null while a
     *  required bound is missing/unparseable (drives the Save button). */
    fun currentEditorRule(): AlertRule? = editorRule(_state.value.editor)

    fun save() {
        val candidate = currentEditorRule() ?: return
        viewModelScope.launch {
            repository.upsert(candidate)
            _state.update { it.copy(editor = null) }
        }
    }

    fun setEnabled(
        id: String,
        enabled: Boolean,
    ) = viewModelScope.launch { repository.setEnabled(id, enabled) }

    fun requestDelete(rule: AlertRule) = _state.update { it.copy(deleting = rule) }

    fun cancelDelete() = _state.update { it.copy(deleting = null) }

    fun confirmDelete() {
        val target = _state.value.deleting ?: return
        _state.update { it.copy(deleting = null) }
        viewModelScope.launch { repository.delete(target.id) }
    }

    private fun resolveEditorBiomarker(
        editor: AlertEditorState,
        catalog: List<BiomarkerSummary>,
    ): AlertEditorState {
        val code = editor.code ?: return editor
        val summary = catalog.firstOrNull { it.code == code } ?: return editor
        return editor.copy(name = summary.name, unit = summary.unit ?: editor.unit)
    }

    companion object {
        /** Builds the rule the editor currently describes, or null while a
         *  required bound is missing/unparseable (drives the Save button). */
        internal fun editorRule(editor: AlertEditorState?): AlertRule? {
            val e = editor ?: return null
            val code = e.code ?: return null
            return AlertRule(
                id = e.editingId ?: UUID.randomUUID().toString(),
                biomarkerCode = code,
                biomarkerName = e.name,
                op = e.op,
                threshold = e.thresholdText.trim().toDoubleOrNull(),
                rangeLow = e.rangeLowText.trim().toDoubleOrNull(),
                rangeHigh = e.rangeHighText.trim().toDoubleOrNull(),
                timeWindowSec = e.windowMinutes * SECONDS_PER_MINUTE,
            ).takeIf { it.isValid() }
        }

        /** Catalog rows win by code; the 13 HcType fallbacks guarantee picker
         *  options even with an empty offline catalog. Name-ascending. */
        internal fun pickerOptions(catalog: List<BiomarkerSummary>): List<AlertBiomarkerOption> {
            val byCode =
                buildMap {
                    HcType.entries.forEach { type -> put(type.code, AlertBiomarkerOption(type.code, type.display, type.defaultUnit)) }
                    catalog.forEach { summary ->
                        summary.code?.let { code -> put(code, AlertBiomarkerOption(code, summary.name, summary.unit)) }
                    }
                }
            return byCode.values.sortedBy { it.name.lowercase() }
        }

        private const val SECONDS_PER_MINUTE = 60L

        fun factory(
            repository: AlertRulesRepository,
            biomarkerRepo: BiomarkerCatalogRepository,
            prefillCode: String?,
        ) = viewModelFactory {
            initializer { AlertRulesViewModel(repository, biomarkerRepo, prefillCode) }
        }
    }
}
