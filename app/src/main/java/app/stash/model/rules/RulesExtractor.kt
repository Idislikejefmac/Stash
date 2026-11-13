package app.stash.model.rules

import app.stash.data.Confidence
import app.stash.data.Direction
import app.stash.data.SubjectType
import app.stash.domain.DateExpr
import app.stash.domain.DatePhrases
import app.stash.domain.ExtractedReading
import app.stash.domain.ExtractedRecord
import app.stash.domain.ExtractionJson
import app.stash.domain.NumberWords
import app.stash.domain.RecordKind
import app.stash.model.ExtractContext
import app.stash.model.ExtractResult
import app.stash.model.LocalExtractor

/**
 * The offline floor (docs/cards/03 section F). It never invents: one record per capture, conservative patterns,
 * `sure` medium for the prototype's shapes and low for the new ones, and anything it does not recognise is one
 * `other` record. The hosted model (card 04) is the main path; this is what runs with no key, no network, or a
 * Private note.
 */
class RulesExtractor : LocalExtractor {
    override val name = "rules-2"

    override fun extract(text: String, context: ExtractContext): ExtractResult {
        val records = parse(text, context)
        return ExtractResult.Ok(records, ExtractionJson.serialise(records))
    }

    /** Empty text gives no record at all (the checks turn that into one `other` record). */
    fun parse(raw: String, context: ExtractContext = ExtractContext()): List<ExtractedRecord> {
        val t = raw.trim().replace(WS, " ").trimEnd('.', '!', '?', ' ')
        if (t.isEmpty()) return emptyList()
        val body = FILLER.replaceFirst(t, "").ifEmpty { t }
        val clauses = splitClauses(body)

        val rec = task(clauses, body)
            ?: price(body)
            ?: loan(body)
            ?: placed(body)
            ?: measurements(body, context)
            ?: event(body)
            ?: idea(body)
            ?: plain(t)
        return listOf(decorate(rec, body))
    }

    // ------------------------------------------------------------------ helpers

    private fun cap(s: String) = s.trim().replaceFirstChar { it.uppercase() }

    private fun shortTitle(s: String) = cap(s).take(80).trim()

    /** Drop a leading "my", "the", "a", "an", "our", "some". */
    private fun clean(s: String): String = s.trim().trim(',', '.', ' ').replace(ARTICLE, "").trim()

    private fun splitClauses(t: String): List<String> =
        CLAUSE_SPLIT.split(t).map { it.trim().trim(',', ';', '.', ' ') }.filter { it.isNotEmpty() }

    private fun base(kind: RecordKind, title: String, quote: String, sure: Confidence) =
        ExtractedRecord(kind = kind, title = shortTitle(title), quote = quote.trim(), sure = sure)

    /** A spot, and a photo hint, may be added to any record that is not a task. */
    private fun decorate(r: ExtractedRecord, body: String): ExtractedRecord {
        if (r.kind == RecordKind.TASK) return r
        val spot = findSpot(body)
        val hint = spot != null || CONTAINER.containsMatchIn(body)
        return r.copy(spot = spot ?: r.spot, photoHint = if (hint) true else r.photoHint)
    }

    // ------------------------------------------------------------------ tasks

    private data class TaskBody(val body: String, val strong: Boolean)

    private fun taskBody(clause: String): TaskBody? {
        val c = CONNECT.replaceFirst(clause.trim(), "").trim()
        REMEMBER.find(c)?.let { return TaskBody(it.groupValues[1].trim(), true) }
        OBLIGE.find(c)?.let { return TaskBody(it.groupValues[1].trim(), false) }
        val words = c.split(' ').filter { it.isNotEmpty() }
        if (words.size >= 2 && IMPERATIVE.containsMatchIn(c)) return TaskBody(c, false)
        return null
    }

    private fun task(clauses: List<String>, whole: String): ExtractedRecord? {
        for ((i, clause) in clauses.withIndex()) {
            val tb = taskBody(clause) ?: continue
            return buildTask(tb, clause, clauses.take(i), whole)
        }
        return null
    }

