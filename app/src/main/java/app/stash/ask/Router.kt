package app.stash.ask

import app.stash.domain.DatePhrases
import app.stash.domain.DateResolver
import app.stash.domain.ReadingCatalog
import app.stash.domain.ReadingQuestion
import app.stash.domain.ReadingQuestions
import app.stash.domain.TaskQuestion
import app.stash.domain.TaskQuestions
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/** Local dates, both included. Resolved on the phone; never sent anywhere. */
data class DateRange(val from: LocalDate, val to: LocalDate) {
    operator fun contains(d: LocalDate): Boolean = !d.isBefore(from) && !d.isAfter(to)
}

/** Which lookup a question goes to (docs/cards/08 B4). [name] is the pattern's code name, kept in the answer trace. */
sealed interface Route {
    val name: String

    data class WhereIs(val item: String) : Route { override val name get() = "where_is" }

    /** "who has X" names the item; "what did I lend P" names the person; "what have I lent" names neither. */
    data class Loans(val person: String?, val item: String?) : Route { override val name get() = "loans" }

    data class OpenTasks(val atPlace: String?) : Route {
        override val name get() = if (atPlace == null) "open_tasks" else "open_tasks_at_place"
    }

    /** Card 07's readings question, matched against the metrics and subjects he has logged. */
    data class LastReading(val question: ReadingQuestion) : Route { override val name get() = "last_reading" }

    data class LastEvent(val query: String) : Route { override val name get() = "last_event" }

    /** [said] is the time phrase as he said it ("last tuesday"), for the answer's wording. */
    data class OnDate(val range: DateRange, val said: String) : Route { override val name get() = "on_date" }

    data class About(val subject: String) : Route { override val name get() = "about" }
}

/**
 * The router (docs/cards/08 section B). Pure: patterns over the lower-cased question, few and literal. A question it
 * does not recognise returns null; that is not a failure (it goes to the loop or to search). The task shapes of card
 * 05 ([TaskQuestions]) and the readings shapes of card 07 ([ReadingQuestions]) are asked first and unchanged.
 */
object Router {
    /** Stored in every answer trace; bump it when a pattern changes. */
    const val VERSION = "r1"

    private const val LEAD = "(?:(?:hey|ok|okay|so|please|um|uh)\\s+)*"
    private const val ART = "(?:(?:my|the|our|a|an)\\s+)?"

    private val WHERE = listOf(
        Regex("^${LEAD}where (?:is|are|was|were) $ART(.+?)$"),
        Regex("^${LEAD}where did i (?:put|leave|store|keep|hide|park) $ART(.+?)$"),
        Regex("^${LEAD}where do i (?:keep|store) $ART(.+?)$"),
    )
    private val WHERE_TAIL = Regex("\\s+(?:now|again|right now|these days|last|at the moment)$")

    private val LOAN_ITEM = listOf(
        Regex("^${LEAD}who has $ART(.+?)$"),
        Regex("^${LEAD}who did i (?:lend|loan|give) $ART(.+?) to$"),
        Regex("^${LEAD}who (?:borrowed|has borrowed|took) $ART(.+?)$"),
    )
    private val LOAN_PERSON = listOf(
        Regex("^${LEAD}what did i (?:lend|loan) (?:to )?(.+?)$"),
        Regex("^${LEAD}what (?:does|did) (.+?) (?:have|borrow)(?: of mine| from me)?$"),
        Regex("^${LEAD}what (?:have i|did i) (?:lent|lend|loaned|loan) (?:to )?(.+?)$"),
    )
    private val LOAN_ANY = listOf(
        Regex("^${LEAD}what (?:have i|did i) (?:lent|lend|loaned|loan)(?: out)?$"),
        Regex("^${LEAD}who has (?:my )?(?:stuff|things|anything of mine)$"),
        Regex("^${LEAD}(?:what|which) (?:things |stuff )?(?:is|are) lent out$"),
    )

    private val TASK_EXTRA = listOf(
        Regex("^${LEAD}what (?:do|should) i (?:need|want) (?:to get )?from (?:the )?(.+?)$"),
        Regex("^${LEAD}what (?:was|am) i (?:going|supposed|meant) to (?:buy|get|pick up) at (?:the )?(.+?)$"),
    )

    private val LAST_EVENT = listOf(
        Regex("^${LEAD}when did i last (.+?)$"),
        Regex("^${LEAD}when was the last time (?:that )?i (.+?)$"),
        Regex("^${LEAD}when did i (.+?) last(?: time)?$"),
    )

    private val ON_DATE = Regex(
        "^${LEAD}what (?:did|have) i (?:say|said|note|noted|write|write down|jot down|save|saved|tell you|record|add)(?: down)?(?: to stash)? (.+?)$",
    )

    private val ABOUT = Regex("^${LEAD}(?:tell me|what do i know|what do you know|what do you have) about $ART(.+?)$")

    /** Lower case, apostrophes and punctuation gone, contractions opened, single spaces. */
    fun normalise(question: String): String {
        var s = question.lowercase().replace('’', '\'')
            .replace("what's", "what is").replace("where's", "where is").replace("who's", "who is")
            .replace("how's", "how is").replace("whats", "what is").replace("wheres", "where is")
        s = s.replace("'", "").replace(Regex("[^\\p{L}\\p{N} ]+"), " ").replace(Regex("\\s+"), " ").trim()
        return s.replace(Regex("\\s+please$"), "")
    }

