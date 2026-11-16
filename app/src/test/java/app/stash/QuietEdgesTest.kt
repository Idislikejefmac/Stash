package app.stash

import app.stash.audio.QuietEdges
import app.stash.domain.AudioFormatConstants
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.EventSink
import app.stash.domain.Pcm
import app.stash.speech.Availability
import app.stash.speech.ChunkedTranscriber
import app.stash.speech.EdgeTrimTranscriber
import app.stash.speech.TranscribeFailure
import app.stash.speech.TranscribeOptions
import app.stash.speech.TranscribeResult
import app.stash.speech.Transcriber
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.sin
import kotlin.random.Random

/** 2026-11-16: quiet edges trimmed before the engine (the 04:26 note the engine wrote out as nothing). */
class QuietEdgesTest {
    private val events = mutableListOf<String>()

    @Before fun sink() { Dbg.sink = EventSink { c, code, n1, n2, _, _ -> synchronized(events) { events += "$c $code $n1 $n2" } } }
    @After fun noSink() { Dbg.sink = null }

    private fun n(ms: Long) = AudioFormatConstants.msToSamples(ms).toInt()

    /** Room noise at about 150 of 32768 (the 04:26 note's level) with a tone at about 2000 where [loud] says. */
    private fun sound(totalMs: Long, vararg loud: LongRange, seed: Int = 1): Pcm {
        val r = Random(seed)
        val s = ShortArray(n(totalMs)) { (r.nextInt(-260, 261)).toShort() }
        for (span in loud) for (i in n(span.first) until n(span.last).coerceAtMost(s.size)) {
            s[i] = (s[i] + 2800 * sin(i * 2 * Math.PI * 220 / 16000)).toInt().toShort()
        }
        return Pcm(s)
    }

    private fun ms(samples: Int) = AudioFormatConstants.samplesToMs(samples.toLong())

    @Test fun findsTheSpeechAndKeepsASecondEachSide() {
        val pcm = sound(14_200, 1_000L..8_200L)
        val sp = QuietEdges.speech(pcm)!!
        assertTrue("starts near 1 s: ${ms(sp.first)}", ms(sp.first) in 980..1_020)
        assertTrue("ends near 8.2 s: ${ms(sp.last)}", ms(sp.last) in 8_180..8_220)
        val keep = QuietEdges.keep(pcm, sp, QuietEdges.KEEP_MS)
        assertEquals(0, keep.first) // only 1 s of quiet before: nothing cut at the start
        assertTrue(ms(keep.last) in 9_180..9_220)
    }

    @Test fun nothingBetweenTheFirstAndLastSpeechIsRemoved() {
        val pcm = sound(20_000, 2_000L..4_000L, 9_000L..11_000L) // a 5 s pause in the middle stays
        val sp = QuietEdges.speech(pcm)!!
        val keep = QuietEdges.keep(pcm, sp, QuietEdges.KEEP_MS)
        assertTrue(ms(keep.first) in 980..1_020)
        assertTrue(ms(keep.last) in 11_980..12_020)
    }

    /**
     * The 04:26 note: the microphone's first ~70 ms are near-silent, then room noise just above the absolute minimum
     * (about -43 dBFS). The floor used to be pinned at its minimum by the wake-up, which made that noise speech.
     */
    @Test fun microphoneWarmUpDoesNotMakeRoomNoiseSpeech() {
        val pcm = warmUpThenNoise(14_200, 1_000L..8_200L)
        val sp = QuietEdges.speech(pcm)!!
        assertTrue("speech ends near 8.2 s, not in the noise: ${ms(sp.last)}", ms(sp.last) in 8_180..8_220)
        val level = DoubleArray(pcm.samples.size / 320) { app.stash.domain.Endpointer.rmsOf(pcm.samples, it * 320, 320) }
        assertTrue(QuietEdges.floor(level) > 3 * app.stash.domain.EndpointTuning.MIN_FLOOR)
    }

    /** The live automatic stop, fed the same sound 20 ms at a time: it stops about 2.5 s after the last word. */
    @Test fun theLivePauseStopIsNotFooledByRoomNoise() {
        val pcm = warmUpThenNoise(14_200, 1_000L..8_200L)
        val e = app.stash.domain.Endpointer(app.stash.domain.EndPause.SHORT)
        var stoppedAt = -1L
        var i = 0
        while (i + 320 <= pcm.samples.size) {
            val ev = e.feed(pcm.samples.copyOfRange(i, i + 320))
            i += 320
            if (ev == app.stash.domain.EndEvent.STOP_PAUSE) { stoppedAt = e.elapsedMs; break }
        }
        assertTrue("stopped near 10.7 s: $stoppedAt", stoppedAt in 10_600..10_800)
    }

    /** Talking from the first moment to the last: the floor stays at the gaps' level and nothing is trimmed. */
    @Test fun speechThroughoutIsNotTrimmed() {
        val r = Random(3)
        val s = ShortArray(n(12_000)) { i -> if (i < n(70)) 0 else r.nextInt(-150, 151).toShort() }
        // syllables of 200 ms with 60 ms gaps, from 0.1 s to the end
        for (i in n(100) until s.size) if ((i / 16) % 260 < 200) s[i] = (s[i] + 4000 * sin(i * 2 * Math.PI * 180 / 16000)).toInt().toShort()
        val pcm = Pcm(s)
        val keep = QuietEdges.keep(pcm, QuietEdges.speech(pcm)!!, QuietEdges.KEEP_MS)
        assertEquals(0, keep.first)
        assertEquals(pcm.samples.size - 1, keep.last)
    }

