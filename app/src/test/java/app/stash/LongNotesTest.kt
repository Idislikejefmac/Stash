package app.stash

import app.stash.audio.PauseSplitter
import app.stash.audio.Piece
import app.stash.audio.Splitter
import app.stash.domain.AudioFormatConstants
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.EventSink
import app.stash.domain.Pcm
import app.stash.speech.Availability
import app.stash.speech.ChunkedTranscriber
import app.stash.speech.DefaultPack
import app.stash.speech.DefaultPackRule
import app.stash.speech.InstalledPack
import app.stash.speech.KnownModels
import app.stash.speech.KnownPack
import app.stash.speech.LoadedModel
import app.stash.speech.MemoryGuide
import app.stash.speech.MemoryRun
import app.stash.speech.ModelLocator
import app.stash.speech.PackChoice
import app.stash.speech.PackFile
import app.stash.speech.PackLocation
import app.stash.speech.PackMarker
import app.stash.speech.PackOutcome
import app.stash.speech.PackPolicy
import app.stash.speech.PackTranscriber
import app.stash.speech.RunPlace
import app.stash.speech.SpeechRuntime
import app.stash.speech.TranscribeFailure
import app.stash.speech.TranscribeOptions
import app.stash.speech.TranscribeResult
import app.stash.speech.Transcriber
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Card 26e: cutting long notes at real pauses, joining the pieces' words, threads, and the base-or-tiny rule. */
class LongNotesTest {
    private val events = mutableListOf<String>()

    @Before fun setUp() {
        Dbg.sink = EventSink { c, code, n1, n2, _, ms -> synchronized(events) { events += "$c $code $n1 $n2 $ms" } }
    }

    @After fun tearDown() { Dbg.sink = null }

    // ------------------------------------------------------------------ synthetic sound: a tone is speech, low noise is a pause

    private class Sound {
        private val parts = ArrayList<ShortArray>()
        private val rnd = Random(26)
        val pauses = mutableListOf<IntRange>()
        var length = 0; private set

        private fun add(a: ShortArray) { parts += a; length += a.size }
        private fun n(ms: Long) = AudioFormatConstants.msToSamples(ms).toInt()

        fun tone(ms: Long, amp: Double = 0.5) = apply {
            val start = length
            add(ShortArray(n(ms)) { (amp * 32767 * sin(2 * PI * 440.0 * (start + it) / 16_000)).toInt().toShort() })
        }

        fun noise(ms: Long, amp: Int = 100, pause: Boolean = true) = apply {
            if (pause) pauses += length until length + n(ms)
            add(ShortArray(n(ms)) { (rnd.nextInt(2 * amp + 1) - amp).toShort() })
        }

        fun zeros(ms: Long) = apply { add(ShortArray(n(ms))) }

        fun pcm(): Pcm {
            val out = ShortArray(length)
            var p = 0
            for (a in parts) { a.copyInto(out, p); p += a.size }
            return Pcm(out)
        }
    }

    private fun ms(samples: Int) = AudioFormatConstants.samplesToMs(samples.toLong())
    private fun samples(ms: Long) = AudioFormatConstants.msToSamples(ms).toInt()

    /** Pieces start at 0, end at the last sample, and each starts right after the one before: every sample once. */
    private fun assertCoversOnce(pieces: List<Piece>, n: Int) {
        assertEquals(0, pieces.first().range.first)
        assertEquals(n - 1, pieces.last().range.last)
        for (i in 1 until pieces.size) assertEquals("piece $i", pieces[i - 1].range.last + 1, pieces[i].range.first)
        assertEquals(n, pieces.sumOf { it.samples })
    }

    /** About 50 s: one second of room noise, then 5.3 s of "speech" and a 0.7 s pause, over and over. */
    private fun speechWithPausesEvery6s(totalMs: Long = 50_000): Sound {
        val s = Sound().noise(1_000)
        while (ms(s.length) + 6_000 <= totalMs) s.tone(5_300).noise(700)
        return s
    }

    // ------------------------------------------------------------------ PauseSplitter

    @Test fun aRecordingUpToTheMaximumIsOnePieceAndAnEmptyOneIsOneEmptyPiece() {
        val s19 = Sound().noise(500).tone(3_000).noise(800).tone(14_700).pcm()
        assertEquals(19_000, s19.durationMs)
        assertEquals(listOf(Piece(0 until s19.samples.size)), PauseSplitter.split(s19))
        val s20 = Sound().tone(PauseSplitter.MAX_MS).pcm()
        assertEquals(1, PauseSplitter.split(s20).size)
        assertEquals(listOf(Piece(0 until 0)), PauseSplitter.split(Pcm(ShortArray(0))))
    }

