package app.stash.data

import androidx.room.withTransaction
import app.stash.domain.SearchDocs

data class Hit(val docType: DocType, val docId: String, val captureId: String)

/**
 * The full-text index (`search_fts`). Maintained by the repositories (never by triggers) and rebuildable from the
 * tables at any time; it is never backed up. The `index*` and `remove*` methods are meant to be called inside a
 * repository's transaction.
 */
class SearchIndex(private val db: StashDatabase) {
    suspend fun indexCapture(c: CaptureEntity) {
        db.search().remove(listOf(c.id))
        db.search().add(c.id, DocType.CAPTURE.name, SearchDocs.captureBody(c.text, db.attachments().ocrTexts(c.id)))
    }

    suspend fun indexRecord(r: RecordEntity) {
        db.search().remove(listOf(r.id))
        val subject = r.subjectId?.let { db.subjects().nameOf(it) }
        val labels = db.readings().metricLabelsFor(r.id)
        db.search().add(r.id, DocType.RECORD.name, SearchDocs.recordBody(r.title, r.quote, r.aliases, r.tags, subject, labels))
    }

    suspend fun remove(docIds: List<String>) {
        if (docIds.isEmpty()) return
        db.search().remove(docIds)
    }

    /** Drop the whole index and build it again from the live captures and records. */
    suspend fun rebuild() = db.withTransaction {
        db.search().clear()
        val live = db.captures().live()
        for (c in live) {
            indexCapture(c.capture)
            for (r in c.records) if (r.record.deletedAt == null) indexRecord(r.record)
        }
    }

    /** Captures and records whose indexed text matches every word typed (each as a prefix). Deleted ones are never indexed. */
    suspend fun search(query: String): List<Hit> {
        val q = SearchDocs.matchQuery(query) ?: return emptyList()
        return db.search().match(q).mapNotNull { k ->
            val type = DocType.valueOf(k.docType)
            when (type) {
                DocType.CAPTURE -> Hit(type, k.docId, k.docId)
                DocType.RECORD -> db.records().get(k.docId)?.let { Hit(type, k.docId, it.captureId) }
            }
        }
    }
}
