package app.stash

import app.stash.TestSupport.rows
import app.stash.capture.DirectSink
import app.stash.capture.Inbound
import app.stash.capture.InboundAttachment
import app.stash.capture.InboundSink
import app.stash.capture.RecordingSaver
import app.stash.data.AttachmentKind
import app.stash.data.AttachmentSource
import app.stash.data.CaptureEntity
import app.stash.data.CaptureSource
import app.stash.data.ExtractState
import app.stash.data.Ids
import app.stash.data.LocationFix
import app.stash.data.RecordingRepository
import app.stash.data.RevisionKind
import app.stash.data.StashDatabase
import app.stash.data.TranscribeState
import app.stash.data.TranscriptionRepository
import app.stash.data.TranscriptionStatus
import app.stash.data.ViolationKind
import app.stash.debug.DumpReceiver
import app.stash.debug.RecordingSelfTest
import app.stash.domain.AudioFormat
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.EncodedAudio
import app.stash.security.AttachmentStore
import app.stash.security.AttachmentUnreadable
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import java.util.Random

/** Card 26c with real Room and real encrypted files: saving a recording, its writing-outs, removing, replacing, purging. */
@RunWith(RobolectricTestRunner::class)
class RecordingStoreTest {
    /** A store whose write or delete can be made to fail, as a full disk or a held file would. */
    private class FlakyStore(dir: File) : AttachmentStore(dir) {
        var failWrites = false
        var failDeletes = 0
        override fun write(fileName: String, key: ByteArray, attachmentId: String, plain: InputStream): Written {
            if (failWrites) throw IOException("disk full")
            return super.write(fileName, key, attachmentId, plain)
        }
        override fun delete(fileName: String): Boolean = if (failDeletes > 0) { failDeletes--; false } else super.delete(fileName)
    }

    private lateinit var db: StashDatabase
    private lateinit var dir: File
    private lateinit var store: FlakyStore
    // Files are stamped by the real file system, so the clock starts at the real time.
    private val clock = FakeClock(System.currentTimeMillis())
    private val repo by lazy { TestSupport.repo(db, clock, store) }
    private val events = mutableListOf<Triple<DbgComponent, DbgCode, Pair<Long?, Long?>>>()
    private val afterSave = mutableListOf<String>()
    private val afterRecording = mutableListOf<String>()
    private val sink by lazy {
        DirectSink(repo.captures, { emptyList() }, afterSave = { afterSave += "x" }, attachments = repo.attachments,
            afterRecordingSave = { afterRecording += it })
    }
    private val saver by lazy { RecordingSaver(store, sink, { LocationFix(33.45, -112.07, 9.0) }, "test", clock) }

    @Before fun setUp() {
        db = TestSupport.memoryDb()
        dir = TestSupport.tempDir()
        store = FlakyStore(dir)
        Dbg.sink = app.stash.domain.EventSink { c, code, n1, n2, _, _ -> synchronized(events) { events += Triple(c, code, n1 to n2) } }
    }

    @After fun tearDown() {
        Dbg.sink = null
        db.close()
        dir.deleteRecursively()
    }

    private fun audio(n: Int = 6_000, seed: Long = 7, ms: Long = 4_200) =
        EncodedAudio(ByteArray(n).also { Random(seed).nextBytes(it) }, "audio/ogg", ms, AudioFormat.OPUS_12)

    private suspend fun record(private: Boolean = false, a: EncodedAudio = audio()): CaptureEntity {
        val r = saver.save(a, capturedLocked = false, markPrivate = private)
        assertTrue(r.toString(), r is InboundSink.Result.Saved)
        return (r as InboundSink.Result.Saved).capture
    }

    /** A recorded note whose first writing-out has arrived. */
    private suspend fun spoken(words: String, private: Boolean = false): CaptureEntity {
        val c = record(private)
        assertEquals(TranscriptionRepository.First.WORDS, repo.transcriptions.applyFirst(c.id, ENGINE, words))
        return repo.captures.get(c.id)!!
    }

    private suspend fun audioRow(captureId: String) = db.attachments().audioForCapture(captureId).single()

    private fun raw() = db.openHelper.writableDatabase

    // ---------- saving ----------

