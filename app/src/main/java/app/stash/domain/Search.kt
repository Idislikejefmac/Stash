package app.stash.domain

/** Minimal view of a note for search and answering (keeps this file free of Android and Room). */
data class NoteView(
    val id: String,
    val createdAt: Long,
    val text: String,
    val kind: NoteKind,
    val item: String? = null,
    val place: String? = null,
    val person: String? = null,
    val eventDate: String? = null,
    val supersedesId: String? = null,
)

data class Match(val note: NoteView, val score: Int, val history: List<NoteView>)

object Searcher {
    private val STOP = setOf(
        "where", "what", "when", "who", "which", "how", "did", "do", "does", "i", "me", "my", "mine", "the", "a", "an",
        "is", "are", "was", "were", "put", "putting", "leave", "left", "to", "of", "in", "on", "at", "it", "that",
        "this", "and", "or", "for", "have", "has", "had", "you", "your", "can", "could", "tell", "about", "again",
        "last", "remember", "know", "find", "stored", "store", "keep", "kept", "with", "now", "currently", "whats",
        "s", "ve", "m",
    )

    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")

    fun isStopWord(w: String): Boolean = w in STOP

    fun tokens(s: String): List<String> = s.lowercase().split(SPLIT).filter { it.isNotEmpty() }

    fun stem(w: String) = when {
        w.length > 4 && w.endsWith("sses") -> w.dropLast(2)
        w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
        else -> w
    }

    fun keywords(query: String): List<String> =
        tokens(query).filter { it !in STOP }.map(::stem).distinct()

    private fun score(n: NoteView, kws: List<String>): Int {
        val item = tokens(n.item.orEmpty()).map(::stem).toSet()
        val other = (tokens(n.place.orEmpty()) + tokens(n.person.orEmpty())).map(::stem).toSet()
        val text = tokens(n.text).map(::stem).toSet()
        var s = 0
        for (k in kws) {
            if (k in item) s += 4
            if (k in other) s += 2
            if (k in text) s += 1
        }
        return s
    }

    /**
     * Notes that match [query], best first. A note that a newer note supersedes is not a result of
     * its own; it appears only as history under the note that replaced it. Ties go to the newest.
     */
    fun search(query: String, notes: List<NoteView>, limit: Int = 5): List<Match> {
        val kws = keywords(query)
        if (kws.isEmpty()) return emptyList()
        val byId = notes.associateBy { it.id }
        val superseded = notes.mapNotNull { it.supersedesId }.toSet()
        return notes.filter { it.id !in superseded }
            .map { it to score(it, kws) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<NoteView, Int>> { it.second }.thenByDescending { it.first.createdAt })
            .take(limit)
            .map { (n, s) ->
                val hist = mutableListOf<NoteView>()
                var cur = n.supersedesId?.let { byId[it] }
                while (cur != null && hist.size < 5 && hist.none { it.id == cur!!.id }) {
                    hist += cur
                    cur = cur.supersedesId?.let { byId[it] }
                }
                Match(n, s, hist)
            }
    }
}

/** Plain-code answers, no model needed. Never invents: it only restates the stored fields. */
object AnswerComposer {
    const val NOTHING = "I don't have a note about that."

    fun compose(matches: List<Match>, now: Long): String {
        if (matches.isEmpty()) return NOTHING
        val top = matches.first()
        val sb = StringBuilder(describe(top.note, now))
        top.history.firstOrNull()?.let { sb.append(" Before that: ").append(historyLine(it, now)) }
        // A different item that also matched, mentioned briefly.
        matches.drop(1).firstOrNull { it.note.item != top.note.item }?.let {
            sb.append(" Also: ").append(describe(it.note, now))
        }
        return sb.toString()
    }

    fun describe(n: NoteView, now: Long): String {
        val age = Age.describe(n.createdAt, now)
        val item = n.item?.takeIf { it.isNotBlank() }
        return when {
            n.kind == NoteKind.STORED_ITEM && item != null && n.place != null ->
                "Your $item ${verb(item)} ${n.place}, noted $age."
            n.kind == NoteKind.LOAN && item != null && n.person != null -> {
                val date = n.eventDate?.let { " on $it" }.orEmpty()
                "You loaned your $item to ${n.person}$date, noted $age."
            }
            n.kind == NoteKind.REMINDER ->
                "Reminder: ${(item ?: n.text)}${n.place?.let { " $it" }.orEmpty()}, noted $age."
            else -> "You noted \"${n.text}\", $age."
        }
    }

    private fun historyLine(n: NoteView, now: Long): String {
        val age = Age.describe(n.createdAt, now)
        return when {
            n.kind == NoteKind.STORED_ITEM && n.place != null -> "${n.place}, $age."
            else -> "\"${n.text}\", $age."
        }
    }

    private fun verb(item: String) = if (item.trimEnd().endsWith("s") && !item.endsWith("ss")) "are" else "is"
}

/** Two items are "the same" if their words match after lower-casing and dropping plural s. */
fun itemKey(item: String?): String? =
    item?.let { Searcher.tokens(it).map(Searcher::stem).joinToString(" ") }?.takeIf { it.isNotEmpty() }