    private fun buildTask(tb: TaskBody, clause: String, prior: List<String>, whole: String): ExtractedRecord? {
        var body = tb.body.trim().trimEnd('.', ',', ' ')
        if (body.isEmpty()) return null
        val due = DatePhrases.findDue(body)
        if (due != null) {
            body = (body.substring(0, due.range.first) + " " + body.substring(due.range.last + 1))
                .replace(WS, " ").trim().trim(',', ' ')
        }
        val buy = BUY.find(body)
        var quote = clause
        val sure = if (tb.strong) Confidence.MEDIUM else Confidence.LOW
        if (buy != null) {
            val verb = buy.groupValues[1]
            var rest = buy.groupValues[2].trim()
            var place: String? = null
            NEXT_TIME.find(rest)?.let { m ->
                place = m.groupValues[1].trim().trim(',', '.', ' ')
                rest = rest.substring(0, m.range.first).trim()
            }
            if (place == null) {
                AT_PLACE.find(rest)?.let { m ->
                    val p = m.groupValues[2].trim().trim(',', '.', ' ')
                    if (p.isNotEmpty() && p.split(' ').size <= 5) {
                        place = p
                        rest = m.groupValues[1].trim()
                    }
                }
            }
            var item: String? = clean(rest).ifEmpty { null }
            if (item != null && item.lowercase() in PRONOUN_ITEMS) {
                val ante = prior.asReversed().firstNotNullOfOrNull { antecedent(it) }
                if (ante != null) {
                    item = ante
                    quote = whole
                } else {
                    item = null
                }
            }
            val shop = verb.lowercase() in SHOP_VERBS
            val itemF = item
            val placeF = place
            val restF = rest
            val title = buildString {
                append(cap(verb))
                if (itemF != null) append(' ').append(itemF)
                else if (restF.isNotBlank()) append(' ').append(restF.trim())
                if (placeF != null) append(" at ").append(placeF)
            }
            return base(RecordKind.TASK, title, quote, sure).copy(
                item = itemF, atPlace = placeF, due = due?.expr, tags = if (shop) listOf("shopping") else emptyList(),
            )
        }
        return base(RecordKind.TASK, body, quote, sure).copy(due = due?.expr)
    }

    /** What "one" or "it" most likely meant: the thing named after "looking at my ...", "holding the ...". */
    private fun antecedent(clause: String): String? {
        val m = ANTECEDENT.find(clause) ?: return null
        val s = clean(m.groupValues[1])
        if (s.isEmpty() || s.split(' ').size > 5) return null
        if (s.lowercase() in PRONOUN_ITEMS) return null
        return s
    }

    // ------------------------------------------------------------------ loans and placements

    private fun loan(t: String): ExtractedRecord? {
        LOAN_OUT.find(t)?.let { m -> return loanOf(t, m.groupValues[1], m.groupValues[2], Direction.LENT) }
        LOAN_IN.find(t)?.let { m -> return loanOf(t, m.groupValues[1], m.groupValues[2], Direction.BORROWED) }
        BACK_THIRD.find(t)?.let { m ->
            val rest = m.groupValues[2]
            if (BACK.containsMatchIn(rest)) {
                val item = clean(BACK.replace(rest, " ").replace(WS, " ").trim())
                if (item.isNotEmpty()) {
                    return base(RecordKind.LOAN, t, t, Confidence.LOW).copy(
                        item = item, person = m.groupValues[1].trim(), direction = Direction.RETURNED,
                    )
                }
            }
        }
        BACK_FIRST.find(t)?.let { m ->
            val item = clean(m.groupValues[1])
            if (item.isNotEmpty()) {
                return base(RecordKind.LOAN, t, t, Confidence.LOW).copy(
                    item = item, person = m.groupValues[2].trim().ifEmpty { null }, direction = Direction.RETURNED,
                )
            }
        }
        RETURNED_TO.find(t)?.let { m ->
            val item = clean(m.groupValues[1])
            if (item.isNotEmpty()) {
                return base(RecordKind.LOAN, t, t, Confidence.LOW).copy(
                    item = item, person = m.groupValues[2].trim(), direction = Direction.RETURNED,
                )
            }
        }
        return null
    }

