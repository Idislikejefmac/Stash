package app.stash.model.rules

import app.stash.domain.NoteKind
import app.stash.domain.Structured
import app.stash.model.Structurer

/** Simple offline pattern matching. Handles the owner's common phrasings; anything else is OTHER. */
class RulesStructurer : Structurer {
    override suspend fun structure(text: String, vocabulary: List<String>): Structured? = parse(text)

    fun parse(raw: String): Structured {
        val t = raw.trim().replace(Regex("\\s+"), " ").trimEnd('.', '!', '?', ' ')

        LOAN_OUT.find(t)?.let { m ->
            val (item, person, date) = loanParts(m.groupValues[1], m.groupValues[2])
            return Structured(NoteKind.LOAN, item = clean(item), person = person, eventDate = date)
        }
        LOAN_IN.find(t)?.let { m ->
            val (item, person, date) = loanParts(m.groupValues[1], m.groupValues[2])
            return Structured(NoteKind.LOAN, item = clean(item), person = person, eventDate = date)
        }
        REMIND.find(t)?.let { m ->
            val task = m.groupValues[1].trim()
            val buy = BUY.find(task)
            return if (buy != null) {
                Structured(NoteKind.REMINDER, item = clean(buy.groupValues[1]), place = buy.groupValues[2].trim().ifEmpty { null }
                    ?.let { "at $it" })
            } else {
                Structured(NoteKind.REMINDER, item = task)
            }
        }
        PUT.find(t)?.let { m ->
            return Structured(
                NoteKind.STORED_ITEM,
                item = clean(m.groupValues[1]),
                place = m.groupValues[2].lowercase() + " " + m.groupValues[3].trim(),
            )
        }
        IS_IN.find(t)?.let { m ->
            return Structured(
                NoteKind.STORED_ITEM,
                item = clean(m.groupValues[1]),
                place = m.groupValues[2].lowercase() + " " + m.groupValues[3].trim(),
            )
        }
        return Structured(NoteKind.OTHER)
    }

    private fun loanParts(itemRaw: String, rest: String): Triple<String, String, String?> {
        val m = ON_DATE.find(rest)
        val person = (if (m != null) rest.substring(0, m.range.first) else rest).trim()
        val date = m?.groupValues?.get(1)?.trim()
        return Triple(itemRaw, person.replaceFirstChar { it.uppercase() }, date)
    }

    /** Drop leading "my", "the", "a" so "my reading glasses" and "reading glasses" match. */
    private fun clean(s: String): String =
        s.trim().replace(Regex("^(my|the|a|an|our|some)\\s+", RegexOption.IGNORE_CASE), "").trim()

    private companion object {
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
        val REMIND = Regex("^(?:$I\\s+)?(?:need to|have to|must|should|want to|got to)?\\s*(?:remember|remind me) to\\s+(.+)$", RegexOption.IGNORE_CASE)
        val BUY = Regex("^(?:buy|get|pick up|grab)\\s+(.+?)(?:\\s+(?:at|from)\\s+(.+))?$", RegexOption.IGNORE_CASE)
    }
}
