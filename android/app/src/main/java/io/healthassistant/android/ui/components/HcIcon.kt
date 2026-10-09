package io.healthassistant.android.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Height
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.MonitorWeight
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.ui.graphics.vector.ImageVector
import io.healthassistant.shared.healthconnect.HcType

/** Semantic icon per [HcType], reused on Home, Insights, and Records. */
fun hcIcon(type: HcType): ImageVector =
    when (type) {
        HcType.HEART_RATE -> Icons.Outlined.Favorite
        HcType.STEPS -> Icons.Outlined.DirectionsWalk
        HcType.WEIGHT -> Icons.Outlined.MonitorWeight
        HcType.OXYGEN_SATURATION -> Icons.Outlined.Air
        HcType.SLEEP_DURATION -> Icons.Outlined.Bedtime
        HcType.BLOOD_PRESSURE_SYS, HcType.BLOOD_PRESSURE_DIA -> Icons.Outlined.MonitorHeart
        HcType.BLOOD_GLUCOSE -> Icons.Outlined.WaterDrop
        HcType.BODY_TEMPERATURE -> Icons.Outlined.Thermostat
        HcType.RESPIRATION_RATE -> Icons.Outlined.Air
        HcType.HEIGHT -> Icons.Outlined.Height
        HcType.DISTANCE -> Icons.Outlined.DirectionsRun
        HcType.CALORIES -> Icons.Outlined.LocalFireDepartment
    }

/** Icon for a Home card: the HC semantic icon when the reading maps to a known
 *  [HcType], else a generic science/lab icon for instance-only biomarkers. */
fun biomarkerIcon(hcType: HcType?): ImageVector = hcType?.let(::hcIcon) ?: Icons.Outlined.Science
