package app.stash

import app.stash.data.CaptureEntity
import app.stash.data.CaptureSource
import app.stash.data.ExtractState
import app.stash.data.ExtractionRunner
import app.stash.data.ExtractionStatus
import app.stash.data.PhotoHint
import app.stash.data.Repository
import app.stash.data.StashDatabase
import app.stash.domain.Attention
import app.stash.domain.ExtractedRecord
import app.stash.domain.ExtractionJson
import app.stash.domain.RecordKind
import app.stash.model.ExtractContext
import app.stash.model.ExtractResult
import app.stash.model.rules.RulesExtractor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneOffset

/** The extraction core against a real (in-memory) Room database: what is stored, and what must never be lost. */
@RunWith(RobolectricTestRunner::class)
class ExtractionRunnerTest {
    private lateinit var db: StashDatabase
    private lateinit var repo: Repository
    private lateinit var runner: ExtractionRunner
    private val clock = FakeClock(1_794_398_400_000L) // 2026-11-11 12:00 UTC, a Wednesday
    private val rules = RulesExtractor()

    @Before fun setUp() {
        db = TestSupport.memoryDb()
        repo = TestSupport.repo(db, clock)
        runner = ExtractionRunner(db, repo.records, clock) { ZoneOffset.UTC }
    }

    @After fun tearDown() = db.close()

    private suspend fun capture(text: String, sensitive: Boolean = false): CaptureEntity {
        clock.advance(60_000)
        return repo.captures.insertCapture(rawText = text, text = text, source = CaptureSource.VOICE, sensitive = sensitive)
    }

    private suspend fun extract(c: CaptureEntity) = runner.runLocal(c.id, rules)

    private suspend fun recordsOf(c: CaptureEntity) = db.records().forCapture(c.id)

    @Test fun aCaptureBecomesRecordsAndIsMarkedDone() = runBlocking {
        val c = capture("I need to remember to buy ground beef at Costco")
        val out = extract(c)
        assertTrue(out is ExtractionRunner.Outcome.Stored)
        val r = recordsOf(c).single()
        assertEquals(RecordKind.TASK, r.kind)
        val t = db.tasks().get(r.id)!!
        assertEquals("Costco", t.atPlaceText)
        assertNotNull(t.atPlaceSubjectId)
        assertEquals(ExtractState.DONE, db.captures().get(c.id)!!.extractState)
        val x = db.extractions().forCapture(c.id).single()
        assertEquals("rules-2", x.extractor)
        assertEquals(1, x.schemaVersion)
        assertEquals(ExtractionStatus.CURRENT, x.status)
        assertEquals(Repository.sha256(c.text), x.textHash)
        assertEquals(1, ExtractionJson.parse(x.outputJson)!!.size)
        assertEquals(x.id, r.extractionId)
        // The capture's words are untouched.
        assertEquals(c.text, db.captures().get(c.id)!!.text)
        assertEquals(c.rawText, db.captures().get(c.id)!!.rawText)
    }

    @Test fun theOwnersSentencesComeOutRightThroughTheWholeChain() = runBlocking {
        val candle = capture("I'm just looking at my warm tobacco pipe candle and I want to remember to purchase one the next time I go to Ross")
        val finish = capture("Finish adding in the features for the app development")
        val update = capture("I am updating the app for stash right now")
        val pool = capture("I just tested the pool water, free chlorine 5, pH 7.2, alkalinity 7")
        val tide = capture("the Tide is on sale for 24.99 at Costco")
        for (c in listOf(candle, finish, update, pool, tide)) assertTrue(extract(c) is ExtractionRunner.Outcome.Stored)

        val cr = recordsOf(candle).single()
        assertEquals(RecordKind.TASK, cr.kind)
        assertNull(cr.attention)
        assertEquals("Ross", db.tasks().get(cr.id)!!.atPlaceText)
        assertEquals("warm tobacco pipe candle", db.subjects().nameOf(cr.subjectId!!))
        assertEquals(RecordKind.TASK, recordsOf(finish).single().kind)
        assertEquals(RecordKind.EVENT, recordsOf(update).single().kind)
        assertNull(db.tasks().get(recordsOf(update).single().id))
        val pr = recordsOf(pool).single()
        assertEquals(RecordKind.READING, pr.kind)
        assertNull(pr.attention)
        assertEquals(3, db.readings().refs().count { it.recordId == pr.id })
        assertEquals(listOf("alkalinity", "free chlorine", "pH"), db.readings().metricLabelsFor(pr.id).sorted())
        assertEquals(RecordKind.READING, recordsOf(tide).single().kind)
        assertEquals(0, ConsistencyRun.violations(repo))
    }

