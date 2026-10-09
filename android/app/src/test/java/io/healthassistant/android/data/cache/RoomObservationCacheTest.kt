package io.healthassistant.android.data.cache

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.healthassistant.shared.data.ObservationCode
import io.healthassistant.shared.data.ObservationPoint
import io.healthassistant.shared.data.cache.CacheDomain
import io.healthassistant.shared.data.cache.CacheRefreshMeta
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Real-SQL test for [RoomObservationCache] (Robolectric + in-memory Room,
 * offline-first M9): the mapping/semantics wiring — store dedupes by id, drops
 * unaddressable rows, reads round-trip back to [ObservationPoint], the
 * latest-per-code/window reads match the DAO's documented behavior — plus the
 * connection binding: rows land under the constructor's connection id only,
 * and a `*Synced` write records the cache_meta success row (timestamp, no
 * error, exact row count) in the same transaction.
 */
@RunWith(RobolectricTestRunner::class)
class RoomObservationCacheTest {
    private lateinit var db: ObservationDatabase
    private lateinit var cache: RoomObservationCache

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ObservationDatabase::class.java).allowMainThreadQueries().build()
        cache = RoomObservationCache(db, CONN)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `store upserts by id instead of duplicating`() =
        runTest {
            cache.store(listOf(point(id = "a", epochMs = 1, value = 70.0)))
            cache.store(listOf(point(id = "a", epochMs = 1, value = 72.0)))

            val series = cache.seriesFor("8867-4").first()
            assertEquals(1, series.size)
            assertEquals(72.0, series.single().rawValue!!, 0.0)
        }

    @Test
    fun `store drops points with no addressable biomarker code`() =
        runTest {
            val addressable = point(id = "a")
            val unaddressable =
                point(id = "b").copy(
                    code = ObservationCode(coding = emptyList(), text = null),
                    biomarkerSlug = null,
                )
            cache.store(listOf(addressable, unaddressable))

            assertEquals(1, db.cacheDao().rowCount(CONN))
            assertEquals(
                "a",
                cache
                    .seriesFor("8867-4")
                    .first()
                    .single()
                    .id,
            )
        }

    @Test
    fun `seriesFor returns points sorted ascending and respects the epoch window`() =
        runTest {
            cache.store(
                listOf(
                    point(id = "1", epochMs = 100),
                    point(id = "2", epochMs = 300),
                    point(id = "3", epochMs = 200),
                ),
            )

            val all = cache.seriesFor("8867-4").first().map { it.id }
            assertEquals(listOf("1", "3", "2"), all)

            val windowed = cache.seriesFor("8867-4", sinceMs = 150, untilMs = 250).first().map { it.id }
            assertEquals(listOf("3"), windowed)
        }

    @Test
    fun `seriesFor keeps the newest points when the window exceeds the limit`() =
        runTest {
            cache.store(
                listOf(
                    point(id = "1", epochMs = 100),
                    point(id = "2", epochMs = 200),
                    point(id = "3", epochMs = 300),
                    point(id = "4", epochMs = 400),
                    point(id = "5", epochMs = 500),
                ),
            )

            val newestThree = cache.seriesFor("8867-4", limit = 3).first().map { it.id }
            assertEquals(listOf("3", "4", "5"), newestThree)
        }

    @Test
    fun `latestPerBiomarker returns the newest point per biomarker code`() =
        runTest {
            cache.store(
                listOf(
                    point(id = "hr-1", code = "8867-4", epochMs = 100),
                    point(id = "hr-2", code = "8867-4", epochMs = 300),
                    point(id = "steps-1", code = "55423-8", epochMs = 200),
                ),
            )

            val latest = cache.latestPerBiomarker().first().associateBy { it.primaryCode }
            assertEquals(2, latest.size)
            assertEquals("hr-2", latest["8867-4"]?.id)
            assertEquals("steps-1", latest["55423-8"]?.id)
        }

    @Test
    fun `latestForCode returns the newest for the code or null when empty`() =
        runTest {
            cache.store(
                listOf(
                    point(id = "a", epochMs = 100),
                    point(id = "b", epochMs = 500),
                    point(id = "c", epochMs = 300),
                ),
            )

            assertEquals("b", cache.latestForCode("8867-4").first()?.id)
            assertNull(cache.latestForCode("nope").first())
        }

    @Test
    fun `previousForCode returns the reading before the newest or null`() =
        runTest {
            cache.store(
                listOf(
                    point(id = "a", epochMs = 100),
                    point(id = "b", epochMs = 500),
                    point(id = "c", epochMs = 300),
                    point(id = "only", code = "55423-8", epochMs = 200),
                ),
            )

            assertEquals("c", cache.previousForCode("8867-4").first()?.id)
            assertNull("one cached row has no previous", cache.previousForCode("55423-8").first())
        }

    @Test
    fun `previousPerBiomarker returns the second newest per code`() =
        runTest {
            cache.store(
                listOf(
                    point(id = "hr-1", code = "8867-4", epochMs = 100),
                    point(id = "hr-2", code = "8867-4", epochMs = 500),
                    point(id = "hr-3", code = "8867-4", epochMs = 300),
                    point(id = "steps-1", code = "55423-8", epochMs = 300),
                    point(id = "steps-2", code = "55423-8", epochMs = 600),
                ),
            )

            val previous = cache.previousPerBiomarker().first().associateBy { it.primaryCode }

            assertEquals("hr-3", previous["8867-4"]?.id)
            assertEquals("steps-1", previous["55423-8"]?.id)
        }

    @Test
    fun `clearForCode and clear remove rows`() =
        runTest {
            cache.store(
                listOf(
                    point(id = "a", code = "8867-4", epochMs = 1),
                    point(id = "b", code = "55423-8", epochMs = 1),
                ),
            )
            cache.clearForCode("8867-4")
            assertTrue(cache.seriesFor("8867-4").first().isEmpty())
            assertEquals(1, db.cacheDao().rowCount(CONN))

            cache.clear()
            assertEquals(0, db.cacheDao().rowCount(CONN))
        }

    @Test
    fun `reads are scoped to the bound connection`() =
        runTest {
            val other = RoomObservationCache(db, "other-conn")
            cache.store(listOf(point(id = "a", code = "8867-4", epochMs = 100)))
            other.store(listOf(point(id = "a", code = "8867-4", epochMs = 999)))

            assertEquals(100L, cache.latestForCode("8867-4").first()!!.effectiveDatetimeEpochMs)
            assertEquals(999L, other.latestForCode("8867-4").first()!!.effectiveDatetimeEpochMs)
            assertEquals(
                "same id under two connections must coexist",
                2,
                db.cacheDao().rowCount(CONN) + db.cacheDao().rowCount("other-conn"),
            )
        }

    @Test
    fun `storeSynced records the success meta row in the same write`() =
        runTest {
            cache.storeSynced(
                listOf(point(id = "a", epochMs = 100), point(id = "b", epochMs = 200)),
                CacheRefreshMeta.success(CacheDomain.OBSERVATIONS, atEpochMs = 1234L),
            )

            val state = db.cacheMetaDao().observeState(CONN, CacheDomain.OBSERVATIONS.wire).first()
            assertEquals(1234L, state?.lastSuccessAtEpochMs)
            assertNull(state?.lastError)
            assertEquals(2, state?.rowCount)
        }

    private val ObservationPoint.effectiveDatetimeEpochMs: Long
        get() =
            java.time.Instant
                .parse(effectiveDatetime!!)
                .toEpochMilli()

    private fun point(
        id: String = "x",
        code: String = "8867-4",
        epochMs: Long = 0L,
        value: Double = 72.0,
    ): ObservationPoint =
        ObservationPoint(
            id = id,
            effectiveDatetime =
                if (epochMs == 0L) {
                    null
                } else {
                    java.time.Instant
                        .ofEpochMilli(epochMs)
                        .toString()
                },
            rawValue = value,
            code =
                ObservationCode(
                    coding = listOf(ObservationCode.Coding(code = code, system = "http://loinc.org", display = "Heart rate")),
                ),
        )

    private companion object {
        const val CONN = "conn-a"
    }
}
