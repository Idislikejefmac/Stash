package app.stash.pipeline

import app.stash.data.RetryPolicy
import app.stash.data.TranscribeState
import app.stash.data.TranscriptionStatus
import app.stash.speech.RunPlace

/*
 * Card 26f: the rules of the transcription pass as plain Kotlin, so they are tested on the JVM with an injected clock.
 * No Android import. The pass (TranscriptionPipeline) and the starts (TranscriptionScheduler) only apply these.
 */

/** Where a pass runs: in the app's process while a screen of it is up, or in the WorkManager job. */
enum class PassWhere(val runPlace: RunPlace, /** n2 of STARTED and KILLED events. */ val eventCode: Int) {
    FOREGROUND(RunPlace.ON_SCREEN, 0),
    BACKGROUND(RunPlace.BACKGROUND, 1),
}

/** States, back-off and attempt limits (card 26f items 2 and 3). */
object TranscriptionPolicy {
    /** The back-off already used for extraction (card 13): 1 min, 10 min, 1 h, 6 h, 24 h. */
    val BACK_OFF_MS: List<Long> = RetryPolicy.BACK_OFF_MS

    /**
     * The fifth counted attempt that fails gives up (the card's check: "Five retryable failures: GAVE_UP"). A run
     * found killed counts as an attempt. With five, the back-offs used are 1 min, 10 min, 1 h and 6 h.
     */
    const val MAX_ATTEMPTS = 5

    /** A note killed this many times in background runs is left for a foreground pass. */
    const val BACKGROUND_KILLS_BEFORE_FOREGROUND_ONLY = 2

    /** A note killed this many times in foreground runs gives up: "too long for this phone". */
    const val FOREGROUND_KILLS_BEFORE_GIVING_UP = 2

    /** Reason codes written into `transcription.error` (never words). */
    const val KILLED_BG = "KILLED_BG"
    const val KILLED_FG = "KILLED_FG"
    const val NO_RECORDING = "NO_RECORDING"
    const val UNREADABLE = "UNREADABLE"
    const val UNDECODABLE = "UNDECODABLE"

    /** Event n1 for a recording that cannot be read (100 + the store's reason) or decoded (199). */
    const val EVENT_UNREADABLE_BASE = 100
    const val EVENT_UNDECODABLE = 199
    const val EVENT_NO_RECORDING = 198

    fun killCode(where: PassWhere) = if (where == PassWhere.FOREGROUND) KILLED_FG else KILLED_BG

    fun backoffMs(attempts: Int): Long = BACK_OFF_MS[(attempts - 1).coerceIn(0, BACK_OFF_MS.size - 1)]

    fun giveUp(attempts: Int): Boolean = attempts >= MAX_ATTEMPTS

    /**
     * How long until a waiting note may be tried: 0 now. PENDING is always due; RETRY waits the back-off from the
     * note's last change. Anything else is never due (null).
     */
    fun waitMs(state: TranscribeState?, attempts: Int, updatedAt: Long, now: Long): Long? = when (state) {
        TranscribeState.PENDING -> 0
        TranscribeState.RETRY -> if (attempts <= 0) 0 else (updatedAt + backoffMs(attempts) - now).coerceAtLeast(0)
        else -> null
    }

    /** One row of a note's writing-outs, newest first: only what the rules need. */
    data class Row(val status: TranscriptionStatus, val error: String?)

    /**
     * The failures that still count for this note. For a note waiting for its first writing-out: the newest
     * `attempts` FAILED rows (every counted attempt writes exactly one, and "Try again" resets attempts to 0, so it
     * starts from zero). For an owner's "again" request: the FAILED rows newer than the newest row with words.
     */
    fun countingFailures(rowsNewestFirst: List<Row>, attempts: Int, firstWritingOut: Boolean): List<String?> {
        val failed = if (firstWritingOut) rowsNewestFirst.filter { it.status == TranscriptionStatus.FAILED }.take(attempts.coerceAtLeast(0))
        else rowsNewestFirst.takeWhile { it.status == TranscriptionStatus.FAILED }
        return failed.map { it.error }
    }