    @Test fun searchFindsTheNewRecordsByTheirSubject() = runBlocking {
        val c = capture("I need to remember to buy ground beef at Costco")
        extract(c)
        val hits = repo.index.search("beef")
        assertTrue(hits.any { it.docType.name == "RECORD" })
    }

    @Test fun readingAgainReplacesUnpinnedRecordsAndKeepsOneCurrentExtraction() = runBlocking {
        val c = capture("I need to remember to buy milk at Costco")
        extract(c)
        val first = recordsOf(c).single()
        extract(c)
        val second = recordsOf(c).single()
        assertTrue(first.id != second.id)
        val xs = db.extractions().forCapture(c.id)
        assertEquals(2, xs.size)
        assertEquals(1, xs.count { it.status == ExtractionStatus.CURRENT })
        assertEquals(1, xs.count { it.status == ExtractionStatus.REPLACED })
        // Same subjects again, not duplicates.
        assertEquals(1, db.subjects().all().count { it.normName == "costco" })
        assertEquals(1, db.subjects().all().count { it.normName == "milk" })
        assertEquals(0, ConsistencyRun.violations(repo))
    }

    @Test fun readingAgainNeverRemovesAPinnedRecord() = runBlocking {
        val c = capture("I need to remember to buy milk at Costco")
        extract(c)
        val mine = recordsOf(c).single()
        repo.records.pin(mine.id)
        extract(c)
        val now = recordsOf(c)
        assertTrue(now.any { it.id == mine.id && it.pinned })
        assertNotNull(db.tasks().get(mine.id)) // its side row stays too
        assertTrue(db.revisions().forCapture(c.id).any { it.what.name == "PINNED" })
    }

    @Test fun anExtractorThatThrowsLeavesTheCaptureAndOldRecordsIntact() = runBlocking {
        val c = capture("I need to remember to buy milk at Costco")
        extract(c)
        val before = recordsOf(c)
        val out = runner.run(c.id, "boom") { _, _ -> throw IllegalStateException("kaboom with words I need to remember") }
        assertTrue(out is ExtractionRunner.Outcome.Failed)
        assertTrue((out as ExtractionRunner.Outcome.Failed).retryable)
        assertEquals(before, recordsOf(c))
        val cap = db.captures().get(c.id)!!
        assertEquals(c.text, cap.text)
        assertEquals(ExtractState.RETRY, cap.extractState)
        assertEquals(1, cap.extractAttempts)
        val failed = db.extractions().forCapture(c.id).single { it.status == ExtractionStatus.FAILED }
        assertEquals("IllegalStateException", failed.error) // a code, never the message or the words
        assertEquals(1, db.extractions().forCapture(c.id).count { it.status == ExtractionStatus.CURRENT })
    }

    @Test fun repeatedFailuresGiveUpAfterThreeAndAnUnretryableFailureGivesUpAtOnce() = runBlocking {
        val c = capture("something")
        repeat(3) { runner.run(c.id, "x") { _, _ -> ExtractResult.Failed("offline", retryable = true) } }
        assertEquals(ExtractState.GAVE_UP, db.captures().get(c.id)!!.extractState)
        assertEquals(3, db.captures().get(c.id)!!.extractAttempts)
        val d = capture("other")
        runner.run(d.id, "x") { _, _ -> ExtractResult.Failed("bad key", retryable = false) }
        assertEquals(ExtractState.GAVE_UP, db.captures().get(d.id)!!.extractState)
        assertTrue(recordsOf(c).isEmpty())
    }

    @Test fun aSuccessfulReadAfterFailuresMarksTheCaptureDone() = runBlocking {
        val c = capture("I need to remember to buy milk at Costco")
        runner.run(c.id, "x") { _, _ -> ExtractResult.Failed("offline", retryable = true) }
        assertEquals(ExtractState.RETRY, db.captures().get(c.id)!!.extractState)
        extract(c)
        assertEquals(ExtractState.DONE, db.captures().get(c.id)!!.extractState)
    }

