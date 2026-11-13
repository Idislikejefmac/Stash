package app.stash.domain

import app.stash.data.ClosedHow
import app.stash.data.DueKind
import app.stash.data.TaskItem
import app.stash.data.TaskState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The sections of the "To handle" screen, in order. Each is hidden when empty. */
data class TaskSections(
    val due: List<TaskItem> = emptyList(),
    val soon: List<TaskItem> = emptyList(),
    /** Grouped by the place he named, groups in the order of their first task. */
    val atPlace: List<Pair<String, List<TaskItem>>> = emptyList(),
    val whenever: List<TaskItem> = emptyList(),
    val later: List<TaskItem> = emptyList(),
    val doneRecently: List<TaskItem> = emptyList(),
    val stoppedAsking: List<TaskItem> = emptyList(),
) {
    /** The badge on the tab: open tasks in Due and Soon. */
    val badge: Int get() = due.size + soon.size

    val isEmpty: Boolean
        get() = due.isEmpty() && soon.isEmpty() && atPlace.isEmpty() && whenever.isEmpty() && later.isEmpty() &&
            doneRecently.isEmpty() && stoppedAsking.isEmpty()

    companion object {
        const val DONE_RECENTLY_DAYS = 14

        /** [items] must already leave out what may not be drawn (Private tasks): this only sorts and groups. */
        fun build(items: List<TaskItem>, today: LocalDate, now: Long): TaskSections {
            val cmp = TaskRules.comparator(today)
            val visible = items.filter { !isFolded(it) }
            val open = visible.filter { it.task.state == TaskState.OPEN }.sortedWith { a, b -> cmp.compare(a.task, b.task) }
            val place = open.filter { TaskRules.bucket(it.task) == TaskBucket.PLACE }
            val cutoff = now - DONE_RECENTLY_DAYS * 24L * 3600_000L
            return TaskSections(
                due = open.filter { TaskRules.bucket(it.task) == TaskBucket.DUE },
                soon = open.filter { TaskRules.bucket(it.task) == TaskBucket.SOON },
                atPlace = place.groupBy { it.task.atPlaceText?.trim().takeUnless { p -> p.isNullOrEmpty() } ?: "Somewhere" }.toList(),
                whenever = open.filter { TaskRules.bucket(it.task) == TaskBucket.WHENEVER },
                later = visible.filter { it.task.state == TaskState.SNOOZED }
                    .sortedWith(compareBy({ it.task.snoozedUntil ?: "" }, { it.task.lastActiveAt })),
                doneRecently = visible.filter {
                    (it.task.state == TaskState.DONE || it.task.state == TaskState.DROPPED) && (it.task.closedAt ?: 0L) >= cutoff
                }.sortedByDescending { it.task.closedAt },
                stoppedAsking = visible.filter { it.task.state == TaskState.EXPIRED }.sortedByDescending { it.task.closedAt },
            )
        }

        /** A task folded into an open one (card 05 C) is not shown as something he did. */
        fun isFolded(i: TaskItem): Boolean = i.record.attention?.startsWith(Attention.DUPLICATE_OF_PREFIX) == true
    }
}

/** Calm wording for task states (docs/cards/05 notes: "Stopped asking", not "Expired"). */
object TaskLabels {
    /** Shown on a record in the Notes list. */
    fun stateLabel(state: TaskState, snoozedUntil: String?): String = when (state) {
        TaskState.OPEN -> "to do"
        TaskState.SNOOZED -> "put off" + (snoozedUntil?.let { " until $it" } ?: "")
        TaskState.DONE -> "done"
        TaskState.EXPIRED -> "stopped asking"
        TaskState.DROPPED -> "not needed"
    }

    fun howClosed(item: TaskItem): String = when (item.task.closedHow) {
        ClosedHow.SPOKEN -> item.closedBy?.let { "You said: \"${it.quote}\"" } ?: "You said you did it"
        ClosedHow.DROPPED -> "You said you don't need it"
        ClosedHow.EXPIRED -> "Stopped asking"
        ClosedHow.TAP, null -> "You marked it done"
    }

    /** "You said: "<quote>"" and its age. */
    fun saidLine(item: TaskItem, now: Long): String = "You said: \"${item.record.quote}\", ${Age.describe(item.capture.createdAt, now)}"

    /** The due words as he said them; the date if there were none and one was worked out. */
    fun dueWords(item: TaskItem): String? = when (item.task.dueKind) {
        DueKind.NONE -> null
        else -> item.task.dueText?.takeIf { it.isNotBlank() }
            ?: (item.task.dueTo ?: item.task.dueFrom)?.let { "due $it" }
    }
}