    fun kills(rows: List<Row>, attempts: Int, firstWritingOut: Boolean, code: String): Int =
        countingFailures(rows, attempts, firstWritingOut).count { it == code }

    /** A background pass leaves this note alone: it died twice in the background, so it waits for a foreground pass. */
    fun skipInBackground(rows: List<Row>, attempts: Int, firstWritingOut: Boolean): Boolean =
        kills(rows, attempts, firstWritingOut, KILLED_BG) >= BACKGROUND_KILLS_BEFORE_FOREGROUND_ONLY

    /** Failures retrying cannot help. */
    val PERMANENT_CODES = setOf(NO_RECORDING, UNREADABLE, UNDECODABLE)

    /**
     * An owner's "again" request has no state of its own to give up, so it is simply not tried again once, since the
     * last words: it died twice in the foreground, or met a failure retrying cannot help, or failed [MAX_ATTEMPTS]
     * times. Its words and recording stay; "write it out again" asks afresh only after a new writing-out has words.
     */
    fun abandonAgainRequest(rows: List<Row>): Boolean {
        val f = countingFailures(rows, 0, firstWritingOut = false)
        return f.count { it == KILLED_FG } >= FOREGROUND_KILLS_BEFORE_GIVING_UP || f.any { it in PERMANENT_CODES } || f.size >= MAX_ATTEMPTS
    }
}

/**
 * Written to a small preferences file just before the engine starts on a note, and cleared just after. A marker that
 * a pass finds and did not write means the process died in the middle of that note (card 26f item 3).
 */
data class RunMarker(val captureId: String, val startedAt: Long, val where: PassWhere, val processToken: String)

interface RunMarkerStore {
    fun read(): RunMarker?
    /** Must be on disk when it returns: the process may die a moment later. */
    fun write(marker: RunMarker)
    fun clear()
}

class MemoryRunMarkerStore : RunMarkerStore {
    @Volatile var marker: RunMarker? = null
    override fun read() = marker
    override fun write(marker: RunMarker) { this.marker = marker }
    override fun clear() { marker = null }
}

object KillCheck {
    sealed interface Verdict {
        data object None : Verdict
        /** Left by this process (a pass that ended by an exception): not a kill; just clear it. */
        data object OwnLeftover : Verdict
        data class Killed(val captureId: String, val where: PassWhere) : Verdict
    }

    fun judge(marker: RunMarker?, myToken: String): Verdict = when {
        marker == null -> Verdict.None
        marker.processToken == myToken -> Verdict.OwnLeftover
        else -> Verdict.Killed(marker.captureId, marker.where)
    }
}

/**
 * Check C1 (card 26 index): does a WorkManager job write out a recording with the screen off? The answer is
 * **one constant**, [TranscriptionWorkShape.OUTCOME]; the two job settings follow from it.
 */
enum class C1Outcome(val expedited: Boolean, val needsCharging: Boolean, /** Settings' sentence (drawn by 26j), or null. */ val settingsLine: String?) {
    /** The job starts within about a minute and finishes. */
    J1(expedited = true, needsCharging = false, settingsLine = null),
    /** The job finishes, but only after minutes or hours: the foreground pass does most of the work. */
    J2(expedited = true, needsCharging = false, settingsLine = null),
    /** The job is killed, or never runs with the screen off: it runs only while charging. */
    J3(expedited = false, needsCharging = true, settingsLine = "Recordings are written out when Stash is open or the phone is charging."),
}

object TranscriptionWorkShape {
    /** Set from check C1 on the owner's phone. J1 until that check is run (card 26f item 9). */
    val OUTCOME: C1Outcome = C1Outcome.J1

