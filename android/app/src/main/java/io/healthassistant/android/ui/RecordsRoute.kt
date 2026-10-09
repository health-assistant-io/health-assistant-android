package io.healthassistant.android.ui

import androidx.compose.runtime.Composable

/**
 * Stateful owner of the Records tab — a thin hub that forwards the six
 * record-type deep links (Biomarkers, Examinations, Medications, Allergies,
 * Vaccines, Clinical events) to their native list routes. The exam list itself
 * lives on [ExaminationsRoute] (a detail route), so this tab has no VM of its
 * own. In SIMPLE mode the Doctors directory row hides (K.3).
 */
@Composable
fun RecordsRoute(
    onOpenBiomarkers: () -> Unit = {},
    onOpenExaminations: () -> Unit = {},
    onOpenMedications: () -> Unit = {},
    onOpenAllergies: () -> Unit = {},
    onOpenVaccines: () -> Unit = {},
    onOpenEvents: () -> Unit = {},
    onOpenDoctors: () -> Unit = {},
    simpleMode: Boolean = false,
) {
    RecordsScreen(
        onOpenBiomarkers = onOpenBiomarkers,
        onOpenExaminations = onOpenExaminations,
        onOpenMedications = onOpenMedications,
        onOpenAllergies = onOpenAllergies,
        onOpenVaccines = onOpenVaccines,
        onOpenEvents = onOpenEvents,
        onOpenDoctors = onOpenDoctors,
        simpleMode = simpleMode,
    )
}
