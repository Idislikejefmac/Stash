package app.stash

import app.stash.audio.Decoder
import app.stash.capture.DirectSink
import app.stash.capture.InboundSink
import app.stash.capture.RecordingSaver
import app.stash.data.CaptureEntity
import app.stash.data.ExtractState
import app.stash.data.Repository
import app.stash.data.StashDatabase
import app.stash.data.Store
import app.stash.data.TranscribeState
import app.stash.data.TranscriptionRepository
import app.stash.data.TranscriptionStatus
import app.stash.domain.AudioFormat
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.EncodedAudio
import app.stash.domain.Outbound
import app.stash.domain.OutboundGate
import app.stash.domain.Pcm
import app.stash.domain.SendableText
import app.stash.model.ExtractContext
import app.stash.model.ExtractResult
import app.stash.model.Extractor
import app.stash.model.hosted.GatedPoster
import app.stash.model.rules.RulesExtractor
import app.stash.pipeline.ExtractionPipeline
import app.stash.pipeline.ForegroundTracker
import app.stash.pipeline.MemoryRunMarkerStore
import app.stash.pipeline.PassWhere
import app.stash.pipeline.RunMarker
import app.stash.pipeline.TranscribeNotice
import app.stash.pipeline.TranscriptionJobLogic
import app.stash.pipeline.TranscriptionPassResult
import app.stash.pipeline.TranscriptionPipeline
import app.stash.pipeline.TranscriptionPolicy
import app.stash.pipeline.TranscriptionScheduler
import app.stash.pipeline.TranscriptionWork
import app.stash.pipeline.TranscriptionWorkShape
import app.stash.security.AttachmentStore
import app.stash.speech.Availability
import app.stash.speech.ImportResult
import app.stash.speech.ModelImporter
import app.stash.speech.RunPlace
import app.stash.speech.TranscribeFailure
import app.stash.speech.TranscribeOptions
import app.stash.speech.TranscribeResult
import app.stash.speech.Transcriber
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Random

/**
 * Card 26f with real Room and real encrypted files: the transcription pass over saved recordings, with a fake
 * transcriber and a fake decoder. Every unit check of the card has a test here or in [TranscriptionPolicyTest].
 */
@RunWith(RobolectricTestRunner::class)
class TranscriptionJobTest {
    /** Answers from a script (default: [words]); counts how often it was entered and released. */
    private class FakeTranscriber : Transcriber {
        var availability: Availability = Availability.Ready
        val script = ArrayDeque<TranscribeResult>()
        var words = "sid has the spare key in the frunk"
        @Volatile var entered = 0
        var released = 0
        val places = mutableListOf<RunPlace?>()
        var hold: CompletableDeferred<Unit>? = null
        var onEnter: (suspend () -> Unit)? = null
        override val id = "fake-engine"
        override fun availability() = availability
        override suspend fun transcribe(audio: Pcm): TranscribeResult = transcribe(audio, TranscribeOptions())
        override suspend fun transcribe(audio: Pcm, options: TranscribeOptions): TranscribeResult {
            entered++
            places += options.place
            onEnter?.invoke()
            hold?.await()
            return script.removeFirstOrNull() ?: TranscribeResult.Ok(words, 120, 1, null)
        }
        override fun release() { released++ }
    }

    /** Bytes that start with "BAD!" cannot be decoded; anything else is one second of silence per 1,000 bytes. */
    private class FakeDecoder : Decoder {
        override fun decode(bytes: ByteArray, mime: String): Pcm {
            if (bytes.size >= 4 && String(bytes, 0, 4) == "BAD!") throw IllegalArgumentException("not audio")
            return Pcm(ShortArray(16 * bytes.size))
        }
    }

    private lateinit var db: StashDatabase
    private lateinit var dir: File
    private lateinit var attStore: AttachmentStore
    private val clock = FakeClock(System.currentTimeMillis())
    private lateinit var repo: Repository
    private var store: () -> Store = { Store.Ready(db) }
    private val engine = FakeTranscriber()
    private val markers = MemoryRunMarkerStore()
    private val lock = Mutex()
    private var extractionAsked = 0
    private var compactCalls = 0
    private var compactResult: (ByteArray) -> EncodedAudio? = { EncodedAudio("OggS-small".toByteArray(), AudioFormat.OPUS_12.mime, 1_000, AudioFormat.OPUS_12) }
    private val events = mutableListOf<Triple<DbgComponent, DbgCode, Pair<Long?, Long?>>>()
    private val afterRecording = mutableListOf<String>()

