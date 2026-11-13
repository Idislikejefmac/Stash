package app.stash

import app.stash.domain.DateExpr
import app.stash.domain.DatePhrases
import app.stash.domain.DateResolver
import app.stash.domain.Due
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class DateResolverTest {
    private val wed = LocalDate.of(2026, 11, 11) // a Wednesday
    private val sat = LocalDate.of(2026, 11, 14)
    private val sun = LocalDate.of(2026, 11, 15)
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)
    private fun due(e: DateExpr?, today: LocalDate = wed) = DateResolver.resolveDue(e, today)
    private fun named(n: String, s: String? = null) = DateExpr(n, named = n, strictness = s)

    @Test fun nothingSaidIsNone() {
        assertEquals(Due.None, due(null))
        assertEquals(Due.None, due(DateExpr("sometime-ish")))
        assertEquals(Due.None, due(named("someday")))
    }

    @Test fun soon() = assertEquals(Due.Soon, due(named("soon")))

    @Test fun everyNamedValueFromAWednesday() {
        assertEquals(Due.On(wed), due(named("today")))
        assertEquals(Due.On(wed), due(named("tonight")))
        assertEquals(Due.On(d(2026, 11, 12)), due(named("tomorrow")))
        assertEquals(Due.On(d(2026, 11, 10)), due(named("yesterday")))
        assertEquals(Due.Window(sat, sun), due(named("this_weekend")))
        assertEquals(Due.Window(d(2026, 11, 21), d(2026, 11, 22)), due(named("next_weekend")))
        assertEquals(Due.Window(wed, sun), due(named("this_week")))
        assertEquals(Due.Window(d(2026, 11, 16), d(2026, 11, 22)), due(named("next_week")))
        assertEquals(Due.On(d(2026, 11, 30)), due(named("end_of_month")))
    }

    @Test fun thisWeekendOnASaturdayIsTodayAndTomorrow() {
        assertEquals(Due.Window(sat, sun), due(named("this_weekend"), sat))
        assertEquals(Due.Window(d(2026, 11, 21), d(2026, 11, 22)), due(named("next_weekend"), sat))
    }

    @Test fun thisWeekendOnASundayIsToday() {
        assertEquals(Due.Window(sun, sun), due(named("this_weekend"), sun))
        assertEquals(Due.Window(d(2026, 11, 21), d(2026, 11, 22)), due(named("next_weekend"), sun))
    }

    @Test fun endOfMonthOnTheLastDayIsToday() {
        assertEquals(Due.On(d(2026, 11, 30)), due(named("end_of_month"), d(2026, 11, 30)))
        assertEquals(Due.On(d(2028, 2, 29)), due(named("end_of_month"), d(2028, 2, 3)))
    }

    @Test fun weekdaysAcrossAWeekBoundary() {
        fun wd(day: String, which: String? = null, today: LocalDate = wed) = due(DateExpr(day, weekday = day, which = which), today)
        assertEquals(Due.On(d(2026, 11, 13)), wd("fri"))
        assertEquals(Due.On(d(2026, 11, 16)), wd("mon")) // across the weekend
        assertEquals(Due.On(d(2026, 11, 18)), wd("wed")) // today does not count unless "this"
        assertEquals(Due.On(wed), wd("wed", "this"))
        assertEquals(Due.On(d(2026, 11, 13)), wd("fri", "this"))
        assertEquals(Due.On(d(2026, 11, 16)), wd("mon", "this")) // "this Monday" said on Wednesday: the coming one
        assertEquals(Due.On(d(2026, 11, 20)), wd("fri", "next")) // Friday of next week
        assertEquals(Due.On(d(2026, 11, 16)), wd("mon", "next"))
        assertEquals(Due.On(d(2026, 11, 6)), wd("fri", "last"))
        assertEquals(Due.On(d(2026, 11, 16)), wd("mon", today = sat))
        assertEquals(Due.On(d(2026, 11, 21)), wd("sat", today = sat))
        assertEquals(Due.On(d(2026, 11, 16)), wd("mon", today = sun))
    }

    @Test fun strictnessByTurnsADateIntoBy() {
        assertEquals(Due.By(d(2026, 11, 13)), due(DateExpr("by Friday", weekday = "fri", strictness = "by")))
        assertEquals(Due.By(d(2026, 11, 12)), due(named("tomorrow", "by")))
        assertEquals(Due.By(sun), due(named("this_weekend", "by")))
        assertEquals(Due.By(d(2026, 11, 30)), due(named("end_of_month", "by")))
        assertEquals(Due.Soon, due(named("soon", "by")))
    }

    @Test fun nAndUnitCountFromToday() {
        assertEquals(Due.On(d(2026, 11, 14)), due(DateExpr("in 3 days", n = 3, unit = "day")))
        assertEquals(Due.On(d(2026, 11, 25)), due(DateExpr("in 2 weeks", n = 2, unit = "week")))
        assertEquals(Due.On(d(2027, 1, 11)), due(DateExpr("in 2 months", n = 2, unit = "month")))
        assertEquals(Due.Window(d(2026, 11, 22), d(2026, 11, 28)), due(DateExpr("around 2 weeks", n = 2, unit = "week", strictness = "around")))
        assertEquals(Due.None, due(DateExpr("in 2 fortnights", n = 2, unit = "fortnight")))
    }

    @Test fun monthAndDayWithNoYearIsTheNextSuchDate() {
        assertEquals(Due.On(d(2027, 1, 5)), due(DateExpr("January 5th", month = 1, day = 5)))
        assertEquals(Due.On(d(2026, 12, 25)), due(DateExpr("Christmas", month = 12, day = 25)))
        assertEquals(Due.On(wed), due(DateExpr("today", month = 11, day = 11)))
        assertEquals(Due.On(d(2027, 11, 5)), due(DateExpr("Nov 5", month = 11, day = 5)))
        assertEquals(Due.On(d(2028, 2, 29)), due(DateExpr("Feb 29", month = 2, day = 29)))
        assertEquals(Due.None, due(DateExpr("Feb 30", month = 2, day = 30)))
        assertEquals(Due.By(d(2027, 1, 5)), due(DateExpr("by Jan 5", month = 1, day = 5, strictness = "by")))
        assertEquals(Due.On(d(2026, 3, 1)), due(DateExpr("Mar 1 2026", month = 3, day = 1, year = 2026)))
    }

    @Test fun dayOfMonthAloneIsTheNextSuchDay() {
        assertEquals(Due.By(d(2026, 11, 15)), due(DateExpr("by the 15th", day = 15, strictness = "by")))
        assertEquals(Due.On(d(2026, 12, 5)), due(DateExpr("the 5th", day = 5)))
        assertEquals(Due.On(d(2026, 11, 11)), due(DateExpr("the 11th", day = 11)))
        assertEquals(Due.On(d(2026, 12, 31)), due(DateExpr("the 31st", day = 31), d(2026, 12, 1)))
        assertEquals(Due.On(d(2026, 12, 31)), due(DateExpr("the 31st", day = 31))) // November has no 31st
    }

    @Test fun monthAloneIsAWindow() {
        assertEquals(Due.Window(d(2026, 12, 1), d(2026, 12, 31)), due(DateExpr("in December", month = 12)))
        assertEquals(Due.Window(wed, d(2026, 11, 30)), due(DateExpr("in November", month = 11)))
        assertEquals(Due.Window(d(2027, 3, 1), d(2027, 3, 31)), due(DateExpr("in March", month = 3)))
    }

    // ---- when something happened ----

    private fun happened(e: DateExpr?, today: LocalDate = wed) = DateResolver.resolveHappened(e, today)

    @Test fun happenedFromWords() {
        assertEquals(d(2026, 11, 10), happened(DateExpr("yesterday", named = "yesterday")))
        assertEquals(wed, happened(DateExpr("today", named = "today")))
        assertNull(happened(DateExpr("soon", named = "soon")))
        assertNull(happened(null))
        assertNull(happened(DateExpr("a while")))
    }

    @Test fun happenedOnAWeekdayIsTheMostRecentOneNotInTheFuture() {
        assertEquals(d(2026, 11, 10), happened(DateExpr("Tuesday", weekday = "tue")))
        assertEquals(wed, happened(DateExpr("Wednesday", weekday = "wed")))
        assertEquals(d(2026, 11, 4), happened(DateExpr("last Wednesday", weekday = "wed", which = "last")))
        assertEquals(d(2026, 11, 9), happened(DateExpr("Monday", weekday = "mon")))
        assertEquals(d(2026, 11, 7), happened(DateExpr("Saturday", weekday = "sat"))) // across the week boundary
    }

    @Test fun happenedOnAMonthAndDayIsTheMostRecentPastOne() {
        assertEquals(d(2026, 1, 5), happened(DateExpr("January fifth", month = 1, day = 5)))
        assertEquals(d(2025, 12, 25), happened(DateExpr("Christmas", month = 12, day = 25)))
        assertEquals(wed, happened(DateExpr("today", month = 11, day = 11)))
        assertEquals(d(2025, 11, 12), happened(DateExpr("Nov 12", month = 11, day = 12)))
        assertEquals(d(2024, 2, 29), happened(DateExpr("Feb 29", month = 2, day = 29)))
        assertEquals(d(2020, 6, 1), happened(DateExpr("June 1 2020", month = 6, day = 1, year = 2020)))
        assertNull(happened(DateExpr("Feb 30", month = 2, day = 30)))
    }

    @Test fun happenedNAgo() {
        assertEquals(d(2026, 11, 8), happened(DateExpr("3 days ago", n = 3, unit = "day")))
        assertEquals(d(2026, 11, 4), happened(DateExpr("a week ago", n = 1, unit = "week")))
        assertEquals(d(2026, 9, 11), happened(DateExpr("2 months ago", n = 2, unit = "month")))
    }
}

