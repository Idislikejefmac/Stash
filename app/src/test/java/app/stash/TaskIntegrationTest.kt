package app.stash

import app.stash.capture.Inbound
import app.stash.capture.DirectSink
import app.stash.capture.InboundSink
import app.stash.data.CaptureEntity
import app.stash.data.CaptureSource
import app.stash.data.ClosedHow
import app.stash.data.Confidence
import app.stash.data.DueKind
import app.stash.data.ExtractionEntity
import app.stash.data.ExtractionStatus
import app.stash.data.RecordBundle
import app.stash.data.RecordEntity
import app.stash.data.RevisionKind
import app.stash.data.StashDatabase
import app.stash.data.Store
import app.stash.data.TaskEntity
import app.stash.data.TaskItems
import app.stash.data.TaskState
import app.stash.domain.ExtractionJson
import app.stash.domain.ExtractedRecord
import app.stash.domain.CloseSetting
import app.stash.domain.RecordKind
import app.stash.domain.SendableText
import app.stash.model.ExtractContext
import app.stash.model.ExtractResult
import app.stash.model.Extractor
import app.stash.model.rules.RulesExtractor
import app.stash.pipeline.ExtractionPipeline
import app.stash.pipeline.MemoryForceList
import app.stash.pipeline.TaskHook
import app.stash.pipeline.TaskUpkeep
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
import java.time.LocalDate
import java.time.ZoneOffset

/** Card 05 against a real (in-memory) Room database: the owner's actions, the hook after reading, and the daily upkeep. */
@RunWith(RobolectricTestRunner::class)
class TaskIntegrationTest {
    private lateinit var db: StashDatabase
    private lateinit var repo: app.stash.data.Repository
    private val clock = FakeClock(1_794_398_400_000L)
    private var setting = CloseSetting.WHEN_SURE
    private var hosted: Extractor? = null
    private val sink get() = DirectSink(repo.captures, { emptyList() }, afterSave = {})

    private fun pipeline() = ExtractionPipeline(
        store = { Store.Ready(db) }, rules = RulesExtractor(), hostedExtractor = { hosted }, hostedOn = { true },
        hasKey = { hosted != null }, networkAvailable = { true },
        hooks = { listOf(TaskHook({ Store.Ready(db) }, { setting }, clock, zone = { ZoneOffset.UTC })) },
        force = MemoryForceList(), clock = clock, zone = { ZoneOffset.UTC },
    )

    @Before fun setUp() {
        db = TestSupport.memoryDb()
        repo = TestSupport.repo(db, clock)
    }

    @After fun tearDown() = db.close()

    private suspend fun say(text: String): CaptureEntity {
        clock.advance(60_000)
        val r = sink.accept(Inbound.Capture.now(text, text, CaptureSource.VOICE, true, null, "test", clock, "UTC"))
        val c = (r as InboundSink.Result.Saved).capture
        pipeline().runPass()
        return c
    }

    private suspend fun taskOf(c: CaptureEntity): Pair<RecordEntity, TaskEntity> {
        val r = db.records().forCapture(c.id).first { it.kind == RecordKind.TASK }
        return r to db.tasks().get(r.id)!!
    }

    /** A hosted model that says the event finishes the first open task, with the given confidence. */
    private fun claimingModel(conf: Confidence) = object : Extractor {
        override val name = "fake-model"
        override suspend fun extract(text: SendableText, context: ExtractContext): ExtractResult {
            val label = context.openTasks.firstOrNull()?.label
            val recs = listOf(ExtractedRecord(RecordKind.EVENT, "Cleaned out the car", text.value, closes = label, sure = conf))
            return ExtractResult.Ok(recs, ExtractionJson.serialise(recs), tokensIn = 1, tokensOut = 1)
        }
    }

    // ------------------------------------------------------------------ what he says

    @Test fun aTaskSaidSoonIsOpenAndSoon() = runBlocking {
        val c = say("I have to remember to clean out the back of the car soon")
        val (_, t) = taskOf(c)
        assertEquals(TaskState.OPEN, t.state)
        assertEquals(DueKind.SOON, t.dueKind)
    }

