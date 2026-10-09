package io.healthassistant.android.data.cache

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Offline-first M2 — v2 → v3 adds the `biomarker_cache` table (the on-device
 * biomarker catalog snapshot). Additive only: existing tables (observation
 * cache + outbox) are untouched, so no data can be lost. The SQL must match
 * Room's expected schema exactly (column order is irrelevant; names, types,
 * nullability, and indices are not).
 */
object CacheMigrations {
    val MIGRATION_2_3: Migration =
        object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `biomarker_cache` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `slug` TEXT,
                        `code` TEXT,
                        `coding_system` TEXT,
                        `unit` TEXT,
                        `is_telemetry` INTEGER NOT NULL,
                        `reference_range_min` REAL,
                        `reference_range_max` REAL,
                        `value_type` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_biomarker_cache_code` ON `biomarker_cache` (`code`)")
            }
        }

    /**
     * Offline-first M3 — v3 → v4 adds the `examination_cache` +
     * `document_cache` tables (Records list + exam-detail documents). Additive
     * only, like MIGRATION_2_3.
     */
    val MIGRATION_3_4: Migration =
        object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `examination_cache` (
                        `id` TEXT NOT NULL,
                        `examination_date` TEXT,
                        `notes` TEXT,
                        `patient_notes` TEXT,
                        `extraction_status` TEXT,
                        `diagnoses` TEXT,
                        `impressions` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_examination_cache_examination_date` ON `examination_cache` (`examination_date`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `document_cache` (
                        `id` TEXT NOT NULL,
                        `filename` TEXT,
                        `status` TEXT,
                        `progress` REAL,
                        `external_id` TEXT,
                        `created_at` TEXT,
                        `content_type` TEXT,
                        `file_size` INTEGER,
                        `examination_id` TEXT,
                        `local_path` TEXT,
                        `local_cached_at` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_document_cache_examination_id_created_at` " +
                        " ON `document_cache` (`examination_id`, `created_at`)",
                )
            }
        }

    /**
     * Offline-first M5 — v4 → v5 adds the four clinical-record cache tables
     * (medications, allergies, vaccines, clinical events). Additive only, like
     * the earlier migrations.
     */
    val MIGRATION_4_5: Migration =
        object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `medication_cache` (
                        `id` TEXT NOT NULL,
                        `status` TEXT,
                        `intent` TEXT,
                        `codeJson` TEXT,
                        `startDate` TEXT,
                        `endDate` TEXT,
                        `dosage` TEXT,
                        `frequencyJson` TEXT,
                        `reason` TEXT,
                        `note` TEXT,
                        `examinationId` TEXT,
                        `createdAt` TEXT,
                        `updatedAt` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_medication_cache_startDate` ON `medication_cache` (`startDate`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `allergy_cache` (
                        `id` TEXT NOT NULL,
                        `clinicalStatus` TEXT,
                        `verificationStatus` TEXT,
                        `category` TEXT,
                        `criticality` TEXT,
                        `codeJson` TEXT,
                        `onsetDate` TEXT,
                        `resolvedDate` TEXT,
                        `lastOccurrence` TEXT,
                        `note` TEXT,
                        `reactionsJson` TEXT,
                        `createdAt` TEXT,
                        `updatedAt` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_allergy_cache_clinicalStatus` ON `allergy_cache` (`clinicalStatus`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `vaccine_cache` (
                        `id` TEXT NOT NULL,
                        `status` TEXT,
                        `vaccineCodeJson` TEXT,
                        `administeredAt` TEXT,
                        `doseNumber` TEXT,
                        `lotNumber` TEXT,
                        `manufacturer` TEXT,
                        `location` TEXT,
                        `note` TEXT,
                        `examinationId` TEXT,
                        `createdAt` TEXT,
                        `updatedAt` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vaccine_cache_administeredAt` ON `vaccine_cache` (`administeredAt`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `clinical_event_cache` (
                        `id` TEXT NOT NULL,
                        `patientId` TEXT,
                        `typeId` TEXT,
                        `typeName` TEXT,
                        `typeSlug` TEXT,
                        `typeIcon` TEXT,
                        `typeColor` TEXT,
                        `status` TEXT,
                        `title` TEXT,
                        `description` TEXT,
                        `onsetDate` TEXT,
                        `resolvedDate` TEXT,
                        `codingSystem` TEXT,
                        `code` TEXT,
                        `createdAt` TEXT,
                        `updatedAt` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_clinical_event_cache_onsetDate` ON `clinical_event_cache` (`onsetDate`)")
            }
        }

    /**
     * Offline-first M6 — v5 → v6 adds the `notification_cache` table (the
     * owner-scoped notification inbox). Additive only, like the earlier
     * migrations.
     */
    val MIGRATION_5_6: Migration =
        object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `notification_cache` (
                        `recipientId` TEXT NOT NULL,
                        `status` TEXT,
                        `readAt` TEXT,
                        `dismissedAt` TEXT,
                        `notificationId` TEXT,
                        `title` TEXT,
                        `body` TEXT,
                        `type` TEXT,
                        `category` TEXT,
                        `severity` TEXT,
                        `source` TEXT,
                        `payloadJson` TEXT,
                        `patientId` TEXT,
                        `createdAt` TEXT,
                        PRIMARY KEY(`recipientId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notification_cache_readAt` ON `notification_cache` (`readAt`)")
            }
        }

    /** R4 — v6 → v7 adds the biomarker `info` column (Markdown education
     *  text from the bridge catalog read). Additive only. */
    val MIGRATION_6_7: Migration =
        object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `biomarker_cache` ADD COLUMN `info` TEXT")
            }
        }

    /** R4-followup — v7 → v8 adds the exam detail-projection columns
     *  (category / lab_name / external_id) so the bridge detail fields
     *  survive the Room round-trip. Additive only. */
    val MIGRATION_7_8: Migration =
        object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `examination_cache` ADD COLUMN `category` TEXT")
                db.execSQL("ALTER TABLE `examination_cache` ADD COLUMN `lab_name` TEXT")
                db.execSQL("ALTER TABLE `examination_cache` ADD COLUMN `external_id` TEXT")
            }
        }

    /**
     * Offline-first M9 — v8 → v9 scopes every cache table by `connection_id`:
     * each table is rebuilt with `(connection_id, <id>)` as the primary key and
     * `connection_id` prepended to every index, existing rows are backfilled
     * with [activeConnectionId] (the active bridge connection — pre-M9 installs
     * only ever cached the active connection), and the `cache_meta` staleness
     * table is created (plan §3.3). Data-preserving: every rebuild copies the
     * old rows across; nothing is dropped. `fallbackToDestructiveMigration`
     * stays off — a failed migration must never wipe health data.
     */
    fun migration8to9(activeConnectionId: String): Migration =
        object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val conn = activeConnectionId.replace("'", "''")
                rebuild(db, conn)
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cache_meta` (
                        `connection_id` TEXT NOT NULL,
                        `domain` TEXT NOT NULL,
                        `filter_key` TEXT NOT NULL DEFAULT '',
                        `last_success_at` INTEGER NOT NULL DEFAULT 0,
                        `last_error` TEXT,
                        `row_count` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`connection_id`, `domain`, `filter_key`)
                    )
                    """.trimIndent(),
                )
            }
        }

    private fun rebuild(
        db: SupportSQLiteDatabase,
        conn: String,
    ) {
        scopeTable(
            db,
            table = "observation_cache",
            connectionColumn = "`connection_id`",
            columns =
                listOf(
                    "id",
                    "biomarker_code",
                    "effective_datetime",
                    "effective_epoch_ms",
                    "raw_value",
                    "normalized_value",
                    "normalized_unit",
                    "value_string",
                    "code_text",
                    "coding_display",
                    "coding_system",
                    "reference_range_low",
                    "reference_range_high",
                    "biomarker_id",
                    "biomarker_slug",
                    "biomarker_value_type",
                    "interpretation",
                    "relative_score",
                    "biomarker_reference_range_min",
                    "biomarker_reference_range_max",
                ),
            create =
                """
                CREATE TABLE IF NOT EXISTS `observation_cache_v9` (
                    `connection_id` TEXT NOT NULL,
                    `id` TEXT NOT NULL,
                    `biomarker_code` TEXT NOT NULL,
                    `effective_datetime` TEXT,
                    `effective_epoch_ms` INTEGER,
                    `raw_value` REAL,
                    `normalized_value` REAL,
                    `normalized_unit` TEXT,
                    `value_string` TEXT,
                    `code_text` TEXT,
                    `coding_display` TEXT,
                    `coding_system` TEXT,
                    `reference_range_low` REAL,
                    `reference_range_high` REAL,
                    `biomarker_id` TEXT,
                    `biomarker_slug` TEXT,
                    `biomarker_value_type` TEXT,
                    `interpretation` TEXT,
                    `relative_score` REAL,
                    `biomarker_reference_range_min` REAL,
                    `biomarker_reference_range_max` REAL,
                    PRIMARY KEY(`connection_id`, `id`)
                )
                """.trimIndent(),
            indices =
                listOf(
                    "CREATE INDEX IF NOT EXISTS `index_observation_cache_connection_id_biomarker_code` " +
                        " ON `observation_cache` (`connection_id`, `biomarker_code`)",
                    "CREATE INDEX IF NOT EXISTS `index_observation_cache_connection_id_biomarker_code_effective_epoch_ms` " +
                        " ON `observation_cache` (`connection_id`, `biomarker_code`, `effective_epoch_ms`)",
                ),
            conn = conn,
        )
        scopeTable(
            db,
            table = "biomarker_cache",
            connectionColumn = "`connection_id`",
            columns =
                listOf(
                    "id",
                    "name",
                    "slug",
                    "code",
                    "coding_system",
                    "unit",
                    "is_telemetry",
                    "reference_range_min",
                    "reference_range_max",
                    "value_type",
                    "info",
                ),
            create =
                """
                CREATE TABLE IF NOT EXISTS `biomarker_cache_v9` (
                    `connection_id` TEXT NOT NULL,
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `slug` TEXT,
                    `code` TEXT,
                    `coding_system` TEXT,
                    `unit` TEXT,
                    `is_telemetry` INTEGER NOT NULL,
                    `reference_range_min` REAL,
                    `reference_range_max` REAL,
                    `value_type` TEXT,
                    `info` TEXT,
                    PRIMARY KEY(`connection_id`, `id`)
                )
                """.trimIndent(),
            indices =
                listOf(
                    "CREATE INDEX IF NOT EXISTS `index_biomarker_cache_connection_id_code` ON `biomarker_cache` (`connection_id`, `code`)",
                ),
            conn = conn,
        )
        scopeTable(
            db,
            table = "examination_cache",
            connectionColumn = "`connection_id`",
            columns =
                listOf(
                    "id",
                    "examination_date",
                    "notes",
                    "patient_notes",
                    "extraction_status",
                    "diagnoses",
                    "impressions",
                    "category",
                    "lab_name",
                    "external_id",
                ),
            create =
                """
                CREATE TABLE IF NOT EXISTS `examination_cache_v9` (
                    `connection_id` TEXT NOT NULL,
                    `id` TEXT NOT NULL,
                    `examination_date` TEXT,
                    `notes` TEXT,
                    `patient_notes` TEXT,
                    `extraction_status` TEXT,
                    `diagnoses` TEXT,
                    `impressions` TEXT,
                    `category` TEXT,
                    `lab_name` TEXT,
                    `external_id` TEXT,
                    PRIMARY KEY(`connection_id`, `id`)
                )
                """.trimIndent(),
            indices =
                listOf(
                    "CREATE INDEX IF NOT EXISTS `index_examination_cache_connection_id_examination_date` " +
                        " ON `examination_cache` (`connection_id`, `examination_date`)",
                ),
            conn = conn,
        )
        scopeTable(
            db,
            table = "document_cache",
            connectionColumn = "`connection_id`",
            columns =
                listOf(
                    "id",
                    "filename",
                    "status",
                    "progress",
                    "external_id",
                    "created_at",
                    "content_type",
                    "file_size",
                    "examination_id",
                    "local_path",
                    "local_cached_at",
                ),
            create =
                """
                CREATE TABLE IF NOT EXISTS `document_cache_v9` (
                    `connection_id` TEXT NOT NULL,
                    `id` TEXT NOT NULL,
                    `filename` TEXT,
                    `status` TEXT,
                    `progress` REAL,
                    `external_id` TEXT,
                    `created_at` TEXT,
                    `content_type` TEXT,
                    `file_size` INTEGER,
                    `examination_id` TEXT,
                    `local_path` TEXT,
                    `local_cached_at` INTEGER,
                    PRIMARY KEY(`connection_id`, `id`)
                )
                """.trimIndent(),
            indices =
                listOf(
                    "CREATE INDEX IF NOT EXISTS `index_document_cache_connection_id_examination_id_created_at` " +
                        " ON `document_cache` (`connection_id`, `examination_id`, `created_at`)",
                ),
            conn = conn,
        )
        scopeTable(
            db,
            table = "medication_cache",
            connectionColumn = "`connectionId`",
            columns =
                listOf(
                    "id",
                    "status",
                    "intent",
                    "codeJson",
                    "startDate",
                    "endDate",
                    "dosage",
                    "frequencyJson",
                    "reason",
                    "note",
                    "examinationId",
                    "createdAt",
                    "updatedAt",
                ),
            create =
                """
                CREATE TABLE IF NOT EXISTS `medication_cache_v9` (
                    `connectionId` TEXT NOT NULL,
                    `id` TEXT NOT NULL,
                    `status` TEXT,
                    `intent` TEXT,
                    `codeJson` TEXT,
                    `startDate` TEXT,
                    `endDate` TEXT,
                    `dosage` TEXT,
                    `frequencyJson` TEXT,
                    `reason` TEXT,
                    `note` TEXT,
                    `examinationId` TEXT,
                    `createdAt` TEXT,
                    `updatedAt` TEXT,
                    PRIMARY KEY(`connectionId`, `id`)
                )
                """.trimIndent(),
            indices =
                listOf(
                    "CREATE INDEX IF NOT EXISTS `index_medication_cache_connectionId_startDate` " +
                        " ON `medication_cache` (`connectionId`, `startDate`)",
                ),
            conn = conn,
        )
        scopeTable(
            db,
            table = "allergy_cache",
            connectionColumn = "`connectionId`",
            columns =
                listOf(
                    "id",
                    "clinicalStatus",
                    "verificationStatus",
                    "category",
                    "criticality",
                    "codeJson",
                    "onsetDate",
                    "resolvedDate",
                    "lastOccurrence",
                    "note",
                    "reactionsJson",
                    "createdAt",
                    "updatedAt",
                ),
            create =
                """
                CREATE TABLE IF NOT EXISTS `allergy_cache_v9` (
                    `connectionId` TEXT NOT NULL,
                    `id` TEXT NOT NULL,
                    `clinicalStatus` TEXT,
                    `verificationStatus` TEXT,
                    `category` TEXT,
                    `criticality` TEXT,
                    `codeJson` TEXT,
                    `onsetDate` TEXT,
                    `resolvedDate` TEXT,
                    `lastOccurrence` TEXT,
                    `note` TEXT,
                    `reactionsJson` TEXT,
                    `createdAt` TEXT,
                    `updatedAt` TEXT,
                    PRIMARY KEY(`connectionId`, `id`)
                )
                """.trimIndent(),
            indices =
                listOf(
                    "CREATE INDEX IF NOT EXISTS `index_allergy_cache_connectionId_clinicalStatus` " +
                        " ON `allergy_cache` (`connectionId`, `clinicalStatus`)",
                ),
            conn = conn,
        )
        scopeTable(
            db,
            table = "vaccine_cache",
            connectionColumn = "`connectionId`",
            columns =
                listOf(
                    "id",
                    "status",
                    "vaccineCodeJson",
                    "administeredAt",
                    "doseNumber",
                    "lotNumber",
                    "manufacturer",
                    "location",
                    "note",
                    "examinationId",
                    "createdAt",
                    "updatedAt",
                ),
            create =
                """
                CREATE TABLE IF NOT EXISTS `vaccine_cache_v9` (
                    `connectionId` TEXT NOT NULL,
                    `id` TEXT NOT NULL,
                    `status` TEXT,
                    `vaccineCodeJson` TEXT,
                    `administeredAt` TEXT,
                    `doseNumber` TEXT,
                    `lotNumber` TEXT,
                    `manufacturer` TEXT,
                    `location` TEXT,
                    `note` TEXT,
                    `examinationId` TEXT,
                    `createdAt` TEXT,
                    `updatedAt` TEXT,
                    PRIMARY KEY(`connectionId`, `id`)
                )
                """.trimIndent(),
            indices =
                listOf(
                    "CREATE INDEX IF NOT EXISTS `index_vaccine_cache_connectionId_administeredAt` " +
                        " ON `vaccine_cache` (`connectionId`, `administeredAt`)",
                ),
            conn = conn,
        )
        scopeTable(
            db,
            table = "clinical_event_cache",
            connectionColumn = "`connectionId`",
            columns =
                listOf(
                    "id",
                    "patientId",
                    "typeId",
                    "typeName",
                    "typeSlug",
                    "typeIcon",
                    "typeColor",
                    "status",
                    "title",
                    "description",
                    "onsetDate",
                    "resolvedDate",
                    "codingSystem",
                    "code",
                    "createdAt",
                    "updatedAt",
                ),
            create =
                """
                CREATE TABLE IF NOT EXISTS `clinical_event_cache_v9` (
                    `connectionId` TEXT NOT NULL,
                    `id` TEXT NOT NULL,
                    `patientId` TEXT,
                    `typeId` TEXT,
                    `typeName` TEXT,
                    `typeSlug` TEXT,
                    `typeIcon` TEXT,
                    `typeColor` TEXT,
                    `status` TEXT,
                    `title` TEXT,
                    `description` TEXT,
                    `onsetDate` TEXT,
                    `resolvedDate` TEXT,
                    `codingSystem` TEXT,
                    `code` TEXT,
                    `createdAt` TEXT,
                    `updatedAt` TEXT,
                    PRIMARY KEY(`connectionId`, `id`)
                )
                """.trimIndent(),
            indices =
                listOf(
                    "CREATE INDEX IF NOT EXISTS `index_clinical_event_cache_connectionId_onsetDate` " +
                        " ON `clinical_event_cache` (`connectionId`, `onsetDate`)",
                ),
            conn = conn,
        )
        scopeTable(
            db,
            table = "notification_cache",
            connectionColumn = "`connectionId`",
            columns =
                listOf(
                    "recipientId",
                    "status",
                    "readAt",
                    "dismissedAt",
                    "notificationId",
                    "title",
                    "body",
                    "type",
                    "category",
                    "severity",
                    "source",
                    "payloadJson",
                    "patientId",
                    "createdAt",
                ),
            create =
                """
                CREATE TABLE IF NOT EXISTS `notification_cache_v9` (
                    `connectionId` TEXT NOT NULL,
                    `recipientId` TEXT NOT NULL,
                    `status` TEXT,
                    `readAt` TEXT,
                    `dismissedAt` TEXT,
                    `notificationId` TEXT,
                    `title` TEXT,
                    `body` TEXT,
                    `type` TEXT,
                    `category` TEXT,
                    `severity` TEXT,
                    `source` TEXT,
                    `payloadJson` TEXT,
                    `patientId` TEXT,
                    `createdAt` TEXT,
                    PRIMARY KEY(`connectionId`, `recipientId`)
                )
                """.trimIndent(),
            indices =
                listOf(
                    "CREATE INDEX IF NOT EXISTS `index_notification_cache_connectionId_readAt` " +
                        " ON `notification_cache` (`connectionId`, `readAt`)",
                ),
            conn = conn,
        )
    }

    /** The SQLite table-rebuild dance (SQLite cannot ALTER a primary key):
     *  create the scoped twin, copy every old row across with the backfilled
     *  connection id, drop the old table, rename, recreate the indices. */
    private fun scopeTable(
        db: SupportSQLiteDatabase,
        table: String,
        connectionColumn: String,
        columns: List<String>,
        create: String,
        indices: List<String>,
        conn: String,
    ) {
        val cols = columns.joinToString(", ") { column -> "`$column`" }
        db.execSQL(create)
        db.execSQL("INSERT INTO `${table}_v9` ($connectionColumn, $cols) SELECT '$conn', $cols FROM `$table`")
        db.execSQL("DROP TABLE `$table`")
        db.execSQL("ALTER TABLE `${table}_v9` RENAME TO `$table`")
        indices.forEach(db::execSQL)
    }

    /**
     * Every migration for the Room builder. The v8 → v9 rebuild backfills
     * `connection_id` from the ACTIVE connection's id (pre-M9 installs only
     * ever cached the active connection — see `CredentialStore.load`).
     */
    fun all(activeConnectionId: String): Array<Migration> =
        arrayOf(
            *ALL,
            migration8to9(activeConnectionId),
        )

    val ALL: Array<Migration> =
        arrayOf(
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
        )
}