    @Test fun fiftySecondsWithPausesEverySixSecondsIsCutOnlyInsidePausesIntoPiecesOfNineToTwentySeconds() {
        val sound = speechWithPausesEvery6s()
        val pcm = sound.pcm()
        assertTrue(pcm.durationMs in 49_000..50_000)
        val pieces = PauseSplitter.split(pcm)
        assertTrue("several pieces: $pieces", pieces.size >= 3)
        assertCoversOnce(pieces, pcm.samples.size)
        assertFalse(pieces.any { it.forcedEnd })
        for (p in pieces) assertTrue("${ms(p.samples)} ms", ms(p.samples) in 9_000..20_000)
        for (p in pieces.drop(1)) {
            val cut = p.range.first
            assertTrue("cut at ${ms(cut)} ms is inside a pause", sound.pauses.any { cut in it })
            // At least 200 ms (half the shortest pause) of quiet on each side of the cut: no word is touched.
            assertTrue(sound.pauses.any { cut - samples(200) in it && cut + samples(200) - 1 in it })
        }
    }

    @Test fun oneContinuousToneIsCutWithThreeHundredMillisecondOverlapsAndMarkedForced() {
        val pcm = Sound().noise(200).tone(49_800).pcm()
        val pieces = PauseSplitter.split(pcm)
        assertTrue(pieces.size >= 3)
        assertTrue(pieces.dropLast(1).all { it.forcedEnd })
        assertFalse(pieces.last().forcedEnd)
        for (i in 1 until pieces.size) {
            val shared = pieces[i - 1].range.last - pieces[i].range.first + 1
            assertEquals("overlap ${ms(shared)} ms", samples(PauseSplitter.OVERLAP_MS), shared)
        }
        for (p in pieces) assertTrue("${ms(p.samples)} ms", p.samples <= samples(PauseSplitter.MAX_MS))
        assertEquals(0, pieces.first().range.first)
        assertEquals(pcm.samples.size - 1, pieces.last().range.last)
    }

    @Test fun aThreeHundredMillisecondGapIsNotUsedWhenASixHundredMillisecondPauseIsInTheWindow() {
        // The short gap is digital silence (quieter than the pause) and sits right at the aim; it still must not win.
        val sound = Sound().noise(1_000).tone(9_500).noise(600).tone(3_400).zeros(300).tone(15_200)
        val pcm = sound.pcm()
        val pieces = PauseSplitter.split(pcm)
        assertEquals(2, pieces.size)
        assertFalse(pieces[0].forcedEnd)
        val pause = sound.pauses[1]
        assertTrue("cut at ${ms(pieces[1].range.first)} ms", pieces[1].range.first in pause)
        assertCoversOnce(pieces, pcm.samples.size)
    }

    @Test fun aThreeMinuteNoteIsCutIntoPiecesNoneLongerThanTheMaximumAndNothingIsLost() {
        val sound = speechWithPausesEvery6s(totalMs = AudioFormatConstants.MAX_NOTE_MS)
        val pcm = sound.pcm()
        val pieces = PauseSplitter.split(pcm)
        assertCoversOnce(pieces, pcm.samples.size)
        assertTrue(pieces.size in 9..20)
        for (p in pieces) assertTrue(p.samples <= samples(PauseSplitter.MAX_MS))
        for (p in pieces.drop(1)) assertTrue(sound.pauses.any { p.range.first in it })
    }

    @Test fun softerSoundAtSpeechLevelIsNeverTakenForAPause() {
        // A "pause" filled with sound at speech level is speech: with no real pause the cut is forced.
        val pcm = Sound().noise(300).tone(10_000).tone(1_000, amp = 0.2).tone(14_000).pcm()
        val pieces = PauseSplitter.split(pcm)
        assertEquals(2, pieces.size)
        assertTrue(pieces[0].forcedEnd)
    }

    // ------------------------------------------------------------------ ChunkedTranscriber

    /** Returns the next text from [texts] for each call, or fails on call [failOn] (1-based). */
    private class FakeInner(val texts: List<String>, val failOn: Int = -1) : Transcriber {
        val received = mutableListOf<Pcm>()
        val options = mutableListOf<TranscribeOptions>()
        var released = 0
        override val id = "fake-engine"
        override fun availability() = Availability.Ready
        override suspend fun transcribe(audio: Pcm): TranscribeResult = transcribe(audio, TranscribeOptions())
        override suspend fun transcribe(audio: Pcm, options: TranscribeOptions): TranscribeResult {
            received += audio
            this.options += options
            if (received.size == failOn) return TranscribeResult.Failed(TranscribeFailure.OUT_OF_MEMORY, retryable = true)
            return TranscribeResult.Ok(texts[received.size - 1], 10L * received.size, 1, null)
        }
        override fun release() { released++ }
    }