    @Test fun aRecordingIsSavedAsOneWordlessCaptureWithOneAudioAttachment() = runBlocking {
        val a = audio()
        val c = record(a = a)
        val saved = repo.captures.get(c.id)!!
        assertEquals("", saved.rawText); assertEquals("", saved.text)
        assertEquals(TranscribeState.PENDING, saved.transcribeState); assertEquals(0, saved.transcribeAttempts)
        assertEquals(ExtractState.DONE, saved.extractState)
        assertEquals(CaptureSource.VOICE, saved.source); assertFalse(saved.sensitive)
        assertEquals(33.45, saved.lat!!, 0.0); assertEquals(false, saved.capturedLocked)
        // one AUDIO attachment, from the recorder, with its length, and the bytes read back identical
        val row = audioRow(c.id)
        assertEquals(AttachmentKind.AUDIO, row.kind); assertEquals(AttachmentSource.RECORDER, row.source)
        assertEquals("audio/ogg", row.mime); assertEquals(4_200L, row.durationMs); assertNull(row.width); assertNull(row.height)
        assertEquals(a.bytes.size.toLong(), row.byteSize)
        assertEquals(1, db.attachments().forCapture(c.id).size)
        assertArrayEquals(a.bytes, repo.attachments.openRecording(row.id).use { it.readBytes() })
        // the picture door refuses it; it is not a picture anywhere
        try { repo.attachments.openBytes(row.id); fail() } catch (e: AttachmentUnreadable) { assertEquals(AttachmentUnreadable.Reason.WRONG_KIND, e.reason) }
        assertTrue(repo.attachments.pictures(c.id).isEmpty())
        // screens get RecordingInfo only
        val info = repo.recordings.info(c.id)!!
        assertEquals(row.id, info.attachmentId); assertEquals(4_200L, info.durationMs); assertEquals("audio/ogg", info.mime)
        // the extraction pipeline never sees it, and the transcription hook (not the extraction one) was called
        assertTrue(repo.captures.toRead().isEmpty())
        assertEquals(listOf(c.id), afterRecording); assertTrue(afterSave.isEmpty())
        // events: numbers only
        assertTrue(Triple(DbgComponent.CAPTURE, DbgCode.SAVED, 0L to 0L) in events)
        assertTrue(Triple(DbgComponent.AUDIO, DbgCode.STORED, a.bytes.size.toLong() to 4_200L) in events)
        assertTrue(repo.consistency.run().isEmpty())
        assertEquals(1, repo.recordings.totals().count); assertEquals(a.bytes.size.toLong(), repo.recordings.totals().bytes)
        assertEquals(4_200L, repo.recordings.totals().durationMs)
    }

    @Test fun thePrivateButtonMakesARecordingPrivateFromTheStart() = runBlocking {
        val c = record(private = true)
        assertTrue(repo.captures.get(c.id)!!.sensitive)
        assertEquals("SAID", repo.captures.get(c.id)!!.sensitiveReason)
        assertTrue(Triple(DbgComponent.CAPTURE, DbgCode.SAVED, 0L to 1L) in events)
    }

    @Test fun aStoreWhoseWriteFailsLeavesNoRowAndReturnsFailed() = runBlocking {
        store.failWrites = true
        val r = saver.save(audio(), capturedLocked = false, markPrivate = false)
        assertTrue(r is InboundSink.Result.Failed)
        assertTrue(repo.captures.live().isEmpty())
        assertEquals(0, raw().rows("SELECT * FROM attachment").size)
        assertTrue(store.list().isEmpty() && store.temps().isEmpty())
        assertTrue(afterRecording.isEmpty())
    }

    @Test fun aFailedTransactionKeepsTheFileAndReturnsFailedAndTheCallerCanSaveAgain() = runBlocking {
        raw().execSQL("CREATE TRIGGER no_insert BEFORE INSERT ON capture BEGIN SELECT RAISE(ABORT, 'refused'); END")
        val a = audio()
        val r = saver.save(a, capturedLocked = false, markPrivate = false)
        assertTrue(r is InboundSink.Result.Failed)
        assertTrue(repo.captures.live().isEmpty())
        assertEquals(0, raw().rows("SELECT * FROM attachment").size)
        assertEquals(1, store.list().size) // the file is kept (16a's rule); the sweep removes it later
        assertTrue(afterRecording.isEmpty())
        raw().execSQL("DROP TRIGGER no_insert")
        val c = record(a = a)
        assertArrayEquals(a.bytes, repo.attachments.openRecording(audioRow(c.id).id).use { it.readBytes() })
        // the first file has no row: reported, and swept after 24 hours
        assertEquals(listOf(ViolationKind.FILE_WITHOUT_ATTACHMENT), repo.consistency.run().map { it.kind })
        clock.advance(25 * 3600_000L)
        assertEquals(1, repo.attachments.sweep())
        assertTrue(repo.consistency.run().isEmpty())
    }

