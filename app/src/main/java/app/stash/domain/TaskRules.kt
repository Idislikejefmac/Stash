package app.stash.domain

import app.stash.data.ClosedHow
import app.stash.data.DueKind
import app.stash.data.TaskEntity
import app.stash.data.TaskState
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** How long a task stays on the list with no word from him (docs/cards/05 A2). The defaults are the card's; they are settings. */
data class TaskLifetimes(
    val undatedDays: Int = 90,
    val atPlaceDays: Int = 45,
    val perishableDays: Int = 14,
    val datedDays: Int = 14,
)

/** The "Later" choices. */
enum class SnoozePreset(val label: String) {
    LATER_TODAY("Later today"),
    TOMORROW("Tomorrow"),
    THIS_WEEKEND("This weekend"),
    NEXT_WEEK("Next week"),
    IN_A_MONTH("In a month"),
    ;

    /**
     * The date this preset means. "Later today" is today (the afternoon check of card 06 picks it up). "This weekend" is the coming Saturday, or tomorrow when today is Saturday; "next week" is the
     * coming Monday.
     */
    fun resolve(today: LocalDate): LocalDate = when (this) {
        LATER_TODAY -> today
        TOMORROW -> today.plusDays(1)
        THIS_WEEKEND -> if (today.dayOfWeek == DayOfWeek.SATURDAY) today.plusDays(1) else today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY))
        NEXT_WEEK -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        IN_A_MONTH -> today.plusMonths(1)
    }
}

/** Where a task sits on the list. */
enum class TaskBucket { DUE, SOON, PLACE, WHENEVER }

/**
 * The life of a task (docs/cards/05 section A). Pure: every function returns the new row and takes the time as a
 * number. Nothing is deleted by a state change, and every change can be undone (reopen, bring back, or putting the
 * old row back). Writing the row, pinning the record and writing the revision is the repository's job.
 */
object TaskRules {
    private fun closedFields(t: TaskEntity) = t.copy(closedAt = null, closedHow = null, closedByRecordId = null)

    fun done(t: TaskEntity, how: ClosedHow, byRecordId: String?, now: Long): TaskEntity = t.copy(
        state = TaskState.DONE, closedAt = now, closedHow = how, closedByRecordId = byRecordId,
        snoozedUntil = null, ignoredInARow = 0, quiet = false, lastActiveAt = now,
    )

    /** "Not done": back to the list. */
    fun reopen(t: TaskEntity, now: Long): TaskEntity =
        closedFields(t).copy(state = TaskState.OPEN, snoozedUntil = null, lastActiveAt = now)

    fun snooze(t: TaskEntity, until: LocalDate, now: Long): TaskEntity = closedFields(t).copy(
        state = TaskState.SNOOZED, snoozedUntil = until.toString(), nextNudgeOn = null, ignoredInARow = 0, quiet = false, lastActiveAt = now,
    )

    /** A snoozed task whose date has come is open again; anything else is returned as it was. */
    fun wake(t: TaskEntity, today: LocalDate, now: Long): TaskEntity {
        if (t.state != TaskState.SNOOZED) return t
        val until = t.snoozedUntil?.let(::parseDate)
        if (until != null && until.isAfter(today)) return t
        // Waking from snooze sets the next nudge to that day (docs/cards/06 A3).
        return t.copy(state = TaskState.OPEN, snoozedUntil = null, lastActiveAt = now, nextNudgeOn = (until ?: today).toString())
    }

    fun drop(t: TaskEntity, now: Long): TaskEntity = t.copy(
        state = TaskState.DROPPED, closedAt = now, closedHow = ClosedHow.DROPPED, closedByRecordId = null,
        snoozedUntil = null, ignoredInARow = 0, quiet = false, lastActiveAt = now,
    )

    /** The app stopped asking. The one change that does not count as activity. */
    fun expire(t: TaskEntity, now: Long): TaskEntity = t.copy(
        state = TaskState.EXPIRED, closedAt = now, closedHow = ClosedHow.EXPIRED, closedByRecordId = null, snoozedUntil = null,
    )

    /** From "Stopped asking" back to the list, with a fresh life. */
    fun bringBack(t: TaskEntity, now: Long): TaskEntity = reopen(t, now)

