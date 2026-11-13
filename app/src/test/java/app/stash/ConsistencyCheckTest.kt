package app.stash

import app.stash.data.AttachmentRef
import app.stash.data.CaptureRef
import app.stash.data.ConsistencyCheck
import app.stash.data.ConsistencySnapshot
import app.stash.data.DocKey
import app.stash.data.ReadingRef
import app.stash.data.RecordRef
import app.stash.data.TranscriptionRef
import app.stash.data.Violation
import app.stash.data.ViolationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure part of the consistency check, over hand-built broken states. */
class ConsistencyCheckTest {
    private fun cap(id: String, deleted: Long? = null) = CaptureRef(id, deleted)
    private fun rec(id: String, cap: String, kind: String = "OTHER", x: String = "x1", deleted: Long? = null) = RecordRef(id, cap, x, kind, deleted)
    private fun doc(id: String, type: String) = DocKey(id, type)

    private fun snap(
        captures: List<CaptureRef> = listOf(cap("c1")),
        records: List<RecordRef> = emptyList(),
        extractions: Set<String> = setOf("x1"),
        tasks: Set<String> = emptySet(),
        whereabouts: Set<String> = emptySet(),
        readings: List<ReadingRef> = emptyList(),
        docs: Set<DocKey>? = null,
    ): ConsistencySnapshot {
        val auto = captures.filter { it.deletedAt == null }.map { doc(it.id, "CAPTURE") } +
            records.filter { it.deletedAt == null && captures.any { c -> c.id == it.captureId && c.deletedAt == null } }.map { doc(it.id, "RECORD") }
        return ConsistencySnapshot(captures, records, extractions, tasks, whereabouts, readings, docs ?: auto.toSet())
    }

    private fun kinds(v: List<Violation>) = v.map { it.kind }

    @Test fun aHealthyStateHasNoViolations() {
        val s = snap(records = listOf(rec("r1", "c1", "TASK"), rec("r2", "c1", "PLACED"), rec("r3", "c1", "LOAN")),
            tasks = setOf("r1"), whereabouts = setOf("r2", "r3"), readings = listOf(ReadingRef("rd", "r1")))
        assertTrue(ConsistencyCheck.run(s).isEmpty())
    }

    @Test fun recordWithoutCaptureOrInADeletedCaptureIsFound() {
        val missing = snap(records = listOf(rec("r1", "nope")))
        assertTrue(ViolationKind.RECORD_WITHOUT_LIVE_CAPTURE in kinds(ConsistencyCheck.run(missing)))
        val inBin = snap(captures = listOf(cap("c1", deleted = 5)), records = listOf(rec("r1", "c1")))
        assertTrue(ViolationKind.RECORD_WITHOUT_LIVE_CAPTURE in kinds(ConsistencyCheck.run(inBin)))
        val hiddenToo = snap(captures = listOf(cap("c1", deleted = 5)), records = listOf(rec("r1", "c1", deleted = 5)))
        assertTrue(ConsistencyCheck.run(hiddenToo).isEmpty())
    }

    @Test fun recordWithoutExtractionIsFound() {
        val v = ConsistencyCheck.run(snap(records = listOf(rec("r1", "c1", x = "gone"))))
        assertEquals(listOf(Violation(ViolationKind.RECORD_WITHOUT_EXTRACTION, "r1")), v)
    }

    @Test fun taskAndWhereaboutsRecordsNeedTheirRows() {
        val v = ConsistencyCheck.run(snap(records = listOf(rec("t", "c1", "TASK"), rec("p", "c1", "PLACED"), rec("l", "c1", "LOAN"), rec("o", "c1", "OTHER"))))
        assertEquals(
            setOf(Violation(ViolationKind.TASK_RECORD_WITHOUT_TASK_ROW, "t"), Violation(ViolationKind.WHEREABOUTS_RECORD_WITHOUT_ROW, "p"),
                Violation(ViolationKind.WHEREABOUTS_RECORD_WITHOUT_ROW, "l")),
            v.toSet(),
        )
    }

    @Test fun readingWithoutRecordIsFound() {
        val v = ConsistencyCheck.run(snap(readings = listOf(ReadingRef("rd1", "ghost"))))
        assertEquals(listOf(Violation(ViolationKind.READING_WITHOUT_RECORD, "rd1")), v)
    }

