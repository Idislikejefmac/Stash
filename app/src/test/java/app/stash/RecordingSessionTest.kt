package app.stash

import app.stash.audio.AudioEncoder
import app.stash.audio.EncoderFactory
import app.stash.audio.MicFailure
import app.stash.audio.Recorder
import app.stash.audio.StreamingEncoder
import app.stash.capture.InboundSink
import app.stash.capture.RecordingSession
import app.stash.capture.RecordingSession.EndReason
import app.stash.capture.RecordingSession.State
import app.stash.data.CaptureEntity
import app.stash.data.ExtractState
import app.stash.data.TranscribeState
import app.stash.domain.AudioFormat
import app.stash.domain.AudioFormatConstants
import app.stash.domain.EncodedAudio
import app.stash.domain.EndPause
import app.stash.domain.Pcm
import app.stash.domain.PcmBuffer
import app.stash.domain.Wav
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.sin

/**
 * Card 26g section C: the recording flow as a state machine, with a fake recorder, fake codecs behind the real
 * [StreamingEncoder], and a fake save. No Android class, no clock: the fake recorder is pushed chunk by chunk.
 */
class RecordingSessionTest {
    private val n = AudioFormatConstants.CHUNK_SAMPLES

    /** Quiet room noise: real sound (peak 50), never speech. */
    private fun quiet(): ShortArray = ShortArray(n) { i -> (if (i % 2 == 0) 50 else -50).toShort() }
    /** A tone standing in for speech. */
    private fun tone(): ShortArray = ShortArray(n) { i -> (6000 * sin(2 * PI * 220 * i / 16000.0)).toInt().toShort() }
    /** Sound too faint to be anything (peak 5, below RecordingVerdict.EMPTY_PEAK), but not the zeros of a blocked microphone. */
    private fun faint(): ShortArray = ShortArray(n) { i -> (if (i % 2 == 0) 5 else -5).toShort() }

    private class FakeRecorder(private val failure: MicFailure? = null) : Recorder {
        private val buffer = PcmBuffer()
        private var cb: ((ShortArray, Int) -> Unit)? = null
        private var err: ((MicFailure) -> Unit)? = null
        var started = false; var stopped = false; var cancelled = false
        val released get() = stopped || cancelled
        override fun start(onChunk: (ShortArray, Int) -> Unit, onError: (MicFailure) -> Unit): MicFailure? {
            if (failure != null) return failure
            cb = onChunk; err = onError; started = true; return null
        }
        fun push(chunk: ShortArray, times: Int = 1) = repeat(times) { if (!released) { buffer.append(chunk); cb!!(chunk, chunk.size) } }
        fun breakDown() = err!!(MicFailure(MicFailure.Kind.READ_ERROR, -3))
        override fun stop(): Pcm { val p = buffer.toPcm(); buffer.release(); stopped = true; return p }
        override fun cancel() { buffer.release(); cancelled = true }
    }

    private class FakeEncoder(val format: AudioFormat, val finishSleepMs: Long = 0) : AudioEncoder {
        var samples = 0L
        @Volatile var aborted = false
        override fun start() {}
        override fun feed(chunk: ShortArray, len: Int) { samples += len }
        override fun finish(): EncodedAudio {
            if (finishSleepMs > 0) Thread.sleep(finishSleepMs)
            return EncodedAudio(ByteArray(10) { 7 }, format.mime, AudioFormatConstants.samplesToMs(samples), format)
        }
        override fun abort() { aborted = true }
    }

    private class FakeFactory(val finishSleepMs: Long = 0) : EncoderFactory {
        val made = CopyOnWriteArrayList<FakeEncoder>()
        override fun available(format: AudioFormat) = format.isOpus
        override fun create(format: AudioFormat): AudioEncoder = FakeEncoder(format, finishSleepMs).also { made += it }
    }

    private fun capture(id: String, asPrivate: Boolean) = CaptureEntity(
        id = id, createdAt = 1, updatedAt = 1, rawText = "", text = "", sensitive = asPrivate,
        extractState = ExtractState.DONE, transcribeState = TranscribeState.PENDING,
    )

