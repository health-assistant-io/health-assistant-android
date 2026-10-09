package io.healthassistant.android.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.ui.graphics.vector.ImageVector
import io.healthassistant.android.R

/** The three fixed top-level destinations shown in the bottom NavigationBar
 *  (Home, Records, Profile). Home absorbs the daily check-in sections of the
 *  old Today tab (2026-08-17 merge) and Insights lives under Records as the
 *  Biomarkers page — the graph detail is a drill-down route. */
enum class HATab(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
) {
    Home("home", R.string.nav_home, Icons.Outlined.Home),
    Records("records", R.string.nav_records, Icons.Outlined.Folder),
    Profile("profile", R.string.nav_profile, Icons.Outlined.Person),
    ;

    /** Matches a nav destination route pattern, tolerating a query-arg suffix. */
    fun matches(route: String?): Boolean = route == this.route || route?.startsWith("${this.route}?") == true
}

/** Detail routes (full-Scaffold screens reached from a tab; no bottom bar). */
object HARoutes {
    const val SYNC = "sync"
    const val INBOX = "inbox"

    // Phase H — the native clinical-record list screens (reached from Records).
    const val EXAMINATIONS = "examinations"
    const val MEDICATIONS = "medications"
    const val ALLERGIES = "allergies"
    const val VACCINES = "vaccines"
    const val EVENTS = "events"
    const val DOCTORS = "doctors"

    // R6 — server-side notification preferences + triggers.
    const val NOTIFICATIONS_SETTINGS = "notifications_settings"

    // M5 — local alert rules + the plain-language rule builder. The optional
    // code arg prefills the builder (the biomarker detail's "Set an alert").
    const val ALERT_RULES = "alert_rules"
    const val ALERT_RULE_ARG = "code"
    const val ALERT_RULES_WITH_ARG = "alert_rules?code={$ALERT_RULE_ARG}"

    fun alertRules(code: String? = null): String = if (code.isNullOrBlank()) ALERT_RULES else "alert_rules?code=$code"

    // Profile detail pages (hub → page, replacing the expandable cards).
    const val SYNC_SETTINGS = "sync_settings"
    const val DATA_STORAGE = "data_storage"
    const val DEVICE_NOTIFICATIONS = "device_notifications"
    const val ACCESSIBILITY = "accessibility"
    const val PRIVACY = "privacy"
    const val ABOUT = "about"

    // Biomarkers list (Records › Biomarkers) + its graph detail + the
    // multi-metric wellness overview (M6).
    const val BIOMARKERS = "biomarkers"
    const val BIOMARKER_ARG = "code"
    const val BIOMARKER_DETAIL_WITH_ARG = "biomarker_detail?code={$BIOMARKER_ARG}"
    const val OVERVIEW = "overview"

    fun biomarkerDetail(code: String): String = "biomarker_detail?code=$code"

    const val EXAM_DETAIL_ARG = "examId"
    const val EXAM_DETAIL_WITH_ARG = "exam_detail?examId={$EXAM_DETAIL_ARG}"

    const val DOC_ID_ARG = "docId"
    const val DOC_NAME_ARG = "filename"
    const val DOC_PREVIEW_WITH_ARG =
        "doc_preview?docId={$DOC_ID_ARG}&filename={$DOC_NAME_ARG}"

    const val EVENT_DETAIL_ARG = "eventId"
    const val EVENT_DETAIL_WITH_ARG = "event_detail?eventId={$EVENT_DETAIL_ARG}"

    /** Route into a clinical event's detail (R5). */
    fun eventDetail(eventId: String): String = "event_detail?eventId=$eventId"

    /** Route into an examination's full record. The Phase A flow renders this
     *  natively (ExaminationDetailRoute); a separate "Open in browser" action
     *  still hands the user to the PWA for the rich edit surface. */
    fun examDetail(examId: String): String = "exam_detail?examId=$examId"

    /** Route into the inline image preview (Coil). PDFs / DICOM / others go
     *  through ACTION_VIEW + FileProvider and never reach this route. */
    fun docPreview(
        docId: String,
        filename: String?,
    ): String = "doc_preview?docId=$docId&filename=${filename ?: ""}"
}
