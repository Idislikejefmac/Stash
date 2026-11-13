package app.stash

import app.stash.audio.MicFailure
import app.stash.audio.Recorder
import app.stash.audio.RecordingSession
import app.stash.domain.AudioFormatConstants
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.EndEvent
import app.stash.domain.EndPause
import app.stash.domain.Endpointer
import app.stash.domain.MicHealth
import app.stash.domain.Pcm
import app.stash.domain.PcmBuffer
import app.stash.domain.RecordingVerdict
import app.stash.domain.NoteVerdict
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Card 26a: the level rule, the blocked-microphone check, the buffer and the verdict, with synthetic sample streams and no clock. */
class RecorderLogicTest {
    private val n = AudioFormatConstants.CHUNK_SAMPLES

    /** Quiet room noise: a repeating small pattern (about 0.0016 of full scale, RMS). */
    private fun quiet(): ShortArray = ShortArray(n) { i -> (if (i % 2 == 0) 50 else -50).toShort() }

    /** A tone standing in for speech. */
    private fun tone(amp: Int = 6000): ShortArray = ShortArray(n) { i -> (amp * sin(2 * PI * 220 * i / 16000.0)).toInt().toShort() }

    private class Run(val e: Endpointer) {
        val events = mutableListOf<Pair<Long, EndEvent>>()
        fun feed(chunk: ShortArray, ms: Long) {
            repeat((ms / AudioFormatConstants.CHUNK_MS).toInt()) {
                e.feed(chunk)?.let { events += e.elapsedMs to it }
            }
        }
    }

    // ---- PcmBuffer ----

    @Test fun `buffer keeps order across blocks and reports duration to the millisecond`() {
        val b = PcmBuffer()
        val all = ShortArray(40_000) { (it % 30_000).toShort() } // more than two one-second blocks
        var pos = 0
        while (pos < all.size) { val len = minOf(n, all.size - pos); b.append(all.copyOfRange(pos, pos + len)); pos += len }
        assertEquals(40_000L, b.sampleCount)
        assertEquals(2_500L, b.durationMs)
        assertFalse(b.hitCeiling)
        val pcm = b.toPcm()
        assertTrue(all.contentEquals(pcm.samples))
        assertEquals(2_500L, pcm.durationMs)
        assertEquals(1L, Pcm(ShortArray(16)).durationMs)
        assertEquals(0L, Pcm(ShortArray(15)).durationMs)
    }

    @Test fun `buffer honours its ceiling, keeps what fits, and says it was hit`() {
        val b = PcmBuffer(maxSamples = 1000)
        assertEquals(600, b.append(ShortArray(600) { 1 }))
        assertFalse(b.hitCeiling)
        assertEquals(400, b.append(ShortArray(500) { 2 }))
        assertTrue(b.hitCeiling)
        assertEquals(0, b.append(ShortArray(10)))
        assertEquals(1000, b.toPcm().samples.size)
        assertEquals(2.toShort(), b.toPcm().samples[999])
    }

    @Test fun `the default ceiling is three minutes and a full note is 5_76 MB`() {
        val b = PcmBuffer()
        val chunk = ShortArray(n)
        repeat(10_000) { b.append(chunk) } // 200 s offered
        assertEquals(180_000L, b.durationMs)
        assertTrue(b.hitCeiling)
        assertEquals(5_760_000L, b.sampleCount * 2)
    }

    @Test fun `releasing the buffer empties it`() {
        val b = PcmBuffer()
        b.append(ShortArray(n) { 5 })
        b.release()
        assertEquals(0L, b.sampleCount)
        assertEquals(0, b.toPcm().samples.size)
    }

    // ---- Endpointer ----

    @Test fun `speech then 2_5 s of quiet stops on the pause at the right chunk`() {
        val r = Run(Endpointer(EndPause.SHORT))
        r.feed(quiet(), 500)
        r.feed(tone(), 2000)
        r.feed(quiet(), 2480)
        assertTrue("not yet: ${r.events}", r.events.isEmpty())
        r.feed(quiet(), 20)
        assertEquals(listOf(5_000L to EndEvent.STOP_PAUSE), r.events)
        assertTrue(r.e.heardSpeech)
        // After a stop it says nothing more.
        r.feed(tone(), 1000)
        assertEquals(1, r.events.size)
    }

    @Test fun `speech with two second gaps never stops`() {
        val r = Run(Endpointer(EndPause.SHORT))
        r.feed(quiet(), 500)
        repeat(10) { r.feed(tone(), 1000); r.feed(quiet(), 2000) }
        assertTrue(r.events.isEmpty())
    }

