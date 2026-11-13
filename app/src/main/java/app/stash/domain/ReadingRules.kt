package app.stash.domain

import kotlin.math.abs

/**
 * One stored reading with what the wording and the checks need of its record. Pure data: the repository fills it.
 * Values are as said: [valueText] is the owner's words or digits, [unit] is only what he said (docs/cards/07 context).
 */
data class ReadingFact(
    val readingId: String,
    val recordId: String,
    val captureId: String,
    val subjectId: String?,
    val subjectName: String?,
    val metricId: String,
    val metricLabel: String,
    val valueText: String,
    val valueNum: Double?,
    val unit: String?,
    val measuredAt: Long,
    /** Where it was said, as he said it, or the place subject's name (a price has a place: D75). */
    val place: String? = null,
    /** The record's attention reason, if any. */
    val attention: String? = null,
    /** The quote of its record: what he said. */
    val quote: String = "",
)

/** All the rows of the newest reading record of a subject, in the order he said them. */
data class ReadingSession(val recordId: String, val captureId: String, val measuredAt: Long, val rows: List<ReadingFact>)

/** Card 07 section B items 3 and 4. Pure: no value is ever changed, only flagged. */
object ReadingRules {
    /** The earlier values needed before a value can be called odd. */
    const val MIN_HISTORY = 3

    /** More than this many times the usual, or less than one over it, is odd. */
    const val FACTOR = 5.0

    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2.0
    }

    /**
     * The usual value (the median) when [value] is more than 5 times, or less than one fifth of, the median of at least
     * three [earlier] numeric values; otherwise null. A zero median never flags. Compared by size, so a sign change alone
     * is not odd.
     */
    fun outlierMedian(value: Double?, earlier: List<Double>): Double? {
        if (value == null || earlier.size < MIN_HISTORY) return null
        val med = median(earlier) ?: return null
        if (med == 0.0) return null
        val ratio = abs(value) / abs(med)
        return if (ratio > FACTOR || ratio < 1.0 / FACTOR) med else null
    }

    /** `ODD_VALUE:pH:7.4`. A colon in the label would not break reading it back: the median is the last part. */
    fun oddValueReason(metricLabel: String, median: Double): String =
        Attention.ODD_VALUE_PREFIX + metricLabel.trim() + ":" + number(median)

    /** The median out of an `ODD_VALUE:` reason, or null if [attention] is not one. */
    fun medianOf(attention: String?): String? =
        attention?.takeIf { it.startsWith(Attention.ODD_VALUE_PREFIX) }?.substringAfterLast(':')

    /** Whole numbers without a decimal point, the rest to at most four places: 70, 7.4, 0.0625. */
    fun number(d: Double): String {
        if (d == Math.rint(d) && abs(d) < 1e15) return d.toLong().toString()
        return java.math.BigDecimal(d).setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    /** Both units are known and they differ (ignoring case and spaces). A reading with no unit never drifts: units are not invented. */
    fun unitDrifted(storedUnit: String?, newUnit: String?): Boolean {
        val a = storedUnit?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val b = newUnit?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return !a.replace(" ", "").equals(b.replace(" ", ""), ignoreCase = true)
    }

    /** The first unit heard for a metric becomes its unit: the new unit when the metric has none yet. */
    fun unitToStore(storedUnit: String?, newUnit: String?): String? =
        if (storedUnit.isNullOrBlank()) newUnit?.trim()?.takeIf { it.isNotEmpty() } else null

    /** Plain words for the flag a reading carries, for the list. Never says whether a value is good. */
    fun flagText(attention: String?): String? = when {
        attention == null -> null
        attention.startsWith(Attention.ODD_VALUE_PREFIX) ->
            "Very different from the usual (${medianOf(attention)}). Check it was heard right."
        attention == Attention.UNIT_CHANGED -> "The unit is not the one used before."
        else -> null
    }
}