    private fun loanOf(t: String, itemRaw: String, rest: String, dir: Direction): ExtractedRecord {
        var person = rest.trim()
        var whenExpr: DateExpr? = null
        val on = ON_DATE.find(rest)
        if (on != null) {
            person = rest.substring(0, on.range.first).trim()
            whenExpr = DatePhrases.parseWhen(on.groupValues[1])
        } else {
            val w = DatePhrases.findWhen(rest)
            if (w != null && rest.substring(w.range.last + 1).isBlank()) {
                person = rest.substring(0, w.range.first).trim()
                whenExpr = w.expr
            }
        }
        person = person.trim(',', ' ').replaceFirstChar { it.uppercase() }
        return base(RecordKind.LOAN, t, t, Confidence.MEDIUM).copy(
            item = clean(itemRaw), person = person.ifEmpty { null }, direction = dir, whenExpr = whenExpr,
        )
    }

    private fun placed(t: String): ExtractedRecord? {
        val m = PUT.find(t) ?: IS_IN.find(t) ?: return null
        val item = clean(m.groupValues[1])
        val where = m.groupValues[2].lowercase() + " " + m.groupValues[3].trim()
        if (item.isEmpty()) return null
        return base(RecordKind.PLACED, t, t, Confidence.MEDIUM).copy(item = item, where = where)
    }

    // ------------------------------------------------------------------ readings and prices

    private fun place(after: String): String? =
        PLACE_NAME.find(after)?.groupValues?.get(1)?.trim()

    private fun price(t: String): ExtractedRecord? {
        if (!PRICE_CUE.containsMatchIn(t)) return null
        val spans = NumberWords.scan(t)
        val cue = PRICE_CUE.find(t)!!
        val span = spans.firstOrNull { it.hasDollar } ?: spans.firstOrNull { it.start >= cue.range.first } ?: return null
        val verb = PRICE_VERB.find(t)
        val subjectRaw = if (verb != null && verb.range.first < span.start) t.substring(0, verb.range.first) else return null
        val subject = clean(SUBJECT_LEAD.replace(subjectRaw.trim(), ""))
        if (subject.isEmpty() || subject.split(' ').size > 4) return null
        if (Regex("^(?:i|we)\\b", RegexOption.IGNORE_CASE).containsMatchIn(subject)) return null
        val after = t.substring(span.end)
        val per = PER.find(after)?.groupValues?.get(1)
        val dollars = span.hasDollar || DOLLAR_WORD.containsMatchIn(after)
        val unit = when {
            dollars && per != null -> "$/$per"
            dollars -> "$"
            per != null -> "/$per"
            else -> null
        }
        val where = place(after)
        val reading = ExtractedReading("price", span.text, span.primary, unit)
        val title = buildString {
            append(subject).append(" price ").append(span.text)
            if (where != null) append(" at ").append(where)
        }
        return base(RecordKind.READING, title, t, Confidence.LOW).copy(
            subject = subject, subjectType = SubjectType.ITEM, place = where, readings = listOf(reading),
            tags = listOf("price"),
        )
    }

    private fun measurements(t: String, ctx: ExtractContext): ExtractedRecord? {
        val spans = NumberWords.scan(t).filter { !it.text.equals("one", ignoreCase = true) }
        if (spans.size < 2) return null
        val known = KNOWN_METRICS + ctx.subjects.flatMap { it.metrics }.map { it.lowercase() }
        val cued = MEASURE_CUE.containsMatchIn(t)
        var cursor = 0
        val readings = mutableListOf<ExtractedReading>()
        var subject: String? = null
        for ((i, span) in spans.withIndex()) {
            val label = WORD.findAll(t.substring(cursor, span.start)).map { it.value }.toList()
            var next = span.end
            var unit: String? = null
            UNIT_AFTER.find(t.substring(span.end))?.let { u ->
                unit = u.groupValues[1].lowercase().let { UNIT_NAMES[it] ?: it }
                next = span.end + u.range.last + 1
            }
            val metricWords: List<String>
            if (i == 0) {
                val core = label.filter { it.lowercase() !in FILLER_WORDS }
                if (core.isEmpty()) return null
                val two = if (core.size >= 2) core.takeLast(2).joinToString(" ") else null
                metricWords = when {
                    two != null && two.lowercase() in known -> core.takeLast(2)
                    core.last().lowercase() in known -> listOf(core.last())
                    cued -> listOf(core.last())
                    else -> return null
                }
                val lead = core.dropLast(metricWords.size).filter { it.lowercase() !in SUBJECT_NOISE }
                subject = lead.joinToString(" ").takeIf { it.isNotBlank() && lead.size <= 3 }
            } else {
                val core = label.filter { it.lowercase() !in FILLER_WORDS }
                if (core.isEmpty() || core.size > 3) return null
                metricWords = core
            }
            readings += ExtractedReading(metricWords.joinToString(" "), span.text, span.primary, unit)
            cursor = next
        }
        val title = (subject?.let { cap(it) + ": " } ?: "") + readings.joinToString(", ") { "${it.metric} ${it.value}" }
        return base(RecordKind.READING, title, t, Confidence.LOW).copy(
            subject = subject, subjectType = if (subject != null) SubjectType.THING else null, readings = readings,
        )
    }

