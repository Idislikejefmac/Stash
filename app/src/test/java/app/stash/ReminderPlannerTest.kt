package app.stash

import app.stash.data.CaptureEntity
import app.stash.data.ClosedHow
import app.stash.data.DueKind
import app.stash.data.NudgeLogEntity
import app.stash.data.NudgeOutcome
import app.stash.data.NudgeType
import app.stash.data.RecordEntity
import app.stash.data.TaskEntity
import app.stash.data.TaskItem
import app.stash.data.TaskState
import app.stash.domain.AlarmKind
import app.stash.domain.AlarmTimes
import app.stash.domain.BudgetClass
import app.stash.domain.DailyDigest
import app.stash.domain.DigestPlanner
import app.stash.domain.LoanFact
import app.stash.domain.NotificationBudget
import app.stash.domain.NudgePlan
import app.stash.domain.NudgeSettings
import app.stash.domain.NudgeSweep
import app.stash.domain.PostedNudge
import app.stash.domain.RecordIds
import app.stash.domain.RecordKind
import app.stash.domain.ReminderText
import app.stash.domain.StoredPlan
import app.stash.domain.Verdict
import app.stash.domain.WeeklyReview
import app.stash.domain.WeeklySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** Card 06: the pure planners, the budget and the sweep, all with a fixed clock. */
class ReminderPlannerTest {
    private val zone = ZoneOffset.UTC
    private val wed = LocalDate.of(2026, 11, 11) // a Wednesday
    private val s = NudgeSettings()

    private fun at(day: LocalDate, h: Int, m: Int = 0): Long = day.atTime(h, m).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun task(id: String = "t", build: TaskEntity.() -> TaskEntity = { this }) =
        TaskEntity(id, lastActiveAt = 1L, createdAt = 1L, updatedAt = 1L).build()

    private fun item(id: String, title: String, t: TaskEntity, sensitive: Boolean = false, createdAt: Long = at(wed.minusDays(10), 9)): TaskItem {
        val cap = CaptureEntity(id = "c-$id", createdAt = createdAt, updatedAt = createdAt, rawText = title, text = title, sensitive = sensitive)
        val rec = RecordEntity(
            id = id, captureId = cap.id, extractionId = "x", seq = 0, kind = RecordKind.TASK, title = title, quote = title,
            createdAt = createdAt, updatedAt = createdAt,
        )
        return TaskItem(t.copy(recordId = id), rec, cap)
    }

    private fun log(id: String, type: NudgeType, at: Long, outcome: NudgeOutcome = NudgeOutcome.SHOWN, ids: List<String> = emptyList()) =
        NudgeLogEntity(id, at, type, RecordIds.encode(ids), outcome)

    // ---------------------------------------------------------------- A: first and next nudges

    @Test fun firstNudgeForEveryDueKind() {
        val cap = wed
        assertEquals(cap.plusDays(2), NudgePlan.firstNudgeOn(task { copy(dueKind = DueKind.SOON) }, cap))
        assertNull(NudgePlan.firstNudgeOn(task { copy(dueKind = DueKind.NONE) }, cap))
        assertEquals(LocalDate.of(2026, 11, 14), NudgePlan.firstNudgeOn(task { copy(dueKind = DueKind.WINDOW, dueFrom = "2026-11-14", dueTo = "2026-11-15") }, cap))
        assertEquals(LocalDate.of(2026, 11, 20), NudgePlan.firstNudgeOn(task { copy(dueKind = DueKind.ON, dueFrom = "2026-11-20") }, cap))
        // BY: halfway, at least a day before.
        assertEquals(LocalDate.of(2026, 11, 15), NudgePlan.firstNudgeOn(task { copy(dueKind = DueKind.BY, dueTo = "2026-11-19") }, cap))
        assertEquals(LocalDate.of(2026, 11, 13), NudgePlan.firstNudgeOn(task { copy(dueKind = DueKind.BY, dueTo = "2026-11-15") }, cap))
        assertEquals(LocalDate.of(2026, 11, 12), NudgePlan.firstNudgeOn(task { copy(dueKind = DueKind.BY, dueTo = "2026-11-14") }, cap))
        // Captured the day before the date: that day.
        assertEquals(cap, NudgePlan.firstNudgeOn(task { copy(dueKind = DueKind.BY, dueTo = cap.plusDays(1).toString()) }, cap))
        // At a place with no date: never by time. With a date: by the date.
        assertNull(NudgePlan.firstNudgeOn(task { copy(dueKind = DueKind.SOON, atPlaceText = "Costco") }, cap))
        assertNull(NudgePlan.firstNudgeOn(task { copy(dueKind = DueKind.NONE, atPlaceText = "Costco") }, cap))
        assertEquals(LocalDate.of(2026, 11, 20), NudgePlan.firstNudgeOn(task { copy(dueKind = DueKind.ON, dueFrom = "2026-11-20", atPlaceText = "Costco") }, cap))
    }

