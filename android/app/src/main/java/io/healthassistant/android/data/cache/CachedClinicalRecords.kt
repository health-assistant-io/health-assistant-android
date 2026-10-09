package io.healthassistant.android.data.cache

import androidx.room.Entity
import androidx.room.Index

/**
 * Room entities mirroring the shared clinical-record rows 1:1 (offline-first
 * M5) — medications, allergies, vaccines, clinical events. Column names use
 * the row field names verbatim so the entity ⇄ row conversion is mechanical.
 *
 * Offline-first M9: every row is scoped by `connectionId` (part of each
 * primary key + each index) — one patient's records can never surface under
 * another connection.
 */
@Entity(tableName = "medication_cache", primaryKeys = ["connectionId", "id"], indices = [Index(value = ["connectionId", "startDate"])])
data class CachedMedication(
    val connectionId: String,
    val id: String,
    val status: String?,
    val intent: String?,
    val codeJson: String?,
    val startDate: String?,
    val endDate: String?,
    val dosage: String?,
    val frequencyJson: String?,
    val reason: String?,
    val note: String?,
    val examinationId: String?,
    val createdAt: String?,
    val updatedAt: String?,
)

@Entity(tableName = "allergy_cache", primaryKeys = ["connectionId", "id"], indices = [Index(value = ["connectionId", "clinicalStatus"])])
data class CachedAllergy(
    val connectionId: String,
    val id: String,
    val clinicalStatus: String?,
    val verificationStatus: String?,
    val category: String?,
    val criticality: String?,
    val codeJson: String?,
    val onsetDate: String?,
    val resolvedDate: String?,
    val lastOccurrence: String?,
    val note: String?,
    val reactionsJson: String?,
    val createdAt: String?,
    val updatedAt: String?,
)

@Entity(tableName = "vaccine_cache", primaryKeys = ["connectionId", "id"], indices = [Index(value = ["connectionId", "administeredAt"])])
data class CachedVaccine(
    val connectionId: String,
    val id: String,
    val status: String?,
    val vaccineCodeJson: String?,
    val administeredAt: String?,
    val doseNumber: String?,
    val lotNumber: String?,
    val manufacturer: String?,
    val location: String?,
    val note: String?,
    val examinationId: String?,
    val createdAt: String?,
    val updatedAt: String?,
)

@Entity(tableName = "clinical_event_cache", primaryKeys = ["connectionId", "id"], indices = [Index(value = ["connectionId", "onsetDate"])])
data class CachedClinicalEvent(
    val connectionId: String,
    val id: String,
    val patientId: String?,
    val typeId: String?,
    val typeName: String?,
    val typeSlug: String?,
    val typeIcon: String?,
    val typeColor: String?,
    val status: String?,
    val title: String?,
    val description: String?,
    val onsetDate: String?,
    val resolvedDate: String?,
    val codingSystem: String?,
    val code: String?,
    val createdAt: String?,
    val updatedAt: String?,
)
