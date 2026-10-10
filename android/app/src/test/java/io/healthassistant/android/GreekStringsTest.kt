package io.healthassistant.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Greek resource pipeline under the `el` qualifier: the localized values-el
 * entries resolve (not the English fallbacks). Guards the many-languages
 * contract — when adding a locale, extend this with its strings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "el")
class GreekStringsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun greek_strings_resolve() {
        assertEquals("Ενημερωμένο", context.getString(R.string.home_up_to_date))
        assertEquals("Αρχική", context.getString(R.string.nav_home))
        assertEquals("Βιοδείκτες", context.getString(R.string.biomarkers_title))
        assertEquals("Εμφάνιση", context.getString(R.string.appearance_title))
        assertEquals("Συγχρονισμός τώρα", context.getString(R.string.home_sync_now))
    }

    @Test
    fun brand_names_stay_untranslated() {
        assertEquals("Health Assistant", context.getString(R.string.app_name))
        assertEquals("Health Connect", context.getString(R.string.settings_health_connect))
    }
}