    private fun pipeline(token: String = "this-process") = TranscriptionPipeline(
        store = { store() },
        parts = { TranscriptionPipeline.Parts(repo.transcriptions, repo.attachments, repo.recordings) },
        transcriber = { engine },
        decoder = FakeDecoder(),
        compact = { compactCalls++; compactResult(it) },
        askExtraction = { extractionAsked++ },
        markers = markers, clock = clock, lock = lock, processToken = token,
    )

    private val p by lazy { pipeline() }

    @Before fun setUp() {
        db = TestSupport.memoryDb()
        dir = TestSupport.tempDir()
        attStore = AttachmentStore(dir)
        repo = TestSupport.repo(db, clock, attStore)
        Dbg.sink = app.stash.domain.EventSink { c, code, n1, n2, _, _ -> synchronized(events) { events += Triple(c, code, n1 to n2) } }
    }

    @After fun tearDown() {
        Dbg.sink = null
        db.close()
        dir.deleteRecursively()
    }

    private val saver by lazy {
        val sink = DirectSink(repo.captures, { emptyList() }, afterSave = {}, attachments = repo.attachments, afterRecordingSave = { afterRecording += it })
        RecordingSaver(attStore, sink, { null }, "test", clock)
    }

    private fun opus(seed: Long = 1) = EncodedAudio(ByteArray(4_000).also { Random(seed).nextBytes(it); it[0] = 'O'.code.toByte() }, AudioFormat.OPUS_12.mime, 4_000, AudioFormat.OPUS_12)

    private suspend fun record(private: Boolean = false, a: EncodedAudio = opus()): CaptureEntity {
        clock.advance(1_000)
        val r = saver.save(a, capturedLocked = false, markPrivate = private)
        assertTrue(r.toString(), r is InboundSink.Result.Saved)
        return (r as InboundSink.Result.Saved).capture
    }

    private suspend fun now(c: CaptureEntity) = db.captures().get(c.id)!!
    private suspend fun rows(c: CaptureEntity) = db.transcriptions().forCapture(c.id)
    private suspend fun audioRow(c: CaptureEntity) = db.attachments().audioForCapture(c.id).single()
    private suspend fun bytes(c: CaptureEntity) = repo.attachments.openRecording(audioRow(c).id).use { it.readBytes() }
    private fun count(code: DbgCode) = synchronized(events) { events.count { it.first == DbgComponent.TRANSCRIBE && it.second == code } }

    // ------------------------------------------------------------------ the pass

    @Test fun aSavedRecordingIsWrittenOutCorrectedAndHandedToTheExtractionPipelineOnce() = runBlocking {
        repo.putCorrection("sid", "Syd")
        val c = record()
        assertEquals(listOf(c.id), afterRecording) // 26c's hook fired; Container wires it to requestNow()
        val r = p.runPass(PassWhere.FOREGROUND)
        assertEquals(1, r.written); assertTrue(r.engineUsed); assertNull(r.nextDelayMs)
        val after = now(c)
        assertEquals(TranscribeState.DONE, after.transcribeState)
        assertEquals("sid has the spare key in the frunk", after.rawText)
        assertEquals("Syd has the spare key in the frunk", after.text)
        assertEquals(ExtractState.PENDING, after.extractState)
        assertEquals(1, extractionAsked)
        assertEquals(TranscriptionStatus.CURRENT, rows(c).single().status)
        assertEquals(4_000L, rows(c).single().audioMs)
        assertEquals(listOf<RunPlace?>(RunPlace.ON_SCREEN), engine.places)
        assertNull(markers.marker) // the run marker is cleared after the call
        // the extraction pipeline now reads it like any typed or dictated note
        assertEquals(listOf(c.id), repo.captures.toRead().map { it.id })
        // a second pass has nothing to do
        assertEquals(0, p.runPass(PassWhere.FOREGROUND).written)
        assertEquals(1, engine.entered); assertEquals(1, extractionAsked)
        assertEquals(1, count(DbgCode.STARTED)); assertEquals(1, count(DbgCode.OK))
    }