    private fun splitter(vararg pieces: Piece) = Splitter { pieces.toList() }
    private val thirty = Pcm(ShortArray(30) { it.toShort() })

    @Test fun piecesAreWrittenOutInOrderAndJoinedWithOneSpace() = runBlocking {
        val inner = FakeInner(listOf(" One two. ", "three four", "", "five"))
        val t = ChunkedTranscriber(inner, splitter(Piece(0..9), Piece(10..19), Piece(20..24), Piece(25..29)))
        val r = t.transcribe(thirty, TranscribeOptions(place = RunPlace.BACKGROUND)) as TranscribeResult.Ok
        assertEquals("One two. three four five", r.text)
        assertEquals(4, r.chunks)
        assertEquals(10L + 20 + 30 + 40, r.procMs)
        assertNull(r.confidence)
        assertEquals(listOf(0, 10, 20, 25), inner.received.map { it.samples[0].toInt() })
        assertEquals(listOf(10, 10, 5, 5), inner.received.map { it.samples.size })
        // Every piece is run where the caller said, and knows the whole note's length (for the engine's pack choice).
        assertTrue(inner.options.all { it == TranscribeOptions(RunPlace.BACKGROUND, thirty.durationMs) })
        assertTrue(events.any { it.startsWith("TRANSCRIBE ${DbgCode.SPLIT} 4 0") })
        t.release()
        assertEquals(1, inner.released)
        assertEquals("fake-engine", t.id)
    }

    @Test fun aRepeatedPairOfWordsIsDroppedOnceAfterAForcedCutAndNeverAfterANaturalOne() = runBlocking {
        val forced = ChunkedTranscriber(FakeInner(listOf("We went to the Shop.", "the shop, the shop was closed")), splitter(Piece(0..15, forcedEnd = true), Piece(11..29)))
        assertEquals("We went to the Shop. the shop was closed", (forced.transcribe(thirty) as TranscribeResult.Ok).text)
        val natural = ChunkedTranscriber(FakeInner(listOf("We went to the shop", "the shop was closed")), splitter(Piece(0..14), Piece(15..29)))
        assertEquals("We went to the shop the shop was closed", (natural.transcribe(thirty) as TranscribeResult.Ok).text)
        val noRepeat = ChunkedTranscriber(FakeInner(listOf("We went to the shop", "shop was closed")), splitter(Piece(0..15, forcedEnd = true), Piece(11..29)))
        assertEquals("We went to the shop shop was closed", (noRepeat.transcribe(thirty) as TranscribeResult.Ok).text)
    }

    @Test fun aFailureInPieceTwoOfThreeFailsTheWholeJob() = runBlocking {
        val inner = FakeInner(listOf("one", "two", "three"), failOn = 2)
        val r = ChunkedTranscriber(inner, splitter(Piece(0..9), Piece(10..19), Piece(20..29))).transcribe(thirty)
        assertEquals(TranscribeResult.Failed(TranscribeFailure.OUT_OF_MEMORY, retryable = true), r)
        assertEquals("piece three is never started", 2, inner.received.size)
    }

    @Test fun aNoteThatIsOnePieceGoesToTheEngineUnchanged() = runBlocking {
        val inner = FakeInner(listOf("exactly as before"))
        val pcm = Sound().tone(8_400).pcm()
        val r = ChunkedTranscriber(inner).transcribe(pcm)
        assertEquals(TranscribeResult.Ok("exactly as before", 10, 1, null), r)
        assertSame(pcm, inner.received.single())
        assertTrue(events.none { DbgCode.SPLIT.name in it })
    }

    @Test fun withTheRealSplitterChunksIsThePieceCountAndEverySampleIsHeard() = runBlocking {
        val pcm = speechWithPausesEvery6s().pcm()
        val pieces = PauseSplitter.split(pcm)
        val inner = FakeInner(List(pieces.size) { "w$it" })
        val r = ChunkedTranscriber(inner).transcribe(pcm) as TranscribeResult.Ok
        assertEquals(pieces.size, r.chunks)
        assertEquals(List(pieces.size) { "w$it" }.joinToString(" "), r.text)
        assertEquals(pcm.samples.toList(), inner.received.flatMap { it.samples.toList() })
    }

    // ------------------------------------------------------------------ threads and the pack policy in the adapter

    private lateinit var root: File
    private val engineThread = Executors.newSingleThreadExecutor { Thread(it, "test-transcriber") }