    val WORK_EXPEDITED: Boolean get() = OUTCOME.expedited
    val WORK_NEEDS_CHARGING: Boolean get() = OUTCOME.needsCharging

    /** Expedited jobs accept no charging constraint, so a charging job is never expedited. */
    fun expedite(outcome: C1Outcome = OUTCOME): Boolean = outcome.expedited && !outcome.needsCharging

    const val NOW = "stash-transcribe"
    const val LATER = "stash-transcribe-later"

    /** When the job finds a pass already running in this process, it looks again this much later, in case that pass dies. */
    const val ALREADY_RUNNING_RECHECK_MS = 2 * 60_000L
}

/** Which ways a request starts the pass (card 26f item 6): the job always; the foreground pass only while a screen is up. */
object StartRule {
    data class Starts(val foreground: Boolean, val job: Boolean)

    fun decide(activitiesStarted: Int): Starts = Starts(foreground = activitiesStarted > 0, job = true)
}

/** Counts the app's started activities (StashApp's lifecycle callbacks feed it). Thread-safe. */
class ForegroundTracker {
    private var started = 0

    /** Returns true when this start brought the app on screen (none were started before). */
    @Synchronized fun started(): Boolean = (started++ == 0)

    @Synchronized fun stopped() { if (started > 0) started-- }

    @Synchronized fun count(): Int = started
}

/**
 * The one line a note shows while it waits for its words (card 26f item 9 and Notes and risks): plain words, no
 * notification. Null when there is nothing to say (no recording, or written out).
 */
object TranscribeNotice {
    const val WAITING = "Saved with its recording. Not written out yet."
    const val NEEDS_MODEL = "Saved with its recording. Not written out yet: needs a speech model (Settings, Voice notes)."
    const val GAVE_UP = "Saved with its recording. It could not be written out."
    const val NO_WORDS = "Saved with its recording. No words were heard."

    fun line(state: TranscribeState?, hasWords: Boolean, modelReady: Boolean): String? = when (state) {
        TranscribeState.PENDING, TranscribeState.RETRY -> if (modelReady) WAITING else NEEDS_MODEL
        TranscribeState.GAVE_UP -> if (hasWords) null else GAVE_UP
        TranscribeState.DONE -> if (hasWords) null else NO_WORDS
        null -> null
    }

    // Card 26g item 13: the Notes list row of a recorded note that has no words, so no note is ever a blank row.
    const val ROW_WRITING = "Writing it out…"
    const val ROW_WAITING = "Not written out yet"
    const val ROW_NEEDS_MODEL = "Not written out yet: needs the speech model (Settings, Voice notes)"
    const val ROW_GAVE_UP = "I couldn't write this one out"
    const val ROW_NO_WORDS = "No words heard"

    /** A length as the capture screen's clock and the Notes list show it: 0:07, 0:12, 3:00. */
    fun clock(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    /**
     * "Voice note, 0:12 · Writing it out…" and its three siblings. Null when the note has words or no recording.
     * [durationMs] null: the length is not known yet, and is left out.
     */
    fun voiceNoteRow(state: TranscribeState?, hasWords: Boolean, modelReady: Boolean, durationMs: Long?): String? {
        if (hasWords) return null
        val what = when (state) {
            TranscribeState.PENDING -> if (modelReady) ROW_WRITING else ROW_NEEDS_MODEL
            TranscribeState.RETRY -> if (modelReady) ROW_WAITING else ROW_NEEDS_MODEL
            TranscribeState.GAVE_UP -> ROW_GAVE_UP
            TranscribeState.DONE -> ROW_NO_WORDS
            null -> return null
        }
        return "Voice note" + (if (durationMs != null) ", " + clock(durationMs) else "") + " · " + what
    }
}

/** What Settings shows about recordings (card 26f item 12; drawn by 26j). Numbers only. */
data class TranscriptionStatusSummary(val waiting: Int, val gaveUp: Int, val lastDelayMs: Long?)
