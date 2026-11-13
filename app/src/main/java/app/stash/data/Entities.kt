package app.stash.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import app.stash.domain.RecordKind

/*
 * Database version 2 (docs/cards/01-data-model-migration.md). IDs are opaque strings; times are UTC epoch
 * milliseconds; enums are stored by name; lists are JSON text. Version 1 (the prototype's single `note`
 * table) is frozen in app/schemas/.../1.json and is carried over by Migrations.MIGRATION_1_2. Version 3 adds
 * `attachment` (docs/cards/16a-attachment-store.md, Migrations.MIGRATION_2_3). Version 4 adds `transcription` and
 * `capture.transcribe_state`, `capture.transcribe_attempts` (docs/cards/26c-recording-store.md, MIGRATION_3_4).
 * Version 5 adds `answer_trace` (docs/cards/08-ask.md E2, MIGRATION_4_5).
 */

/** What he said. Never rewritten except by his own correction (which writes a revision). */
@Entity(
    tableName = "capture",
    indices = [Index("created_at"), Index("extract_state")],
)
data class CaptureEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") override val updatedAt: Long,
    val zone: String? = null,
    @ColumnInfo(name = "raw_text") val rawText: String,
    /** After the personal-vocabulary correction and any edits by the owner. */
    val text: String,
    val source: CaptureSource = CaptureSource.VOICE,
    @ColumnInfo(name = "captured_locked") val capturedLocked: Boolean? = null,
    val sensitive: Boolean = false,
    @ColumnInfo(name = "sensitive_reason") val sensitiveReason: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    @ColumnInfo(name = "accuracy_m") val accuracyM: Double? = null,
    @ColumnInfo(name = "extract_state") val extractState: ExtractState = ExtractState.PENDING,
    @ColumnInfo(name = "extract_attempts") val extractAttempts: Int = 0,
    @ColumnInfo(name = "app_version") val appVersion: String? = null,
    @ColumnInfo(name = "photo_hint") val photoHint: PhotoHint = PhotoHint.NONE,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    /** Card 26c (version 4): null unless the note has a recording; see [TranscribeState]. */
    @ColumnInfo(name = "transcribe_state") val transcribeState: TranscribeState? = null,
    @ColumnInfo(name = "transcribe_attempts", defaultValue = "0") val transcribeAttempts: Int = 0,
) : Stamped<CaptureEntity> {
    override fun withUpdatedAt(t: Long) = copy(updatedAt = t)
}

/** One run of an extractor over a capture, with its raw output kept. Holds a copy of the words: purged with the capture. */
@Entity(tableName = "extraction", indices = [Index("capture_id")])
data class ExtractionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "capture_id") val captureId: String,
    val at: Long,
    val extractor: String,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,
    @ColumnInfo(name = "text_hash") val textHash: String,
    @ColumnInfo(name = "output_json") val outputJson: String,
    val status: ExtractionStatus,
    val error: String? = null,
    @ColumnInfo(name = "tokens_in") val tokensIn: Int? = null,
    @ColumnInfo(name = "tokens_out") val tokensOut: Int? = null,
)

/** One thing the app understood from a capture. Disposable and re-derivable unless [pinned]. */
@Entity(
    tableName = "record",
    indices = [Index("capture_id"), Index("kind"), Index("subject_id")],
)
data class RecordEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "capture_id") val captureId: String,
    @ColumnInfo(name = "extraction_id") val extractionId: String,
    val seq: Int,
    val kind: RecordKind,
    val title: String,
    val quote: String,
    @ColumnInfo(name = "subject_id") val subjectId: String? = null,
    @ColumnInfo(name = "place_subject_id") val placeSubjectId: String? = null,
    @ColumnInfo(name = "place_text") val placeText: String? = null,
    @ColumnInfo(name = "spot_text") val spotText: String? = null,
    @ColumnInfo(name = "happened_on") val happenedOn: String? = null,
    @ColumnInfo(name = "happened_text") val happenedText: String? = null,
    val confidence: Confidence = Confidence.MEDIUM,
    val attention: String? = null,
    val pinned: Boolean = false,
    @ColumnInfo(name = "supersedes_record_id") val supersedesRecordId: String? = null,
    @ColumnInfo(name = "claims_to_close_record_id") val claimsToCloseRecordId: String? = null,
    /** JSON list. */
    val aliases: String = "[]",
    /** JSON list. */
    val tags: String = "[]",
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") override val updatedAt: Long,
) : Stamped<RecordEntity> {
    override fun withUpdatedAt(t: Long) = copy(updatedAt = t)
}

