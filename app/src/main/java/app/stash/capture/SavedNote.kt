package app.stash.capture

import app.stash.domain.PictureOffer

/**
 * The note just saved, while the capture screen is ready for the next one. Pure state so it can be tested:
 * the screen passes in the clock. [words] is null for a Private note and is never kept once a note is Private,
 * so nothing here can leak it into the drifting "Saved" message.
 */
data class SavedNote(
    val id: String,
    val words: String?,
    val isPrivate: Boolean,
    /** Undo and Private on this note are offered until this time. */
    val undoUntil: Long,
    /** The screen closes itself when this passes with nobody touching anything. */
    val idleAt: Long,
    /** Card 16g: the "Add a picture" button has appeared on this note (the windows were extended then, once). */
    val offered: Boolean = false,
    /** Card 26g: a recorded note whose words have not arrived yet. The screen watches the capture while this is true. */
    val awaitingWords: Boolean = false,
) {
    fun undoOpen(now: Long) = now < undoUntil
    fun idleExpired(now: Long) = now >= idleAt

    /** Any touch on the ready screen: the idle countdown starts again (the Undo window does not). */
    fun touched(now: Long, idleMs: Long = IDLE_MS) = copy(idleAt = maxOf(idleAt, now + idleMs))

    /** Private pressed: the words are dropped for good, and the window stays open at least [extraMs] longer. */
    fun markedPrivate(now: Long, extraMs: Long = PRIVATE_EXTRA_MS) =
        copy(words = null, isPrivate = true, undoUntil = maxOf(undoUntil, now + extraMs), idleAt = maxOf(idleAt, now + extraMs))

    /**
     * Card 16g: the picture offer appeared at [now]. Undo and the idle close both run until [extraMs] from now, never
     * shorter than they were. Only the first appearance extends; with no offer nothing changes.
     */
    fun offerAppeared(now: Long, extraMs: Long = PictureOffer.SAVED_EXTENSION_MS): SavedNote =
        if (offered) this else copy(offered = true, undoUntil = maxOf(undoUntil, now + extraMs), idleAt = maxOf(idleAt, now + extraMs))

    /** Card 26g: whether the screen should still look for this note's words. Never for a Private note. */
    val watching: Boolean get() = awaitingWords && !isPrivate

    /**
     * Card 26g item 11: what the watch found ([WordsLater.of]). Words are taken only by a note that is not Private; a
     * note that turned out Private (it began "private note ...") drops everything, as [markedPrivate] does, without
     * touching the windows. Nothing here extends the idle close: the screen does not wait for the words.
     */
    fun arrived(update: WordsLater.Update): SavedNote = when (update) {
        WordsLater.Update.Waiting -> this
        is WordsLater.Update.Words -> if (isPrivate) copy(awaitingWords = false) else copy(words = update.text, awaitingWords = false)
        WordsLater.Update.Private -> copy(words = null, isPrivate = true, awaitingWords = false)
        WordsLater.Update.NoWords, WordsLater.Update.GaveUp, WordsLater.Update.Gone -> copy(awaitingWords = false)
    }

    /** What the grey message says. Private notes get only "Saved". */
    fun message(maxChars: Int = MESSAGE_CHARS): String {
        val w = words?.trim().orEmpty()
        if (isPrivate || w.isEmpty()) return "Saved"
        return "Saved: " + if (w.length <= maxChars) w else w.take(maxChars).trimEnd() + "..."
    }

    companion object {
        const val UNDO_MS = 5_000L
        const val PRIVATE_EXTRA_MS = 3_000L
        const val IDLE_MS = 10_000L
        const val MESSAGE_CHARS = 60

        fun of(id: String, words: String, isPrivate: Boolean, now: Long) =
            SavedNote(id, if (isPrivate) null else words, isPrivate, now + UNDO_MS, now + IDLE_MS)

        /** Card 26g: a note saved as a recording. It has no words yet, so the message is "Saved" until they arrive. */
        fun recorded(id: String, isPrivate: Boolean, now: Long) =
            SavedNote(id, null, isPrivate, now + UNDO_MS, now + IDLE_MS, awaitingWords = true)
    }
}

/**
 * Card 26g item 11: the words, afterwards. While the capture screen is ready, it looks at the capture it just saved
 * and asks this what to show. Pure. A Private capture never yields its words here, whatever else is true of it.
 */
object WordsLater {
    /**
     * Card 26g's line ends "; you can listen to it in Stash." Listening is card 26h and is not built yet, so until
     * then the line says only what is true. Card 26h should put the card's wording back.
     */
    const val GAVE_UP_LINE = "Saved. I couldn't write this one out. Its recording is kept."

    /** Shown once, after the first recorded note (item 14), until card 23's explanation card replaces it. */
    const val FIRST_TIME_LINE = "Stash now keeps a recording of each voice note. You can turn that off in Settings."

    sealed interface Update {
        data object Waiting : Update
        data class Words(val text: String) : Update
        /** Written out, and nothing was said. */
        data object NoWords : Update
        data object GaveUp : Update
        /** The note is Private (the button, or its own words): nothing of it is drawn. */
        data object Private : Update
        /** Undone or deleted meanwhile. */
        data object Gone : Update
    }

    fun of(found: Boolean, sensitive: Boolean, state: app.stash.data.TranscribeState?, text: String): Update = when {
        !found -> Update.Gone
        sensitive -> Update.Private
        text.isNotBlank() -> Update.Words(text)
        state == app.stash.data.TranscribeState.GAVE_UP -> Update.GaveUp
        state == app.stash.data.TranscribeState.DONE -> Update.NoWords
        else -> Update.Waiting
    }
}