    /** Something happened to this task that shows it is still wanted (a duplicate was folded into it). */
    fun touch(t: TaskEntity, now: Long): TaskEntity = t.copy(lastActiveAt = now)

    fun isOpenOrSnoozed(t: TaskEntity) = t.state == TaskState.OPEN || t.state == TaskState.SNOOZED

    fun isDated(t: TaskEntity) = t.dueKind == DueKind.ON || t.dueKind == DueKind.BY || t.dueKind == DueKind.WINDOW

    fun hasPlace(t: TaskEntity) = !t.atPlaceText.isNullOrBlank() || t.atPlaceSubjectId != null

    /** The last day a dated task is due: `due_to`, or `due_from` when there is no end. */
    fun dueEnd(t: TaskEntity): LocalDate? =
        if (isDated(t)) (t.dueTo ?: t.dueFrom)?.let(::parseDate) else null

    /** The day a dated task is sorted by. */
    private fun dueSortDate(t: TaskEntity): LocalDate? = when (t.dueKind) {
        DueKind.BY -> (t.dueTo ?: t.dueFrom)?.let(::parseDate)
        DueKind.ON, DueKind.WINDOW -> (t.dueFrom ?: t.dueTo)?.let(::parseDate)
        else -> null
    }

    /**
     * When the app stops asking. No date: [TaskLifetimes.undatedDays] after the last activity; at a place:
     * [TaskLifetimes.atPlaceDays], or [TaskLifetimes.perishableDays] if perishable; dated: [TaskLifetimes.datedDays]
     * after the due date. A snooze extends life: the count starts from the snooze date if that is later.
     */
    fun expiresOn(t: TaskEntity, life: TaskLifetimes, zone: ZoneId): LocalDate {
        val active = Instant.ofEpochMilli(t.lastActiveAt).atZone(zone).toLocalDate()
        val snooze = t.snoozedUntil?.let(::parseDate)
        val due = dueEnd(t)
        if (due != null) return maxOf(due, snooze ?: due).plusDays(life.datedDays.toLong())
        val base = maxOf(active, snooze ?: active)
        val days = when {
            hasPlace(t) && t.perishable -> life.perishableDays
            hasPlace(t) -> life.atPlaceDays
            else -> life.undatedDays
        }
        return base.plusDays(days.toLong())
    }

    fun bucket(t: TaskEntity): TaskBucket = when {
        isDated(t) -> TaskBucket.DUE
        t.dueKind == DueKind.SOON -> TaskBucket.SOON
        hasPlace(t) -> TaskBucket.PLACE
        else -> TaskBucket.WHENEVER
    }

    fun isOverdue(t: TaskEntity, today: LocalDate): Boolean = dueEnd(t)?.isBefore(today) == true

    /** Overdue dated first, then dated by date, then SOON by age (oldest first), then at-a-place, then the rest by age. */
    fun comparator(today: LocalDate): Comparator<TaskEntity> {
        fun rank(t: TaskEntity) = when (bucket(t)) {
            TaskBucket.DUE -> if (isOverdue(t, today)) 0 else 1
            TaskBucket.SOON -> 2
            TaskBucket.PLACE -> 3
            TaskBucket.WHENEVER -> 4
        }
        return Comparator<TaskEntity> { a, b -> rank(a).compareTo(rank(b)) }
            .thenComparing { a, b ->
                when (bucket(a)) {
                    TaskBucket.DUE -> {
                        val da = if (isOverdue(a, today)) dueEnd(a) else dueSortDate(a)
                        val db = if (isOverdue(b, today)) dueEnd(b) else dueSortDate(b)
                        compareValues(da, db)
                    }
                    TaskBucket.PLACE -> (a.atPlaceText.orEmpty().lowercase()).compareTo(b.atPlaceText.orEmpty().lowercase())
                    else -> 0
                }
            }
            .thenBy { it.lastActiveAt }
    }

    fun sort(tasks: List<TaskEntity>, today: LocalDate): List<TaskEntity> = tasks.sortedWith(comparator(today))

    fun parseDate(s: String): LocalDate? = try { LocalDate.parse(s) } catch (e: Exception) { null }
}
