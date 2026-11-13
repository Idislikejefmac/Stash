package app.stash.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 1 (the prototype's `note` table) to version 2 (two layers). Hand-written SQL; Room runs it inside one
 * transaction, so a failure leaves version 1 untouched. The rules are docs/cards/01-data-model-migration.md
 * "Mapping from the old table". The DDL below is copied from the exported schema app/schemas/.../2.json.
 *
 * Nothing in this file is allowed to lose a note: every old column is either carried over or (placeName,
 * expiresAt, which the prototype never wrote) documented as dropped, and the old structured fields are also kept
 * whole in `extraction.output_json`. `note` is dropped last.
 */
object Migrations {
    /** Statements in order. Public so the JVM test can run exactly what the phone runs. */
    val V1_TO_V2: List<String> = listOf(
        // --- new tables (vocab_word and correction already exist and are untouched) ---
        "CREATE TABLE IF NOT EXISTS `capture` (`id` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `zone` TEXT, `raw_text` TEXT NOT NULL, `text` TEXT NOT NULL, `source` TEXT NOT NULL, `captured_locked` INTEGER, `sensitive` INTEGER NOT NULL, `sensitive_reason` TEXT, `lat` REAL, `lon` REAL, `accuracy_m` REAL, `extract_state` TEXT NOT NULL, `extract_attempts` INTEGER NOT NULL, `app_version` TEXT, `photo_hint` TEXT NOT NULL, `deleted_at` INTEGER, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_capture_created_at` ON `capture` (`created_at`)",
        "CREATE INDEX IF NOT EXISTS `index_capture_extract_state` ON `capture` (`extract_state`)",
        "CREATE TABLE IF NOT EXISTS `extraction` (`id` TEXT NOT NULL, `capture_id` TEXT NOT NULL, `at` INTEGER NOT NULL, `extractor` TEXT NOT NULL, `schema_version` INTEGER NOT NULL, `text_hash` TEXT NOT NULL, `output_json` TEXT NOT NULL, `status` TEXT NOT NULL, `error` TEXT, `tokens_in` INTEGER, `tokens_out` INTEGER, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_extraction_capture_id` ON `extraction` (`capture_id`)",
        "CREATE TABLE IF NOT EXISTS `record` (`id` TEXT NOT NULL, `capture_id` TEXT NOT NULL, `extraction_id` TEXT NOT NULL, `seq` INTEGER NOT NULL, `kind` TEXT NOT NULL, `title` TEXT NOT NULL, `quote` TEXT NOT NULL, `subject_id` TEXT, `place_subject_id` TEXT, `place_text` TEXT, `spot_text` TEXT, `happened_on` TEXT, `happened_text` TEXT, `confidence` TEXT NOT NULL, `attention` TEXT, `pinned` INTEGER NOT NULL, `supersedes_record_id` TEXT, `claims_to_close_record_id` TEXT, `aliases` TEXT NOT NULL, `tags` TEXT NOT NULL, `deleted_at` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_record_capture_id` ON `record` (`capture_id`)",
        "CREATE INDEX IF NOT EXISTS `index_record_kind` ON `record` (`kind`)",
        "CREATE INDEX IF NOT EXISTS `index_record_subject_id` ON `record` (`subject_id`)",
        "CREATE TABLE IF NOT EXISTS `task` (`record_id` TEXT NOT NULL, `state` TEXT NOT NULL, `due_kind` TEXT NOT NULL, `due_from` TEXT, `due_to` TEXT, `due_text` TEXT, `at_place_subject_id` TEXT, `at_place_text` TEXT, `perishable` INTEGER NOT NULL, `next_nudge_on` TEXT, `nudge_count` INTEGER NOT NULL, `ignored_in_a_row` INTEGER NOT NULL, `quiet` INTEGER NOT NULL, `snoozed_until` TEXT, `closed_at` INTEGER, `closed_how` TEXT, `closed_by_record_id` TEXT, `last_active_at` INTEGER NOT NULL, `expires_on` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`record_id`))",
        "CREATE INDEX IF NOT EXISTS `index_task_state` ON `task` (`state`)",
        "CREATE TABLE IF NOT EXISTS `reading` (`id` TEXT NOT NULL, `record_id` TEXT NOT NULL, `subject_id` TEXT, `metric_id` TEXT NOT NULL, `value_text` TEXT NOT NULL, `value_num` REAL, `unit` TEXT, `measured_at` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_reading_metric_id_measured_at` ON `reading` (`metric_id`, `measured_at`)",
        "CREATE INDEX IF NOT EXISTS `index_reading_record_id` ON `reading` (`record_id`)",
        "CREATE TABLE IF NOT EXISTS `metric` (`id` TEXT NOT NULL, `subject_id` TEXT, `key` TEXT NOT NULL, `label` TEXT NOT NULL, `aliases` TEXT NOT NULL, `unit` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `whereabouts` (`record_id` TEXT NOT NULL, `item_subject_id` TEXT, `item_text` TEXT NOT NULL, `where_text` TEXT, `person_subject_id` TEXT, `person_text` TEXT, `direction` TEXT NOT NULL, `copy_label` TEXT, `said_date_text` TEXT, `due_back_on` TEXT, `returned_at` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`record_id`))",
        "CREATE INDEX IF NOT EXISTS `index_whereabouts_item_subject_id` ON `whereabouts` (`item_subject_id`)",
        "CREATE TABLE IF NOT EXISTS `subject` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `name` TEXT NOT NULL, `norm_name` TEXT NOT NULL, `aliases` TEXT NOT NULL, `volatility` TEXT, `sensitive` INTEGER NOT NULL, `merged_into_id` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_subject_norm_name` ON `subject` (`norm_name`)",
        "CREATE TABLE IF NOT EXISTS `place_location` (`id` TEXT NOT NULL, `subject_id` TEXT NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `radius_m` REAL NOT NULL, `source` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_place_location_subject_id` ON `place_location` (`subject_id`)",
        "CREATE TABLE IF NOT EXISTS `nudge_log` (`id` TEXT NOT NULL, `at` INTEGER NOT NULL, `type` TEXT NOT NULL, `record_ids` TEXT NOT NULL, `outcome` TEXT NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `revision` (`id` TEXT NOT NULL, `at` INTEGER NOT NULL, `capture_id` TEXT, `record_id` TEXT, `subject_id` TEXT, `what` TEXT NOT NULL, `old_value` TEXT NOT NULL, `new_value` TEXT NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_revision_capture_id` ON `revision` (`capture_id`)",
        "CREATE INDEX IF NOT EXISTS `index_revision_record_id` ON `revision` (`record_id`)",
        "CREATE TABLE IF NOT EXISTS `tombstone` (`id` TEXT NOT NULL, `table_name` TEXT NOT NULL, `row_id` TEXT NOT NULL, `deleted_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `miss_log` (`id` TEXT NOT NULL, `at` INTEGER NOT NULL, `question` TEXT NOT NULL, `reason` TEXT NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `app_event` (`id` TEXT NOT NULL, `at` INTEGER NOT NULL, `component` TEXT NOT NULL, `code` TEXT NOT NULL, `n1` INTEGER, `n2` INTEGER, `ref_id` TEXT, `ms` INTEGER, PRIMARY KEY(`id`))",
        "CREATE VIRTUAL TABLE IF NOT EXISTS `search_fts` USING FTS4(`doc_id` TEXT NOT NULL, `doc_type` TEXT NOT NULL, `body` TEXT NOT NULL, tokenize=porter)",
        // --- one capture per old note ---
        """
        INSERT INTO capture (id, created_at, updated_at, zone, raw_text, text, source, captured_locked, sensitive,
            sensitive_reason, lat, lon, accuracy_m, extract_state, extract_attempts, app_version, photo_hint, deleted_at)
        SELECT id, createdAt, createdAt, NULL, rawText, text, 'VOICE', NULL, localOnly,
            NULL, lat, lon, NULL, 'PENDING', 0, NULL, 'NONE', NULL
        FROM note
        """.trimIndent(),
        // --- the old structured fields, kept whole as the first extraction ---
        """
        INSERT INTO extraction (id, capture_id, at, extractor, schema_version, text_hash, output_json, status,
            error, tokens_in, tokens_out)
        SELECT id || '-x0', id, createdAt,
            CASE structureSource WHEN 'MODEL' THEN 'prototype-model' ELSE 'prototype-rules' END,
            0, '',
            json_object('kind', kind, 'item', item, 'place', place, 'person', person, 'eventDate', eventDate),
            'CURRENT', NULL, NULL, NULL
        FROM note
        """.trimIndent(),
        // --- one record per old note ---
        """
        INSERT INTO record (id, capture_id, extraction_id, seq, kind, title, quote, subject_id, place_subject_id,
            place_text, spot_text, happened_on, happened_text, confidence, attention, pinned, supersedes_record_id,
            claims_to_close_record_id, aliases, tags, deleted_at, created_at, updated_at)
        SELECT id || '-r0', id, id || '-x0', 0,
            CASE kind WHEN 'STORED_ITEM' THEN 'PLACED' WHEN 'LOAN' THEN 'LOAN' WHEN 'REMINDER' THEN 'TASK' ELSE 'OTHER' END,
            substr(text, 1, 80), text, NULL, NULL,
            NULL, NULL, NULL, NULL, 'MEDIUM', NULL, 0,
            CASE WHEN supersedesId IS NOT NULL AND supersedesId IN (SELECT id FROM note) THEN supersedesId || '-r0' ELSE NULL END,
            NULL, '[]', '[]', NULL, createdAt, createdAt
        FROM note
        """.trimIndent(),
        // --- side rows. item_text must not be NULL, so COALESCE(item, text). The place, person and date are carried
        //     for both stored items and loans (the card names a subset; carrying all loses nothing). ---
        """
        INSERT INTO whereabouts (record_id, item_subject_id, item_text, where_text, person_subject_id, person_text,
            direction, copy_label, said_date_text, due_back_on, returned_at, created_at, updated_at)
        SELECT id || '-r0', NULL, COALESCE(item, text), place, NULL, person,
            CASE kind WHEN 'LOAN' THEN 'LENT' ELSE 'PLACED' END, NULL, eventDate, NULL, NULL, createdAt, createdAt
        FROM note WHERE kind IN ('STORED_ITEM', 'LOAN')
        """.trimIndent(),
        """
        INSERT INTO task (record_id, state, due_kind, due_from, due_to, due_text, at_place_subject_id, at_place_text,
            perishable, next_nudge_on, nudge_count, ignored_in_a_row, quiet, snoozed_until, closed_at, closed_how,
            closed_by_record_id, last_active_at, expires_on, created_at, updated_at)
        SELECT id || '-r0', 'OPEN', 'NONE', NULL, NULL, NULL, NULL, place,
            0, NULL, 0, 0, 0, NULL, NULL, NULL,
            NULL, createdAt, NULL, createdAt, createdAt
        FROM note WHERE kind = 'REMINDER'
        """.trimIndent(),
        // --- search documents: one per capture and one per record (the repository keeps them current afterwards) ---
        "INSERT INTO search_fts (doc_id, doc_type, body) SELECT id, 'CAPTURE', text FROM note",
        "INSERT INTO search_fts (doc_id, doc_type, body) SELECT id || '-r0', 'RECORD', substr(text, 1, 80) || ' ' || text FROM note",
        // --- the old table goes last ---
        "DROP TABLE note",
    )

    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            V1_TO_V2.forEach { db.execSQL(it) }
        }
    }

    /**
     * Version 2 to 3 (docs/cards/16a): the `attachment` table and its index, nothing else. No existing table changes,
     * so no note is touched. The DDL is copied from the exported schema app/schemas/.../3.json.
     */
    val V2_TO_V3: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `attachment` (`id` TEXT NOT NULL, `capture_id` TEXT NOT NULL, `kind` TEXT NOT NULL, `position` INTEGER NOT NULL, `mime` TEXT NOT NULL, `file_name` TEXT NOT NULL, `file_key` BLOB NOT NULL, `format_version` INTEGER NOT NULL, `byte_size` INTEGER NOT NULL, `sha256` TEXT NOT NULL, `width` INTEGER, `height` INTEGER, `duration_ms` INTEGER, `ocr_text` TEXT, `ocr_engine` TEXT, `source` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_attachment_capture_id` ON `attachment` (`capture_id`)",
    )

    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            V2_TO_V3.forEach { db.execSQL(it) }
        }
    }

    /**
     * Version 3 to 4 (docs/cards/26c): two columns on `capture` and the `transcription` table with its index, nothing
     * else. `ALTER TABLE ... ADD COLUMN` changes no existing row: every note gets `transcribe_state` null (it has no
     * recording) and `transcribe_attempts` 0. The column definitions and the DDL are copied from the exported schema
     * app/schemas/.../4.json (a test checks they are identical).
     */
    val V3_TO_V4: List<String> = listOf(
        "ALTER TABLE `capture` ADD COLUMN `transcribe_state` TEXT",
        "ALTER TABLE `capture` ADD COLUMN `transcribe_attempts` INTEGER NOT NULL DEFAULT 0",
        "CREATE TABLE IF NOT EXISTS `transcription` (`id` TEXT NOT NULL, `capture_id` TEXT NOT NULL, `attachment_id` TEXT, `at` INTEGER NOT NULL, `engine` TEXT NOT NULL, `text` TEXT NOT NULL, `status` TEXT NOT NULL, `error` TEXT, `audio_ms` INTEGER, `proc_ms` INTEGER, `chunks` INTEGER, `doubt` INTEGER NOT NULL DEFAULT 0, `asked_by_owner` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_transcription_capture_id` ON `transcription` (`capture_id`)",
    )

    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            V3_TO_V4.forEach { db.execSQL(it) }
        }
    }

    /**
     * Version 4 to 5 (docs/cards/08 E2): the `answer_trace` table and its index, nothing else. No existing table
     * changes, so no note is touched. The DDL is copied from the exported schema app/schemas/.../5.json (a test checks
     * they are identical).
     */
    val V4_TO_V5: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `answer_trace` (`id` TEXT NOT NULL, `at` INTEGER NOT NULL, `question` TEXT NOT NULL, `route` TEXT NOT NULL, `route_name` TEXT, `steps_json` TEXT NOT NULL, `source_ids` TEXT NOT NULL, `answer_text` TEXT NOT NULL, `written_by` TEXT NOT NULL, `outcome` TEXT NOT NULL, `error_code` TEXT, `ms_total` INTEGER NOT NULL, `marked_wrong` INTEGER NOT NULL, `app_version` TEXT NOT NULL, `router_version` TEXT NOT NULL, `prompt_version` TEXT, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_answer_trace_at` ON `answer_trace` (`at`)",
    )

    val MIGRATION_4_5: Migration = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            V4_TO_V5.forEach { db.execSQL(it) }
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
}
