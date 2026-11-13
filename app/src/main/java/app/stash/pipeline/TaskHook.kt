package app.stash.pipeline

import app.stash.data.Clock
import app.stash.data.RecordRepository
import app.stash.data.SearchIndex
import app.stash.data.Store
import app.stash.data.TaskItems
import app.stash.data.TaskRepository
import app.stash.data.TaskState
import app.stash.domain.Attention
import app.stash.domain.CloseDecision
import app.stash.domain.CloseSetting
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.DuplicateVerdict
import app.stash.domain.TaskToClose
import app.stash.domain.RecordKind
import app.stash.domain.SpokenRecord
import app.stash.domain.TaskCloser
import app.stash.domain.TaskCompare
import app.stash.domain.TaskDuplicates
import app.stash.domain.TaskLifetimes
import app.stash.domain.TaskRules
import java.time.ZoneId

/**
 * Card 05's work after a capture is read: fold a repeated task into the open one, and close (or ask about) the task
 * that something he said finished. Registered as an [AfterProjection] hook, so it runs after the records are written
 * in their own transaction; a failure is an event and leaves the records as they are (docs/cards/13).
 *
 * Everything here stays on the phone. Tasks and events are only compared within the same privacy class: a note that is
 * not Private never gets a reason that names a Private task, and the other way round.
 */
class TaskHook(
    private val store: () -> Store,
    private val setting: () -> CloseSetting,
    private val clock: Clock = Clock.System,
    private val lifetimes: () -> TaskLifetimes = { TaskLifetimes() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val changed: () -> Unit = {},
) : AfterProjection {
    override suspend fun afterProjection(captureId: String, recordIds: List<String>) {
        val db = (store() as? Store.Ready)?.db ?: return
        val records = RecordRepository(db, SearchIndex(db), clock)
        val tasks = TaskRepository(db, clock, lifetimes, zone, changed)

        val all = db.captures().live()
        val mine = all.firstOrNull { it.capture.id == captureId } ?: return
        val capture = mine.capture
        // Local decisions only: includeSensitive is true so Private tasks can be matched with Private notes, never shown.
        val others = TaskItems.from(all, includeSensitive = true).filter {
            it.capture.id != captureId && it.capture.sensitive == capture.sensitive && TaskRules.isOpenOrSnoozed(it.task) &&
                app.stash.domain.TaskSections.isFolded(it).not()
        }

        val fresh = mine.records.filter { it.record.id in recordIds && it.record.deletedAt == null }.sortedBy { it.record.seq }
        for (rp in fresh) {
            val r = rp.record
            val t = rp.task
            if (r.kind == RecordKind.TASK && t != null && t.state == TaskState.OPEN) {
                val older = others.filter { it.capture.createdAt <= capture.createdAt }
                    .map { TaskCompare(it.record.id, it.record.title, it.task.atPlaceText) }
                when (val v = TaskDuplicates.check(TaskCompare(r.id, r.title, t.atPlaceText), older)) {
                    is DuplicateVerdict.Duplicate -> if (tasks.foldDuplicate(r.id, v.existingRecordId)) {
                        Dbg.event(DbgComponent.TASK, DbgCode.FOLDED, refId = r.id)
                    }
                    is DuplicateVerdict.Near -> {
                        records.setAttention(r.id, Attention.SAME_AS_TASK_PREFIX + v.existingRecordId)
                        Dbg.event(DbgComponent.TASK, DbgCode.FLAGGED, refId = r.id)
                    }
                    DuplicateVerdict.None -> {}
                }
            }
            if (r.claimsToCloseRecordId == null && r.kind != RecordKind.EVENT) continue
            val open = others.map { TaskToClose(it.record.id, it.record.title, it.record.quote, it.task.state, it.record.subjectId) }
            val spoken = SpokenRecord(r.id, r.kind, r.title, r.quote, r.confidence, r.claimsToCloseRecordId, r.subjectId)
            when (val d = TaskCloser.decide(spoken, open, setting())) {
                is CloseDecision.Close -> if (tasks.closeBySpeaking(d.taskRecordId, r.id)) Dbg.event(DbgComponent.TASK, DbgCode.CLOSED, refId = d.taskRecordId)
                is CloseDecision.Ask -> {
                    records.setAttention(r.id, Attention.DID_THIS_FINISH_PREFIX + d.taskRecordId)
                    Dbg.event(DbgComponent.TASK, DbgCode.ASKED, refId = r.id)
                }
                CloseDecision.Nothing -> {}
            }
        }
    }
}
