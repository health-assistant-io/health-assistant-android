package io.healthassistant.android.source

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import io.healthassistant.shared.healthconnect.HcType
import io.healthassistant.shared.healthconnect.RawSample
import io.healthassistant.shared.source.HealthDataSource
import io.healthassistant.shared.source.ReadResult
import java.time.Instant

/**
 * Health Connect source adapter (Phase A of the health-connect-sync plan). Reads
 * real on-device health data via [androidx.health.connect:connect-client],
 * converts to [RawSample]s for the existing pure-Kotlin mapper. Permissions are
 * declared in the manifest and requested at runtime from the Settings UI.
 */
class HealthConnectSource(
    private val context: Context,
) : HealthDataSource {
    override val id = "health_connect"
    override val displayName = "Health Connect"
    override val availableTypes = HcType.entries.toList()

    override suspend fun isAvailable(): Boolean =
        try {
            HealthConnectClient.getSdkStatus(context) == HEALTH_CONNECT_AVAILABLE
        } catch (e: Exception) {
            false
        }

    override suspend fun read(
        types: Set<HcType>,
        since: Map<HcType, Long>,
    ): ReadResult {
        val client = HealthConnectClient.getOrCreate(context)
        val nowMs = Instant.now().toEpochMilli()
        val allSamples = mutableListOf<RawSample>()
        val newCursors = mutableMapOf<HcType, Long>()

        for (type in types) {
            // Walking time-window: read at most one STEP forward from the cursor per
            // pass, so a single worker run never pulls the device's entire history.
            val sinceMs = since[type] ?: 0L
            val stepEnd = minOf(sinceMs + STEP_MS, nowMs)
            val filter = TimeRangeFilter.between(Instant.ofEpochMilli(sinceMs), Instant.ofEpochMilli(stepEnd))
            val samples =
                try {
                    val result = readType(client, type, filter)
                    android.util.Log.i(TAG, "readType $type: ${result.size} samples")
                    result
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "readType $type threw: ${e.javaClass.simpleName}: ${e.message}")
                    emptyList()
                }
            allSamples.addAll(samples)
            newCursors[type] = stepEnd
        }
        return ReadResult(allSamples, newCursors)
    }

    private suspend fun readType(
        client: HealthConnectClient,
        type: HcType,
        filter: TimeRangeFilter,
    ): List<RawSample> =
        when (type) {
            HcType.HEART_RATE -> readHeartRate(client, type, filter)
            HcType.STEPS -> readSteps(client, type, filter)
            HcType.WEIGHT -> readWeight(client, type, filter)
            HcType.OXYGEN_SATURATION -> readOxygenSaturation(client, type, filter)
            HcType.SLEEP_DURATION -> readSleep(client, type, filter)
            // Phase J — expanded types:
            HcType.BLOOD_PRESSURE_SYS -> readBloodPressure(client, type, filter).first
            HcType.BLOOD_PRESSURE_DIA -> readBloodPressure(client, type, filter).second
            HcType.BLOOD_GLUCOSE -> readBloodGlucose(client, type, filter)
            HcType.BODY_TEMPERATURE -> readBodyTemperature(client, type, filter)
            HcType.RESPIRATION_RATE -> readRespirationRate(client, type, filter)
            HcType.HEIGHT -> readHeight(client, type, filter)
            HcType.DISTANCE -> readDistance(client, type, filter)
            HcType.CALORIES -> readCalories(client, type, filter)
        }

    private suspend fun readHeartRate(
        client: HealthConnectClient,
        type: HcType,
        filter: TimeRangeFilter,
    ): List<RawSample> {
        val response = client.readRecords(ReadRecordsRequest(HeartRateRecord::class, filter))
        return response.records.flatMap { record ->
            record.samples.map { sample ->
                RawSample(
                    hcType = type,
                    value = sample.beatsPerMinute.toDouble(),
                    unit = "bpm",
                    timestamp = sample.time.toString(),
                )
            }
        }
    }

    private suspend fun readSteps(
        client: HealthConnectClient,
        type: HcType,
        filter: TimeRangeFilter,
    ): List<RawSample> {
        val response = client.readRecords(ReadRecordsRequest(StepsRecord::class, filter))
        return response.records.map { record ->
            RawSample(
                hcType = type,
                value = record.count.toDouble(),
                unit = "count",
                timestamp = record.endTime.toString(),
            )
        }
    }

    private suspend fun readWeight(
        client: HealthConnectClient,
        type: HcType,
        filter: TimeRangeFilter,
    ): List<RawSample> {
        val response = client.readRecords(ReadRecordsRequest(WeightRecord::class, filter))
        return response.records.map { record ->
            RawSample(
                hcType = type,
                value = record.weight.inKilograms,
                unit = "kg",
                timestamp = record.time.toString(),
            )
        }
    }

    private suspend fun readOxygenSaturation(
        client: HealthConnectClient,
        type: HcType,
        filter: TimeRangeFilter,
    ): List<RawSample> {
        val response = client.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class, filter))
        return response.records.map { record ->
            RawSample(
                hcType = type,
                value = record.percentage.value,
                unit = "%",
                timestamp = record.time.toString(),
            )
        }
    }

    private suspend fun readSleep(
        client: HealthConnectClient,
        type: HcType,
        filter: TimeRangeFilter,
    ): List<RawSample> {
        val response = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, filter))
        return response.records.map { record ->
            val durationMin =
                java.time.Duration
                    .between(record.startTime, record.endTime)
                    .toMinutes()
                    .toDouble()
            RawSample(
                hcType = type,
                value = durationMin,
                unit = "min",
                timestamp = record.endTime.toString(),
            )
        }
    }

    // --- Phase J: expanded Health Connect types ---------------------------

    /** Reads BloodPressureRecord ONCE and returns (systolic, diastolic)
     *  sample lists. The caller picks the half it needs based on [HcType]. */
    private suspend fun readBloodPressure(
        client: HealthConnectClient,
        type: HcType,
        filter: TimeRangeFilter,
    ): Pair<List<RawSample>, List<RawSample>> {
        val response =
            client.readRecords(
                ReadRecordsRequest(
                    androidx.health.connect.client.records.BloodPressureRecord::class,
                    filter,
                ),
            )
        val sys =
            response.records.map { record ->
                RawSample(
                    hcType = HcType.BLOOD_PRESSURE_SYS,
                    value = record.systolic.inMillimetersOfMercury,
                    unit = "mmHg",
                    timestamp = record.time.toString(),
                )
            }
        val dia =
            response.records.map { record ->
                RawSample(
                    hcType = HcType.BLOOD_PRESSURE_DIA,
                    value = record.diastolic.inMillimetersOfMercury,
                    unit = "mmHg",
                    timestamp = record.time.toString(),
                )
            }
        return sys to dia
    }

    private suspend fun readBloodGlucose(
        client: HealthConnectClient,
        type: HcType,
        filter: TimeRangeFilter,
    ): List<RawSample> {
        val response =
            client.readRecords(
                ReadRecordsRequest(
                    androidx.health.connect.client.records.BloodGlucoseRecord::class,
                    filter,
                ),
            )
        return response.records.map { record ->
            RawSample(
                hcType = type,
                value = record.level.inMillimolesPerLiter,
                unit = "mmol/L",
                timestamp = record.time.toString(),
            )
        }
    }

    private suspend fun readBodyTemperature(
        client: HealthConnectClient,
        type: HcType,
        filter: TimeRangeFilter,
    ): List<RawSample> {
        val response =
            client.readRecords(
                ReadRecordsRequest(
                    androidx.health.connect.client.records.BodyTemperatureRecord::class,
                    filter,
                ),
            )
        return response.records.map { record ->
            RawSample(
                hcType = type,
                value = record.temperature.inCelsius,
                unit = "°C",
                timestamp = record.time.toString(),
            )
        }
    }

    private suspend fun readRespirationRate(
        client: HealthConnectClient,
        type: HcType,
        filter: TimeRangeFilter,
    ): List<RawSample> {
        val response =
            client.readRecords(
                ReadRecordsRequest(
                    androidx.health.connect.client.records.RespiratoryRateRecord::class,
                    filter,
                ),
            )
        return response.records.map { record ->
            RawSample(
                hcType = type,
                value = record.rate,
                unit = "{breaths}/min",
                timestamp = record.time.toString(),
            )
        }
    }

    private suspend fun readHeight(
        client: HealthConnectClient,
        type: HcType,
        filter: TimeRangeFilter,
    ): List<RawSample> {
        val response =
            client.readRecords(
                ReadRecordsRequest(
                    androidx.health.connect.client.records.HeightRecord::class,
                    filter,
                ),
            )
        return response.records.map { record ->
            RawSample(
                hcType = type,
                value = record.height.inMeters,
                unit = "m",
                timestamp = record.time.toString(),
            )
        }
    }

    private suspend fun readDistance(
        client: HealthConnectClient,
        type: HcType,
        filter: TimeRangeFilter,
    ): List<RawSample> {
        val response =
            client.readRecords(
                ReadRecordsRequest(
                    androidx.health.connect.client.records.DistanceRecord::class,
                    filter,
                ),
            )
        return response.records.map { record ->
            RawSample(
                hcType = type,
                value = record.distance.inMeters,
                unit = "m",
                timestamp = record.endTime.toString(),
            )
        }
    }

    private suspend fun readCalories(
        client: HealthConnectClient,
        type: HcType,
        filter: TimeRangeFilter,
    ): List<RawSample> {
        val response =
            client.readRecords(
                ReadRecordsRequest(
                    androidx.health.connect.client.records.TotalCaloriesBurnedRecord::class,
                    filter,
                ),
            )
        return response.records.map { record ->
            RawSample(
                hcType = type,
                value = record.energy.inKilocalories,
                unit = "kcal",
                timestamp = record.endTime.toString(),
            )
        }
    }

    private companion object {
        const val TAG = "HAHealthSource"
        const val HEALTH_CONNECT_AVAILABLE = 3
        const val STEP_MS = 24L * 60 * 60 * 1000 // 1-day walking window
    }
}
