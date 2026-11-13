package app.stash.ask

import app.stash.data.Direction
import app.stash.data.TaskItem
import app.stash.data.TaskState
import app.stash.data.Volatility
import app.stash.domain.Age
import app.stash.domain.ReadingCatalog
import app.stash.domain.ReadingFact
import app.stash.domain.ReadingQuestions
import app.stash.domain.ReadingSession
import app.stash.domain.RecordKind
import app.stash.domain.Searcher
import app.stash.domain.TaskLabels
import app.stash.domain.TaskSections
import app.stash.domain.TaskText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One record (or one capture that has no records yet) as the lookups see it: plain values, no Room. The source
 * decides what may be in the list ([LookupSource.notes] with `includeSensitive`); the lookups only rank and shape.
 */
data class NoteRow(
    /** Null for a capture with no live records. */
    val recordId: String?,
    val captureId: String,
    val kind: RecordKind?,
    val title: String,
    val quote: String,
    val captureText: String,
    val capturedAt: Long,
    val subjectId: String? = null,
    val subjectName: String? = null,
    val subjectAliases: List<String> = emptyList(),
    /** The record's place words, or its place subject's name. */
    val place: String? = null,
    val itemSubjectId: String? = null,
    val itemText: String? = null,
    val itemNames: List<String> = emptyList(),
    val whereText: String? = null,
    val personText: String? = null,
    val direction: Direction? = null,
    val saidDateText: String? = null,
    val returnedAt: Long? = null,
    val volatility: Volatility? = null,
    /** An ISO local date he said it happened on, when the extractor worked one out. */
    val happenedOn: String? = null,
    /** Another live record replaces this one (it is history, not current). */
    val superseded: Boolean = false,
    val pictures: Int = 0,
)

/** What a lookup reads. Implemented over the repositories (`data/AskRepository`); tests may pass their own. */
interface LookupSource {
    /** Every live record (and every capture with none). Private ones, and ones about a Private subject, only if [includeSensitive]. */
    suspend fun notes(includeSensitive: Boolean): List<NoteRow>

    /**
     * The same rows for Private notes only, for one purpose: counting how many also match a question (card 08 item 15).
     * Their words are never shown, sent, spoken or stored by the caller.
     */
    suspend fun privateNotesForCounting(): List<NoteRow>

    /** Ids (capture and record) of the indexed documents that hold every word of [words] (each as a prefix). */
    suspend fun textHits(words: String): Set<String>

    suspend fun tasks(includeSensitive: Boolean): List<TaskItem>
    suspend fun readingCatalog(includeSensitive: Boolean): ReadingCatalog
    suspend fun latestReadings(metricId: String, n: Int, includeSensitive: Boolean): List<ReadingFact>
    suspend fun latestSession(subjectId: String, includeSensitive: Boolean): ReadingSession?
}

/** A note as an answer shows it: what kind, its title and quote, how old (in words), where, and whether it has a picture. */
data class NoteHit(
    val recordId: String?,
    val captureId: String,
    val kind: RecordKind?,
    val title: String,
    val quote: String,
    val age: String,
    val place: String?,
    val hasPicture: Boolean,
) {
    /** The id an answer's sources and the trace keep: the record's, or the capture's when there is no record. */
    val id: String get() = recordId ?: captureId
}

data class TaskHit(val recordId: String, val captureId: String, val title: String, val due: String?, val age: String)

data class OpenTasks(val items: List<TaskItem>, val hits: List<TaskHit>)

data class ReadingHit(
    val recordId: String,
    val captureId: String,
    val subject: String?,
    val metric: String,
    val value: String,
    val unit: String?,
    val age: String,
)

data class Whereabout(
    val recordId: String,
    val captureId: String,
    val item: String,
    val where: String?,
    val person: String?,
    val direction: Direction,
    val age: String,
    val saidDate: String?,
    val quote: String,
)

