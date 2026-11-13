package app.stash.domain

import app.stash.data.CaptureEntity

/**
 * Note text that may be sent to a hosted extractor. The constructor is private and the only way in is
 * [Sensitivity.sendable], which refuses a sensitive capture; a hosted [app.stash.model.Extractor] takes this type,
 * so sending a private note is a compile error. (A test keeps `SendableText.mint` out of every other file.)
 */
@JvmInline
value class SendableText private constructor(val value: String) {
    companion object {
        /** Only [Sensitivity] calls this. */
        internal fun mint(text: String) = SendableText(text)
    }
}

/** Why a capture was (or should be) marked Private. The code is what `capture.sensitive_reason` stores. */
enum class SensitiveReason(val code: String) {
    /** He said so ("private note ..."). */
    SAID("SAID"),

    /** A password, passcode, PIN, combination or an extra word of his. */
    AUTO_SECRET("AUTO_SECRET"),

    /** An account, card, licence or social security number, or a long run of digits. */
    PERSONAL_CODE("PERSONAL_CODE"),
}

/** Softer categories the app only suggests Private for; it never sets the mark itself. */
enum class SuggestCategory { HEALTH, MONEY }

/** The check that keeps private notes on the phone (docs/cards/03 section B). Pure. */
object Sensitivity {
    private val MARKER = Regex(
        "^\\s*(?:(?:ok|okay|um|uh|so)\\b[,.]?\\s+)*(?:this is a private note|this is private|private note|local note|private)\\b[\\s:,.\\-]*",
        RegexOption.IGNORE_CASE,
    )

    private val SECRET_WORDS = listOf(
        Regex("\\bpass ?words?\\b", RegexOption.IGNORE_CASE),
        Regex("\\bpass ?codes?\\b", RegexOption.IGNORE_CASE),
        Regex("\\bPIN\\b"), // capitals only: "pin the map" is not a secret
        Regex("\\bpin (?:number|code)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bcombination\\b", RegexOption.IGNORE_CASE),
        Regex("\\bthe code is\\b", RegexOption.IGNORE_CASE),
        Regex("\\bcode for\\b", RegexOption.IGNORE_CASE),
        Regex("\\bsecurity code\\b", RegexOption.IGNORE_CASE),
    )

    private val PERSONAL_WORDS = listOf(
        Regex("\\bsocial security\\b", RegexOption.IGNORE_CASE),
        Regex("\\bSSN\\b", RegexOption.IGNORE_CASE),
        Regex("\\baccount number\\b", RegexOption.IGNORE_CASE),
        Regex("\\brouting number\\b", RegexOption.IGNORE_CASE),
        Regex("\\bcard number\\b", RegexOption.IGNORE_CASE),
        Regex("\\blicen[cs]e number\\b", RegexOption.IGNORE_CASE),
    )

    /** Six or more digits, with single spaces or dashes allowed between them. A decimal point ends a run. */
    private val DIGIT_RUN = Regex("(?<![\\d.])(?:\\d[ -]?){5,}\\d(?![\\d.]\\d)")

