package io.healthassistant.android.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.healthassistant.shared.data.DashboardLayout
import io.healthassistant.shared.data.HomeViewStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dashboardPrefsDataStore: DataStore<Preferences> by preferencesDataStore(name = "ha_dashboard_prefs")

/** Persists the Home dashboard customization: the view style (grid / list /
 *  simple), the **shown** biomarker codes (an allowlist — defaults to the basic
 *  set, the catalog adds the rest), the custom display order, and the
 *  wellness-overview selection (monitoring viz M6). Backed by DataStore. */
class DashboardPrefsRepository(
    context: Context,
    private val store: DataStore<Preferences> = context.applicationContext.dashboardPrefsDataStore,
) {
    val viewStyle: Flow<HomeViewStyle> =
        store.data.map { prefs ->
            prefs[VIEW_STYLE]?.let { name -> runCatching { HomeViewStyle.valueOf(name) }.getOrNull() }
                ?: HomeViewStyle.GRID
        }

    val shownCodes: Flow<Set<String>> =
        store.data.map { prefs -> prefs[SHOWN_CODES] ?: DashboardLayout.basicCodes() }

    val order: Flow<List<String>> =
        store.data.map { prefs ->
            prefs[ORDER]?.takeIf { it.isNotBlank() }?.split(ORDER_SEPARATOR) ?: emptyList()
        }

    /** The wellness-overview selection (Records › Biomarkers › Overview):
     *  the picked biomarker codes, pick-order preserved. `null` = nothing
     *  stored yet (the caller applies the default — the most recently
     *  active biomarkers); an explicitly emptied selection persists as an
     *  empty list. */
    val overviewCodes: Flow<List<String>?> =
        store.data.map { prefs ->
            prefs[OVERVIEW_CODES]?.split(ORDER_SEPARATOR)?.filter { it.isNotBlank() }
        }

    suspend fun setOverviewCodes(codes: List<String>) {
        store.edit { prefs -> prefs[OVERVIEW_CODES] = codes.filter { it.isNotBlank() }.joinToString(ORDER_SEPARATOR) }
    }

    suspend fun setViewStyle(style: HomeViewStyle) {
        store.edit { prefs -> prefs[VIEW_STYLE] = style.name }
    }

    suspend fun setShownCodes(codes: Set<String>) {
        store.edit { prefs -> prefs[SHOWN_CODES] = codes }
    }

    suspend fun addCode(code: String) {
        store.edit { prefs ->
            val current = prefs[SHOWN_CODES] ?: DashboardLayout.basicCodes()
            prefs[SHOWN_CODES] = current + code
        }
    }

    suspend fun removeCode(code: String) {
        store.edit { prefs ->
            val current = prefs[SHOWN_CODES] ?: DashboardLayout.basicCodes()
            prefs[SHOWN_CODES] = current - code
        }
    }

    suspend fun setOrder(order: List<String>) {
        store.edit { prefs -> prefs[ORDER] = order.joinToString(ORDER_SEPARATOR) }
    }

    /** Back to the default basic set (view style + shown codes + order +
     *  overview selection). */
    suspend fun reset() {
        store.edit { prefs ->
            prefs.remove(VIEW_STYLE)
            prefs.remove(SHOWN_CODES)
            prefs.remove(ORDER)
            prefs.remove(OVERVIEW_CODES)
        }
    }

    private companion object {
        const val ORDER_SEPARATOR = ","

        val VIEW_STYLE = stringPreferencesKey("view_style")
        val SHOWN_CODES = stringSetPreferencesKey("shown_codes")
        val ORDER = stringPreferencesKey("order")
        val OVERVIEW_CODES = stringPreferencesKey("overview_codes")
    }
}
