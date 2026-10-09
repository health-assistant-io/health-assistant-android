package io.healthassistant.android.settings

import io.healthassistant.shared.healthconnect.HcType

/**
 * Maps a [HcType] to its Health Connect runtime permission token (Phase B of
 * the health-connect-sync plan). The Settings UI requests these per-type via
 * `ActivityResultContracts.RequestMultiplePermissions` when the user toggles a
 * type on. Mirrors the `<uses-permission>` declarations in `AndroidManifest.xml`.
 */
object HealthConnectPermissions {
    fun forType(type: HcType): String =
        when (type) {
            HcType.HEART_RATE -> "android.permission.health.READ_HEART_RATE"
            HcType.STEPS -> "android.permission.health.READ_STEPS"
            HcType.WEIGHT -> "android.permission.health.READ_WEIGHT"
            HcType.OXYGEN_SATURATION -> "android.permission.health.READ_OXYGEN_SATURATION"
            HcType.SLEEP_DURATION -> "android.permission.health.READ_SLEEP"
            HcType.BLOOD_PRESSURE_SYS, HcType.BLOOD_PRESSURE_DIA -> "android.permission.health.READ_BLOOD_PRESSURE"
            HcType.BLOOD_GLUCOSE -> "android.permission.health.READ_BLOOD_GLUCOSE"
            HcType.BODY_TEMPERATURE -> "android.permission.health.READ_BODY_TEMPERATURE"
            HcType.RESPIRATION_RATE -> "android.permission.health.READ_RESPIRATORY_RATE"
            HcType.HEIGHT -> "android.permission.health.READ_HEIGHT"
            HcType.DISTANCE -> "android.permission.health.READ_DISTANCE"
            HcType.CALORIES -> "android.permission.health.READ_TOTAL_CALORIES_BURNED"
        }

    /** All permission tokens for the given types (used to batch-request). */
    fun forTypes(types: Set<HcType>): Set<String> = types.map(::forType).toSet()
}
