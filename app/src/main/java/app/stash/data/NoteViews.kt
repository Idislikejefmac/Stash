package app.stash.data

import app.stash.domain.NoteKind
import app.stash.domain.NoteView
import org.json.JSONObject

/**
 * Turns stored captures and records into the flat [NoteView]s that [app.stash.domain.Searcher] and the answer
 * wording work on. One view per live record (a capture with none appears once, as plain text), so supersession
 * links are record ids.
 */
object NoteViews {
    /** [hiddenLinks]: record id to what it superseded, for records that are deleted, so chains can skip over them. */
    fun from(captures: List<CaptureWithRecords>, hiddenLinks: Map<String, String?>): List<NoteView> {
        val out = mutableListOf<NoteView>()
        for (c in captures) {
            val live = c.records.filter { it.record.deletedAt == null }.sortedBy { it.record.seq }
            if (live.isEmpty()) {
                out += NoteView(c.capture.id, c.capture.createdAt, c.capture.text, NoteKind.OTHER)
                continue
            }
            for (r in live) out += view(c.capture, r, hiddenLinks)
        }
        return out
    }

    fun view(capture: CaptureEntity, r: RecordWithParts, hiddenLinks: Map<String, String?>): NoteView {
        val w = r.whereabouts
        val item = w?.itemText ?: oldItem(r)
        return NoteView(
            id = r.record.id,
            createdAt = capture.createdAt,
            text = capture.text,
            kind = r.record.kind.toNoteKind(),
            item = item,
            place = w?.whereText ?: r.task?.atPlaceText,
            person = w?.personText,
            eventDate = w?.saidDateText,
            supersedesId = resolve(r.record.supersedesRecordId, hiddenLinks),
        )
    }

    /** A reminder's item is only in the prototype extractor's output (tasks have no item column); read it from there. */
    private fun oldItem(r: RecordWithParts): String? {
        val e = r.extraction ?: return null
        if (e.schemaVersion != 0) return null
        return try {
            val j = JSONObject(e.outputJson)
            if (j.isNull("item")) null else j.optString("item").takeIf { it.isNotBlank() }
        } catch (ex: Exception) {
            null
        }
    }

    private fun resolve(start: String?, hidden: Map<String, String?>): String? {
        var cur = start
        val seen = mutableSetOf<String>()
        while (cur != null && cur in hidden && seen.add(cur)) cur = hidden[cur]
        return cur
    }
}
