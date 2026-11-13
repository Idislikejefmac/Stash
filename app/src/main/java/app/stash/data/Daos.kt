package app.stash.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/*
 * DAOs. Only repositories and Container.store() touch these (docs/STORAGE.md rule 1): screens never do.
 */

data class CaptureRef(
    val id: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    /** Card 26c: the recording's state, by name, or null for a note with no recording. */
    @ColumnInfo(name = "transcribe_state") val transcribeState: String? = null,
    /** Card 26c: the note has no words (`text` is blank). Never the words themselves. */
    val blank: Boolean = false,
)

/** What the consistency check needs of a writing-out (card 26c). No words. */
data class TranscriptionRef(
    val id: String,
    @ColumnInfo(name = "capture_id") val captureId: String,
    val status: String,
)

/**
 * The only view of a recording that screens ever get (card 26c item 5): no key, no file name.
 */
data class RecordingInfo(
    @ColumnInfo(name = "id") val attachmentId: String,
    @ColumnInfo(name = "capture_id") val captureId: String,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    @ColumnInfo(name = "byte_size") val byteSize: Long,
    val mime: String,
)

/** How many recordings the app holds, what they weigh and how long they last in all (card 26c item 5; shown by 26j). */
data class AudioTotals(
    val count: Int,
    val bytes: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
)

data class RecordRef(
    val id: String,
    @ColumnInfo(name = "capture_id") val captureId: String,
    @ColumnInfo(name = "extraction_id") val extractionId: String,
    val kind: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
)

data class ReadingRef(
    val id: String,
    @ColumnInfo(name = "record_id") val recordId: String,
)

data class DocKey(
    @ColumnInfo(name = "doc_id") val docId: String,
    @ColumnInfo(name = "doc_type") val docType: String,
)

data class RecordLink(
    val id: String,
    @ColumnInfo(name = "supersedes_record_id") val supersedesRecordId: String?,
)

data class ItemRecordRow(
    @ColumnInfo(name = "record_id") val recordId: String,
    @ColumnInfo(name = "capture_id") val captureId: String,
    @ColumnInfo(name = "item_subject_id") val itemSubjectId: String,
    val kind: app.stash.domain.RecordKind,
    val direction: Direction,
    @ColumnInfo(name = "copy_label") val copyLabel: String?,
    @ColumnInfo(name = "returned_at") val returnedAt: Long?,
    @ColumnInfo(name = "captured_at") val capturedAt: Long,
)

/** One reading with its record, capture, subject and place, for the readings queries (card 07). Not a table. */
data class ReadingRow(
    @ColumnInfo(name = "reading_id") val readingId: String,
    @ColumnInfo(name = "record_id") val recordId: String,
    @ColumnInfo(name = "capture_id") val captureId: String,
    @ColumnInfo(name = "subject_id") val subjectId: String?,
    @ColumnInfo(name = "subject_name") val subjectName: String?,
    @ColumnInfo(name = "metric_id") val metricId: String,
    @ColumnInfo(name = "metric_label") val metricLabel: String,
    @ColumnInfo(name = "value_text") val valueText: String,
    @ColumnInfo(name = "value_num") val valueNum: Double?,
    val unit: String?,
    @ColumnInfo(name = "measured_at") val measuredAt: Long,
    @ColumnInfo(name = "place_text") val placeText: String?,
    val attention: String?,
    val quote: String,
)

data class OpenTaskRow(
    @ColumnInfo(name = "record_id") val recordId: String,
    val title: String,
)

/** What the consistency check needs of an attachment. No key. */
data class AttachmentRef(
    val id: String,
    @ColumnInfo(name = "capture_id") val captureId: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    /** [AttachmentKind] by name (card 26c: the check counts recordings). */
    val kind: String = "",
)

/** The only view of an attachment that screens and view models ever get: no key, no file name (docs/cards/16a item 4). */
data class AttachmentInfo(
    val id: String,
    @ColumnInfo(name = "capture_id") val captureId: String,
    val kind: AttachmentKind,
    val position: Int,
    val width: Int?,
    val height: Int?,
    @ColumnInfo(name = "byte_size") val byteSize: Long,
    @ColumnInfo(name = "ocr_text") val ocrText: String?,
)

/** A number per note (card 08: its pictures). */
data class CaptureCount(@ColumnInfo(name = "capture_id") val captureId: String, val count: Int)