    fun route(question: String, catalog: ReadingCatalog, today: LocalDate): Route? {
        val q = normalise(question)
        if (q.isEmpty()) return null

        // Card 05 and card 07 shapes, unchanged.
        when (val t = TaskQuestions.parse(question)) {
            TaskQuestion.WhatToHandle -> return Route.OpenTasks(null)
            is TaskQuestion.AtPlace -> return Route.OpenTasks(t.place)
            null -> {}
        }
        for (r in TASK_EXTRA) r.matchEntire(q)?.let { m -> clean(m.groupValues[1])?.let { return Route.OpenTasks(it) } }
        ReadingQuestions.parse(question, catalog)?.let { return Route.LastReading(it) }

        for (r in WHERE) r.matchEntire(q)?.let { m -> clean(m.groupValues[1].replace(WHERE_TAIL, ""))?.let { return Route.WhereIs(it) } }
        for (r in LOAN_ANY) if (r.matches(q)) return Route.Loans(null, null)
        for (r in LOAN_ITEM) r.matchEntire(q)?.let { m -> clean(m.groupValues[1])?.let { return Route.Loans(null, it) } }
        for (r in LOAN_PERSON) r.matchEntire(q)?.let { m -> clean(m.groupValues[1])?.let { return Route.Loans(it, null) } }
        for (r in LAST_EVENT) r.matchEntire(q)?.let { m -> clean(m.groupValues[1])?.let { return Route.LastEvent(it) } }
        ON_DATE.matchEntire(q)?.let { m ->
            val said = m.groupValues[1].trim()
            QuestionDates.parse(said, today)?.let { return Route.OnDate(it, said) }
        }
        ABOUT.matchEntire(q)?.let { m -> clean(m.groupValues[1])?.let { return Route.About(it) } }
        return null
    }

    private val ARTICLE = Regex("^(?:my|the|our|a|an|his|her|their)\\s+")

    /** The named thing without a leading article; null when nothing is left. */
    private fun clean(s: String): String? = s.trim().replace(ARTICLE, "").trim().takeIf { it.isNotEmpty() }
}

/** Card 08 B4 "what did I say yesterday / last Tuesday / on the 3rd / this week": the whole phrase must be a time. */
object QuestionDates {
    private const val DAY = "monday|tuesday|wednesday|thursday|friday|saturday|sunday"
    private const val MONTH = "january|february|march|april|may|june|july|august|september|october|november|december"
    private const val ORD = "\\d{1,2}(?:st|nd|rd|th)?|first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|eleventh|twelfth|" +
        "thirteenth|fourteenth|fifteenth|sixteenth|seventeenth|eighteenth|nineteenth|twentieth|thirtieth|(?:twenty|thirty) (?:first|second|third|fourth|fifth|sixth|seventh|eighth|ninth)"

    private val WEEKDAY = Regex("^(?:on |last |this past )?($DAY)$")
    private val MONTH_DAY = Regex("^(?:on )?($MONTH) (?:the )?($ORD)$")
    private val DAY_OF_MONTH = Regex("^(?:on )?the ($ORD)$")
    private val AGO = Regex("^(\\d+|a|one|two|three|four|five|six|seven|eight|nine|ten) (day|week)s? ago$")

    fun parse(phrase: String, today: LocalDate): DateRange? {
        val p = phrase.trim().lowercase()
        when (p) {
            "today", "earlier today", "this morning", "this afternoon", "this evening", "tonight" -> return DateRange(today, today)
            "yesterday", "last night", "yesterday morning", "yesterday afternoon", "yesterday evening" ->
                return today.minusDays(1).let { DateRange(it, it) }
            "this week" -> return DateRange(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), today)
            "last week" -> {
                val mon = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1)
                return DateRange(mon, mon.plusDays(6))
            }
            "this month" -> return DateRange(today.withDayOfMonth(1), today)
            "last month" -> YearMonth.from(today).minusMonths(1).let { return DateRange(it.atDay(1), it.atEndOfMonth()) }
        }
        if (WEEKDAY.matches(p) || MONTH_DAY.matches(p) || AGO.matches(p)) {
            val d = DateResolver.resolveHappened(DatePhrases.parseWhen(p), today) ?: return null
            if (d.isAfter(today)) return null
            return if (AGO.matches(p) && p.contains("week")) DateRange(d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), d.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).let { if (it.isAfter(today)) today else it })
            else DateRange(d, d)
        }
        DAY_OF_MONTH.matchEntire(p)?.let { m ->
            val n = DatePhrases.parseDayNum(m.groupValues[1].replace(' ', '-')) ?: return null
            var ym = YearMonth.from(today)
            repeat(13) {
                if (n <= ym.lengthOfMonth() && !ym.atDay(n).isAfter(today)) return ym.atDay(n).let { DateRange(it, it) }
                ym = ym.minusMonths(1)
            }
        }
        return null
    }
}
