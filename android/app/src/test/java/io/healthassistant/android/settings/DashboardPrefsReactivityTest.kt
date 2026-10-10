package io.healthassistant.android.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import io.healthassistant.shared.data.HomeViewStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Regression net for the layout-switch bug (v1.3: "changing List/Grid needs a
 * restart"): proves [DashboardPrefsRepository.viewStyle] re-emits the new
 * value to LIVE collectors immediately after a write — the exact behavior
 * HomeViewModel's `combine` depends on.
 */
@RunWith(RobolectricTestRunner::class)
class DashboardPrefsReactivityTest {
    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder.builder().assureDeletion().build()

    private fun repository(): DashboardPrefsRepository {
        val store =
            PreferenceDataStoreFactory.create(produceFile = { File(tmpFolder.newFolder(), "dash_prefs_test.preferences_pb") })
        return DashboardPrefsRepository(ApplicationProvider.getApplicationContext<Context>(), store)
    }

    @Test
    fun viewStyle_write_re_emits_to_a_live_collector() =
        runBlocking {
            val repo = repository()
            assertEquals(HomeViewStyle.GRID, repo.viewStyle.first())

            val seen = CopyOnWriteArrayList<HomeViewStyle>()
            val collector =
                launch {
                    repo.viewStyle.take(2).toList(seen)
                }

            repo.setViewStyle(HomeViewStyle.LIST)

            withTimeout(3_000) { collector.join() }
            assertEquals(listOf(HomeViewStyle.GRID, HomeViewStyle.LIST), seen)
        }
}
