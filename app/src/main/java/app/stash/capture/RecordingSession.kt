package app.stash.capture

import app.stash.audio.MicFailure
import app.stash.audio.Recorder
import app.stash.audio.StreamingEncoder
import app.stash.data.CaptureEntity
import app.stash.domain.AudioFormat
import app.stash.domain.EncodedAudio
import app.stash.domain.EndEvent
import app.stash.domain.EndPause
import app.stash.domain.MicHealth
import app.stash.domain.NoteVerdict
import app.stash.domain.Pcm
import app.stash.domain.Wav
import app.stash.audio.RecordingSession as MicSession

/**
 * Card 26g section C: one recorded voice note, from the first chunk to the row in the database. A state machine in
 * plain Kotlin; the recorder, the encoder and the save are passed in, so it is tested with fakes.
 *
 *     RECORDING -> FINISHING -> SAVED | EMPTY | SAVE_FAILED        (SAVE_FAILED -> FINISHING again on "Save again")
 *     RECORDING -> SWITCHED_TO_QUICK                               (the microphone is blocked or would not start)
 *     RECORDING -> CANCELLED                                       (Cancel: the only thing that discards a recording)
 *
 * The save, in this order and nothing else before the tone: stop the recorder; an EMPTY recording is not saved;
 * finish the encoder (at most 1.5 s, then WAV); save; on Saved the save cue. Nothing about writing the note out is
 * awaited. Everything that ends a recording except Cancel saves it.
 *
 * Threads: the recorder calls back on its own thread, so anything that stops the recorder from there goes through
 * [launch] (the application's scope on the phone; it must outlive the screen, because leaving the screen saves).
 * [Hooks] are called on whatever thread got there; the screen hands them to its own thread.
 */
