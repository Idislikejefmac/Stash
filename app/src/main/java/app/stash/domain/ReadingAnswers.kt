package app.stash.domain

/** A metric he has logged, as the question matcher needs it. */
data class MetricInfo(
    val metricId: String,
    val label: String,
    val key: String,
    val aliases: List<String>,
    val subjectId: String?,
    val subjectName: String?,
    /** When its newest reading was measured. */
    val lastAt: Long,
)

/** A subject with at least one reading. */
data class SubjectInfo(val subjectId: String, val name: String, val aliases: List<String>, val lastAt: Long)

/** Only what he has actually logged (and the caller may draw): questions are matched against this and nothing else. */
data class ReadingCatalog(val metrics: List<MetricInfo>, val subjects: List<SubjectInfo>)

sealed interface ReadingQuestion {
    /** "What was my pH last time?" [named]: the metric name exists under several subjects, so the answer says which. */
    data class LastValue(val metricId: String, val named: Boolean) : ReadingQuestion

    /** "How is the pool?" */
    data class LastSession(val subjectId: String) : ReadingQuestion
}

/** Card 07 section D: which readings question a sentence is. Pure; matches only names in the [ReadingCatalog]. */
object ReadingQuestions {
    private val FILLER_END = Regex("\\s+(?:the last time|last time|most recently|recently|again|please|now|so far|today)$")
    private val LEAD_WHAT = Regex(
        "^(?:what (?:was|is|were|are)|what|tell me|give me|show me|read me)\\s+(?:(?:my|the|our|a)\\s+)?(?:(?:latest|last|most recent|current|previous)\\s+)?(.+)$",
    )
    private val LEAD_LAST = Regex("^(?:the\\s+)?(?:latest|last|most recent)\\s+(.+)$")
    private val LEAD_HOW = Regex("^how (?:is|are|was|were)\\s+(?:(?:my|the|our)\\s+)?(.+)$")
    private val ARTICLE = Regex("^(?:my|the|our|a)\\s+")
    private val ARTICLES = setOf("the", "my", "our", "a")
    private val SESSION_WORDS = setOf("test", "tests", "reading", "readings", "result", "results", "numbers")

    /** Lower case, each word singular (the same rule the subject names use). */
    fun norm(s: String): String = Searcher.tokens(s).map(Searcher::stem).joinToString(" ")

    private fun clean(q: String): String {
        var s = q.lowercase().trim()
            .replace("what's", "what is").replace("whats", "what is").replace("how's", "how is")
            .replace(Regex("'s\\b"), "").replace("'", "")
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").replace(Regex("\\s+"), " ").trim()
        while (true) {
            val t = s.replace(FILLER_END, "")
            if (t == s) break
            s = t
        }
        return s
    }

    fun parse(question: String, catalog: ReadingCatalog): ReadingQuestion? {
        if (catalog.metrics.isEmpty()) return null
        val q = clean(question)
        LEAD_HOW.matchEntire(q)?.let { m ->
            val phrase = stripSessionWord(m.groupValues[1].replace(ARTICLE, "")).first
            subjectNamed(phrase, catalog)?.let { return ReadingQuestion.LastSession(it) }
            return null
        }
        val phrase = (LEAD_WHAT.matchEntire(q) ?: LEAD_LAST.matchEntire(q))?.groupValues?.get(1)?.replace(ARTICLE, "") ?: return null
        val (subjectPart, hadWord) = stripSessionWord(phrase)
        if (hadWord) subjectNamed(subjectPart, catalog)?.let { return ReadingQuestion.LastSession(it) }
        return metricNamed(phrase, catalog)
    }

    private fun stripSessionWord(phrase: String): Pair<String, Boolean> {
        val words = phrase.trim().split(' ').filter { it.isNotEmpty() }
        if (words.size > 1 && words.last() in SESSION_WORDS) return words.dropLast(1).joinToString(" ") to true
        return words.joinToString(" ") to false
    }

    private fun subjectNamed(phrase: String, catalog: ReadingCatalog): String? {
        val want = norm(phrase)
        if (want.isEmpty()) return null
        return catalog.subjects.filter { s -> (listOf(s.name) + s.aliases).any { norm(it) == want } }
            .maxByOrNull { it.lastAt }?.subjectId
    }