    @Before fun makeRoot() { root = Files.createTempDirectory("packs").toFile() }
    @After fun cleanUp() { engineThread.shutdownNow(); root.deleteRecursively() }

    private fun pack(id: String, ws: Long) = KnownPack(
        id, id, "licence", PackFile("encoder_model.ort", 3, "a"), PackFile("decoder_model_merged.ort", 4, "b"),
        PackFile("tokens.txt", 2, "c"), PackFile("LICENSE", 1, "d"), ws,
    )

    private val base = pack("t-base", 300)
    private val tiny = pack("t-tiny", 200)

    /** Puts a pack on "disk" as the importer leaves it: the files at their sizes and the marker. */
    private fun install(p: KnownPack) {
        val dir = File(root, p.id).apply { mkdirs() }
        for (f in p.files) File(dir, f.name).writeBytes(ByteArray(f.bytes.toInt()))
        File(dir, PackMarker.NAME).writeText(PackMarker.text(p))
    }

    private class Runtime : SpeechRuntime {
        override val engineId = "rt"
        val opens = mutableListOf<String>()
        override fun loadLibrary() {}
        override fun open(pack: InstalledPack, threads: Int): LoadedModel {
            opens += "${pack.pack.id}@$threads"
            return object : LoadedModel {
                override fun decode(samples16k: FloatArray) = pack.pack.id
                override fun release() {}
            }
        }
    }

    private fun adapter(rt: Runtime, policy: PackPolicy, mem: Long? = null) = PackTranscriber(
        ModelLocator(root, null, listOf(base, tiny)), rt, threads = 2, availableMemory = { mem },
        isMainThread = { false }, executor = engineThread, policy = policy,
    )

    @Test fun theCallerSaysHowManyThreadsOneInTheBackgroundTwoOnScreen() = runBlocking {
        install(base)
        val rt = Runtime()
        val t = adapter(rt, PackPolicy(listOf(base.id, tiny.id)))
        val one = Pcm(ShortArray(16))
        t.transcribe(one, TranscribeOptions(place = RunPlace.BACKGROUND))
        t.transcribe(one, TranscribeOptions(place = RunPlace.BACKGROUND))
        assertEquals(listOf("t-base@1"), rt.opens)
        t.transcribe(one, TranscribeOptions(place = RunPlace.ON_SCREEN))
        t.transcribe(one) // no place given: the adapter's own default, two
        assertEquals(listOf("t-base@1", "t-base@2"), rt.opens)
        assertEquals(1, RunPlace.BACKGROUND.threads)
        assertEquals(2, RunPlace.ON_SCREEN.threads)
    }

    @Test fun outcomeM2SendsLongNotesToTinyAndLogsWhenTinyIsMissing() = runBlocking {
        install(base); install(tiny)
        val m2 = PackPolicy(listOf(base.id, tiny.id), longNoteFromMs = 60_000, longNotePackId = tiny.id)
        val rt = Runtime()
        val t = adapter(rt, m2)
        val piece = Pcm(ShortArray(16))
        assertEquals("t-base", (t.transcribe(piece, TranscribeOptions(noteMs = 30_000)) as TranscribeResult.Ok).text)
        assertEquals("t-tiny", (t.transcribe(piece, TranscribeOptions(noteMs = 90_000)) as TranscribeResult.Ok).text)
        assertEquals(listOf("t-base@2", "t-tiny@2"), rt.opens)
        t.release()
        File(root, tiny.id).deleteRecursively()
        events.clear()
        assertEquals("t-base", (t.transcribe(piece, TranscribeOptions(noteMs = 90_000)) as TranscribeResult.Ok).text)
        assertTrue(events.toString(), events.any { it.startsWith("TRANSCRIBE ${DbgCode.NO_LONG_NOTE_PACK} 90000") })
    }

    @Test fun thePolicyOrderDecidesWhichInstalledPackLoadsAndMemoryStillDecidesIfItFits() {
        val both = listOf(InstalledPack(base, PackLocation.FILES, "b"), InstalledPack(tiny, PackLocation.FILES, "t"))
        val tinyFirst = PackPolicy(listOf(tiny.id, base.id))
        assertEquals(tiny, PackChoice.choose(both, null, 10_000, tinyFirst)!!.pack.pack)
        assertEquals(base, PackChoice.choose(both, null, 10_000, PackPolicy(listOf(base.id)))!!.pack.pack)
        assertEquals("unknown ids keep their own order", base, PackChoice.choose(both, null, 10_000, PackPolicy(emptyList()))!!.pack.pack)
        val m2 = PackPolicy(listOf(base.id, tiny.id), 60_000, tiny.id)
        assertEquals(tiny, PackChoice.choose(both, 1_000, 61_000, m2)!!.pack.pack)
        assertEquals(base, PackChoice.choose(both, 1_000, 60_000, m2)!!.pack.pack)
        assertEquals(tiny, PackChoice.choose(both, 250, 10_000, m2)!!.pack.pack)
        val baseOnly = PackChoice.choose(both.take(1), 1_000, 61_000, m2)!!
        assertEquals(base, baseOnly.pack.pack)
        assertTrue(baseOnly.longNoteWithoutItsPack)
        assertNull(PackChoice.choose(both, 100, 10_000, m2))
    }

