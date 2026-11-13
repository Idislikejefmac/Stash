package app.stash.domain

import app.stash.data.NudgeLogEntity
import app.stash.data.NudgeOutcome
import app.stash.data.NudgeType
import app.stash.data.TaskEntity

/** What the 24-hour sweep decided: new outcomes for log rows, and the tasks whose counts changed. */
data class SweepResult(val outcomes: Map<String, NudgeOutcome>, val tasks: List<TaskEntity>) {
    val ignored: Int get() = outcomes.values.count { it == NudgeOutcome.IGNORED }
}

/** Ignored digests, backing off, and the digest stopping itself (docs/cards/06 B8 and B9). Pure. */
object NudgeSweep {
    const val IGNORED_AFTER_MS = 24L * 3600_000L
    const val QUIET_AFTER = 3
    const val PAUSE_AFTER = 5

    /** The outcome a later event may set: opening beats showing, acting beats opening, nothing goes back. */
    fun merge(old: NudgeOutcome, new: NudgeOutcome): NudgeOutcome = when {
        old == NudgeOutcome.ACTED -> old
        new == NudgeOutcome.ACTED -> new
        old == NudgeOutcome.OPENED -> old
        old == NudgeOutcome.SHOWN -> new
        else -> old
    }

    /**
     * A SHOWN notification that was not opened, and none of whose tasks was acted on, within 24 hours becomes IGNORED
     * and each task it listed goes up by one in `ignored_in_a_row`; three in a row make it quiet. If one of its tasks
     * was acted on after it was posted, the notification counts as ACTED instead. [tasks] are the rows the log refers to.
     */
    fun sweep(log: List<NudgeLogEntity>, tasks: Map<String, TaskEntity>, now: Long): SweepResult {
        val outcomes = LinkedHashMap<String, NudgeOutcome>()
        val changed = LinkedHashMap<String, TaskEntity>()
        for (e in log.sortedBy { it.at }) {
            if (e.outcome != NudgeOutcome.SHOWN || now - e.at < IGNORED_AFTER_MS) continue
            val ids = RecordIds.decode(e.recordIds)
            val acted = ids.any { id -> (tasks[id]?.lastActiveAt ?: 0L) > e.at }
            if (acted) {
                outcomes[e.id] = NudgeOutcome.ACTED
                continue
            }
            outcomes[e.id] = NudgeOutcome.IGNORED
            for (id in ids) {
                val t = changed[id] ?: tasks[id] ?: continue
                val n = t.ignoredInARow + 1
                changed[id] = t.copy(ignoredInARow = n, quiet = t.quiet || n >= QUIET_AFTER)
            }
        }
        return SweepResult(outcomes, changed.values.toList())
    }

    /**
     * Five consecutive DAILY entries, newest first, all IGNORED or DISMISSED, since the owner last turned the digest on:
     * the digest stops itself. A SHOWN or OPENED or ACTED entry breaks the run.
     */
    fun shouldAutoPause(log: List<NudgeLogEntity>, resumedAt: Long): Boolean {
        val daily = log.filter { it.type == NudgeType.DAILY && it.at > resumedAt }.sortedByDescending { it.at }.take(PAUSE_AFTER)
        return daily.size == PAUSE_AFTER && daily.all { it.outcome == NudgeOutcome.IGNORED || it.outcome == NudgeOutcome.DISMISSED }
    }
}
