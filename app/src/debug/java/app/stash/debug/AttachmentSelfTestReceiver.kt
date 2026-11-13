package app.stash.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.room.Room
import app.stash.StashApp
import app.stash.capture.InboundAttachment
import app.stash.data.AttachmentKind
import app.stash.data.AttachmentSource
import app.stash.data.CaptureSource
import app.stash.data.Ids
import app.stash.data.Repository
import app.stash.data.StashDatabase
import app.stash.security.AttachmentStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Debug builds only (card 16a, phone check). `adb shell am broadcast -n app.stash/app.stash.debug.AttachmentSelfTestReceiver`
 * writes 1 MB of random bytes through the real attachment store (`files/att/`) and `AttachmentRepository`, reads them
 * back, compares, removes them, and writes `files/debug/attachment-selftest.json` (pass or fail, milliseconds). The
 * rows go into a throwaway in-memory database, never into the owner's notes. Afterwards `files/att` is empty again.
 */
class AttachmentSelfTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val bytes = intent.getIntExtra("bytes", 1 shl 20).coerceIn(0, 64 shl 20)
        val pending = goAsync()
        thread {
            try {
                val store = (context.applicationContext as StashApp).container.attachmentStore
                val out = runBlocking { AttachmentSelfTest.run(context, store, bytes) }
                val file = File(context.filesDir, "debug/attachment-selftest.json")
                file.parentFile?.mkdirs()
                file.writeText(out.toString())
            } finally {
                pending.finish()
            }
        }
    }
}

object AttachmentSelfTest {
    suspend fun run(context: Context, store: AttachmentStore, size: Int): JSONObject {
        val out = JSONObject().put("bytes", size)
        val db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java).build()
        try {
            val repo = Repository(null, db, null, attachmentStore = store)
            val plain = ByteArray(size).also { SecureRandom().nextBytes(it) }
            val id = Ids.new()
            val key = store.newKey()
            val name = store.newFileName()
            val t0 = System.nanoTime()
            val written = store.write(name, key, id, ByteArrayInputStream(plain))
            val writeMs = (System.nanoTime() - t0) / 1_000_000
            val capture = repo.captures.insertCapture(
                rawText = "", text = "", source = CaptureSource.PHOTO,
                attachments = listOf(
                    InboundAttachment(id, AttachmentKind.PHOTO, AttachmentSource.PICKER, "application/octet-stream", name, key, written.byteSize, written.sha256),
                ),
            )
            val t1 = System.nanoTime()
            val back = repo.attachments.openBytes(id).use { it.readBytes() }
            val readMs = (System.nanoTime() - t1) / 1_000_000
            val same = back.contentEquals(plain)
            val removed = repo.attachments.remove(id)
            val fileGone = name !in store.list()
            repo.captures.purge(capture.id)
            out.put("pass", same && removed && fileGone && written.byteSize == size.toLong())
                .put("same", same).put("removed", removed).put("fileGone", fileGone)
                .put("writeMs", writeMs).put("readMs", readMs)
        } catch (e: Exception) {
            out.put("pass", false).put("error", e.javaClass.simpleName)
        } finally {
            db.close()
        }
        return out
    }
}