    @Test fun aBackgroundPassUsesOneThread() = runBlocking {
        record()
        p.runPass(PassWhere.BACKGROUND)
        assertEquals(listOf<RunPlace?>(RunPlace.BACKGROUND), engine.places)
        assertTrue(events.any { it.second == DbgCode.STARTED && it.third.second == 1L })
    }

    @Test fun aRetryableFailureWaitsItsBackOffThenSucceeds() = runBlocking {
        val c = record()
        engine.script += TranscribeResult.Failed(TranscribeFailure.OUT_OF_MEMORY, retryable = true)
        val r = p.runPass(PassWhere.BACKGROUND)
        assertEquals(TranscribeState.RETRY, now(c).transcribeState); assertEquals(1, now(c).transcribeAttempts)
        assertEquals(60_000L, r.nextDelayMs)
        assertEquals("OUT_OF_MEMORY", rows(c).single().error)
        assertEquals(0, extractionAsked)
        // not retried before the back-off
        clock.advance(59_000)
        p.runPass(PassWhere.BACKGROUND)
        assertEquals(1, engine.entered)
        clock.advance(1_000)
        p.runPass(PassWhere.BACKGROUND)
        assertEquals(2, engine.entered)
        assertEquals(TranscribeState.DONE, now(c).transcribeState)
        assertEquals(1, extractionAsked)
    }

    @Test fun fiveRetryableFailuresGiveUpAndTheRecordingIsStillThere() = runBlocking {
        val c = record()
        val before = bytes(c)
        repeat(5) {
            engine.script += TranscribeResult.Failed(TranscribeFailure.ENGINE_ERROR, retryable = true)
            p.runPass(PassWhere.BACKGROUND)
            clock.advance(25 * 3_600_000L)
        }
        assertEquals(TranscribeState.GAVE_UP, now(c).transcribeState); assertEquals(5, now(c).transcribeAttempts)
        assertEquals(5, rows(c).count { it.status == TranscriptionStatus.FAILED })
        assertEquals(1, count(DbgCode.GAVE_UP))
        // GAVE_UP deletes nothing
        assertArrayEquals(before, bytes(c))
        assertTrue(File(dir, audioRow(c).fileName).isFile)
        // and is never tried again by itself
        p.runPass(PassWhere.FOREGROUND)
        assertEquals(5, engine.entered)
        assertEquals(TranscribeNotice.GAVE_UP, TranscribeNotice.line(now(c).transcribeState, now(c).text.isNotBlank(), true))
    }

    @Test fun aFailureThatCannotBeRetriedGivesUpAtOnce() = runBlocking {
        val c = record()
        engine.script += TranscribeResult.Failed(TranscribeFailure.MODEL_CORRUPT, retryable = false)
        p.runPass(PassWhere.FOREGROUND)
        assertEquals(TranscribeState.GAVE_UP, now(c).transcribeState); assertEquals(1, now(c).transcribeAttempts)
        assertTrue(File(dir, audioRow(c).fileName).isFile)
    }

    @Test fun withNoModelTheNoteStaysPendingWithNoAttemptAndIsWrittenOutOnceAModelArrives() = runBlocking {
        engine.availability = Availability.NoModel
        val c = record()
        val r1 = p.runPass(PassWhere.FOREGROUND)
        val r2 = p.runPass(PassWhere.BACKGROUND)
        assertTrue(r1.modelMissing); assertTrue(r2.modelMissing)
        assertNull(r1.nextDelayMs) // nothing is retried in a loop: no reschedule
        assertEquals(TranscribeState.PENDING, now(c).transcribeState); assertEquals(0, now(c).transcribeAttempts)
        assertTrue(rows(c).isEmpty())
        assertEquals(0, engine.entered); assertEquals(0, engine.released)
        assertEquals(2, count(DbgCode.MODEL_MISSING)) // once per pass
        assertEquals(TranscribeNotice.NEEDS_MODEL, TranscribeNotice.line(now(c).transcribeState, false, modelReady = false))
        assertTrue(bytes(c).isNotEmpty()) // never lost
        // A model pack is imported: ModelImporter asks for a pass (requestNow), here with a screen up.
        engine.availability = Availability.Ready
        val work = CountingWork()
        val tracker = ForegroundTracker().apply { started() }
        TranscriptionScheduler(tracker, { block -> runBlocking { block() } }, work, { p }).requestNow()
        assertEquals(TranscribeState.DONE, now(c).transcribeState)
        assertEquals(1, work.now)
    }

