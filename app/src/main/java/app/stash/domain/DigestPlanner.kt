package app.stash.domain

import app.stash.data.ClosedHow
import app.stash.data.NudgeLogEntity
import app.stash.data.NudgeType
import app.stash.data.TaskItem
import app.stash.data.TaskState
import org.json.JSONArray
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class DigestLine(val recordId: String, val title: String)

/** The daily digest: the first five tasks listed, the rest only counted. [recordIds] holds every task it covers. */
data class DailyDigest(val lines: List<DigestLine>, val more: Int, val recordIds: List<String>) {
    val total: Int get() = recordIds.size
}

/** The one most pressing thing due today. */
data class DueReminder(val recordId: String, val title: String)

/** A loan the weekly review may mention. */
data class LoanFact(val recordId: String, val firstMentionedAt: Long, val dueBackOn: String?, val sensitive: Boolean)

/** What the weekly review knows (docs/cards/06 B6). Private rows are left out again inside the planner. */
data class WeeklySnapshot(
    val tasks: List<TaskItem>,
    val loans: List<LoanFact>,
    val attentionCount: Int,
)

enum class ReviewTarget { TO_HANDLE, NOTES }

data class ReviewLine(val text: String, val target: ReviewTarget)

data class WeeklyReview(
    val openCount: Int,
    val doneCount: Int,
    /** Titles of tasks he closed by speaking in the last 7 days (shown on the review screen only). */
    val spokenDoneTitles: List<String>,
    val stoppingSoonCount: Int,
    val loanCount: Int,
    val attentionCount: Int,
    /** The one-time line that the daily digest stopped itself. */
    val pausedNotice: Boolean,
) {
    /** Every count is zero: the notification is skipped. */
    val isEmpty: Boolean
        get() = openCount == 0 && doneCount == 0 && stoppingSoonCount == 0 && loanCount == 0 && attentionCount == 0 && !pausedNotice

    /** Counts only, no words: safe for the notification when unlocked. Titles are on the review screen. */
    fun lines(): List<ReviewLine> = buildList {
        if (pausedNotice) add(ReviewLine(PAUSED_LINE, ReviewTarget.TO_HANDLE))
        if (openCount > 0) add(ReviewLine("${things(openCount)} still to do.", ReviewTarget.TO_HANDLE))
        if (doneCount > 0) add(ReviewLine("You finished ${things(doneCount)} this week.", ReviewTarget.TO_HANDLE))
        if (stoppingSoonCount > 0) add(ReviewLine("I'll stop asking about ${things(stoppingSoonCount)} in the next two weeks.", ReviewTarget.TO_HANDLE))
        if (loanCount > 0) add(ReviewLine("${things(loanCount)} you lent out may be due back.", ReviewTarget.NOTES))
        if (attentionCount > 0) add(ReviewLine("${if (attentionCount == 1) "1 note" else "$attentionCount notes"} I wasn't sure about.", ReviewTarget.NOTES))
    }

    private fun things(n: Int) = if (n == 1) "1 thing" else "$n things"

    companion object {
        const val PAUSED_LINE = "I've stopped the daily reminder because you weren't opening it. You can turn it back on in Settings."
    }
}

/** What to send today (docs/cards/06 section B). Pure; private tasks are filtered out of every input before anything else. */
object DigestPlanner {
    const val LISTED = 5
    private const val DAY_MS = 24L * 3600_000L

    /** Open, not quiet, not folded into another task, and never from a Private capture. */
    fun eligible(items: List<TaskItem>): List<TaskItem> = items.filter {
        !it.capture.sensitive && it.record.deletedAt == null && it.task.state == TaskState.OPEN && !it.task.quiet &&
            !TaskSections.isFolded(it)
    }

    private fun sameDay(at: Long, day: LocalDate, zone: ZoneId) = Instant.ofEpochMilli(at).atZone(zone).toLocalDate() == day

