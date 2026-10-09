package io.healthassistant.android.data.cache

import androidx.room.withTransaction
import io.healthassistant.bridge.Allergy
import io.healthassistant.bridge.ClinicalEvent
import io.healthassistant.bridge.Medication
import io.healthassistant.bridge.Vaccine
import io.healthassistant.shared.data.cache.AllergyDeltaRow
import io.healthassistant.shared.data.cache.CacheRefreshMeta
import io.healthassistant.shared.data.cache.CachedAllergyRow
import io.healthassistant.shared.data.cache.CachedClinicalEventRow
import io.healthassistant.shared.data.cache.CachedMedicationRow
import io.healthassistant.shared.data.cache.CachedVaccineRow
import io.healthassistant.shared.data.cache.ClinicalEventDeltaRow
import io.healthassistant.shared.data.cache.ClinicalRecordCache
import io.healthassistant.shared.data.cache.ClinicalRecordCacheMapper
import io.healthassistant.shared.data.cache.DeltaRows
import io.healthassistant.shared.data.cache.MedicationDeltaRow
import io.healthassistant.shared.data.cache.VaccineDeltaRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Android Room implementation of the shared [ClinicalRecordCache]
 * (offline-first M5), bound to one bridge connection (offline-first M9): every
 * read, write, and reconcile is scoped by the connection id — one patient's
 * records can never surface under another connection. Entity ⇄ row conversions
 * are mechanical (same fields); model ⇄ row goes through the shared
 * [ClinicalRecordCacheMapper] so the JSON-field encode/decode stays in the KMP
 * core. Empty-snapshot reconciles clear the connection's slice instead of
 * binding an empty `NOT IN ()` collection. A `*Synced` write records the
 * cache_meta success row in the SAME Room transaction as the upsert.
 */
