package app.stash

import app.stash.audio.AudioEncoder
import app.stash.audio.EncoderFactory
import app.stash.audio.MicFailure
import app.stash.audio.Recorder
import app.stash.audio.StreamingEncoder
import app.stash.capture.DirectSink
import app.stash.capture.RecordingSaver
import app.stash.capture.RecordingSession
import app.stash.capture.SavedNote
import app.stash.capture.WordsLater
import app.stash.data.CaptureEntity
import app.stash.data.CaptureSource
import app.stash.data.ExtractState
import app.stash.data.StashDatabase
import app.stash.data.TranscribeState
import app.stash.data.TranscriptionRepository
import app.stash.domain.AudioFormat
import app.stash.domain.AudioFormatConstants
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.EncodedAudio
import app.stash.domain.EndPause
import app.stash.domain.Pcm
import app.stash.domain.PcmBuffer
import app.stash.security.AttachmentStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.sin

/**
 * Card 26g end to end below the screen: a fake recorder and codec, then the real [RecordingSaver], sink, Room and
 * encrypted files, then the words arriving through the real `applyFirst`. What the screen would be handed is read the
 * way the screen reads it ([WordsLater], [SavedNote]).
 */
@RunWith(RobolectricTestRunner::class)
class RecordedNoteFlowTest {
    private val n = AudioFormatConstants.CHUNK_SAMPLES
    private fun quiet(): ShortArray = ShortArray(n) { i -> (if (i % 2 == 0) 50 else -50).toShort() }
    private fun tone(): ShortArray = ShortArray(n) { i -> (6000 * sin(2 * PI * 220 * i / 16000.0)).toInt().toShort() }

    private class FakeRecorder : Recorder {
        private val buffer = PcmBuffer()
        private var cb: ((ShortArray, Int) -> Unit)? = null
        override fun start(onChunk: (ShortArray, Int) -> Unit, onError: (MicFailure) -> Unit): MicFailure? { cb = onChunk; return null }
        fun push(chunk: ShortArray, times: Int) = repeat(times) { buffer.append(chunk); cb!!(chunk, chunk.size) }
        override fun stop(): Pcm { val p = buffer.toPcm(); buffer.release(); return p }
        override fun cancel() { buffer.release() }
    }

    private class OneCodec : EncoderFactory {
        override fun available(format: AudioFormat) = format.isOpus
        override fun create(format: AudioFormat) = object : AudioEncoder {
            var samples = 0L
            override fun start() {}
            override fun feed(chunk: ShortArray, len: Int) { samples += len }
            override fun finish() = EncodedAudio(ByteArray(900) { (it % 50).toByte() }, format.mime, AudioFormatConstants.samplesToMs(samples), format)
            override fun abort() {}
        }
    }

    private class FlakyStore(dir: File) : AttachmentStore(dir) {
        var failWrites = false
        override fun write(fileName: String, key: ByteArray, attachmentId: String, plain: InputStream): Written {
            if (failWrites) throw IOException("disk full")
            return super.write(fileName, key, attachmentId, plain)
        }
    }

    private lateinit var db: StashDatabase
    private lateinit var dir: File
    private lateinit var store: FlakyStore
    private val clock = FakeClock(System.currentTimeMillis())
    private val repo by lazy { TestSupport.repo(db, clock, store) }
    private val writeOutAsked = CopyOnWriteArrayList<String>()
    private val extractionAsked = CopyOnWriteArrayList<String>()
    private val events = CopyOnWriteArrayList<Pair<DbgComponent, DbgCode>>()
    private val sink by lazy {
        DirectSink(repo.captures, { emptyList() }, afterSave = { extractionAsked += "x" }, attachments = repo.attachments,
            afterRecordingSave = { writeOutAsked += it })
    }
    private val saver by lazy { RecordingSaver(store, sink, { null }, "test", clock) }

    @Before fun setUp() {
        db = TestSupport.memoryDb()
        dir = TestSupport.tempDir()
        store = FlakyStore(dir)
        Dbg.sink = app.stash.domain.EventSink { c, code, _, _, _, _ -> events += c to code }
    }

    @After fun tearDown() {
        Dbg.sink = null
        db.close()
        dir.deleteRecursively()
    }

    private inner class Note(locked: Boolean = false) : RecordingSession.Hooks {
        val rec = FakeRecorder()
        val log = CopyOnWriteArrayList<String>()
        val session = RecordingSession(
            recorder = rec, encoder = StreamingEncoder(OneCodec()),
            save = { audio, wasLocked, asPrivate -> saver.save(audio, wasLocked, asPrivate) },
            format = AudioFormat.OPUS_12, pause = EndPause.SHORT, locked = locked,
            launch = { block -> runBlocking { block() } }, hooks = this,
        )
        override fun cueSaved() { log += "cue saved" }
        override fun cueFailed() { log += "cue failed" }
        override fun afterSaved() { log += "ask for writing out" }

        fun speak() { session.start(); rec.push(quiet(), 25); rec.push(tone(), 50) }
        fun done(): CaptureEntity {
            assertEquals(RecordingSession.State.SAVED, runBlocking { session.finish(RecordingSession.EndReason.DONE) })
            return session.saved!!
        }
    }

    /** What the capture screen's watch would be handed for this note right now. */
    private suspend fun watch(id: String): WordsLater.Update {
        val c = repo.captures.get(id)
        return WordsLater.of(c != null && c.deletedAt == null, c?.sensitive ?: false, c?.transcribeState, c?.text.orEmpty())
    }

