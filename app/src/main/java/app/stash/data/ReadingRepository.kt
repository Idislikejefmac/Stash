package app.stash.data

import app.stash.domain.MetricInfo
import app.stash.domain.ReadingAnswers
import app.stash.domain.ReadingCatalog
import app.stash.domain.ReadingFact
import app.stash.domain.ReadingQuestion
import app.stash.domain.ReadingQuestions
import app.stash.domain.ReadingSession
import app.stash.domain.SearchDocs
import app.stash.domain.SubjectInfo

/** One metric for the readings list: its newest value and how many there are. */
data class MetricSummary(val metric: MetricInfo, val latest: ReadingFact, val count: Int)

/** One reading record: when, and where it came from. For rhythm (card 07 pass 2). */
data class SessionRef(val recordId: String, val captureId: String, val measuredAt: Long)

/**
 * Read-only queries over readings (docs/cards/07 section A). Card 08's tools call these. Every function takes
 * [includeSensitive] with no default (docs/cards/00 rule 4): a reading from a Private note, or about a Private
 * subject, is returned only when the caller says it may be drawn. There is no app gate yet (card 02), so nothing
 * in the app passes true; the screens and the question answering pass false.
 *
 * Nothing here writes, and nothing here corrects a value: it is what was said.
 */
class ReadingRepository(private val db: StashDatabase) {
    private suspend fun rows(includeSensitive: Boolean): List<ReadingFact> =
        db.readings().visible(if (includeSensitive) 1 else 0).map(::fact)

    private suspend fun rowsOf(metricId: String, includeSensitive: Boolean): List<ReadingFact> =
        db.readings().forMetric(metricId, if (includeSensitive) 1 else 0).map(::fact)

    /** The newest [n] values of a metric, newest first. */
    suspend fun latest(metricId: String, n: Int, includeSensitive: Boolean): List<ReadingFact> =
        rowsOf(metricId, includeSensitive).take(n.coerceAtLeast(0))

    /** The values measured from [from] to [to] (both included), oldest first. */
    suspend fun series(metricId: String, from: Long, to: Long, includeSensitive: Boolean): List<ReadingFact> =
        rowsOf(metricId, includeSensitive).filter { it.measuredAt in from..to }.reversed()

    /** Every value of a metric, newest first. */
    suspend fun all(metricId: String, includeSensitive: Boolean): List<ReadingFact> = rowsOf(metricId, includeSensitive)

    /** The newest reading record of a subject with all its rows, in the order he said them. */
    suspend fun latestSession(subjectId: String, includeSensitive: Boolean): ReadingSession? {
        val mine = rows(includeSensitive).filter { it.subjectId == subjectId }
        val newest = mine.firstOrNull() ?: return null
        val rows = mine.filter { it.recordId == newest.recordId }
        return ReadingSession(newest.recordId, newest.captureId, newest.measuredAt, rows)
    }

    /** The metrics a subject has readings for, in the order they were first said. */
    suspend fun metricsFor(subjectId: String, includeSensitive: Boolean): List<MetricInfo> =
        catalog(includeSensitive).metrics.filter { it.subjectId == subjectId }

    /** The reading records of a subject by date, oldest first (for rhythm, card 07 pass 2). */
    suspend fun sessionsFor(subjectId: String, includeSensitive: Boolean): List<SessionRef> =
        rows(includeSensitive).filter { it.subjectId == subjectId }
            .distinctBy { it.recordId }.map { SessionRef(it.recordId, it.captureId, it.measuredAt) }.reversed()

    /**
     * A spoken name matched against metric label, key and aliases across subjects. The same name under two subjects
     * returns both, newest first. Only metrics that have a reading the caller may draw are returned.
     */
    suspend fun findMetric(words: String, includeSensitive: Boolean): List<MetricInfo> {
        val want = ReadingQuestions.norm(words)
        if (want.isEmpty()) return emptyList()
        return catalog(includeSensitive).metrics.filter { m ->
            (listOf(m.label, m.key) + m.aliases).any { ReadingQuestions.norm(it) == want }
        }.sortedByDescending { it.lastAt }
    }

    /** What he has logged and the caller may draw: the only names a question can match. */
    suspend fun catalog(includeSensitive: Boolean): ReadingCatalog {
        val visible = rows(includeSensitive)
        val metrics = db.metrics().all().associateBy { it.id }
        val subjects = db.subjects().all().associateBy { it.id }
        val infos = visible.groupBy { it.metricId }.mapNotNull { (id, list) ->
            val m = metrics[id] ?: return@mapNotNull null
            MetricInfo(
                id, m.label, m.key, SearchDocs.jsonList(m.aliases), m.subjectId, m.subjectId?.let { subjects[it]?.name },
                list.maxOf { it.measuredAt },
            )
        }.sortedBy { metrics[it.metricId]?.createdAt ?: 0L }
        val subjectInfos = visible.mapNotNull { it.subjectId }.distinct().mapNotNull { id ->
            val s = subjects[id] ?: return@mapNotNull null
            SubjectInfo(id, s.name, SearchDocs.jsonList(s.aliases), visible.filter { it.subjectId == id }.maxOf { it.measuredAt })
        }
        return ReadingCatalog(infos, subjectInfos)
    }

    /** One line per metric: its newest value and how many values there are. Newest metric first. */
    suspend fun summaries(includeSensitive: Boolean): List<MetricSummary> {
        val visible = rows(includeSensitive)
        val byMetric = visible.groupBy { it.metricId }
        return catalog(includeSensitive).metrics.mapNotNull { m ->
            val list = byMetric[m.metricId] ?: return@mapNotNull null
            MetricSummary(m, list.first(), list.size)
        }.sortedByDescending { it.latest.measuredAt }
    }

    /**
     * The coded answer to a readings question, or null when the sentence is not one (or names nothing he has logged).
     * No model; nothing leaves the phone.
     */
    suspend fun answer(question: String, now: Long, includeSensitive: Boolean): String? {
        val cat = catalog(includeSensitive)
        return when (val q = ReadingQuestions.parse(question, cat)) {
            null -> null
            is ReadingQuestion.LastValue -> ReadingAnswers.lastValue(latest(q.metricId, 2, includeSensitive), now, q.named)
            is ReadingQuestion.LastSession -> {
                val session = latestSession(q.subjectId, includeSensitive) ?: return null
                val name = cat.subjects.firstOrNull { it.subjectId == q.subjectId }?.name ?: return null
                ReadingAnswers.lastSession(name, session, now)
            }
        }
    }

    private fun fact(r: ReadingRow) = ReadingFact(
        r.readingId, r.recordId, r.captureId, r.subjectId, r.subjectName, r.metricId, r.metricLabel, r.valueText, r.valueNum,
        r.unit, r.measuredAt, r.placeText, r.attention, r.quote,
    )
}
