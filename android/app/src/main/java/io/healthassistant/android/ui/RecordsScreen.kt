package io.healthassistant.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.MedicalInformation
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.healthassistant.android.R

/**
 * The Records tab — a hub listing every record type as a uniform, tappable row
 * (Examinations, Medications, Allergies, Vaccines, Clinical events). Each row
 * deep-links to its native list screen. In SIMPLE mode (K.3) the five daily
 * clinical rows + Biomarkers stay; the Doctors directory (a management
 * surface) hides. Pure state + lambdas.
 */
@Composable
fun RecordsScreen(
    onOpenBiomarkers: () -> Unit = {},
    onOpenExaminations: () -> Unit = {},
    onOpenMedications: () -> Unit = {},
    onOpenAllergies: () -> Unit = {},
    onOpenVaccines: () -> Unit = {},
    onOpenEvents: () -> Unit = {},
    onOpenDoctors: () -> Unit = {},
    simpleMode: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
    ) {
        Text(stringResource(R.string.nav_records), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.records_all_section), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        RecordTypeRow(
            icon = Icons.Outlined.MonitorHeart,
            label = stringResource(R.string.biomarkers_title),
            onClick = onOpenBiomarkers,
        )
        RecordTypeRow(
            icon = Icons.Outlined.Folder,
            label = stringResource(R.string.records_examinations),
            onClick = onOpenExaminations,
        )
        RecordTypeRow(
            icon = Icons.Outlined.Medication,
            label = stringResource(R.string.records_medications),
            onClick = onOpenMedications,
        )
        RecordTypeRow(
            icon = Icons.Outlined.Warning,
            label = stringResource(R.string.records_allergies),
            onClick = onOpenAllergies,
        )
        RecordTypeRow(
            icon = Icons.Outlined.Shield,
            label = stringResource(R.string.records_vaccines),
            onClick = onOpenVaccines,
        )
        RecordTypeRow(
            icon = Icons.Outlined.LocalHospital,
            label = stringResource(R.string.records_events),
            onClick = onOpenEvents,
        )
        if (!simpleMode) {
            RecordTypeRow(
                icon = Icons.Outlined.MedicalInformation,
                label = stringResource(R.string.records_doctors),
                onClick = onOpenDoctors,
            )
        }
    }
}

/** One tappable row in the Records hub that deep-links to a native
 *  clinical-record list. */
@Composable
private fun RecordTypeRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        ListItem(
            leadingContent = {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            },
            headlineContent = { Text(label) },
            trailingContent = {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            modifier = Modifier.padding(vertical = 1.dp).clickable(onClick = onClick),
        )
    }
}
