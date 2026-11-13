package app.stash.data

import app.stash.ask.LookupSource
import app.stash.ask.NoteRow
import app.stash.domain.ReadingCatalog
import app.stash.domain.ReadingFact
import app.stash.domain.ReadingSession
import app.stash.domain.SearchDocs

/**
 * What card 08's lookups read, over the database (docs/STORAGE.md rule 1: the lookups never touch a DAO). Read-only.
 *
 * Privacy: with `includeSensitive = false` a row is left out when its capture is Private or when any subject it names
 * (its subject, item, person or place) is Private. No app gate exists yet (card 02), so the app passes false
 * everywhere; [privateNotesForCounting] is the one door to Private rows, and its caller only counts them.
 */
class AskRepository(
    private val db: StashDatabase,
    private val index: SearchIndex,
    private val readings: ReadingRepository,
) : LookupSource {

    override suspend fun notes(includeSensitive: Boolean): List<NoteRow> =
        rows().filter { includeSensitive || !it.second }.map { it.first }

    override suspend fun privateNotesForCounting(): List<NoteRow> = rows().filter { it.second }.map { it.first }

    /** Every live row, each with whether it is Private (its capture, or a subject it names). */
    private suspend fun rows(): List<Pair<NoteRow, Boolean>> {
        val captures = db.captures().live()
        val subjects = db.subjects().all().associateBy { it.id }
        val pictures = db.attachments().pictureCounts().associate { it.captureId to it.count }
        // A live record that another live record supersedes is history.
        val superseded = captures.flatMap { c -> c.records }.filter { it.record.deletedAt == null }
            .mapNotNull { it.record.supersedesRecordId }.toSet()
        val out = mutableListOf<Pair<NoteRow, Boolean>>()
        for (c in captures) {
            val cap = c.capture
            val pics = pictures[cap.id] ?: 0
            val live = c.records.filter { it.record.deletedAt == null }.sortedBy { it.record.seq }
            if (live.isEmpty()) {
                if (cap.text.isBlank() && pics == 0) continue // a recording still waiting for its words
                val text = cap.text.ifBlank { "A note with a picture" }
                out += NoteRow(null, cap.id, null, text.take(80), cap.text, cap.text, cap.createdAt, pictures = pics) to cap.sensitive
                continue
            }
            for (r in live) {
                val rec = r.record
                val w = r.whereabouts
                val subject = rec.subjectId?.let { subjects[it] }
                val item = w?.itemSubjectId?.let { subjects[it] }
                val person = w?.personSubjectId?.let { subjects[it] }
                val place = rec.placeSubjectId?.let { subjects[it] }
                val atPlace = r.task?.atPlaceSubjectId?.let { subjects[it] }
                val private = cap.sensitive || listOf(subject, item, person, place, atPlace).any { it?.sensitive == true }
                out += NoteRow(
                    recordId = rec.id, captureId = cap.id, kind = rec.kind, title = rec.title, quote = rec.quote,
                    captureText = cap.text, capturedAt = cap.createdAt,
                    subjectId = rec.subjectId, subjectName = subject?.name, subjectAliases = SearchDocs.jsonList(subject?.aliases),
                    place = rec.placeText?.takeIf { it.isNotBlank() } ?: place?.name,
                    itemSubjectId = w?.itemSubjectId, itemText = w?.itemText,
                    itemNames = listOfNotNull(item?.name) + SearchDocs.jsonList(item?.aliases),
                    whereText = w?.whereText, personText = w?.personText ?: person?.name, direction = w?.direction,
                    saidDateText = w?.saidDateText, returnedAt = w?.returnedAt,
                    volatility = item?.volatility ?: subject?.volatility,
                    happenedOn = rec.happenedOn, superseded = rec.id in superseded, pictures = pics,
                ) to private
            }
        }
        return out
    }

    override suspend fun textHits(words: String): Set<String> =
        index.search(words).flatMap { listOf(it.docId, it.captureId) }.toSet()

    override suspend fun tasks(includeSensitive: Boolean): List<TaskItem> {
        val subjects = db.subjects().all().filter { it.sensitive }.map { it.id }.toSet()
        return TaskItems.from(db.captures().live(), includeSensitive).filter { i ->
            includeSensitive || (i.record.subjectId !in subjects && i.task.atPlaceSubjectId !in subjects && i.record.placeSubjectId !in subjects)
        }
    }

    override suspend fun readingCatalog(includeSensitive: Boolean): ReadingCatalog = readings.catalog(includeSensitive)

    override suspend fun latestReadings(metricId: String, n: Int, includeSensitive: Boolean): List<ReadingFact> =
        readings.latest(metricId, n, includeSensitive)

    override suspend fun latestSession(subjectId: String, includeSensitive: Boolean): ReadingSession? =
        readings.latestSession(subjectId, includeSensitive)
}