/** How many pictures the app holds and what they weigh: Settings, under storage (card 16d item 13). */
data class PictureStats(val count: Int, val bytes: Long)

@Dao
interface CaptureDao {
    @Insert
    suspend fun insert(c: CaptureEntity)

    @Update
    suspend fun update(c: CaptureEntity)

    @Query("SELECT * FROM capture WHERE id = :id")
    suspend fun get(id: String): CaptureEntity?

    @Transaction
    @Query("SELECT * FROM capture WHERE deleted_at IS NULL ORDER BY created_at DESC")
    fun observeLive(): Flow<List<CaptureWithRecords>>

    @Transaction
    @Query("SELECT * FROM capture WHERE deleted_at IS NULL ORDER BY created_at DESC")
    suspend fun live(): List<CaptureWithRecords>

    @Query("SELECT id FROM capture WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff")
    suspend fun deletedBefore(cutoff: Long): List<String>

    /** Captures waiting to be read (or read again), oldest first. */
    @Query("SELECT * FROM capture WHERE deleted_at IS NULL AND extract_state IN ('PENDING', 'RETRY') ORDER BY created_at")
    suspend fun toRead(): List<CaptureEntity>

    @Query("SELECT id, deleted_at, transcribe_state, (TRIM(text) = '') AS blank FROM capture")
    suspend fun refs(): List<CaptureRef>

    /** Card 16g: close every picture offer older than [cutoff]. Returns how many were closed. */
    @Query("UPDATE capture SET photo_hint = 'DISMISSED', updated_at = :now WHERE photo_hint = 'PENDING' AND created_at < :cutoff")
    suspend fun dismissStaleOffers(cutoff: Long, now: Long): Int

    @Query("DELETE FROM capture WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ExtractionDao {
    @Insert
    suspend fun insert(e: ExtractionEntity)

    @Query("UPDATE extraction SET status = 'REPLACED' WHERE capture_id = :captureId AND status = 'CURRENT'")
    suspend fun markReplaced(captureId: String)

    @Query("SELECT * FROM extraction WHERE capture_id = :captureId ORDER BY at")
    suspend fun forCapture(captureId: String): List<ExtractionEntity>

    @Query("DELETE FROM extraction WHERE capture_id = :captureId")
    suspend fun deleteForCapture(captureId: String)

    @Query("SELECT id FROM extraction")
    suspend fun ids(): List<String>
}

@Dao
interface RecordDao {
    @Insert
    suspend fun insert(r: RecordEntity)

    @Update
    suspend fun update(r: RecordEntity)

    @Query("SELECT * FROM record WHERE id = :id")
    suspend fun get(id: String): RecordEntity?

    @Query("SELECT * FROM record WHERE capture_id = :captureId ORDER BY seq")
    suspend fun forCapture(captureId: String): List<RecordEntity>

    @Query("DELETE FROM record WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("UPDATE record SET deleted_at = :at, updated_at = :now WHERE capture_id = :captureId")
    suspend fun setDeletedForCapture(captureId: String, at: Long?, now: Long)

    /** Records that supersede [oldId] now supersede [newId] instead (used when [oldId] goes away). */
    @Query("UPDATE record SET supersedes_record_id = :newId, updated_at = :now WHERE supersedes_record_id = :oldId")
    suspend fun relink(oldId: String, newId: String?, now: Long)

    @Query("SELECT supersedes_record_id FROM record WHERE id = :id")
    suspend fun supersedesOf(id: String): String?

    /** Links of records that are hidden (soft-deleted), so chains can skip over them. */
    @Query("SELECT id, supersedes_record_id FROM record WHERE deleted_at IS NOT NULL")
    suspend fun deletedLinks(): List<RecordLink>

    @Query("SELECT id, capture_id, extraction_id, kind, deleted_at FROM record")
    suspend fun refs(): List<RecordRef>

    /** A later capture acted on this record (it replaced or closed it): re-reading its own capture will not remove it. Not an owner action, so no revision. */
    @Query("UPDATE record SET pinned = 1, updated_at = :now WHERE id = :id")
    suspend fun pinBySystem(id: String, now: Long)

    @Query("SELECT COUNT(*) FROM record WHERE subject_id = :subjectId OR place_subject_id = :subjectId")
    suspend fun countForSubject(subjectId: String): Int
}

@Dao
interface TaskDao {
    @Insert
    suspend fun insert(t: TaskEntity)

