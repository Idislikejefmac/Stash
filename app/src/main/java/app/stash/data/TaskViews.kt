package app.stash.data

/** One task with the record and capture it belongs to, and (if he closed it by speaking) the words that closed it. */
data class TaskItem(
    val task: TaskEntity,
    val record: RecordEntity,
    val capture: CaptureEntity,
    /** The record that closed it, when closed by speaking and that record is still there. */
    val closedBy: RecordEntity? = null,
    val closedByCapture: CaptureEntity? = null,
)

/** Flat tasks from stored captures. Pure. */
object TaskItems {
    /**
     * Every task of a live record. [includeSensitive] has no default (docs/cards/00 rule 4): a task from a Private
     * capture is only returned when the caller says it may be drawn. Nothing yet may, until the app gate (card 02) exists.
     */
    fun from(captures: List<CaptureWithRecords>, includeSensitive: Boolean): List<TaskItem> {
        val byId = HashMap<String, Pair<RecordEntity, CaptureEntity>>()
        for (c in captures) for (r in c.records) if (r.record.deletedAt == null) byId[r.record.id] = r.record to c.capture
        val out = mutableListOf<TaskItem>()
        for (c in captures) {
            if (c.capture.sensitive && !includeSensitive) continue
            for (r in c.records) {
                val t = r.task ?: continue
                if (r.record.deletedAt != null) continue
                val closer = t.closedByRecordId?.let { byId[it] }
                // Words from a Private capture are never shown beside a task that is not Private.
                val showCloser = closer != null && (!closer.second.sensitive || c.capture.sensitive)
                out += TaskItem(t, r.record, c.capture, if (showCloser) closer?.first else null, if (showCloser) closer?.second else null)
            }
        }
        return out
    }
}