    /** One note under test: everything that happened to it, in order, in [log]. */
    private inner class Rig(
        locked: Boolean = false,
        pause: EndPause = EndPause.SHORT,
        val rec: FakeRecorder = FakeRecorder(),
        val factory: FakeFactory = FakeFactory(),
        finishLimitMs: Long = 1_500,
        /** Results of each save in turn; after the last one every save succeeds. */
        val saveResults: MutableList<Boolean> = mutableListOf(),
    ) : RecordingSession.Hooks {
        val log = CopyOnWriteArrayList<String>()
        val saves = CopyOnWriteArrayList<Triple<EncodedAudio, Boolean, Boolean>>()
        val encoder = StreamingEncoder(factory, finishLimitMs)
        val session = RecordingSession(
            recorder = rec, encoder = encoder,
            save = { audio, wasLocked, asPrivate ->
                log += "save"
                saves += Triple(audio, wasLocked, asPrivate)
                val ok = if (saveResults.isEmpty()) true else saveResults.removeAt(0)
                val r = if (ok) InboundSink.Result.Saved(capture("c${saves.size}", asPrivate)) else InboundSink.Result.Failed("IOException")
                log += "save returned " + if (ok) "Saved" else "Failed"
                r
            },
            format = AudioFormat.OPUS_12, pause = pause, locked = locked,
            // In the app this is the application's scope; here it runs at once, so a test reads as a sequence.
            launch = { block -> runBlocking { block() } },
            hooks = this,
        )
        override fun onState(state: State) { log += "state $state" }
        override fun cueSaved() { log += "cue saved" }
        override fun cueFailed() { log += "cue failed" }
        override fun lockedMicBlocked() { log += "remember locked mic blocked" }
        override fun afterSaved() { log += "ask for writing out" }

        fun finish(reason: EndReason) = runBlocking { session.finish(reason) }
        fun waitAborted() {
            val deadline = System.currentTimeMillis() + 2_000
            while (factory.made.any { !it.aborted } && System.currentTimeMillis() < deadline) Thread.sleep(5)
        }
    }

    private fun speaking(r: Rig) { r.rec.push(quiet(), 25); r.rec.push(tone(), 50) }

    // ------------------------------------------------------------------ Done

    @Test fun `Done saves once, and the save cue comes only after the sink returned Saved`() {
        val r = Rig()
        assertEquals(State.RECORDING, r.session.start())
        assertTrue(r.rec.started)
        speaking(r)
        assertEquals(listOf("state RECORDING"), r.log.toList()) // nothing is saved and no cue plays while he speaks
        assertEquals(State.SAVED, r.finish(EndReason.DONE))
        assertEquals(
            listOf("state RECORDING", "state FINISHING", "save", "save returned Saved", "cue saved", "state SAVED", "ask for writing out"),
            r.log.toList(),
        )
        assertTrue(r.rec.stopped)
        assertEquals(1, r.saves.size)
        // The encoder was fed every chunk from start to stop: nothing trimmed.
        assertEquals(AudioFormat.OPUS_12, r.saves.single().first.format)
        assertEquals(75L * 20, r.saves.single().first.durationMs)
        assertEquals(75L * 20, r.session.durationMs)
        assertEquals("c1", r.session.saved!!.id)
        assertEquals(EndReason.DONE, r.session.endReason)
        // A second Done, or the pause rule arriving late, does nothing.
        assertEquals(State.SAVED, r.finish(EndReason.DONE))
        assertEquals(State.SAVED, r.finish(EndReason.PAUSE))
        assertEquals(1, r.saves.size)
        assertEquals(1, r.log.count { it == "cue saved" })
    }

    @Test fun `the screen can read the level, the clock and whether speech was heard while recording`() {
        val r = Rig()
        r.session.start()
        r.rec.push(quiet(), 25)
        assertFalse(r.session.heardSpeech) // "Take your time."
        assertEquals(500L, r.session.elapsedMs)
        r.rec.push(tone(), 50)
        assertTrue(r.session.heardSpeech)
        assertTrue(r.session.level > 0.05)
        assertEquals(1_500L, r.session.elapsedMs)
        assertFalse(r.session.nearLimit)
    }

    // ------------------------------------------------------------------ Cancel