    // ------------------------------------------------------------------ events, ideas, the rest

    private fun event(t: String): ExtractedRecord? {
        val m = EVENT_PAST.find(t)
        val progressive = if (m == null) EVENT_NOW.find(t) else null
        if (m == null && progressive == null) return null
        if (progressive != null && progressive.groupValues[1].lowercase() in NOT_NOW) return null
        val at = EVENT_PLACE.find(t)
        val w = DatePhrases.findWhen(t)
        return base(RecordKind.EVENT, t, t, Confidence.LOW).copy(place = at?.groupValues?.get(1)?.trim(), whenExpr = w?.expr)
    }

    private fun idea(t: String): ExtractedRecord? =
        if (IDEA.containsMatchIn(t)) base(RecordKind.IDEA, t, t, Confidence.LOW) else null

    /** Nothing matched. A note that gives a spot ("aisle 4") is kept as a reference; anything else is `other`. */
    private fun plain(t: String): ExtractedRecord =
        if (findSpot(t) != null) base(RecordKind.REFERENCE, t, t, Confidence.LOW)
        else base(RecordKind.OTHER, t, t, Confidence.LOW)

    // ------------------------------------------------------------------ spot

    /** "Italian section, row 5, bucket 10": the run of place-within-the-place phrases, as he said them. */
    internal fun findSpot(t: String): String? {
        val segs = t.split(',').map { it.trim() }
        val out = mutableListOf<String>()
        for (seg in segs) {
            val s = SPOT_LEAD.replace(seg, "").trim()
            if (SPOT_NAMED.matches(s) || SPOT_NUMBERED.matches(s)) {
                out += s
            } else {
                if (out.isEmpty()) {
                    SPOT_INLINE.find(seg)?.let { out += it.value.trim() }
                }
                if (out.isNotEmpty()) break
            }
        }
        return out.joinToString(", ").takeIf { it.isNotEmpty() }
    }