    @Update
    suspend fun update(t: TaskEntity)

    @Query("SELECT * FROM task WHERE record_id = :recordId")
    suspend fun get(recordId: String): TaskEntity?

    @Query("DELETE FROM task WHERE record_id IN (:recordIds)")
    suspend fun deleteForRecords(recordIds: List<String>)

    @Query("SELECT record_id FROM task")
    suspend fun recordIds(): List<String>

    /** Open or snoozed tasks of live records: what the daily upkeep looks at. */
    @Query("SELECT t.record_id FROM task t JOIN record r ON r.id = t.record_id WHERE t.state IN ('OPEN', 'SNOOZED') AND r.deleted_at IS NULL")
    suspend fun activeRecordIds(): List<String>

    /** Open or snoozed tasks from live, non-private captures other than [exceptCaptureId], newest first. */
    @Query(
        "SELECT t.record_id AS record_id, r.title AS title FROM task t JOIN record r ON r.id = t.record_id " +
            "JOIN capture c ON c.id = r.capture_id WHERE t.state IN ('OPEN', 'SNOOZED') AND r.deleted_at IS NULL " +
            "AND c.deleted_at IS NULL AND c.sensitive = 0 AND c.id != :exceptCaptureId ORDER BY c.created_at DESC LIMIT :limit",
    )
    suspend fun openTasks(exceptCaptureId: String, limit: Int): List<OpenTaskRow>
}

@Dao
interface WhereaboutsDao {
    @Insert
    suspend fun insert(w: WhereaboutsEntity)

    @Update
    suspend fun update(w: WhereaboutsEntity)

    @Query("SELECT * FROM whereabouts WHERE record_id = :recordId")
    suspend fun get(recordId: String): WhereaboutsEntity?

    @Query("DELETE FROM whereabouts WHERE record_id IN (:recordIds)")
    suspend fun deleteForRecords(recordIds: List<String>)

    @Query("SELECT record_id FROM whereabouts")
    suspend fun recordIds(): List<String>

    /**
     * Every live placement or loan that no record supersedes, with the time of its capture. Records of
     * [exceptCaptureId] (the capture being read again) do not count as superseding anything: they are about to be replaced.
     */
    @Query(
        "SELECT r.id AS record_id, r.capture_id AS capture_id, w.item_subject_id AS item_subject_id, r.kind AS kind, " +
            "w.direction AS direction, w.copy_label AS copy_label, w.returned_at AS returned_at, c.created_at AS captured_at " +
            "FROM record r JOIN whereabouts w ON w.record_id = r.id JOIN capture c ON c.id = r.capture_id " +
            "WHERE r.deleted_at IS NULL AND c.deleted_at IS NULL AND w.item_subject_id IS NOT NULL " +
            "AND r.id NOT IN (SELECT supersedes_record_id FROM record WHERE supersedes_record_id IS NOT NULL AND capture_id != :exceptCaptureId)",
    )
    suspend fun currentItemRecords(exceptCaptureId: String): List<ItemRecordRow>

    @Query("UPDATE whereabouts SET returned_at = :at, updated_at = :now WHERE record_id = :recordId AND returned_at IS NULL")
    suspend fun markReturned(recordId: String, at: Long, now: Long)
}

@Dao
interface ReadingDao {
    @Insert
    suspend fun insert(r: ReadingEntity)

    @Query("DELETE FROM reading WHERE record_id IN (:recordIds)")
    suspend fun deleteForRecords(recordIds: List<String>)

    @Query("SELECT id, record_id FROM reading")
    suspend fun refs(): List<ReadingRef>

    @Query("SELECT DISTINCT m.label FROM reading r JOIN metric m ON m.id = r.metric_id WHERE r.record_id = :recordId")
    suspend fun metricLabelsFor(recordId: String): List<String>

    @Query("SELECT COUNT(*) FROM reading WHERE subject_id = :subjectId")
    suspend fun countForSubject(subjectId: String): Int