    @Test fun soonTasksGoThrough3_7_14_then30() {
        var t = task { copy(dueKind = DueKind.SOON) }
        var day = wed
        val gaps = mutableListOf<Long>()
        repeat(6) {
            val next = NudgePlan.nextAfter(t, day)!!
            gaps += java.time.temporal.ChronoUnit.DAYS.between(day, next)
            t = t.copy(nudgeCount = t.nudgeCount + 1)
            day = next
        }
        assertEquals(listOf(3L, 7L, 14L, 30L, 30L, 30L), gaps)
    }

    @Test fun nextAfterForDatedKindsAndAWindowThatHasPassed() {
        val win = task { copy(dueKind = DueKind.WINDOW, dueFrom = "2026-11-14", dueTo = "2026-11-15") }
        assertEquals(LocalDate.of(2026, 11, 15), NudgePlan.nextAfter(win, LocalDate.of(2026, 11, 14))) // once more on the last day
        // After the window: "soon" from the 7-day gap.
        assertEquals(LocalDate.of(2026, 11, 22), NudgePlan.nextAfter(win, LocalDate.of(2026, 11, 15)))
        assertEquals(LocalDate.of(2026, 11, 23), NudgePlan.nextAfter(win.copy(nudgeCount = 1), LocalDate.of(2026, 11, 16)))

        val by = task { copy(dueKind = DueKind.BY, dueTo = "2026-11-19") }
        assertEquals(LocalDate.of(2026, 11, 19), NudgePlan.nextAfter(by, LocalDate.of(2026, 11, 15)))
        assertEquals(LocalDate.of(2026, 11, 20), NudgePlan.nextAfter(by, LocalDate.of(2026, 11, 19))) // "was due"
        assertEquals(LocalDate.of(2026, 11, 27), NudgePlan.nextAfter(by, LocalDate.of(2026, 11, 20))) // then 7 days

        val on = task { copy(dueKind = DueKind.ON, dueFrom = "2026-11-20") }
        assertEquals(LocalDate.of(2026, 11, 21), NudgePlan.nextAfter(on, LocalDate.of(2026, 11, 20)))
        assertEquals(LocalDate.of(2026, 11, 28), NudgePlan.nextAfter(on, LocalDate.of(2026, 11, 21)))
    }

    @Test fun quietSnoozedOrClosedTasksHaveNoNextNudge() {
        val soon = task { copy(dueKind = DueKind.SOON) }
        assertNotNull(NudgePlan.nextAfter(soon, wed))
        assertNull(NudgePlan.nextAfter(soon.copy(quiet = true), wed))
        assertNull(NudgePlan.nextAfter(soon.copy(state = TaskState.SNOOZED), wed))
        assertNull(NudgePlan.nextAfter(soon.copy(state = TaskState.DONE), wed))
        assertNull(NudgePlan.nextAfter(task { copy(dueKind = DueKind.NONE) }, wed))
    }

    // ---------------------------------------------------------------- B: what to send

    private fun due(id: String, title: String = "Task $id", sensitive: Boolean = false, build: TaskEntity.() -> TaskEntity = { this }) =
        item(id, title, task { copy(dueKind = DueKind.SOON, nextNudgeOn = wed.toString()).build() }, sensitive)