    @Test fun aModelThatVanishesMidPassCountsNoAttempt() = runBlocking {
        val c = record()
        engine.script += TranscribeResult.Failed(TranscribeFailure.MODEL_MISSING, retryable = false)
        val r = p.runPass(PassWhere.FOREGROUND)
        assertTrue(r.modelMissing)
        assertEquals(TranscribeState.PENDING, now(c).transcribeState); assertEquals(0, now(c).transcribeAttempts)
        assertTrue(rows(c).isEmpty())
    }

    @Test fun anUnreadableOrUndecodableFileGivesUpAtOnceAndTheOthersAreStillDone() = runBlocking {
        val broken = record()
        val bad = record(a = EncodedAudio("BAD!not-audio".toByteArray(), AudioFormat.OPUS_12.mime, 1_000, AudioFormat.OPUS_12))
        val good = record(a = opus(2))
        // damage the first one's encrypted file
        File(dir, audioRow(broken).fileName).writeBytes(ByteArray(64) { 7 })
        p.runPass(PassWhere.BACKGROUND)
        assertEquals(TranscribeState.GAVE_UP, now(broken).transcribeState)
        assertEquals(TranscriptionPolicy.UNREADABLE, rows(broken).single().error)
        assertEquals(TranscribeState.GAVE_UP, now(bad).transcribeState)
        assertEquals(TranscriptionPolicy.UNDECODABLE, rows(bad).single().error)
        assertEquals(TranscribeState.DONE, now(good).transcribeState)
        assertEquals(1, engine.entered)
        // nothing was deleted: rows and files stay for "Try again" and the consistency check
        assertTrue(File(dir, audioRow(broken).fileName).isFile)
        assertTrue(File(dir, audioRow(bad).fileName).isFile)
    }

    @Test fun aNoteInTheBinIsNotWrittenOut() = runBlocking {
        val c = record()
        repo.deleteCapture(c.id)
        p.runPass(PassWhere.FOREGROUND)
        assertEquals(0, engine.entered)
        assertEquals(TranscribeState.PENDING, now(c).transcribeState)
    }

    @Test fun aRecordingSavedDuringAPassIsWrittenOutInTheSamePass() = runBlocking {
        record()
        var second: CaptureEntity? = null
        engine.onEnter = { if (second == null) second = record(a = opus(9)) }
        val r = p.runPass(PassWhere.FOREGROUND)
        assertEquals(2, r.written)
        assertEquals(TranscribeState.DONE, now(second!!).transcribeState)
    }

    // ------------------------------------------------------------------ kills

    @Test fun aRunMarkerLeftByADeadBackgroundRunIsAKillTwiceThenOnlyAForegroundPassTriesIt() = runBlocking {
        val c = record()
        markers.write(RunMarker(c.id, clock.now(), PassWhere.BACKGROUND, "a-dead-process"))
        p.runPass(PassWhere.BACKGROUND)
        assertEquals(TranscriptionPolicy.KILLED_BG, rows(c).single().error)
        assertEquals(TranscribeState.RETRY, now(c).transcribeState); assertEquals(1, now(c).transcribeAttempts)
        assertEquals(1, count(DbgCode.KILLED)); assertNull(markers.marker)
        assertEquals(0, engine.entered) // waits its back-off
        // killed again in the background
        clock.advance(61_000)
        markers.write(RunMarker(c.id, clock.now(), PassWhere.BACKGROUND, "another-dead-process"))
        p.runPass(PassWhere.BACKGROUND)
        assertEquals(2, rows(c).count { it.error == TranscriptionPolicy.KILLED_BG }); assertEquals(2, now(c).transcribeAttempts)
        // after the back-off, a background pass leaves it for the foreground
        clock.advance(11 * 60_000L)
        val bg = p.runPass(PassWhere.BACKGROUND)
        assertEquals(1, bg.leftForForeground); assertEquals(0, engine.entered)
        assertEquals(TranscribeState.RETRY, now(c).transcribeState)
        p.runPass(PassWhere.FOREGROUND)
        assertEquals(1, engine.entered)
        assertEquals(TranscribeState.DONE, now(c).transcribeState)
    }

