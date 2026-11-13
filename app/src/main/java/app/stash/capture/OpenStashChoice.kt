package app.stash.capture

/** What the "Open Stash" button on the capture screen says and does, from the lock state at that moment. */
object OpenStashChoice {
    const val LABEL_UNLOCKED = "Open Stash"
    const val LABEL_LOCKED = "Unlock to open Stash"

    fun label(keyguardLocked: Boolean) = if (keyguardLocked) LABEL_LOCKED else LABEL_UNLOCKED

    /** Offered in every capture state except while a note is being saved (listening is fine: heard words are saved first). */
    fun available(listening: Boolean, saving: Boolean) = !saving

    /** What "Open Stash" does while listening: words heard are saved first; nothing heard just cancels. */
    enum class WhileListening { SAVE_THEN_OPEN, CANCEL_THEN_OPEN }

    fun whileListening(heard: String) =
        if (heard.isBlank()) WhileListening.CANCEL_THEN_OPEN else WhileListening.SAVE_THEN_OPEN
}

/** What "Done" does while listening. */
enum class DoneChoice {
    /** Something was heard: stop the engine, which delivers the words to save. */
    SAVE,

    /** Nothing heard: "I am done". Cancel, show "No note made", close. */
    LEAVE_EMPTY;

    companion object {
        const val EMPTY_MESSAGE = "No note made"
        const val EMPTY_FLOAT_MS = 1_500
        fun of(heard: String) = if (heard.isBlank()) LEAVE_EMPTY else SAVE
    }
}
