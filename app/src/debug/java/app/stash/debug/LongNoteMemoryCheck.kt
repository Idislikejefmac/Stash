package app.stash.debug

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.stash.StashApp
import app.stash.audio.PauseSplitter
import app.stash.domain.AudioFormatConstants
import app.stash.domain.Pcm
import app.stash.domain.Wav
import app.stash.speech.ChunkedTranscriber
import app.stash.speech.KnownModels
import app.stash.speech.ModelLocator
import app.stash.speech.RunPlace
import app.stash.speech.TranscribeOptions
import app.stash.speech.TranscribeResult
import app.stash.speech.Transcriber
import app.stash.speech.sherpa.SherpaEngine
import java.io.File

/**
 * Debug builds only (card 26e item 4, the memory measurement). Runs `Container.transcriber` inside a real
 * WorkManager job on the corpus's 27.7-second recording (`files/spike25/s10.wav`), on three copies of it joined end
 * to end, or on a three-minute file made by repeating it, and samples the process's total PSS every 200 ms
 * (`Debug.getMemoryInfo().totalPss`, as card 25 did).
 *
 *   adb shell am kill app.stash
 *   adb shell am broadcast -n app.stash/app.stash.debug.LongNoteMemoryReceiver --es pack base --es length s10
 *
 * Extras: `pack` base (default) | tiny | app (Container's own choice, untouched); `length` s10 (default) | x3 | 3min;
 * `place` background (default, 1 thread) | screen (2 threads); `uncut` true writes out base or tiny without the
 * PauseSplitter (to compare with card 25); `tag` the output folder (default `<pack>-<length>`); `delaySec`.
 *
 * Output: `files/spike26/long-notes/<tag>/summary.tsv` in the card 25 spike's layout (the s10 row can be copied to
 * `docs/spike25/results/<tag>/` and scored by `score.py`), `.../<tag>/work.txt`, and one numbers-only row appended
 * to `files/spike26/long-notes/table.tsv`.
 */
class LongNoteMemoryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pack = intent.getStringExtra("pack") ?: "base"
        val length = intent.getStringExtra("length") ?: "s10"
        val req = OneTimeWorkRequestBuilder<LongNoteMemoryWorker>()
            .setInputData(workDataOf(
                "pack" to pack, "length" to length,
                "place" to (intent.getStringExtra("place") ?: "background"),
                "uncut" to intent.getBooleanExtra("uncut", false),
                "tag" to (intent.getStringExtra("tag") ?: "$pack-$length"),
                "enqueuedAt" to System.currentTimeMillis(),
            ))
            .setInitialDelay(intent.getIntExtra("delaySec", 0).toLong(), java.util.concurrent.TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueue(req)
    }
}

class LongNoteMemoryWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    @Volatile private var sampling = false
    @Volatile private var peakPss = 0

    private fun pss(): Int { val mi = Debug.MemoryInfo(); Debug.getMemoryInfo(mi); return mi.totalPss }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val d = inputData
        val packName = d.getString("pack") ?: "base"
        val length = d.getString("length") ?: "s10"
        val place = if (d.getString("place") == "screen") RunPlace.ON_SCREEN else RunPlace.BACKGROUND
        val uncut = d.getBoolean("uncut", false)
        val tag = d.getString("tag") ?: "$packName-$length"
        val out = File(ctx.filesDir, "spike26/long-notes/$tag").apply { mkdirs() }
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val startedAt = System.currentTimeMillis()
        val interactiveAtStart = pm.isInteractive
        val processAgeMs = SystemClock.uptimeMillis() - Process.getStartUptimeMillis()

        val wav = File(ctx.filesDir, "spike25/s10.wav")
        if (!wav.isFile) {
            File(out, "work.txt").writeText("error=no-s10-wav\n")
            return Result.failure()
        }
        val one = Wav.decode(wav.readBytes()).samples
        val pcm = Pcm(when (length) {
            "x3" -> ShortArray(one.size * 3) { one[it % one.size] }
            "3min" -> ShortArray(AudioFormatConstants.msToSamples(AudioFormatConstants.MAX_NOTE_MS).toInt()) { one[it % one.size] }
            else -> one
        })
        val id = when (length) { "x3" -> "s10x3"; "3min" -> "s10rep180"; else -> "s10" }

        val container = (ctx as StashApp).container
        val transcriber: Transcriber? = when (packName) {
            "app" -> container.transcriber
            else -> {
                val only = if (packName == "tiny") KnownModels.TINY else KnownModels.BASE
                val engine = SherpaEngine.transcriber(ModelLocator(File(ctx.filesDir, ModelLocator.DIR), null, listOf(only)), null) { null }
                engine?.let { if (uncut) it else ChunkedTranscriber(it) }
            }
        }
        if (transcriber == null) {
            File(out, "work.txt").writeText("error=no-engine-in-this-build\n")
            return Result.failure()
        }
        // The measurement runs through Container.transcriber, as the app will; this process is thrown away afterwards.
        container.transcriber = transcriber
        val t = container.transcriber

        val pieces = if (uncut) listOf(pcm.samples.indices) else PauseSplitter.split(pcm).map { it.range }
        val forced = if (uncut) 0 else PauseSplitter.split(pcm).count { it.forcedEnd }
        val longestPieceMs = pieces.maxOf { AudioFormatConstants.samplesToMs((it.last - it.first + 1).toLong()) }
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

        val idle = pss()
        peakPss = idle
        sampling = true
        val sampler = Thread {
            while (sampling) { val v = pss(); if (v > peakPss) peakPss = v; SystemClock.sleep(200) }
        }.apply { isDaemon = true; start() }

        val t0 = SystemClock.elapsedRealtime()
        val r = t.transcribe(pcm, TranscribeOptions(place = place))
        val wall = SystemClock.elapsedRealtime() - t0
        sampling = false
        sampler.join(1000)
        val peak = maxOf(peakPss, pss())
        val engineId = t.id
        t.release()

        val secs = pcm.durationMs / 1000.0
        val lines = mutableListOf(
            "# tag=$tag id=$engineId pack=$packName length=$length place=$place threads=${place.threads} uncut=$uncut pid=${Process.myPid()} processAgeMs=$processAgeMs",
            "# pieces=${pieces.size} forced=$forced longestPieceMs=$longestPieceMs pieceMs=${pieces.joinToString(",") { AudioFormatConstants.samplesToMs((it.last - it.first + 1).toLong()).toString() }}",
            "# pssIdleKb=$idle peakPssKb=$peak wallMs=$wall availMemMb=${mem.availMem / 1_000_000} totalMemMb=${mem.totalMem / 1_000_000} lowMemory=${mem.lowMemory}",
            "id\taudioSec\tprocMs\trtf\tpeakPssKb\tsegments\ttext",
        )
        val row: String
        when (r) {
            is TranscribeResult.Ok -> {
                lines += "$id\t${"%.2f".format(secs)}\t${r.procMs}\t${"%.3f".format(r.procMs / 1000.0 / secs)}\t$peak\t${r.chunks}\t${r.text}"
                row = "ok\t${r.procMs}\t${r.chunks}"
            }
            is TranscribeResult.Failed -> {
                lines += "# FAILED ${r.code} retryable=${r.retryable}"
                row = "FAILED_${r.code}\t-1\t-1"
            }
        }
        File(out, "summary.tsv").writeText(lines.joinToString("\n") + "\n")
        File(out, "work.txt").writeText(
            "enqueuedAt=${d.getLong("enqueuedAt", 0)} startedAt=$startedAt endedAt=${System.currentTimeMillis()}" +
                " interactiveAtStart=$interactiveAtStart interactiveAtEnd=${pm.isInteractive} pid=${Process.myPid()}\n"
        )
        // Numbers and names only: no words in the table.
        val table = File(ctx.filesDir, "spike26/long-notes/table.tsv")
        if (!table.exists()) table.writeText(
            "tag\tpack\tplace\tuncut\taudioMs\tpieces\tforced\tlongestPieceMs\tpssIdleKb\tpeakPssKb\tresult\tprocMs\tchunks\twallMs\tavailMemMb\ttotalMemMb\tprocessAgeMs\tinteractive\n"
        )
        table.appendText(
            "$tag\t$packName\t$place\t$uncut\t${pcm.durationMs}\t${pieces.size}\t$forced\t$longestPieceMs\t$idle\t$peak\t$row\t$wall\t" +
                "${mem.availMem / 1_000_000}\t${mem.totalMem / 1_000_000}\t$processAgeMs\t$interactiveAtStart\n"
        )
        return Result.success()
    }
}