    @Test fun twoForegroundKillsGiveUpTooLongForThisPhoneAndKeepTheRecording() = runBlocking {
        val c = record()
        markers.write(RunMarker(c.id, clock.now(), PassWhere.FOREGROUND, "dead-1"))
        p.runPass(PassWhere.FOREGROUND)
        assertEquals(TranscribeState.RETRY, now(c).transcribeState)
        clock.advance(61_000)
        markers.write(RunMarker(c.id, clock.now(), PassWhere.FOREGROUND, "dead-2"))
        p.runPass(PassWhere.FOREGROUND)
        assertEquals(TranscribeState.GAVE_UP, now(c).transcribeState); assertEquals(2, now(c).transcribeAttempts)
        assertEquals(listOf(TranscriptionPolicy.KILLED_FG, TranscriptionPolicy.KILLED_FG), rows(c).map { it.error })
        assertEquals(0, engine.entered)
        assertTrue(bytes(c).isNotEmpty())
        // "Try again" starts from zero
        assertEquals(TranscriptionRepository.AskAgain.WAITING_AGAIN, repo.transcriptions.askAgain(c.id))
        p.runPass(PassWhere.FOREGROUND)
        assertEquals(TranscribeState.DONE, now(c).transcribeState)
    }

    @Test fun aMarkerThisProcessLeftIsNotAKill() = runBlocking {
        val c = record()
        markers.write(RunMarker(c.id, clock.now(), PassWhere.FOREGROUND, "this-process"))
        p.runPass(PassWhere.FOREGROUND)
        assertEquals(0, count(DbgCode.KILLED))
        assertEquals(TranscribeState.DONE, now(c).transcribeState); assertEquals(0, now(c).transcribeAttempts)
    }

    @Test fun theMarkerIsOnDiskWhileTheEngineRuns() = runBlocking {
        val c = record()
        var seen: RunMarker? = null
        engine.onEnter = { seen = markers.marker }
        p.runPass(PassWhere.BACKGROUND)
        assertEquals(c.id, seen!!.captureId); assertEquals(PassWhere.BACKGROUND, seen!!.where); assertEquals("this-process", seen!!.processToken)
        assertNull(markers.marker)
    }

    // ------------------------------------------------------------------ "again"

    @Test fun againOnADoneNoteMakesAnOfferAndAgainOnGaveUpWritesItOut() = runBlocking {
        val done = record()
        p.runPass(PassWhere.FOREGROUND)
        val text = now(done).text
        assertEquals(TranscriptionRepository.AskAgain.OFFER_NEXT, repo.transcriptions.askAgain(done.id))
        engine.words = "Syd has the spare key in the trunk"
        p.runPass(PassWhere.FOREGROUND)
        assertEquals(TranscriptionStatus.OFFERED, rows(done).first().status)
        assertEquals(text, now(done).text)
        assertEquals(1, extractionAsked) // only the first writing-out asked for a reading
        assertFalse(repo.transcriptions.wantsNewOffer(done.id))

        val gaveUp = record(a = opus(5))
        engine.script += TranscribeResult.Failed(TranscribeFailure.MODEL_CORRUPT, retryable = false)
        p.runPass(PassWhere.FOREGROUND)
        assertEquals(TranscribeState.GAVE_UP, now(gaveUp).transcribeState)
        assertEquals(TranscriptionRepository.AskAgain.WAITING_AGAIN, repo.transcriptions.askAgain(gaveUp.id))
        assertEquals(TranscribeState.PENDING, now(gaveUp).transcribeState)
        p.runPass(PassWhere.FOREGROUND)
        assertEquals(TranscribeState.DONE, now(gaveUp).transcribeState)
    }

    // ------------------------------------------------------------------ one at a time