    @Test fun aNoteHasAtMostOneRecording() = runBlocking {
        fun file(seed: Long): InboundAttachment {
            val plain = ByteArray(800).also { Random(seed).nextBytes(it) }
            val id = Ids.new(); val key = store.newKey(); val name = store.newFileName()
            val w = store.write(name, key, id, ByteArrayInputStream(plain))
            return InboundAttachment(id, AttachmentKind.AUDIO, AttachmentSource.RECORDER, "audio/ogg", name, key, w.byteSize, w.sha256, durationMs = 1_000)
        }
        // two in one hand-over: refused, nothing kept
        val two = sink.accept(Inbound.Capture.now("", "", CaptureSource.VOICE, false, null, "test", clock, attachments = listOf(file(1), file(2))))
        assertTrue(two is InboundSink.Result.Failed)
        assertTrue(repo.captures.live().isEmpty())
        // a second one added to a note that has one: refused, the first untouched
        val c = record()
        val first = audioRow(c.id).id
        val added = sink.accept(Inbound.AddAttachments(c.id, listOf(file(3))))
        assertTrue(added is InboundSink.Result.Failed)
        assertEquals(listOf(first), db.attachments().audioForCapture(c.id).map { it.id })
        // a picture can still be added beside it
        val plain = ByteArray(900).also { Random(4).nextBytes(it) }
        val id = Ids.new(); val key = store.newKey(); val name = store.newFileName()
        val w = store.write(name, key, id, ByteArrayInputStream(plain))
        val pic = InboundAttachment(id, AttachmentKind.PHOTO, AttachmentSource.CAMERA, "image/webp", name, key, w.byteSize, w.sha256, 10, 10)
        assertTrue(sink.accept(Inbound.AddAttachments(c.id, listOf(pic))) is InboundSink.Result.Saved)
        assertEquals(listOf(id), repo.attachments.pictures(c.id).map { it.id })
    }

    // ---------- the words arrive ----------

    @Test fun applyFirstSetsTheRawWordsTheCorrectedTextAndQueuesTheReading() = runBlocking {
        repo.putCorrection("sid", "Syd")
        val c = record()
        clock.advance()
        val r = repo.transcriptions.applyFirst(c.id, ENGINE, "sid has the spare key in the frunk", app.stash.data.TranscriptionTimings(4_200, 150, 1))
        assertEquals(TranscriptionRepository.First.WORDS, r)
        val after = repo.captures.get(c.id)!!
        assertEquals("sid has the spare key in the frunk", after.rawText)
        assertEquals("Syd has the spare key in the frunk", after.text)
        assertEquals(TranscribeState.DONE, after.transcribeState)
        assertEquals(ExtractState.PENDING, after.extractState); assertEquals(0, after.extractAttempts)
        assertEquals(clock.now(), after.updatedAt)
        assertEquals(listOf(c.id), repo.captures.toRead().map { it.id })
        assertEquals(setOf(c.id), repo.index.search("spare").map { it.docId }.toSet())
        val t = repo.transcriptions.history(c.id).single()
        assertEquals(TranscriptionStatus.CURRENT, t.status); assertEquals("sid has the spare key in the frunk", t.text)
        assertEquals(ENGINE, t.engine); assertEquals(audioRow(c.id).id, t.attachmentId)
        assertEquals(4_200L, t.audioMs); assertEquals(150L, t.procMs); assertEquals(1, t.chunks); assertEquals(0, t.doubt); assertFalse(t.askedByOwner)
        // a second applyFirst is refused and changes nothing
        assertEquals(TranscriptionRepository.First.REFUSED, repo.transcriptions.applyFirst(c.id, ENGINE, "something else"))
        assertEquals("Syd has the spare key in the frunk", repo.captures.get(c.id)!!.text)
        assertEquals(1, repo.transcriptions.history(c.id).size)
        assertTrue(repo.consistency.run().isEmpty())
    }

