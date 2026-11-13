package app.stash.data

import androidx.room.withTransaction
import app.stash.domain.NudgePlan
import app.stash.domain.TaskLifetimes
import app.stash.domain.TaskRules
import java.time.LocalDate
import java.time.ZoneId

/**
 * Everything that changes a task (docs/cards/05). The transitions themselves are pure ([TaskRules]); this class writes
 * the row, pins the record and writes the revision in one transaction. Revisions hold states and dates, never words.
 *
 * Owner actions ([markDone], [snooze], [drop], [reopen], [bringBack], [restore]) pin the record and write a revision.
 * What the app does by itself ([foldDuplicate], upkeep) is not an owner action and writes none.
 */
class TaskRepository(
    private val db: StashDatabase,
    private val clock: Clock = Clock.System,
    private val lifetimes: () -> TaskLifetimes = { TaskLifetimes() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    /** Called after any change to a task, so reminders can be planned again (docs/cards/06 9a). Never throws into the caller. */
    private val changed: () -> Unit = {},
) {
    private val revisions = RevisionWriter(db, clock)

    suspend fun get(recordId: String): TaskEntity? = db.tasks().get(recordId)

    private fun finish(t: TaskEntity): TaskEntity =
        t.copy(expiresOn = if (TaskRules.isOpenOrSnoozed(t)) TaskRules.expiresOn(t, lifetimes(), zone()).toString() else null).stamped(clock)

    /**
     * Applies one owner action. Returns the row as it was before (for Undo), or null if there is nothing to change.
     * [revisionValue] is the new value written to the revision (a state or a date).
     */
    private fun notifyChanged() { try { changed() } catch (e: Exception) { /* planning again is never worth failing a tap */ } }

    private fun today(): LocalDate = java.time.Instant.ofEpochMilli(clock.now()).atZone(zone()).toLocalDate()

    /** A task that is back on the list gets its first nudge worked out again. */
    private fun withFirstNudge(t: TaskEntity): TaskEntity =
        t.copy(nextNudgeOn = NudgePlan.firstNudgeOn(t, today())?.toString())

    private suspend fun ownerAction(
        recordId: String,
        what: RevisionKind,
        revisionValue: (TaskEntity) -> String,
        change: (TaskEntity) -> TaskEntity,
    ): TaskEntity? = ownerActionInner(recordId, what, revisionValue, change).also { if (it != null) notifyChanged() }

    private suspend fun ownerActionInner(
        recordId: String,
        what: RevisionKind,
        revisionValue: (TaskEntity) -> String,
        change: (TaskEntity) -> TaskEntity,
    ): TaskEntity? = db.withTransaction {
        val t = db.tasks().get(recordId) ?: return@withTransaction null
        val r = db.records().get(recordId) ?: return@withTransaction null
        val n = change(t)
        db.tasks().update(finish(n))
        if (!r.pinned) db.records().update(r.copy(pinned = true).stamped(clock))
        revisions.write(what, captureId = r.captureId, recordId = recordId, old = t.state.name, new = revisionValue(n))
        t
    }

    suspend fun markDone(recordId: String): TaskEntity? =
        ownerAction(recordId, RevisionKind.TASK_DONE, { "DONE" }) { TaskRules.done(it, ClosedHow.TAP, null, clock.now()) }

    suspend fun snooze(recordId: String, until: LocalDate): TaskEntity? =
        ownerAction(recordId, RevisionKind.SNOOZED, { until.toString() }) { TaskRules.snooze(it, until, clock.now()) }

    suspend fun drop(recordId: String): TaskEntity? =
        ownerAction(recordId, RevisionKind.DROPPED, { "DROPPED" }) { TaskRules.drop(it, clock.now()) }

    /** "Not done": from Done recently (or not needed) back to the list. */
    suspend fun reopen(recordId: String): TaskEntity? =
        ownerAction(recordId, RevisionKind.TASK_REOPENED, { "OPEN" }) { withFirstNudge(TaskRules.reopen(it, clock.now())) }

    /** "Bring back": from Stopped asking back to the list. */
    suspend fun bringBack(recordId: String): TaskEntity? =
        ownerAction(recordId, RevisionKind.TASK_REOPENED, { "BROUGHT_BACK" }) { withFirstNudge(TaskRules.bringBack(it, clock.now())) }

    /** Undo: put the row back as it was before an action. */
    suspend fun restore(previous: TaskEntity) {
        restoreInner(previous)
        notifyChanged()
    }

    private suspend fun restoreInner(previous: TaskEntity) {
        db.withTransaction {
            val current = db.tasks().get(previous.recordId) ?: return@withTransaction
            val r = db.records().get(previous.recordId) ?: return@withTransaction
            db.tasks().update(finish(previous))
            revisions.write(RevisionKind.TASK_REOPENED, captureId = r.captureId, recordId = previous.recordId, old = current.state.name, new = "UNDO:" + previous.state.name)
        }
    }

    /**
     * What he said finished this task (docs/cards/05 B6): the task is done, both records are pinned, and the closing
     * record's capture gets the revision. Returns false if the task is not open or snoozed any more.
     */
    suspend fun closeBySpeaking(taskRecordId: String, byRecordId: String): Boolean =
        closeBySpeakingInner(taskRecordId, byRecordId).also { if (it) notifyChanged() }

    private suspend fun closeBySpeakingInner(taskRecordId: String, byRecordId: String): Boolean = db.withTransaction {
        val t = db.tasks().get(taskRecordId) ?: return@withTransaction false
        if (!TaskRules.isOpenOrSnoozed(t)) return@withTransaction false
        val by = db.records().get(byRecordId) ?: return@withTransaction false
        val taskRecord = db.records().get(taskRecordId) ?: return@withTransaction false
        db.tasks().update(finish(TaskRules.done(t, ClosedHow.SPOKEN, byRecordId, clock.now())))
        if (!taskRecord.pinned) db.records().update(taskRecord.copy(pinned = true).stamped(clock))
        if (!by.pinned) db.records().update(by.copy(pinned = true).stamped(clock))
        revisions.write(RevisionKind.TASK_DONE, captureId = by.captureId, recordId = taskRecordId, old = t.state.name, new = "SPOKEN")
        true
    }

    /**
     * A new task says what an open one already says: the new record is kept (the capture is truth), its task is
     * dropped and marked, and the existing task is bumped. Not an owner action: no revision, no pin.
     */
    suspend fun foldDuplicate(newRecordId: String, existingRecordId: String): Boolean =
        foldInner(newRecordId, existingRecordId).also { if (it) notifyChanged() }

    private suspend fun foldInner(newRecordId: String, existingRecordId: String): Boolean = db.withTransaction {
        val t = db.tasks().get(newRecordId) ?: return@withTransaction false
        val existing = db.tasks().get(existingRecordId) ?: return@withTransaction false
        val r = db.records().get(newRecordId) ?: return@withTransaction false
        val now = clock.now()
        db.tasks().update(finish(TaskRules.drop(t, now)))
        db.tasks().update(finish(TaskRules.touch(existing, now)))
        db.records().update(r.copy(attention = app.stash.domain.Attention.DUPLICATE_OF_PREFIX + existingRecordId).stamped(clock))
        true
    }

    /** Daily upkeep (docs/cards/05 D): wake the snoozed, stop asking about the forgotten, recompute `expires_on`. */
    suspend fun upkeep(today: LocalDate): Upkeep = upkeepInner(today).also { notifyChanged() }

    /** Snoozed tasks whose day has come are open again with their nudge set to that day (card 06: "Later today"). No expiry. */
    suspend fun wakeDue(today: LocalDate): Int = db.withTransaction {
        var woke = 0
        val now = clock.now()
        for (id in db.tasks().activeRecordIds()) {
            val t = db.tasks().get(id) ?: continue
            val w = TaskRules.wake(t, today, now)
            if (w !== t) { db.tasks().update(w.stamped(clock)); woke++ }
        }
        woke
    }

    private suspend fun upkeepInner(today: LocalDate): Upkeep = db.withTransaction {
        var woke = 0
        var expired = 0
        val now = clock.now()
        val life = lifetimes()
        val z = zone()
        for (id in db.tasks().activeRecordIds()) {
            var t = db.tasks().get(id) ?: continue
            val before = t
            val woken = TaskRules.wake(t, today, now)
            if (woken !== t) { t = woken; woke++ }
            if (t.state == TaskState.OPEN && TaskRules.expiresOn(t, life, z).isBefore(today)) {
                t = TaskRules.expire(t, now)
                expired++
            }
            val next = t.copy(expiresOn = if (TaskRules.isOpenOrSnoozed(t)) TaskRules.expiresOn(t, life, z).toString() else null)
            if (next != before) db.tasks().update(next.stamped(clock))
        }
        Upkeep(woke, expired)
    }

    data class Upkeep(val woke: Int, val expired: Int)
}