    @Test fun planDailyNothingDueGivesNull() {
        val items = listOf(item("a", "A", task { copy(dueKind = DueKind.SOON, nextNudgeOn = wed.plusDays(1).toString()) }))
        assertNull(DigestPlanner.planDaily(items, emptyList(), wed, s, zone))
        assertNull(DigestPlanner.planDaily(emptyList(), emptyList(), wed, s, zone))
    }

    @Test fun planDailySixDueListsFiveAndCountsOneMore() {
        val items = (1..6).map { due("t$it") }
        val d: DailyDigest = DigestPlanner.planDaily(items, emptyList(), wed, s, zone)!!
        assertEquals(5, d.lines.size)
        assertEquals(1, d.more)
        assertEquals(6, d.total)
    }

    @Test fun planDailyOnTheWeeklyDayIsNullAndOnlyWhenTheReviewIsOn() {
        val sunday = LocalDate.of(2026, 11, 15)
        val items = listOf(due("a"))
        assertNull(DigestPlanner.planDaily(items, emptyList(), sunday, s, zone))
        assertNotNull(DigestPlanner.planDaily(items, emptyList(), sunday, s.copy(weeklyOn = false), zone))
    }

    @Test fun planDailyLeavesOutQuietPausedOffAndAlreadySent() {
        assertNull(DigestPlanner.planDaily(listOf(due("a") { copy(quiet = true) }), emptyList(), wed, s, zone))
        assertNull(DigestPlanner.planDaily(listOf(due("a")), emptyList(), wed, s.copy(dailyAutoPaused = true), zone))
        assertNull(DigestPlanner.planDaily(listOf(due("a")), emptyList(), wed, s.copy(dailyOn = false), zone))
        assertNull(DigestPlanner.planDaily(listOf(due("a")), listOf(log("l", NudgeType.DAILY, at(wed, 8, 30))), wed, s, zone))
        val mixed = DigestPlanner.planDaily(listOf(due("a") { copy(quiet = true) }, due("b")), emptyList(), wed, s, zone)!!
        assertEquals(listOf("b"), mixed.recordIds)
    }

    @Test fun aPrivateTaskChangesNoPlannersOutputNotEvenCounts() {
        val open = listOf(due("a"), due("b"))
        val withPrivate = open + due("p", "Secret thing", sensitive = true) + item(
            "p2", "Secret due today", task { copy(dueKind = DueKind.ON, dueFrom = wed.toString(), nextNudgeOn = wed.toString()) }, sensitive = true,
        ) + item("p3", "Secret done", task { copy(state = TaskState.DONE, closedAt = at(wed, 7), closedHow = ClosedHow.SPOKEN) }, sensitive = true)
        assertEquals(
            DigestPlanner.planDaily(open, emptyList(), wed, s, zone),
            DigestPlanner.planDaily(withPrivate, emptyList(), wed, s, zone),
        )
        assertEquals(DigestPlanner.planDueToday(open, emptyList(), wed, s, zone), DigestPlanner.planDueToday(withPrivate, emptyList(), wed, s, zone))
        val loan = LoanFact("L", at(wed.minusDays(60), 9), null, sensitive = true)
        val a = DigestPlanner.planWeekly(WeeklySnapshot(open, emptyList(), 0), wed, at(wed, 17), s, false)
        val b = DigestPlanner.planWeekly(WeeklySnapshot(withPrivate, listOf(loan), 0), wed, at(wed, 17), s, false)
        assertEquals(a, b)
        assertFalse(b.spokenDoneTitles.contains("Secret done"))
        // Nothing but private tasks: nothing to say at all.
        assertNull(DigestPlanner.planDaily(withPrivate.filter { it.capture.sensitive }, emptyList(), wed, s, zone))
        assertTrue(DigestPlanner.planWeekly(WeeklySnapshot(withPrivate.filter { it.capture.sensitive }, listOf(loan), 0), wed, at(wed, 17), s, false).isEmpty)
    }