    private companion object {
        val WS = Regex("\\s+")
        val FILLER = Regex("^(?:(?:ok|okay|um|uh|so|well|hey|yeah)\\b[,.]?\\s+)+", RegexOption.IGNORE_CASE)
        val ARTICLE = Regex("^(?:my|the|a|an|our|some)\\s+", RegexOption.IGNORE_CASE)
        val CLAUSE_SPLIT = Regex(
            "(?<=[.!?])\\s+|\\s*;\\s*|,?\\s+(?:and|but|so|then)\\s+(?=(?:i|i'm|i've|i'll|don't|do not|remember|remind)\\b)|,\\s+(?=i\\b)",
            RegexOption.IGNORE_CASE,
        )

        // tasks
        val CONNECT = Regex("^(?:(?:and|but|so|also|then|um|uh|ok|okay|well|yeah)\\b[, ]*)+", RegexOption.IGNORE_CASE)
        val REMEMBER = Regex(
            "^(?:i(?:'ll| will)?\\s+)?(?:(?:(?:really|just)\\s+)?(?:need|have|ought|want|got)\\s+to\\s+|(?:should|must|gotta)\\s+)?" +
                "(?:remember to|remind me to|don'?t forget to|not forget to)\\s+(.+)$",
            RegexOption.IGNORE_CASE,
        )
        val OBLIGE = Regex(
            "^i(?:'ve| have)?\\s+(?:(?:really|just|still)\\s+)?(?:(?:need|have|ought|want|got)\\s+to|should|must|gotta)\\s+(.+)$",
            RegexOption.IGNORE_CASE,
        )
        val IMPERATIVE = Regex(
            "^(?:finish|complete|call|email|schedule|buy|purchase|pick up|fix|clean|send|pay|renew|cancel|" +
                "add|write|replace|organize|submit|mow|research)\\b",
            RegexOption.IGNORE_CASE,
        )
        val BUY = Regex("^(buy|get|pick up|grab|purchase|order|stop and get)\\s+(.+)$", RegexOption.IGNORE_CASE)
        val SHOP_VERBS = setOf("buy", "get", "grab", "purchase", "order", "stop and get")
        val NEXT_TIME = Regex(
            "\\s+(?:the\\s+)?(?:next time|whenever|when|once)\\s+(?:i|we)\\s+(?:am\\s+|'m\\s+)?" +
                "(?:go|going|get|getting|stop|stopping|head|heading|drive|driving|walk|walking|swing|swinging|am|are)?\\s*" +
                "(?:to|at|in|into|by|past|near)\\s+(.+)$",
            RegexOption.IGNORE_CASE,
        )
        val AT_PLACE = Regex("^(.*)\\s+(?:at|from)\\s+(.+)$", RegexOption.IGNORE_CASE)
        val PRONOUN_ITEMS = setOf("one", "it", "some", "some more", "more", "another", "another one", "them", "those", "these", "a few", "one more")
        val ANTECEDENT = Regex(
            "(?:looking at|looking for|staring at|holding|smelling|burning|wearing|using|eating|drinking|trying|just got|got|have)" +
                "\\s+(?:(?:my|this|that|these|those|the|a|an|some)\\s+)?(.+?)$",
            RegexOption.IGNORE_CASE,
        )

        // loans and placements
        const val I = "(?:i'?m|i am|i'?ve|i have|i just|i)"
        const val PREP = "in|on|at|inside|under|behind|next to|near|by|on top of|in front of"
        val PUT = Regex(
            "^$I\\s+(?:put|putting|placed|placing|left|leaving|stored|storing|stashed|stashing|hid|hiding|parked|parking|keeping|kept)\\s+(.+?)\\s+($PREP)\\s+(.+)$",
            RegexOption.IGNORE_CASE,
        )
        val IS_IN = Regex("^((?:my|the|our)\\s+.+?)\\s+(?:is|are|was|were)\\s+($PREP)\\s+(.+)$", RegexOption.IGNORE_CASE)
        val LOAN_OUT = Regex("^$I\\s+(?:loaned|lent|lend|loan|am lending|am loaning)\\s+(.+?)\\s+to\\s+(.+)$", RegexOption.IGNORE_CASE)
        val LOAN_IN = Regex("^$I\\s+(?:borrowed|borrow)\\s+(.+?)\\s+from\\s+(.+)$", RegexOption.IGNORE_CASE)
        val ON_DATE = Regex("\\s+on\\s+(.+)$", RegexOption.IGNORE_CASE)
        val BACK = Regex("\\bback\\b", RegexOption.IGNORE_CASE)
        // Case-sensitive on purpose: the person's name starts with a capital letter.
        val BACK_THIRD = Regex("^(?!I\\b)([A-Z][\\w'-]*(?: [A-Z][\\w'-]*)?)\\s+(?:gave|returned|brought|handed)\\s+(?:me\\s+)?(.+)$")
        val BACK_FIRST = Regex("^i\\s+(?:got|have|took)\\s+(.+?)\\s+back(?:\\s+from\\s+(.+))?$", RegexOption.IGNORE_CASE)
        val RETURNED_TO = Regex("^(?i:i\\s+(?:returned|gave back)\\s+)(.+?)\\s+to\\s+([A-Z][\\w'-]*(?: [A-Z][\\w'-]*)?)$")

        // readings and prices
        val PRICE_CUE = Regex("\\b(?:on sale|for sale|sale|priced?|costs?|selling|sells|marked down|going for)\\b|\\$", RegexOption.IGNORE_CASE)
        val PRICE_VERB = Regex("\\b(?:is|are|costs?|sells?|selling|priced|was on sale|on sale|for sale|going for)\\b", RegexOption.IGNORE_CASE)
        val SUBJECT_LEAD = Regex("^(?:i (?:saw|noticed|see|found)(?: that)?\\s+)", RegexOption.IGNORE_CASE)
        val PER = Regex("^\\s*(?:per|a|each)\\s+([A-Za-z]+)", RegexOption.IGNORE_CASE)
        val DOLLAR_WORD = Regex("^\\s*(?:dollars?|bucks)\\b", RegexOption.IGNORE_CASE)
        val PLACE_NAME = Regex("\\b(?:at|from|in)\\s+([A-Z][\\w'&.-]*(?:\\s+[A-Z][\\w'&.-]*)*)")
        val MEASURE_CUE = Regex("\\b(?:test|tests|tested|testing|checked|measured|reading|readings|levels?|results?)\\b", RegexOption.IGNORE_CASE)
        val WORD = Regex("[A-Za-z][A-Za-z']*")
        val UNIT_AFTER = Regex("^\\s*(ppm|ppb|percent|psi|degrees|%)(?![A-Za-z])", RegexOption.IGNORE_CASE)
        val UNIT_NAMES = mapOf("percent" to "%")
        val FILLER_WORDS = setOf(
            "and", "is", "was", "are", "were", "at", "of", "around", "about", "reads", "read", "the", "a", "an", "it", "its",
            "it's", "they", "that", "this", "there", "here", "then", "to", "came", "out", "comes", "got", "reading", "level",
            "levels", "now", "currently", "i", "we", "you", "also",
        )
        val SUBJECT_NOISE = setOf(
            "i", "just", "tested", "test", "tests", "testing", "checked", "check", "measured", "took", "got", "the", "my",
            "our", "a", "an", "water", "results", "result", "numbers", "today", "this", "morning", "did", "for", "of",
            "on", "at", "in", "and", "is", "was", "are", "were", "reading", "readings", "level", "levels",
        )
        val KNOWN_METRICS = setOf(
            "chlorine", "free chlorine", "total chlorine", "combined chlorine", "ph", "alkalinity", "total alkalinity",
            "hardness", "calcium hardness", "total hardness", "cyanuric acid", "cya", "salt", "tds", "temperature", "temp",
            "bromine", "phosphates", "copper", "iron", "orp", "nitrate", "nitrates", "nitrite", "nitrites", "ammonia",
            "weight", "pressure", "systolic", "diastolic", "humidity",
        )

        // events and the rest
        val EVENT_PAST = Regex(
            "^(?:(?:i'?ve|i have|i)\\s+)?(?:(?:just|already|finally|also|then)\\s+)*" +
                "(?:finished|changed|replaced|cleaned|fixed|paid|called|mowed|watered|tested|bought|picked up|washed|vacuumed|" +
                "ordered|sent|emailed|texted|renewed|installed|swept|took|walked|fed|cooked|assembled|mailed|returned)\\b.+$",
            RegexOption.IGNORE_CASE,
        )
        val EVENT_NOW = Regex("^i(?:'m| am)\\s+(?:(?:just|currently|still)\\s+)?([a-z]+ing)\\b.*$", RegexOption.IGNORE_CASE)
        val NOT_NOW = setOf("going", "planning", "thinking", "hoping", "wanting", "needing", "trying", "wondering", "gonna")
        val EVENT_PLACE = Regex("\\s+at\\s+([A-Z][\\w'&.-]*(?:\\s+[A-Z][\\w'&.-]*)*)\\s*$")
        val IDEA = Regex(
            "^(?:idea\\b[:,]?|i wonder (?:if|whether)\\b|what if\\b|it would be (?:cool|nice|great|fun) (?:if|to)\\b)",
            RegexOption.IGNORE_CASE,
        )

        // spots and pictures
        val CONTAINER = Regex(
            "\\b(?:bottle|box|jar|label|part number|model number|serial)\\b|\\b(?:a|the|this|that|my)\\s+can\\b",
            RegexOption.IGNORE_CASE,
        )
        val SPOT_LEAD = Regex("^(?:(?:it'?s|it is)\\s+)?(?:(?:in|on|at)\\s+)?(?:the\\s+)?", RegexOption.IGNORE_CASE)
        val SPOT_NAMED = Regex("^(?:[\\w'-]+\\s+)?(?:section|aisle|bay)$", RegexOption.IGNORE_CASE)
        const val SPOT_KEY = "aisle|row|shelf|bin|bucket|bay|section|drawer"
        const val SPOT_VAL = "(?:\\d+[a-z]?|[a-z]|one|two|three|four|five|six|seven|eight|nine|ten)"
        val SPOT_NUMBERED = Regex("^(?:$SPOT_KEY)\\s+$SPOT_VAL$", RegexOption.IGNORE_CASE)
        val SPOT_INLINE = Regex("\\b(?:$SPOT_KEY)\\s+$SPOT_VAL\\b", RegexOption.IGNORE_CASE)
    }
}