    @Test fun twoCallersAtOnceEnterTheTranscriberOnce() = runBlocking {
        record()
        val gate = CompletableDeferred<Unit>()
        engine.hold = gate
        val first = async(Dispatchers.Default) { pipeline().runPass(PassWhere.FOREGROUND) }
        while (engine.entered == 0) Thread.sleep(5)
        val second = pipeline().runPass(PassWhere.BACKGROUND) // another instance, the same process-wide lock
        assertTrue(second.alreadyRunning)
        gate.complete(Unit)
        assertEquals(1, first.await().written)
        assertEquals(1, engine.entered)
    }

    @Test fun theJobRetriesWhenTheStoreIsNotAvailableAndChangesNothing() = runBlocking {
        val c = record()
        store = { Store.NotAvailable }
        val later = mutableListOf<Long>()
        assertEquals(TranscriptionJobLogic.Outcome.RETRY, TranscriptionJobLogic.run({ p.runPass(PassWhere.BACKGROUND) }, { later += it }))
        assertTrue(later.isEmpty())
        assertEquals(0, engine.entered)
        store = { Store.Ready(db) }
        assertEquals(TranscribeState.PENDING, now(c).transcribeState); assertEquals(0, now(c).transcribeAttempts)
        assertTrue(rows(c).isEmpty())
    }

    @Test fun theJobLooksAgainLaterWhenAPassIsAlreadyRunningAndReschedulesForTheBackOff() = runBlocking {
        val later = mutableListOf<Long>()
        assertEquals(TranscriptionJobLogic.Outcome.SUCCESS, TranscriptionJobLogic.run({ TranscriptionPassResult(alreadyRunning = true) }, { later += it }))
        assertEquals(listOf(TranscriptionWorkShape.ALREADY_RUNNING_RECHECK_MS), later)
        later.clear()
        assertEquals(TranscriptionJobLogic.Outcome.SUCCESS, TranscriptionJobLogic.run({ TranscriptionPassResult(nextDelayMs = 600_000) }, { later += it }))
        assertEquals(listOf(600_000L), later)
    }

    private class CountingWork : TranscriptionWork {
        var now = 0
        val later = mutableListOf<Long>()
        override fun enqueueNow() { now++ }
        override fun enqueueLater(delayMs: Long) { later += delayMs }
    }

    @Test fun requestNowAlwaysEnqueuesTheJobAndRunsInTheForegroundOnlyWhileAScreenIsUp() = runBlocking {
        val c = record()
        val work = CountingWork()
        val tracker = ForegroundTracker()
        var launched = 0
        val s = TranscriptionScheduler(tracker, { block -> launched++; runBlocking { block() } }, work, { p })
        s.requestNow()
        assertEquals(0, launched); assertEquals(1, work.now)
        assertEquals(TranscribeState.PENDING, now(c).transcribeState) // left to the job
        tracker.started()
        engine.script += TranscribeResult.Failed(TranscribeFailure.OUT_OF_MEMORY, retryable = true)
        s.requestNow()
        assertEquals(1, launched); assertEquals(2, work.now)
        assertEquals(listOf(60_000L), work.later) // the foreground pass's back-off goes to the later job
    }

    // ------------------------------------------------------------------ Private

    @Test fun aPrivateRecordingIsWrittenOutLikeAnyOtherAndNothingIsSent() = runBlocking {
        val permits = mutableListOf<Outbound>()
        val gate = OutboundGate { permits += it }
        val spy = object : Extractor {
            override val name = "spy-model"
            override suspend fun extract(text: SendableText, context: ExtractContext): ExtractResult {
                GatedPoster(gate) { _, _, _, _ -> error("no network in tests") }.post(Outbound.EXTRACT_NOTE, "https://example.invalid", emptyMap(), text.value)
                return ExtractResult.Failed("unreachable", retryable = true)
            }
        }
        val marked = record(private = true)
        p.runPass(PassWhere.FOREGROUND)
        assertTrue(now(marked).sensitive)
        assertEquals(TranscribeState.DONE, now(marked).transcribeState)
        assertTrue(now(marked).text.isNotBlank())
        // words that make a note Private do so, and the spoken marker leaves the text
        engine.words = "private note the gate code is 4471"
        val said = record(a = opus(3))
        p.runPass(PassWhere.FOREGROUND)
        assertTrue(now(said).sensitive)
        assertEquals("the gate code is 4471", now(said).text)
        assertEquals("private note the gate code is 4471", now(said).rawText)

        ExtractionPipeline(
            store = { Store.Ready(db) }, rules = RulesExtractor(), hostedExtractor = { spy }, hostedOn = { true },
            hasKey = { true }, networkAvailable = { true }, clock = clock,
        ).runPass()
        assertEquals(emptyList<Outbound>(), permits)
        // and the spy works: a note that is not Private would have reached the gate
        engine.words = "the furnace filter is 20 by 25 by 1"
        record(a = opus(4))
        p.runPass(PassWhere.FOREGROUND)
        ExtractionPipeline(
            store = { Store.Ready(db) }, rules = RulesExtractor(), hostedExtractor = { spy }, hostedOn = { true },
            hasKey = { true }, networkAvailable = { true }, clock = clock,
        ).runPass()
        assertEquals(listOf(Outbound.EXTRACT_NOTE), permits)
    }

