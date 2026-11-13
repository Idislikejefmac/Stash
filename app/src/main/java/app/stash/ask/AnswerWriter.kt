package app.stash.ask

import app.stash.data.Direction
import app.stash.domain.DatePhrases
import app.stash.domain.ReadingAnswers
import app.stash.domain.ReadingFact
import app.stash.domain.ReadingSession

/** A note an answer used, as the screen lists it under "From these notes:". Tapping it opens [captureId]. */
data class SourceNote(val id: String, val captureId: String, val title: String, val quote: String, val age: String)

/** A sentence written by code, and the notes it came from. */
data class Answer(val text: String, val sources: List<SourceNote>)

/**
 * Coded answers (docs/cards/08 section C). Pure. Every answer says how old its note is, in words; an empty lookup is not
 * an answer (null), so the engine falls through to the next step. Card 05's task wording and card 07's readings
 * wording are used as they are.
 */
object AnswerWriter {
    const val NOTHING = "I don't have a note about that."
    const val LIST_NO_MODEL = "Without a model I can only match words. Notes that match:"
    const val LIST_GAVE_UP = "I couldn't work that out. Notes that match:"
    const val LIST_MARKED_WRONG = "Notes that match your words:"

    fun source(n: NoteHit) = SourceNote(n.id, n.captureId, n.title, n.quote, n.age)

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

    private val IN_LEAD = Regex("^in\\s+(?:(?:the|my)\\s+)?", RegexOption.IGNORE_CASE)

    /** "in the nightstand drawer" as "nightstand drawer"; "on the top shelf" keeps its "on". */
    fun placeWords(where: String): String = where.trim().trimEnd('.').replace(IN_LEAD, "").trim()

    private fun whereSource(w: Whereabout) = SourceNote(w.recordId, w.captureId, w.item, w.quote, w.age)

    // ---- where is

    /** "Nightstand drawer. You said so 3 weeks ago, so it may have moved. Before that: kitchen counter, 2 months ago." */
    fun whereIs(r: WhereIs?): Answer? {
        r ?: return null
        val c = r.current
        val first = when (c.direction) {
            Direction.LENT -> c.person?.let { "${cap(it)} has it. You said so ${c.age}" }
            Direction.BORROWED -> c.person?.let { "You borrowed it from ${cap(it)}. You said so ${c.age}" }
            Direction.RETURNED -> "You got it back. You said so ${c.age}"
            Direction.PLACED -> c.where?.takeIf { it.isNotBlank() }?.let { "${cap(placeWords(it))}. You said so ${c.age}" }
        } ?: "You said: \"${c.quote.trim()}\", ${c.age}"
        val sb = StringBuilder(first)
        sb.append(if (r.stale) ", so it may have moved." else ".")
        val before = r.previous.mapNotNull { p ->
            when (p.direction) {
                Direction.PLACED -> p.where?.takeIf { it.isNotBlank() }?.let { "${placeWords(it)}, ${p.age}" }
                Direction.LENT -> p.person?.let { "with ${cap(it)}, ${p.age}" }
                else -> null
            }
        }
        if (before.isNotEmpty()) sb.append(" Before that: ").append(before.joinToString("; ")).append(".")
        return Answer(sb.toString(), listOf(whereSource(c)) + r.previous.map(::whereSource))
    }

    // ---- loans

    /** "Frank has your guitar, since January 5th." */
    fun loans(list: List<LoanHit>): Answer? {
        if (list.isEmpty()) return null
        val lines = list.take(5).map { l ->
            val item = Lookups.bare(l.item)
            val since = l.saidDate?.takeIf { it.isNotBlank() }?.let { ", since ${it.trim().removePrefix("on ").removePrefix("On ")}" }
                ?: " (you said so ${l.age})"
            when (l.direction) {
                Direction.BORROWED -> "You have ${l.person?.let { cap(it) + "'s" } ?: "a borrowed"} $item$since"
                else -> "${l.person?.let(::cap) ?: "Someone"} has your $item$since"
            }
        }
        val more = list.size - lines.size
        val text = lines.joinToString(". ") + "." + if (more > 0) " And $more more." else ""
        return Answer(text, list.map { SourceNote(it.recordId, it.captureId, it.item, it.item, it.age) })
    }

    // ---- tasks (card 05's wording)

    /**
     * Card 05's answers, unchanged: "You have 7 things. Soonest: …", or "At Costco you have …". An empty list is still
     * an answer here ("You have nothing on your list."), as card 05 built it: it is a fact about his list, not a guess.
     */
    fun openTasks(r: OpenTasks, atPlace: String?, today: java.time.LocalDate, now: Long): Answer {
        val text = if (atPlace == null) app.stash.domain.TaskAnswers.whatToHandle(r.items, today, now)
        else app.stash.domain.TaskAnswers.atPlace(r.items, atPlace, today, now)
        val cmp = app.stash.domain.TaskRules.comparator(today)
        val top = r.items.sortedWith { a, b -> cmp.compare(a.task, b.task) }.take(app.stash.domain.TaskAnswers.TOP)
        return Answer(text, top.map { SourceNote(it.record.id, it.capture.id, it.record.title, it.record.quote, r.hits.first { h -> h.recordId == it.record.id }.age) })
    }

    // ---- readings (card 07's wording)

    fun lastValue(rows: List<ReadingFact>, now: Long, named: Boolean): Answer? {
        if (rows.isEmpty()) return null
        return Answer(ReadingAnswers.lastValue(rows, now, named), rows.distinctBy { it.recordId }.map { readingSource(it, now) })
    }