    @Test fun withoutAModelAnEventAsksAndTheTaskStaysOpen() = runBlocking {
        val c = say("I have to remember to clean out the back of the car soon")
        val (taskRecord, _) = taskOf(c)
        val e = say("I cleaned out the car")
        val event = db.records().forCapture(e.id).single()
        assertEquals(RecordKind.EVENT, event.kind)
        assertEquals("DID_THIS_FINISH:" + taskRecord.id, event.attention)
        assertEquals(TaskState.OPEN, db.tasks().get(taskRecord.id)!!.state)
    }

    @Test fun aSureModelClosesTheTaskBySpeakingAndBothRecordsArePinned() = runBlocking {
        val c = say("I have to remember to clean out the back of the car soon")
        val (taskRecord, _) = taskOf(c)
        hosted = claimingModel(Confidence.HIGH)
        val e = say("I cleaned out the car")
        val event = db.records().forCapture(e.id).single()
        val t = db.tasks().get(taskRecord.id)!!
        assertEquals(TaskState.DONE, t.state)
        assertEquals(ClosedHow.SPOKEN, t.closedHow)
        assertEquals(event.id, t.closedByRecordId)
        assertTrue(event.pinned)
        assertTrue(db.records().get(taskRecord.id)!!.pinned)
        assertTrue(db.revisions().forCapture(e.id).any { it.what == RevisionKind.TASK_DONE && it.recordId == taskRecord.id })
        // The words that closed it can be shown beside the task.
        val items = TaskItems.from(db.captures().live(), includeSensitive = false)
        assertEquals("I cleaned out the car", items.first { it.record.id == taskRecord.id }.closedBy!!.quote)
    }

    @Test fun anUnsureModelOnlyAsks() = runBlocking {
        val c = say("I have to remember to clean out the back of the car soon")
        val (taskRecord, _) = taskOf(c)
        hosted = claimingModel(Confidence.MEDIUM)
        val e = say("I cleaned out the car")
        assertEquals(TaskState.OPEN, db.tasks().get(taskRecord.id)!!.state)
        assertEquals("DID_THIS_FINISH:" + taskRecord.id, db.records().forCapture(e.id).single().attention)
    }

    @Test fun notDoneReopensAClosedTask() = runBlocking {
        val c = say("I have to remember to clean out the back of the car soon")
        val (taskRecord, _) = taskOf(c)
        hosted = claimingModel(Confidence.HIGH)
        say("I cleaned out the car")
        clock.advance()
        repo.tasks.reopen(taskRecord.id)
        val t = db.tasks().get(taskRecord.id)!!
        assertEquals(TaskState.OPEN, t.state)
        assertNull(t.closedHow); assertNull(t.closedByRecordId)
    }

    @Test fun theSettingAnyMatchClosesAndAlwaysAskNeverCloses() = runBlocking {
        val c = say("I have to remember to clean out the back of the car soon")
        val (taskRecord, _) = taskOf(c)
        hosted = claimingModel(Confidence.HIGH)
        setting = CloseSetting.ALWAYS_ASK
        say("I cleaned out the car")
        assertEquals(TaskState.OPEN, db.tasks().get(taskRecord.id)!!.state)
        setting = CloseSetting.ANY_MATCH
        say("I cleaned out the car again")
        assertEquals(TaskState.DONE, db.tasks().get(taskRecord.id)!!.state)
    }

    @Test fun sayingBuyMilkAtCostcoTwiceLeavesOneOpenTask() = runBlocking {
        val a = say("I need to remember to buy milk at Costco")
        val (ra, ta) = taskOf(a)
        val b = say("I need to remember to buy milk at Costco")
        val (rb, tb) = taskOf(b)
        assertEquals(TaskState.OPEN, ta.state)
        assertEquals(TaskState.DROPPED, tb.state)
        assertEquals(ClosedHow.DROPPED, tb.closedHow)
        assertEquals("DUPLICATE_OF:" + ra.id, db.records().get(rb.id)!!.attention)
        assertTrue(ta.lastActiveAt < db.tasks().get(ra.id)!!.lastActiveAt) // bumped
        // The capture is still the truth: both records are there.
        assertEquals(2, db.captures().live().size)
        // And exactly one is on the list.
        val open = TaskItems.from(db.captures().live(), includeSensitive = false).filter { it.task.state == TaskState.OPEN }
        assertEquals(1, open.size)
    }

