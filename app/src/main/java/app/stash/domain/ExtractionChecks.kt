package app.stash.domain

import app.stash.data.Confidence
import app.stash.data.Direction

/** Attention codes the checks set (stored in `record.attention`). */
object Attention {
    const val BAD_QUOTE = "BAD_QUOTE"
    const val MISSING_FIELD = "MISSING_FIELD"
    const val NUMBER_NOT_IN_TEXT = "NUMBER_NOT_IN_TEXT"
    const val UNKNOWN_TASK_LABEL = "UNKNOWN_TASK_LABEL"
    const val TOO_MANY = "TOO_MANY"
    const val PARTLY_FILED = "PARTLY_FILED"
    const val SAME_AS_PREFIX = "SAME_AS:"
    const val SUGGEST_PRIVATE_PREFIX = "SUGGEST_PRIVATE:"

    /** Card 05: an event that may have finished the task whose record id follows. */
    const val DID_THIS_FINISH_PREFIX = "DID_THIS_FINISH:"

    /** Card 05: a task that said what an open one already says; it was folded into the one whose record id follows. */
    const val DUPLICATE_OF_PREFIX = "DUPLICATE_OF:"

    /** Card 05: a task much like an open one (same place, most words shared). Left alone. */
    const val SAME_AS_TASK_PREFIX = "SAME_AS_TASK:"

    /** Card 07: a reading far from the usual for its metric; the usual (the median) follows as `ODD_VALUE:<metric>:<median>`. */
    const val ODD_VALUE_PREFIX = "ODD_VALUE:"

    /** Card 07: a reading whose unit differs from the unit first heard for the metric. */
    const val UNIT_CHANGED = "UNIT_CHANGED"
}

/**
 * Checks on what an extractor returned (docs/cards/03 section C). The model proposes, code decides: a record that
 * fails a check is kept as a `reference` with a reason, never dropped. Pure.
 */
object ExtractionChecks {
    const val MAX_RECORDS = 8

    /** [taskLabels] are the `T1`... labels that were in the extractor's context. */
    fun check(records: List<ExtractedRecord>, text: String, taskLabels: Set<String> = emptySet()): List<CheckedRecord> {
        val whole = text.trim()
        if (records.isEmpty()) return listOf(CheckedRecord(wholeText(RecordKind.OTHER, whole), null))
        if (records.size > MAX_RECORDS) {
            return listOf(CheckedRecord(wholeText(RecordKind.REFERENCE, whole), Attention.TOO_MANY))
        }
        val checked = records.map { checkOne(it, whole, taskLabels) }.toMutableList()
        if (partlyFiled(checked.map { it.record.quote }, whole) && checked.first().attention == null) {
            checked[0] = checked[0].copy(attention = Attention.PARTLY_FILED)
        }
        return checked
    }

    /** One record covering the whole text, for the cases where nothing finer can be trusted. */
    fun wholeText(kind: RecordKind, text: String) =
        ExtractedRecord(kind = kind, title = text.take(80), quote = text, sure = Confidence.LOW)

    private fun checkOne(r: ExtractedRecord, text: String, labels: Set<String>): CheckedRecord {
        if (!quoteInText(r.quote, text)) {
            // The words he said are the only trustworthy quote: use the whole text.
            return CheckedRecord(downgrade(r).copy(quote = text, title = r.title.ifBlank { text.take(80) }), Attention.BAD_QUOTE)
        }
        val missing = when (r.kind) {
            RecordKind.PLACED -> r.item.isNullOrBlank() || r.where.isNullOrBlank()
            RecordKind.LOAN -> r.item.isNullOrBlank() || (r.person.isNullOrBlank() && r.direction != Direction.RETURNED)
            RecordKind.TASK -> r.title.isBlank()
            else -> false
        }
        if (missing) return CheckedRecord(downgrade(r).copy(title = r.title.ifBlank { r.quote.take(80) }), Attention.MISSING_FIELD)
        if (r.kind == RecordKind.READING) {
            if (r.readings.isEmpty()) return CheckedRecord(downgrade(r), Attention.MISSING_FIELD)
            val bad = r.readings.any { it.number != null && !NumberWords.matches(it.number, r.quote) }
            if (bad) return CheckedRecord(downgrade(r), Attention.NUMBER_NOT_IN_TEXT)
        }
        if (r.closes != null && r.closes !in labels) return CheckedRecord(downgrade(r), Attention.UNKNOWN_TASK_LABEL)
        return CheckedRecord(r, null)
    }

    /** The same title and quote as a plain reference: nothing is lost and nothing is trusted. */
    private fun downgrade(r: ExtractedRecord) = ExtractedRecord(
        kind = RecordKind.REFERENCE, title = r.title, quote = r.quote, sure = Confidence.LOW,
    )

    private fun normalise(s: String): String =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** The quote occurs in the text, ignoring case, punctuation and runs of spaces. An empty quote does not. */
    fun quoteInText(quote: String, text: String): Boolean {
        val q = normalise(quote)
        if (q.isEmpty()) return false
        return " ${normalise(text)} ".contains(" $q ")
    }

    private fun contentWords(s: String): Set<String> =
        Searcher.tokens(s).filter { !Searcher.isStopWord(it) }.map(Searcher::stem).toSet()

    /** The quotes together hold fewer than half of the text's content words. */
    fun partlyFiled(quotes: List<String>, text: String): Boolean {
        val all = contentWords(text)
        if (all.isEmpty()) return false
        val covered = quotes.flatMap { contentWords(it) }.toSet()
        return all.count { it in covered } * 2 < all.size
    }
}