    @Test fun missingAndStrayIndexDocumentsAreFound() {
        val s = snap(records = listOf(rec("r1", "c1")), docs = setOf(doc("stray", "RECORD"), doc("c1", "CAPTURE").copy(docType = "WEIRD")))
        assertEquals(
            setOf(ViolationKind.CAPTURE_NOT_INDEXED, ViolationKind.RECORD_NOT_INDEXED, ViolationKind.INDEX_DOCUMENT_WITHOUT_LIVE_ROW),
            kinds(ConsistencyCheck.run(s)).toSet(),
        )
        // a deleted capture must not be indexed
        val bin = snap(captures = listOf(cap("c1", deleted = 1)), docs = setOf(doc("c1", "CAPTURE")))
        assertEquals(listOf(ViolationKind.INDEX_DOCUMENT_WITHOUT_LIVE_ROW), kinds(ConsistencyCheck.run(bin)))
    }

    @Test fun attachmentsNeedACaptureAndAFileAndEveryFileNeedsARow() {
        val ok = snap().copy(attachments = listOf(AttachmentRef("a1", "c1", "f1")), files = setOf("f1"))
        assertTrue(ConsistencyCheck.run(ok).isEmpty())
        // an attachment of a note in the bin is fine: it is hidden with it
        val inBin = snap(captures = listOf(cap("c1", deleted = 5))).copy(attachments = listOf(AttachmentRef("a1", "c1", "f1")), files = setOf("f1"))
        assertTrue(ConsistencyCheck.run(inBin).isEmpty())
        val broken = snap().copy(
            attachments = listOf(AttachmentRef("a1", "gone", "f1"), AttachmentRef("a2", "c1", "missing")),
            files = setOf("f1", "stray"),
        )
        assertEquals(
            setOf(Violation(ViolationKind.ATTACHMENT_WITHOUT_CAPTURE, "a1"), Violation(ViolationKind.ATTACHMENT_WITHOUT_FILE, "a2"),
                Violation(ViolationKind.FILE_WITHOUT_ATTACHMENT, "stray")),
            ConsistencyCheck.run(broken).toSet(),
        )
    }

    @Test fun recordingsAndTheirWritingOutsAreChecked() {
        val audio = AttachmentRef("a1", "c1", "f1", "AUDIO")
        // a waiting recording, a written-out one whose recording was removed, a picture-only note: all fine
        val ok = snap(captures = listOf(CaptureRef("c1", null, "PENDING", blank = true), CaptureRef("c2", null, "DONE"), CaptureRef("c3", null, null, blank = true)))
            .copy(
                attachments = listOf(audio, AttachmentRef("p1", "c3", "f2", "PHOTO")), files = setOf("f1", "f2"),
                transcriptions = listOf(TranscriptionRef("t1", "c2", "CURRENT"), TranscriptionRef("t2", "c2", "REPLACED"), TranscriptionRef("t3", "c2", "OFFERED")),
            )
        assertTrue(ConsistencyCheck.run(ok).isEmpty())
        val broken = snap(
            captures = listOf(
                CaptureRef("c1", null, "RETRY"), CaptureRef("c2", null, "GAVE_UP"), CaptureRef("c3", null, null, blank = true),
                CaptureRef("c4", null, "DONE"), CaptureRef("c5", 9, null, blank = true),
            ),
        ).copy(
            attachments = listOf(AttachmentRef("p1", "c1", "f1", "PHOTO"), AttachmentRef("a1", "c4", "f2", "AUDIO"), AttachmentRef("a2", "c4", "f3", "AUDIO")),
            files = setOf("f1", "f2", "f3"),
            transcriptions = listOf(TranscriptionRef("t1", "c4", "CURRENT"), TranscriptionRef("t2", "c4", "CURRENT"), TranscriptionRef("t3", "c1", "CURRENT")),
        )
        assertEquals(
            setOf(
                Violation(ViolationKind.RECORDING_STATE_WITHOUT_AUDIO, "c1"), Violation(ViolationKind.RECORDING_STATE_WITHOUT_AUDIO, "c2"),
                Violation(ViolationKind.EMPTY_NOTE, "c3"), Violation(ViolationKind.EMPTY_NOTE, "c5"),
                Violation(ViolationKind.TWO_RECORDINGS, "c4"), Violation(ViolationKind.TWO_CURRENT_TRANSCRIPTIONS, "c4"),
            ),
            ConsistencyCheck.run(broken).filter { it.kind !in setOf(ViolationKind.CAPTURE_NOT_INDEXED, ViolationKind.INDEX_DOCUMENT_WITHOUT_LIVE_ROW) }.toSet(),
        )
    }
}
