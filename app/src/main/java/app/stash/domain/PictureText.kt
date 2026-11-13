package app.stash.domain

/*
 * Card 16f: the words printed on a picture, as plain rules. Pure Kotlin, no Android imports. The words themselves are
 * never logged (docs/cards/00 rule 11); this file only decides how they are cut, shown and titled.
 */
object PictureTextRules {
    /** The most characters of picture text kept. */
    const val MAX_CHARS = 4_000

    /** The label on the annotate screen's draft line. */
    const val DRAFT_PREFIX = "Read from the picture: "

    /** How many lines the draft shows. */
    const val DRAFT_LINES = 4

    /** How many lines make a picture-only note's title. */
    const val TITLE_LINES = 2

    const val TITLE_MAX_CHARS = 80
    const val TITLE_FALLBACK = "Picture"
    const val SEPARATOR = " · "

    /** How many unread stored pictures one catch-up reads. */
    const val CATCH_UP_BATCH = 20

    const val ENGINE_NAME = "mlkit-text-latin"

    /** Settings, beside the picture-text switch, until card 23 builds the explanation card. Fixed text (D80). */
    const val DISCLOSURE =
        "The phone reads the words itself. The reader is made by Google and sends Google figures about how it is " +
            "used and how it performs. It does not send your pictures or their words."

    const val STORED_SUFFIX = "/stored"

    /** `mlkit-text-latin/16.0.1`. */
    fun engine(libraryVersion: String): String = "$ENGINE_NAME/$libraryVersion"

    /** The catch-up reads the stored copy, which is less sharp than the one read before shrinking, and says so. */
    fun asStored(engine: String): String = if (engine.endsWith(STORED_SUFFIX)) engine else engine + STORED_SUFFIX

    /** The non-blank lines, trimmed, in order. */
    fun lines(text: String?): List<String> =
        text.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** The text as the reader hands it back: cut at [MAX_CHARS]. */
    fun cut(text: String): String = if (text.length <= MAX_CHARS) text else text.take(MAX_CHARS)

    /** The median of the numbers; 0 for none. */
    fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2f
    }

    /** Number of lines and their median height in pixels, for the small-print rule (card 16b). */
    fun stats(lineHeightsPx: List<Float>): TextStats = TextStats(lineHeightsPx.size, median(lineHeightsPx))

    /** "Read from the picture: A · B · C · D": the first four non-empty lines, or null when there are none. Display only. */
    fun draftLine(pictureText: String?): String? {
        val first = lines(pictureText).take(DRAFT_LINES)
        if (first.isEmpty()) return null
        return DRAFT_PREFIX + first.joinToString(SEPARATOR)
    }

    /** The title of a picture-only note: the first two non-empty lines, cut at 80 characters, or "Picture". */
    fun title(pictureText: String?): String {
        val joined = lines(pictureText).take(TITLE_LINES).joinToString(SEPARATOR).take(TITLE_MAX_CHARS).trim()
        return joined.ifEmpty { TITLE_FALLBACK }
    }
}

/**
 * Records made from a picture's words are picture text, not his words. This is the one place that says which ones, so
 * cards 16h and 16j can keep such a title away from a hosted model unless the owner turned on that separate switch.
 */
object RecordsFromPictures {
    /** The name of the small local extractor that files a note that is only a picture. */
    const val EXTRACTOR = "picture-1"

    fun isPictureText(extractorName: String): Boolean = extractorName == EXTRACTOR
}
