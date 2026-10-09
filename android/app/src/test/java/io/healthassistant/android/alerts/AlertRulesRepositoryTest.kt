package io.healthassistant.android.alerts

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import io.healthassistant.shared.alerts.AlertOp
import io.healthassistant.shared.alerts.AlertRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** M5 rules persistence gate: CRUD round-trips, the cooldown stamp, and the
 *  tolerant decode (corrupted payload / unusable rules). Each test gets its
 *  own DataStore file via the repository's test constructor parameter. */
@RunWith(RobolectricTestRunner::class)
class AlertRulesRepositoryTest {
    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder.builder().assureDeletion().build()

    private fun repository(): AlertRulesRepository {
        val store =
            PreferenceDataStoreFactory.create(produceFile = { File(tmpFolder.newFolder(), "alert_rules_test.preferences_pb") })
        return AlertRulesRepository(ApplicationProvider.getApplicationContext<Context>(), store)
    }

    private fun rule(id: String) =
        AlertRule(
            id = id,
            biomarkerCode = "8867-4",
            biomarkerName = "Heart Rate",
            op = AlertOp.GT,
            threshold = 120.0,
            timeWindowSec = 300,
            cooldownSec = 1800,
        )

    @Test
    fun starts_empty() =
        runBlocking {
            assertTrue(repository().rules.first().isEmpty())
        }

    @Test
    fun upsert_round_trips_every_field() =
        runBlocking {
            val repo = repository()
            val rule =
                rule("r1").copy(
                    op = AlertOp.OUT_OF_RANGE,
                    threshold = null,
                    rangeLow = 60.0,
                    rangeHigh = 100.0,
                    timeWindowSec = 900,
                    enabled = false,
                    lastFiredEpochMs = 123L,
                )

            repo.upsert(rule)

            assertEquals(listOf(rule), repo.rules.first())
        }

    @Test
    fun upsert_with_the_same_id_replaces_the_rule() =
        runBlocking {
            val repo = repository()
            repo.upsert(rule("r1"))
            repo.upsert(rule("r1").copy(threshold = 90.0))
            repo.upsert(rule("r2"))

            val rules = repo.rules.first()
            assertEquals(2, rules.size)
            assertEquals(90.0, rules.first { it.id == "r1" }.threshold!!, 0.0)
        }

    @Test
    fun set_enabled_toggles_one_rule() =
        runBlocking {
            val repo = repository()
            repo.upsert(rule("r1"))
            repo.upsert(rule("r2"))

            repo.setEnabled("r1", false)

            assertEquals(
                false,
                repo.rules
                    .first()
                    .first { it.id == "r1" }
                    .enabled,
            )
            assertEquals(
                true,
                repo.rules
                    .first()
                    .first { it.id == "r2" }
                    .enabled,
            )
        }

    @Test
    fun delete_removes_only_the_target() =
        runBlocking {
            val repo = repository()
            repo.upsert(rule("r1"))
            repo.upsert(rule("r2"))

            repo.delete("r1")

            assertEquals(listOf("r2"), repo.rules.first().map { it.id })
        }

    @Test
    fun mark_fired_stamps_the_cooldown_bookkeeping() =
        runBlocking {
            val repo = repository()
            repo.upsert(rule("r1"))

            repo.markFired("r1", 42L)

            assertEquals(
                42L,
                repo.rules
                    .first()
                    .single()
                    .lastFiredEpochMs,
            )
        }

    @Test
    fun corrupted_payload_reads_as_no_rules() =
        runBlocking {
            val store =
                PreferenceDataStoreFactory.create(produceFile = { File(tmpFolder.newFolder(), "alert_rules_test.preferences_pb") })
            store.edit { it[stringPreferencesKey("rules")] = "definitely not json" }
            val repo = AlertRulesRepository(ApplicationProvider.getApplicationContext<Context>(), store)

            assertTrue(repo.rules.first().isEmpty())
        }

    @Test
    fun unusable_stored_rules_are_dropped_on_decode() =
        runBlocking {
            val store =
                PreferenceDataStoreFactory.create(produceFile = { File(tmpFolder.newFolder(), "alert_rules_test.preferences_pb") })
            store.edit {
                it[stringPreferencesKey("rules")] =
                    """[{"id":"ok","biomarker_code":"8867-4","threshold":120.0,"cooldown_sec":1800},""" +
                    """{"id":"broken","biomarker_code":"8867-4","cooldown_sec":1800}]"""
            }
            val repo = AlertRulesRepository(ApplicationProvider.getApplicationContext<Context>(), store)

            assertEquals(listOf("ok"), repo.rules.first().map { it.id })
        }
}
