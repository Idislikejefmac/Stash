package app.stash.domain

/**
 * The words and the small decisions of the "Anything to add about this?" screen (docs/cards/16c). Pure Kotlin:
 * no Android imports, so every line of it is pinned by a JVM test.
 */
object ShareRules {
    /**
     * Owner decision D80: listening starts by itself on this screen, as soon as the first picture is ready. He will
     * review it after about two weeks of use; turning it off is this one value.
     */
    const val AUTO_LISTEN = true

    /** The same silence handling as the capture screen: quietly keep listening this long for someone who has not spoken. */
    const val SILENCE_LIMIT_MS = 25_000L

    /** The most one dictation can take. */
    const val HARD_LIMIT_MS = 60_000L

    /** The beep must finish before the microphone opens, so it is not recorded. */
    const val CUE_GAP_MS = 300L

    /** "Saved" stays this long before the screen closes. */
    const val SAVED_SHOWN_MS = 700L

    const val TITLE = "Anything to add about this?"
    const val PICTURES_OFF = "Pictures are switched off in Stash. Turn them on in Settings."
    const val SAVE_FAILED = "I couldn't save that. Try again."
    const val GALLERY_NOTE = "This picture is still in your gallery too. Stash keeps its own copy."
    const val NOTHING_HEARD = "I didn't catch that. Tap the microphone to try again."
    const val VOICE_OFF = "Voice is off. Type your note instead."
    const val NOTHING_TO_SAVE = "There is nothing to save."

    /** While pictures are being made ready: [current] is the one being worked on now (1 for the first). */
    fun gettingReady(current: Int, total: Int): String = "Getting $current of $total ready"

    /** The line under the strip, or null when every picture was added. */
    fun couldNotAdd(failed: Int): String? = when {
        failed <= 0 -> null
        failed == 1 -> "1 picture could not be added"
        else -> "$failed pictures could not be added"
    }

    /**
     * Save is allowed as soon as there is a picture (ready, or still being made ready: the screen then says "Saving..."
     * and saves when it is done) or some words. With neither it is disabled.
     */
    fun canSave(ready: Int, pending: Int, text: String): Boolean = ready > 0 || pending > 0 || text.isNotBlank()

    /** What can really be saved once nothing is pending: a picture that got made, or words. */
    fun hasContent(ready: Int, text: String): Boolean = ready > 0 || text.isNotBlank()

    /** Words heard are added after what was already in the field, with one space between. */
    fun join(base: String, heard: String): String {
        val h = heard.trim()
        if (h.isEmpty()) return base
        if (base.isBlank()) return h
        return if (base.last().isWhitespace()) base + h else "$base $h"
    }
}