    fun planDaily(
        items: List<TaskItem>,
        log: List<NudgeLogEntity>,
        today: LocalDate,
        settings: NudgeSettings,
        zone: ZoneId,
    ): DailyDigest? {
        if (!settings.dailyOn || settings.dailyAutoPaused) return null
        // The weekly review replaces the digest on its day.
        if (settings.weeklyOn && today.dayOfWeek == settings.weeklyDay) return null
        if (log.any { it.type == NudgeType.DAILY && sameDay(it.at, today, zone) }) return null
        val due = eligible(items).filter { NudgePlan.isDue(it.task, today) }
        if (due.isEmpty()) return null
        val cmp = TaskRules.comparator(today)
        val sorted = due.sortedWith { a, b -> cmp.compare(a.task, b.task) }
        return DailyDigest(
            lines = sorted.take(LISTED).map { DigestLine(it.record.id, it.record.title) },
            more = (sorted.size - LISTED).coerceAtLeast(0),
            recordIds = sorted.map { it.record.id },
        )
    }

    /**
     * At the afternoon time: a task due today (ON today, or the last day of a BY or a window) that is still open, or an
     * undated one he put off to "later today" (its nudge is today and no digest or reminder named it today). One reminder,
     * for the most pressing.
     */
    fun planDueToday(items: List<TaskItem>, log: List<NudgeLogEntity>, today: LocalDate, settings: NudgeSettings, zone: ZoneId, pickups: Boolean = true): DueReminder? {
        if (log.any { it.type == NudgeType.DUE && sameDay(it.at, today, zone) }) return null
        val namedToday = log.filter { sameDay(it.at, today, zone) && (it.type == NudgeType.DAILY || it.type == NudgeType.DUE) }
            .flatMap { RecordIds.decode(it.recordIds) }.toSet()
        val open = eligible(items)
        val cmp = TaskRules.comparator(today)
        val dueToday = open.filter { TaskRules.isDated(it.task) && TaskRules.dueEnd(it.task) == today }
        // "Later today" pickups only on a day when a digest is expected (not the weekly day, not paused): the wording says "due today".
        val digestDay = settings.dailyOn && !settings.dailyAutoPaused && !(settings.weeklyOn && today.dayOfWeek == settings.weeklyDay)
        val laterToday = if (!digestDay || !pickups) emptyList() else
            open.filter { !TaskRules.isDated(it.task) && it.record.id !in namedToday && NudgePlan.isDue(it.task, today) }
        val pick = dueToday.sortedWith { a, b -> cmp.compare(a.task, b.task) }.firstOrNull()
            ?: laterToday.sortedWith { a, b -> cmp.compare(a.task, b.task) }.firstOrNull()
            ?: return null
        return DueReminder(pick.record.id, pick.record.title)
    }

    fun planWeekly(snapshot: WeeklySnapshot, today: LocalDate, now: Long, settings: NudgeSettings, noticePending: Boolean): WeeklyReview {
        val tasks = snapshot.tasks.filter { !it.capture.sensitive && it.record.deletedAt == null && !TaskSections.isFolded(it) }
        val cutoff = now - 7 * DAY_MS
        val done = tasks.filter { it.task.state == TaskState.DONE && (it.task.closedAt ?: 0L) >= cutoff }
        val spoken = done.filter { it.task.closedHow == ClosedHow.SPOKEN }.map { it.record.title }
        val horizon = today.plusDays(14)
        val stopping = tasks.count {
            it.task.state == TaskState.OPEN &&
                it.task.expiresOn?.let(TaskRules::parseDate)?.let { d -> !d.isBefore(today) && !d.isAfter(horizon) } == true
        }
        val loans = snapshot.loans.count {
            !it.sensitive && (
                now - it.firstMentionedAt > 30 * DAY_MS ||
                    it.dueBackOn?.let(TaskRules::parseDate)?.isBefore(today) == true
                )
        }
        return WeeklyReview(
            openCount = tasks.count { it.task.state == TaskState.OPEN },
            doneCount = done.size,
            spokenDoneTitles = spoken,
            stoppingSoonCount = stopping,
            loanCount = loans,
            attentionCount = snapshot.attentionCount,
            pausedNotice = noticePending && settings.dailyAutoPaused,
        )
    }
}

/** Record ids in `nudge_log.record_ids` (a JSON list). */
object RecordIds {
    fun encode(ids: List<String>): String = JSONArray(ids).toString()

    fun decode(s: String): List<String> = try {
        val a = JSONArray(s)
        (0 until a.length()).map { a.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }
}