    @Test fun planDueTodayPicksTheMostPressingAndOnlyOnePerDay() {
        val on = item("on", "Pay rent", task { copy(dueKind = DueKind.ON, dueFrom = wed.toString()) })
        val windowEnds = item("w", "Pool test", task { copy(dueKind = DueKind.WINDOW, dueFrom = wed.minusDays(1).toString(), dueTo = wed.toString()) })
        val byLater = item("b", "Later one", task { copy(dueKind = DueKind.BY, dueTo = wed.plusDays(3).toString()) })
        assertTrue(DigestPlanner.planDueToday(listOf(byLater, windowEnds, on), emptyList(), wed, s, zone)!!.recordId in setOf("on", "w"))
        assertNull(DigestPlanner.planDueToday(listOf(byLater), emptyList(), wed, s, zone))
        val sent = listOf(log("d", NudgeType.DUE, at(wed, 16)))
        assertNull(DigestPlanner.planDueToday(listOf(on), sent, wed, s, zone))
        // Done tasks are not due.
        assertNull(DigestPlanner.planDueToday(listOf(on.copy(task = on.task.copy(state = TaskState.DONE))), emptyList(), wed, s, zone))
    }

    @Test fun laterTodayIsPickedUpByTheAfternoonCheckOnlyOnADigestDay() {
        val later = due("later")
        assertEquals("later", DigestPlanner.planDueToday(listOf(later), emptyList(), wed, s, zone)!!.recordId)
        // The morning digest already named it: not again.
        val named = listOf(log("d", NudgeType.DAILY, at(wed, 8, 30), ids = listOf("later")))
        assertNull(DigestPlanner.planDueToday(listOf(later), named, wed, s, zone))
        assertNull(DigestPlanner.planDueToday(listOf(later), emptyList(), wed, s, zone, pickups = false))
    }

    @Test fun weeklyReviewCountsAndSkipsWhenAllZero() {
        val now = at(wed, 17)
        val open = item("o", "Open one", task { copy(dueKind = DueKind.SOON, expiresOn = wed.plusDays(5).toString()) })
        val spoken = item("s", "Cleaned the car", task { copy(state = TaskState.DONE, closedHow = ClosedHow.SPOKEN, closedAt = now - 2 * 86_400_000L) })
        val tapped = item("t", "Called mum", task { copy(state = TaskState.DONE, closedHow = ClosedHow.TAP, closedAt = now - 3 * 86_400_000L) })
        val old = item("old", "Done long ago", task { copy(state = TaskState.DONE, closedHow = ClosedHow.TAP, closedAt = now - 20 * 86_400_000L) })
        val loans = listOf(
            LoanFact("l1", now - 40 * 86_400_000L, null, false), // older than 30 days
            LoanFact("l2", now - 2 * 86_400_000L, wed.minusDays(1).toString(), false), // past due back
            LoanFact("l3", now - 2 * 86_400_000L, wed.plusDays(5).toString(), false), // fine
        )
        val r: WeeklyReview = DigestPlanner.planWeekly(WeeklySnapshot(listOf(open, spoken, tapped, old), loans, 3), wed, now, s, false)
        assertEquals(1, r.openCount)
        assertEquals(2, r.doneCount)
        assertEquals(listOf("Cleaned the car"), r.spokenDoneTitles)
        assertEquals(1, r.stoppingSoonCount)
        assertEquals(2, r.loanCount)
        assertEquals(3, r.attentionCount)
        assertFalse(r.isEmpty)
        assertTrue(DigestPlanner.planWeekly(WeeklySnapshot(emptyList(), emptyList(), 0), wed, now, s, false).isEmpty)
        // Counts only in the lines the notification may carry: no titles.
        assertTrue(r.lines().none { it.text.contains("Cleaned the car") })
    }

    // ---------------------------------------------------------------- budget

    private fun p(day: LocalDate, h: Int, m: Int, type: NudgeType) = PostedNudge(at(day, h, m), type)

