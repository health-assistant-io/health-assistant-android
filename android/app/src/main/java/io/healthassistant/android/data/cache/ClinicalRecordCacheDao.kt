package io.healthassistant.android.data.cache

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for the four clinical-record cache tables (offline-first M5).
 * Writes upsert by the (connection, id) primary key; the `reconcile*` calls
 * drop rows missing from a fresh snapshot — scoped to one connection so a
 * reconcile can never drop another connection's rows. Orderings mirror the
 * bridge reads: meds by start_date (newest, nulls last), vaccines by
 * administered_at (newest, nulls last), events by onset_date (newest, nulls
 * last), allergies by created_at (newest).
 */
@Dao
interface ClinicalRecordCacheDao {
    // --- medications -----------------------------------------------------

    @Query("SELECT * FROM medication_cache WHERE connectionId = :connectionId AND id IN (:ids)")
    suspend fun medicationsByIds(
        connectionId: String,
        ids: List<String>,
    ): List<CachedMedication>

    @Query("SELECT * FROM allergy_cache WHERE connectionId = :connectionId AND id IN (:ids)")
    suspend fun allergiesByIds(
        connectionId: String,
        ids: List<String>,
    ): List<CachedAllergy>

    @Query("SELECT * FROM vaccine_cache WHERE connectionId = :connectionId AND id IN (:ids)")
    suspend fun vaccinesByIds(
        connectionId: String,
        ids: List<String>,
    ): List<CachedVaccine>

    @Query("SELECT * FROM clinical_event_cache WHERE connectionId = :connectionId AND id IN (:ids)")
    suspend fun clinicalEventsByIds(
        connectionId: String,
        ids: List<String>,
    ): List<CachedClinicalEvent>

    @Upsert
    suspend fun upsertMedications(rows: List<CachedMedication>)

    @Query("DELETE FROM medication_cache WHERE connectionId = :connectionId AND id NOT IN (:ids)")
    suspend fun reconcileMedications(
        connectionId: String,
        ids: List<String>,
    )

    @Query("DELETE FROM medication_cache WHERE connectionId = :connectionId AND id = :id")
    suspend fun deleteMedication(
        connectionId: String,
        id: String,
    )

    @Query("SELECT * FROM medication_cache WHERE connectionId = :connectionId ORDER BY startDate IS NULL, startDate DESC, rowid DESC")
    fun observeMedications(connectionId: String): Flow<List<CachedMedication>>

    @Query("DELETE FROM medication_cache WHERE connectionId = :connectionId")
    suspend fun clearMedications(connectionId: String)

    @Query("SELECT COUNT(*) FROM medication_cache WHERE connectionId = :connectionId")
    suspend fun medicationCount(connectionId: String): Int

    // --- allergies -------------------------------------------------------

    @Upsert
    suspend fun upsertAllergies(rows: List<CachedAllergy>)

    @Query("DELETE FROM allergy_cache WHERE connectionId = :connectionId AND id NOT IN (:ids)")
    suspend fun reconcileAllergies(
        connectionId: String,
        ids: List<String>,
    )

    @Query("DELETE FROM allergy_cache WHERE connectionId = :connectionId AND id = :id")
    suspend fun deleteAllergy(
        connectionId: String,
        id: String,
    )

    @Query("SELECT * FROM allergy_cache WHERE connectionId = :connectionId ORDER BY createdAt IS NULL, createdAt DESC, rowid DESC")
    fun observeAllergies(connectionId: String): Flow<List<CachedAllergy>>

    @Query("DELETE FROM allergy_cache WHERE connectionId = :connectionId")
    suspend fun clearAllergies(connectionId: String)

    @Query("SELECT COUNT(*) FROM allergy_cache WHERE connectionId = :connectionId")
    suspend fun allergyCount(connectionId: String): Int

    // --- vaccines --------------------------------------------------------

    @Upsert
    suspend fun upsertVaccines(rows: List<CachedVaccine>)

    @Query("DELETE FROM vaccine_cache WHERE connectionId = :connectionId AND id NOT IN (:ids)")
    suspend fun reconcileVaccines(
        connectionId: String,
        ids: List<String>,
    )

    @Query("DELETE FROM vaccine_cache WHERE connectionId = :connectionId AND id = :id")
    suspend fun deleteVaccine(
        connectionId: String,
        id: String,
    )

    @Query(
        "SELECT * FROM vaccine_cache WHERE connectionId = :connectionId ORDER BY administeredAt IS NULL, administeredAt DESC, rowid DESC",
    )
    fun observeVaccines(connectionId: String): Flow<List<CachedVaccine>>

    @Query("DELETE FROM vaccine_cache WHERE connectionId = :connectionId")
    suspend fun clearVaccines(connectionId: String)

    @Query("SELECT COUNT(*) FROM vaccine_cache WHERE connectionId = :connectionId")
    suspend fun vaccineCount(connectionId: String): Int

    // --- clinical events -------------------------------------------------

    @Upsert
    suspend fun upsertClinicalEvents(rows: List<CachedClinicalEvent>)

    @Query("DELETE FROM clinical_event_cache WHERE connectionId = :connectionId AND id NOT IN (:ids)")
    suspend fun reconcileClinicalEvents(
        connectionId: String,
        ids: List<String>,
    )

    @Query("DELETE FROM clinical_event_cache WHERE connectionId = :connectionId AND id = :id")
    suspend fun deleteClinicalEvent(
        connectionId: String,
        id: String,
    )

    @Query("SELECT * FROM clinical_event_cache WHERE connectionId = :connectionId ORDER BY onsetDate IS NULL, onsetDate DESC, rowid DESC")
    fun observeClinicalEvents(connectionId: String): Flow<List<CachedClinicalEvent>>

    @Query("DELETE FROM clinical_event_cache WHERE connectionId = :connectionId")
    suspend fun clearClinicalEvents(connectionId: String)

    @Query("SELECT COUNT(*) FROM clinical_event_cache WHERE connectionId = :connectionId")
    suspend fun clinicalEventCount(connectionId: String): Int
}
