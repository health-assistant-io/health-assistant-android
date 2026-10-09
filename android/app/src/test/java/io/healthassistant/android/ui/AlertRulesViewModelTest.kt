package io.healthassistant.android.ui

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import io.healthassistant.android.alerts.AlertRulesRepository
import io.healthassistant.shared.alerts.AlertOp
import io.healthassistant.shared.alerts.AlertRule
import io.healthassistant.shared.data.BiomarkerSummary
import io.healthassistant.shared.data.cache.BiomarkerCache
import io.healthassistant.shared.data.cache.CacheMetaState
import io.healthassistant.shared.data.cache.CacheMetaStore
import io.healthassistant.shared.data.cache.CacheRefreshMeta
import io.healthassistant.shared.data.repository.BiomarkerCatalogRepository
import io.healthassistant.shared.data.repository.BiomarkerGateway
import io.healthassistant.shared.data.repository.ConnectivityProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** M5 rule-builder gate: the editor form state, the plain-language → rule
 *  mapping, the prefilled biomarker (the detail screen's "Set an alert"), and
 *  the picker options merge (catalog + HcType fallback). Uses a real
 *  per-test DataStore repository + a fake catalog repository. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AlertRulesViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder.builder().assureDeletion().build()

    private class FakeBiomarkerCache : BiomarkerCache {
        val rows = MutableStateFlow<List<BiomarkerSummary>>(emptyList())

        override suspend fun replaceAll(catalog: List<BiomarkerSummary>) {
            rows.value = catalog
        }

        override suspend fun replaceAllSynced(
            catalog: List<BiomarkerSummary>,
            meta: CacheRefreshMeta,
        ) {
            rows.value = catalog
        }

        override fun observeAll(): Flow<List<BiomarkerSummary>> = rows.asStateFlow()

        override fun observeByCode(code: String): Flow<BiomarkerSummary?> =
            rows.asStateFlow().map { list -> list.firstOrNull { it.code == code } }

        override suspend fun clear() {
            rows.value = emptyList()
        }
    }

    private class NoopMeta : CacheMetaStore {
        override suspend fun recordRefresh(meta: CacheRefreshMeta) = Unit

        override fun observe(domain: io.healthassistant.shared.data.cache.CacheDomain): Flow<CacheMetaState?> = MutableStateFlow(null)

        override fun observeAll(): Flow<List<CacheMetaState>> = MutableStateFlow(emptyList())

        override suspend fun clearDomain(domain: io.healthassistant.shared.data.cache.CacheDomain) = Unit

        override suspend fun clear() = Unit
    }

    private class OfflineGateway : BiomarkerGateway {
        override suspend fun catalog(limit: Int): List<BiomarkerSummary> = emptyList()
    }

    private lateinit var repository: AlertRulesRepository
    private val catalogCache = FakeBiomarkerCache()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val store =
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(dispatcher + Job()),
                produceFile = { File(tmpFolder.newFolder(), "alert_rules_vm_test.preferences_pb") },
            )
        repository = AlertRulesRepository(ApplicationProvider.getApplicationContext<Context>(), store)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(prefillCode: String? = null): AlertRulesViewModel =
        AlertRulesViewModel(
            repository,
            BiomarkerCatalogRepository(catalogCache, OfflineGateway(), ConnectivityProvider { false }, NoopMeta()),
            prefillCode,
        )

    @Test
    fun openEditor_starts_at_the_biomarker_picker() =
        runTest(dispatcher) {
            val vm = vm()
            advanceUntilIdle()

            vm.openEditor()

            val editor = vm.state.value.editor
            assertNotNull(editor)
            assertEquals(true, editor!!.pickingBiomarker)
            assertNull(editor.code)
        }

    @Test
    fun prefilled_code_opens_the_editor_with_the_biomarker_fixed() =
        runTest(dispatcher) {
            val vm = vm(prefillCode = "8867-4")
            advanceUntilIdle()

            val editor = vm.state.value.editor
            assertNotNull(editor)
            assertEquals("8867-4", editor!!.code)
            assertEquals(false, editor.pickingBiomarker)
            assertEquals("Heart Rate", editor.name)
            assertEquals("bpm", editor.unit)
        }

    @Test
    fun catalog_emissions_fill_the_name_and_refresh_picker_options() =
        runTest(dispatcher) {
            val vm = vm(prefillCode = "2339-0")
            advanceUntilIdle()
            catalogCache.rows.value =
                listOf(
                    BiomarkerSummary(id = "b1", name = "Glucose", code = "2339-0", unit = "mmol/L"),
                    BiomarkerSummary(id = "b2", name = "Cholesterol", code = "2093-3", unit = "mg/dL"),
                )
            advanceUntilIdle()

            val state = vm.state.value
            assertEquals("Glucose", state.editor!!.name)
            assertEquals("mmol/L", state.editor.unit)
            val names = state.options.map { it.name }
            assertTrue(names.contains("Glucose"))
            assertTrue(names.contains("Cholesterol"))
            assertTrue("HcType fallbacks survive the merge", names.contains("Heart Rate"))
            assertEquals("name-ascending", names.sortedBy { it.lowercase() }, names)
        }

    @Test
    fun save_builds_the_rule_from_the_plain_language_form() =
        runTest(dispatcher) {
            val vm = vm(prefillCode = "8867-4")
            advanceUntilIdle()
            vm.setOp(AlertOp.LT)
            vm.setThreshold("50")
            vm.setWindowMinutes(15)
            advanceUntilIdle()

            vm.save()
            advanceUntilIdle()

            val saved = repository.current().single()
            assertEquals("8867-4", saved.biomarkerCode)
            assertEquals("Heart Rate", saved.biomarkerName)
            assertEquals(AlertOp.LT, saved.op)
            assertEquals(50.0, saved.threshold!!, 0.0)
            assertEquals(15L * 60L, saved.timeWindowSec)
            assertEquals(true, saved.enabled)
            assertNull(vm.state.value.editor)
        }

    @Test
    fun out_of_range_editor_needs_both_bounds() =
        runTest(dispatcher) {
            val vm = vm(prefillCode = "59408-5")
            advanceUntilIdle()
            vm.setOp(AlertOp.OUT_OF_RANGE)
            advanceUntilIdle()
            assertNull("no bounds yet", vm.currentEditorRule())

            vm.setRangeLow("90")
            vm.setRangeHigh("95")
            advanceUntilIdle()
            val rule = vm.currentEditorRule()
            assertNotNull(rule)
            assertEquals(90.0, rule!!.rangeLow!!, 0.0)
            assertEquals(95.0, rule.rangeHigh!!, 0.0)
        }

    @Test
    fun unparseable_threshold_blocks_the_save() =
        runTest(dispatcher) {
            val vm = vm(prefillCode = "8867-4")
            advanceUntilIdle()
            vm.setThreshold("abc")
            advanceUntilIdle()

            assertNull(vm.currentEditorRule())
            vm.save()
            advanceUntilIdle()

            assertTrue(repository.current().isEmpty())
        }

    @Test
    fun toggle_and_delete_round_trip_through_the_repository() =
        runTest(dispatcher) {
            val vm = vm()
            advanceUntilIdle()
            val rule = AlertRule(id = "keep", biomarkerCode = "8867-4", biomarkerName = "Heart Rate", threshold = 120.0)
            val doomed = AlertRule(id = "drop", biomarkerCode = "8867-4", biomarkerName = "Heart Rate", threshold = 60.0)
            repository.upsert(rule)
            repository.upsert(doomed)
            advanceUntilIdle()
            assertEquals(2, vm.state.value.rules.size)

            vm.setEnabled("keep", false)
            advanceUntilIdle()
            assertEquals(
                false,
                vm.state.value.rules
                    .first { it.id == "keep" }
                    .enabled,
            )

            vm.requestDelete(doomed)
            advanceUntilIdle()
            assertEquals(doomed, vm.state.value.deleting)

            vm.confirmDelete()
            advanceUntilIdle()
            assertEquals(
                listOf("keep"),
                vm.state.value.rules
                    .map { it.id },
            )
            assertNull(vm.state.value.deleting)
        }

    @Test
    fun editor_rule_requires_a_biomarker() {
        assertNull(AlertRulesViewModel.editorRule(AlertEditorState(thresholdText = "120")))
        val rule = AlertRulesViewModel.editorRule(AlertEditorState(code = "8867-4", thresholdText = "120"))
        assertNotNull(rule)
        assertEquals(120.0, rule!!.threshold!!, 0.0)
    }
}