    @Test fun aMilkTaskAtAnotherShopIsNotADuplicate() = runBlocking {
        say("I need to remember to buy milk at Costco")
        val b = say("I need to remember to buy milk at Safeway")
        assertEquals(TaskState.OPEN, taskOf(b).second.state)
    }

    // ------------------------------------------------------------------ private notes

    @Test fun aPrivateEventNeverAsksAboutAPublicTaskAndTheOtherWayRound() = runBlocking {
        val pub = say("I have to remember to clean out the back of the car soon")
        val (publicTask, _) = taskOf(pub)
        val priv = say("private note I cleaned out the car")
        assertTrue(db.captures().get(priv.id)!!.sensitive)
        assertNull(db.records().forCapture(priv.id).single().attention)
        assertEquals(TaskState.OPEN, db.tasks().get(publicTask.id)!!.state)

        val privTask = say("private note I have to remember to wash the dog soon")
        val publicEvent = say("I washed the dog")
        assertNull(db.records().forCapture(publicEvent.id).single().attention)
        assertEquals(TaskState.OPEN, taskOf(privTask).second.state)
    }

    @Test fun privateTasksAreNotDrawnOnTheListOrInTheAnswerInput() = runBlocking {
        say("private note I have to remember to wash the dog soon")
        say("I have to remember to buy stamps soon")
        val shown = TaskItems.from(db.captures().live(), includeSensitive = false)
        assertEquals(1, shown.size)
        assertFalse(shown.any { it.capture.sensitive })
        assertEquals(2, TaskItems.from(db.captures().live(), includeSensitive = true).size)
    }

    // ------------------------------------------------------------------ the owner's actions

    @Test fun everyOwnerActionPinsTheRecordWritesARevisionAndCanBeUndone() = runBlocking {
        val c = say("I have to remember to clean out the back of the car soon")
        val (r, _) = taskOf(c)
        assertFalse(r.pinned)

        val before = repo.tasks.markDone(r.id)!!
        assertEquals(TaskState.DONE, db.tasks().get(r.id)!!.state)
        assertEquals(ClosedHow.TAP, db.tasks().get(r.id)!!.closedHow)
        assertTrue(db.records().get(r.id)!!.pinned)
        repo.tasks.restore(before)
        assertEquals(TaskState.OPEN, db.tasks().get(r.id)!!.state)

        val b2 = repo.tasks.snooze(r.id, LocalDate.of(2026, 11, 14))!!
        assertEquals(TaskState.SNOOZED, db.tasks().get(r.id)!!.state)
        repo.tasks.restore(b2)
        assertEquals(TaskState.OPEN, db.tasks().get(r.id)!!.state)

        val b3 = repo.tasks.drop(r.id)!!
        assertEquals(TaskState.DROPPED, db.tasks().get(r.id)!!.state)
        repo.tasks.restore(b3)
        assertEquals(TaskState.OPEN, db.tasks().get(r.id)!!.state)

        val kinds = db.revisions().forCapture(c.id).map { it.what }
        assertTrue(RevisionKind.TASK_DONE in kinds && RevisionKind.SNOOZED in kinds && RevisionKind.DROPPED in kinds)
        assertTrue(kinds.count { it == RevisionKind.TASK_REOPENED } >= 3)
        // A revision holds states and dates, never the words.
        assertTrue(db.revisions().forCapture(c.id).none { it.oldValue.contains("car") || it.newValue.contains("car") })
    }

    @Test fun aPinnedTaskSurvivesReadingTheCaptureAgain() = runBlocking {
        val c = say("I have to remember to clean out the back of the car soon")
        val (r, _) = taskOf(c)
        repo.tasks.markDone(r.id)
        repo.captures.requestReread(c.id)
        pipeline().runPass()
        assertEquals(TaskState.DONE, db.tasks().get(r.id)!!.state)
    }

    // ------------------------------------------------------------------ upkeep