    /**
     * Every reading of a live note, newest first. [includeSensitive] is 1 only for a caller that may draw Private notes
     * (docs/cards/07; no gate exists yet, so nothing in the app passes 1). Repositories pass it on; screens never call this.
     */
    @Query(
        "SELECT r.id AS reading_id, r.record_id AS record_id, rec.capture_id AS capture_id, r.subject_id AS subject_id, s.name AS subject_name, " +
            "r.metric_id AS metric_id, m.label AS metric_label, r.value_text AS value_text, r.value_num AS value_num, r.unit AS unit, " +
            "r.measured_at AS measured_at, COALESCE(rec.place_text, ps.name) AS place_text, rec.attention AS attention, rec.quote AS quote " +
            "FROM reading r JOIN metric m ON m.id = r.metric_id JOIN record rec ON rec.id = r.record_id JOIN capture c ON c.id = rec.capture_id " +
            "LEFT JOIN subject s ON s.id = r.subject_id LEFT JOIN subject ps ON ps.id = rec.place_subject_id " +
            "WHERE rec.deleted_at IS NULL AND c.deleted_at IS NULL AND (:includeSensitive = 1 OR (c.sensitive = 0 AND COALESCE(s.sensitive, 0) = 0)) " +
            "ORDER BY r.measured_at DESC, c.created_at DESC, rec.seq ASC, r.rowid ASC"
    )
    suspend fun visible(includeSensitive: Int): List<ReadingRow>

    @Query(
        "SELECT r.id AS reading_id, r.record_id AS record_id, rec.capture_id AS capture_id, r.subject_id AS subject_id, s.name AS subject_name, " +
            "r.metric_id AS metric_id, m.label AS metric_label, r.value_text AS value_text, r.value_num AS value_num, r.unit AS unit, " +
            "r.measured_at AS measured_at, COALESCE(rec.place_text, ps.name) AS place_text, rec.attention AS attention, rec.quote AS quote " +
            "FROM reading r JOIN metric m ON m.id = r.metric_id JOIN record rec ON rec.id = r.record_id JOIN capture c ON c.id = rec.capture_id " +
            "LEFT JOIN subject s ON s.id = r.subject_id LEFT JOIN subject ps ON ps.id = rec.place_subject_id " +
            "WHERE rec.deleted_at IS NULL AND c.deleted_at IS NULL AND (:includeSensitive = 1 OR (c.sensitive = 0 AND COALESCE(s.sensitive, 0) = 0)) AND r.metric_id = :metricId " +
            "ORDER BY r.measured_at DESC, c.created_at DESC, rec.seq ASC, r.rowid ASC"
    )
    suspend fun forMetric(metricId: String, includeSensitive: Int): List<ReadingRow>

    @Query("SELECT * FROM reading WHERE id = :id")
    suspend fun get(id: String): ReadingEntity?

    /** The values of one record, in the order they were stored (the order he said them). */
    @Query("SELECT * FROM reading WHERE record_id = :recordId ORDER BY rowid")
    suspend fun forRecord(recordId: String): List<ReadingEntity>
}

@Dao
interface MetricDao {
    @Insert
    suspend fun insert(m: MetricEntity)

    @Query("SELECT * FROM metric")
    suspend fun all(): List<MetricEntity>

    @Query("SELECT * FROM metric WHERE id = :id")
    suspend fun get(id: String): MetricEntity?

    @Update
    suspend fun update(m: MetricEntity)

    @Query("DELETE FROM metric WHERE subject_id = :subjectId AND id NOT IN (SELECT metric_id FROM reading)")
    suspend fun deleteUnusedForSubject(subjectId: String)
}

@Dao
interface SubjectDao {
    @Insert
    suspend fun insert(s: SubjectEntity)

    @Query("SELECT * FROM subject")
    suspend fun all(): List<SubjectEntity>

    @Query("SELECT name FROM subject WHERE id = :id")
    suspend fun nameOf(id: String): String?

    @Query("SELECT id FROM subject")
    suspend fun ids(): List<String>

    @Query("SELECT COUNT(*) FROM whereabouts WHERE item_subject_id = :id OR person_subject_id = :id")
    suspend fun whereaboutsUses(id: String): Int

    @Query("SELECT COUNT(*) FROM task WHERE at_place_subject_id = :id")
    suspend fun taskUses(id: String): Int

    @Query("DELETE FROM subject WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface PlaceLocationDao {
    @Insert
    suspend fun insert(p: PlaceLocationEntity)

    @Query("DELETE FROM place_location WHERE subject_id = :subjectId")
    suspend fun deleteForSubject(subjectId: String)
}

@Dao
interface NudgeLogDao {
    @Insert
    suspend fun insert(n: NudgeLogEntity)

