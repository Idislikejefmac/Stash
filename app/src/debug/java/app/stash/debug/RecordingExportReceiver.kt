package app.stash.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.stash.StashApp
import app.stash.audio.AudioDecoder
import app.stash.domain.Wav
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug builds only (2026-11-16, the owner asked for it). Writes the kept recordings of recent voice notes as plain
 * 16 kHz WAV files into the app's own private folder, so the developer can pull them to the PC over adb (`run-as`) and
 * check whether the start of each note was cut off. Nothing is sent anywhere; the files never leave the app's private
 * storage except by the developer's own `adb exec-out run-as` read, and `step=clear` deletes them.
 *
 * Private (sensitive) notes are skipped unless `includePrivate=true` is passed.
 *
 *   step=export (default)  extras: `since` (epoch ms, default 0), `limit` (default 100)
 *   step=clear             delete the export folder
 *
 * Output: `files/debug/rec-export/<captureId>.wav` and `files/debug/rec-export/index.json` (ids, times, durations,
 * peaks: numbers only, no words).
 */
class RecordingExportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        thread(name = "rec-export") {
            val dir = File(context.filesDir, DIR)
            try {
                if (intent.getStringExtra("step") == "clear") {
                    dir.deleteRecursively()
                } else {
                    export(context, intent, dir)
                }
            } catch (e: Throwable) {
                dir.mkdirs()
                File(dir, "index.json").writeText(JSONObject().put("error", e.javaClass.simpleName).toString())
            } finally {
                pending.finish()
            }
        }
    }

    private fun export(context: Context, intent: Intent, dir: File) {
        val container = (context.applicationContext as StashApp).container
        val since = intent.getLongExtra("since", 0L)
        val limit = intent.getIntExtra("limit", 100).coerceIn(1, 1_000)
        val includePrivate = intent.getBooleanExtra("includePrivate", false)
        dir.deleteRecursively()
        dir.mkdirs()
        val db = container.database().openHelper.readableDatabase
        val rows = mutableListOf<Triple<String, String, Long>>() // capture id, attachment id, created_at
        db.query(
            "SELECT c.id, a.id, c.created_at, c.sensitive FROM capture c JOIN attachment a ON a.capture_id = c.id " +
                "WHERE a.kind = 'AUDIO' AND c.deleted_at IS NULL AND c.created_at >= ? ORDER BY c.created_at DESC LIMIT ?",
            arrayOf<Any>(since, limit),
        ).use { c ->
            while (c.moveToNext()) {
                if (c.getInt(3) != 0 && !includePrivate) continue
                rows += Triple(c.getString(0), c.getString(1), c.getLong(2))
            }
        }
        val decoder = AudioDecoder()
        val out = JSONArray()
        for ((captureId, attachmentId, createdAt) in rows) {
            val o = JSONObject().put("captureId", captureId).put("createdAt", createdAt)
            try {
                val info = runBlocking { container.repo.recordings.info(captureId) }
                val bytes = runBlocking { container.repo.attachments.openRecording(attachmentId).use { it.readBytes() } }
                val pcm = decoder.decode(bytes, info?.mime ?: "audio/ogg")
                File(dir, "$captureId.wav").writeBytes(Wav.encode(pcm))
                o.put("durationMs", pcm.durationMs).put("peak", pcm.peak()).put("file", "$captureId.wav")
            } catch (e: Throwable) {
                o.put("error", e.javaClass.simpleName)
            }
            out.put(o)
        }
        File(dir, "index.json").writeText(JSONObject().put("at", System.currentTimeMillis()).put("notes", out).toString(2))
    }

    companion object {
        const val DIR = "debug/rec-export"
    }
}