class RecordingSession(
    recorder: Recorder,
    private val encoder: StreamingEncoder,
    /** `RecordingSaver.save`: the encoded note, whether the phone was locked, whether Private was pressed. */
    private val save: suspend (EncodedAudio, Boolean, Boolean) -> InboundSink.Result,
    private val format: AudioFormat,
    pause: EndPause,
    /** The phone was locked when the note began. */
    private val locked: Boolean,
    private val launch: (suspend () -> Unit) -> Unit,
    private val hooks: Hooks,
    clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    enum class State { IDLE, RECORDING, FINISHING, SAVED, EMPTY, SAVE_FAILED, SWITCHED_TO_QUICK, CANCELLED }

    /** What ended the recording. Every one of these saves it. */
    enum class EndReason { DONE, PAUSE, LIMIT, NOTHING_HEARD, LEFT_SCREEN, OPEN_STASH, MIC_ERROR }

    interface Hooks {
        fun onState(state: State) {}
        /** The save cue: called only after the sink returned Saved. */
        fun cueSaved() {}
        fun cueFailed() {}
        /** The recorder was blocked, or would not start, while the phone was locked: remember it (item 4). */
        fun lockedMicBlocked() {}
        /** After a save: ask for the note to be written out. Never awaited. */
        fun afterSaved() {}
    }

    private val stateLock = Any()
    /** Held while the recorder is started or stopped, so a stop never overtakes the start it belongs to. */
    private val micLock = Any()

    @Volatile var state = State.IDLE
        private set
    @Volatile var endReason: EndReason? = null
        private set
    /** The saved capture, once [state] is SAVED. Its words are blank: they arrive later. */
    @Volatile var saved: CaptureEntity? = null
        private set
    /** Length of the sound kept, once the recorder has stopped. */
    @Volatile var durationMs = 0L
        private set

    // For the screen's meter, clock and hints; written on the recorder thread.
    @Volatile var level = 0.0
        private set
    @Volatile var elapsedMs = 0L
        private set
    @Volatile var heardSpeech = false
        private set
    /** True from 15 seconds before the three-minute limit. */
    @Volatile var nearLimit = false
        private set

    @Volatile private var markPrivate = false
    @Volatile private var encoderStarted = false
    private var encoderTried = false
    /** The encoded note, held while it is being saved and after a failed save, so "Save again" sends the same bytes. */
    @Volatile private var pending: EncodedAudio? = null

    private val mic: MicSession = MicSession(
        recorder = Tee(recorder) { chunk, len -> if (encoderStarted) try { encoder.feed(chunk, len) } catch (_: Throwable) {} },
        pause = pause,
        clock = clock,
        onLevel = { l -> level = l; elapsedMs = micElapsed(); heardSpeech = micHeard() },
        onEnd = { e ->
            when (e) {
                EndEvent.WARN_LIMIT -> nearLimit = true
                EndEvent.STOP_PAUSE -> auto(EndReason.PAUSE)
                EndEvent.STOP_LIMIT -> auto(EndReason.LIMIT)
                EndEvent.STOP_NOTHING_HEARD -> auto(EndReason.NOTHING_HEARD)
            }
        },
        onHealth = { h -> if (h == MicHealth.Status.BLOCKED) launch { switchToQuick(rememberIfLocked = true) } },
        // The microphone stopped giving sound part-way (a call took it, say): keep what there is.
        onFailure = { auto(EndReason.MIC_ERROR) },
    )

    private fun micElapsed(): Long = mic.elapsedMs
    private fun micHeard(): Boolean = mic.heardSpeech

    private fun auto(reason: EndReason) = launch { finish(reason) }

    private fun move(from: State, to: State): Boolean = synchronized(stateLock) {
        if (state != from) false else { state = to; true }
    }

    /**
     * Starts the encoder, then the recorder. Call off the main thread (the codec takes a moment to start). Returns
     * the state: RECORDING, or SWITCHED_TO_QUICK when the recorder would not start.
     */
    fun start(): State {
        if (!move(State.IDLE, State.RECORDING)) return state
        var failure: MicFailure? = null
        synchronized(micLock) {
            // Cancel or a stop may have come while this waited: then nothing is started.
            if (state != State.RECORDING) return state
            startEncoder()
            failure = mic.start()
        }
        val f = failure
        if (f != null) {
            // No permission is not a blocked microphone: the screen asks for it before it gets here.
            switchToQuick(rememberIfLocked = f.kind != MicFailure.Kind.NO_PERMISSION)
            return state
        }
        if (state == State.RECORDING) hooks.onState(State.RECORDING)
        return state
    }

    /**
     * Optional: starts the encoder ahead of [start], while the start cue plays, so the recorder opens the moment the
     * cue has finished. Off the main thread. Does nothing once the note has started or been cancelled.
     */
    fun prepare() {
        synchronized(micLock) { if (state == State.IDLE) startEncoder() }
    }

    /** Under [micLock]. A codec that will not start is not a failure: the recorder's own samples are saved as WAV. */
    private fun startEncoder() {
        if (encoderTried) return
        encoderTried = true
        encoderStarted = try { encoder.start(format); true } catch (_: Throwable) { false }
    }

    /** The Private button: the note is saved Private from the start. */
    fun markPrivate() { markPrivate = true }

    private fun switchToQuick(rememberIfLocked: Boolean) {
        if (!move(State.RECORDING, State.SWITCHED_TO_QUICK)) return
        synchronized(micLock) { mic.cancel(); abortEncoder() }
        if (locked && rememberIfLocked) runCatching { hooks.lockedMicBlocked() }
        hooks.onState(State.SWITCHED_TO_QUICK)
    }

    /** Cancel: stop, throw the sound away. Nothing was on disk, so nothing needs deleting. */
    fun cancel() {
        val was = synchronized(stateLock) {
            val s = state
            if (s == State.IDLE || s == State.RECORDING) state = State.CANCELLED
            s
        }
        if (was != State.IDLE && was != State.RECORDING) return
        synchronized(micLock) { mic.cancel(); abortEncoder() }
        hooks.onState(State.CANCELLED)
    }

    /**
     * Ends the recording and saves it. Only the first caller does anything (Done and the pause rule can arrive
     * together); the others get the state as it is. Not on the recorder's thread.
     */
    suspend fun finish(reason: EndReason): State {
        if (!move(State.RECORDING, State.FINISHING)) return state
        endReason = reason
        hooks.onState(State.FINISHING)
        val result = synchronized(micLock) { mic.finish() }
        durationMs = result.pcm.durationMs
        if (result.verdict == NoteVerdict.EMPTY) {
            abortEncoder()
            return settle(State.EMPTY)
        }
        return store(encode(result.pcm))
    }

    /** The encoder's file; if the encoder was never started or throws, the recorder's own samples as WAV. */
    private fun encode(pcm: Pcm): EncodedAudio {
        val fromEncoder = if (encoderStarted) try { encoder.finish() } catch (_: Throwable) { null } else null
        return fromEncoder ?: EncodedAudio(Wav.encode(pcm), AudioFormat.WAV.mime, pcm.durationMs, AudioFormat.WAV)
    }

    private suspend fun store(encoded: EncodedAudio): State {
        pending = encoded
        val result = try {
            save(encoded, locked, markPrivate)
        } catch (e: kotlinx.coroutines.CancellationException) {
            settle(State.SAVE_FAILED)
            throw e
        } catch (e: Exception) {
            InboundSink.Result.Failed(e.javaClass.simpleName)
        }
        return when (result) {
            is InboundSink.Result.Saved -> {
                pending = null
                saved = result.capture
                hooks.cueSaved()
                settle(State.SAVED)
                runCatching { hooks.afterSaved() }
                state
            }
            is InboundSink.Result.Failed -> {
                hooks.cueFailed()
                settle(State.SAVE_FAILED)
            }
        }
    }

    private fun settle(to: State): State {
        synchronized(stateLock) { state = to }
        hooks.onState(to)
        return to
    }

    /** "Save again" after a failed save: the same bytes, not a new recording. */
    suspend fun saveAgain(): State {
        val bytes = pending ?: return state
        if (!move(State.SAVE_FAILED, State.FINISHING)) return state
        hooks.onState(State.FINISHING)
        return store(bytes)
    }

    /** "Throw this recording away" after a failed save. */
    fun discard() {
        if (!move(State.SAVE_FAILED, State.CANCELLED)) return
        pending = null
        hooks.onState(State.CANCELLED)
    }

    private fun abortEncoder() { encoderTried = true; try { encoder.abort() } catch (_: Throwable) {} }

    /** Hands every chunk to [also] before the level rule sees it; everything else is the recorder's own. */
    private class Tee(private val inner: Recorder, private val also: (ShortArray, Int) -> Unit) : Recorder {
        override fun start(onChunk: (ShortArray, Int) -> Unit, onError: (MicFailure) -> Unit): MicFailure? =
            inner.start({ c, n -> also(c, n); onChunk(c, n) }, onError)
        override fun stop(): Pcm = inner.stop()
        override fun cancel() = inner.cancel()
    }

    companion object {
        const val LIMIT_SAVED_LINE = "Stopped at 3:00. Saved."
        const val SAVE_FAILED_LINE = "I couldn't save that note."
        const val DISCARD_QUESTION = "Throw this recording away?"
    }
}
