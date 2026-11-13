package app.stash.domain

/**
 * Finds the few time phrases the offline rules understand and turns them into [DateExpr]s (the phone resolves them
 * with [DateResolver]). Conservative: a phrase it does not know is kept as `said` only, and nothing is guessed.
 */
object DatePhrases {
    private val DAYS = mapOf(
        "monday" to "mon", "mon" to "mon", "tuesday" to "tue", "tue" to "tue", "tues" to "tue",
        "wednesday" to "wed", "wed" to "wed", "thursday" to "thu", "thu" to "thu", "thurs" to "thu",
        "friday" to "fri", "fri" to "fri", "saturday" to "sat", "sat" to "sat", "sunday" to "sun", "sun" to "sun",
    )
    private val MONTHS = listOf(
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october",
        "november", "december",
    )
    private val ORDINALS = mapOf(
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6, "seventh" to 7,
        "eighth" to 8, "ninth" to 9, "tenth" to 10, "eleventh" to 11, "twelfth" to 12, "thirteenth" to 13,
        "fourteenth" to 14, "fifteenth" to 15, "sixteenth" to 16, "seventeenth" to 17, "eighteenth" to 18,
        "nineteenth" to 19, "twentieth" to 20, "thirtieth" to 30,
    )
    private val ORD_TENS = mapOf("twenty" to 20, "thirty" to 30)
    private val ORD_UNITS = ORDINALS.filter { it.value < 10 }

    private const val DAY_ALT = "monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tues?|wed|thu(?:rs)?|fri|sat|sun"
    private const val MONTH_ALT = "january|february|march|april|may|june|july|august|september|october|november|december"
    private const val ORD_WORD = "(?:(?:twenty|thirty)[ -])?(?:first|second|third|fourth|fifth|sixth|seventh|eighth|ninth)|tenth|eleventh|twelfth|thirteenth|fourteenth|fifteenth|sixteenth|seventeenth|eighteenth|nineteenth|twentieth|thirtieth"
    private const val DAY_NUM = "(?:\\d{1,2}(?:st|nd|rd|th)?|$ORD_WORD)"

    /** "by the 15th": a bare "by 5" is more likely a time of day, so a day of the month needs its ordinal. */
    private const val DAY_NUM_ORD = "(?:\\d{1,2}(?:st|nd|rd|th)|$ORD_WORD)"

    /** A due phrase found in a task's words, and where. */
    data class Found(val expr: DateExpr, val range: IntRange)

    private val DUE_PATTERNS: List<Pair<Regex, (MatchResult) -> DateExpr>> = listOf(
        Regex("\\b(?:by|before)\\s+(?:the\\s+)?end of (?:the |this )?month\\b", RegexOption.IGNORE_CASE) to
            { m -> DateExpr(m.value, named = "end_of_month", strictness = "by") },
        Regex("\\b(?:at\\s+)?the end of (?:the |this )?month\\b", RegexOption.IGNORE_CASE) to
            { m -> DateExpr(m.value, named = "end_of_month") },
        Regex("\\b(?:by|before)\\s+(?:the\\s+)?($DAY_NUM_ORD)\\b", RegexOption.IGNORE_CASE) to
            { m -> dayOfMonth(m)?.let { d -> DateExpr(m.value, day = d, strictness = "by") } ?: DateExpr(m.value) },
        Regex("\\b(by|before|on|this|next|coming)\\s+($DAY_ALT)\\b", RegexOption.IGNORE_CASE) to { m ->
            val lead = m.groupValues[1].lowercase()
            DateExpr(
                m.value, weekday = DAYS[m.groupValues[2].lowercase()],
                which = when (lead) { "this" -> "this"; "next", "coming" -> "next"; else -> null },
                strictness = when (lead) { "by", "before" -> "by"; "on" -> "on"; else -> null },
            )
        },
        Regex("\\bnext weekend\\b", RegexOption.IGNORE_CASE) to { m -> DateExpr(m.value, named = "next_weekend") },
        Regex("\\b(?:this|on the) weekend\\b", RegexOption.IGNORE_CASE) to { m -> DateExpr(m.value, named = "this_weekend") },
        Regex("\\bnext week\\b", RegexOption.IGNORE_CASE) to { m -> DateExpr(m.value, named = "next_week") },
        Regex("\\bthis week\\b", RegexOption.IGNORE_CASE) to { m -> DateExpr(m.value, named = "this_week") },
        Regex("\\bin\\s+(\\d+|a|an|one|two|three|four|five|six|seven|eight|nine|ten)\\s+(day|week|month)s?\\b", RegexOption.IGNORE_CASE) to { m ->
            DateExpr(m.value, n = smallNumber(m.groupValues[1]), unit = m.groupValues[2].lowercase(), strictness = "around")
        },
        Regex("\\btomorrow\\b", RegexOption.IGNORE_CASE) to { m -> DateExpr(m.value, named = "tomorrow") },
        Regex("\\btonight\\b", RegexOption.IGNORE_CASE) to { m -> DateExpr(m.value, named = "tonight") },
        Regex("\\btoday\\b", RegexOption.IGNORE_CASE) to { m -> DateExpr(m.value, named = "today") },
        Regex("\\b(?:soon|one of these days|at some point|sometime soon)\\b", RegexOption.IGNORE_CASE) to
            { m -> DateExpr(m.value, named = "soon") },
        Regex("\\bsomeday\\b", RegexOption.IGNORE_CASE) to { m -> DateExpr(m.value, named = "someday") },
    )

