package app.stash.debug

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import app.stash.StashApp
import app.stash.audio.Compactor
import app.stash.audio.PlatformEncoders
import app.stash.capture.InboundSink
import app.stash.data.AppEventEntity
import app.stash.domain.AudioFormat
import app.stash.domain.EncodedAudio
import app.stash.domain.Wav
import app.stash.pipeline.C1Outcome
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug builds only (card 26f, phone checks; check C1). Saves a corpus WAV as a **real recorded note** through the
 * app's own `RecordingSaver`, exactly as card 26g's capture screen will, so the real hook, the real foreground pass and
 * the real WorkManager job write it out. The note is one of the owner's notes afterwards: delete it from the Notes list
 * when the checks are done. Nothing is sent anywhere. Files written hold numbers, names and row ids only.
 *
 *   step=save   (default) save now. With Stash on screen, the foreground pass writes it out at once.
 *   step=arm    save after `delay` seconds (default 60), from an alarm, so the screen can be turned off first (C1).
 *   step=report read the events and write, for every note this tool saved, stored -> started -> ok/killed, which pass
 *               did it (foreground or job), and the J1/J2/J3 reading. Run it BEFORE opening Stash after a C1 run:
 *               opening Stash starts a foreground pass, which would finish the note in the job's place.
 *
 * Extras: `file` (under the app's files, default `spike25/s10.wav`, the 27.7 s ramble), `format` (`opus` default,
 * compacted the way 26b's fallback is; `wav` keeps the WAV, so the pass also compacts it), `delay` seconds.
 * Output: `files/debug/transcribe-check.json` (the saves, appended) and `files/debug/transcribe-report.json`.
 */
class TranscriptionCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        thread(name = "transcribe-check") {
            try {
                when (intent.getStringExtra("step") ?: "save") {
                    "arm" -> arm(context, intent)
                    "report" -> report(context)
                    else -> save(context, intent)
                }
            } catch (e: Throwable) {
                append(context, JSONObject().put("step", intent.getStringExtra("step") ?: "save").put("error", e.javaClass.simpleName))
            } finally {
                pending.finish()
            }
        }
    }

    private fun arm(context: Context, intent: Intent) {
        val delayS = intent.getIntExtra("delay", 60).coerceIn(5, 3_600)
        val fire = Intent(context, TranscriptionCheckReceiver::class.java).putExtra("step", "save")
            .putExtra("file", intent.getStringExtra("file") ?: DEFAULT_FILE).putExtra("format", intent.getStringExtra("format") ?: "opus")
            .putExtra("armed", true)
        val pi = PendingIntent.getBroadcast(context, 26, fire, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val am = context.getSystemService(AlarmManager::class.java)
        // Inexact but allowed while idle; no exact-alarm permission is needed. The save time is recorded either way.
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayS * 1000L, pi)
        append(context, JSONObject().put("step", "arm").put("at", System.currentTimeMillis()).put("delayS", delayS))
    }

    private fun save(context: Context, intent: Intent) {
        val container = (context.applicationContext as StashApp).container
        val name = intent.getStringExtra("file") ?: DEFAULT_FILE
        val wav = File(context.filesDir, name).readBytes()
        val pcm = Wav.decode(wav)
        val encoded = if ((intent.getStringExtra("format") ?: "opus") == "wav") EncodedAudio(wav, AudioFormat.WAV.mime, pcm.durationMs, AudioFormat.WAV)
        else Compactor(PlatformEncoders()).compact(wav) ?: EncodedAudio(wav, AudioFormat.WAV.mime, pcm.durationMs, AudioFormat.WAV)
        val power = context.getSystemService(PowerManager::class.java)
        val out = JSONObject().put("step", "save").put("at", System.currentTimeMillis()).put("file", name)
            .put("armed", intent.getBooleanExtra("armed", false))
            .put("format", encoded.format.name).put("bytes", encoded.bytes.size).put("durationMs", pcm.durationMs)
            .put("screenOnAtSave", power.isInteractive).put("deviceIdleAtSave", power.isDeviceIdleMode)
            .put("powerSaveAtSave", power.isPowerSaveMode)
            .put("ignoringBatteryOptimizations", power.isIgnoringBatteryOptimizations(context.packageName))
            .put("lockedAtSave", context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
            .put("standbyBucket", context.getSystemService(UsageStatsManager::class.java).appStandbyBucket)
            .put("screensStarted", container.foreground.count())
            .put("outcomeConstant", app.stash.pipeline.TranscriptionWorkShape.OUTCOME.name)
        val r = runBlocking { container.recordingSaver.save(encoded, capturedLocked = out.getBoolean("lockedAtSave"), markPrivate = false) }
        if (r is InboundSink.Result.Saved) {
            out.put("result", "SAVED").put("captureId", r.capture.id)
            val audio = runBlocking { container.repo.recordings.info(r.capture.id) }
            out.put("attachmentId", audio?.attachmentId ?: "")
        } else {
            out.put("result", "FAILED").put("reason", (r as InboundSink.Result.Failed).reason)
        }
        append(context, out)
    }

    private fun report(context: Context) {
        val container = (context.applicationContext as StashApp).container
        val saves = readSaves(context).filter { it.optString("result") == "SAVED" }.map { it.getString("attachmentId") }.toSet()
        val events = runBlocking { container.database().appEvents().newest(5_000) }
        val runs = C1Report.runs(events, saves)
        val now = System.currentTimeMillis()
        val arr = JSONArray()
        for (r in runs) {
            arr.put(
                JSONObject().put("attachmentId", r.attachmentId).put("storedAt", r.storedAt)
                    .put("startedAfterMs", r.startedAfterMs ?: JSONObject.NULL).put("okAfterMs", r.okAfterMs ?: JSONObject.NULL)
                    .put("by", r.by ?: "none yet").put("killed", r.killed).put("failed", r.failed)
                    .put("msSinceStored", now - r.storedAt),
            )
        }
        val out = JSONObject().put("at", now).put("runs", arr).put("reading", C1Report.reading(runs)?.name ?: "no runs")
            .put("note", "J1 if every run was finished by the job within ${C1Report.J1_START_WITHIN_MS / 1000} s; worst run wins")
        File(context.filesDir, "debug/transcribe-report.json").apply { parentFile?.mkdirs() }.writeText(out.toString(2))
    }

    private fun readSaves(context: Context): List<JSONObject> {
        val f = File(context.filesDir, LOG)
        if (!f.isFile) return emptyList()
        return f.readLines().filter { it.isNotBlank() }.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
    }

    private fun append(context: Context, o: JSONObject) {
        File(context.filesDir, LOG).apply { parentFile?.mkdirs() }.appendText(o.toString() + "\n")
    }

    companion object {
        const val DEFAULT_FILE = "spike25/s10.wav"
        const val LOG = "debug/transcribe-check.json"
    }
}

