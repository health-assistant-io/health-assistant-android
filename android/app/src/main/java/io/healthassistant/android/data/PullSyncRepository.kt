package io.healthassistant.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.healthassistant.shared.sync.ChangesDelta
import io.healthassistant.shared.sync.PullSyncStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

private val Context.pullSyncDataStore: DataStore<Preferences> by preferencesDataStore(name = "ha_pull_sync")

/**
 * Phase G — DataStore-backed [PullSyncStore] for the two-way incremental sync
 * (`GET /changes`) cursor, plus an observable "last successful pull" signal.
 *
 * Cursor contract (mirrors [io.healthassistant.shared.sync.PullSyncCoordinator]):
 * the coordinator advances the cursor ONLY after a successful `applyDelta`. A
 * null cursor = first pull (the server defaults the window to the last 7 days).
 *
 * **Offline-first M7:** `applyDelta` now hydrates the per-domain Room caches
 * (via the [DeltaApplier] hooks injected at construction — the repositories are
 * per-connection so the worker supplies them) BEFORE bumping the
 * [lastPullEpochMs] signal. Screens migrated to the SSOT caches re-render
 * straight from the hydration; the signal remains for the not-yet-migrated
 * surfaces and for sync-status display. The signal is only bumped when the
 * delta actually carried rows (an empty pull no longer triggers screen
 * re-fetches).
 */
class PullSyncRepository(
    context: Context,
    private val deltaApplier: DeltaApplier? = null,
) : PullSyncStore {
    /** Hydration sink for the per-domain caches; supplied by the worker. */
    fun interface DeltaApplier {
        suspend fun apply(delta: ChangesDelta)
    }

    private val store = context.applicationContext.pullSyncDataStore

    private val _lastPullEpochMs = MutableStateFlow(0L)

    /** Emits the epoch-ms of the most recent successful pull. ViewModels react
     *  to changes (a non-zero, advancing value) by re-fetching their bridge data. */
    val lastPullEpochMs: StateFlow<Long> = _lastPullEpochMs.asStateFlow()

    /** Total rows applied across the most recent delta (all six type families). */
    private val _lastPullChanged = MutableStateFlow(0)
    val lastPullChanged: StateFlow<Int> = _lastPullChanged.asStateFlow()

    override suspend fun currentCursor(): String? = store.data.first()[CURSOR]

    override suspend fun saveCursor(isoCursor: String) {
        store.edit { it[CURSOR] = isoCursor }
    }

    override suspend fun applyDelta(delta: ChangesDelta) {
        // 1. Hydrate the per-domain caches (merge-upsert by id; a throw aborts
        //    the pull and the cursor is not advanced — the coordinator's
        //    crash-safe contract).
        deltaApplier?.apply(delta)
        // 2. Bump the refresh signal ONLY when something actually changed.
        if (!delta.isEmpty) {
            val now = System.currentTimeMillis()
            _lastPullChanged.value = delta.totalCount
            _lastPullEpochMs.value = now
            store.edit { it[LAST_PULL_AT] = now }
        }
    }

    override suspend fun clearCursor() {
        store.edit { it.remove(CURSOR) }
    }

    /** Load the persisted last-pull timestamp into the signal on startup so the
     *  "pull age" shown in Settings survives a restart. Safe to call once on
     *  app start; idempotent. */
    suspend fun hydrateFromDisk() {
        val persisted = store.data.first()[LAST_PULL_AT] ?: return
        if (_lastPullEpochMs.value == 0L) _lastPullEpochMs.value = persisted
    }

    /** Milliseconds since the last full re-snapshot (Long.MAX_VALUE when never
     *  run — the first pull always triggers one). Drives the 24h deletion
     *  reconciliation cadence in [io.healthassistant.android.work.PullSyncWorker]. */
    suspend fun millisecondsSinceLastResnapshot(): Long {
        val last = store.data.first()[LAST_RESNAPSHOT_AT] ?: return Long.MAX_VALUE
        return System.currentTimeMillis() - last
    }

    /** Record that a full re-snapshot just completed. */
    suspend fun markResnapshotted() {
        store.edit { it[LAST_RESNAPSHOT_AT] = System.currentTimeMillis() }
    }

    private companion object {
        val CURSOR = stringPreferencesKey("changes_cursor")
        val LAST_PULL_AT = longPreferencesKey("last_pull_at")
        val LAST_RESNAPSHOT_AT = longPreferencesKey("last_resnapshot_at")
    }
}