class DatePhrasesTest {
    private fun due(s: String) = DatePhrases.findDue(s)?.expr

    @Test fun dueNamedPhrases() {
        assertEquals("soon", due("clean out the car soon")?.named)
        assertEquals("soon", due("do it one of these days")?.named)
        assertEquals("tomorrow", due("call the dentist tomorrow")?.named)
        assertEquals("tonight", due("take out the trash tonight")?.named)
        assertEquals("this_weekend", due("mow the lawn this weekend")?.named)
        assertEquals("this_weekend", due("mow the lawn on the weekend")?.named)
        assertEquals("next_weekend", due("go next weekend")?.named)
        assertEquals("next_week", due("send it next week")?.named)
        assertEquals("this_week", due("finish this week")?.named)
        assertEquals("end_of_month", due("pay it at the end of the month")?.named)
        assertEquals("by", due("pay it by the end of the month")?.strictness)
    }

    @Test fun dueWeekdaysAndCounts() {
        val fri = due("pay the bill by Friday")!!
        assertEquals("fri", fri.weekday); assertEquals("by", fri.strictness); assertEquals("by Friday", fri.said)
        assertEquals("by", due("finish before Monday")!!.strictness)
        assertEquals("on", due("dentist on Tuesday")!!.strictness)
        assertEquals("this", due("do it this Saturday")!!.which)
        assertEquals("next", due("do it next Sunday")!!.which)
        val n = due("renew in two weeks")!!
        assertEquals(2, n.n); assertEquals("week", n.unit); assertEquals("around", n.strictness)
        assertEquals(3, due("renew in 3 days")!!.n)
        val day = due("pay by the 15th")!!
        assertEquals(15, day.day); assertEquals("by", day.strictness)
        assertEquals(5, due("pay by the fifth")!!.day)
    }