    @Test fun `Cancel saves nothing, releases the recorder and aborts the encoder`() {
        val r = Rig()
        r.session.start()
        speaking(r)
        r.session.cancel()
        r.waitAborted()
        assertEquals(State.CANCELLED, r.session.state)
        assertTrue(r.rec.cancelled)
        assertFalse(r.rec.stopped)
        assertTrue(r.factory.made.single().aborted)
        assertTrue(r.saves.isEmpty())
        assertTrue(r.log.none { it.startsWith("cue") || it == "save" })
        // Nothing after Cancel can save it.
        assertEquals(State.CANCELLED, r.finish(EndReason.LEFT_SCREEN))
        assertTrue(r.saves.isEmpty())
    }

    @Test fun `Cancel before the recorder has started starts nothing`() {
        val r = Rig()
        r.session.prepare()
        r.session.cancel()
        assertEquals(State.CANCELLED, r.session.start())
        assertFalse(r.rec.started)
        r.waitAborted()
        assertTrue(r.factory.made.all { it.aborted })
    }

    // ------------------------------------------------------------------ everything else that ends it saves it

    @Test fun `the pause rule saves the note`() {
        val r = Rig()
        r.session.start()
        speaking(r)
        r.rec.push(quiet(), 124)
        assertEquals(State.RECORDING, r.session.state)
        r.rec.push(quiet(), 1) // 2.5 s of quiet
        assertEquals(State.SAVED, r.session.state)
        assertEquals(EndReason.PAUSE, r.session.endReason)
        assertEquals(200L * 20, r.saves.single().first.durationMs)
        assertEquals(1, r.log.count { it == "cue saved" })
    }

    @Test fun `the three minute limit saves the note, with the warning fifteen seconds before`() {
        val r = Rig(pause = EndPause.ONLY_DONE)
        r.session.start()
        r.rec.push(quiet(), 25)
        r.rec.push(tone(), 8_224) // up to 164.98 s
        assertFalse(r.session.nearLimit)
        r.rec.push(tone(), 1) // 165 s: "Stops at 3:00"
        assertTrue(r.session.nearLimit)
        r.rec.push(tone(), 1_000)
        assertEquals(State.SAVED, r.session.state)
        assertEquals(EndReason.LIMIT, r.session.endReason)
        assertEquals(180_000L, r.saves.single().first.durationMs)
        assertEquals("Stopped at 3:00. Saved.", RecordingSession.LIMIT_SAVED_LINE)
    }

    @Test fun `leaving the screen while recording saves what was said so far`() {
        val r = Rig()
        r.session.start()
        speaking(r)
        assertEquals(State.SAVED, r.finish(EndReason.LEFT_SCREEN)) // onStop: Home, back, screen off, a call
        assertEquals(1, r.saves.size)
        assertEquals(75L * 20, r.saves.single().first.durationMs)
    }

    @Test fun `Open Stash while recording saves first`() {
        val r = Rig()
        r.session.start()
        speaking(r)
        assertEquals(State.SAVED, r.finish(EndReason.OPEN_STASH))
        assertEquals(EndReason.OPEN_STASH, r.session.endReason)
    }

    @Test fun `a microphone that stops part-way saves what there is`() {
        val r = Rig()
        r.session.start()
        speaking(r)
        r.rec.breakDown()
        assertEquals(State.SAVED, r.session.state)
        assertEquals(EndReason.MIC_ERROR, r.session.endReason)
        assertEquals(1, r.saves.size)
    }

    @Test fun `room noise with no speech is kept when in doubt, and ends as nothing heard at 25 seconds`() {
        val r = Rig()
        r.session.start()
        r.rec.push(quiet(), 1_249)
        assertEquals(State.RECORDING, r.session.state)
        r.rec.push(quiet(), 1)
        assertEquals(EndReason.NOTHING_HEARD, r.session.endReason)
        assertEquals(State.SAVED, r.session.state) // 26a: only a plainly empty recording is refused
    }

    // ------------------------------------------------------------------ an empty recording

    @Test fun `an EMPTY recording saves nothing and plays no cue`() {
        val r = Rig()
        r.session.start()
        r.rec.push(faint(), 100)
        assertEquals(State.EMPTY, r.finish(EndReason.DONE))
        r.waitAborted()
        assertTrue(r.saves.isEmpty())
        assertTrue(r.log.none { it.startsWith("cue") })
        assertTrue(r.rec.stopped)
        assertTrue(r.factory.made.single().aborted)
        assertNull(r.session.saved)
        assertEquals(EndReason.DONE, r.session.endReason) // the screen says "No note made" for Done
    }

