package app.stash.domain

import org.json.JSONArray

/**
 * What goes into the full-text index for one record or one capture (docs/cards/01 scope item 2). Pure: the
 * repositories gather the pieces, this decides the text.
 */
object SearchDocs {
    /** Words of a JSON list stored as text, e.g. `["specs","reading glasses"]`. Bad input gives an empty list. */
    fun jsonList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Title, quote, aliases, tags, subject name, metric labels. */
    fun recordBody(
        title: String,
        quote: String,
        aliasesJson: String,
        tagsJson: String,
        subjectName: String?,
        metricLabels: List<String>,
    ): String = (listOf(title, quote) + jsonList(aliasesJson) + jsonList(tagsJson) + listOfNotNull(subjectName) + metricLabels)
        .filter { it.isNotBlank() }
        .joinToString(" ")

    /** A capture's document is his words, then each picture's words (card 16f). */
    fun captureBody(text: String, pictureTexts: List<String> = emptyList()): String =
        (listOf(text) + pictureTexts).filter { it.isNotBlank() }.joinToString("\n")

    /**
     * An FTS4 query from what a person typed: each word of letters or digits, as a prefix match, all required.
     * Null when there is nothing to search for (never pass user text to MATCH as it is).
     */
    fun matchQuery(query: String): String? {
        val words = Searcher.tokens(query)
        if (words.isEmpty()) return null
        return words.joinToString(" ") { "$it*" }
    }
}