    // ------------------------------------------------------------------ the base-or-tiny rule

    private fun runs(vararg r: Pair<Long, Int>) = r.map { MemoryRun(it.first, it.second) }

    @Test fun theRuleNamesTheCardsOutcomes() {
        // Every length inside "about 400 MB" and no more than two extra words: M1, base first, no length limit.
        val m1 = DefaultPackRule.decide(runs(27_700L to 380, 83_100L to 395, 180_000L to 405), extraWrongWordsWhenCut = 2, hardLine = false)
        assertEquals(PackOutcome.M1, m1.outcome)
        assertNull(m1.baseUpToMs)
        assertEquals(listOf(KnownModels.BASE, KnownModels.TINY), m1.order)
        // Base passes up to 83 s and misses at three minutes: M2 with L = 83 s, long notes to tiny.
        val m2 = DefaultPackRule.decide(runs(180_000L to 450, 27_700L to 380, 83_100L to 395), extraWrongWordsWhenCut = 0, hardLine = false)
        assertEquals(PackOutcome.M2, m2.outcome)
        assertEquals(83_100L, m2.baseUpToMs)
        assertEquals(PackPolicy(listOf(KnownModels.BASE.id, KnownModels.TINY.id), 83_100L, KnownModels.TINY.id), m2.policy)
        // Misses even on the shortest: M3, tiny first.
        val m3 = DefaultPackRule.decide(runs(8_400L to 430, 27_700L to 450), extraWrongWordsWhenCut = 0, hardLine = false)
        assertEquals(PackOutcome.M3, m3.outcome)
        assertEquals(listOf(KnownModels.TINY, KnownModels.BASE), m3.order)
        // Memory fine but the cut costs three words: base only for notes it never cuts.
        val words = DefaultPackRule.decide(runs(27_700L to 380, 180_000L to 390), extraWrongWordsWhenCut = 3, hardLine = false)
        assertEquals(PackOutcome.M2, words.outcome)
        assertEquals(PauseSplitter.MAX_MS, words.baseUpToMs)
    }

    @Test fun guideOrHardLineIsOneConstantAndCardTwentyFivesFiguresLandWhereTheLogPutThem() {
        assertEquals(400, MemoryGuide.GUIDE_MB)
        assertEquals(410, MemoryGuide.limitMb(hardLine = false))
        assertEquals(400, MemoryGuide.limitMb(hardLine = true))
        assertEquals(MemoryGuide.limitMb(MemoryGuide.HARD_LINE), MemoryGuide.limitMb())
        // Card 25, base uncut in a background job: 401 MB at 8.4 s ("on the bar"), 418 MB at 27.7 s ("misses by 5%").
        val card25 = runs(8_400L to 401, 27_700L to 418)
        val guide = DefaultPackRule.decide(card25, extraWrongWordsWhenCut = 0, hardLine = false)
        assertEquals(PackOutcome.M2, guide.outcome)
        assertEquals(8_400L, guide.baseUpToMs)
        assertEquals("a hard line makes 401 MB a miss on a short note", PackOutcome.M3, DefaultPackRule.decide(card25, 0, hardLine = true).outcome)
    }

    @Test fun untilMeasuredTheAppRunsAsCard26dDidBaseFirstNoLengthLimit() {
        assertNull("not measured yet: set after the phone run", DefaultPack.MEASURED)
        assertEquals(listOf(KnownModels.BASE, KnownModels.TINY), DefaultPack.DEFAULT_PACK_ORDER)
        assertNull(DefaultPack.BASE_UP_TO_MS)
        assertEquals(PackPolicy(listOf(KnownModels.BASE.id, KnownModels.TINY.id)), DefaultPack.POLICY)
        assertEquals("the M1 decision is the policy in force today", DefaultPack.POLICY, DefaultPackRule.decide(runs(10_000L to 300), 0).policy)
        assertEquals(AudioFormatConstants.MAX_NOTE_MS, 180_000L)
    }
}