@Entity(tableName = "task", indices = [Index("state")])
data class TaskEntity(
    @PrimaryKey @ColumnInfo(name = "record_id") val recordId: String,
    val state: TaskState = TaskState.OPEN,
    @ColumnInfo(name = "due_kind") val dueKind: DueKind = DueKind.NONE,
    @ColumnInfo(name = "due_from") val dueFrom: String? = null,
    @ColumnInfo(name = "due_to") val dueTo: String? = null,
    @ColumnInfo(name = "due_text") val dueText: String? = null,
    @ColumnInfo(name = "at_place_subject_id") val atPlaceSubjectId: String? = null,
    @ColumnInfo(name = "at_place_text") val atPlaceText: String? = null,
    val perishable: Boolean = false,
    @ColumnInfo(name = "next_nudge_on") val nextNudgeOn: String? = null,
    @ColumnInfo(name = "nudge_count") val nudgeCount: Int = 0,
    @ColumnInfo(name = "ignored_in_a_row") val ignoredInARow: Int = 0,
    val quiet: Boolean = false,
    @ColumnInfo(name = "snoozed_until") val snoozedUntil: String? = null,
    @ColumnInfo(name = "closed_at") val closedAt: Long? = null,
    @ColumnInfo(name = "closed_how") val closedHow: ClosedHow? = null,
    @ColumnInfo(name = "closed_by_record_id") val closedByRecordId: String? = null,
    @ColumnInfo(name = "last_active_at") val lastActiveAt: Long,
    @ColumnInfo(name = "expires_on") val expiresOn: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") override val updatedAt: Long,
) : Stamped<TaskEntity> {
    override fun withUpdatedAt(t: Long) = copy(updatedAt = t)
}

@Entity(tableName = "reading", indices = [Index(value = ["metric_id", "measured_at"]), Index("record_id")])
data class ReadingEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "record_id") val recordId: String,
    @ColumnInfo(name = "subject_id") val subjectId: String? = null,
    @ColumnInfo(name = "metric_id") val metricId: String,
    @ColumnInfo(name = "value_text") val valueText: String,
    @ColumnInfo(name = "value_num") val valueNum: Double? = null,
    val unit: String? = null,
    @ColumnInfo(name = "measured_at") val measuredAt: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") override val updatedAt: Long,
) : Stamped<ReadingEntity> {
    override fun withUpdatedAt(t: Long) = copy(updatedAt = t)
}

@Entity(tableName = "metric")
data class MetricEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "subject_id") val subjectId: String? = null,
    val key: String,
    val label: String,
    val aliases: String = "[]",
    val unit: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") override val updatedAt: Long,
) : Stamped<MetricEntity> {
    override fun withUpdatedAt(t: Long) = copy(updatedAt = t)
}

@Entity(tableName = "whereabouts", indices = [Index("item_subject_id")])
data class WhereaboutsEntity(
    @PrimaryKey @ColumnInfo(name = "record_id") val recordId: String,
    @ColumnInfo(name = "item_subject_id") val itemSubjectId: String? = null,
    @ColumnInfo(name = "item_text") val itemText: String,
    @ColumnInfo(name = "where_text") val whereText: String? = null,
    @ColumnInfo(name = "person_subject_id") val personSubjectId: String? = null,
    @ColumnInfo(name = "person_text") val personText: String? = null,
    val direction: Direction,
    @ColumnInfo(name = "copy_label") val copyLabel: String? = null,
    @ColumnInfo(name = "said_date_text") val saidDateText: String? = null,
    @ColumnInfo(name = "due_back_on") val dueBackOn: String? = null,
    @ColumnInfo(name = "returned_at") val returnedAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") override val updatedAt: Long,
) : Stamped<WhereaboutsEntity> {
    override fun withUpdatedAt(t: Long) = copy(updatedAt = t)
}

@Entity(tableName = "subject", indices = [Index("norm_name")])
data class SubjectEntity(
    @PrimaryKey val id: String,
    val type: SubjectType,
    val name: String,
    @ColumnInfo(name = "norm_name") val normName: String,
    val aliases: String = "[]",
    val volatility: Volatility? = null,
    val sensitive: Boolean = false,
    @ColumnInfo(name = "merged_into_id") val mergedIntoId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") override val updatedAt: Long,
) : Stamped<SubjectEntity> {
    override fun withUpdatedAt(t: Long) = copy(updatedAt = t)
}