data class WhereIs(val current: Whereabout, val previous: List<Whereabout>, val stale: Boolean)

data class LoanHit(
    val recordId: String,
    val captureId: String,
    val item: String,
    val person: String?,
    val direction: Direction,
    /** "January 5th" as he said it, or null. */
    val saidDate: String?,
    val age: String,
)

data class EventHit(val recordId: String, val captureId: String, val title: String, val quote: String, val age: String)

data class AboutSubject(val name: String, val counts: Map<RecordKind, Int>, val newest: Map<RecordKind, List<NoteHit>>) {
    val total: Int get() = counts.values.sum()
}

data class CaptureHit(val captureId: String, val title: String, val text: String, val age: String, val recordIds: List<String>)

/**
 * The read-only lookups (docs/cards/08 section A). Each takes [includeSensitive] with no default (docs/cards/00 rule 4):
 * a Private note, or a note about a Private subject, is returned only when the caller passes true. No app gate exists
 * yet (card 02), so nothing in the app does. Ages are in words; nothing here returns a timestamp to an answer. Nothing
 * here writes.
 */
class Lookups(
    private val source: LookupSource,
    private val now: () -> Long,
    private val zone: () -> ZoneId,
) {
    companion object {
        const val MAX_NOTES = 8
        const val STALE_DAILY_DAYS = 2L
        const val STALE_NORMAL_DAYS = 90L
        const val STALE_STORED_DAYS = 365L
        private const val DAY_MS = 86_400_000L

        fun staleAfterDays(v: Volatility?): Long = when (v) {
            Volatility.DAILY -> STALE_DAILY_DAYS
            Volatility.STORED -> STALE_STORED_DAYS
            Volatility.NORMAL, null -> STALE_NORMAL_DAYS
        }

        private val LEAD_ARTICLE = Regex("^(?:my|the|our|a|an)\\s+", RegexOption.IGNORE_CASE)

        /** "my reading glasses" as "reading glasses". */
        fun bare(s: String): String = s.trim().replace(LEAD_ARTICLE, "").trim()
    }

    private fun age(t: Long) = Age.describe(t, now())

    private fun localDate(t: Long): LocalDate = Instant.ofEpochMilli(t).atZone(zone()).toLocalDate()

    /** When it happened: the date he said, else when he said it. */
    private fun eventTime(n: NoteRow): Long = n.happenedOn?.let {
        runCatching { LocalDate.parse(it).atTime(12, 0).atZone(zone()).toInstant().toEpochMilli() }.getOrNull()
    }?.coerceAtMost(n.capturedAt) ?: n.capturedAt

    private fun hit(n: NoteRow) = NoteHit(
        n.recordId, n.captureId, n.kind, n.title, n.quote, age(n.capturedAt), n.place ?: n.whereText, n.pictures > 0,
    )

    private fun stems(s: String?): Set<String> = if (s.isNullOrBlank()) emptySet() else TaskText.stems(s)

    /** Both non-empty and one holds all of the other: "glasses" finds "reading glasses", and the reverse. */
    private fun names(want: Set<String>, have: Set<String>) = want.isNotEmpty() && have.isNotEmpty() && (have.containsAll(want) || want.containsAll(have))

    // ---- searchNotes

    /**
     * Up to [MAX_NOTES] notes. [query] may be empty when [place] or [on] is given. Ranking (card 08 A2): how many of the
     * question's words the full-text index found, then a match in the subject or item above the title above the quote,
     * then current above superseded, then newer above older.
     */
    suspend fun searchNotes(
        query: String?,
        kind: RecordKind? = null,
        on: DateRange? = null,
        place: String? = null,
        hasPicture: Boolean? = null,
        includeSensitive: Boolean,
    ): List<NoteHit> = rank(source.notes(includeSensitive), query, kind, on, place, hasPicture).map(::hit)

    /** How many Private notes (counted by capture) match [query]. A number only: no words leave this function. */
    suspend fun privateMatchCount(query: String): Int =
        rank(source.privateNotesForCounting(), query, null, null, null, null, limit = Int.MAX_VALUE).map { it.captureId }.distinct().size

    private suspend fun rank(
        rows: List<NoteRow>,
        query: String?,
        kind: RecordKind?,
        on: DateRange?,
        place: String?,
        hasPicture: Boolean?,
        limit: Int = MAX_NOTES,
    ): List<NoteRow> {
        // The words as typed (the index stems them itself), without the question's own words ("where", "my", ...).
        val words = query?.let { Searcher.tokens(it).filter { w -> !Searcher.isStopWord(w) }.distinct() }.orEmpty()
        if (words.isEmpty() && on == null && place.isNullOrBlank()) return emptyList()
        val wantPlace = stems(place)
        var candidates = rows.filter { n ->
            (kind == null || n.kind == kind) &&
                (on == null || localDate(n.capturedAt) in on || n.happenedOn?.let { d -> runCatching { LocalDate.parse(d) in on }.getOrDefault(false) } == true) &&
                (wantPlace.isEmpty() || names(wantPlace, stems(n.place)) || names(wantPlace, stems(n.whereText))) &&
                (hasPicture == null || (n.pictures > 0) == hasPicture)
        }
        if (words.isEmpty()) return candidates.sortedByDescending { it.capturedAt }.take(limit)
        val hitsPerWord = words.map { source.textHits(it) }
        fun found(n: NoteRow) = hitsPerWord.count { h -> n.captureId in h || (n.recordId != null && n.recordId in h) }
        val scored = candidates.map { it to found(it) }.filter { it.second > 0 }
        val wordStems = words.map(Searcher::stem).toSet()
        fun fieldTier(n: NoteRow): Int {
            fun has(s: String?) = s != null && Searcher.tokens(s).map(Searcher::stem).any { it in wordStems || wordStems.any { w -> it.startsWith(w) } }
            return when {
                has(n.subjectName) || has(n.itemText) || n.itemNames.any { has(it) } || n.subjectAliases.any { has(it) } -> 3
                has(n.title) -> 2
                has(n.quote) -> 1
                else -> 0
            }
        }
        candidates = scored.sortedWith(
            compareByDescending<Pair<NoteRow, Int>> { it.second }
                .thenByDescending { fieldTier(it.first) }
                .thenBy { if (it.first.superseded) 1 else 0 }
                .thenByDescending { it.first.capturedAt },
        ).map { it.first }
        return candidates.take(limit)
    }

    // ---- openTasks

    /** Open tasks (not put off, not folded into another), optionally about a subject or at a place. */
    suspend fun openTasks(subject: String?, atPlace: String?, includeSensitive: Boolean): OpenTasks {
        val wantSubject = stems(subject)
        val wantPlace = stems(atPlace)
        val items = source.tasks(includeSensitive).filter { i ->
            i.task.state == TaskState.OPEN && !TaskSections.isFolded(i) &&
                (wantPlace.isEmpty() || names(wantPlace, stems(i.task.atPlaceText))) &&
                (wantSubject.isEmpty() || names(wantSubject, stems(i.record.title)) || wantSubject.all { it in stems(i.record.title + " " + i.record.quote) })
        }
        return OpenTasks(items, items.map { TaskHit(it.record.id, it.capture.id, it.record.title, TaskLabels.dueWords(it), age(it.capture.createdAt)) })
    }

    // ---- readings

    suspend fun catalog(includeSensitive: Boolean): ReadingCatalog = source.readingCatalog(includeSensitive)

    /** The newest [n] values of one metric, newest first. */
    suspend fun metricReadings(metricId: String, n: Int, includeSensitive: Boolean): List<ReadingFact> =
        source.latestReadings(metricId, n, includeSensitive)

    suspend fun lastSession(subjectId: String, includeSensitive: Boolean): ReadingSession? = source.latestSession(subjectId, includeSensitive)

    /**
     * Card 08's `readings(subject, metric?, lastN = 5)`: by name. With a metric, its newest [lastN] values (the subject
     * picks between two metrics of the same name); without one, the subject's newest session.
     */
    suspend fun readings(subject: String?, metric: String?, lastN: Int = 5, includeSensitive: Boolean): List<ReadingHit> {
        val cat = source.readingCatalog(includeSensitive)
        val subjectNorm = subject?.let(ReadingQuestions::norm)?.takeIf { it.isNotEmpty() }
        if (!metric.isNullOrBlank()) {
            val m = ReadingQuestions.norm(metric)
            val matches = cat.metrics.filter { mi -> (listOf(mi.label, mi.key) + mi.aliases).any { ReadingQuestions.norm(it) == m } }
                .filter { subjectNorm == null || it.subjectName?.let(ReadingQuestions::norm) == subjectNorm }
            val chosen = matches.maxByOrNull { it.lastAt } ?: return emptyList()
            return source.latestReadings(chosen.metricId, lastN.coerceIn(1, 20), includeSensitive).map(::readingHit)
        }
        val s = cat.subjects.filter { si -> (listOf(si.name) + si.aliases).any { ReadingQuestions.norm(it) == subjectNorm } }
            .maxByOrNull { it.lastAt } ?: return emptyList()
        return source.latestSession(s.subjectId, includeSensitive)?.rows.orEmpty().map(::readingHit)
    }

    fun readingHit(f: ReadingFact) = ReadingHit(f.recordId, f.captureId, f.subjectName, f.metricLabel, f.valueText, f.unit, age(f.measuredAt))

    // ---- whereIs

    /** Where [item] is now, with up to 2 earlier places, and whether that is old for this kind of thing (card 08 A3). */
    suspend fun whereIs(item: String, includeSensitive: Boolean): WhereIs? {
        val want = stems(bare(item))
        if (want.isEmpty()) return null
        val rows = source.notes(includeSensitive).filter { it.recordId != null && it.direction != null && it.itemText != null }
        val matching = rows.filter { n -> (listOf(n.itemText) + n.itemNames).any { names(want, stems(it)) } }
            .sortedWith(compareBy<NoteRow> { if (it.superseded) 1 else 0 }.thenByDescending { it.capturedAt })
        val first = matching.firstOrNull() ?: return null
        val key = first.itemSubjectId ?: ReadingQuestions.norm(first.itemText!!)
        val same = matching.filter { (it.itemSubjectId ?: ReadingQuestions.norm(it.itemText!!)) == key }.sortedByDescending { it.capturedAt }
        val current = same.firstOrNull { !it.superseded } ?: same.first()
        val previous = same.filter { it !== current }.take(2)
        val days = (now() - current.capturedAt) / DAY_MS
        return WhereIs(whereabout(current), previous.map(::whereabout), days > staleAfterDays(current.volatility))
    }

    private fun whereabout(n: NoteRow) = Whereabout(
        n.recordId!!, n.captureId, n.itemText!!, n.whereText, n.personText, n.direction!!, age(n.capturedAt), n.saidDateText, n.quote,
    )

    // ---- loans

    /** Things lent or borrowed and not back yet, newest first; optionally to or from [person], or of [item]. */
    suspend fun loans(person: String?, includeSensitive: Boolean, item: String? = null): List<LoanHit> {
        val rows = source.notes(includeSensitive).filter { it.recordId != null && it.direction != null && it.itemText != null }
        fun key(n: NoteRow) = n.itemSubjectId ?: ReadingQuestions.norm(n.itemText!!)
        val newestByItem = rows.groupBy(::key).mapValues { (_, l) -> l.maxOf { it.capturedAt } }
        val wantPerson = stems(person)
        val wantItem = stems(item?.let(::bare))
        return rows.filter { n ->
            (n.direction == Direction.LENT || n.direction == Direction.BORROWED) && n.returnedAt == null && !n.superseded &&
                newestByItem[key(n)] == n.capturedAt &&
                (wantPerson.isEmpty() || names(wantPerson, stems(n.personText))) &&
                (wantItem.isEmpty() || (listOf(n.itemText) + n.itemNames).any { names(wantItem, stems(it)) })
        }.sortedByDescending { it.capturedAt }
            .map { LoanHit(it.recordId!!, it.captureId, it.itemText!!, it.personText, it.direction!!, it.saidDateText, age(it.capturedAt)) }
    }

    // ---- lastEvent

    /** Up to 3 newest events whose words hold the query's words (one may be missing when there are three or more). */
    suspend fun lastEvent(query: String, includeSensitive: Boolean): List<EventHit> {
        val want = stems(query)
        if (want.isEmpty()) return emptyList()
        val need = if (want.size >= 3) want.size - 1 else want.size
        return source.notes(includeSensitive).filter { it.kind == RecordKind.EVENT && it.recordId != null }
            .filter { n -> val have = stems(n.title + " " + n.quote + " " + n.subjectName.orEmpty()); want.count { it in have } >= need }
            .sortedByDescending(::eventTime).take(3)
            .map { EventHit(it.recordId!!, it.captureId, it.title, it.quote, age(eventTime(it))) }
    }

    // ---- aboutSubject

    /** Counts by kind of the notes about a subject (by name or alias), and the newest 2 of each kind. */
    suspend fun aboutSubject(name: String, includeSensitive: Boolean): AboutSubject? {
        val want = ReadingQuestions.norm(bare(name))
        if (want.isEmpty()) return null
        val rows = source.notes(includeSensitive).filter { it.recordId != null && it.kind != null }
        val ids = rows.filter { n -> n.subjectId != null && (listOf(n.subjectName.orEmpty()) + n.subjectAliases).any { ReadingQuestions.norm(it) == want } }
            .mapNotNull { it.subjectId }.toSet() +
            rows.filter { n -> n.itemSubjectId != null && n.itemNames.any { ReadingQuestions.norm(it) == want } }.mapNotNull { it.itemSubjectId }
        if (ids.isEmpty()) return null
        val about = rows.filter { it.subjectId in ids || it.itemSubjectId in ids }.sortedByDescending { it.capturedAt }
        if (about.isEmpty()) return null
        val byKind = about.groupBy { it.kind!! }
        val shown = about.firstNotNullOfOrNull { n -> n.subjectName?.takeIf { n.subjectId in ids } ?: n.itemNames.firstOrNull()?.takeIf { n.itemSubjectId in ids } } ?: bare(name)
        return AboutSubject(shown, byKind.mapValues { it.value.size }, byKind.mapValues { e -> e.value.take(2).map(::hit) })
    }

    // ---- capturesOn

    /** Notes made on those local dates (the phone's zone), oldest first. */
    suspend fun capturesOn(range: DateRange, includeSensitive: Boolean): List<CaptureHit> =
        source.notes(includeSensitive).filter { localDate(it.capturedAt) in range }
            .sortedBy { it.capturedAt }
            .groupBy { it.captureId }.values // keeps the order of first appearance
            .map { l ->
                val f = l.first()
                CaptureHit(f.captureId, f.title, f.captureText, age(f.capturedAt), l.mapNotNull { it.recordId })
            }

    // ---- sources by id (for the loop's answers, card 08 pass 2)

    /** The notes with these record or capture ids that the caller may draw, in the order given. */
    suspend fun notesById(ids: List<String>, includeSensitive: Boolean): List<NoteHit> {
        val rows = source.notes(includeSensitive)
        val byRecord = rows.filter { it.recordId != null }.associateBy { it.recordId!! }
        val byCapture = rows.groupBy { it.captureId }
        return ids.distinct().mapNotNull { id -> byRecord[id]?.let(::hit) ?: byCapture[id]?.firstOrNull()?.let(::hit) }
    }
}
