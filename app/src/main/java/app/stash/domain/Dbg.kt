package app.stash.domain

/*
 * The only place the app logs (docs/cards/13 section D). An event is a component, a code, and up to a few numbers
 * and one row id. There is no parameter that can carry a string of note content, and nothing here uses the system
 * log: events are rows in `app_event`, read back with `tools/peek.py events`.
 */

enum class DbgComponent { CAPTURE, EXTRACT, CHECKS, PROJECT, HOOK, WORKER, SPEECH, OUTBOUND, TASK, UPKEEP, REMINDER, PICTURE, SHARE, CAMERA, OFFER, AUDIO, TRANSCRIBE, READING, ASK }

enum class DbgCode {
    SAVED, UNDONE, STARTED, OK, FAILED, GAVE_UP, SKIPPED, DOWNGRADED, WRITTEN, THREW, RESCHEDULED, STORE_UNAVAILABLE,
    REFUSED, SPEECH_ERROR, REREAD_ASKED,
    /** Card 05: a task closed by speaking, a question asked, a duplicate folded or flagged, a task woken or expired. */
    CLOSED, ASKED, FOLDED, FLAGGED, WOKE, EXPIRED,
    /** Card 16a (component PICTURE): a file stored (n1 bytes), removed, unreadable (n1 reason code), swept (n1 files). */
    STORED, REMOVED, UNREADABLE, SWEPT,
    /** Card 16c (component SHARE): something arrived through Share to Stash (n1 pictures, n2 1 if text), saved (same numbers), cancelled. */
    RECEIVED, CANCELLED,
    /** Card 16e (component CAMERA): the camera screen opened (n1 = 1 if locked); a picture taken (ms from shutter to prepared, n1 bytes kept); a failure is FAILED with n1 = reason code. */
    OPENED, TAKEN,
    /** Card 16f (component PICTURE): words read (n1 lines, n2 characters, ms), read timed out (ms), catch-up ran (n1 pictures). A failure is FAILED with n1 0. */
    READ, TIMED_OUT, CAUGHT_UP,
    /** Card 16g (component OFFER): offered (n1 1 = Saved screen, 2 = note row), accepted (n1 same), dismissed (n1 1 = No thanks, 2 = by age with n2 = how many, 3 = a picture was added). Counts only. */
    OFFERED, ACCEPTED, DISMISSED,
    /** Card 26a (component AUDIO): a recording finished (n1 duration ms, n2 loudest sample, ms wall time); the microphone gave only zeros; a failure is FAILED with n1 = failure code, n2 = detail. */
    RECORDED, MIC_BLOCKED,
    /** Card 26b (component AUDIO): a recording encoded (n1 bytes, n2 AudioFormat ordinal, ms from finish called to done). A fallback is DOWNGRADED (n1 from ordinal, n2 to ordinal); a decode failure is FAILED with n1 = 100 + format ordinal, a compaction failure 200 + format ordinal. */
    ENCODED,
    /** Card 26d (component TRANSCRIBE): STARTED (n1 audio ms); OK (n1 audio ms, n2 chunks, ms engine time); FAILED (n1 TranscribeFailure code, n2 1 if retryable); a model loaded (n1 pack index in KnownModels, n2 0 files / 1 assets, ms load time); released. A pack installed is STORED (n1 bytes, ms), refused REFUSED (n1 ImportFailure code), deleted REMOVED. Never words. */
    LOADED, RELEASED,
    /** Card 26e (component TRANSCRIBE): a long note written out in pieces (n1 pieces, n2 forced cuts, ms note length); outcome M2's long note written out with base because the tiny pack is not installed (n1 note ms). */
    SPLIT, NO_LONG_NOTE_PACK,
    /**
     * Card 26f (component TRANSCRIBE, refId = the recording's attachment id, the same id AUDIO STORED carries): the pass
     * also logs STARTED (n1 audio ms, n2 0 foreground / 1 background), OK (n1 chunks, n2 characters, ms engine time),
     * FAILED (n1 TranscribeFailure code, or 100 + reason for an unreadable or undecodable recording), GAVE_UP (n1
     * attempts) and RESCHEDULED (n1 delay ms; n2 1 when the job found a pass already running and looks again later). KILLED: a run found dead (n1 audio ms, n2 0 foreground / 1 background).
     * MODEL_MISSING: notes are waiting and no speech model is ready (once a pass; n1 notes waiting).
     */
    KILLED, MODEL_MISSING,
    /**
     * Card 08 (component ASK, refId = the answer trace's id): a question answered is ASKED (n1 route: 1 router, 2 loop,
     * 3 search, 0 none; n2 outcome: 1 answered, 2 gave up, 3 no match; ms total). A question that threw is THREW (n1 =
     * the route reached as above). "That's not it" is MARKED_WRONG. A trace's words blanked by a purge is BLANKED (n1 how
     * many traces). Never the question or the answer.
     */
    MARKED_WRONG, BLANKED,
}

/** Where events go. The phone's implementation writes `app_event`; tests use a list. */
fun interface EventSink {
    fun write(component: DbgComponent, code: DbgCode, n1: Long?, n2: Long?, refId: String?, ms: Long?)
}

object Dbg {
    @Volatile
    var sink: EventSink? = null

    /** [n1] and [n2] are counts or sizes, [refId] is a row id, [ms] a duration. Never fails the caller. */
    fun event(
        component: DbgComponent,
        code: DbgCode,
        n1: Number? = null,
        n2: Number? = null,
        refId: String? = null,
        ms: Number? = null,
    ) {
        try {
            sink?.write(component, code, n1?.toLong(), n2?.toLong(), refId, ms?.toLong())
        } catch (e: Exception) {
            // Logging must never break what it is logging.
        }
    }
}