    @Test fun aThirdScheduledNotificationIsNeverAllowed() {
        val log = listOf(p(wed, 8, 30, NudgeType.DAILY), p(wed, 16, 0, NudgeType.DUE))
        assertEquals(Verdict.DROP, NotificationBudget.check(NudgeType.DUE, at(wed, 17), log, s, zone))
        assertEquals(Verdict.DROP, NotificationBudget.check(NudgeType.WEEKLY, at(wed, 17), log, s, zone))
        assertEquals(Verdict.ALLOW, NotificationBudget.check(NudgeType.DAILY, at(wed.plusDays(1), 8, 30), log, s, zone))
    }

    @Test fun theWeeklyReviewCountsAsTheDaysDigest() {
        val sunday = LocalDate.of(2026, 11, 15)
        assertEquals(Verdict.DROP, NotificationBudget.check(NudgeType.DAILY, at(sunday, 17, 5), listOf(p(sunday, 8, 30, NudgeType.WEEKLY)), s, zone))
        assertEquals(Verdict.DROP, NotificationBudget.check(NudgeType.WEEKLY, at(sunday, 17), listOf(p(sunday, 8, 30, NudgeType.DAILY)), s, zone))
        assertEquals(Verdict.ALLOW, NotificationBudget.check(NudgeType.DUE, at(sunday, 16), listOf(p(sunday, 8, 30, NudgeType.WEEKLY)), s, zone))
    }

    @Test fun aThirdEventAndAFifthOfAnyKindAreNeverAllowed() {
        val two = listOf(p(wed, 9, 0, NudgeType.ARRIVAL), p(wed, 12, 0, NudgeType.LEAVING))
        assertEquals(Verdict.DROP, NotificationBudget.check(NudgeType.ARRIVAL, at(wed, 15), two, s, zone))
        val four = listOf(p(wed, 8, 30, NudgeType.DAILY), p(wed, 16, 0, NudgeType.DUE), p(wed, 9, 0, NudgeType.ARRIVAL), p(wed, 12, 0, NudgeType.LEAVING))
        assertEquals(Verdict.DROP, NotificationBudget.check(NudgeType.WATCH, at(wed, 18), four, s, zone))
        assertEquals(Verdict.DROP, NotificationBudget.check(NudgeType.DUE, at(wed, 18), four, s, zone))
    }

    @Test fun twoEventPostsTenMinutesApartRefuseTheSecond() {
        val one = listOf(p(wed, 10, 0, NudgeType.ARRIVAL))
        assertEquals(Verdict.DROP, NotificationBudget.check(NudgeType.LEAVING, at(wed, 10, 10), one, s, zone))
        assertEquals(Verdict.ALLOW, NotificationBudget.check(NudgeType.LEAVING, at(wed, 10, 30), one, s, zone))
        assertFalse(NotificationBudget.mayPost(NudgeType.LEAVING, at(wed, 10, 10), one, s, zone))
        assertEquals(BudgetClass.EVENT, BudgetClass.of(NudgeType.WATCH))
    }

    @Test fun quietHoursHold() {
        assertEquals(Verdict.WAIT, NotificationBudget.check(NudgeType.DAILY, at(wed, 7, 59), emptyList(), s, zone))
        assertEquals(Verdict.ALLOW, NotificationBudget.check(NudgeType.DAILY, at(wed, 8, 0), emptyList(), s, zone))
        assertEquals(Verdict.WAIT, NotificationBudget.check(NudgeType.DUE, at(wed, 21, 0), emptyList(), s, zone))
        assertEquals(Verdict.DROP, NotificationBudget.check(NudgeType.ARRIVAL, at(wed, 22, 0), emptyList(), s, zone))
        assertEquals(Verdict.WAIT, NotificationBudget.check(NudgeType.WATCH, at(wed, 23, 0), emptyList(), s, zone))
        assertTrue(s.isQuiet(LocalTime.of(2, 0)))
        assertFalse(s.isQuiet(LocalTime.of(12, 0)))
    }

    // ---------------------------------------------------------------- ignored, backing off, pausing