    private suspend fun seed(text: String, task: TaskEntity, daysAgo: Long = 0): Pair<CaptureEntity, RecordEntity> {
        val at = clock.now() - daysAgo * 86_400_000L
        val cap = repo.captures.insertCapture(text, text, createdAt = at)
        val ex = ExtractionEntity("x-${cap.id}", cap.id, at, "rules-2", 1, "h", "{}", ExtractionStatus.CURRENT)
        val rec = RecordEntity(
            id = "r-${cap.id}", captureId = cap.id, extractionId = ex.id, seq = 0, kind = RecordKind.TASK, title = text, quote = text,
            createdAt = at, updatedAt = at,
        )
        repo.records.replaceRecordsFor(cap.id, ex, listOf(RecordBundle(rec, task = task.copy(recordId = rec.id))))
        return cap to db.records().forCapture(cap.id).single()
    }

    private fun base(active: Long) = TaskEntity("x", lastActiveAt = active, createdAt = active, updatedAt = active)

    private fun dayOf(ms: Long) = LocalDate.ofEpochDay(Math.floorDiv(ms, 86_400_000L))

    @Test fun upkeepWakesTheSnoozedExpiresTheForgottenAndRecomputesExpiry() = runBlocking {
        val now = clock.now()
        val today = dayOf(now)
        val (_, due) = seed("snoozed and due", base(now).copy(state = TaskState.SNOOZED, snoozedUntil = today.toString()))
        val (_, later) = seed("snoozed for later", base(now).copy(state = TaskState.SNOOZED, snoozedUntil = today.plusDays(9).toString()))
        val (_, forgotten) = seed("old forgotten", base(now - 100 * 86_400_000L), daysAgo = 100)
        val (_, fresh) = seed("fresh", base(now))
        val result = TaskUpkeep.run({ Store.Ready(db) }, clock, zone = { ZoneOffset.UTC }, today = { today })
        assertTrue(result.storeAvailable)
        assertEquals(1, result.woke)
        assertEquals(1, result.expired)
        assertEquals(TaskState.OPEN, db.tasks().get(due.id)!!.state)
        assertEquals(TaskState.SNOOZED, db.tasks().get(later.id)!!.state)
        val gone = db.tasks().get(forgotten.id)!!
        assertEquals(TaskState.EXPIRED, gone.state)
        assertEquals(ClosedHow.EXPIRED, gone.closedHow)
        assertEquals(now - 100 * 86_400_000L, gone.lastActiveAt) // expiring is not activity
        assertEquals(today.plusDays(90).toString(), db.tasks().get(fresh.id)!!.expiresOn)
        // The snooze extended the later one's life: ninety days from its snooze date.
        assertEquals(today.plusDays(9 + 90).toString(), db.tasks().get(later.id)!!.expiresOn)
        // Expiring is not deleting: the record is still there, and "Bring back" works.
        assertNotNull(db.records().get(forgotten.id))
        repo.tasks.bringBack(forgotten.id)
        assertEquals(TaskState.OPEN, db.tasks().get(forgotten.id)!!.state)
    }

    @Test fun upkeepReallyDeletesNotesThatHaveBeenInTheBinThirtyDays() = runBlocking {
        val (_, bin) = seed("in the bin", base(clock.now()))
        val (_, kept) = seed("kept", base(clock.now()))
        repo.captures.softDelete(bin.captureId)
        clock.advance(29 * 86_400_000L)
        TaskUpkeep.run({ Store.Ready(db) }, clock, zone = { ZoneOffset.UTC }, today = { dayOf(clock.now()) })
        assertNotNull(db.captures().get(bin.captureId))
        clock.advance(2 * 86_400_000L)
        val r = TaskUpkeep.run({ Store.Ready(db) }, clock, zone = { ZoneOffset.UTC }, today = { dayOf(clock.now()) })
        assertEquals(1, r.purged)
        assertNull(db.captures().get(bin.captureId))
        assertNotNull(db.captures().get(kept.captureId))
    }

    @Test fun upkeepWithNoDatabaseSaysSoAndChangesNothing() = runBlocking {
        val r = TaskUpkeep.run({ Store.NotAvailable }, clock)
        assertFalse(r.storeAvailable)
    }
}
