package io.healthassistant.android.data.cache

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.healthassistant.shared.data.ObservationCode
import io.healthassistant.shared.data.ObservationPoint
import io.healthassistant.shared.data.cache.CacheMapper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Real-SQL gate for the seriesFor query (Robolectric + in-memory Room): the
 * newest-N-of-the-window semantics must hold against the generated SQLite,
 * not just the JVM fake in [RoomObservationCacheTest] — a previous ASC+LIMIT
 * bug served the OLDEST slice of every window.
 */
@RunWith(RobolectricTestRunner::class)
class ObservationCacheDaoSqlTest {
    private lateinit var db: ObservationDatabase
    private lateinit var dao: ObservationCacheDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ObservationDatabase::class.java).allowMainThreadQueries().build()
        dao = db.cacheDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun seriesFor_returns_newest_n_of_window_in_ascending_order() =
        runTest {
            dao.upsertAll((1..10).map { row(it, epochMs = it * 1_000L) })

            val newest4 = dao.seriesFor(CONN, "8867-4", null, null, limit = 4).first()

            assertEquals(listOf(7_000L, 8_000L, 9_000L, 10_000L), newest4.map { it.effectiveEpochMs })
        }

    @Test
    fun seriesFor_respects_the_window_before_limiting() =
        runTest {
            dao.upsertAll((1..10).map { row(it, epochMs = it * 1_000L) })

            val windowed = dao.seriesFor(CONN, "8867-4", sinceMs = 2_500L, untilMs = 6_500L, limit = 10).first()

            assertEquals(listOf(3_000L, 4_000L, 5_000L, 6_000L), windowed.map { it.effectiveEpochMs })
        }

    @Test
    fun seriesFor_limit_smaller_than_window_takes_the_newest_inside_the_window() =
        runTest {
            dao.upsertAll((1..10).map { row(it, epochMs = it * 1_000L) })

            val newest2 = dao.seriesFor(CONN, "8867-4", sinceMs = 2_500L, untilMs = 8_500L, limit = 2).first()

            assertEquals(listOf(7_000L, 8_000L), newest2.map { it.effectiveEpochMs })
        }

    @Test
    fun previousForCode_returns_the_second_newest_row_for_the_code() =
        runTest {
            dao.upsertAll(
                listOf(
                    row(1, epochMs = 1_000L),
                    row(2, epochMs = 3_000L),
                    row(3, epochMs = 2_000L),
                    row(4, epochMs = 9_000L, code = "55423-8"),
                ),
            )

            assertEquals(2_000L, dao.previousForCode(CONN, "8867-4").first()?.effectiveEpochMs)
            assertNull("a code with a single row has no previous", dao.previousForCode(CONN, "55423-8").first())
            assertNull("an unknown code has no previous", dao.previousForCode(CONN, "nope").first())
        }

    @Test
    fun previousPerBiomarker_returns_the_second_newest_row_per_code() =
        runTest {
            dao.upsertAll(
                listOf(
                    row(1, epochMs = 1_000L),
                    row(2, epochMs = 3_000L),
                    row(3, epochMs = 2_000L),
                    row(4, epochMs = 4_000L, code = "55423-8"),
                ),
            )

            val previous = dao.previousPerBiomarker(CONN, 10).first().associateBy { it.biomarkerCode }

            assertEquals(2_000L, previous["8867-4"]?.effectiveEpochMs)
            assertNull("single-row codes are skipped", previous["55423-8"])
        }

    private fun row(
        i: Int,
        epochMs: Long,
        code: String = "8867-4",
    ): CachedObservation {
        val point =
            ObservationPoint(
                id = "p$i",
                effectiveDatetime =
                    java.time.Instant
                        .ofEpochMilli(epochMs)
                        .toString(),
                rawValue = 70.0 + i,
                code =
                    ObservationCode(
                        coding = listOf(ObservationCode.Coding(code = code, system = "http://loinc.org", display = "Heart rate")),
                    ),
            )
        return requireNotNull(CacheMapper.toCached(point)).toEntity(CONN)
    }

    private companion object {
        const val CONN = "conn-a"
    }
}