    @Test fun `a four second pause keeps recording when the setting is five seconds`() {
        val r = Run(Endpointer(EndPause.LONG))
        r.feed(quiet(), 500); r.feed(tone(), 1000); r.feed(quiet(), 4000); r.feed(tone(), 1000)
        assertTrue(r.events.isEmpty())
        r.feed(quiet(), 5000)
        assertEquals(EndEvent.STOP_PAUSE, r.events.single().second)
    }

    @Test fun `no speech stops as nothing heard at 25 s and not before`() {
        val r = Run(Endpointer(EndPause.SHORT))
        r.feed(quiet(), 24_980)
        assertTrue(r.events.isEmpty())
        r.feed(quiet(), 20)
        assertEquals(listOf(25_000L to EndEvent.STOP_NOTHING_HEARD), r.events)
        assertFalse(r.e.heardSpeech)
    }

    @Test fun `only when I tap Done never stops on a pause but the limit still does`() {
        val r = Run(Endpointer(EndPause.ONLY_DONE))
        r.feed(quiet(), 500); r.feed(tone(), 1000); r.feed(quiet(), 100_000)
        assertTrue(r.events.isEmpty())
        r.feed(quiet(), 100_000)
        assertEquals(EndEvent.WARN_LIMIT to 165_000L, r.events.first().second to r.events.first().first)
        assertEquals(EndEvent.STOP_LIMIT, r.events.last().second)
    }

    @Test fun `a tap running after speech never counts as a pause, and the limit still fires`() {
        val r = Run(Endpointer(EndPause.SHORT))
        r.feed(quiet(), 1000); r.feed(tone(), 1000)
        r.feed(tone(3000), 200_000) // steady loud noise from here on, louder than the room ever was
        assertEquals(listOf(EndEvent.WARN_LIMIT, EndEvent.STOP_LIMIT), r.events.map { it.second })
        assertEquals(180_000L, r.events.last().first)
    }

    @Test fun `steady loud noise from the very first chunk is never speech, so nothing is heard`() {
        val r = Run(Endpointer(EndPause.SHORT))
        r.feed(tone(3000), 30_000)
        assertFalse(r.e.heardSpeech)
        assertEquals(listOf(25_000L to EndEvent.STOP_NOTHING_HEARD), r.events)
    }

    @Test fun `the limit warning fires once, fifteen seconds before the limit`() {
        val r = Run(Endpointer(EndPause.ONLY_DONE))
        r.feed(quiet(), 1000); r.feed(tone(), 300_000)
        assertEquals(listOf(165_000L to EndEvent.WARN_LIMIT, 180_000L to EndEvent.STOP_LIMIT), r.events)
    }

    @Test fun `a quiet sentence still counts as speech over a quiet room`() {
        val r = Run(Endpointer(EndPause.SHORT))
        r.feed(quiet(), 1000); r.feed(tone(500), 1000); r.feed(quiet(), 2500)
        assertEquals(EndEvent.STOP_PAUSE, r.events.single().second)
    }

    // ---- card 26g: a tap is not speech (check C2's finding: keys tapped nearby kept a note open for 21 s) ----

    /** A key tap or a click: a loud burst that is over within one 20 ms chunk. */
    private fun click(): ShortArray = ShortArray(n) { i -> if (i < 80) (if (i % 2 == 0) 20_000 else -20_000).toShort() else (if (i % 2 == 0) 50 else -50).toShort() }

    @Test fun `a click is loud enough to have been speech under the old rule`() {
        assertTrue(Endpointer.isSpeech(Endpointer.rmsOf(click(), n), floor = 0.0016))
        assertEquals(3, app.stash.domain.EndpointTuning.MIN_SPEECH_RUN_CHUNKS)
    }

    @Test fun `keys tapped during the pause do not keep the note open`() {
        for (clickChunks in 1..2) {
            val r = Run(Endpointer(EndPause.SHORT))
            r.feed(quiet(), 500); r.feed(tone(), 2000)
            // 2.5 s without speech, with a tap every half second, each one or two chunks long.
            r.feed(quiet(), 100)
            repeat(4) { r.feed(click(), 20L * clickChunks); r.feed(quiet(), 500 - 20L * clickChunks) }
            r.feed(quiet(), 380)
            assertTrue("not yet: ${r.events}", r.events.isEmpty())
            r.feed(quiet(), 20)
            assertEquals("clicks of $clickChunks chunk(s)", listOf(5_000L to EndEvent.STOP_PAUSE), r.events)
        }
    }