    @Test fun `nothing at all for 25 seconds ends EMPTY without an error cue`() {
        val r = Rig()
        r.session.start()
        r.rec.push(faint(), 1_250)
        assertEquals(State.EMPTY, r.session.state)
        assertEquals(EndReason.NOTHING_HEARD, r.session.endReason) // the screen says "I didn't catch that."
        assertTrue(r.saves.isEmpty())
        assertTrue(r.log.none { it.startsWith("cue") })
    }

    // ------------------------------------------------------------------ the encoder

    @Test fun `a slow encoder ends in a WAV save holding every sample`() {
        val r = Rig(factory = FakeFactory(finishSleepMs = 20_000), finishLimitMs = 150)
        r.session.start()
        val fed = ShortArray(60 * n)
        repeat(60) { i -> val c = if (i < 10) quiet() else tone(); c.copyInto(fed, i * n); r.rec.push(c) }
        assertEquals(State.SAVED, r.finish(EndReason.DONE))
        val saved = r.saves.single().first
        assertEquals(AudioFormat.WAV, saved.format)
        assertEquals("audio/wav", saved.mime)
        assertArrayEquals(fed, Wav.decode(saved.bytes).samples)
        assertEquals(1, r.log.count { it == "cue saved" })
    }

    @Test fun `an encoder that cannot be started at all still saves the note, as WAV from the recorder's own samples`() {
        val r = Rig()
        r.encoder.start(AudioFormat.OPUS_12) // a second start throws inside the session
        r.session.start()
        val fed = ShortArray(40 * n)
        repeat(40) { i -> val c = tone(); c.copyInto(fed, i * n); r.rec.push(c) }
        assertEquals(State.SAVED, r.finish(EndReason.DONE))
        assertEquals(AudioFormat.WAV, r.saves.single().first.format)
        assertArrayEquals(fed, Wav.decode(r.saves.single().first.bytes).samples)
        r.encoder.abort()
    }

    @Test fun `prepare starts the encoder ahead of the recorder, once`() {
        val r = Rig()
        r.session.prepare()
        assertEquals(1, r.factory.made.size)
        assertFalse(r.rec.started)
        r.session.prepare()
        assertEquals(State.RECORDING, r.session.start())
        assertEquals(1, r.factory.made.size)
        speaking(r)
        assertEquals(State.SAVED, r.finish(EndReason.DONE))
        assertEquals(AudioFormat.OPUS_12, r.saves.single().first.format)
    }

    // ------------------------------------------------------------------ a failed save

    @Test fun `a Failed save keeps the bytes, and Save again sends the same bytes`() {
        val r = Rig(saveResults = mutableListOf(false, false))
        r.session.start()
        speaking(r)
        assertEquals(State.SAVE_FAILED, r.finish(EndReason.DONE))
        assertEquals(listOf("save", "save returned Failed", "cue failed", "state SAVE_FAILED"), r.log.takeLast(4))
        assertTrue(r.log.none { it == "cue saved" || it == "ask for writing out" })
        assertNull(r.session.saved)
        assertTrue(r.rec.stopped) // the recorder is not started again: it is the same recording

        assertEquals(State.SAVE_FAILED, runBlocking { r.session.saveAgain() })
        assertEquals(State.SAVED, runBlocking { r.session.saveAgain() })
        assertEquals(3, r.saves.size)
        assertSame(r.saves[0].first, r.saves[1].first)
        assertSame(r.saves[0].first.bytes, r.saves[2].first.bytes)
        assertEquals(listOf("cue saved", "state SAVED", "ask for writing out"), r.log.takeLast(3))
        assertEquals(1, r.log.count { it == "cue saved" })
        assertNotNull(r.session.saved)
        // Saved: there is nothing left to save again or to throw away.
        assertEquals(State.SAVED, runBlocking { r.session.saveAgain() })
        r.session.discard()
        assertEquals(State.SAVED, r.session.state)
        assertEquals(3, r.saves.size)
    }

