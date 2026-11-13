package app.stash.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/** When a task is wanted, resolved on the phone from what he said. */
sealed interface Due {
    data object None : Due
    data object Soon : Due
    data class On(val date: LocalDate) : Due
    data class By(val date: LocalDate) : Due
    data class Window(val from: LocalDate, val to: LocalDate) : Due
}

/**
 * Turns a [DateExpr] into dates. Pure: [today] is passed in (the phone's clock and zone, never the model's).
 * Weeks run Monday to Sunday.
 */
object DateResolver {
    private val WEEKDAYS = mapOf(
        "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY,
        "thu" to DayOfWeek.THURSDAY, "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY, "sun" to DayOfWeek.SUNDAY,
    )

    fun resolveDue(expr: DateExpr?, today: LocalDate): Due {
        if (expr == null) return Due.None
        val by = expr.strictness == "by"
        val around = expr.strictness == "around"

        fun date(d: LocalDate?): Due = when {
            d == null -> Due.None
            by -> Due.By(d)
            else -> Due.On(d)
        }

        fun window(from: LocalDate, to: LocalDate): Due = if (by) Due.By(to) else Due.Window(from, to)

        when (expr.named) {
            "soon" -> return Due.Soon
            "someday" -> return Due.None
            "today", "tonight" -> return date(today)
            "tomorrow" -> return date(today.plusDays(1))
            "yesterday" -> return date(today.minusDays(1))
            "this_weekend" -> return when (today.dayOfWeek) {
                DayOfWeek.SATURDAY -> window(today, today.plusDays(1))
                DayOfWeek.SUNDAY -> window(today, today)
                else -> {
                    val sat = today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY))
                    window(sat, sat.plusDays(1))
                }
            }
            "next_weekend" -> {
                val sat = if (today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY) {
                    today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY))
                } else {
                    today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY)).plusDays(7)
                }
                return window(sat, sat.plusDays(1))
            }
            "this_week" -> return window(today, today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)))
            "next_week" -> {
                val mon = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                return window(mon, mon.plusDays(6))
            }
            "end_of_month" -> return date(YearMonth.from(today).atEndOfMonth())
        }

        val wd = expr.weekday?.let { WEEKDAYS[it] }
        if (wd != null) {
            val d = when (expr.which) {
                "this" -> today.with(TemporalAdjusters.nextOrSame(wd))
                // "next Friday": the Friday of next week (Monday to Sunday), not merely the next one to come.
                "next" -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).with(TemporalAdjusters.nextOrSame(wd))
                "last" -> today.with(TemporalAdjusters.previous(wd))
                else -> today.with(TemporalAdjusters.next(wd))
            }
            return date(d)
        }

        if (expr.n != null && expr.unit != null) {
            val n = expr.n.toLong()
            val d = when (expr.unit) {
                "day" -> today.plusDays(n)
                "week" -> today.plusWeeks(n)
                "month" -> today.plusMonths(n)
                else -> return Due.None
            }
            if (around) {
                val slack = when (expr.unit) { "day" -> 1L; "week" -> 3L; else -> 7L }
                return Due.Window(d.minusDays(slack), d.plusDays(slack))
            }
            return date(d)
        }

        val month = expr.month
        val day = expr.day
        if (month != null && month in 1..12) {
            if (day == null) {
                val ym = if (expr.year != null) YearMonth.of(expr.year, month) else nextMonthOccurrence(month, today)
                val from = if (today.isAfter(ym.atDay(1)) && YearMonth.from(today) == ym) today else ym.atDay(1)
                return window(from, ym.atEndOfMonth())
            }
            return date(dayOfYear(month, day, expr.year, today, future = true))
        }
        if (month == null && day != null && day in 1..31) {
            var ym = YearMonth.from(today)
            repeat(14) {
                if (day <= ym.lengthOfMonth() && !ym.atDay(day).isBefore(today)) return date(ym.atDay(day))
                ym = ym.plusMonths(1)
            }
        }
        return Due.None
    }

    /** The date a past thing happened: the most recent such date that is not in the future. Null if not a date. */
    fun resolveHappened(expr: DateExpr?, today: LocalDate): LocalDate? {
        if (expr == null) return null
        when (expr.named) {
            "today", "tonight" -> return today
            "yesterday" -> return today.minusDays(1)
            null -> {}
            else -> return null
        }
        val wd = expr.weekday?.let { WEEKDAYS[it] }
        if (wd != null) {
            return if (expr.which == "last") today.with(TemporalAdjusters.previous(wd)) else today.with(TemporalAdjusters.previousOrSame(wd))
        }
        if (expr.n != null && expr.unit != null) {
            val n = expr.n.toLong()
            return when (expr.unit) {
                "day" -> today.minusDays(n)
                "week" -> today.minusWeeks(n)
                "month" -> today.minusMonths(n)
                else -> null
            }
        }
        val month = expr.month
        val day = expr.day
        if (month != null && day != null && month in 1..12) {
            return dayOfYear(month, day, expr.year, today, future = false)
        }
        return null
    }

    private fun nextMonthOccurrence(month: Int, today: LocalDate): YearMonth {
        val this_ = YearMonth.of(today.year, month)
        return if (this_.isBefore(YearMonth.from(today))) this_.plusYears(1) else this_
    }

    /** [future]: the next such date today or later; otherwise the latest such date today or earlier. Null if no such date exists. */
    private fun dayOfYear(month: Int, day: Int, year: Int?, today: LocalDate, future: Boolean): LocalDate? {
        fun make(y: Int): LocalDate? = if (day in 1..YearMonth.of(y, month).lengthOfMonth()) LocalDate.of(y, month, day) else null
        if (year != null) return make(year)
        if (future) {
            for (y in today.year..today.year + 8) {
                val d = make(y) ?: continue
                if (!d.isBefore(today)) return d
            }
        } else {
            for (y in today.year downTo today.year - 8) {
                val d = make(y) ?: continue
                if (!d.isAfter(today)) return d
            }
        }
        return null
    }
}
