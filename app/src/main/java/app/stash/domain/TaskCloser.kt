package app.stash.domain

import app.stash.data.Confidence
import app.stash.data.TaskState

/**
 * Comparing what a task and an event are about. Uses the stop words and the plural-stripping stemmer of [Searcher],
 * plus a few more stop words that carry no task meaning ("need", "soon") and the "-ed", "-ing" and final "e" that
 * past and present forms differ by ("cleaned" and "clean out" meet at "clean").
 */
object TaskText {
    private val EXTRA_STOP = setOf(
        "need", "needs", "needed", "should", "must", "soon", "just", "finally", "already", "also", "really", "going", "gonna",
        "gotta", "want", "wanted", "please", "got", "get", "today", "tonight", "tomorrow",
    )

    fun stem(word: String): String {
        var s = Searcher.stem(word)
        if (s.length > 5 && s.endsWith("ing")) s = s.dropLast(3)
        else if (s.length > 4 && s.endsWith("ed")) s = s.dropLast(2)
        if (s.length > 3 && s.endsWith("e")) s = s.dropLast(1)
        return s
    }

    fun stems(text: String): Set<String> =
        Searcher.tokens(text).filter { !Searcher.isStopWord(it) && it !in EXTRA_STOP }.map(::stem).filter { it.length > 1 }.toSet()
}

/** What the closer needs to know about an open task. */
data class TaskToClose(
    val recordId: String,
    val title: String,
    val quote: String = "",
    val state: TaskState,
    val subjectId: String? = null,
)

/** What the closer needs to know about the record that was just stored. */
data class SpokenRecord(
    val id: String,
    val kind: RecordKind,
    val title: String,
    val quote: String = "",
    val confidence: Confidence,
    val claimsToCloseRecordId: String? = null,
    val subjectId: String? = null,
)

/** The setting "When I say I did something". */
enum class CloseSetting(val label: String) {
    /** Close when the model named the task and is sure; ask otherwise. */
    WHEN_SURE("Close it when sure"),
    ALWAYS_ASK("Always ask me"),

    /** Treat every possible match as a sure one. */
    ANY_MATCH("Close it on any match"),
    ;

    companion object {
        fun parse(s: String?): CloseSetting = entries.firstOrNull { it.name == s } ?: WHEN_SURE
    }
}

sealed interface CloseDecision {
    data class Close(val taskRecordId: String) : CloseDecision
    data class Ask(val taskRecordId: String) : CloseDecision
    data object Nothing : CloseDecision
}

/**
 * Closing a task by speaking (docs/cards/05 section B). A wrongly closed task is worse than one left open, so the
 * bar is high: only a record the extractor says finishes a named task, with high confidence, closes it.
 */
object TaskCloser {
    fun decide(record: SpokenRecord, open: List<TaskToClose>, setting: CloseSetting = CloseSetting.WHEN_SURE): CloseDecision {
        val live = open.filter { (it.state == TaskState.OPEN || it.state == TaskState.SNOOZED) && it.recordId != record.id }
        val claimed = record.claimsToCloseRecordId
        if (claimed != null) {
            val task = live.firstOrNull { it.recordId == claimed } ?: return CloseDecision.Nothing
            return when (setting) {
                CloseSetting.ANY_MATCH -> CloseDecision.Close(task.recordId)
                CloseSetting.ALWAYS_ASK -> CloseDecision.Ask(task.recordId)
                CloseSetting.WHEN_SURE ->
                    if (record.confidence == Confidence.HIGH) CloseDecision.Close(task.recordId) else CloseDecision.Ask(task.recordId)
            }
        }
        if (record.kind != RecordKind.EVENT) return CloseDecision.Nothing
        val said = TaskText.stems(record.title + " " + record.quote)
        val matches = live.filter { t ->
            val shared = said.intersect(TaskText.stems(t.title + " " + t.quote)).size
            shared >= 2 || (shared == 1 && record.subjectId != null && record.subjectId == t.subjectId)
        }
        val only = matches.singleOrNull() ?: return CloseDecision.Nothing
        return if (setting == CloseSetting.ANY_MATCH) CloseDecision.Close(only.recordId) else CloseDecision.Ask(only.recordId)
    }
}

/** What the duplicate check needs to know about a task. */
data class TaskCompare(val recordId: String, val title: String, val placeText: String?)

sealed interface DuplicateVerdict {
    data class Duplicate(val existingRecordId: String) : DuplicateVerdict
    data class Near(val existingRecordId: String) : DuplicateVerdict
    data object None : DuplicateVerdict
}

/** Duplicate tasks (docs/cards/05 section C). A task that says exactly what an open one says, in the same place, is not a second nag. */
object TaskDuplicates {
    private fun placeKey(p: String?) = Titles.norm(p.orEmpty())

    fun check(task: TaskCompare, open: List<TaskCompare>): DuplicateVerdict {
        val mine = TaskText.stems(task.title)
        var near: String? = null
        for (o in open) {
            if (o.recordId == task.recordId || placeKey(o.placeText) != placeKey(task.placeText)) continue
            val theirs = TaskText.stems(o.title)
            if (mine.isEmpty() || theirs.isEmpty()) {
                if (Titles.norm(task.title) == Titles.norm(o.title) && Titles.norm(task.title).isNotEmpty()) return DuplicateVerdict.Duplicate(o.recordId)
                continue
            }
            if (mine == theirs) return DuplicateVerdict.Duplicate(o.recordId)
            val shared = mine.intersect(theirs).size
            if (near == null && shared >= 2 && shared.toDouble() / maxOf(mine.size, theirs.size) >= 0.6) near = o.recordId
        }
        return near?.let { DuplicateVerdict.Near(it) } ?: DuplicateVerdict.None
    }
}
