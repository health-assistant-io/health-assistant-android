package io.healthassistant.shared.sync

import io.healthassistant.bridge.ClientRecord
import io.healthassistant.bridge.SyncPayload
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

/** Convenience builders for outbox items — used by the Health Connect adapter
 *  (Phase 3) and by the dashboard's "Sync now" debug trigger (Phase 5). */
object SyncItems {

    private val json = Json { encodeDefaults = false }

    /** A `/sync` POST item carrying one quantitative record (e.g. an HC reading). */
    fun recordItem(
        clientVersion: String,
        sourceSystem: String,
        record: ClientRecord,
        cursor: String? = null,
    ): OutboxItem {
        val payload = SyncPayload(clientVersion, sourceSystem, cursor, listOf(record))
        val body = json.encodeToString(SyncPayload.serializer(), payload).encodeToByteArray()
        return OutboxItem(id = UUID.randomUUID().toString(), method = "POST", path = "/sync", payload = body)
    }

    /** A synthetic Heart Rate reading (for the Phase 5 on-device "Sync now" trigger). */
    fun heartRateItem(
        bpm: Double = 72.0,
        clientVersion: String = "0.1",
        sourceSystem: String = "android-app",
    ): OutboxItem = recordItem(
        clientVersion, sourceSystem,
        ClientRecord(
            type = "quantitative", code = "8867-4", codingSystem = "loinc",
            name = "Heart Rate", value = bpm, unit = "bpm",
            timestamp = Instant.now().toString(),
        ),
    )

    private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** The record code (LOINC/custom) carried by a `/sync` outbox item's payload,
     *  or null if the item isn't a single-record sync payload. Used by the worker
     *  to tally per-biomarker progress without pulling serialization into `:app`. */
    fun recordCode(item: OutboxItem): String? =
        runCatching {
            JSON.decodeFromString(SyncPayload.serializer(), item.payload.decodeToString())
                .records?.firstOrNull()?.code
        }.getOrNull()
}
