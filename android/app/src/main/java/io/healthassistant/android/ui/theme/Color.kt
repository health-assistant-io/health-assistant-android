package io.healthassistant.android.ui.theme

import androidx.compose.ui.graphics.Color

// --- Brand palette: a calm clinical teal + warm neutrals.
//     Chosen for trust + legibility; not the Material template purple. ---

// Teal primary scale
val Teal10 = Color(0xFF002B2B)
val Teal20 = Color(0xFF00504D)
val Teal30 = Color(0xFF007873)
val Teal40 = Color(0xFF009F98)
val Teal80 = Color(0xFF4CD8D2)
val Teal90 = Color(0xFF6FF0E9)

// Warm neutral surface scale (light)
val NeutralWarm99 = Color(0xFFFAFCFB)
val NeutralWarm96 = Color(0xFFF0F4F2)
val NeutralWarm90 = Color(0xFFDDE4E1)
val NeutralWarm60 = Color(0xFF5F6764)
val NeutralWarm40 = Color(0xFF474F4C)
val NeutralWarm10 = Color(0xFF1A1C1B)

// Dark surfaces
val NeutralDark99 = Color(0xFF1B1F1E)
val NeutralDark95 = Color(0xFF232826)
val NeutralDark90 = Color(0xFF2E3431)

// Accent / tertiary (soft coral) for highlights
val Coral40 = Color(0xFF9A4543)
val Coral80 = Color(0xFFFFB3AC)
val Coral90 = Color(0xFFFFDAD4)

// Error
val Red40 = Color(0xFFBA1A1A)
val Red80 = Color(0xFFFFB4AB)
val Red90 = Color(0xFFFFDAD6)

// --- Health semantic colors (light / dark pairs) ---
//     Used for in/above/below-range biomarker chips + sync status. Never decorative.
val HealthGoodLight = Color(0xFF2E7D32)
val HealthGoodDark = Color(0xFF9ED89A)
val HealthWatchLight = Color(0xFFB26A00)
val HealthWatchDark = Color(0xFFFFB95A)
val HealthAlertLight = Color(0xFFC62828)
val HealthAlertDark = Color(0xFFFF9A9A)
val SyncActiveLight = Teal40
val SyncActiveDark = Teal80
val SyncIdleLight = NeutralWarm60
val SyncIdleDark = Color(0xFFB8C2BE)
