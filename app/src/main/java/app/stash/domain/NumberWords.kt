package app.stash.domain

import kotlin.math.abs

/** One run of number tokens found in a text: where it is, and what it can mean. */
data class NumberSpan(
    /** Offsets into the scanned text: [start, end). */
    val start: Int,
    val end: Int,
    /** The words or digits as written, e.g. "seven point two", "$24.99". */
    val text: String,
    /** The most likely value ("twenty four ninety nine" is 24.99). */
    val primary: Double,
    /** Every reading worth accepting (each group on its own, the money form, the decimal form). */
    val candidates: List<Double>,
    val isDigits: Boolean,
    val hasDollar: Boolean,
)

/**
 * Numbers as digits or as spoken words, for the one job of checking that a number an extractor returned really
 * is in the words he said (docs/cards/03 item 7), and for the rules extractor to find values.
 * Never used to change a value: readings keep what was said.
 */
object NumberWords {
    private val UNITS = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70,
        "eighty" to 80, "ninety" to 90,
    )
    private val TOKEN = Regex("(?:\\$\\s?)?\\d+(?:,\\d{3})*(?:\\.\\d+)?|[A-Za-z]+(?:-[A-Za-z]+)?")

    private sealed interface Tok {
        val start: Int
        val end: Int
    }

    private data class Word(val w: String, override val start: Int, override val end: Int) : Tok
    private data class Digits(val value: Double, val dollar: Boolean, override val start: Int, override val end: Int) : Tok

    private fun isNumberWord(w: String) = w in UNITS || w in TENS || w == "hundred" || w == "thousand" || w == "oh" ||
        w == "point"

    /** Find every run of number tokens. Hyphenated words ("twenty-four") count as two words. */
    fun scan(text: String): List<NumberSpan> {
        val toks = mutableListOf<Tok?>() // null marks a break
        var lastEnd = 0
        for (m in TOKEN.findAll(text)) {
            val gap = text.substring(lastEnd, m.range.first)
            // A comma, full stop or other punctuation between tokens ends a run; plain spaces do not.
            if (gap.any { !it.isWhitespace() && it != '-' }) toks += null
            lastEnd = m.range.last + 1
            val s = m.value
            if (s[0].isLetter()) {
                val parts = s.lowercase().split('-')
                var off = m.range.first
                for (p in parts) {
                    val st = off
                    val en = st + p.length
                    toks += if (isNumberWord(p)) Word(p, st, en) else null
                    off = en + 1
                }
            } else {
                val dollar = s.startsWith("$")
                val digits = s.removePrefix("$").trim().replace(",", "")
                val v = digits.toDoubleOrNull()
                toks += if (v == null) null else Digits(v, dollar, m.range.first, m.range.last + 1)
            }
        }
        val spans = mutableListOf<NumberSpan>()
        val run = mutableListOf<Tok>()
        fun flush() {
            if (run.isNotEmpty()) buildSpan(run.toList(), text)?.let { spans += it }
            run.clear()
        }
        for (t in toks) if (t == null) flush() else run += t
        flush()
        return spans
    }

    private fun buildSpan(run: List<Tok>, text: String): NumberSpan? {
        // Trim a leading or trailing "point" or "oh": "point" alone is not a number.
        var items = run
        while (items.isNotEmpty() && (items.last() as? Word)?.w == "point") items = items.dropLast(1)
        while (items.isNotEmpty() && (items.first() as? Word)?.w == "point") items = items.drop(1)
        while (items.isNotEmpty() && (items.last() as? Word)?.w == "oh") items = items.dropLast(1)
        if (items.isEmpty()) return null
        val start = items.first().start
        val end = items.last().end
        val pointAt = items.indexOfFirst { (it as? Word)?.w == "point" }
        val cands = mutableListOf<Double>()
        val primary: Double
        if (pointAt >= 0) {
            val left = items.subList(0, pointAt)
            val right = items.subList(pointAt + 1, items.size)
            val l = if (left.isEmpty()) listOf(0.0) else groups(left).ifEmpty { return null }
            val frac = StringBuilder()
            for (r in right) {
                when {
                    r is Digits && r.value >= 0 && r.value == Math.floor(r.value) && r.value < 10 -> frac.append(r.value.toInt())
                    r is Word && (r.w in UNITS && UNITS.getValue(r.w) < 10 || r.w == "oh") ->
                        frac.append(if (r.w == "oh") 0 else UNITS.getValue(r.w))
                    r is Digits -> frac.append(trimDigits(r.value))
                    else -> return null
                }
            }
            if (frac.isEmpty()) return null
            primary = (l.first().toLong().toString() + "." + frac).toDouble()
            cands += primary
            cands += l
        } else {
            val g = groups(items).ifEmpty { return null }
            cands += g
            primary = if (g.size == 2 && g[1] in 1.0..99.0 && g[0] >= 0 && isIntegerGroup(g[0]) &&
                items.none { it is Digits }
            ) g[0] + g[1] / 100.0 else g.first()
            if (primary != g.first()) cands += primary
        }
        val digitsOnly = items.all { it is Digits }
        val dollar = items.any { it is Digits && it.dollar }
        return NumberSpan(start, end, text.substring(start, end).trim(), primary, cands.distinct(), digitsOnly, dollar)
    }

    private fun isIntegerGroup(d: Double) = d == Math.floor(d)

    private fun trimDigits(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else d.toString()

    /** Split a run of number tokens into standalone numbers ("twenty four ninety nine" is [24, 99]). Empty if it is not a number. */
    private fun groups(items: List<Tok>): List<Double> {
        val out = mutableListOf<Double>()
        var total = 0L
        var current = 0L
        var state = 0 // 0 start, 1 after tens, 2 after unit or teen, 3 after hundred, 4 after oh
        var open = false
        fun close() {
            if (open) out += (total + current).toDouble()
            total = 0; current = 0; state = 0; open = false
        }
        for (t in items) {
            if (t is Digits) {
                close()
                out += t.value
                continue
            }
            val w = (t as Word).w
            val unit = UNITS[w]
            val tens = TENS[w]
            when {
                w == "oh" -> {
                    close(); open = true; state = 4
                }
                unit != null && unit in 1..9 && (state == 0 || state == 1 || state == 3 || state == 4) -> {
                    current += unit; state = 2; open = true
                }
                unit != null && unit >= 10 && (state == 0 || state == 3) -> {
                    current += unit; state = 2; open = true
                }
                unit == 0 && state == 0 -> {
                    open = true; state = 2
                }
                tens != null && (state == 0 || state == 3) -> {
                    current += tens; state = 1; open = true
                }
                w == "hundred" && open && current in 1..9 && state == 2 -> {
                    current *= 100; state = 3
                }
                w == "thousand" && open && current > 0 -> {
                    total += current * 1000; current = 0; state = 3
                }
                else -> {
                    // Cannot continue this number: start a new one with this token.
                    close()
                    if (w == "hundred" || w == "thousand" || w == "point") return emptyList()
                    val restart = restartWith(t)
                    total = restart.first; current = restart.second; state = restart.third; open = true
                }
            }
        }
        close()
        return out
    }

    private fun restartWith(t: Tok): Triple<Long, Long, Int> {
        val w = (t as Word).w
        UNITS[w]?.let { return Triple(0L, it.toLong(), 2) }
        TENS[w]?.let { return Triple(0L, it.toLong(), 1) }
        return Triple(0L, 0L, 0)
    }

    /** The value of [text] if all of it is one number ("seven point two", "24.99", "$5"), else null. */
    fun parse(text: String): Double? {
        val spans = scan(text)
        val s = spans.singleOrNull() ?: return null
        val rest = text.removeRange(s.start, s.end).trim()
        if (rest.isNotEmpty() && rest.any { it.isLetterOrDigit() }) return null
        return s.primary
    }

    /**
     * True when [number] (as digits, "7.2", "$24.99", "1,250") is in [text], as digits or as spoken words.
     * "72" does not match 7.2; "twenty four ninety nine" and "$24.99" both match 24.99.
     */
    fun matches(number: String, text: String): Boolean {
        val target = number.trim().removePrefix("$").replace(",", "").toDoubleOrNull() ?: return false
        return matches(target, text)
    }

    fun matches(target: Double, text: String): Boolean =
        scan(text).any { span -> span.candidates.any { abs(it - target) < 1e-9 } }
}
