package app.stash

import android.app.Notification
import app.stash.data.DueKind
import app.stash.data.ExtractionEntity
import app.stash.data.ExtractionStatus
import app.stash.data.NudgeOutcome
import app.stash.data.NudgeType
import app.stash.data.RecordBundle
import app.stash.data.RecordEntity
import app.stash.data.StashDatabase
import app.stash.data.Store
import app.stash.data.TaskEntity
import app.stash.data.TaskRepository
import app.stash.domain.AlarmKind
import app.stash.domain.NudgeSettings
import app.stash.domain.PostedNudge
import app.stash.domain.RecordKind
import app.stash.domain.StoredPlan
import app.stash.domain.WeeklyReview
import app.stash.reminders.NotificationPoster
import app.stash.reminders.NotificationSpec
import app.stash.reminders.Notifier
import app.stash.reminders.PlanStore
import app.stash.reminders.PostLedger
import app.stash.reminders.PostResult
import app.stash.reminders.ReminderEngine
import app.stash.reminders.ReminderSettingsAccess
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

private class MemoryPlans : PlanStore, PostLedger {
    val plans = mutableMapOf<AlarmKind, StoredPlan>()
    val posts = mutableListOf<PostedNudge>()
    override fun get(kind: AlarmKind) = plans[kind]
    override fun put(plan: StoredPlan) { plans[plan.kind] = plan }
    override fun clear(kind: AlarmKind) { plans.remove(kind) }
    override fun recent() = posts.toList()
    override fun add(p: PostedNudge) { posts += p }
}

/** Card 06: the alarm path and the sweep against a real in-memory database, and the notifications as the phone would build them. */
@RunWith(RobolectricTestRunner::class)
class ReminderEngineTest {
    private lateinit var db: StashDatabase
    private lateinit var repo: app.stash.data.Repository
    private val day = LocalDate.of(2026, 11, 13) // a Friday
    private val clock = FakeClock(day.atTime(8, 30).toInstant(ZoneOffset.UTC).toEpochMilli())
    private val mem = MemoryPlans()
    private val posted = mutableListOf<NotificationSpec>()
    private var settings = NudgeSettings()
    private var paused = false
    private var noticePending = false
    private var on = true

    private val access = ReminderSettingsAccess(
        get = { settings.copy(dailyAutoPaused = paused || settings.dailyAutoPaused) },
        autoPause = { paused = true; noticePending = true },
        noticePending = { noticePending },
        noticeShown = { noticePending = false },
    )

    private fun engine(store: () -> Store = { Store.Ready(db) }) = ReminderEngine(
        store = store, plans = mem, ledger = mem, poster = NotificationPoster { posted += it }, settings = access,
        enabled = { on }, tasks = { TaskRepository(it, clock, zone = { ZoneOffset.UTC }) }, clock = clock, zone = { ZoneOffset.UTC },
    )

    @Before fun setUp() {
        db = TestSupport.memoryDb()
        repo = TestSupport.repo(db, clock)
    }

    @After fun tearDown() = db.close()

    private fun base(due: DueKind = DueKind.SOON) =
        TaskEntity("x", dueKind = due, nextNudgeOn = day.toString(), lastActiveAt = clock.now() - 10 * 86_400_000L, createdAt = 1, updatedAt = 1)

    private suspend fun seed(title: String, task: TaskEntity = base(), sensitive: Boolean = false): String {
        val cap = repo.captures.insertCapture(title, title, sensitive = sensitive, createdAt = clock.now() - 10 * 86_400_000L)
        val ex = ExtractionEntity("x-${cap.id}", cap.id, clock.now(), "rules-2", 1, "h", "{}", ExtractionStatus.CURRENT)
        val rec = RecordEntity(
            id = "r-${cap.id}", captureId = cap.id, extractionId = ex.id, seq = 0, kind = RecordKind.TASK, title = title, quote = title,
            createdAt = clock.now(), updatedAt = clock.now(),
        )
        repo.records.replaceRecordsFor(cap.id, ex, listOf(RecordBundle(rec, task = task.copy(recordId = rec.id))))
        return rec.id
    }

    private fun closedStore(): () -> Store = { throw IllegalStateException("the database must not be opened") }