    @Query("SELECT * FROM nudge_log WHERE at >= :since ORDER BY at")
    suspend fun since(since: Long): List<NudgeLogEntity>

    @Query("SELECT * FROM nudge_log WHERE id = :id")
    suspend fun get(id: String): NudgeLogEntity?

    @Query("UPDATE nudge_log SET outcome = :outcome WHERE id = :id")
    suspend fun setOutcome(id: String, outcome: NudgeOutcome)
}

@Dao
interface MissLogDao {
    @Insert
    suspend fun insert(m: MissLogEntity)

    @Query("SELECT * FROM miss_log ORDER BY at, rowid")
    suspend fun all(): List<MissLogEntity>
}

/** Card 08 E2. Only [AnswerTraceRepository] and [CaptureRepository.purge] use this. */
@Dao
interface AnswerTraceDao {
    @Insert
    suspend fun insert(t: AnswerTraceEntity)

    @Update
    suspend fun update(t: AnswerTraceEntity)

    @Query("SELECT * FROM answer_trace WHERE id = :id")
    suspend fun get(id: String): AnswerTraceEntity?

    @Query("SELECT * FROM answer_trace ORDER BY at DESC, rowid DESC")
    suspend fun newestFirst(): List<AnswerTraceEntity>

    /** Keep only the newest [keep] traces. */
    @Query("DELETE FROM answer_trace WHERE id IN (SELECT id FROM answer_trace ORDER BY at ASC, rowid ASC LIMIT MAX(0, (SELECT COUNT(*) FROM answer_trace) - :keep))")
    suspend fun trimTo(keep: Int)

    @Query("DELETE FROM answer_trace WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("DELETE FROM answer_trace")
    suspend fun deleteAll()
}

@Dao
interface AppEventDao {
    @Insert
    suspend fun insert(e: AppEventEntity)

    @Query("SELECT * FROM app_event ORDER BY at DESC LIMIT :limit")
    suspend fun newest(limit: Int): List<AppEventEntity>

    @Query("SELECT COUNT(*) FROM app_event")
    suspend fun count(): Int

    /** Keep only the newest [keep] events. */
    @Query("DELETE FROM app_event WHERE id IN (SELECT id FROM app_event ORDER BY at ASC, rowid ASC LIMIT MAX(0, (SELECT COUNT(*) FROM app_event) - :keep))")
    suspend fun trimTo(keep: Int)
}

@Dao
interface RevisionDao {
    @Insert
    suspend fun insert(r: RevisionEntity)

    @Query("SELECT * FROM revision WHERE capture_id = :captureId OR record_id IN (SELECT id FROM record WHERE capture_id = :captureId) ORDER BY at")
    suspend fun forCapture(captureId: String): List<RevisionEntity>

    @Query("DELETE FROM revision WHERE capture_id = :captureId")
    suspend fun deleteForCapture(captureId: String)

    @Query("DELETE FROM revision WHERE record_id IN (:recordIds)")
    suspend fun deleteForRecords(recordIds: List<String>)
}

@Dao
interface TombstoneDao {
    @Insert
    suspend fun insert(t: TombstoneEntity)

    @Query("SELECT * FROM tombstone ORDER BY deleted_at")
    suspend fun all(): List<TombstoneEntity>
}

/**
 * Card 16a. Only [AttachmentRepository] and [CaptureRepository.purge] use this; it is the only code that reads
 * `file_key`. Listings are ordered by position, then time, then id, so the order is total.
 */
@Dao
interface AttachmentDao {
    @Insert
    suspend fun insert(a: AttachmentEntity)

    @Query("SELECT * FROM attachment WHERE id = :id")
    suspend fun get(id: String): AttachmentEntity?

    @Query("SELECT * FROM attachment WHERE capture_id = :captureId ORDER BY position, created_at, id")
    suspend fun forCapture(captureId: String): List<AttachmentEntity>

    /** Pictures only: audio (card 26) is left out of everything that lists pictures. */
    @Query("SELECT * FROM attachment WHERE capture_id = :captureId AND kind IN ('PHOTO','SCREENSHOT','SCAN') ORDER BY position, created_at, id")
    suspend fun picturesForCapture(captureId: String): List<AttachmentEntity>