class RoomClinicalRecordCache(
    private val db: ObservationDatabase,
    private val connectionId: String,
) : ClinicalRecordCache {
    private val dao get() = db.clinicalRecordDao()

    override suspend fun storeMedications(rows: List<CachedMedicationRow>) {
        if (rows.isEmpty()) return
        dao.upsertMedications(rows.map { it.toEntity(connectionId) })
    }

    override suspend fun storeMedicationsSynced(
        rows: List<CachedMedicationRow>,
        meta: CacheRefreshMeta,
    ) {
        if (rows.isEmpty()) return recordSuccess(meta)
        db.withTransaction {
            dao.upsertMedications(rows.map { it.toEntity(connectionId) })
            db.cacheMetaDao().recordSuccess(connectionId, meta.domain.wire, meta.successAtEpochMs ?: 0L, dao.medicationCount(connectionId))
        }
    }

    override suspend fun reconcileMedications(ids: List<String>) {
        if (ids.isEmpty()) dao.clearMedications(connectionId) else dao.reconcileMedications(connectionId, ids)
    }

    override suspend fun deleteMedication(id: String) = dao.deleteMedication(connectionId, id)

    override fun observeMedications(): Flow<List<Medication>> =
        dao.observeMedications(connectionId).map { rows -> rows.map { ClinicalRecordCacheMapper.toModel(it.toRow()) } }

    override suspend fun storeAllergies(rows: List<CachedAllergyRow>) {
        if (rows.isEmpty()) return
        dao.upsertAllergies(rows.map { it.toEntity(connectionId) })
    }

    override suspend fun storeAllergiesSynced(
        rows: List<CachedAllergyRow>,
        meta: CacheRefreshMeta,
    ) {
        if (rows.isEmpty()) return recordSuccess(meta)
        db.withTransaction {
            dao.upsertAllergies(rows.map { it.toEntity(connectionId) })
            db.cacheMetaDao().recordSuccess(connectionId, meta.domain.wire, meta.successAtEpochMs ?: 0L, dao.allergyCount(connectionId))
        }
    }

    override suspend fun reconcileAllergies(ids: List<String>) {
        if (ids.isEmpty()) dao.clearAllergies(connectionId) else dao.reconcileAllergies(connectionId, ids)
    }

    override suspend fun deleteAllergy(id: String) = dao.deleteAllergy(connectionId, id)

    override fun observeAllergies(): Flow<List<Allergy>> =
        dao.observeAllergies(connectionId).map { rows -> rows.map { ClinicalRecordCacheMapper.toModel(it.toRow()) } }

    override suspend fun storeVaccines(rows: List<CachedVaccineRow>) {
        if (rows.isEmpty()) return
        dao.upsertVaccines(rows.map { it.toEntity(connectionId) })
    }

    override suspend fun storeVaccinesSynced(
        rows: List<CachedVaccineRow>,
        meta: CacheRefreshMeta,
    ) {
        if (rows.isEmpty()) return recordSuccess(meta)
        db.withTransaction {
            dao.upsertVaccines(rows.map { it.toEntity(connectionId) })
            db.cacheMetaDao().recordSuccess(connectionId, meta.domain.wire, meta.successAtEpochMs ?: 0L, dao.vaccineCount(connectionId))
        }
    }

    override suspend fun reconcileVaccines(ids: List<String>) {
        if (ids.isEmpty()) dao.clearVaccines(connectionId) else dao.reconcileVaccines(connectionId, ids)
    }

    override suspend fun deleteVaccine(id: String) = dao.deleteVaccine(connectionId, id)

    override fun observeVaccines(): Flow<List<Vaccine>> =
        dao.observeVaccines(connectionId).map { rows -> rows.map { ClinicalRecordCacheMapper.toModel(it.toRow()) } }

    override suspend fun storeClinicalEvents(rows: List<CachedClinicalEventRow>) {
        if (rows.isEmpty()) return
        dao.upsertClinicalEvents(rows.map { it.toEntity(connectionId) })
    }

    override suspend fun storeClinicalEventsSynced(
        rows: List<CachedClinicalEventRow>,
        meta: CacheRefreshMeta,
    ) {
        if (rows.isEmpty()) return recordSuccess(meta)
        db.withTransaction {
            dao.upsertClinicalEvents(rows.map { it.toEntity(connectionId) })
            db.cacheMetaDao().recordSuccess(
                connectionId,
                meta.domain.wire,
                meta.successAtEpochMs ?: 0L,
                dao.clinicalEventCount(connectionId),
            )
        }
    }

    override suspend fun reconcileClinicalEvents(ids: List<String>) {
        if (ids.isEmpty()) dao.clearClinicalEvents(connectionId) else dao.reconcileClinicalEvents(connectionId, ids)
    }

    override suspend fun deleteClinicalEvent(id: String) = dao.deleteClinicalEvent(connectionId, id)

    override fun observeClinicalEvents(): Flow<List<ClinicalEvent>> =
        dao.observeClinicalEvents(connectionId).map { rows -> rows.map { ClinicalRecordCacheMapper.toModel(it.toRow()) } }

    override suspend fun clear() {
        dao.clearMedications(connectionId)
        dao.clearAllergies(connectionId)
        dao.clearVaccines(connectionId)
        dao.clearClinicalEvents(connectionId)
    }

    override suspend fun applyMedicationDeltas(rows: List<MedicationDeltaRow>) {
        if (rows.isEmpty()) return
        val existing = dao.medicationsByIds(connectionId, rows.map { it.id }).associateBy { it.id }
        val byId = rows.associateBy { it.id }
        storeMedications(rows.map { DeltaRows.merge(existing[it.id]?.toRow(), byId[it.id]!!) })
    }

    override suspend fun applyAllergyDeltas(rows: List<AllergyDeltaRow>) {
        if (rows.isEmpty()) return
        val existing = dao.allergiesByIds(connectionId, rows.map { it.id }).associateBy { it.id }
        val byId = rows.associateBy { it.id }
        storeAllergies(rows.map { DeltaRows.merge(existing[it.id]?.toRow(), byId[it.id]!!) })
    }

    override suspend fun applyVaccineDeltas(rows: List<VaccineDeltaRow>) {
        if (rows.isEmpty()) return
        val existing = dao.vaccinesByIds(connectionId, rows.map { it.id }).associateBy { it.id }
        val byId = rows.associateBy { it.id }
        storeVaccines(rows.map { DeltaRows.merge(existing[it.id]?.toRow(), byId[it.id]!!) })
    }

    override suspend fun applyClinicalEventDeltas(rows: List<ClinicalEventDeltaRow>) {
        if (rows.isEmpty()) return
        val existing = dao.clinicalEventsByIds(connectionId, rows.map { it.id }).associateBy { it.id }
        val byId = rows.associateBy { it.id }
        storeClinicalEvents(rows.map { DeltaRows.merge(existing[it.id]?.toRow(), byId[it.id]!!) })
    }

    private suspend fun recordSuccess(meta: CacheRefreshMeta) {
        db.cacheMetaDao().recordSuccess(connectionId, meta.domain.wire, meta.successAtEpochMs ?: 0L, 0)
    }
}