    @Test fun ignoredDigestsRaiseCountsThreeMakeQuietAnActionResets() {
        var t = task("a") { copy(dueKind = DueKind.SOON) }
        val day = wed
        var log = mutableListOf<NudgeLogEntity>()
        for (i in 0 until 3) {
            log += log("l$i", NudgeType.DAILY, at(day.plusDays(i.toLong()), 8, 30), ids = listOf("a"))
            val r = NudgeSweep.sweep(log, mapOf("a" to t), at(day.plusDays(i.toLong() + 1), 9))
            assertEquals(NudgeOutcome.IGNORED, r.outcomes["l$i"])
            t = r.tasks.single()
            log = log.map { it.copy(outcome = r.outcomes[it.id] ?: it.outcome) }.toMutableList()
            assertEquals(i + 1, t.ignoredInARow)
            assertEquals(i == 2, t.quiet)
        }
        // Within 24 hours: nothing yet. Opened: not ignored. Acted on: counts as acted.
        val fresh = log("f", NudgeType.DAILY, at(wed.plusDays(5), 8, 30), ids = listOf("a"))
        assertTrue(NudgeSweep.sweep(listOf(fresh), mapOf("a" to t), at(wed.plusDays(5), 20)).outcomes.isEmpty())
        assertTrue(NudgeSweep.sweep(listOf(fresh.copy(outcome = NudgeOutcome.OPENED)), mapOf("a" to t), at(wed.plusDays(7), 20)).outcomes.isEmpty())
        val acted = NudgeSweep.sweep(listOf(fresh), mapOf("a" to t.copy(lastActiveAt = fresh.at + 1000)), at(wed.plusDays(7), 20))
        assertEquals(NudgeOutcome.ACTED, acted.outcomes["f"])
        assertTrue(acted.tasks.isEmpty())
    }

    @Test fun fiveIgnoredOrDismissedDailyDigestsPauseTheDigestAndAnOpenOneBreaksTheRun() {
        val ignored = (0 until 5).map { log("d$it", NudgeType.DAILY, at(wed.plusDays(it.toLong()), 8, 30), if (it % 2 == 0) NudgeOutcome.IGNORED else NudgeOutcome.DISMISSED) }
        assertTrue(NudgeSweep.shouldAutoPause(ignored, 0L))
        assertFalse(NudgeSweep.shouldAutoPause(ignored.take(4), 0L))
        assertFalse(NudgeSweep.shouldAutoPause(ignored.map { if (it.id == "d2") it.copy(outcome = NudgeOutcome.OPENED) else it }, 0L))
        // Weekly entries do not count; turning the digest back on forgets the old ones.
        assertFalse(NudgeSweep.shouldAutoPause(ignored + log("w", NudgeType.DAILY, at(wed.plusDays(6), 8, 30), NudgeOutcome.OPENED), 0L))
        assertFalse(NudgeSweep.shouldAutoPause(ignored, at(wed.plusDays(10), 0)))
    }

    @Test fun thePausedLineIsOnTheReviewOnlyWhilePendingAndPaused() {
        val snap = WeeklySnapshot(emptyList(), emptyList(), 0)
        val pausedSettings = s.copy(dailyAutoPaused = true)
        val r = DigestPlanner.planWeekly(snap, wed, at(wed, 17), pausedSettings, noticePending = true)
        assertTrue(r.lines().any { it.text == WeeklyReview.PAUSED_LINE })
        assertFalse(r.isEmpty) // the line alone is worth a review
        assertFalse(DigestPlanner.planWeekly(snap, wed, at(wed, 17), pausedSettings, noticePending = false).lines().any { it.text == WeeklyReview.PAUSED_LINE })
        assertFalse(DigestPlanner.planWeekly(snap, wed, at(wed, 17), s, noticePending = true).lines().any { it.text == WeeklyReview.PAUSED_LINE })
    }

    // ---------------------------------------------------------------- alarms and words

    @Test fun alarmTimesAreTheNextOccurrence() {
        val now = at(wed, 9, 0)
        assertEquals(LocalDate.of(2026, 11, 12), AlarmTimes.next(AlarmKind.DAILY, now, s, zone).toLocalDate())
        assertEquals(wed, AlarmTimes.next(AlarmKind.AFTERNOON, now, s, zone).toLocalDate())
        val weekly = AlarmTimes.next(AlarmKind.WEEKLY, now, s, zone)
        assertEquals(DayOfWeek.SUNDAY, weekly.dayOfWeek)
        assertEquals(LocalDate.of(2026, 11, 15), weekly.toLocalDate())
        assertEquals(LocalTime.of(17, 0), weekly.toLocalTime())
        // Sunday after 17:00 goes to the next Sunday.
        assertEquals(LocalDate.of(2026, 11, 22), AlarmTimes.next(AlarmKind.WEEKLY, at(LocalDate.of(2026, 11, 15), 17, 1), s, zone).toLocalDate())
    }

