package app.stash.domain

/** What kind of thing a note is. Stored as its name in the database. */
enum class NoteKind(val label: String) {
    STORED_ITEM("Stored item"),
    LOAN("Loan"),
    REMINDER("Reminder"),
    OTHER("Other");

    companion object {
        fun parse(s: String?): NoteKind? =
            entries.firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) }
    }
}

/** The fields a structurer pulls out of a note's text. All optional except kind. */
data class Structured(
    val kind: NoteKind,
    val item: String? = null,
    /** Where, with its preposition, e.g. "in the nightstand drawer". */
    val place: String? = null,
    val person: String? = null,
    /** Free-text date mentioned in the note ("Tuesday", "March 3"), if any. */
    val eventDate: String? = null,
)

/** Plain "how long ago" wording used in answers. */
object Age {
    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR

    fun describe(thenMs: Long, nowMs: Long): String {
        val d = (nowMs - thenMs).coerceAtLeast(0)
        return when {
            d < MIN -> "just now"
            d < HOUR -> plural(d / MIN, "minute")
            d < DAY -> plural(d / HOUR, "hour")
            d < 2 * DAY -> "yesterday"
            d < 14 * DAY -> plural(d / DAY, "day")
            d < 60 * DAY -> plural(d / (7 * DAY), "week")
            d < 730 * DAY -> plural(d / (30 * DAY), "month")
            else -> plural(d / (365 * DAY), "year")
        }
    }

    private fun plural(n: Long, unit: String) = "$n $unit${if (n == 1L) "" else "s"} ago"
}

/** What a record is (docs/cards/00-READ-FIRST.md). Stored as its name in the database. */
enum class RecordKind { PLACED, LOAN, TASK, READING, EVENT, REFERENCE, IDEA, OTHER;

    /** The prototype's kind for this record (the prototype's four kinds are all it knows). */
    fun toNoteKind(): NoteKind = when (this) {
        PLACED -> NoteKind.STORED_ITEM
        LOAN -> NoteKind.LOAN
        TASK -> NoteKind.REMINDER
        else -> NoteKind.OTHER
    }

    companion object {
        fun parse(s: String?): RecordKind? = entries.firstOrNull { it.name == s }
    }
}

fun NoteKind.toRecordKind(): RecordKind = when (this) {
    NoteKind.STORED_ITEM -> RecordKind.PLACED
    NoteKind.LOAN -> RecordKind.LOAN
    NoteKind.REMINDER -> RecordKind.TASK
    NoteKind.OTHER -> RecordKind.OTHER
}

/** Comparing titles: the same words, whatever the case, spacing or punctuation. */
object Titles {
    fun norm(title: String): String =
        title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}
