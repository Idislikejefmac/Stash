package app.stash.domain

import app.stash.data.DueKind
import app.stash.data.TaskEntity
import app.stash.data.TaskState
import java.time.LocalDate

/**
 * When a task is next nudged (docs/cards/06 section A). Pure: dates in, dates out, no clock.
 * A task that is quiet, snoozed or not open has no next nudge.
 */
object NudgePlan {
    /** Gaps for "someday" tasks, by how many nudges there have been: 3, 7, 14, then 30 days. */
    private val GAPS = intArrayOf(3, 7, 14, 30)

    private fun gap(index: Int) = GAPS[index.coerceIn(0, GAPS.lastIndex)].toLong()

    private fun dateOf(s: String?): LocalDate? = s?.let(TaskRules::parseDate)

    /** The first nudge for a new (or brought-back) task, or null when time never nudges it. */
    fun firstNudgeOn(t: TaskEntity, capturedOn: LocalDate): LocalDate? {
        if (TaskRules.hasPlace(t) && (t.dueKind == DueKind.NONE || t.dueKind == DueKind.SOON)) return null
        return when (t.dueKind) {
            DueKind.NONE -> null
            DueKind.SOON -> capturedOn.plusDays(2)
            DueKind.WINDOW -> dateOf(t.dueFrom) ?: dateOf(t.dueTo)
            DueKind.ON -> dateOf(t.dueFrom) ?: dateOf(t.dueTo)
            DueKind.BY -> {
                val by = dateOf(t.dueTo) ?: dateOf(t.dueFrom) ?: return null
                val days = java.time.temporal.ChronoUnit.DAYS.between(capturedOn, by)
                // Captured the day before (or the day of): that day. Otherwise halfway, at least a day before.
                if (days <= 1) capturedOn else minOf(capturedOn.plusDays(days / 2), by.minusDays(1))
            }
        }
    }

    /** The nudge after one shown on [shownOn]. [TaskEntity.nudgeCount] is the number of nudges before this one. */
    fun nextAfter(t: TaskEntity, shownOn: LocalDate): LocalDate? {
        if (t.state != TaskState.OPEN || t.quiet) return null
        if (TaskRules.hasPlace(t) && (t.dueKind == DueKind.NONE || t.dueKind == DueKind.SOON)) return null
        // Anything that has fallen back to "soon" starts at the 7-day gap.
        fun fallback() = shownOn.plusDays(gap(maxOf(t.nudgeCount, 1)))
        return when (t.dueKind) {
            DueKind.NONE -> null
            DueKind.SOON -> shownOn.plusDays(gap(t.nudgeCount))
            DueKind.WINDOW -> {
                val end = TaskRules.dueEnd(t) ?: return fallback()
                if (shownOn.isBefore(end)) end else fallback()
            }
            DueKind.BY, DueKind.ON -> {
                val end = TaskRules.dueEnd(t) ?: return fallback()
                when {
                    shownOn.isBefore(end) -> end
                    shownOn == end -> end.plusDays(1) // one "was due" mention
                    else -> fallback()
                }
            }
        }
    }

    /** True when this task's nudge date has come. */
    fun isDue(t: TaskEntity, today: LocalDate): Boolean {
        if (t.state != TaskState.OPEN || t.quiet) return false
        val on = dateOf(t.nextNudgeOn) ?: return false
        return !on.isAfter(today)
    }
}
