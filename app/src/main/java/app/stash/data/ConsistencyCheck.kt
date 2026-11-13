package app.stash.data

import androidx.room.withTransaction
import app.stash.security.AttachmentStore

enum class ViolationKind {
    RECORD_WITHOUT_LIVE_CAPTURE,
    RECORD_WITHOUT_EXTRACTION,
    TASK_RECORD_WITHOUT_TASK_ROW,
    WHEREABOUTS_RECORD_WITHOUT_ROW,
    READING_WITHOUT_RECORD,
    RECORD_NOT_INDEXED,
    CAPTURE_NOT_INDEXED,
    INDEX_DOCUMENT_WITHOUT_LIVE_ROW,
    /** Card 16a: an attachment row whose capture is gone. */
    ATTACHMENT_WITHOUT_CAPTURE,
    /** Card 16a: an attachment row whose file is missing. Reported, never deleted: the row holds the words read from it. */
    ATTACHMENT_WITHOUT_FILE,
    /** Card 16a: a file in `files/att/` with no row (the sweep deletes it once it is 24 hours old). [Violation.rowId] is the file name. */
    FILE_WITHOUT_ATTACHMENT,
    /** Card 26c: a capture waiting for, retrying or given up on a writing-out (PENDING, RETRY, GAVE_UP) that has no recording. */
    RECORDING_STATE_WITHOUT_AUDIO,
    /** Card 26c: a capture with blank text and no attachment at all: neither words nor a file. */
    EMPTY_NOTE,
    /** Card 26c: a capture with more than one `AUDIO` attachment. [Violation.rowId] is the capture id. */
    TWO_RECORDINGS,
    /** Card 26c: a capture with more than one CURRENT writing-out. [Violation.rowId] is the capture id. */
    TWO_CURRENT_TRANSCRIPTIONS,
}

/** [rowId] is an opaque id, never any of the owner's words. */
data class Violation(val kind: ViolationKind, val rowId: String)

/** Everything the check looks at, as plain data so the check itself is pure and testable. */
data class ConsistencySnapshot(
    val captures: List<CaptureRef>,
    val records: List<RecordRef>,
    val extractionIds: Set<String>,
    val taskRecordIds: Set<String>,
    val whereaboutsRecordIds: Set<String>,
    val readings: List<ReadingRef>,
    val documents: Set<DocKey>,
    /** Card 16a: attachment rows (no keys). */
    val attachments: List<AttachmentRef> = emptyList(),
    /** Card 16a: the finished file names in `files/att/`. */
    val files: Set<String> = emptySet(),
    /** Card 26c: writing-out rows (no words). */
    val transcriptions: List<TranscriptionRef> = emptyList(),
)

object ConsistencyCheck {
    fun run(s: ConsistencySnapshot): List<Violation> {
        val out = mutableListOf<Violation>()
        val captureById = s.captures.associateBy { it.id }
        val recordIds = s.records.map { it.id }.toSet()
        val liveCaptureIds = s.captures.filter { it.deletedAt == null }.map { it.id }.toSet()
        val liveRecords = s.records.filter { it.deletedAt == null && it.captureId in liveCaptureIds }

        for (r in s.records) {
            val c = captureById[r.captureId]
            if (c == null || (c.deletedAt != null && r.deletedAt == null)) out += Violation(ViolationKind.RECORD_WITHOUT_LIVE_CAPTURE, r.id)
            if (r.extractionId !in s.extractionIds) out += Violation(ViolationKind.RECORD_WITHOUT_EXTRACTION, r.id)
            if (r.kind == "TASK" && r.id !in s.taskRecordIds) out += Violation(ViolationKind.TASK_RECORD_WITHOUT_TASK_ROW, r.id)
            if ((r.kind == "PLACED" || r.kind == "LOAN") && r.id !in s.whereaboutsRecordIds) {
                out += Violation(ViolationKind.WHEREABOUTS_RECORD_WITHOUT_ROW, r.id)
            }
        }
        for (rd in s.readings) if (rd.recordId !in recordIds) out += Violation(ViolationKind.READING_WITHOUT_RECORD, rd.id)

        val docs = s.documents
        for (c in liveCaptureIds) if (DocKey(c, DocType.CAPTURE.name) !in docs) out += Violation(ViolationKind.CAPTURE_NOT_INDEXED, c)
        for (r in liveRecords) if (DocKey(r.id, DocType.RECORD.name) !in docs) out += Violation(ViolationKind.RECORD_NOT_INDEXED, r.id)
        val liveRecordIds = liveRecords.map { it.id }.toSet()
        for (d in docs) {
            val ok = when (d.docType) {
                DocType.CAPTURE.name -> d.docId in liveCaptureIds
                DocType.RECORD.name -> d.docId in liveRecordIds
                else -> false
            }
            if (!ok) out += Violation(ViolationKind.INDEX_DOCUMENT_WITHOUT_LIVE_ROW, d.docId)
        }

        for (a in s.attachments) {
            if (a.captureId !in captureById) out += Violation(ViolationKind.ATTACHMENT_WITHOUT_CAPTURE, a.id)
            if (a.fileName !in s.files) out += Violation(ViolationKind.ATTACHMENT_WITHOUT_FILE, a.id)
        }
        val owned = s.attachments.map { it.fileName }.toSet()
        for (f in s.files) if (f !in owned) out += Violation(ViolationKind.FILE_WITHOUT_ATTACHMENT, f)

        // Card 26c: recordings and their writing-outs.
        val byCapture = s.attachments.groupBy { it.captureId }
        val waiting = setOf("PENDING", "RETRY", "GAVE_UP")
        for (c in s.captures) {
            val atts = byCapture[c.id].orEmpty()
            val audio = atts.count { it.kind == AttachmentKind.AUDIO.name }
            if (c.transcribeState in waiting && audio == 0) out += Violation(ViolationKind.RECORDING_STATE_WITHOUT_AUDIO, c.id)
            if (c.blank && atts.isEmpty()) out += Violation(ViolationKind.EMPTY_NOTE, c.id)
            if (audio > 1) out += Violation(ViolationKind.TWO_RECORDINGS, c.id)
        }
        s.transcriptions.filter { it.status == TranscriptionStatus.CURRENT.name }.groupBy { it.captureId }
            .filter { it.value.size > 1 }.keys.forEach { out += Violation(ViolationKind.TWO_CURRENT_TRANSCRIPTIONS, it) }
        return out
    }
}

/** Runs the check over the real database. [repair] can only fix the index, which is rebuildable; the rest it reports. */
class Consistency(
    private val db: StashDatabase,
    private val index: SearchIndex,
    /** The attachment files (card 16a); null only where no store exists, and then there can be no attachments either. */
    private val files: AttachmentStore? = null,
) {
    suspend fun snapshot() = ConsistencySnapshot(
        captures = db.captures().refs(),
        records = db.records().refs(),
        extractionIds = db.extractions().ids().toSet(),
        taskRecordIds = db.tasks().recordIds().toSet(),
        whereaboutsRecordIds = db.whereabouts().recordIds().toSet(),
        readings = db.readings().refs(),
        documents = db.search().keys().toSet(),
        attachments = db.attachments().refs(),
        files = files?.list().orEmpty(),
        transcriptions = db.transcriptions().refs(),
    )

    suspend fun run(): List<Violation> = ConsistencyCheck.run(snapshot())

    /** Rebuild the index, then report whatever is still wrong. */
    suspend fun repair(): List<Violation> {
        db.withTransaction { index.rebuild() }
        return run()
    }
}
