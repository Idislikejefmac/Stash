package app.stash.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.room.Room
import app.stash.StashApp
import app.stash.capture.DirectSink
import app.stash.capture.InboundSink
import app.stash.capture.RecordingSaver
import app.stash.data.Repository
import app.stash.data.StashDatabase
import app.stash.data.TranscriptionRepository
import app.stash.domain.AudioFormat
import app.stash.domain.EncodedAudio
import app.stash.domain.Wav
import app.stash.security.AttachmentStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/**
 * Debug builds only (card 26c, phone check). `adb shell am broadcast -n app.stash/app.stash.debug.RecordingSelfTestReceiver`
 * saves 10 seconds of a generated tone through [RecordingSaver] into the real attachment store (`files/att/`), reads it
 * back, applies a fixed sentence as its first writing-out, removes the recording, then purges the note, and writes
 * `files/debug/recording-selftest.json` (pass or fail, milliseconds, numbers only). The rows go into a throwaway
 * in-memory database, never into the owner's notes. Afterwards `files/att` holds nothing from it.
 */
class RecordingSelfTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        thread {
            try {
                val store = (context.applicationContext as StashApp).container.attachmentStore
                val out = runBlocking { RecordingSelfTest.run(context, store) }
                val file = File(context.filesDir, "debug/recording-selftest.json")
                file.parentFile?.mkdirs()
                file.writeText(out.toString())
            } finally {
                pending.finish()
            }
        }
    }
}

object RecordingSelfTest {
    /** A 440 Hz tone at 16 kHz, as WAV (no codec needed, so the test checks only the store and the save path). */
    fun tone(seconds: Int = 10): EncodedAudio {
        val n = 16_000 * seconds
        val samples = ShortArray(n) { (sin(2 * PI * 440.0 * it / 16_000) * 8_000).toInt().toShort() }
        return EncodedAudio(Wav.encode(samples, n), AudioFormat.WAV.mime, seconds * 1000L, AudioFormat.WAV)
    }

    suspend fun run(context: Context, store: AttachmentStore): JSONObject {
        val out = JSONObject()
        val db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java).build()
        val filesBefore = store.list()
        try {
            val repo = Repository(null, db, null, attachmentStore = store)
            val sink = DirectSink(repo.captures, { emptyList() }, attachments = repo.attachments)
            val saver = RecordingSaver(store, sink, { null }, "selftest")
            val audio = tone()
            val t0 = System.nanoTime()
            val saved = saver.save(audio, capturedLocked = false, markPrivate = false)
            val saveMs = (System.nanoTime() - t0) / 1_000_000
            val capture = (saved as? InboundSink.Result.Saved)?.capture ?: error("save failed")
            val info = repo.recordings.info(capture.id) ?: error("no recording")
            val t1 = System.nanoTime()
            val back = repo.attachments.openRecording(info.attachmentId).use { it.readBytes() }
            val readMs = (System.nanoTime() - t1) / 1_000_000
            val same = back.contentEquals(audio.bytes)
            val first = repo.transcriptions.applyFirst(capture.id, "selftest", "The spare key is in the frunk")
            val removed = repo.recordings.remove(capture.id)
            val purged = repo.captures.purge(capture.id)
            val leftover = store.list() - filesBefore
            out.put(
                "pass",
                same && first == TranscriptionRepository.First.WORDS && removed == app.stash.data.RecordingRepository.Removal.REMOVED &&
                    purged && leftover.isEmpty(),
            )
                .put("bytes", audio.bytes.size).put("same", same).put("first", first.name).put("removed", removed.name)
                .put("purged", purged).put("leftoverFiles", leftover.size).put("saveMs", saveMs).put("readMs", readMs)
        } catch (e: Exception) {
            out.put("pass", false).put("error", e.javaClass.simpleName)
        } finally {
            db.close()
        }
        return out
    }
}
