package app.stash

import androidx.sqlite.db.SupportSQLiteDatabase
import app.stash.TestSupport.rows
import app.stash.data.CaptureEntity
import app.stash.data.Clock
import app.stash.data.Direction
import app.stash.data.ExtractState
import app.stash.data.ExtractionEntity
import app.stash.data.ExtractionStatus
import app.stash.data.MetricEntity
import app.stash.data.PlaceLocationEntity
import app.stash.data.ReadingEntity
import app.stash.data.RecordBundle
import app.stash.data.RecordEntity
import app.stash.data.RevisionKind
import app.stash.data.StashDatabase
import app.stash.data.SubjectEntity
import app.stash.data.SubjectType
import app.stash.data.TaskEntity
import app.stash.data.WhereaboutsEntity
import app.stash.data.AppEventEntity
import app.stash.data.stamped
import app.stash.domain.AnswerComposer
import app.stash.domain.NoteKind
import app.stash.domain.RecordKind
import app.stash.domain.Searcher
import app.stash.domain.Structured
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class FoundationsTest {
    private lateinit var db: StashDatabase
    private val clock = FakeClock()
    private val repo by lazy { TestSupport.repo(db, clock) }

    @Before fun setUp() { db = TestSupport.memoryDb() }
    @After fun tearDown() { db.close() }

    private fun extraction(captureId: String, id: String = UUID.randomUUID().toString(), json: String = "{}") =
        ExtractionEntity(id, captureId, clock.now(), "test", 1, "h", json, ExtractionStatus.CURRENT)

    private fun record(captureId: String, id: String = UUID.randomUUID().toString(), kind: RecordKind = RecordKind.OTHER,
                       title: String = "t", quote: String = "q", pinned: Boolean = false, aliases: String = "[]", seq: Int = 0,
                       supersedes: String? = null, subjectId: String? = null) =
        RecordEntity(id, captureId, "x", seq, kind, title, quote, pinned = pinned, aliases = aliases, supersedesRecordId = supersedes,
            subjectId = subjectId, createdAt = clock.now(), updatedAt = clock.now())

    private fun SupportSQLiteDatabase.count(table: String) = (rows("SELECT COUNT(*) AS n FROM $table").single()["n"] as Long).toInt()

    // ---------- new records get UUIDs; the save path ----------

    @Test fun savingANoteMakesACaptureAnExtractionAndARecordWithUuids() = runBlocking {
        val c = repo.saveNote("i put the keys on the hook", null, capturedLocked = true)
        UUID.fromString(c.id)
        val live = repo.captures.live().single()
        assertEquals(c.id, live.capture.id)
        assertEquals(true, live.capture.capturedLocked); assertEquals("test", live.capture.appVersion)
        assertEquals(ExtractState.DONE, live.capture.extractState)
        val r = live.records.single()
        UUID.fromString(r.record.id); UUID.fromString(r.extraction!!.id)
        assertEquals(RecordKind.PLACED, r.record.kind)
        assertEquals("keys", r.whereabouts!!.itemText); assertEquals(Direction.PLACED, r.whereabouts!!.direction)
        assertEquals("prototype-rules", r.extraction!!.extractor); assertEquals(0, r.extraction!!.schemaVersion)
        assertEquals(c.text.take(80), r.record.title)
        assertEquals(64, r.extraction!!.textHash.length)
        assertTrue(repo.consistency.run().isEmpty())
    }

    @Test fun aNewerNoteAboutTheSameItemSupersedesAndAnswersLatestFirst() = runBlocking {
        repo.saveNote("i put the keys in the garage", null); clock.advance()
        repo.saveNote("i put the keys on the hook", null)
        val views = repo.noteViews()
        val m = Searcher.search("where are my keys", views)
        assertEquals(1, m.size)
        assertEquals(1, m[0].history.size)
        assertTrue(AnswerComposer.compose(m, clock.now()).startsWith("Your keys are on the hook"))
        // deleting the newer note makes the older one current again
        repo.deleteCapture(repo.captures.live().first().capture.id)
        val after = Searcher.search("where are my keys", repo.noteViews())
        assertTrue(AnswerComposer.compose(after, clock.now()).startsWith("Your keys are in the garage"))
    }

    @Test fun reminderKeepsItsItemForAnswers() = runBlocking {
        repo.saveNote("I need to remember to buy milk at Costco", null)
        val r = repo.captures.live().single().records.single()
        assertEquals(RecordKind.TASK, r.record.kind); assertEquals("at Costco", r.task!!.atPlaceText)
        assertEquals("milk", repo.noteViews().single().item)
        assertEquals(NoteKind.REMINDER, repo.noteViews().single().kind)
    }

    // ---------- updated_at ----------

    @Test fun everyStampedEntityGetsUpdatedAtFromTheClock() {
        val c = Clock { 42L }
        val e = 1L
        assertEquals(42L, CaptureEntity("a", e, e, rawText = "r", text = "t").stamped(c).updatedAt)
        assertEquals(42L, RecordEntity("a", "c", "x", 0, RecordKind.OTHER, "t", "q", createdAt = e, updatedAt = e).stamped(c).updatedAt)
        assertEquals(42L, TaskEntity("a", lastActiveAt = e, createdAt = e, updatedAt = e).stamped(c).updatedAt)
        assertEquals(42L, ReadingEntity("a", "r", null, "m", "5", null, null, e, e, e).stamped(c).updatedAt)
        assertEquals(42L, MetricEntity("a", null, "ph", "pH", createdAt = e, updatedAt = e).stamped(c).updatedAt)
        assertEquals(42L, WhereaboutsEntity("a", itemText = "i", direction = Direction.PLACED, createdAt = e, updatedAt = e).stamped(c).updatedAt)
        assertEquals(42L, SubjectEntity("a", SubjectType.ITEM, "n", "n", createdAt = e, updatedAt = e).stamped(c).updatedAt)
        assertEquals(42L, PlaceLocationEntity("a", "s", 1.0, 2.0, 3.0, "x", e, e).stamped(c).updatedAt)
    }

    @Test fun repositoryWritesSetUpdatedAt() = runBlocking {
        clock.t = 5_000
        val c = repo.captures.insertCapture("raw", "hello world")
        assertEquals(5_000L, c.updatedAt); assertEquals(5_000L, c.createdAt)
        clock.t = 6_000
        repo.captures.updateText(c.id, "hello there")
        assertEquals(6_000L, repo.captures.get(c.id)!!.updatedAt)
        assertEquals(5_000L, repo.captures.get(c.id)!!.createdAt)
        clock.t = 7_000
        repo.records.replaceRecordsFor(c.id, extraction(c.id), listOf(RecordBundle(record(c.id, kind = RecordKind.PLACED),
            whereabouts = WhereaboutsEntity("z", itemText = "i", direction = Direction.PLACED, createdAt = 0, updatedAt = 0),
            task = TaskEntity("z", lastActiveAt = 0, createdAt = 0, updatedAt = 0))))
        val r = db.records().forCapture(c.id).single()
        assertEquals(7_000L, r.updatedAt)
        assertEquals(7_000L, db.whereabouts().get(r.id)!!.updatedAt)
        assertEquals(7_000L, db.openHelper.readableDatabase.rows("SELECT updated_at AS u FROM task").single()["u"])
        assertEquals(7_000L, repo.captures.get(c.id)!!.updatedAt) // marked DONE
        clock.t = 8_000
        repo.captures.setSensitive(c.id, true)
        assertEquals(8_000L, repo.captures.get(c.id)!!.updatedAt)
        clock.t = 9_000
        repo.records.pin(r.id)
        assertEquals(9_000L, db.records().get(r.id)!!.updatedAt)
    }

    // ---------- revisions: the owner's actions ----------

    @Test fun ownerActionsWriteRevisionsWithOldAndNewValues() = runBlocking {
        val c = repo.captures.insertCapture("sid has it", "sid has it")
        repo.captures.updateText(c.id, "Syd has it")
        repo.captures.setSensitive(c.id, true, "owner")
        repo.captures.softDelete(c.id)
        repo.captures.restore(c.id)
        val revs = db.revisions().forCapture(c.id)
        assertEquals(
            listOf(RevisionKind.TEXT_CORRECTED, RevisionKind.MADE_PRIVATE, RevisionKind.DELETED, RevisionKind.RESTORED),
            revs.map { it.what },
        )
        assertEquals("sid has it", revs[0].oldValue); assertEquals("Syd has it", revs[0].newValue)
        assertEquals("false", revs[1].oldValue); assertEquals("true", revs[1].newValue)
        assertEquals(ExtractState.PENDING, repo.captures.get(c.id)!!.extractState)
        // a correction that changes nothing writes nothing
        repo.captures.updateText(c.id, "Syd has it")
        assertEquals(4, db.revisions().forCapture(c.id).size)
    }

    @Test fun correctingAWordEditsTheRecordPinsItAndWritesRevisions() = runBlocking {
        val c = repo.saveNote("sid has the spare key in the frunk", null)
        repo.correctWord(c.id, "sid", "Syd")
        val live = repo.captures.live().single()
        assertTrue(live.capture.text.startsWith("Syd has"))
        val r = live.records.single().record
        assertTrue(r.pinned); assertTrue(r.title.startsWith("Syd has"))
        val kinds = db.revisions().forCapture(c.id).map { it.what }
        assertTrue(RevisionKind.TEXT_CORRECTED in kinds && RevisionKind.EDITED in kinds)
        assertEquals("sid", db.corrections().all().single { it.meant == "Syd" }.heard)
    }

    // ---------- replaceRecordsFor ----------

    @Test fun replaceKeepsPinnedRecordsAndReplacesUnpinned() = runBlocking {
        val c = repo.captures.insertCapture("raw", "text")
        val x1 = extraction(c.id)
        val pinned = record(c.id, "pinned1", pinned = true, title = "mine", seq = 0)
        val loose = record(c.id, "loose1", title = "old guess", seq = 1)
        repo.records.replaceRecordsFor(c.id, x1, listOf(RecordBundle(pinned), RecordBundle(loose, task = TaskEntity("loose1", lastActiveAt = 0, createdAt = 0, updatedAt = 0))))
        val x2 = extraction(c.id)
        repo.records.replaceRecordsFor(c.id, x2, listOf(RecordBundle(record(c.id, "new1", title = "new guess", seq = 1))))
        val now = db.records().forCapture(c.id)
        assertEquals(setOf("pinned1", "new1"), now.map { it.id }.toSet())
        assertEquals("mine", now.first { it.id == "pinned1" }.title)
        assertEquals(0, db.openHelper.readableDatabase.count("task"))
        val xs = db.extractions().forCapture(c.id)
        assertEquals(ExtractionStatus.REPLACED, xs.first { it.id == x1.id }.status)
        assertEquals(ExtractionStatus.CURRENT, xs.first { it.id == x2.id }.status)
        assertTrue(repo.consistency.run().isEmpty())
    }

    @Test fun aFailureHalfwayLeavesTheOldRecordsIntact() = runBlocking {
        val c = repo.captures.insertCapture("raw", "text")
        val x1 = extraction(c.id)
        repo.records.replaceRecordsFor(c.id, x1, listOf(RecordBundle(record(c.id, "keep1", title = "keep me"))))
        val before = db.openHelper.readableDatabase.rows("SELECT * FROM record")
        // two new records with the same id: the second insert throws after the old ones were already deleted
        try {
            repo.records.replaceRecordsFor(c.id, extraction(c.id), listOf(RecordBundle(record(c.id, "dup")), RecordBundle(record(c.id, "dup"))))
            fail("expected the write to fail")
        } catch (e: Exception) {
            // expected
        }
        assertEquals(before, db.openHelper.readableDatabase.rows("SELECT * FROM record"))
        assertEquals(1, db.extractions().forCapture(c.id).size)
        assertEquals(ExtractionStatus.CURRENT, db.extractions().forCapture(c.id).single().status)
        assertEquals(1, db.openHelper.readableDatabase.rows("SELECT * FROM search_fts WHERE doc_type = 'RECORD'").size)
    }

    // ---------- search ----------

    @Test fun searchFindsCapturesByTextAndRecordsByAliasesAndNotDeletedOnes() = runBlocking {
        val c = repo.captures.insertCapture("raw", "the zebra crossing is near the library")
        repo.records.replaceRecordsFor(c.id, extraction(c.id), listOf(RecordBundle(record(c.id, "r1", title = "crossing", quote = "q", aliases = "[\"quokka\",\"wombat\"]"))))
        val byText = repo.index.search("zebra")
        assertEquals(listOf(c.id), byText.map { it.captureId }.distinct()); assertTrue(byText.any { it.docId == c.id })
        val byAlias = repo.index.search("quokka")
        assertEquals(listOf("r1"), byAlias.map { it.docId }); assertEquals(c.id, byAlias.single().captureId)
        assertTrue(repo.index.search("librar").isNotEmpty()) // prefix + stemming
        assertTrue(repo.index.search("   ").isEmpty())
        repo.captures.softDelete(c.id)
        assertTrue(repo.index.search("zebra").isEmpty()); assertTrue(repo.index.search("quokka").isEmpty())
        repo.captures.restore(c.id)
        assertFalse(repo.index.search("quokka").isEmpty())
    }

    @Test fun rebuildAndRepairRestoreTheIndex() = runBlocking {
        val c = repo.captures.insertCapture("raw", "alpha beta")
        repo.records.replaceRecordsFor(c.id, extraction(c.id), listOf(RecordBundle(record(c.id, "r1"))))
        assertTrue(repo.consistency.run().isEmpty())
        db.search().clear() // break the index
        assertEquals(2, repo.consistency.run().size)
        assertTrue(repo.consistency.repair().isEmpty())
        assertTrue(repo.index.search("alpha").isNotEmpty())
    }

    // ---------- delete, defined once ----------

    @Test fun purgeLeavesNoTraceOfTheWordsAndOneTombstone() = runBlocking {
        val marker = "zyxwvmarker"
        val keep = repo.captures.insertCapture("keep raw", "keep this one")
        val c = repo.saveNote("i put the $marker in the garage", null)
        val gone = repo.captures.live().first { it.capture.id == c.id }
        val rid = gone.records.single().record.id
        // a subject only this capture used, and a revision and a reading
        val subj = SubjectEntity("s1", SubjectType.ITEM, marker, marker, createdAt = 1, updatedAt = 1)
        db.subjects().insert(subj)
        db.records().update(db.records().get(rid)!!.copy(subjectId = "s1"))
        repo.captures.updateText(c.id, "i put the $marker in the shed")
        db.metrics().insert(MetricEntity("m1", "s1", "k", marker, createdAt = 1, updatedAt = 1))
        db.readings().insert(ReadingEntity("rd1", rid, "s1", "m1", marker, null, null, 1, 1, 1))
        repo.index.indexRecord(db.records().get(rid)!!)
        repo.captures.softDelete(c.id)
        clock.advance(31L * 24 * 3600_000)
        assertEquals(1, repo.purgeDeleted(30))

        val raw = db.openHelper.readableDatabase
        assertEquals(1, raw.count("capture")); assertEquals(keep.id, raw.rows("SELECT id FROM capture").single()["id"])
        val tomb = db.tombstones().all().single()
        assertEquals("capture", tomb.tableName); assertEquals(c.id, tomb.rowId)
        for (table in listOf("extraction", "record", "task", "whereabouts", "reading", "metric", "subject", "revision", "place_location")) {
            assertEquals("$table should be empty", 0, raw.count(table))
        }
        assertEquals(0, db.openHelper.readableDatabase.rows("SELECT * FROM search_fts WHERE doc_id NOT IN ('${keep.id}')").size)
        assertTrue(repo.index.search(marker).isEmpty())
        assertNull(findInAnyColumn(raw, marker))
        assertTrue(repo.consistency.run().isEmpty())
    }

    @Test fun purgeKeepsSubjectsStillUsedAndRelinksHistory() = runBlocking {
        val a = repo.saveNote("i put the ladder in the shed", null); clock.advance()
        val b = repo.saveNote("i put the ladder in the garage", null); clock.advance()
        val c = repo.saveNote("i put the ladder on the hook", null)
        val recB = db.records().forCapture(b.id).single()
        val recA = db.records().forCapture(a.id).single()
        assertEquals(recB.id, db.records().forCapture(c.id).single().supersedesRecordId)
        db.subjects().insert(SubjectEntity("shared", SubjectType.ITEM, "ladder", "ladder", createdAt = 1, updatedAt = 1))
        db.records().update(db.records().get(recA.id)!!.copy(subjectId = "shared"))
        db.records().update(db.records().get(recB.id)!!.copy(subjectId = "shared"))
        assertTrue(repo.captures.purge(b.id))
        // c now supersedes a (the chain skips the removed note)
        assertEquals(recA.id, db.records().forCapture(c.id).single().supersedesRecordId)
        assertEquals(listOf("shared"), db.openHelper.readableDatabase.rows("SELECT id FROM subject").map { it["id"] })
        assertFalse(repo.captures.purge(b.id)) // already gone
    }

    @Test fun purgeOlderThanOnlyTakesWhatHasBeenInTheBinLongEnough() = runBlocking {
        val old = repo.captures.insertCapture("a", "old one"); val recent = repo.captures.insertCapture("b", "recent one")
        val live = repo.captures.insertCapture("c", "live one")
        repo.captures.softDelete(old.id)
        clock.advance(20L * 24 * 3600_000)
        repo.captures.softDelete(recent.id)
        clock.advance(15L * 24 * 3600_000)
        assertEquals(1, repo.captures.purgeDeletedOlderThan(30))
        assertNull(repo.captures.get(old.id)); assertNotNull(repo.captures.get(recent.id)); assertNotNull(repo.captures.get(live.id))
    }

    /** Looks through every text and blob value of every table (shadow tables of the search index included). */
    private fun findInAnyColumn(raw: SupportSQLiteDatabase, needle: String): String? {
        val tables = raw.rows("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'").map { it["name"] as String }
        for (t in tables) for (row in raw.rows("SELECT * FROM `$t`")) for ((col, v) in row) {
            if (v is String && v.contains(needle, ignoreCase = true)) return "$t.$col"
        }
        return null
    }

    // ---------- event log ----------

    @Test fun eventLogHasNoTextColumn() = runBlocking {
        db.appEvents().insert(AppEventEntity("e1", 5, "capture", "SAVED", 1, null, "ref", 12))
        val cols = db.openHelper.readableDatabase.rows("PRAGMA table_info(app_event)").map { it["name"] }
        assertEquals(listOf("id", "at", "component", "code", "n1", "n2", "ref_id", "ms"), cols)
        assertEquals("SAVED", db.appEvents().newest(5).single().code)
    }

    // ---------- the debug receiver stays out of release builds ----------

    @Test fun theDumpReceiverLivesOnlyInTheDebugSourceSet() {
        assertTrue(File("src/debug/java/app/stash/debug/DumpReceiver.kt").exists())
        val inMain = File("src/main").walkTopDown().filter { it.isFile }.filter { it.name.contains("DumpReceiver") || (it.extension in setOf("kt", "xml") && it.readText().contains("DumpReceiver")) }.toList()
        assertTrue("DumpReceiver found under src/main: $inMain", inMain.isEmpty())
    }
}