    @Query("SELECT id, capture_id, kind, position, width, height, byte_size, ocr_text FROM attachment WHERE capture_id = :captureId ORDER BY position, created_at, id")
    suspend fun infoForCapture(captureId: String): List<AttachmentInfo>

    @Query("SELECT id, capture_id, kind, position, width, height, byte_size, ocr_text FROM attachment WHERE capture_id = :captureId AND kind IN ('PHOTO','SCREENSHOT','SCAN') ORDER BY position, created_at, id")
    suspend fun pictureInfoForCapture(captureId: String): List<AttachmentInfo>

    @Query("SELECT COUNT(*) AS count, COALESCE(SUM(byte_size), 0) AS bytes FROM attachment WHERE kind IN ('PHOTO','SCREENSHOT','SCAN')")
    suspend fun pictureStats(): PictureStats

    @Query("SELECT MAX(position) FROM attachment WHERE capture_id = :captureId")
    suspend fun maxPosition(captureId: String): Int?

    @Query("DELETE FROM attachment WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("DELETE FROM attachment WHERE capture_id = :captureId")
    suspend fun deleteForCapture(captureId: String)

    @Query("SELECT id, capture_id, file_name, kind FROM attachment")
    suspend fun refs(): List<AttachmentRef>

    /** Card 26c: the note's recording (at most one; a list so a broken state is visible, not hidden). */
    @Query("SELECT * FROM attachment WHERE capture_id = :captureId AND kind = 'AUDIO' ORDER BY position, created_at, id")
    suspend fun audioForCapture(captureId: String): List<AttachmentEntity>

    @Query("SELECT id, capture_id, duration_ms, byte_size, mime FROM attachment WHERE capture_id = :captureId AND kind = 'AUDIO' ORDER BY position, created_at, id")
    suspend fun recordingInfo(captureId: String): List<RecordingInfo>

    @Query("SELECT COUNT(*) AS count, COALESCE(SUM(byte_size), 0) AS bytes, COALESCE(SUM(duration_ms), 0) AS duration_ms FROM attachment WHERE kind = 'AUDIO'")
    suspend fun audioTotals(): AudioTotals

    /** Card 26c item 16: point a row at a new file (the old file is deleted after the transaction). */
    @Query("UPDATE attachment SET file_name = :fileName, file_key = :fileKey, mime = :mime, byte_size = :byteSize, sha256 = :sha256, updated_at = :now WHERE id = :id")
    suspend fun replaceFile(id: String, fileName: String, fileKey: ByteArray, mime: String, byteSize: Long, sha256: String, now: Long): Int

    @Query("SELECT file_name FROM attachment")
    suspend fun fileNames(): List<String>

    /** Card 08: how many pictures each note has (audio does not count). Notes with none are not listed. */
    @Query("SELECT capture_id, COUNT(*) AS count FROM attachment WHERE kind IN ('PHOTO','SCREENSHOT','SCAN') GROUP BY capture_id")
    suspend fun pictureCounts(): List<CaptureCount>

    @Query("UPDATE attachment SET ocr_text = :text, ocr_engine = :engine, updated_at = :now WHERE id = :id")
    suspend fun updateOcr(id: String, text: String?, engine: String?, now: Long)

    /** Card 16f: the words read from a note's pictures, in order, for the search document. */
    @Query("SELECT ocr_text FROM attachment WHERE capture_id = :captureId AND ocr_text IS NOT NULL AND ocr_text != '' AND kind IN ('PHOTO','SCREENSHOT','SCAN') ORDER BY position, created_at, id")
    suspend fun ocrTexts(captureId: String): List<String>

    /**
     * Card 16f: pictures of live notes that no reader has looked at yet (`ocr_engine` is null), the least recently
     * touched first, so one the reader cannot read goes to the back of the line instead of blocking it.
     */
    @Query(
        "SELECT a.id, a.capture_id, a.kind, a.position, a.width, a.height, a.byte_size, a.ocr_text FROM attachment a " +
            "JOIN capture c ON c.id = a.capture_id WHERE a.ocr_engine IS NULL AND a.kind IN ('PHOTO','SCREENSHOT','SCAN') " +
            "AND c.deleted_at IS NULL ORDER BY a.updated_at, a.id LIMIT :limit",
    )
    suspend fun unreadPictures(limit: Int): List<AttachmentInfo>
}

/** Card 26c. Only [TranscriptionRepository], [CaptureRepository.purge] and the consistency check use this. */
@Dao
interface TranscriptionDao {
    @Insert
    suspend fun insert(t: TranscriptionEntity)