    @Test fun publicWordsAreCountsAndCalm() {
        assertEquals("You have 3 things you wanted to do.", ReminderText.dailyPublic(3))
        assertEquals("You have 1 thing you wanted to do.", ReminderText.dailyPublic(1))
        val daily = StoredPlan(AlarmKind.DAILY, wed.toString(), 3, listOf("a", "b", "c"))
        assertEquals("You have 3 things you wanted to do.", ReminderText.publicText(daily, "Secret", showFirst = false))
        assertEquals("Your weekly review is ready.", ReminderText.publicText(StoredPlan(AlarmKind.WEEKLY, wed.toString(), 2), "Secret", true))
        assertEquals("Something you wanted to do is due today.", ReminderText.publicText(StoredPlan(AlarmKind.AFTERNOON, wed.toString(), 1, listOf("a")), "Secret", false))
        assertTrue(ReminderText.publicText(daily, "Water plants", showFirst = true).contains("Water plants"))
        assertEquals(daily, StoredPlan.fromJson(daily.toJson()))
        for (text in listOf(ReminderText.dailyPublic(2), ReminderText.WEEKLY_PUBLIC, ReminderText.DUE_PUBLIC)) {
            assertFalse(text.contains('!'))
        }
    }

    // ---------------------------------------------------------------- a simulated month

    /** One notification the simulation posted. */
    private data class Sim(val day: LocalDate, val type: NudgeType, val ids: List<String>)

    private fun simulate(
        tasksIn: List<TaskEntity>,
        titles: Map<String, String>,
        days: Int,
        settings: NudgeSettings = s,
        owner: (LocalDate, NudgeType) -> NudgeOutcome,
    ): List<Sim> {
        var tasks = tasksIn.associateBy { it.recordId }.toMutableMap()
        val log = mutableListOf<NudgeLogEntity>()
        val posts = mutableListOf<Sim>()
        var cfg = settings
        var n = 0
        fun items() = tasks.values.map { item(it.recordId, titles.getValue(it.recordId), it) }
        fun post(day: LocalDate, h: Int, m: Int, type: NudgeType, ids: List<String>): Boolean {
            val now = at(day, h, m)
            val ledger = log.map { PostedNudge(it.at, it.type) }
            if (!NotificationBudget.mayPost(type, now, ledger, cfg, zone)) return false
            val out = owner(day, type)
            log += NudgeLogEntity("n${n++}", now, type, RecordIds.encode(ids), out)
            posts += Sim(day, type, ids)
            for (id in ids) {
                val t = tasks[id] ?: continue
                if (t.state == TaskState.OPEN) tasks[id] = t.copy(nextNudgeOn = NudgePlan.nextAfter(t, day)?.toString(), nudgeCount = t.nudgeCount + 1)
            }
            return true
        }
        for (d in 0 until days) {
            val day = wed.plusDays(d.toLong())
            // The 24-hour sweep, then the owner's pause setting.
            val r = NudgeSweep.sweep(log, tasks, at(day, 8, 0))
            for ((i, o) in r.outcomes) log.replaceAll { if (it.id == i) it.copy(outcome = o) else it }
            for (t in r.tasks) tasks[t.recordId] = t
            if (!cfg.dailyAutoPaused && NudgeSweep.shouldAutoPause(log, cfg.dailyResumedAt)) cfg = cfg.copy(dailyAutoPaused = true)
            DigestPlanner.planDaily(items(), log, day, cfg, zone)?.let { post(day, 8, 30, NudgeType.DAILY, it.recordIds) }
            DigestPlanner.planDueToday(items(), log, day, cfg, zone)?.let { post(day, 16, 0, NudgeType.DUE, listOf(it.recordId)) }
            if (day.dayOfWeek == cfg.weeklyDay && cfg.weeklyOn) {
                val review = DigestPlanner.planWeekly(WeeklySnapshot(items(), emptyList(), 0), day, at(day, 17), cfg, false)
                if (!review.isEmpty) post(day, 17, 0, NudgeType.WEEKLY, emptyList())
            }
            val perDay = posts.count { it.day == day }
            assertTrue("day $day had $perDay notifications", perDay <= 2)
        }
        return posts
    }