    /** The first due phrase in [text] that the rules know, or null. */
    fun findDue(text: String): Found? {
        var best: Found? = null
        for ((re, build) in DUE_PATTERNS) {
            val m = re.find(text) ?: continue
            if (best == null || m.range.first < best.range.first) best = Found(build(m), m.range)
        }
        return best
    }

    private val WHEN_PATTERNS: List<Pair<Regex, (MatchResult) -> DateExpr>> = listOf(
        Regex("\\byesterday\\b", RegexOption.IGNORE_CASE) to { m -> DateExpr(m.value, named = "yesterday") },
        Regex("\\b(?:today|this morning|this afternoon|this evening|tonight|earlier today)\\b", RegexOption.IGNORE_CASE) to
            { m -> DateExpr(m.value, named = "today") },
        Regex("\\blast night\\b", RegexOption.IGNORE_CASE) to { m -> DateExpr(m.value, named = "yesterday") },
        Regex("\\b(last|on|this past)\\s+($DAY_ALT)\\b", RegexOption.IGNORE_CASE) to { m ->
            DateExpr(m.value, weekday = DAYS[m.groupValues[2].lowercase()], which = if (m.groupValues[1].lowercase() == "on") null else "last")
        },
        Regex("\\b($DAY_ALT)\\b", RegexOption.IGNORE_CASE) to { m ->
            DateExpr(m.value, weekday = DAYS[m.groupValues[1].lowercase()])
        },
        Regex("\\b(?:on\\s+)?($MONTH_ALT)\\s+(?:the\\s+)?($DAY_NUM)(?:,?\\s+(\\d{4}))?\\b", RegexOption.IGNORE_CASE) to { m ->
            val month = MONTHS.indexOf(m.groupValues[1].lowercase()) + 1
            DateExpr(m.value, month = month, day = parseDayNum(m.groupValues[2]), year = m.groupValues[3].toIntOrNull())
        },
        Regex("\\b(\\d+|a|one|two|three|four|five|six|seven|eight|nine|ten)\\s+(day|week|month)s?\\s+ago\\b", RegexOption.IGNORE_CASE) to { m ->
            DateExpr(m.value, n = smallNumber(m.groupValues[1]), unit = m.groupValues[2].lowercase())
        },
    )

    private const val BARE_WEEKDAY = 4

    /** What he said about when something happened, as an expression. A phrase it cannot read keeps only `said`. */
    fun parseWhen(said: String): DateExpr {
        for ((re, build) in WHEN_PATTERNS) {
            val m = re.find(said) ?: continue
            return build(m).copy(said = said.trim())
        }
        return DateExpr(said.trim())
    }

    /** A time phrase inside a sentence ("... today", "... on Tuesday"), for events. */
    fun findWhen(text: String): Found? {
        var best: Found? = null
        for ((i, p) in WHEN_PATTERNS.withIndex()) {
            // Index 4 is the bare weekday name: too weak inside a sentence ("Sunday school"); only the explicit forms.
            if (i == BARE_WEEKDAY) continue
            val m = p.first.find(text) ?: continue
            if (best == null || m.range.first < best.range.first) best = Found(p.second(m), m.range)
        }
        return best
    }

    private fun dayOfMonth(m: MatchResult): Int? = parseDayNum(m.groupValues[1])

    fun parseDayNum(s: String): Int? {
        val t = s.trim().lowercase()
        t.removeSuffix("st").removeSuffix("nd").removeSuffix("rd").removeSuffix("th").toIntOrNull()?.let { return it.takeIf { d -> d in 1..31 } }
        ORDINALS[t]?.let { return it }
        val parts = t.split(' ', '-')
        if (parts.size == 2) {
            val tens = ORD_TENS[parts[0]]
            val unit = ORD_UNITS[parts[1]]
            if (tens != null && unit != null) return tens + unit
        }
        return null
    }

    private fun smallNumber(s: String): Int? = when (val t = s.lowercase()) {
        "a", "an", "one" -> 1
        "two" -> 2
        "three" -> 3
        "four" -> 4
        "five" -> 5
        "six" -> 6
        "seven" -> 7
        "eight" -> 8
        "nine" -> 9
        "ten" -> 10
        else -> t.toIntOrNull()
    }
}