    @Test fun aSpokenPrivateMarkerMakesTheNotePrivateAndIsKeptOnlyInTheRawWords() = runBlocking {
        val c = record()
        repo.transcriptions.applyFirst(c.id, ENGINE, "private note the gate code is 4471")
        val after = repo.captures.get(c.id)!!
        assertTrue(after.sensitive); assertEquals("SAID", after.sensitiveReason)
        assertEquals("the gate code is 4471", after.text)
        assertEquals("private note the gate code is 4471", after.rawText)
    }

    @Test fun anEngineThatHeardNothingEndsDoneWithNothingToRead() = runBlocking {
        val c = record()
        assertEquals(TranscriptionRepository.First.NO_WORDS, repo.transcriptions.applyFirst(c.id, ENGINE, "  "))
        val after = repo.captures.get(c.id)!!
        assertEquals(TranscribeState.DONE, after.transcribeState)
        assertEquals(ExtractState.DONE, after.extractState)
        assertEquals("", after.text); assertEquals("", after.rawText)
        assertTrue(repo.captures.toRead().isEmpty())
        assertEquals(TranscriptionStatus.CURRENT, repo.transcriptions.history(c.id).single().status)
        // it has its recording, so it is not an empty note
        assertTrue(repo.consistency.run().isEmpty())
        assertEquals(TranscriptionRepository.First.REFUSED, repo.transcriptions.applyFirst(c.id, ENGINE, "late words"))
    }

    @Test fun aNoteTheOwnerMadePrivateStaysPrivateWhateverTheWordsSay() = runBlocking {
        val c = spoken("the milk is in the fridge", private = true)
        assertTrue(c.sensitive); assertEquals("the milk is in the fridge", c.text)
        val o = repo.transcriptions.offer(c.id, ENGINE, "the milk is in the garage fridge")!!
        assertTrue(repo.transcriptions.use(o.id))
        val after = repo.captures.get(c.id)!!
        assertTrue(after.sensitive); assertEquals("SAID", after.sensitiveReason)
        assertEquals("the milk is in the garage fridge", after.text)
    }

    @Test fun failuresRetryThenGiveUpAndAskingAgainStartsOver() = runBlocking {
        val c = record()
        assertEquals(TranscribeState.RETRY, repo.transcriptions.recordFailure(c.id, ENGINE, "MODEL_MISSING", retryable = true, countAttempt = true))
        assertEquals(1, repo.captures.get(c.id)!!.transcribeAttempts)
        assertEquals(TranscribeState.RETRY, repo.transcriptions.recordFailure(c.id, ENGINE, "KILLED", retryable = true, countAttempt = false))
        assertEquals(1, repo.captures.get(c.id)!!.transcribeAttempts)
        assertEquals(TranscribeState.GAVE_UP, repo.transcriptions.recordFailure(c.id, ENGINE, "OOM", retryable = true, countAttempt = true) { it >= 2 })
        val rows = repo.transcriptions.history(c.id)
        assertEquals(3, rows.size); assertTrue(rows.all { it.status == TranscriptionStatus.FAILED && it.text == "" })
        assertEquals(listOf("OOM", "KILLED", "MODEL_MISSING"), rows.map { it.error }) // newest first
        // a reason is a code, never words
        repo.transcriptions.recordFailure(c.id, ENGINE, "the gate code is 4471!", retryable = false, countAttempt = false)
        assertEquals("thegatecodeis4471", repo.transcriptions.history(c.id).first().error)
        // the recording is untouched
        assertNotNull(repo.recordings.info(c.id))
        assertTrue(repo.consistency.run().isEmpty())
        // ask again: back to PENDING with no attempts, and the first writing-out is allowed
        assertEquals(TranscriptionRepository.AskAgain.WAITING_AGAIN, repo.transcriptions.askAgain(c.id))
        assertEquals(TranscribeState.PENDING, repo.captures.get(c.id)!!.transcribeState); assertEquals(0, repo.captures.get(c.id)!!.transcribeAttempts)
        assertEquals(TranscriptionRepository.First.WORDS, repo.transcriptions.applyFirst(c.id, ENGINE, "found it"))
        // asking again on a written-out note marks it for an offer, until one is made
        assertEquals(TranscriptionRepository.AskAgain.OFFER_NEXT, repo.transcriptions.askAgain(c.id))
        assertTrue(repo.transcriptions.wantsNewOffer(c.id))
        repo.transcriptions.offer(c.id, ENGINE, "found it again")
        assertFalse(repo.transcriptions.wantsNewOffer(c.id))
        // a typed note has no recording: nothing to ask
        val typed = repo.captures.insertCapture("typed", "typed", CaptureSource.TYPED)
        assertEquals(TranscriptionRepository.AskAgain.NOTHING, repo.transcriptions.askAgain(typed.id))
    }