    @Test fun dueIgnoresWhatIsNotADate() {
        assertNull(due("buy one the next time I go to Ross"))
        assertNull(due("pick up the kids by 5"))
        assertNull(due("clean the garage"))
        assertNull(DatePhrases.findDue(""))
    }

    @Test fun earliestPhraseWins() {
        val f = DatePhrases.findDue("call mom tomorrow and pay on Friday")!!
        assertEquals("tomorrow", f.expr.named)
    }

    @Test fun whenPhrases() {
        assertEquals(1, DatePhrases.parseWhen("January fifth").month)
        assertEquals(5, DatePhrases.parseWhen("January fifth").day)
        assertEquals("January fifth", DatePhrases.parseWhen("January fifth").said)
        assertEquals(5, DatePhrases.parseWhen("January 5th").day)
        assertEquals(25, DatePhrases.parseWhen("December twenty fifth").day)
        assertEquals(2024, DatePhrases.parseWhen("March 3 2024").year)
        assertEquals("tue", DatePhrases.parseWhen("Tuesday").weekday)
        assertEquals("tue", DatePhrases.parseWhen("last Tuesday").weekday)
        assertEquals("last", DatePhrases.parseWhen("last Tuesday").which)
        assertEquals("yesterday", DatePhrases.parseWhen("yesterday").named)
        assertEquals(3, DatePhrases.parseWhen("three days ago").n)
        val unknown = DatePhrases.parseWhen("a while back")
        assertEquals("a while back", unknown.said)
        assertNull(unknown.named); assertNull(unknown.month)
    }

    @Test fun findWhenInASentence() {
        assertEquals("today", DatePhrases.findWhen("I changed the filter today")?.expr?.named)
        assertEquals("yesterday", DatePhrases.findWhen("I mowed yesterday")?.expr?.named)
        assertEquals("yesterday", DatePhrases.findWhen("I mowed last night")?.expr?.named)
        assertNull(DatePhrases.findWhen("Sunday school was fun"))
        assertEquals("tue", DatePhrases.findWhen("I painted on Tuesday")?.expr?.weekday)
    }

    @Test fun dayNumbers() {
        assertEquals(1, DatePhrases.parseDayNum("first"))
        assertEquals(22, DatePhrases.parseDayNum("twenty-second"))
        assertEquals(31, DatePhrases.parseDayNum("31st"))
        assertNull(DatePhrases.parseDayNum("32nd"))
        assertNull(DatePhrases.parseDayNum("banana"))
    }
}