private fun CachedMedicationRow.toEntity(connectionId: String): CachedMedication =
    CachedMedication(
        connectionId,
        id,
        status,
        intent,
        codeJson,
        startDate,
        endDate,
        dosage,
        frequencyJson,
        reason,
        note,
        examinationId,
        createdAt,
        updatedAt,
    )

private fun CachedAllergyRow.toEntity(connectionId: String): CachedAllergy =
    CachedAllergy(
        connectionId,
        id,
        clinicalStatus,
        verificationStatus,
        category,
        criticality,
        codeJson,
        onsetDate,
        resolvedDate,
        lastOccurrence,
        note,
        reactionsJson,
        createdAt,
        updatedAt,
    )

private fun CachedVaccineRow.toEntity(connectionId: String): CachedVaccine =
    CachedVaccine(
        connectionId,
        id,
        status,
        vaccineCodeJson,
        administeredAt,
        doseNumber,
        lotNumber,
        manufacturer,
        location,
        note,
        examinationId,
        createdAt,
        updatedAt,
    )

private fun CachedClinicalEventRow.toEntity(connectionId: String): CachedClinicalEvent =
    CachedClinicalEvent(
        connectionId,
        id,
        patientId,
        typeId,
        typeName,
        typeSlug,
        typeIcon,
        typeColor,
        status,
        title,
        description,
        onsetDate,
        resolvedDate,
        codingSystem,
        code,
        createdAt,
        updatedAt,
    )

private fun CachedMedication.toRow(): CachedMedicationRow =
    CachedMedicationRow(
        id,
        status,
        intent,
        codeJson,
        startDate,
        endDate,
        dosage,
        frequencyJson,
        reason,
        note,
        examinationId,
        createdAt,
        updatedAt,
    )

private fun CachedAllergy.toRow(): CachedAllergyRow =
    CachedAllergyRow(
        id,
        clinicalStatus,
        verificationStatus,
        category,
        criticality,
        codeJson,
        onsetDate,
        resolvedDate,
        lastOccurrence,
        note,
        reactionsJson,
        createdAt,
        updatedAt,
    )

private fun CachedVaccine.toRow(): CachedVaccineRow =
    CachedVaccineRow(
        id,
        status,
        vaccineCodeJson,
        administeredAt,
        doseNumber,
        lotNumber,
        manufacturer,
        location,
        note,
        examinationId,
        createdAt,
        updatedAt,
    )

private fun CachedClinicalEvent.toRow(): CachedClinicalEventRow =
    CachedClinicalEventRow(
        id,
        patientId,
        typeId,
        typeName,
        typeSlug,
        typeIcon,
        typeColor,
        status,
        title,
        description,
        onsetDate,
        resolvedDate,
        codingSystem,
        code,
        createdAt,
        updatedAt,
    )