    // ------------------------------------------------------------------ release and compaction

    @Test fun theEngineIsReleasedAfterAPassThatTranscribedAndNotAfterOneThatDidNothing() = runBlocking {
        p.runPass(PassWhere.FOREGROUND)
        assertEquals(0, engine.released)
        record()
        p.runPass(PassWhere.FOREGROUND)
        assertEquals(1, engine.released)
        p.runPass(PassWhere.FOREGROUND)
        assertEquals(1, engine.released)
    }

    @Test fun aWavRecordingIsCompactedAfterThePassAndItsBytesReplaced() = runBlocking {
        val wav = EncodedAudio(app.stash.domain.Wav.encode(ShortArray(16_000), 16_000), AudioFormat.WAV.mime, 1_000, AudioFormat.WAV)
        val c = record(a = wav)
        val oldFile = audioRow(c).fileName
        p.runPass(PassWhere.BACKGROUND)
        assertEquals(TranscribeState.DONE, now(c).transcribeState)
        assertEquals(1, compactCalls)
        assertEquals(AudioFormat.OPUS_12.mime, audioRow(c).mime)
        assertArrayEquals("OggS-small".toByteArray(), bytes(c))
        assertFalse(File(dir, oldFile).exists())
        p.runPass(PassWhere.BACKGROUND)
        assertEquals(1, compactCalls) // nothing left to compact
    }

    @Test fun aWavThatCannotBeCompactedIsKeptAndNotTriedInALoop() = runBlocking {
        compactResult = { null }
        val wav = EncodedAudio(app.stash.domain.Wav.encode(ShortArray(16_000), 16_000), AudioFormat.WAV.mime, 1_000, AudioFormat.WAV)
        val c = record(a = wav)
        val before = bytes(c)
        p.runPass(PassWhere.BACKGROUND); p.runPass(PassWhere.BACKGROUND)
        assertEquals(1, compactCalls)
        assertEquals(AudioFormat.WAV.mime, audioRow(c).mime)
        assertArrayEquals(before, bytes(c))
    }

    // ------------------------------------------------------------------ Settings' numbers, the model hook

    @Test fun theSummaryCountsWaitingAndGivenUpAndTheLastDelay() = runBlocking {
        val a = record(); record(a = opus(2))
        engine.availability = Availability.NoModel
        p.runPass(PassWhere.FOREGROUND)
        val d = db.transcriptions()
        assertEquals(2, d.countInState("PENDING")); assertNull(d.lastFirstDelayMs())
        engine.availability = Availability.Ready
        engine.script += TranscribeResult.Failed(TranscribeFailure.MODEL_CORRUPT, retryable = false)
        clock.advance(5_000)
        p.runPass(PassWhere.FOREGROUND)
        assertEquals(1, d.countInState("GAVE_UP")); assertEquals(0, d.countInState("PENDING"))
        // the second note: saved 1 s after the first, written out 5 s after that
        assertEquals(5_000L, d.lastFirstDelayMs())
        assertEquals(TranscribeState.GAVE_UP, now(a).transcribeState)
    }

    @Test fun theModelImporterAsksForAPassOnlyWhenAPackIsInstalled() {
        var asked = 0
        val importer = ModelImporter(TestSupport.tempDir(), onInstalled = { asked++ })
        val r = importer.import("not a zip".byteInputStream())
        assertTrue(r is ImportResult.Failed)
        assertEquals(0, asked)
    }
}