    // ---------- write it out again ----------

    @Test fun offerThenUseRewritesTheTextAndKeepsTheRawWordsAndEveryVersion() = runBlocking {
        repo.putCorrection("sid", "Syd")
        val c = spoken("I put the passport in the drawer")
        clock.advance()
        val o = repo.transcriptions.offer(c.id, ENGINE, "sid put the password in the drawer")!!
        assertEquals(TranscriptionStatus.OFFERED, o.status); assertTrue(o.askedByOwner)
        assertEquals("I put the passport in the drawer", repo.captures.get(c.id)!!.text) // offer touches nothing
        db.captures().update(repo.captures.get(c.id)!!.copy(extractState = ExtractState.DONE))
        clock.advance()
        assertTrue(repo.transcriptions.use(o.id))
        val after = repo.captures.get(c.id)!!
        assertEquals("I put the passport in the drawer", after.rawText)
        assertEquals("Syd put the password in the drawer", after.text)
        assertEquals(ExtractState.PENDING, after.extractState)
        val hist = repo.transcriptions.history(c.id)
        assertEquals(listOf(TranscriptionStatus.CURRENT, TranscriptionStatus.REPLACED), hist.map { it.status })
        assertEquals(o.id, hist.first().id)
        val rev = db.revisions().forCapture(c.id).single { it.what == RevisionKind.TEXT_REWRITTEN }
        assertEquals("I put the passport in the drawer", rev.oldValue); assertEquals("Syd put the password in the drawer", rev.newValue)
        assertEquals(setOf(c.id), repo.index.search("password").map { it.docId }.toSet())
        // "password" is a secret word: the note became Private, as a first writing-out would
        assertTrue(after.sensitive)
        // a CURRENT row cannot be used again
        assertFalse(repo.transcriptions.use(o.id))
        assertTrue(repo.consistency.run().isEmpty())
    }

    @Test fun offerThenKeepMineChangesNothing() = runBlocking {
        val c = spoken("the ladder is in the shed")
        val o = repo.transcriptions.offer(c.id, ENGINE, "the latter is in the shed")!!
        assertTrue(repo.transcriptions.keepMine(o.id))
        assertEquals("the ladder is in the shed", repo.captures.get(c.id)!!.text)
        assertEquals(listOf(TranscriptionStatus.REPLACED, TranscriptionStatus.CURRENT), repo.transcriptions.history(c.id).map { it.status })
        assertTrue(db.revisions().forCapture(c.id).none { it.what == RevisionKind.TEXT_REWRITTEN })
        assertFalse(repo.transcriptions.keepMine(o.id))
    }

    @Test fun anOfferOfTheSameWordsLeavesNoOfferOpen() = runBlocking {
        repo.putCorrection("sid", "Syd")
        val c = spoken("Syd has the key")
        val o = repo.transcriptions.offer(c.id, ENGINE, "sid has the key")!!
        assertEquals(TranscriptionStatus.REPLACED, o.status)
        assertTrue(repo.transcriptions.history(c.id).none { it.status == TranscriptionStatus.OFFERED })
        // a newer offer closes an older one still open
        val o1 = repo.transcriptions.offer(c.id, ENGINE, "Syd has the keys")!!
        val o2 = repo.transcriptions.offer(c.id, ENGINE, "Syd has a key")!!
        assertEquals(TranscriptionStatus.REPLACED, db.transcriptions().get(o1.id)!!.status)
        assertEquals(TranscriptionStatus.OFFERED, db.transcriptions().get(o2.id)!!.status)
    }

    // ---------- removing the recording alone ----------