    fun lastSession(subjectName: String, session: ReadingSession?, now: Long): Answer? {
        if (session == null || session.rows.isEmpty()) return null
        return Answer(ReadingAnswers.lastSession(subjectName, session, now), listOf(readingSource(session.rows.first(), now)))
    }

    private fun readingSource(f: ReadingFact, now: Long) =
        SourceNote(f.recordId, f.captureId, "${f.subjectName?.let { "$it " }.orEmpty()}${f.metricLabel}", f.quote, ReadingAnswers.ago(f.measuredAt, now))

    // ---- last event

    private val TIME_WORDS = Regex("[\\s,]+(?:today|yesterday|this morning|this afternoon|this evening|tonight|last night|earlier today|just now)\\.?$", RegexOption.IGNORE_CASE)

    /** "I changed the furnace filter today" as "You changed the furnace filter"; null when it is not said that way. */
    fun youDid(quote: String): String? {
        var s = quote.trim().trimEnd('.', '!', ' ')
        DatePhrases.findWhen(s)?.let { f -> if (f.range.last == s.length - 1) s = s.substring(0, f.range.first).trimEnd(' ', ',') }
        s = s.replace(TIME_WORDS, "").trim()
        val m = Regex("^(?:i|i've|i have|i just|i finally)\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(s) ?: return null
        val rest = m.groupValues[1].replace(Regex("\\bmy\\b", RegexOption.IGNORE_CASE), "your").replace(Regex("\\bmyself\\b", RegexOption.IGNORE_CASE), "yourself")
        val lead = s.substring(0, s.length - m.groupValues[1].length).trim().lowercase()
        val verb = when (lead) { "i've", "i have" -> "You have"; "i just" -> "You"; "i finally" -> "You finally"; else -> "You" }
        return "$verb $rest"
    }

    /** "You changed the furnace filter 5 weeks ago." With an earlier one: "Before that: 4 months ago." */
    fun lastEvent(hits: List<EventHit>): Answer? {
        val first = hits.firstOrNull() ?: return null
        val said = youDid(first.quote)
        val sb = StringBuilder(if (said != null) "$said ${first.age}." else "You noted \"${first.title.trim().trimEnd('.')}\", ${first.age}.")
        hits.getOrNull(1)?.let { sb.append(" Before that: ${it.age}.") }
        return Answer(sb.toString(), hits.map { SourceNote(it.recordId, it.captureId, it.title, it.quote, it.age) })
    }

    // ---- on a date

    /** "Last Tuesday you noted 4 things: …". [said] is the time phrase he used. */
    fun onDate(said: String, list: List<CaptureHit>): Answer? {
        if (list.isEmpty()) return null
        val shown = list.take(8).joinToString("; ") { short(it.text.ifBlank { it.title }) }
        val more = list.size - 8
        val count = if (list.size == 1) "1 thing" else "${list.size} things"
        val text = "${cap(properNames(said.trim()))} you noted $count: $shown" + (if (more > 0) "; and $more more." else ".")
        return Answer(text, list.map { SourceNote(it.recordIds.firstOrNull() ?: it.captureId, it.captureId, it.title, it.text, it.age) })
    }

    private val NAMES = Regex(
        "\\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday|january|february|march|april|may|june|july|august|september|october|november|december)\\b",
    )

    /** "last tuesday" as "last Tuesday". */
    fun properNames(s: String): String = s.replace(NAMES) { it.value.replaceFirstChar(Char::uppercase) }

    private fun short(s: String): String {
        val t = s.trim().trimEnd('.')
        return if (t.length <= 70) t else t.take(67).trimEnd() + "..."
    }

    // ---- about

    /** "About the pool you have 7 notes: 3 readings, 2 tasks, 2 events. Newest: … (2 days ago)." */
    fun about(a: AboutSubject?): Answer? {
        if (a == null || a.total == 0) return null
        val parts = a.counts.entries.sortedByDescending { it.value }.joinToString(", ") { (k, n) -> "$n ${kindWord(k.name, n)}" }
        val newest = a.newest.values.flatten().sortedBy { ageRank(it.age) }.take(3)
        val name = a.name.trim()
        val notes = if (a.total == 1) "1 note" else "${a.total} notes"
        val text = "About $name you have $notes: $parts. Newest: " + newest.joinToString("; ") { "${it.title.trim().trimEnd('.')} (${it.age})" } + "."
        return Answer(text, newest.map(::source))
    }

    /** Ages in words sort newest first by how they are made by [app.stash.domain.Age]: only used to order a few lines. */
    private fun ageRank(age: String): Long {
        if (age == "just now") return 0
        if (age == "yesterday") return 24 * 60
        val m = Regex("^(\\d+) (minute|hour|day|week|month|year)s? ago$").matchEntire(age) ?: return Long.MAX_VALUE
        val unit = when (m.groupValues[2]) { "minute" -> 1L; "hour" -> 60L; "day" -> 1440L; "week" -> 10_080L; "month" -> 43_200L; else -> 525_600L }
        return m.groupValues[1].toLong() * unit
    }

    fun kindWord(kind: String, n: Int): String {
        val one = when (kind) {
            "PLACED" -> "placement"; "LOAN" -> "loan"; "TASK" -> "task"; "READING" -> "reading"; "EVENT" -> "event"
            "REFERENCE" -> "fact"; "IDEA" -> "idea"; else -> "other note"
        }
        return if (n == 1) one else one + "s"
    }
}
