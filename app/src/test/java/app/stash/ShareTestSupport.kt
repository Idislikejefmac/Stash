package app.stash

import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.room.RoomDatabase
import app.stash.capture.CaptureEntry
import app.stash.capture.DirectSink
import app.stash.capture.InboundAttachment
import app.stash.capture.InboundSink
import app.stash.data.AttachmentSource
import app.stash.data.AttachmentKind
import app.stash.data.CaptureSource
import app.stash.data.Ids
import app.stash.data.Repository
import app.stash.data.StashDatabase
import app.stash.pictures.PictureException
import app.stash.pictures.PictureFailure
import app.stash.pictures.Prepared
import app.stash.security.AttachmentStore
import app.stash.share.PictureIntake
import app.stash.share.ShareCues
import app.stash.share.ShareDeps
import app.stash.speech.SpeechEngine
import kotlinx.coroutines.CompletableDeferred
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList

/** A picture intake that writes a real (tiny) encrypted file through a real store, and counts what it is asked. */
class FakeIntake(val store: AttachmentStore) : PictureIntake {
    val prepared = CopyOnWriteArrayList<String>()
    val sources = CopyOnWriteArrayList<AttachmentSource>()
    val discarded = CopyOnWriteArrayList<String>()
    var failUris: Set<String> = emptySet()

    /** When set, every `prepare` waits for it first. */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun prepare(uri: Uri, source: AttachmentSource): Prepared {
        gate?.await()
        if (uri.toString() in failUris) throw PictureException(PictureFailure.UNDECODABLE)
        prepared += uri.toString()
        sources += source
        val id = Ids.new()
        val key = store.newKey()
        val name = store.newFileName()
        val bytes = uri.toString().toByteArray()
        val w = store.write(name, key, id, ByteArrayInputStream(bytes))
        val a = InboundAttachment(id, AttachmentKind.PHOTO, source, "image/webp", name, key, w.byteSize, w.sha256, 10, 10)
        return Prepared(a, Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888))
    }

    override fun discard(prepared: Prepared) {
        discarded += prepared.attachment.id
        store.delete(prepared.attachment.fileName)
    }
}

class FakeSpeech : SpeechEngine {
    var listener: SpeechEngine.Listener? = null
    var starts = 0
    var stops = 0
    var cancels = 0
    var destroyed = false
    override val name = "fake"
    override fun start(listener: SpeechEngine.Listener, biasWords: List<String>) { this.listener = listener; starts++ }
    override fun stop() { stops++ }
    override fun cancel() { cancels++ }
    override fun destroy() { destroyed = true }
}

class CountingCues : ShareCues {
    var started = 0
    var saved = 0
    var failed = 0
    override fun start() { started++ }
    override fun saved() { saved++ }
    override fun failed() { failed++ }
    override fun release() {}
}

/** A real database that records every SQL statement, so a test can prove which tables were read. */
class RecordingDb {
    val sql = CopyOnWriteArrayList<String>()
    val db: StashDatabase = Room.inMemoryDatabaseBuilder(TestSupport.context, StashDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryCallback({ q, _ -> sql += q }, { it.run() })
        .build()
}

/** Everything a share test needs: the fakes, and a real save path (CaptureEntry, DirectSink, Room, files) unless [failSaves] says otherwise. */
class ShareHarness(val database: RecordingDb = RecordingDb()) {
    val clock = FakeClock(1_794_398_400_000L)
    val store: AttachmentStore = TestSupport.tempStore()
    val repo: Repository = TestSupport.repo(database.db, clock, store)
    val intake = FakeIntake(store)
    val speech = FakeSpeech()
    val cues = CountingCues()
    var picturesOn = true
    var voiceOn = false
    var locked = false
    var seenGalleryNote = false
    var failSaves = 0
    val saveCalls = CopyOnWriteArrayList<List<InboundAttachment>>()
    var lastSource: CaptureSource? = null
    var lastLocked: Boolean? = null

    private val entry = CaptureEntry({ it }, DirectSink(repo.captures, { emptyList() }, attachments = repo.attachments), { null }, "test", clock)

    val deps = ShareDeps(
        intake = intake,
        save = { raw, isLocked, priv, atts ->
            saveCalls += atts
            lastLocked = isLocked
            if (failSaves > 0) {
                failSaves--
                InboundSink.Result.Failed("test")
            } else {
                lastSource = CaptureSource.SHARED
                entry.save(raw, CaptureSource.SHARED, isLocked, priv, atts)
            }
        },
        picturesOn = { picturesOn },
        voiceOn = { voiceOn },
        speech = { speech },
        vocabulary = { emptyList() },
        cues = cues,
        isLocked = { locked },
        galleryNoteSeen = { seenGalleryNote },
        markGalleryNoteSeen = { seenGalleryNote = true },
        savedShownMs = 0,
    )
}
