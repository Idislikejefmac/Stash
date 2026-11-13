package app.stash.model

import app.stash.domain.Structured

/** Turns a note's text into fields. May fail (null); callers fall back to the offline rules. */
interface Structurer {
    suspend fun structure(text: String, vocabulary: List<String>): Structured?
}
