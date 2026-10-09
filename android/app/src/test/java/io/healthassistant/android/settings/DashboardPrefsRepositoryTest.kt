package io.healthassistant.android.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import io.healthassistant.shared.data.HomeViewStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Monitoring-viz M6 gate: the wellness-overview selection's tri-state
 * persistence — nothing stored (null → the caller applies the default),
 * round-trip with pick order, and an explicitly emptied selection that stays
 * empty instead of falling back. Each test gets its own DataStore file via
 * the repository's test constructor parameter.
 */
@RunWith(RobolectricTestRunner::class)
class DashboardPrefsRepositoryTest {
    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder.builder().assureDeletion().build()

    private fun repository(): DashboardPrefsRepository {
        val store =
            PreferenceDataStoreFactory.create(produceFile = { File(tmpFolder.newFolder(), "dashboard_prefs_test.preferences_pb") })
        return DashboardPrefsRepository(ApplicationProvider.getApplicationContext<Context>(), store)
    }

    @Test
    fun nothing_stored_reads_as_null_so_the_default_applies() =
        runBlocking {
            assertNull(repository().overviewCodes.first())
        }

    @Test
    fun the_selection_round_trips_preserving_pick_order() =
        runBlocking {
            val repo = repository()

            repo.setOverviewCodes(listOf("8867-4", "29463-7", "sleep"))

            assertEquals(listOf("8867-4", "29463-7", "sleep"), repo.overviewCodes.first())
        }

    @Test
    fun an_explicitly_emptied_selection_persists_as_empty_not_null() =
        runBlocking {
            val repo = repository()
            repo.setOverviewCodes(listOf("8867-4"))

            repo.setOverviewCodes(emptyList())

            assertEquals(emptyList<String>(), repo.overviewCodes.first())
        }

    @Test
    fun a_new_selection_replaces_the_stored_one() =
        runBlocking {
            val repo = repository()
            repo.setOverviewCodes(listOf("8867-4", "29463-7"))

            repo.setOverviewCodes(listOf("55423-8"))

            assertEquals(listOf("55423-8"), repo.overviewCodes.first())
        }

    @Test
    fun saving_the_selection_leaves_the_other_dashboard_prefs_untouched() =
        runBlocking {
            val store =
                PreferenceDataStoreFactory.create(produceFile = { File(tmpFolder.newFolder(), "dashboard_prefs_test.preferences_pb") })
            store.edit { it[stringPreferencesKey("view_style")] = "LIST" }
            val repo = DashboardPrefsRepository(ApplicationProvider.getApplicationContext<Context>(), store)

            repo.setOverviewCodes(listOf("8867-4"))

            assertEquals(HomeViewStyle.LIST, repo.viewStyle.first())
            assertEquals(listOf("8867-4"), repo.overviewCodes.first())
        }
}