    @Test fun theAlarmPostsThePublicTextFromAStoredPlanWithTheDatabaseClosed() = runBlocking {
        mem.put(StoredPlan(AlarmKind.DAILY, day.toString(), 3, listOf("a", "b", "c")))
        val r = engine(closedStore()).onAlarm(AlarmKind.DAILY)
        assertEquals(PostResult.POSTED, r)
        val spec = posted.single()
        assertEquals("You have 3 things you wanted to do.", spec.publicText)
        assertNull(spec.lines) // no titles without the database
        assertNull(spec.actionRecordId)
        assertEquals(1, mem.posts.size) // the budget ledger knows, with no database
        // A second one the same day is held by the budget only after two; a third is refused.
        mem.put(StoredPlan(AlarmKind.AFTERNOON, day.toString(), 1, listOf("a")))
        clock.t = day.atTime(16, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(PostResult.POSTED, engine(closedStore()).onAlarm(AlarmKind.AFTERNOON))
        mem.put(StoredPlan(AlarmKind.WEEKLY, day.toString(), 2, lines = listOf("x")))
        assertEquals(PostResult.REFUSED, engine(closedStore()).onAlarm(AlarmKind.WEEKLY))
        assertEquals(2, posted.size)
        assertEquals("Something you wanted to do is due today.", posted[1].publicText)
    }

    @Test fun aStalePlanOrNoPlanPostsNothingWithTheDatabaseClosed() = runBlocking {
        mem.put(StoredPlan(AlarmKind.DAILY, day.minusDays(1).toString(), 3, listOf("a")))
        assertEquals(PostResult.NOTHING_TO_SAY, engine(closedStore()).onAlarm(AlarmKind.DAILY))
        assertEquals(PostResult.NOTHING_TO_SAY, engine(closedStore()).onAlarm(AlarmKind.WEEKLY))
        assertTrue(posted.isEmpty())
        on = false
        mem.put(StoredPlan(AlarmKind.DAILY, day.toString(), 1, listOf("a")))
        assertEquals(PostResult.OFF, engine().onAlarm(AlarmKind.DAILY))
    }

    @Test fun replanThenAlarmPostsAndWritesTheLogAndMovesTheNudgeOn() = runBlocking {
        val car = seed("Clean out the car")
        val pool = seed("Do the pool test", base(DueKind.NONE).copy(nextNudgeOn = null))
        seed("A secret task", sensitive = true)
        engine().replan()
        val plan = mem.get(AlarmKind.DAILY)!!
        assertEquals(listOf(car), plan.recordIds) // the private one is not even counted; NONE is never due
        assertEquals(1, plan.count)

        // The 08:30 alarm: the unlocked version has the title, the public version only the count.
        assertEquals(PostResult.POSTED, engine().onAlarm(AlarmKind.DAILY))
        val spec = posted.single()
        assertEquals("You have 1 thing you wanted to do.", spec.publicText)
        assertEquals(listOf("Clean out the car"), spec.lines)
        assertFalse(spec.publicText.contains("car"))

        val log = db.nudgeLog().since(0)
        assertEquals(1, log.size)
        assertEquals(NudgeType.DAILY, log[0].type)
        assertEquals(NudgeOutcome.SHOWN, log[0].outcome)
        val t = db.tasks().get(car)!!
        assertEquals(day.plusDays(3).toString(), t.nextNudgeOn) // gap of 3 days
        assertEquals(1, t.nudgeCount)
        assertEquals(clock.now() - 10 * 86_400_000L, t.lastActiveAt) // not an owner action
        assertNull(mem.get(AlarmKind.DAILY)) // and nothing more to say tomorrow
        assertNotNull(db.tasks().get(pool))

        // Opened, then acted: outcomes only go forward.
        val id = log[0].id
        engine().outcome(id, NudgeOutcome.OPENED)
        assertEquals(NudgeOutcome.OPENED, db.nudgeLog().get(id)!!.outcome)
        engine().outcome(id, NudgeOutcome.DISMISSED)
        assertEquals(NudgeOutcome.OPENED, db.nudgeLog().get(id)!!.outcome)
        engine().outcome(id, NudgeOutcome.ACTED)
        assertEquals(NudgeOutcome.ACTED, db.nudgeLog().get(id)!!.outcome)
    }

    @Test fun doneAndLaterFromTheNotificationChangeTheTask() = runBlocking {
        val a = seed("Pay the plumber")
        val b = seed("Call the vet")
        mem.put(StoredPlan(AlarmKind.AFTERNOON, day.toString(), 1, listOf(a)))
        clock.t = day.atTime(16, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        engine().onAlarm(AlarmKind.AFTERNOON)
        val postId = posted.single().postId
        assertEquals(a, posted.single().actionRecordId)
        assertTrue(engine().actOnTask(postId, a, done = true))
        assertEquals(app.stash.data.TaskState.DONE, db.tasks().get(a)!!.state)
        assertEquals(NudgeOutcome.ACTED, db.nudgeLog().get(postId)!!.outcome)
        assertTrue(engine().actOnTask(postId, b, done = false))
        val later = db.tasks().get(b)!!
        assertEquals(app.stash.data.TaskState.SNOOZED, later.state)
        assertEquals(day.plusDays(1).toString(), later.snoozedUntil)
    }

    @Test fun ignoredDigestsAreSweptAndFiveOfThemPauseTheDigestAndTheReviewSaysSoOnce() = runBlocking {
        settings = settings.copy(weeklyOn = false) // keep the weekly day out of the five mornings
        val ids = (1..2).map { seed("Task $it") }
        // Five days of digests the owner never opens.
        for (i in 0 until 5) {
            clock.t = day.plusDays(i.toLong()).atTime(8, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
            for (id in ids) db.tasks().update(db.tasks().get(id)!!.copy(nextNudgeOn = day.plusDays(i.toLong()).toString(), quiet = false, ignoredInARow = 0))
            engine().replan()
            assertEquals("day $i", PostResult.POSTED, engine().onAlarm(AlarmKind.DAILY))
        }
        assertFalse(paused)
        clock.t = day.plusDays(6).atTime(8, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        engine().sweep()
        assertTrue("five ignored digests should pause the digest", paused)
        val outcomes = db.nudgeLog().since(0).map { it.outcome }
        assertEquals(5, outcomes.count { it == NudgeOutcome.IGNORED })
        assertTrue(db.tasks().get(ids[0])!!.ignoredInARow >= 1)

        // The weekly review carries the line once, then not again.
        settings = settings.copy(weeklyOn = true, weeklyDay = day.plusDays(6).dayOfWeek)
        clock.t = day.plusDays(6).atTime(17, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        mem.put(StoredPlan(AlarmKind.WEEKLY, day.plusDays(6).toString(), 1, lines = listOf(WeeklyReview.PAUSED_LINE), pausedNotice = true))
        assertEquals(PostResult.POSTED, engine().onAlarm(AlarmKind.WEEKLY))
        assertTrue(posted.last().lines!!.contains(WeeklyReview.PAUSED_LINE))
        assertFalse(noticePending)
        val again = engine().reviewNow()!!
        assertFalse(again.lines().any { it.text == WeeklyReview.PAUSED_LINE })
        // Paused: no more daily plans.
        engine().replan()
        assertNull(mem.get(AlarmKind.DAILY))
    }

    @Test fun newTasksWithoutANudgeDateGetOneOnReplan() = runBlocking {
        val id = seed("Old prototype task", base().copy(nextNudgeOn = null))
        engine().replan()
        assertEquals(day.minusDays(10).plusDays(2).toString(), db.tasks().get(id)!!.nextNudgeOn)
    }

    // ---------------------------------------------------------------- the notifications as built

    private fun spec(kind: AlarmKind, public: String, lines: List<String>?, action: String?) =
        NotificationSpec("post-1", StoredPlan(kind, day.toString(), 2, listOf("a", "b")), public, lines, action)

    @Test fun thePublicVersionHasNoTitleAndNoActionsAndTheActionsAskForTheUnlockedPhone() {
        val notifier = Notifier(TestSupport.context)
        val n = notifier.build(spec(AlarmKind.AFTERNOON, "Something you wanted to do is due today.", listOf("Pay the secretive plumber"), "a"))
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        val public = n.publicVersion!!
        val publicText = public.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertEquals("Something you wanted to do is due today.", publicText)
        assertFalse(publicText.contains("plumber"))
        assertTrue(public.actions == null || public.actions.isEmpty())
        // The private version has the title and the two actions, each needing authentication.
        assertEquals("Pay the secretive plumber", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(listOf("Done", "Later"), n.actions.map { it.title.toString() })
        assertTrue(n.actions.all { it.isAuthenticationRequired })
        assertTrue(listOf(n.extras, public.extras).all { e -> e.keySet().none { k -> e.get(k)?.toString()?.contains("!") == true } })
    }

    @Test fun withoutTitlesTheNotificationIsOnlyTheCountAndHasNoActions() {
        val n = Notifier(TestSupport.context).build(spec(AlarmKind.DAILY, "You have 2 things you wanted to do.", null, null))
        assertEquals("You have 2 things you wanted to do.", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(n.actions == null || n.actions.isEmpty())
        val withLines = Notifier(TestSupport.context).build(spec(AlarmKind.DAILY, "You have 2 things you wanted to do.", listOf("One", "Two"), null))
        assertTrue(withLines.actions == null || withLines.actions.isEmpty()) // actions are only on "due today"
        assertEquals("You have 2 things you wanted to do.", withLines.publicVersion!!.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }
}