@Entity(tableName = "place_location", indices = [Index("subject_id")])
data class PlaceLocationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "subject_id") val subjectId: String,
    val lat: Double,
    val lon: Double,
    @ColumnInfo(name = "radius_m") val radiusM: Double,
    val source: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") override val updatedAt: Long,
) : Stamped<PlaceLocationEntity> {
    override fun withUpdatedAt(t: Long) = copy(updatedAt = t)
}

@Entity(tableName = "nudge_log")
data class NudgeLogEntity(
    @PrimaryKey val id: String,
    val at: Long,
    val type: NudgeType,
    /** JSON list of record ids. */
    @ColumnInfo(name = "record_ids") val recordIds: String,
    val outcome: NudgeOutcome,
)

/** One thing the owner did. Append-only; with captures, everything that cannot be rebuilt. */
@Entity(tableName = "revision", indices = [Index("capture_id"), Index("record_id")])
data class RevisionEntity(
    @PrimaryKey val id: String,
    val at: Long,
    @ColumnInfo(name = "capture_id") val captureId: String? = null,
    @ColumnInfo(name = "record_id") val recordId: String? = null,
    @ColumnInfo(name = "subject_id") val subjectId: String? = null,
    val what: RevisionKind,
    @ColumnInfo(name = "old_value") val oldValue: String,
    @ColumnInfo(name = "new_value") val newValue: String,
)

/** A content-free marker that a row was really deleted, so a restore from an older backup cannot bring it back. */
@Entity(tableName = "tombstone")
data class TombstoneEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "table_name") val tableName: String,
    @ColumnInfo(name = "row_id") val rowId: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long,
)

@Entity(tableName = "miss_log")
data class MissLogEntity(
    @PrimaryKey val id: String,
    val at: Long,
    val question: String,
    val reason: MissReason,
)

/** The event log. No text column, by design (card 13 adds `Dbg.event`, which writes here). */
@Entity(tableName = "app_event")
data class AppEventEntity(
    @PrimaryKey val id: String,
    val at: Long,
    val component: String,
    val code: String,
    val n1: Long? = null,
    val n2: Long? = null,
    @ColumnInfo(name = "ref_id") val refId: String? = null,
    val ms: Long? = null,
)

/**
 * One file that belongs to exactly one capture (docs/cards/16a, version 3). The file lives in `files/att/<file_name>`,
 * encrypted under [fileKey], which is kept here and read only inside [AttachmentRepository]: screens get
 * [AttachmentInfo]. No `deleted_at`: hidden with its capture, really deleted by [AttachmentRepository.remove] or
 * [CaptureRepository.purge]. Holds a [ByteArray], so `==` between two entities compares the key by reference.
 */
@Entity(tableName = "attachment", indices = [Index("capture_id")])
data class AttachmentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "capture_id") val captureId: String,
    val kind: AttachmentKind,
    /** Order within the capture: 0, 1, 2 … as added; never renumbered. The lowest picture is the note's cover. */
    val position: Int,
    val mime: String,
    /** Random, no extension. */
    @ColumnInfo(name = "file_name") val fileName: String,
    /** 32 random bytes. Never logged, exported or dumped. */
    @ColumnInfo(name = "file_key", typeAffinity = ColumnInfo.BLOB) val fileKey: ByteArray,
    @ColumnInfo(name = "format_version") val formatVersion: Int,
    /** Size of the plain bytes. */
    @ColumnInfo(name = "byte_size") val byteSize: Long,
    /** SHA-256 of the plain bytes, lower-case hex. */
    val sha256: String,
    val width: Int? = null,
    val height: Int? = null,
    /** Null for pictures; for audio later (card 26). */
    @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,
    /** Both null means "not read yet" (card 16f). */
    @ColumnInfo(name = "ocr_text") val ocrText: String? = null,
    @ColumnInfo(name = "ocr_engine") val ocrEngine: String? = null,
    val source: AttachmentSource,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") override val updatedAt: Long,
) : Stamped<AttachmentEntity> {
    override fun withUpdatedAt(t: Long) = copy(updatedAt = t)
}

