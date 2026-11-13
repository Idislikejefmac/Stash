package app.stash.domain

import app.stash.data.NudgeType
import java.time.Instant
import java.time.ZoneId

/** One notification that was posted: enough for the budget, with no words in it. */
data class PostedNudge(val at: Long, val type: NudgeType)

enum class BudgetClass {
    /** The daily digest, the weekly review, "due today". */
    SCHEDULED,
    /** Something that just happened in the world: arrival, leaving, a watch match. */
    EVENT,
    ;

    companion object {
        fun of(type: NudgeType): BudgetClass = when (type) {
            NudgeType.DAILY, NudgeType.WEEKLY, NudgeType.DUE -> SCHEDULED
            NudgeType.ARRIVAL, NudgeType.LEAVING, NudgeType.WATCH -> EVENT
        }
    }
}

enum class Verdict {
    ALLOW,
    /** Not now; the caller may try again later (a watch match in quiet hours; a scheduled one waits for the next digest). */
    WAIT,
    /** Not at all. */
    DROP,
}

/**
 * The one place that decides whether a notification may be posted (docs/cards/06 B7). Nothing calls the notifier
 * without asking [mayPost] first. Pure: the log and the time are arguments.
 *
 * - Scheduled: at most 2 a calendar day; the weekly review counts as the day's digest.
 * - Event: at most 2 a day, at least 30 minutes apart.
 * - At most 4 a day in all, and nothing in quiet hours.
 */
object NotificationBudget {
    const val SCHEDULED_PER_DAY = 2
    const val EVENT_PER_DAY = 2
    const val TOTAL_PER_DAY = 4
    const val EVENT_GAP_MS = 30 * 60_000L

    fun check(type: NudgeType, now: Long, log: List<PostedNudge>, settings: NudgeSettings, zone: ZoneId): Verdict {
        val cls = BudgetClass.of(type)
        val local = Instant.ofEpochMilli(now).atZone(zone)
        if (settings.isQuiet(local.toLocalTime())) {
            return when {
                cls == BudgetClass.SCHEDULED -> Verdict.WAIT
                type == NudgeType.WATCH -> Verdict.WAIT
                else -> Verdict.DROP
            }
        }
        val today = local.toLocalDate()
        val sameDay = log.filter { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() == today }
        if (sameDay.size >= TOTAL_PER_DAY) return Verdict.DROP
        val scheduled = sameDay.filter { BudgetClass.of(it.type) == BudgetClass.SCHEDULED }
        val events = sameDay.filter { BudgetClass.of(it.type) == BudgetClass.EVENT }
        if (cls == BudgetClass.SCHEDULED) {
            if (scheduled.size >= SCHEDULED_PER_DAY) return Verdict.DROP
            // The weekly review is the day's digest: never both.
            val digests = scheduled.map { it.type }
            if (type == NudgeType.DAILY && NudgeType.WEEKLY in digests) return Verdict.DROP
            if (type == NudgeType.WEEKLY && NudgeType.DAILY in digests) return Verdict.DROP
        } else {
            if (events.size >= EVENT_PER_DAY) return Verdict.DROP
            val last = events.maxOfOrNull { it.at }
            if (last != null && now - last < EVENT_GAP_MS) return if (type == NudgeType.WATCH) Verdict.WAIT else Verdict.DROP
        }
        return Verdict.ALLOW
    }

    fun mayPost(type: NudgeType, now: Long, log: List<PostedNudge>, settings: NudgeSettings, zone: ZoneId): Boolean =
        check(type, now, log, settings, zone) == Verdict.ALLOW
}