    private fun metricNamed(phrase: String, catalog: ReadingCatalog): ReadingQuestion? {
        val want = norm(phrase).split(' ').filter { it !in ARTICLES }.joinToString(" ")
        if (want.isEmpty()) return null
        val bare = mutableListOf<MetricInfo>()
        val specific = mutableListOf<MetricInfo>()
        for (m in catalog.metrics) {
            val names = listOf(m.label, m.key) + m.aliases
            val subject = m.subjectName?.let(::norm)?.takeIf { it.isNotEmpty() }
            if (names.any { norm(it) == want }) bare += m
            if (subject != null && names.any { n ->
                    val nn = norm(n)
                    want == "$subject $nn" || want == "$nn of $subject" || want == "$nn for $subject" || want == "$nn at $subject"
                }
            ) specific += m
        }
        val pool = specific.ifEmpty { bare }
        if (pool.isEmpty()) return null
        val chosen = pool.maxByOrNull { it.lastAt }!!
        return ReadingQuestion.LastValue(chosen.metricId, named = specific.isEmpty() && pool.size > 1)
    }
}

/** Card 07 item 6: the words that give a reading back. Pure. Never says whether a value is good. */
object ReadingAnswers {
    /** "2 days ago" as "two days ago" (up to twelve), otherwise as [Age] says it. */
    fun ago(thenMs: Long, nowMs: Long): String {
        val s = Age.describe(thenMs, nowMs)
        val m = Regex("^(\\d+) (.*)$").matchEntire(s) ?: return s
        val n = m.groupValues[1].toInt()
        return if (n in 1..12) "${WORDS[n - 1]} ${m.groupValues[2]}" else s
    }

    private val WORDS = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve")

    /** The value as said, with the unit only when he said one ("7.2", "$24.99", "$24.99/lb", "70 ppm"). */
    fun display(f: ReadingFact): String {
        val v = f.valueText.trim()
        val u = f.unit?.trim()?.takeIf { it.isNotEmpty() } ?: return v
        return when {
            u.startsWith("$") -> {
                val base = if (v.startsWith("$")) v else "$" + v
                val rest = u.drop(1)
                if (rest.isEmpty() || base.endsWith(rest)) base else base + rest
            }
            v.endsWith(u, ignoreCase = true) -> v
            u.first().isLetter() -> "$v $u"
            else -> v + u
        }
    }

    private fun shown(f: ReadingFact) = if (f.valueNum == null) "'${f.valueText.trim()}'" else display(f)

    private fun nameOf(f: ReadingFact, named: Boolean): String = when {
        f.metricLabel.equals("price", ignoreCase = true) -> f.subjectName ?: "Price"
        named && f.subjectName != null -> f.subjectName + " " + f.metricLabel
        else -> f.metricLabel.let { if (it.any(Char::isUpperCase)) it else it.replaceFirstChar(Char::uppercase) }
    }

    private fun placePart(f: ReadingFact) = f.place?.trim()?.takeIf { it.isNotEmpty() }?.let { " at $it" }.orEmpty()

    /**
     * [rows] are one metric's readings, newest first. "pH was 7.2, two days ago. Before that: 7.6, nine days ago."
     * A place is named when there is one; a word value is quoted.
     */
    fun lastValue(rows: List<ReadingFact>, now: Long, named: Boolean = false): String {
        val first = rows.firstOrNull() ?: return "I don't have a reading for that."
        val sb = StringBuilder("${nameOf(first, named)} was ${shown(first)}${placePart(first)}, ${ago(first.measuredAt, now)}.")
        rows.getOrNull(1)?.let { sb.append(" Before that: ${shown(it)}${placePart(it)}, ${ago(it.measuredAt, now)}.") }
        return sb.toString()
    }

    /** "Your last pool test was two days ago: free chlorine 5, pH 7.2, alkalinity 70." */
    fun lastSession(subjectName: String, session: ReadingSession, now: Long): String {
        val noun = if (Regex("\\btest(?:s|ed|ing)?\\b", RegexOption.IGNORE_CASE).containsMatchIn(session.rows.firstOrNull()?.quote.orEmpty())) "test" else "reading"
        val name = if (subjectName.drop(1) == subjectName.drop(1).lowercase()) subjectName.replaceFirstChar { it.lowercase() } else subjectName
        val values = session.rows.joinToString(", ") { "${it.metricLabel} ${if (it.valueNum == null) it.valueText.trim() else display(it)}" }
        return "Your last $name $noun was ${ago(session.measuredAt, now)}: $values."
    }
}
