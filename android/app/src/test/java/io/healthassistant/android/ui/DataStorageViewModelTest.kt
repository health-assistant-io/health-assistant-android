package io.healthassistant.android.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.healthassistant.android.data.cache.FileDocumentByteStore
import io.healthassistant.android.data.cache.ObservationDatabase
import io.healthassistant.android.data.cache.RoomCaches
import io.healthassistant.bridge.BridgeClient
import io.healthassistant.shared.data.ExaminationSummary
import io.healthassistant.shared.data.cache.CacheDomain
import io.healthassistant.shared.data.cache.CacheRefreshMeta
import io.healthassistant.shared.data.repository.ConnectivityProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * JVM gate for the Settings "Data & storage" view-model (offline-first M8):
 * per-domain row counts + last-refresh come from the ACTIVE connection's
 * caches only, "Clear cached data" drops every domain's rows + staleness meta
 * via the repositories' clear(), and "Clear downloaded documents" empties the
 * shared byte store + its manifest pointers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DataStorageViewModelTest {
    private lateinit var db: ObservationDatabase
    private lateinit var caches: RoomCaches
    private lateinit var byteStore: FileDocumentByteStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ObservationDatabase::class.java).allowMainThreadQueries().build()
        caches = RoomCaches(db, CONN)
        byteStore = FileDocumentByteStore(context)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun refresh_reports_per_domain_counts_and_last_refresh_of_the_active_connection() =
        runTest(UnconfinedTestDispatcher()) {
            Dispatchers.setMain(UnconfinedTestDispatcher())
            caches.examinations.storeAllSynced(
                listOf(ExaminationSummary(id = "e1"), ExaminationSummary(id = "e2")),
                CacheRefreshMeta.success(CacheDomain.EXAMINATIONS, atEpochMs = 42L),
            )
            RoomCaches(db, "other-conn").examinations.storeAll(listOf(ExaminationSummary(id = "e1")))

            val vm = vm()
            vm.state.first { !it.loading }

            val examRow =
                vm.state.value.rows
                    .first { it.domain == CacheDomain.EXAMINATIONS }
            assertEquals(2, examRow.rowCount)
            assertEquals(42L, examRow.lastSuccessAtEpochMs)
            assertNull(examRow.lastError)
            assertEquals(
                "another connection's rows must not leak in",
                0,
                vm.state.value.rows
                    .first { it.domain == CacheDomain.OBSERVATIONS }
                    .rowCount,
            )
        }

    @Test
    fun clear_cached_data_drops_rows_and_staleness_meta() =
        runTest(UnconfinedTestDispatcher()) {
            Dispatchers.setMain(UnconfinedTestDispatcher())
            caches.examinations.storeAllSynced(
                listOf(ExaminationSummary(id = "e1")),
                CacheRefreshMeta.success(CacheDomain.EXAMINATIONS, atEpochMs = 42L),
            )

            val vm = vm()
            vm.state.first { !it.loading }
            vm.clearCachedData()
            vm.state.first { s -> s.rows.any { it.domain == CacheDomain.EXAMINATIONS && it.rowCount == 0 } }

            assertEquals(
                0L,
                vm.state.value.rows
                    .first { it.domain == CacheDomain.EXAMINATIONS }
                    .lastSuccessAtEpochMs,
            )
            assertEquals(0, db.examinationDao().rowCount(CONN))
            assertNull(db.cacheMetaDao().get(CONN, CacheDomain.EXAMINATIONS.wire))
        }

    @Test
    fun clear_documents_empties_the_byte_store_and_manifest_pointers() =
        runTest(UnconfinedTestDispatcher()) {
            Dispatchers.setMain(UnconfinedTestDispatcher())
            caches.documents.storeAll(
                listOf(
                    io.healthassistant.shared.data
                        .DocumentSummary(id = "d1", examinationId = "exam-1"),
                ),
            )
            caches.documents.setLocalManifest("d1", "ha_docs/d1.bin", 99L)
            byteStore.write("d1", ByteArray(2048))

            val vm = vm()
            vm.state.first { !it.loading }
            assertTrue(vm.state.value.documentBytes >= 2048)
            assertEquals(1, vm.state.value.downloadedDocuments)

            vm.clearDocuments()
            vm.state.first { s -> s.documentBytes == 0L }

            assertEquals(0, vm.state.value.downloadedDocuments)
        }

    private fun vm() =
        DataStorageViewModel(
            client = BridgeClient(baseUrl = "http://localhost:9", integrationId = CONN, apiSecret = null),
            caches = caches,
            db = db,
            connectivity = ConnectivityProvider { false },
            byteStore = byteStore,
        )

    private companion object {
        const val CONN = "conn-settings"
    }
}
