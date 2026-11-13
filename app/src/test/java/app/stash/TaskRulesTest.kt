package app.stash

import app.stash.data.CaptureEntity
import app.stash.data.ClosedHow
import app.stash.data.Confidence
import app.stash.data.DueKind
import app.stash.data.RecordEntity
import app.stash.data.TaskEntity
import app.stash.data.TaskItem
import app.stash.data.TaskState
import app.stash.domain.CloseDecision
import app.stash.domain.CloseSetting
import app.stash.domain.DuplicateVerdict
import app.stash.domain.RecordKind
import app.stash.domain.SnoozePreset
import app.stash.domain.SpokenRecord
import app.stash.domain.TaskAnswers
import app.stash.domain.TaskBucket
import app.stash.domain.TaskCloser
import app.stash.domain.TaskCompare
import app.stash.domain.TaskDuplicates
import app.stash.domain.TaskLifetimes
import app.stash.domain.TaskQuestion
import app.stash.domain.TaskQuestions
import app.stash.domain.TaskRules
import app.stash.domain.TaskSections
import app.stash.domain.TaskText
import app.stash.domain.TaskToClose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** Card 05 sections A, B, C and F: pure logic with a fixed clock. 2026-11-11 is a Wednesday. */
class TaskRulesTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 11)
    private fun ms(d: LocalDate) = d.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val now = ms(today)
    private val life = TaskLifetimes()

    private fun task(
        id: String = "t1", state: TaskState = TaskState.OPEN, kind: DueKind = DueKind.NONE, from: String? = null, to: String? = null,
        place: String? = null, perishable: Boolean = false, active: Long = now, snoozed: String? = null,
    ) = TaskEntity(
        recordId = id, state = state, dueKind = kind, dueFrom = from, dueTo = to, atPlaceText = place, perishable = perishable,
        lastActiveAt = active, snoozedUntil = snoozed, ignoredInARow = 2, quiet = true, createdAt = 0, updatedAt = 0,
    )

    // ------------------------------------------------------------------ transitions

    @Test fun doneByTapClosesAndResetsTheNagCounters() {
        val t = TaskRules.done(task(active = now - 5_000), ClosedHow.TAP, null, now)
        assertEquals(TaskState.DONE, t.state)
        assertEquals(ClosedHow.TAP, t.closedHow)
        assertEquals(now, t.closedAt)
        assertEquals(now, t.lastActiveAt)
        assertEquals(0, t.ignoredInARow)
        assertFalse(t.quiet)
        assertNull(t.closedByRecordId)
    }

    @Test fun doneBySpeakingKeepsTheClosingRecord() {
        val t = TaskRules.done(task(), ClosedHow.SPOKEN, "r9", now)
        assertEquals(ClosedHow.SPOKEN, t.closedHow)
        assertEquals("r9", t.closedByRecordId)
    }

    @Test fun reopenUndoesDone() {
        val closed = TaskRules.done(task(), ClosedHow.SPOKEN, "r9", now)
        val back = TaskRules.reopen(closed, now + 10)
        assertEquals(TaskState.OPEN, back.state)
        assertNull(back.closedAt); assertNull(back.closedHow); assertNull(back.closedByRecordId)
        assertEquals(now + 10, back.lastActiveAt)
    }

    @Test fun snoozeSetsTheDateAndResetsCounters() {
        val t = TaskRules.snooze(task(), LocalDate.of(2026, 11, 14), now)
        assertEquals(TaskState.SNOOZED, t.state)
        assertEquals("2026-11-14", t.snoozedUntil)
        assertEquals(0, t.ignoredInARow)
        assertFalse(t.quiet)
        assertEquals(now, t.lastActiveAt)
    }

    @Test fun wakeOnlyWhenTheDateHasCome() {
        val s = TaskRules.snooze(task(), LocalDate.of(2026, 11, 14), now - 1000)
        assertSame(s, TaskRules.wake(s, LocalDate.of(2026, 11, 13), now))
        val woke = TaskRules.wake(s, LocalDate.of(2026, 11, 14), now)
        assertEquals(TaskState.OPEN, woke.state)
        assertNull(woke.snoozedUntil)
        assertEquals(now, woke.lastActiveAt)
        val open = task()
        assertSame(open, TaskRules.wake(open, today, now))
    }

    @Test fun dropAndItsUndo() {
        val d = TaskRules.drop(task(), now)
        assertEquals(TaskState.DROPPED, d.state)
        assertEquals(ClosedHow.DROPPED, d.closedHow)
        assertEquals(0, d.ignoredInARow)
        assertEquals(TaskState.OPEN, TaskRules.reopen(d, now).state)
    }

    @Test fun expireDoesNotCountAsActivityAndBringBackStartsAFreshLife() {
        val old = task(active = now - 100_000)
        val e = TaskRules.expire(old, now)
        assertEquals(TaskState.EXPIRED, e.state)
        assertEquals(ClosedHow.EXPIRED, e.closedHow)
        assertEquals(old.lastActiveAt, e.lastActiveAt)
        val b = TaskRules.bringBack(e, now + 5)
        assertEquals(TaskState.OPEN, b.state)
        assertNull(b.closedHow)
        assertEquals(now + 5, b.lastActiveAt)
    }

    // ------------------------------------------------------------------ expiry

    @Test fun anUndatedTaskLivesNinetyDaysFromItsLastActivity() {
        assertEquals(LocalDate.of(2027, 2, 9), TaskRules.expiresOn(task(), life, zone))
        assertEquals(today.plusDays(90), TaskRules.expiresOn(task(), life, zone))
    }

    @Test fun aTaskAtAPlaceLivesFortyFiveDaysOrFourteenIfPerishable() {
        assertEquals(today.plusDays(45), TaskRules.expiresOn(task(place = "Costco"), life, zone))
        assertEquals(today.plusDays(14), TaskRules.expiresOn(task(place = "Costco", perishable = true), life, zone))
    }

    @Test fun aDatedTaskLivesFourteenDaysPastItsDueDate() {
        assertEquals(LocalDate.of(2026, 11, 29), TaskRules.expiresOn(task(kind = DueKind.ON, from = "2026-11-15"), life, zone))
        assertEquals(LocalDate.of(2026, 11, 24), TaskRules.expiresOn(task(kind = DueKind.BY, to = "2026-11-10"), life, zone))
        assertEquals(LocalDate.of(2026, 11, 30), TaskRules.expiresOn(task(kind = DueKind.WINDOW, from = "2026-11-14", to = "2026-11-16"), life, zone))
    }

    @Test fun aSnoozeExtendsLife() {
        val plain = TaskRules.expiresOn(task(), life, zone)
        val snoozed = TaskRules.snooze(task(), LocalDate.of(2027, 3, 1), now)
        assertTrue(TaskRules.expiresOn(snoozed, life, zone).isAfter(plain))
        assertEquals(LocalDate.of(2027, 3, 1).plusDays(90), TaskRules.expiresOn(snoozed, life, zone))
        val dated = task(kind = DueKind.ON, from = "2026-11-15", snoozed = "2026-12-20")
        assertEquals(LocalDate.of(2027, 1, 3), TaskRules.expiresOn(dated, life, zone))
    }

    @Test fun lifetimesAreSettings() {
        assertEquals(today.plusDays(10), TaskRules.expiresOn(task(), TaskLifetimes(undatedDays = 10), zone))
    }

    @Test fun expiryUsesTheLocalDayOfTheLastActivity() {
        val late = LocalDate.of(2026, 11, 11).atTime(23, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
        val t = task(active = late)
        assertEquals(LocalDate.of(2026, 11, 11).plusDays(90), TaskRules.expiresOn(t, life, ZoneOffset.UTC))
        assertEquals(LocalDate.of(2026, 11, 12).plusDays(90), TaskRules.expiresOn(t, life, ZoneOffset.ofHours(2)))
    }

    // ------------------------------------------------------------------ presets

    @Test fun snoozePresetsResolveToDates() {
        assertEquals(today, SnoozePreset.LATER_TODAY.resolve(today))
        assertEquals(LocalDate.of(2026, 11, 12), SnoozePreset.TOMORROW.resolve(today))
        assertEquals(LocalDate.of(2026, 11, 14), SnoozePreset.THIS_WEEKEND.resolve(today))
        assertEquals(LocalDate.of(2026, 11, 16), SnoozePreset.NEXT_WEEK.resolve(today))
        assertEquals(LocalDate.of(2026, 12, 11), SnoozePreset.IN_A_MONTH.resolve(today))
        // On a Saturday "this weekend" is tomorrow; on a Monday "next week" is a week away.
        assertEquals(LocalDate.of(2026, 11, 15), SnoozePreset.THIS_WEEKEND.resolve(LocalDate.of(2026, 11, 14)))
        assertEquals(LocalDate.of(2026, 11, 23), SnoozePreset.NEXT_WEEK.resolve(LocalDate.of(2026, 11, 16)))
    }

    // ------------------------------------------------------------------ ordering

    @Test fun listOrderIsOverdueThenDatedThenSoonThenPlaceThenTheRest() {
        val a = task("none-old", active = now - 9_000)
        val b = task("none-new", active = now - 1_000)
        val c = task("soon-old", kind = DueKind.SOON, active = now - 8_000)
        val d = task("soon-new", kind = DueKind.SOON, active = now - 2_000)
        val e = task("place", place = "Costco", active = now - 3_000)
        val f = task("dated-late", kind = DueKind.ON, from = "2026-11-20")
        val g = task("dated-soon", kind = DueKind.BY, to = "2026-11-13")
        val h = task("overdue", kind = DueKind.BY, to = "2026-11-01")
        val i = task("overdue-older", kind = DueKind.ON, from = "2026-10-20")
        val sorted = TaskRules.sort(listOf(a, b, c, d, e, f, g, h, i), today).map { it.recordId }
        assertEquals(listOf("overdue-older", "overdue", "dated-soon", "dated-late", "soon-old", "soon-new", "place", "none-old", "none-new"), sorted)
    }

    @Test fun bucketsFollowTheDueKindThenThePlace() {
        assertEquals(TaskBucket.DUE, TaskRules.bucket(task(kind = DueKind.ON, from = "2026-11-12")))
        assertEquals(TaskBucket.SOON, TaskRules.bucket(task(kind = DueKind.SOON, place = "Costco")))
        assertEquals(TaskBucket.PLACE, TaskRules.bucket(task(place = "Costco")))
        assertEquals(TaskBucket.WHENEVER, TaskRules.bucket(task()))
    }

    // ------------------------------------------------------------------ the closer

    private fun open(id: String, title: String, state: TaskState = TaskState.OPEN, subject: String? = null) =
        TaskToClose(id, title, title, state, subject)

    private fun said(
        title: String, kind: RecordKind = RecordKind.EVENT, conf: Confidence = Confidence.LOW, claims: String? = null, subject: String? = null,
    ) = SpokenRecord("new", kind, title, title, conf, claims, subject)

    private val car = open("car", "Clean out the back of the car")

    @Test fun aClaimWithHighConfidenceCloses() {
        val d = TaskCloser.decide(said("I cleaned out the car", conf = Confidence.HIGH, claims = "car"), listOf(car))
        assertEquals(CloseDecision.Close("car"), d)
    }

    @Test fun aClaimWithLowerConfidenceAsks() {
        for (c in listOf(Confidence.MEDIUM, Confidence.LOW)) {
            assertEquals(CloseDecision.Ask("car"), TaskCloser.decide(said("I cleaned out the car", conf = c, claims = "car"), listOf(car)))
        }
    }

    @Test fun aClaimOnASnoozedTaskStillCloses() {
        val d = TaskCloser.decide(said("x", conf = Confidence.HIGH, claims = "car"), listOf(open("car", "Clean the car", TaskState.SNOOZED)))
        assertEquals(CloseDecision.Close("car"), d)
    }

    @Test fun aClaimOnAFinishedTaskDoesNothing() {
        for (s in listOf(TaskState.DONE, TaskState.DROPPED, TaskState.EXPIRED)) {
            assertEquals(CloseDecision.Nothing, TaskCloser.decide(said("x", conf = Confidence.HIGH, claims = "car"), listOf(open("car", "Clean the car", s))))
        }
    }

    @Test fun aClaimOnAnUnknownTaskDoesNothing() {
        assertEquals(CloseDecision.Nothing, TaskCloser.decide(said("x", conf = Confidence.HIGH, claims = "gone"), listOf(car)))
    }

    @Test fun noClaimAnEventSharingTwoWordsWithOneTaskAsks() {
        assertEquals(CloseDecision.Ask("car"), TaskCloser.decide(said("I cleaned out the car"), listOf(car, open("milk", "Buy milk"))))
    }

    @Test fun noClaimAndTwoMatchingTasksDoesNothing() {
        val two = listOf(car, open("car2", "Clean out the car trunk"))
        assertEquals(CloseDecision.Nothing, TaskCloser.decide(said("I cleaned out the car"), two))
    }

    @Test fun oneSharedWordIsEnoughOnlyWithTheSameSubject() {
        val t = open("filter", "Replace the furnace filter", subject = "s1")
        assertEquals(CloseDecision.Nothing, TaskCloser.decide(said("I changed the filter", subject = null), listOf(t)))
        assertEquals(CloseDecision.Nothing, TaskCloser.decide(said("I changed the filter", subject = "s2"), listOf(t)))
        assertEquals(CloseDecision.Ask("filter"), TaskCloser.decide(said("I changed the filter", subject = "s1"), listOf(t)))
    }

    @Test fun onlyAnEventAsksWithoutAClaim() {
        assertEquals(CloseDecision.Nothing, TaskCloser.decide(said("I cleaned out the car", kind = RecordKind.IDEA), listOf(car)))
    }

    @Test fun aRecordNeverClosesItself() {
        val me = TaskToClose("new", "Clean out the car", "Clean out the car", TaskState.OPEN)
        assertEquals(CloseDecision.Nothing, TaskCloser.decide(said("I cleaned out the car", claims = "new", conf = Confidence.HIGH), listOf(me)))
    }

    @Test fun theThreeSettings() {
        val sure = said("I cleaned out the car", conf = Confidence.HIGH, claims = "car")
        val unsure = said("I cleaned out the car", conf = Confidence.LOW, claims = "car")
        val guess = said("I cleaned out the car")
        // close when sure (the default)
        assertEquals(CloseDecision.Close("car"), TaskCloser.decide(sure, listOf(car), CloseSetting.WHEN_SURE))
        assertEquals(CloseDecision.Ask("car"), TaskCloser.decide(unsure, listOf(car), CloseSetting.WHEN_SURE))
        // always ask
        assertEquals(CloseDecision.Ask("car"), TaskCloser.decide(sure, listOf(car), CloseSetting.ALWAYS_ASK))
        assertEquals(CloseDecision.Ask("car"), TaskCloser.decide(guess, listOf(car), CloseSetting.ALWAYS_ASK))
        // close on any match: Ask becomes Close
        assertEquals(CloseDecision.Close("car"), TaskCloser.decide(unsure, listOf(car), CloseSetting.ANY_MATCH))
        assertEquals(CloseDecision.Close("car"), TaskCloser.decide(guess, listOf(car), CloseSetting.ANY_MATCH))
        assertEquals(CloseDecision.Nothing, TaskCloser.decide(said("I went for a walk"), listOf(car), CloseSetting.ANY_MATCH))
    }

    @Test fun stemsMeetAcrossTenses() {
        assertTrue(TaskText.stems("I cleaned out the car").containsAll(setOf("clean", "out", "car")))
        assertTrue(TaskText.stems("clean out the back of the car soon").containsAll(setOf("clean", "out", "car", "back")))
        assertEquals(TaskText.stems("changed the filters"), TaskText.stems("change the filter"))
    }

    // ------------------------------------------------------------------ duplicates

    @Test fun anIdenticalTitleInTheSamePlaceFoldsIn() {
        val v = TaskDuplicates.check(TaskCompare("new", "Buy milk", "Costco"), listOf(TaskCompare("old", "buy milk.", "costco")))
        assertEquals(DuplicateVerdict.Duplicate("old"), v)
    }

    @Test fun theSameWordsInADifferentFormAreTheSameTask() {
        val v = TaskDuplicates.check(TaskCompare("new", "Buy the milk", null), listOf(TaskCompare("old", "Buy milk", null)))
        assertEquals(DuplicateVerdict.Duplicate("old"), v)
    }

    @Test fun aDifferentPlaceIsNotADuplicate() {
        val v = TaskDuplicates.check(TaskCompare("new", "Buy milk", "Costco"), listOf(TaskCompare("old", "Buy milk", "Safeway")))
        assertEquals(DuplicateVerdict.None, v)
        assertEquals(DuplicateVerdict.None, TaskDuplicates.check(TaskCompare("new", "Buy milk", "Costco"), listOf(TaskCompare("old", "Buy milk", null))))
    }

    @Test fun aNearDuplicateIsFlaggedNotFolded() {
        val v = TaskDuplicates.check(
            TaskCompare("new", "Clean out the back of the car", null), listOf(TaskCompare("old", "Clean out the car", null)),
        )
        assertEquals(DuplicateVerdict.Near("old"), v)
    }

    @Test fun differentTasksAreLeftAlone() {
        assertEquals(DuplicateVerdict.None, TaskDuplicates.check(TaskCompare("new", "Call the dentist", null), listOf(TaskCompare("old", "Buy milk", null))))
        assertEquals(DuplicateVerdict.None, TaskDuplicates.check(TaskCompare("new", "Buy milk", null), listOf(TaskCompare("new", "Buy milk", null))))
    }

    // ------------------------------------------------------------------ the question

    @Test fun theTaskQuestionsMatch() {
        val yes = listOf(
            "What should I handle?", "what should I handle today", "What are the things I said I should do?",
            "What are the things I said I need to handle", "what are the things I said I have to do",
            "What are the things I should handle?", "What's on my list?", "what is on my list", "What's on my to do list",
            "What do I need to do?", "what do I have to do", "Anything I need to do?", "anything I have to do", "Hey, what should I handle",
        )
        for (q in yes) assertEquals(q, TaskQuestion.WhatToHandle, TaskQuestions.parse(q))
    }

    @Test fun otherQuestionsDoNotMatch() {
        val no = listOf(
            "What did I handle yesterday?", "What did I say I should handle last week", "Where did I put my glasses",
            "What was my pH last time", "what do I need to do about the furnace filter", "How do I handle a flat tire",
            "What's on my calendar", "when did I last do the filter", "", "I should handle the car",
        )
        for (q in no) assertNull(q, TaskQuestions.parse(q))
    }

    @Test fun aPlaceQuestionNamesThePlace() {
        assertEquals(TaskQuestion.AtPlace("costco"), TaskQuestions.parse("What do I need at Costco?"))
        assertEquals(TaskQuestion.AtPlace("costco"), TaskQuestions.parse("what do I need to do at Costco"))
        assertEquals(TaskQuestion.AtPlace("hardware store"), TaskQuestions.parse("what do I need at the hardware store"))
        assertNull(TaskQuestions.parse("what did I need at Costco"))
    }

    // ------------------------------------------------------------------ the answer

    private fun item(
        id: String, title: String, t: TaskEntity, quote: String = title, said: Long = now - 5 * 86_400_000L, attention: String? = null,
    ): TaskItem {
        val cap = CaptureEntity(id = "c$id", createdAt = said, updatedAt = said, rawText = quote, text = quote)
        val rec = RecordEntity(
            id = id, captureId = cap.id, extractionId = "x", seq = 0, kind = RecordKind.TASK, title = title, quote = quote,
            attention = attention, createdAt = said, updatedAt = said,
        )
        return TaskItem(t.copy(recordId = id), rec, cap)
    }

    @Test fun theAnswerListsTheTopFiveInOrderAndSaysHowManyMore() {
        val items = (1..7).map { item("t$it", "Task number $it", task(kind = DueKind.SOON, active = now - it * 1000L)) }
        val text = TaskAnswers.whatToHandle(items, today, now)
        assertTrue(text, text.startsWith("You have 7 things. Soonest: Task number 7 (you said that 5 days ago); Task number 6"))
        assertTrue(text, text.endsWith("; and 2 more."))
        assertEquals(5, text.count { it == ';' })
    }

    @Test fun theAnswerForOneTaskAndForNone() {
        val one = listOf(item("a", "Clean out the back of the car", task(kind = DueKind.SOON)))
        assertEquals("You have 1 thing. It is: Clean out the back of the car (you said that 5 days ago).", TaskAnswers.whatToHandle(one, today, now))
        assertEquals("You have nothing on your list.", TaskAnswers.whatToHandle(emptyList(), today, now))
    }

    @Test fun theAnswerMentionsADueDateAndLeavesOutFoldedAndPutOffTasks() {
        val dated = item("d", "Pay the plumber", task(kind = DueKind.ON, from = "2026-11-14").copy(dueText = "on Saturday"))
        val folded = item("f", "Pay plumber", task(), attention = "DUPLICATE_OF:d")
        val later = item("l", "Wash the dog", task(state = TaskState.SNOOZED, snoozed = "2026-11-20"))
        val text = TaskAnswers.whatToHandle(listOf(dated, folded, later), today, now)
        assertEquals("You have 1 thing. It is: Pay the plumber, on Saturday (you said that 5 days ago).", text)
    }

    @Test fun thePlaceAnswerOnlyHasThatPlacesTasks() {
        val items = listOf(
            item("m", "Buy milk", task(place = "Costco")), item("e", "Return the cable", task(place = "Best Buy")),
            item("w", "Wash the dog", task()),
        )
        assertEquals("At Costco you have 1 thing: Buy milk (you said that 5 days ago).", TaskAnswers.atPlace(items, "costco", today, now))
        assertEquals("I don't have anything for you to do at Safeway.", TaskAnswers.atPlace(items, "safeway", today, now))
    }

    @Test fun sectionsAreInOrderGroupedAndHideWhatIsEmpty() {
        val items = listOf(
            item("due", "Pay the plumber", task(kind = DueKind.BY, to = "2026-11-12")),
            item("soon", "Clean the car", task(kind = DueKind.SOON)),
            item("p1", "Buy milk", task(place = "Costco")), item("p2", "Buy eggs", task(place = "Costco")),
            item("p3", "Buy nails", task(place = "Home Depot")),
            item("any", "Sort the garage", task()),
            item("later", "Wash the dog", task(state = TaskState.SNOOZED, snoozed = "2026-11-20")),
            item("done", "Call mum", task(state = TaskState.DONE).copy(closedAt = now - 86_400_000L, closedHow = ClosedHow.TAP)),
            item("olddone", "Call dad", task(state = TaskState.DONE).copy(closedAt = now - 20 * 86_400_000L, closedHow = ClosedHow.TAP)),
            item("stopped", "Fix the gate", task(state = TaskState.EXPIRED).copy(closedAt = now - 86_400_000L, closedHow = ClosedHow.EXPIRED)),
            item("dup", "Buy milk", task(state = TaskState.DROPPED).copy(closedAt = now, closedHow = ClosedHow.DROPPED), attention = "DUPLICATE_OF:p1"),
        )
        val s = TaskSections.build(items, today, now)
        assertEquals(listOf("due"), s.due.map { it.record.id })
        assertEquals(listOf("soon"), s.soon.map { it.record.id })
        assertEquals(listOf("Costco", "Home Depot"), s.atPlace.map { it.first })
        assertEquals(listOf("p1", "p2"), s.atPlace[0].second.map { it.record.id }.sorted())
        assertEquals(listOf("any"), s.whenever.map { it.record.id })
        assertEquals(listOf("later"), s.later.map { it.record.id })
        assertEquals(listOf("done"), s.doneRecently.map { it.record.id })
        assertEquals(listOf("stopped"), s.stoppedAsking.map { it.record.id })
        assertEquals(2, s.badge)
        assertTrue(TaskSections.build(emptyList(), today, now).isEmpty)
        val onlySoon = TaskSections.build(items.filter { it.record.id == "soon" }, today, now)
        assertTrue(onlySoon.due.isEmpty() && onlySoon.atPlace.isEmpty() && onlySoon.whenever.isEmpty() && onlySoon.later.isEmpty())
    }
}
