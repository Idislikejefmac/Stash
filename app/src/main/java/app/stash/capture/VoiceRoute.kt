package app.stash.capture

import app.stash.speech.Availability
import app.stash.speech.SpeechEngine

/*
 * Card 26g section B: which way a voice note is taken. Pure Kotlin, no Android import.
 *  - RECORD: our own recorder; the recording is saved, then written out by the speech model inside Stash.
 *  - QUICK: the phone's own recogniser, as before card 26; the words are saved and no sound is kept.
 *  - ASK_UNLOCK: the owner chose to be asked to unlock rather than take a locked note without a recording.
 */

/** Whether our recorder gets real sound while the phone is locked (check C2), as known right now. */
enum class LockedMic { OK, BLOCKED }

/** The setting "When the phone is locked". It only matters while the locked microphone is known to be blocked. */
enum class LockedChoice(val label: String) {
    QUICK_NOTE("Take a quick note without a recording"),
    ASK_UNLOCK("Ask me to unlock first");

    companion object {
        fun parse(s: String?): LockedChoice = entries.firstOrNull { it.name == s } ?: QUICK_NOTE
    }
}

/** Why a note is a quick note. [line] is what the capture screen says about it (null: nothing extra). */
enum class QuickReason(val line: String?) {
    /** The owner switched recording off. */
    OFF(null),
    NO_MODEL("No recording: the speech model isn't added yet."),
    LOCKED("Phone is locked: written down as you speak, no recording."),
    /** The recorder was blocked or would not start during this note (item 4, "at run time"). */
    MIC_FAILED("Say that again, please."),
}

sealed interface Route {
    data object Record : Route
    data class Quick(val reason: QuickReason) : Route
    data object AskUnlock : Route
}

object VoiceRoute {
    /**
     * Check C2 passed on the S25 Edge (2026-11-13: locked, MicHealth OK, real speech), so this is route R1: recording
     * is used on the lock screen like anywhere else. A phone that blocks it is found at run time and remembered.
     */
    val LOCKED_MIC_DEFAULT = LockedMic.OK

    const val UNLOCK_TO_RECORD = "Unlock to keep a recording"
    const val ASK_UNLOCK_TITLE = "Unlock to add a voice note with a recording"
    const val ASK_UNLOCK_BUTTON = "Unlock"
    const val ASK_UNLOCK_QUICK = "Quick note instead"

    /** What is known about the locked microphone: the constant, unless this phone has shown it is blocked. */
    fun lockedMic(rememberedBlocked: Boolean, default: LockedMic = LOCKED_MIC_DEFAULT): LockedMic =
        if (rememberedBlocked) LockedMic.BLOCKED else default

    /** For the event log (numbers only): 0 recorded; 1 to 4 a quick note, by its reason; 9 asked to unlock. */
    fun code(route: Route): Int = when (route) {
        Route.Record -> 0
        is Route.Quick -> 1 + route.reason.ordinal
        Route.AskUnlock -> 9
    }

    /** Item 3's table; the first matching row wins. Adding a note is never refused: every row leads to a note. */
    fun choose(
        recordingOn: Boolean,
        engine: Availability,
        locked: Boolean,
        lockedMic: LockedMic,
        lockedChoice: LockedChoice = LockedChoice.QUICK_NOTE,
    ): Route = when {
        !recordingOn -> Route.Quick(QuickReason.OFF)
        engine != Availability.Ready -> Route.Quick(QuickReason.NO_MODEL)
        locked && lockedMic == LockedMic.BLOCKED ->
            if (lockedChoice == LockedChoice.ASK_UNLOCK) Route.AskUnlock else Route.Quick(QuickReason.LOCKED)
        else -> Route.Record
    }
}

/**
 * Starts a note down its route. The quick route is the [SpeechEngine] exactly as before card 26g; the recorder is
 * touched only for [Route.Record]. Kept apart from the screen so a test can see which of the two was started.
 */
class VoiceStarter(
    private val speech: SpeechEngine,
    private val startRecording: () -> Unit,
    private val askUnlock: () -> Unit = {},
) {
    fun start(route: Route, listener: SpeechEngine.Listener, biasWords: List<String>) {
        when (route) {
            is Route.Quick -> speech.start(listener, biasWords)
            Route.Record -> startRecording()
            Route.AskUnlock -> askUnlock()
        }
    }
}
