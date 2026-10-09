package io.healthassistant.shared.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Projection of `GET /clinical-events/{id}` (the bridge returns the full
 * `ClinicalEvent.to_dict()` — we decode only the fields the app renders and
 * ignore the rest leniently). Lives in shared so the parsing is JVM-tested.
 */
@Serializable
data class ClinicalEventDetail(
    val id: String,
    val title: String? = null,
    val status: String? = null,
    val description: String? = null,
    @SerialName("onset_date") val onsetDate: String? = null,
    @SerialName("resolved_date") val resolvedDate: String? = null,
    @SerialName("type_details") val typeDetails: EventTypeDetails? = null,
    val occurrences: List<EventOccurrence> = emptyList(),
)

@Serializable
data class EventTypeDetails(
    val name: String? = null,
    val icon: String? = null,
    val color: String? = null,
    val description: String? = null,
)

@Serializable
data class EventOccurrence(
    val id: String? = null,
    @SerialName("occurred_at") val occurredAt: String? = null,
    val date: String? = null,
    val title: String? = null,
    val severity: String? = null,
    val intensity: Int? = null,
    val notes: String? = null,
)

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** Parse the raw bridge detail JSON; null on a decode failure. */
fun parseClinicalEventDetail(raw: String): ClinicalEventDetail? =
    runCatching { lenientJson.decodeFromString(ClinicalEventDetail.serializer(), raw) }.getOrNull()