/** What he asked, when it is about his tasks (docs/cards/05 section F). */
sealed interface TaskQuestion {
    data object WhatToHandle : TaskQuestion
    data class AtPlace(val place: String) : TaskQuestion
}

object TaskQuestions {
    private const val LEAD = "(?:(?:please|hey|ok|okay|so|tell me|can you tell me|could you tell me|show me)\\s+)*"
    private const val TAIL = "(?:\\s+(?:today|now|right now|this week|soon))?"

    private val HANDLE = listOf(
        "what should i (?:handle|take care of)",
        "what do i (?:need|have|want) to (?:handle|do|take care of)",
        "what are the things i (?:said |told you )?i (?:should|need to|have to|must) (?:do|handle)",
        "what are the things i (?:should|need to|have to|must) (?:do|handle)",
        "what(?:s| is| are) on my (?:to ?do )?list",
        "what(?:s| is) on my plate",
        "anything i (?:need|have|should|must) (?:to )?(?:do|handle)",
        "anything (?:left )?(?:for me )?to (?:do|handle)",
        "do i have anything (?:i need )?to (?:do|handle)",
    ).map { Regex("^$LEAD$it$TAIL$") }

    private val AT_PLACE = listOf(
        Regex("^${LEAD}what (?:do|should) i (?:need|have|want) (?:to (?:do|get|buy|pick up) )?at (?:the )?(.+?)$TAIL$"),
    )

    fun normalise(q: String): String =
        q.lowercase().replace("’", "'").replace("'", "").replace(Regex("[^a-z0-9 ]+"), " ").replace(Regex("\\s+"), " ").trim()

    fun parse(question: String): TaskQuestion? {
        val q = normalise(question)
        if (q.isEmpty()) return null
        for (r in HANDLE) if (r.matches(q)) return TaskQuestion.WhatToHandle
        for (r in AT_PLACE) {
            val m = r.matchEntire(q) ?: continue
            val place = m.groupValues[1].trim()
            if (place.isNotEmpty()) return TaskQuestion.AtPlace(place)
        }
        return null
    }
}

/** The spoken or written answer about tasks, built by code. No model (docs/cards/05 F16). */
object TaskAnswers {
    const val TOP = 5

    /** [open] are the open tasks the caller may draw (never Private ones), in any order. */
    fun whatToHandle(open: List<TaskItem>, today: LocalDate, now: Long): String {
        val tasks = open.filter { it.task.state == TaskState.OPEN && !TaskSections.isFolded(it) }
        if (tasks.isEmpty()) return "You have nothing on your list."
        val cmp = TaskRules.comparator(today)
        val sorted = tasks.sortedWith { a, b -> cmp.compare(a.task, b.task) }
        val top = sorted.take(TOP)
        val sb = StringBuilder("You have ${count(tasks.size)}. ")
        sb.append(if (top.size == 1) "It is: " else "Soonest: ")
        sb.append(top.joinToString("; ") { line(it, now) })
        val more = sorted.size - top.size
        sb.append(if (more > 0) "; and $more more." else ".")
        return sb.toString()
    }

    fun atPlace(open: List<TaskItem>, place: String, today: LocalDate, now: Long): String {
        val want = TaskText.stems(place)
        val tasks = open.filter { it.task.state == TaskState.OPEN && !TaskSections.isFolded(it) && matchesPlace(it.task.atPlaceText, want) }
        val shown = place.trim().replaceFirstChar { it.uppercase() }
        if (tasks.isEmpty()) return "I don't have anything for you to do at $shown."
        val cmp = TaskRules.comparator(today)
        val sorted = tasks.sortedWith { a, b -> cmp.compare(a.task, b.task) }
        val top = sorted.take(TOP)
        val more = sorted.size - top.size
        return "At $shown you have ${count(tasks.size)}: " + top.joinToString("; ") { line(it, now) } +
            if (more > 0) "; and $more more." else "."
    }

    private fun matchesPlace(taskPlace: String?, want: Set<String>): Boolean {
        if (taskPlace.isNullOrBlank() || want.isEmpty()) return false
        val have = TaskText.stems(taskPlace)
        return have.isNotEmpty() && (have.containsAll(want) || want.containsAll(have))
    }

    private fun count(n: Int) = if (n == 1) "1 thing" else "$n things"

    private fun line(i: TaskItem, now: Long): String {
        val due = if (TaskRules.isDated(i.task)) TaskLabels.dueWords(i)?.let { ", $it" }.orEmpty() else ""
        return "${i.record.title.trim().trimEnd('.')}$due (you said that ${Age.describe(i.capture.createdAt, now)})"
    }

    fun todayIn(zone: ZoneId, now: Long): LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
}
