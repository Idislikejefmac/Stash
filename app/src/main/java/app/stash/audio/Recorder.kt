package app.stash.audio

import app.stash.domain.AudioFormatConstants
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.EndEvent
import app.stash.domain.EndPause
import app.stash.domain.Endpointer
import app.stash.domain.MicHealth
import app.stash.domain.Pcm
import app.stash.domain.RecordingVerdict
import app.stash.domain.NoteVerdict

/** Why the microphone could not be used. [code] goes into the FAILED event as n1; [detail] (an AudioRecord result or state) as n2. */
class MicFailure(val kind: Kind, val detail: Int = 0) {
    enum class Kind(val code: Int) { NO_PERMISSION(1), NOT_INITIALIZED(2), START_REFUSED(3), THREW(4), READ_ERROR(5) }
    val code: Int get() = kind.code
    override fun toString() = "MicFailure(${kind.name}, $detail)"
}

/** The microphone behind a seam, so tests use a fake and no Android class is needed. */
interface Recorder {
    /**
     * Starts reading 20 ms chunks on the recorder's own thread; [onChunk] runs on that thread and must be quick.
     * Returns null on success or the failure; never throws. A failure after start goes to [onError].
     */
    fun start(onChunk: (ShortArray, Int) -> Unit, onError: (MicFailure) -> Unit = {}): MicFailure?

    /** Stops, releases the microphone and every buffer, and returns all sound from start to stop (nothing trimmed). */
    fun stop(): Pcm

    /** Stops and releases the microphone and every buffer; returns nothing. */
    fun cancel()
}

/** What a finished session knows. */
class RecordingResult(
    val pcm: Pcm,
    val loudest: Int,
    val heardSpeech: Boolean,
    val health: MicHealth.Status,
    val endReason: EndEvent?,
    val verdict: NoteVerdict,
    val hitCeiling: Boolean,
)

/**
 * One note's worth of listening: pours the recorder's chunks into the level rule and the blocked-microphone check,
 * keeps the loudest sample, and writes the events (numbers only). Pure Kotlin; the recorder and the clock are injected.
 * The callbacks run on the recorder thread: a screen must hand [finish] to its own thread, not call it there.
 */
class RecordingSession(
    private val recorder: Recorder,
    pause: EndPause,
    private val clock: () -> Long,
    private val onLevel: (Double) -> Unit = {},
    private val onEnd: (EndEvent) -> Unit = {},
    private val onHealth: (MicHealth.Status) -> Unit = {},
    private val onFailure: (MicFailure) -> Unit = {},
) {
    private val endpointer = Endpointer(pause)
    private val health = MicHealth()
    @Volatile private var loudest = 0
    private var lastHealth = MicHealth.Status.UNKNOWN
    private var startedAt = 0L

    /** For a screen's clock and hint (card 26g): read after a chunk, from the recorder thread's callbacks. */
    val heardSpeech: Boolean get() = endpointer.heardSpeech
    val elapsedMs: Long get() = endpointer.elapsedMs

    /** Returns null when listening has begun. */
    fun start(): MicFailure? {
        startedAt = clock()
        val failure = recorder.start(::onChunk) { f ->
            Dbg.event(DbgComponent.AUDIO, DbgCode.FAILED, f.code, f.detail)
            onFailure(f)
        }
        if (failure != null) Dbg.event(DbgComponent.AUDIO, DbgCode.FAILED, failure.code, failure.detail)
        return failure
    }

    private fun onChunk(chunk: ShortArray, len: Int) {
        var p = loudest
        for (i in 0 until len) { val a = if (chunk[i] < 0) -chunk[i].toInt() else chunk[i].toInt(); if (a > p) p = a }
        loudest = p
        health.feed(chunk, len)
        val h = health.status
        if (h != lastHealth) {
            lastHealth = h
            if (h == MicHealth.Status.BLOCKED) Dbg.event(DbgComponent.AUDIO, DbgCode.MIC_BLOCKED)
            onHealth(h)
        }
        val e = endpointer.feed(chunk, len)
        onLevel(endpointer.level)
        if (e != null) onEnd(e)
    }

    /** Stops the recorder and returns the result; the recorder has released its memory and only the result holds the sound. */
    fun finish(): RecordingResult {
        val pcm = recorder.stop()
        val loud = maxOf(loudest, pcm.peak())
        val verdict = RecordingVerdict.of(pcm, endpointer.heardSpeech, loud)
        Dbg.event(DbgComponent.AUDIO, DbgCode.RECORDED, pcm.durationMs, loud, ms = clock() - startedAt)
        return RecordingResult(
            pcm, loud, endpointer.heardSpeech, health.status, endpointer.stopped, verdict,
            hitCeiling = pcm.durationMs >= AudioFormatConstants.MAX_NOTE_MS,
        )
    }

    fun cancel() = recorder.cancel()
}