    @Test fun `a save that throws is a failed save, and the recording can be thrown away only by asking`() {
        val r = Rig()
        val boom = RecordingSession(
            recorder = r.rec, encoder = r.encoder, save = { _, _, _ -> throw java.io.IOException("disk full") },
            format = AudioFormat.OPUS_12, pause = EndPause.SHORT, locked = false,
            launch = { block -> runBlocking { block() } }, hooks = r,
        )
        boom.start()
        speaking(r)
        assertEquals(State.SAVE_FAILED, runBlocking { boom.finish(EndReason.PAUSE) })
        assertEquals("cue failed", r.log[r.log.size - 2])
        boom.discard()
        assertEquals(State.CANCELLED, boom.state)
        assertEquals(State.CANCELLED, runBlocking { boom.saveAgain() })
        assertEquals("Throw this recording away?", RecordingSession.DISCARD_QUESTION)
        assertEquals("I couldn't save that note.", RecordingSession.SAVE_FAILED_LINE)
    }

    // ------------------------------------------------------------------ a blocked microphone

    @Test fun `MicHealth BLOCKED switches to the quick note and records the lock fact when locked`() {
        val r = Rig(locked = true)
        r.session.start()
        r.rec.push(ShortArray(n), 74) // 1.48 s of zeros: not decided yet
        assertEquals(State.RECORDING, r.session.state)
        r.rec.push(ShortArray(n), 1)
        r.waitAborted()
        assertEquals(State.SWITCHED_TO_QUICK, r.session.state)
        assertTrue(r.rec.cancelled) // the recorder is stopped so the phone's recogniser can have the microphone
        assertTrue(r.factory.made.single().aborted)
        assertTrue(r.saves.isEmpty())
        assertEquals(listOf("state RECORDING", "remember locked mic blocked", "state SWITCHED_TO_QUICK"), r.log.toList())
        assertEquals(State.SWITCHED_TO_QUICK, r.finish(EndReason.DONE))
        assertTrue(r.saves.isEmpty())
    }

    @Test fun `MicHealth BLOCKED on an unlocked phone switches to the quick note and remembers nothing`() {
        val r = Rig(locked = false)
        r.session.start()
        r.rec.push(ShortArray(n), 80)
        assertEquals(State.SWITCHED_TO_QUICK, r.session.state)
        assertEquals(listOf("state RECORDING", "state SWITCHED_TO_QUICK"), r.log.toList())
    }

    @Test fun `a recorder that will not start switches to the quick note, and is remembered only when locked`() {
        val locked = Rig(locked = true, rec = FakeRecorder(MicFailure(MicFailure.Kind.START_REFUSED, 1)))
        assertEquals(State.SWITCHED_TO_QUICK, locked.session.start())
        locked.waitAborted()
        assertEquals(listOf("remember locked mic blocked", "state SWITCHED_TO_QUICK"), locked.log.toList())
        assertTrue(locked.factory.made.single().aborted)
        assertTrue(locked.saves.isEmpty())

        val unlocked = Rig(locked = false, rec = FakeRecorder(MicFailure(MicFailure.Kind.NOT_INITIALIZED, 0)))
        assertEquals(State.SWITCHED_TO_QUICK, unlocked.session.start())
        assertEquals(listOf("state SWITCHED_TO_QUICK"), unlocked.log.toList())

        // A missing permission is not a blocked lock screen.
        val noPermission = Rig(locked = true, rec = FakeRecorder(MicFailure(MicFailure.Kind.NO_PERMISSION)))
        assertEquals(State.SWITCHED_TO_QUICK, noPermission.session.start())
        assertEquals(listOf("state SWITCHED_TO_QUICK"), noPermission.log.toList())
    }

    // ------------------------------------------------------------------ Private, and what the save is told

    @Test fun `Private pressed while recording saves the note Private from the start`() {
        val r = Rig(locked = true)
        r.session.start()
        speaking(r)
        r.session.markPrivate()
        r.rec.push(tone(), 10)
        r.finish(EndReason.DONE)
        val (_, wasLocked, asPrivate) = r.saves.single()
        assertTrue(asPrivate)
        assertTrue(wasLocked)
        assertTrue(r.session.saved!!.sensitive)

        val plain = Rig()
        plain.session.start(); speaking(plain); plain.finish(EndReason.DONE)
        assertFalse(plain.saves.single().third)
        assertFalse(plain.saves.single().second)
    }
}