    @Test fun `taps alone are never heard as speech`() {
        val r = Run(Endpointer(EndPause.SHORT))
        repeat(100) { r.feed(quiet(), 260); r.feed(click(), 40) } // 30 s of tapping, nobody speaking
        assertFalse(r.e.heardSpeech)
        assertEquals(listOf(25_000L to EndEvent.STOP_NOTHING_HEARD), r.events)
    }

    @Test fun `a sound that lasts 60 ms is speech, counts in full, and starts the pause again`() {
        val r = Run(Endpointer(EndPause.SHORT))
        r.feed(quiet(), 500); r.feed(tone(), 2000); r.feed(quiet(), 2000)
        r.feed(tone(), 60)
        r.feed(quiet(), 2480)
        assertTrue("not yet: ${r.events}", r.events.isEmpty())
        r.feed(quiet(), 20)
        assertEquals(listOf(7_060L to EndEvent.STOP_PAUSE), r.events)

        val heard = Run(Endpointer(EndPause.ONLY_DONE))
        heard.feed(quiet(), 500); heard.feed(tone(), 280)
        assertFalse(heard.e.heardSpeech)
        heard.feed(tone(), 20) // 300 ms of speech in all: the first chunks of the run are counted too
        assertTrue(heard.e.heardSpeech)
    }

    @Test fun `a word that starts just before the pause limit is not cut off, and a tap there costs only its own length`() {
        val word = Run(Endpointer(EndPause.SHORT))
        word.feed(quiet(), 500); word.feed(tone(), 2000); word.feed(quiet(), 2460)
        word.feed(tone(), 1000) // its first two chunks straddle the 2.5 s mark
        assertTrue(word.events.isEmpty())

        val tap = Run(Endpointer(EndPause.SHORT))
        tap.feed(quiet(), 500); tap.feed(tone(), 2000); tap.feed(quiet(), 2460)
        tap.feed(click(), 40)
        assertTrue(tap.events.isEmpty())
        tap.feed(quiet(), 20)
        assertEquals(listOf(5_020L to EndEvent.STOP_PAUSE), tap.events)
    }

    @Test fun `level follows the chunk and stays between 0 and 1`() {
        val e = Endpointer()
        e.feed(quiet()); assertTrue(e.level in 0.0..0.05)
        e.feed(tone(32000)); assertEquals(1.0, e.level, 0.0)
    }

    @Test fun `the setting parses and defaults to 2_5 seconds`() {
        assertEquals(EndPause.SHORT, EndPause.parse(null))
        assertEquals(EndPause.SHORT, EndPause.parse("junk"))
        assertEquals(5_000L, EndPause.parse("LONG").pauseMs)
        assertNull(EndPause.parse("ONLY_DONE").pauseMs)
    }

    // ---- MicHealth ----

    @Test fun `all zeros for 1_5 s is blocked, and nothing is reported before`() {
        val h = MicHealth()
        val zeros = ShortArray(n)
        repeat(74) { h.feed(zeros) } // 1.48 s
        assertEquals(MicHealth.Status.UNKNOWN, h.status)
        h.feed(zeros)
        assertEquals(MicHealth.Status.BLOCKED, h.status)
    }

    @Test fun `one non-zero sample is OK, at any time`() {
        val h = MicHealth()
        repeat(200) { h.feed(ShortArray(n)) }
        assertEquals(MicHealth.Status.BLOCKED, h.status)
        h.feed(ShortArray(n).also { it[100] = 1 })
        assertEquals(MicHealth.Status.OK, h.status)
        val early = MicHealth(); early.feed(ShortArray(n).also { it[0] = -1 })
        assertEquals(MicHealth.Status.OK, early.status)
    }

    // ---- RecordingVerdict ----

    @Test fun `verdict is EMPTY only for silence with no speech`() {
        val silent = Pcm(ShortArray(16_000))
        assertEquals(NoteVerdict.EMPTY, RecordingVerdict.of(silent, heardSpeech = false, loudest = 0))
        assertEquals(NoteVerdict.EMPTY, RecordingVerdict.of(Pcm(ShortArray(0)), false))
        assertEquals(NoteVerdict.KEEP, RecordingVerdict.of(silent, heardSpeech = true, loudest = 0))
        val quietReal = Pcm(ShortArray(16_000) { (if (it % 2 == 0) 300 else -300).toShort() })
        assertEquals(NoteVerdict.KEEP, RecordingVerdict.of(quietReal, heardSpeech = false))
        assertEquals(NoteVerdict.KEEP, RecordingVerdict.of(silent, false, loudest = RecordingVerdict.EMPTY_PEAK))
    }