    @Test fun `a recorded note is on disk with no words before the cue, and its words follow`() = runBlocking {
        val note = Note()
        note.speak()
        assertEquals(0, db.captures().refs().size) // nothing is stored while he speaks
        assertTrue(dir.listFiles().orEmpty().isEmpty())
        val c = note.done()
        assertEquals(listOf("cue saved", "ask for writing out"), note.log.toList())
        val row = repo.captures.get(c.id)!!
        assertEquals("", row.text)
        assertEquals("", row.rawText)
        assertEquals(CaptureSource.VOICE, row.source)
        assertEquals(TranscribeState.PENDING, row.transcribeState)
        assertEquals(ExtractState.DONE, row.extractState) // nothing to read yet
        assertFalse(row.sensitive)
        val info = repo.recordings.info(c.id)!!
        assertEquals(1_500L, info.durationMs)
        assertEquals("audio/ogg", info.mime)
        assertEquals(1, dir.listFiles().orEmpty().size)
        assertEquals(listOf(c.id), writeOutAsked.toList()) // the sink asked; the session asked again, harmlessly
        assertTrue(extractionAsked.isEmpty())
        assertTrue(events.indexOf(DbgComponent.AUDIO to DbgCode.STORED) >= 0)

        var shown = SavedNote.recorded(c.id, c.sensitive, 0)
        assertEquals("Saved", shown.message())
        assertEquals(WordsLater.Update.Waiting, watch(c.id))
        assertEquals(TranscriptionRepository.First.WORDS, repo.transcriptions.applyFirst(c.id, "fake/1", "I'm putting my reading glasses in the nightstand drawer"))
        shown = shown.arrived(watch(c.id))
        assertEquals("Saved: I'm putting my reading glasses in the nightstand drawer", shown.message())
    }

    @Test fun `Private pressed while recording inserts the capture sensitive, and its words are never handed to the screen`() = runBlocking {
        val note = Note(locked = true)
        note.speak()
        note.session.markPrivate()
        val c = note.done()
        assertTrue(c.sensitive)
        val row = repo.captures.get(c.id)!!
        assertTrue(row.sensitive)
        assertEquals(true, row.capturedLocked)
        var shown = SavedNote.recorded(c.id, c.sensitive, 0)
        assertFalse(shown.watching)
        assertEquals("Saved", shown.message())

        // The words arrive later, with nothing in them that would mark the note by itself.
        assertEquals(TranscriptionRepository.First.WORDS, repo.transcriptions.applyFirst(c.id, "fake/1", "the gate code is 4 4 7 1"))
        assertTrue(repo.captures.get(c.id)!!.text.isNotBlank())
        assertTrue(repo.captures.get(c.id)!!.sensitive)
        val update = watch(c.id)
        assertEquals(WordsLater.Update.Private, update) // carries no text
        shown = shown.arrived(update)
        assertNull(shown.words)
        assertEquals("Saved", shown.message())
    }

    @Test fun `a note that says private note is not drawn when its words arrive, though Private was never pressed`() = runBlocking {
        val note = Note()
        note.speak()
        val c = note.done()
        assertFalse(c.sensitive)
        var shown = SavedNote.recorded(c.id, c.sensitive, 0)
        assertTrue(shown.watching)
        repo.transcriptions.applyFirst(c.id, "fake/1", "private note the gate code is 4 4 7 1")
        assertTrue(repo.captures.get(c.id)!!.sensitive)
        shown = shown.arrived(watch(c.id))
        assertTrue(shown.isPrivate)
        assertNull(shown.words)
        assertEquals("Saved", shown.message())
    }

    @Test fun `a note that could not be written out says so, and an undone note shows nothing more`() = runBlocking {
        val note = Note()
        note.speak()
        val c = note.done()
        assertEquals(TranscribeState.GAVE_UP, repo.transcriptions.recordFailure(c.id, "fake/1", "UNDECODABLE", retryable = false, countAttempt = true))
        assertEquals(WordsLater.Update.GaveUp, watch(c.id))
        assertNotNull(repo.recordings.info(c.id)) // the recording is kept

        val second = Note()
        second.speak()
        val undone = second.done()
        repo.deleteCapture(undone.id) // Undo within five seconds
        assertEquals(WordsLater.Update.Gone, watch(undone.id))
    }

    @Test fun `a full disk is a failed save with nothing in the database, and Save again stores the same recording`() = runBlocking {
        val note = Note()
        note.speak()
        store.failWrites = true
        assertEquals(RecordingSession.State.SAVE_FAILED, note.session.finish(RecordingSession.EndReason.DONE))
        assertEquals(listOf("cue failed"), note.log.toList())
        assertEquals(0, db.captures().refs().size)
        store.failWrites = false
        assertEquals(RecordingSession.State.SAVED, note.session.saveAgain())
        assertEquals(listOf("cue failed", "cue saved", "ask for writing out"), note.log.toList())
        val c = note.session.saved!!
        assertEquals(1, db.captures().refs().size)
        assertEquals(1_500L, repo.recordings.info(c.id)!!.durationMs)
        assertEquals(900L, repo.recordings.info(c.id)!!.byteSize)
    }

    @Test fun `Cancel leaves no row and no file`() = runBlocking {
        val note = Note()
        note.speak()
        note.session.cancel()
        assertEquals(0, db.captures().refs().size)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
        assertTrue(writeOutAsked.isEmpty())
    }
}
