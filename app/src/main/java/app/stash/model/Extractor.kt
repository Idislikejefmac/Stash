package app.stash.model

import app.stash.domain.ExtractedRecord
import app.stash.domain.SendableText

/** A subject the app already knows, with the metric names recorded for it. Built from non-sensitive notes only. */
data class KnownSubject(val name: String, val metrics: List<String> = emptyList())

/** An open, non-private task and the short label ("T1") the extractor may use in `closes`. */
data class OpenTaskRef(val label: String, val title: String)

/** What an extractor may be told besides the note's words. Never dates, times or places on a map. */
data class ExtractContext(
    val words: List<String> = emptyList(),
    val subjects: List<KnownSubject> = emptyList(),
    val openTasks: List<OpenTaskRef> = emptyList(),
) {
    val taskLabels: Set<String> get() = openTasks.map { it.label }.toSet()
}

sealed interface ExtractResult {
    data class Ok(
        val records: List<ExtractedRecord>,
        val rawJson: String,
        val tokensIn: Int? = null,
        val tokensOut: Int? = null,
    ) : ExtractResult

    data class Failed(val reason: String, val retryable: Boolean) : ExtractResult
}

/**
 * Turns a capture's text into records. Takes [SendableText], which only `Sensitivity.sendable` can make, so a
 * private note cannot reach a hosted implementation. Card 04 adds the hosted one; this card builds only the seam.
 */
interface Extractor {
    val name: String
    suspend fun extract(text: SendableText, context: ExtractContext): ExtractResult
}

/** An extractor that runs on the phone and needs no permission to see private notes (the offline rules). */
interface LocalExtractor {
    val name: String
    fun extract(text: String, context: ExtractContext): ExtractResult
}
