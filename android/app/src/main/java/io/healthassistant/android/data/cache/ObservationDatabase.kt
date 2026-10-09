package io.healthassistant.android.data.cache

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The on-device Room database (M8 observation cache + Phase C outbox +
 * offline-first M2 biomarker catalog + M3 examinations + documents). All
 * entities live in one SQLCipher-encrypted DB (the `SupportFactory` is wired
 * in `di/AppModule.kt`). Constructed once via Koin — no companion singleton so
 * DI stays the single construction path and tests can build an in-memory copy.
 *
 * `exportSchema = true` — the versioned schema JSONs under `schemas/` back the
 * `MigrationTestHelper`-based migration tests (enabled at v9: the migration
 * count grew past the point where hand-rolled DDL tests are trustworthy).
 * Version 2 added the `outbox` table (Phase C migration of the hand-rolled
 * `SqliteOutboxStore`); version 3 adds `biomarker_cache` (offline-first M2);
 * version 4 adds `examination_cache` + `document_cache` (offline-first M3);
 * version 9 scopes every cache table by `connection_id` and adds `cache_meta`
 * (offline-first M9) — see [CacheMigrations].
 */
@Database(
    entities = [
        CachedObservation::class,
        OutboxEntity::class,
        CachedBiomarker::class,
        CachedExamination::class,
        CachedDocument::class,
        CachedMedication::class,
        CachedAllergy::class,
        CachedVaccine::class,
        CachedClinicalEvent::class,
        CachedNotification::class,
        CacheMetaEntity::class,
    ],
    version = 9,
    exportSchema = true,
)
abstract class ObservationDatabase : RoomDatabase() {
    abstract fun cacheDao(): ObservationCacheDao

    abstract fun outboxDao(): OutboxDao

    abstract fun biomarkerDao(): BiomarkerCacheDao

    abstract fun examinationDao(): ExaminationCacheDao

    abstract fun documentDao(): DocumentCacheDao

    abstract fun clinicalRecordDao(): ClinicalRecordCacheDao

    abstract fun notificationDao(): NotificationCacheDao

    abstract fun cacheMetaDao(): CacheMetaDao
}