    // ---- RecordingSession with a fake recorder and an injected clock ----

    private class FakeRecorder(private val failure: MicFailure? = null) : Recorder {
        private val buffer = PcmBuffer()
        private var cb: ((ShortArray, Int) -> Unit)? = null
        var started = false; var released = false
        override fun start(onChunk: (ShortArray, Int) -> Unit, onError: (MicFailure) -> Unit): MicFailure? {
            if (failure != null) return failure
            cb = onChunk; started = true; return null
        }
        fun push(chunk: ShortArray, times: Int = 1) = repeat(times) { buffer.append(chunk); cb!!(chunk, chunk.size) }
        override fun stop(): Pcm { val p = buffer.toPcm(); buffer.release(); released = true; return p }
        override fun cancel() { buffer.release(); released = true }
    }

    private val events = mutableListOf<List<Any?>>()
    private fun capture() { events.clear(); Dbg.sink = app.stash.domain.EventSink { c, code, n1, n2, _, ms -> events += listOf(c, code, n1, n2, ms) } }
    @After fun clearSink() { Dbg.sink = null }

    @Test fun `a session records, ends on the pause, and writes one RECORDED event of numbers`() {
        capture()
        var now = 1_000L
        val rec = FakeRecorder()
        val ends = mutableListOf<EndEvent>()
        val s = RecordingSession(rec, EndPause.SHORT, { now }, onEnd = { ends += it })
        assertNull(s.start())
        rec.push(quiet(), 25); rec.push(tone(), 50); rec.push(quiet(), 125)
        assertEquals(listOf(EndEvent.STOP_PAUSE), ends)
        now = 6_500L
        val r = s.finish()
        assertTrue(rec.released)
        assertEquals(EndEvent.STOP_PAUSE, r.endReason)
        assertEquals(NoteVerdict.KEEP, r.verdict)
        assertEquals(MicHealth.Status.OK, r.health)
        assertTrue(r.heardSpeech)
        assertEquals(r.pcm.peak(), r.loudest)
        assertEquals(200L * 20, r.pcm.durationMs) // nothing trimmed: every chunk from start to stop is there
        assertEquals(listOf(listOf(DbgComponent.AUDIO, DbgCode.RECORDED, 4000L, r.loudest.toLong(), 5_500L)), events)
    }

    @Test fun `a silenced microphone is reported once as MIC_BLOCKED and the note is EMPTY`() {
        capture()
        val rec = FakeRecorder()
        val health = mutableListOf<MicHealth.Status>()
        val s = RecordingSession(rec, EndPause.SHORT, { 0L }, onHealth = { health += it })
        s.start()
        rec.push(ShortArray(n), 100)
        assertEquals(listOf(MicHealth.Status.BLOCKED), health)
        val r = s.finish()
        assertEquals(MicHealth.Status.BLOCKED, r.health)
        assertEquals(NoteVerdict.EMPTY, r.verdict)
        assertEquals(1, events.count { it[1] == DbgCode.MIC_BLOCKED })
    }

    @Test fun `a recorder that cannot start is a FAILED event with its code and nothing throws`() {
        capture()
        val s = RecordingSession(FakeRecorder(MicFailure(MicFailure.Kind.NOT_INITIALIZED, 0)), EndPause.SHORT, { 0L })
        val f = s.start()
        assertNotNull(f)
        assertEquals(listOf(listOf<Any?>(DbgComponent.AUDIO, DbgCode.FAILED, 2L, 0L, null)), events)
    }

    @Test fun `cancel releases the recorder`() {
        val rec = FakeRecorder()
        val s = RecordingSession(rec, EndPause.SHORT, { 0L })
        s.start(); rec.push(tone(), 5); s.cancel()
        assertTrue(rec.released)
    }

    @Test fun `a note past the ceiling is cut at three minutes and flagged`() {
        val rec = FakeRecorder()
        val s = RecordingSession(rec, EndPause.ONLY_DONE, { 0L })
        s.start(); rec.push(quiet(), 50); rec.push(tone(), 9_200) // 185 s offered
        val r = s.finish()
        assertEquals(180_000L, r.pcm.durationMs)
        assertTrue(r.hitCeiling)
        assertEquals(EndEvent.STOP_LIMIT, r.endReason)
    }
}