    @Test fun removingTheRecordingKeepsTheWordsAndEveryWritingOut() = runBlocking {
        val c = spoken("the spare tire is in the frunk")
        repo.transcriptions.offer(c.id, ENGINE, "the spare tyre is in the frunk")
        val a = audioRow(c.id)
        clock.advance()
        assertEquals(RecordingRepository.Removal.REMOVED, repo.recordings.remove(c.id))
        assertNull(db.attachments().get(a.id))
        assertFalse(a.fileName in store.list())
        assertTrue(db.tombstones().all().any { it.tableName == "attachment" && it.rowId == a.id })
        val rev = db.revisions().forCapture(c.id).single { it.what == RevisionKind.AUDIO_REMOVED }
        assertEquals(a.id, rev.oldValue)
        assertTrue(db.revisions().forCapture(c.id).none { it.what == RevisionKind.PICTURE_REMOVED })
        val after = repo.captures.get(c.id)!!
        assertEquals("the spare tire is in the frunk", after.text); assertEquals(clock.now(), after.updatedAt)
        assertEquals(2, repo.transcriptions.history(c.id).size)
        assertEquals(a.id, repo.transcriptions.history(c.id).last().attachmentId) // kept as a plain value
        assertNull(repo.recordings.info(c.id))
        assertTrue(Triple(DbgComponent.AUDIO, DbgCode.REMOVED, null to null) in events)
        assertEquals(RecordingRepository.Removal.NO_RECORDING, repo.recordings.remove(c.id))
        assertTrue(repo.consistency.run().isEmpty())
    }

    @Test fun removingTheRecordingOfANoteWithNoWordsIsRefused() = runBlocking {
        val c = record()
        assertEquals(RecordingRepository.Removal.NO_WORDS, repo.recordings.remove(c.id))
        val a = audioRow(c.id)
        assertTrue(a.fileName in store.list())
        assertTrue(db.tombstones().all().isEmpty())
        assertTrue(db.revisions().forCapture(c.id).isEmpty())
        // the same after a writing-out that heard nothing
        repo.transcriptions.applyFirst(c.id, ENGINE, "")
        assertEquals(RecordingRepository.Removal.NO_WORDS, repo.recordings.remove(c.id))
        assertTrue(repo.consistency.run().isEmpty())
    }

    // ---------- replacing the file ----------

    @Test fun replaceFileGivesNewBytesUnderANewFileAndDeletesTheOldOne() = runBlocking {
        val wav = EncodedAudio(ByteArray(20_000).also { Random(1).nextBytes(it) }, "audio/wav", 600, AudioFormat.WAV)
        val c = record(a = wav)
        val before = audioRow(c.id)
        val opus = audio(n = 1_500, seed = 2, ms = 600)
        clock.advance()
        assertTrue(repo.recordings.replaceFile(before.id, opus))
        val after = audioRow(c.id)
        assertEquals(before.id, after.id)
        assertFalse(before.fileName == after.fileName)
        assertFalse(before.fileKey.contentEquals(after.fileKey))
        assertEquals("audio/ogg", after.mime); assertEquals(1_500L, after.byteSize); assertFalse(before.sha256 == after.sha256)
        assertEquals(clock.now(), after.updatedAt)
        assertArrayEquals(opus.bytes, repo.attachments.openRecording(after.id).use { it.readBytes() })
        assertEquals(setOf(after.fileName), store.list())
        assertTrue(repo.consistency.run().isEmpty())
        // not a recording: refused
        assertFalse(repo.recordings.replaceFile("no-such-id", opus))
    }

    @Test fun anOldFileThatCannotBeDeletedIsSweptLater() = runBlocking {
        val c = record()
        val before = audioRow(c.id)
        store.failDeletes = 1
        assertTrue(repo.recordings.replaceFile(before.id, audio(n = 700, seed = 5)))
        assertTrue(before.fileName in store.list())
        assertEquals(listOf(ViolationKind.FILE_WITHOUT_ATTACHMENT), repo.consistency.run().map { it.kind })
        clock.advance(25 * 3600_000L)
        assertEquals(1, repo.attachments.sweep())
        assertFalse(before.fileName in store.list())
        assertTrue(repo.consistency.run().isEmpty())
    }

    // ---------- deleting the note ----------

