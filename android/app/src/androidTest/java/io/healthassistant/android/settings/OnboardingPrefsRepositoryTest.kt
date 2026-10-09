package io.healthassistant.android.settings

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** R7 gate: the welcome flag defaults to false and round-trips after being set
 *  (DataStore-backed, so it runs on a device/emulator). */
@RunWith(AndroidJUnit4::class)
class OnboardingPrefsRepositoryTest {
    @Test
    fun welcome_flag_defaults_false_and_round_trips() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val repo = OnboardingPrefsRepository(context)

            assertFalse(repo.welcomeShown.first())

            repo.setWelcomeShown()
            assertTrue(repo.welcomeShown.first())
        }
}