    @Query("SELECT * FROM transcription WHERE id = :id")
    suspend fun get(id: String): TranscriptionEntity?

    /** Newest first; rows written in the same millisecond keep the order they were written in. */
    @Query("SELECT * FROM transcription WHERE capture_id = :captureId ORDER BY at DESC, rowid DESC")
    suspend fun forCapture(captureId: String): List<TranscriptionEntity>

    @Query("UPDATE transcription SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: TranscriptionStatus)

    /** Every row of the capture with status [from] becomes [to]. */
    @Query("UPDATE transcription SET status = :to WHERE capture_id = :captureId AND status = :from")
    suspend fun moveStatus(captureId: String, from: TranscriptionStatus, to: TranscriptionStatus)

    @Query("DELETE FROM transcription WHERE capture_id = :captureId")
    suspend fun deleteForCapture(captureId: String)

    @Query("SELECT id, capture_id, status FROM transcription")
    suspend fun refs(): List<TranscriptionRef>

    // --- card 26f: the transcription pass and what Settings shows (queries only; no schema change) ---

    /** Live notes waiting for their first writing-out (PENDING or RETRY), oldest first. */
    @Query("SELECT * FROM capture WHERE deleted_at IS NULL AND transcribe_state IN ('PENDING', 'RETRY') ORDER BY created_at, id")
    suspend fun waitingCaptures(): List<CaptureEntity>

    /** Live notes that are written out (DONE): the candidates for an owner's "write it out again". */
    @Query("SELECT id FROM capture WHERE deleted_at IS NULL AND transcribe_state = 'DONE' ORDER BY created_at, id")
    suspend fun doneCaptureIds(): List<String>

    /** Recordings still kept as the WAV fallback (26b), oldest first. */
    @Query("SELECT id FROM attachment WHERE kind = 'AUDIO' AND mime = :wavMime ORDER BY created_at, id LIMIT :limit")
    suspend fun wavRecordingIds(wavMime: String, limit: Int): List<String>

    @Query("SELECT COUNT(*) FROM capture WHERE deleted_at IS NULL AND transcribe_state = :state")
    suspend fun countInState(state: String): Int

    /** From saving a note to its first writing-out, for the newest one (null if none yet). */
    @Query(
        "SELECT t.at - c.created_at FROM transcription t JOIN capture c ON c.id = t.capture_id " +
            "WHERE t.asked_by_owner = 0 AND t.status != 'FAILED' ORDER BY t.at DESC, t.rowid DESC LIMIT 1",
    )
    suspend fun lastFirstDelayMs(): Long?
}

@Dao
interface SearchDao {
    @Query("INSERT INTO search_fts(doc_id, doc_type, body) VALUES (:docId, :docType, :body)")
    suspend fun add(docId: String, docType: String, body: String)

    @Query("DELETE FROM search_fts WHERE doc_id IN (:docIds)")
    suspend fun remove(docIds: List<String>)

    @Query("DELETE FROM search_fts")
    suspend fun clear()

    @Query("SELECT doc_id, doc_type FROM search_fts WHERE search_fts MATCH :query")
    suspend fun match(query: String): List<DocKey>

    @Query("SELECT doc_id, doc_type FROM search_fts")
    suspend fun keys(): List<DocKey>

    /** Merge the index's segments so no deleted words linger in its internal tables. */
    @Query("INSERT INTO search_fts(search_fts) VALUES ('optimize')")
    suspend fun optimize()
}

@Dao
interface VocabDao {
    @Query("SELECT word FROM vocab_word ORDER BY word COLLATE NOCASE")
    fun observe(): Flow<List<String>>

    @Query("SELECT word FROM vocab_word")
    suspend fun all(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(w: WordEntity)

    @Query("DELETE FROM vocab_word WHERE word = :word")
    suspend fun remove(word: String)
}

@Dao
interface CorrectionDao {
    @Query("SELECT * FROM correction ORDER BY heard")
    fun observe(): Flow<List<CorrectionEntity>>

    @Query("SELECT * FROM correction")
    suspend fun all(): List<CorrectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(c: CorrectionEntity)

    @Query("DELETE FROM correction WHERE heard = :heard")
    suspend fun remove(heard: String)
}