    @Test fun aFailedWriteRollsBackEverythingIncludingNewSubjects() = runBlocking {
        val c = capture("Remember to buy milk at Costco pH 7")
        extract(c)
        val before = recordsOf(c)
        val subjectsBefore = db.subjects().all().size
        val extractionsBefore = db.extractions().forCapture(c.id).map { it.id to it.status }
        // A database rule that refuses any new reading makes the write fail after the records were already replaced.
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER refuse_reading BEFORE INSERT ON reading BEGIN SELECT RAISE(ABORT, 'no'); END")
        val bad = ExtractedRecord(
            RecordKind.READING, "Pool", "pH 7", subject = "pool",
            readings = listOf(app.stash.domain.ExtractedReading("pH", "7", 7.0)),
        )
        val out = runner.run(c.id, "x") { _, _ -> ExtractResult.Ok(listOf(bad), "{}") }
        assertTrue(out is ExtractionRunner.Outcome.Failed)
        // Nothing changed: same records, no new subject, no half-written extraction, old one still current.
        assertEquals(before, recordsOf(c))
        assertEquals(subjectsBefore, db.subjects().all().size)
        assertEquals(extractionsBefore, db.extractions().forCapture(c.id).filter { it.status != ExtractionStatus.FAILED }.map { it.id to it.status })
        assertEquals(c.text, db.captures().get(c.id)!!.text)
        assertEquals(ExtractState.RETRY, db.captures().get(c.id)!!.extractState)
        assertNotNull(db.tasks().get(before.single().id))
    }

    @Test fun aCaptureThatIsDeletedOrMissingIsNotRead() = runBlocking {
        assertTrue(runner.runLocal("nope", rules) is ExtractionRunner.Outcome.NoSuchCapture)
        val c = capture("buy milk")
        repo.deleteCapture(c.id)
        assertTrue(runner.runLocal(c.id, rules) is ExtractionRunner.Outcome.NoSuchCapture)
        assertTrue(db.extractions().forCapture(c.id).isEmpty())
    }

    @Test fun emptyOrUnreadableTextStillGivesOneOtherRecord() = runBlocking {
        val c = capture("Uh huh")
        extract(c)
        val r = recordsOf(c).single()
        assertEquals(RecordKind.OTHER, r.kind)
        assertEquals("Uh huh", r.quote)
    }

    @Test fun aBadModelQuoteIsKeptAsAReferenceWithAReason() = runBlocking {
        val c = capture("I need to remember to buy milk")
        val bad = ExtractedRecord(RecordKind.TASK, "Buy bread", "buy bread", item = "bread")
        runner.run(c.id, "fake") { _, _ -> ExtractResult.Ok(listOf(bad), ExtractionJson.serialise(listOf(bad))) }
        val r = recordsOf(c).single()
        assertEquals(RecordKind.REFERENCE, r.kind)
        assertEquals(Attention.BAD_QUOTE, r.attention)
        assertNull(db.tasks().get(r.id))
        // The model's raw output is kept as it came.
        assertTrue(db.extractions().forCapture(c.id).single().outputJson.contains("buy bread"))
    }

    @Test fun supersessionAcrossCapturesPinsTheOlderRecord() = runBlocking {
        val a = capture("I'm putting my reading glasses in the nightstand drawer")
        extract(a)
        val b = capture("I'm putting my reading glasses in the kitchen")
        extract(b)
        val ra = recordsOf(a).single()
        val rb = recordsOf(b).single()
        assertEquals(ra.id, rb.supersedesRecordId)
        assertTrue(db.records().get(ra.id)!!.pinned)
        assertEquals(1, db.subjects().all().count { it.normName == "reading glass" })
        assertEquals(ra.subjectId, rb.subjectId)
        // A third note supersedes the second, not the first.
        val c = capture("I'm putting my reading glasses in the car")
        extract(c)
        assertEquals(rb.id, recordsOf(c).single().supersedesRecordId)
        assertEquals(0, ConsistencyRun.violations(repo))
    }