    private val carTask = TaskEntity("car", dueKind = DueKind.SOON, nextNudgeOn = wed.plusDays(2).toString(), lastActiveAt = 1, createdAt = 1, updatedAt = 1)
    // "Pool test this weekend", said on the Wednesday: Saturday and Sunday.
    private val poolTask = TaskEntity(
        "pool", dueKind = DueKind.WINDOW, dueFrom = "2026-11-14", dueTo = "2026-11-15", nextNudgeOn = "2026-11-14",
        lastActiveAt = 1, createdAt = 1, updatedAt = 1,
    )
    private val titles = mapOf("car" to "Clean out the back of the car", "pool" to "Do the pool test")

    @Test fun aSimulatedMonthOfAnOwnerWhoOpensEverything() {
        val posts = simulate(listOf(carTask, poolTask), titles, 30) { _, _ -> NudgeOutcome.OPENED }
        // Car "soon": days 2, 5, 12, 26 after the Wednesday (gaps 3, 7, 14). Pool: Saturday's digest; the last day (a Sunday)
        // is the weekly day, so the afternoon check carries it and the weekly review follows; then 7-day gaps.
        val byType = posts.groupBy { it.type }.mapValues { it.value.size }
        println("month: $byType, days: ${posts.map { "${it.day.dayOfMonth}${it.type.name.first()}" }}")
        assertEquals(posts.size, posts.count { it.day in wed..wed.plusDays(29) })
        assertEquals(listOf(LocalDate.of(2026, 11, 13)), posts.filter { it.type == NudgeType.DAILY && "car" in it.ids }.map { it.day }.take(1))
        // The pool test is named on the Saturday, reminded again on its last day, and not before.
        assertEquals(LocalDate.of(2026, 11, 14), posts.first { "pool" in it.ids }.day)
        assertTrue(posts.any { it.type == NudgeType.DUE && "pool" in it.ids && it.day == LocalDate.of(2026, 11, 15) })
        // Four weekly reviews at most (Sundays), each the day's digest.
        assertTrue(posts.count { it.type == NudgeType.WEEKLY } in 1..5)
        // Plain upper bound for the month: far fewer than one a day.
        assertTrue("too many: ${posts.size}", posts.size <= 20)
    }

    @Test fun aSimulatedMonthOfAnOwnerWhoIgnoresEverythingBacksOffAndPauses() {
        val onlyCar = simulate(listOf(carTask), mapOf("car" to "Clean out the back of the car"), 30) { _, t ->
            if (t == NudgeType.DAILY) NudgeOutcome.SHOWN else NudgeOutcome.OPENED
        }
        val daily = onlyCar.filter { it.type == NudgeType.DAILY }
        // One task, ignored: it is mentioned three times, then it goes quiet.
        assertEquals(3, daily.size)
        // Many tasks, all ignored: five digests, then the daily reminder stops itself.
        val many = (1..8).associate { "t$it" to TaskEntity("t$it", dueKind = DueKind.SOON, nextNudgeOn = wed.plusDays(1).toString(), lastActiveAt = 1, createdAt = 1, updatedAt = 1) }
        val posts = simulate(many.values.toList(), many.keys.associateWith { "Task $it" }, 40) { _, t ->
            if (t == NudgeType.DAILY) NudgeOutcome.SHOWN else NudgeOutcome.OPENED
        }
        assertTrue("the digest should have stopped, got ${posts.count { it.type == NudgeType.DAILY }}", posts.count { it.type == NudgeType.DAILY } in 3..5)
    }
}