/**
 * One writing-out of a recording (docs/cards/26c, version 4). [text] is the engine's words, untouched (empty when it
 * heard none). Rows are never edited except their [status], like `extraction`, and are deleted only when the note is
 * purged: the capture row carries the note's `updated_at`. [attachmentId] is a soft reference, kept as a plain value
 * after the recording is removed (no foreign key, by design). [error] is a reason code, never words.
 */
@Entity(tableName = "transcription", indices = [Index("capture_id")])
data class TranscriptionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "capture_id") val captureId: String,
    @ColumnInfo(name = "attachment_id") val attachmentId: String? = null,
    val at: Long,
    val engine: String,
    val text: String,
    val status: TranscriptionStatus,
    val error: String? = null,
    @ColumnInfo(name = "audio_ms") val audioMs: Long? = null,
    @ColumnInfo(name = "proc_ms") val procMs: Long? = null,
    val chunks: Int? = null,
    /** Bit flags, filled by card 26i; 0 until then. */
    @ColumnInfo(defaultValue = "0") val doubt: Int = 0,
    /** True for "write it out again". */
    @ColumnInfo(name = "asked_by_owner", defaultValue = "0") val askedByOwner: Boolean = false,
)

/**
 * The steps behind one answer (docs/cards/08 E2, version 5). Written once per question by `AskEngine`, whatever the
 * path. [stepsJson] holds each lookup with its arguments and the ids and count of what it returned; [sourceIds] is a
 * JSON list of record and capture ids, never their words. [question] and [answerText] (and the lookups' arguments)
 * are blanked when a capture among the sources is purged. Stays on the phone.
 */
@Entity(tableName = "answer_trace", indices = [Index("at")])
data class AnswerTraceEntity(
    @PrimaryKey val id: String,
    val at: Long,
    val question: String,
    val route: TraceRoute,
    /** Which router pattern, by its code name (`where_is`, `open_tasks`, ...), or null. */
    @ColumnInfo(name = "route_name") val routeName: String? = null,
    @ColumnInfo(name = "steps_json") val stepsJson: String,
    @ColumnInfo(name = "source_ids") val sourceIds: String,
    @ColumnInfo(name = "answer_text") val answerText: String,
    @ColumnInfo(name = "written_by") val writtenBy: WrittenBy,
    val outcome: TraceOutcome,
    /** A code, never words. */
    @ColumnInfo(name = "error_code") val errorCode: String? = null,
    @ColumnInfo(name = "ms_total") val msTotal: Long,
    @ColumnInfo(name = "marked_wrong") val markedWrong: Boolean = false,
    @ColumnInfo(name = "app_version") val appVersion: String,
    @ColumnInfo(name = "router_version") val routerVersion: String,
    /** The loop's prompt version (card 08 pass 2); null for every answer made without the model. */
    @ColumnInfo(name = "prompt_version") val promptVersion: String? = null,
    @ColumnInfo(name = "updated_at") override val updatedAt: Long,
) : Stamped<AnswerTraceEntity> {
    override fun withUpdatedAt(t: Long) = copy(updatedAt = t)
}

@Entity(tableName = "vocab_word")
data class WordEntity(@PrimaryKey val word: String)

@Entity(tableName = "correction")
data class CorrectionEntity(@PrimaryKey val heard: String, val meant: String)

/**
 * Full-text index: one document per live record and per live capture. Written by [SearchDao] through the
 * repositories, not by triggers. `doc_id` is a normal column; `rowid` is only the index's own row number.
 */
@Fts4(tokenizer = FtsOptions.TOKENIZER_PORTER)
@Entity(tableName = "search_fts")
data class SearchDocEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Int,
    @ColumnInfo(name = "doc_id") val docId: String,
    @ColumnInfo(name = "doc_type") val docType: String,
    val body: String,
)

/* Read models (not tables) */

data class RecordWithParts(
    @Embedded val record: RecordEntity,
    @Relation(parentColumn = "id", entityColumn = "record_id") val whereabouts: WhereaboutsEntity?,
    @Relation(parentColumn = "id", entityColumn = "record_id") val task: TaskEntity?,
    @Relation(parentColumn = "extraction_id", entityColumn = "id") val extraction: ExtractionEntity?,
)

data class CaptureWithRecords(
    @Embedded val capture: CaptureEntity,
    @Relation(parentColumn = "id", entityColumn = "capture_id", entity = RecordEntity::class)
    val records: List<RecordWithParts>,
)