    @Test fun readingTheLaterCaptureAgainKeepsItsPlaceInTheChain() = runBlocking {
        val a = capture("I'm putting my reading glasses in the nightstand drawer")
        extract(a)
        val b = capture("I'm putting my reading glasses in the kitchen")
        extract(b)
        extract(b)
        assertEquals(recordsOf(a).single().id, recordsOf(b).single().supersedesRecordId)
    }

    @Test fun aReturnedNoteMarksTheOpenLoan() = runBlocking {
        val a = capture("I loaned my guitar to Frank on January fifth")
        extract(a)
        val b = capture("Frank gave my guitar back")
        extract(b)
        val loan = db.whereabouts().get(recordsOf(a).single().id)!!
        assertEquals(db.captures().get(b.id)!!.createdAt, loan.returnedAt)
        assertTrue(db.records().get(recordsOf(a).single().id)!!.pinned)
        assertEquals("RETURNED", db.whereabouts().get(recordsOf(b).single().id)!!.direction.name)
        assertNull(recordsOf(b).single().supersedesRecordId)
    }

    @Test fun poolNotesShareOneSubjectAndThreeMetrics() = runBlocking {
        val a = capture("Pool test free chlorine 5 pH 7.2 alkalinity 70")
        val b = capture("Pool test free chlorine 3 pH 7.4 alkalinity 80")
        extract(a); extract(b)
        assertEquals(1, db.subjects().all().count { it.normName == "pool" })
        assertEquals(3, db.metrics().all().size)
        assertEquals(6, db.readings().refs().size)
        assertEquals(0, ConsistencyRun.violations(repo))
    }

    @Test fun subjectsFromAPrivateCaptureArePrivateAndTheContextHidesThem() = runBlocking {
        val c = capture("Private note: Pool test free chlorine 5 pH 7.2", sensitive = true)
        extract(c)
        assertTrue(db.subjects().all().all { it.sensitive })
        var seen: ExtractContext? = null
        val d = capture("I put the keys in the bowl")
        runner.run(d.id, "spy") { _, ctx -> seen = ctx; ExtractResult.Ok(emptyList(), "{}") }
        assertTrue(seen!!.subjects.isEmpty())
        assertTrue(seen!!.openTasks.isEmpty())
    }

    @Test fun openTasksGetLabelsInTheContextAndClosesIsStoredNotActedOn() = runBlocking {
        val t = capture("I have to remember to clean out the back of the car soon")
        extract(t)
        val taskRecord = recordsOf(t).single()
        val e = capture("I cleaned out the back of the car")
        var ctxSeen: ExtractContext? = null
        runner.run(e.id, "fake") { cap, ctx ->
            ctxSeen = ctx
            val r = ExtractedRecord(RecordKind.EVENT, "Cleaned out the car", "I cleaned out the back of the car", closes = "T1")
            ExtractResult.Ok(listOf(r), ExtractionJson.serialise(listOf(r)))
        }
        assertEquals("T1", ctxSeen!!.openTasks.single().label)
        val er = recordsOf(e).single()
        assertEquals(RecordKind.EVENT, er.kind)
        assertNull(er.attention)
        assertEquals(taskRecord.id, er.claimsToCloseRecordId)
        assertEquals("OPEN", db.tasks().get(taskRecord.id)!!.state.name) // card 05 decides about closing
    }

    @Test fun aPhotoHintSetsThePendingFlagOnTheCapture() = runBlocking {
        val c = capture("It's a Valpolicella by Allegrini, in the Italian section, row 5, bucket 10")
        extract(c)
        assertEquals(PhotoHint.PENDING, db.captures().get(c.id)!!.photoHint)
        val d = capture("I cleaned out the car")
        extract(d)
        assertEquals(PhotoHint.NONE, db.captures().get(d.id)!!.photoHint)
    }

    @Test fun datesUseTheDayTheCaptureWasMade() = runBlocking {
        val c = capture("Remember to call the dentist tomorrow")
        extract(c)
        clock.advance(10 * 24 * 3600_000L) // read again ten days later
        extract(c)
        val t = db.tasks().get(recordsOf(c).single().id)!!
        assertEquals("2026-11-12", t.dueFrom)
        assertFalse(t.dueText.isNullOrBlank())
    }
}

/** Runs the consistency check and returns the number of violations (a healthy database has none). */
private object ConsistencyRun {
    suspend fun violations(repo: Repository): Int = repo.consistency.run().size
}
