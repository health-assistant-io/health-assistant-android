package io.healthassistant.android.alerts

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.healthassistant.shared.alerts.AlertRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.alertRulesDataStore: DataStore<Preferences> by preferencesDataStore(name = "ha_alert_rules")

/**
 * M5 — DataStore-backed persistence for the local alert rules (one JSON list
 * under the `rules` key). The [store] parameter lets JVM tests substitute a
 * fresh per-test DataStore (the production `preferencesDataStore` delegate is
 * a per-file singleton). Decoding is tolerant: a corrupted or legacy payload
 * reads as "no rules", and rules that lost their bounds are dropped so the
 * engine never evaluates an unusable rule.
 */
class AlertRulesRepository(
    context: Context,
    private val store: DataStore<Preferences> = context.applicationContext.alertRulesDataStore,
) {
    val rules: Flow<List<AlertRule>> = store.data.map { prefs -> decode(prefs[RULES]) }

    suspend fun current(): List<AlertRule> = rules.first()

    suspend fun upsert(rule: AlertRule) {
        store.edit { prefs ->
            val kept = decode(prefs[RULES]).filterNot { it.id == rule.id }
            prefs[RULES] = encode(kept + rule)
        }
    }

    suspend fun delete(id: String) {
        store.edit { prefs ->
            prefs[RULES] = encode(decode(prefs[RULES]).filterNot { it.id == id })
        }
    }

    suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    ) {
        store.edit { prefs ->
            prefs[RULES] =
                encode(
                    decode(prefs[RULES]).map { rule ->
                        if (rule.id == id) rule.copy(enabled = enabled) else rule
                    },
                )
        }
    }

    /** Cooldown bookkeeping: stamp the wall-clock fire time after a breach posts. */
    suspend fun markFired(
        id: String,
        firedAtEpochMs: Long,
    ) {
        store.edit { prefs ->
            prefs[RULES] =
                encode(
                    decode(prefs[RULES]).map { rule ->
                        if (rule.id == id) rule.copy(lastFiredEpochMs = firedAtEpochMs) else rule
                    },
                )
        }
    }

    private fun encode(rules: List<AlertRule>): String = JSON.encodeToString(LIST, rules)

    private fun decode(text: String?): List<AlertRule> =
        text
            ?.let { runCatching { JSON.decodeFromString(LIST, it) }.getOrNull() }
            ?.filter { it.isValid() }
            ?: emptyList()

    private companion object {
        val RULES = stringPreferencesKey("rules")
        val LIST = ListSerializer(AlertRule.serializer())
        val JSON =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
    }
}
