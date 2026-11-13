package app.stash.domain

import app.stash.data.PhotoHint

/**
 * The quiet "Add a picture?" offer (docs/cards/16g). Pure rules. The offer is a button and nothing else: never a
 * notification, never accepted by voice, never an attention item (D73). It is made once, and it ends by itself.
 */
object PictureOffer {
    /** The offer is gone after this long, with or without a tap. */
    const val EXPIRY_MS = 24L * 3600_000L

    /** On the Saved screen, Undo and the idle close run this long from the moment the button appears. */
    const val SAVED_EXTENSION_MS = 8_000L

    /** How often the Saved screen looks at the note's hint while its Undo window is open. */
    const val POLL_MS = 500L

    const val ADD_LABEL = "Add a picture"
    const val NO_THANKS_LABEL = "No thanks"

    /**
     * True only when the hint is PENDING, the note has no picture, it is not Private (Private notes are left out of
     * suggestions; he can still add a picture with the ordinary button) and it is under 24 hours old.
     */
    fun show(photoHint: PhotoHint, pictureCount: Int, sensitive: Boolean, ageMs: Long): Boolean =
        photoHint == PhotoHint.PENDING && pictureCount == 0 && !sensitive && ageMs < EXPIRY_MS

    /** Captures created before this time and still PENDING are closed by the daily upkeep. */
    fun staleBefore(now: Long): Long = now - EXPIRY_MS
}