    /** The reason this text must stay on the phone, or null. [extraWords] are the owner's own "always private" words. */
    fun check(text: String, extraWords: List<String> = emptyList()): SensitiveReason? {
        if (removeMarker(text) != null) return SensitiveReason.SAID
        if (SECRET_WORDS.any { it.containsMatchIn(text) }) return SensitiveReason.AUTO_SECRET
        if (extraWords.any { w -> w.isNotBlank() && Regex("\\b" + Regex.escape(w.trim()) + "\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) }) {
            return SensitiveReason.AUTO_SECRET
        }
        if (PERSONAL_WORDS.any { it.containsMatchIn(text) }) return SensitiveReason.PERSONAL_CODE
        if (hasDigitRun(text)) return SensitiveReason.PERSONAL_CODE
        return null
    }

    private fun hasDigitRun(text: String): Boolean {
        for (m in DIGIT_RUN.findAll(text)) {
            val prev = text.getOrNull(m.range.first - 1)
            val next = text.getOrNull(m.range.last + 1)
            // "$1,250,000" or "24.99" style numbers are prices and measurements, not codes.
            if (prev == '$' || prev == ',' || next == ',') continue
            if (m.value.count { it.isDigit() } >= 6) return true
        }
        return false
    }

    /**
     * "private" among the first [EARLY_WORDS] words: the speech engine often garbles the opening
     * ("The private goat cake ..."), so the marker need not be the very first word.
     * Deliberately wide: a note wrongly made Private costs one tap to undo; a secret sent costs far more.
     */
    private const val EARLY_WORDS = 4

    /** A marker said at the end: "make that private", "this is private", "this is a private note". */
    private val TRAILING = Regex(
        "[\\s,.;:\\-]*\\b(?:(?:and|please|ok|okay)\\s+)*(?:(?:make|mark|set|keep)\\s+(?:that|this|it)\\s+(?:as\\s+)?private|this is (?:a )?private(?: note)?)\\b[\\s.!,]*$",
        RegexOption.IGNORE_CASE,
    )

    private val NOTE_AFTER = Regex("^\\s+note\\b[\\s:,.\\-]*", RegexOption.IGNORE_CASE)

    /** The text with every spoken Private marker taken out, or null if it holds none. Pure. */
    private fun removeMarker(text: String): String? {
        var out = text
        var found = false
        if (MARKER.containsMatchIn(out)) {
            out = MARKER.replaceFirst(out, "")
            found = true
        } else {
            val words = Regex("\\S+").findAll(out).take(EARLY_WORDS).toList()
            val i = words.indexOfFirst { w -> w.value.trim { c -> !c.isLetter() }.equals("private", ignoreCase = true) }
            if (i >= 0) {
                var end = words[i].range.last + 1
                NOTE_AFTER.find(out.substring(end))?.let { end += it.value.length }
                out = (out.substring(0, words[i].range.first).trimEnd() + " " + out.substring(end).trimStart()).trim()
                found = true
            }
        }
        if (TRAILING.containsMatchIn(out)) {
            out = TRAILING.replace(out, "")
            found = true
        }
        return if (found) out.trim() else null
    }

    /** Remove the spoken marker phrase. Applied to the stored `text`, never to `raw_text`. */
    fun stripMarker(text: String): String = removeMarker(text) ?: text.trim()

    private val HEALTH = listOf(
        Regex("\\bblood pressure\\b", RegexOption.IGNORE_CASE),
        Regex("\\bblood sugar\\b", RegexOption.IGNORE_CASE),
        Regex("\\bglucose\\b", RegexOption.IGNORE_CASE),
        Regex("\\bi weigh\\b", RegexOption.IGNORE_CASE),
        Regex("\\bdiagnos(?:is|ed)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bprescription\\b", RegexOption.IGNORE_CASE),
        Regex("\\bdoses?\\b", RegexOption.IGNORE_CASE),
        Regex("\\b\\d+(?:\\.\\d+)?\\s?mg\\b", RegexOption.IGNORE_CASE),
    )
    private val MONEY = listOf(
        Regex("\\bsalary\\b", RegexOption.IGNORE_CASE),
        Regex("\\bbalance\\b", RegexOption.IGNORE_CASE),
        Regex("\\bowe[sd]?\\b", RegexOption.IGNORE_CASE),
        Regex("\\bdebt\\b", RegexOption.IGNORE_CASE),
        Regex("\\baccounts?\\b", RegexOption.IGNORE_CASE),
    )

    /** A category to suggest Private for; the note is still filed normally and nothing is set. */
    fun suggest(text: String): SuggestCategory? = when {
        HEALTH.any { it.containsMatchIn(text) } -> SuggestCategory.HEALTH
        MONEY.any { it.containsMatchIn(text) } -> SuggestCategory.MONEY
        else -> null
    }

    /** The only door to [SendableText]: null for a capture marked sensitive. */
    fun sendable(capture: CaptureEntity): SendableText? =
        if (capture.sensitive) null else SendableText.mint(capture.text)
}