    @Test fun purgingARecordedNoteLeavesNoRowAndNoFile() = runBlocking {
        val c = spoken("the guitar is with Frank")
        repo.transcriptions.offer(c.id, ENGINE, "the guitar is with Franks")
        repo.transcriptions.recordFailure(c.id, ENGINE, "X", retryable = true, countAttempt = true)
        val a = audioRow(c.id)
        repo.captures.softDelete(c.id)
        // soft delete and restore touch nothing new
        assertEquals(3, repo.transcriptions.history(c.id).size); assertTrue(a.fileName in store.list())
        repo.captures.restore(c.id)
        assertEquals(3, repo.transcriptions.history(c.id).size)
        assertTrue(repo.captures.purge(c.id))
        assertNull(repo.captures.get(c.id))
        assertEquals(0, raw().rows("SELECT * FROM attachment").size)
        assertEquals(0, raw().rows("SELECT * FROM transcription").size)
        assertTrue(store.list().isEmpty())
        assertTrue(db.tombstones().all().any { it.tableName == "attachment" && it.rowId == a.id })
        assertTrue(db.tombstones().all().any { it.tableName == "capture" && it.rowId == c.id })
        assertTrue(repo.consistency.run().isEmpty())
    }

    // ---------- the check, the dump, the export ----------

    @Test fun theConsistencyCheckReportsEachNewKindOfBrokenRow() = runBlocking {
        // a waiting note whose recording row is gone
        val c1 = record()
        db.attachments().delete(listOf(audioRow(c1.id).id))
        // a note with neither words nor a file
        db.captures().insert(repo.captures.get(c1.id)!!.copy(id = "empty", transcribeState = null))
        // a note with two recordings, seeded past the repository
        val c2 = record(a = audio(seed = 9))
        db.attachments().insert(audioRow(c2.id).copy(id = "second", position = 5))
        // two CURRENT writing-outs
        val c3 = spoken("words")
        db.transcriptions().insert(repo.transcriptions.history(c3.id).single().copy(id = "t2"))
        val v = repo.consistency.run()
        assertTrue(v.toString(), app.stash.data.Violation(ViolationKind.RECORDING_STATE_WITHOUT_AUDIO, c1.id) in v)
        assertTrue(v.toString(), app.stash.data.Violation(ViolationKind.EMPTY_NOTE, "empty") in v)
        assertTrue(v.toString(), app.stash.data.Violation(ViolationKind.TWO_RECORDINGS, c2.id) in v)
        assertTrue(v.toString(), app.stash.data.Violation(ViolationKind.TWO_CURRENT_TRANSCRIPTIONS, c3.id) in v)
        // reported, never repaired
        repo.consistency.repair()
        assertEquals(2, db.attachments().audioForCapture(c2.id).size)
    }

    @Test fun theDebugDumpShowsTheWritingOutsAndTheNewColumnsButNoKey() = runBlocking {
        val c = spoken("the frunk has the charger")
        val a = audioRow(c.id)
        val text = DumpReceiver.dump(raw(), 100).toString()
        val json = org.json.JSONObject(text)
        assertEquals(5, json.getInt("version")) // card 08 added answer_trace
        val t = json.getJSONObject("tables").getJSONArray("transcription").getJSONObject(0)
        assertEquals("CURRENT", t.getString("status")); assertEquals(ENGINE, t.getString("engine"))
        val cap = json.getJSONObject("tables").getJSONArray("capture").getJSONObject(0)
        assertEquals("DONE", cap.getString("transcribe_state")); assertEquals(0, cap.getInt("transcribe_attempts"))
        val hex = a.fileKey.joinToString("") { "%02x".format(it) }
        for (form in listOf(hex, hex.uppercase(), Base64.getEncoder().encodeToString(a.fileKey), Base64.getUrlEncoder().withoutPadding().encodeToString(a.fileKey))) {
            assertFalse(text.contains(form))
        }
        assertFalse(text.contains(a.fileName))
    }

    @Test fun theExportHasTheWordsAndNothingOfTheRecording() = runBlocking {
        val c = spoken("the spare key is with Syd")
        val a = audioRow(c.id)
        val out = repo.exportText(repo.captures.live())
        assertTrue(out.contains("the spare key is with Syd"))
        assertFalse(out.contains(a.fileName)); assertFalse(out.contains(a.id)); assertFalse(out.contains(a.sha256))
        assertFalse(out.contains("audio/"))
    }

    @Test fun theDebugSelfTestPassesAndLeavesNothingBehind() = runBlocking {
        val out = RecordingSelfTest.run(TestSupport.context, store)
        assertTrue(out.toString(), out.getBoolean("pass"))
        assertTrue(store.list().isEmpty() && store.temps().isEmpty())
        assertTrue(repo.captures.live().isEmpty())
    }

    private companion object {
        const val ENGINE = "moonshine-base-en-2026-02-27/sherpa-2.0.1"
    }
}