/** Pure: reads the event log into one line per saved recording, and C1's answer. Tested on the JVM. */
object C1Report {
    /** "The job starts within about a minute" (card 26 index, J1). */
    const val J1_START_WITHIN_MS = 90_000L

    data class Run(
        val attachmentId: String, val storedAt: Long, val startedAfterMs: Long?, val okAfterMs: Long?,
        /** "job" or "foreground": which pass wrote it out (STARTED n2), or null if none has. */
        val by: String?, val killed: Int, val failed: Int,
    )

    fun runs(events: List<AppEventEntity>, attachmentIds: Set<String>): List<Run> {
        val byRef = events.filter { it.refId in attachmentIds }.sortedBy { it.at }.groupBy { it.refId!! }
        return byRef.mapNotNull { (id, ev) ->
            val stored = ev.firstOrNull { it.component == "AUDIO" && it.code == "STORED" } ?: return@mapNotNull null
            val t = ev.filter { it.component == "TRANSCRIBE" }
            val ok = t.firstOrNull { it.code == "OK" }
            // The STARTED that led to the OK (the last one before it), or the last one seen.
            val started = t.lastOrNull { it.code == "STARTED" && (ok == null || it.at <= ok.at) }
            Run(
                id, stored.at, started?.let { it.at - stored.at }, ok?.let { it.at - stored.at },
                started?.let { if (it.n2 == 1L) "job" else "foreground" },
                t.count { it.code == "KILLED" }, t.count { it.code == "FAILED" },
            )
        }.sortedBy { it.storedAt }
    }

    /** J1: every run finished by the job, started within [J1_START_WITHIN_MS]. J2: all finished, some late or by a foreground pass. J3: any killed or unfinished. */
    fun reading(runs: List<Run>): C1Outcome? {
        if (runs.isEmpty()) return null
        return runs.map { r ->
            when {
                r.killed > 0 || r.okAfterMs == null -> C1Outcome.J3
                r.by == "job" && (r.startedAfterMs ?: Long.MAX_VALUE) <= J1_START_WITHIN_MS -> C1Outcome.J1
                else -> C1Outcome.J2
            }
        }.maxBy { it.ordinal }
    }
}