    private fun warmUpThenNoise(totalMs: Long, speech: LongRange): Pcm {
        val r = Random(7)
        val s = ShortArray(n(totalMs)) { i -> if (i < n(70)) 0 else r.nextInt(-400, 401).toShort() }
        for (i in n(speech.first) until n(speech.last)) s[i] = (s[i] + 5000 * sin(i * 2 * Math.PI * 220 / 16000)).toInt().toShort()
        return Pcm(s)
    }

    @Test fun noSpeechMeansNoRange() {
        assertNull(QuietEdges.speech(sound(5_000)))
        assertNull(QuietEdges.speech(Pcm(ShortArray(0))))
        assertNull(QuietEdges.speech(Pcm(ShortArray(100))))
    }

    @Test fun aTapAtTheEndIsNotSpeech() {
        val pcm = sound(14_000, 1_000L..6_000L, 13_000L..13_030L) // a 30 ms click: under 60 ms in a row
        assertTrue(ms(QuietEdges.speech(pcm)!!.last) in 5_980..6_020)
    }

    /** Writes nothing when the audio is longer than [emptyAboveMs]; records what it was given. */
    private class Fake(val emptyAboveMs: Long, val fail: Boolean = false) : Transcriber {
        val heardMs = mutableListOf<Long>()
        val noteMs = mutableListOf<Long?>()
        override val id = "fake"
        override fun availability() = Availability.Ready
        override fun release() {}
        override suspend fun transcribe(audio: Pcm) = transcribe(audio, TranscribeOptions())
        override suspend fun transcribe(audio: Pcm, options: TranscribeOptions): TranscribeResult {
            heardMs += audio.durationMs
            noteMs += options.noteMs
            if (fail && heardMs.size > 1) return TranscribeResult.Failed(TranscribeFailure.ENGINE_ERROR, true)
            return TranscribeResult.Ok(if (audio.durationMs > emptyAboveMs) "" else "words", 10, 1, null)
        }
    }

    @Test fun theEngineGetsTheTrimmedPieceAndTheNotesLength() = runBlocking {
        val f = Fake(emptyAboveMs = 10_000)
        val r = EdgeTrimTranscriber(f).transcribe(sound(14_200, 1_000L..8_200L)) as TranscribeResult.Ok
        assertEquals("words", r.text)
        assertEquals(1, f.heardMs.size)
        assertTrue(f.heardMs[0] in 9_180..9_220)
        assertEquals(14_200L, f.noteMs[0])
        assertTrue(events.any { it.startsWith("TRANSCRIBE ${DbgCode.TRIMMED} 0 ") })
    }

    @Test fun emptyResultIsTriedOnceMoreOnTheSpeechAlone() = runBlocking {
        val f = Fake(emptyAboveMs = 8_000) // the 9.2 s trimmed piece comes back empty; the 7.7 s retry does not
        val r = EdgeTrimTranscriber(f).transcribe(sound(14_200, 1_000L..8_200L)) as TranscribeResult.Ok
        assertEquals("words", r.text)
        assertEquals(2, f.heardMs.size)
        assertTrue("retry is speech + 0.25 s each side: ${f.heardMs[1]}", f.heardMs[1] in 7_650..7_750)
        assertEquals(20L, r.procMs)
        assertTrue(events.any { it.startsWith("TRANSCRIBE ${DbgCode.RETRIED} ") && it.endsWith(" 1") })
    }

    @Test fun stillEmptyAfterTheRetryIsEmptyAndAFailedRetryKeepsTheFirstResult() = runBlocking {
        val none = EdgeTrimTranscriber(Fake(emptyAboveMs = 1_000)).transcribe(sound(14_200, 1_000L..8_200L)) as TranscribeResult.Ok
        assertEquals("", none.text)
        val failing = EdgeTrimTranscriber(Fake(emptyAboveMs = 1_000, fail = true)).transcribe(sound(14_200, 1_000L..8_200L))
        assertTrue(failing is TranscribeResult.Ok && failing.text.isEmpty())
    }

    @Test fun noSpeechGoesToTheEngineUnchanged() = runBlocking {
        val pcm = sound(6_000)
        var given: Pcm? = null
        val f = object : Transcriber {
            override val id = "f"
            override fun availability() = Availability.Ready
            override fun release() {}
            override suspend fun transcribe(audio: Pcm): TranscribeResult { given = audio; return TranscribeResult.Ok("", 1, 1, null) }
        }
        EdgeTrimTranscriber(f).transcribe(pcm)
        assertSame(pcm, given)
    }

    @Test fun aNoteWithLittleQuietIsGivenWhole() = runBlocking {
        val pcm = sound(6_000, 500L..5_500L)
        val f = Fake(emptyAboveMs = 60_000)
        EdgeTrimTranscriber(f).transcribe(pcm)
        assertEquals(6_000L, f.heardMs.single())
        assertTrue(events.none { DbgCode.TRIMMED.name in it })
    }

    @Test fun worksUnderTheLongNoteSplitter() = runBlocking {
        val f = Fake(emptyAboveMs = 60_000)
        val r = ChunkedTranscriber(EdgeTrimTranscriber(f)).transcribe(sound(14_200, 1_000L..8_200L)) as TranscribeResult.Ok
        assertEquals("words", r.text)
    }
}
