package app.stash.debug

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale

/**
 * Card 25 spike (throwaway, DEBUG ONLY): ML Kit GenAI Speech Recognition (alpha) over the same corpus, MEASURED
 * ONLY (owner's yes 2026-11-12; Google's terms; it reports usage metrics to Google). Writes
 * `files/spike25/out/<tag>/summary.tsv` in the same format as [Spike25Runner]. The model runs in a system
 * service, so this process's memory does not include it.
 */
class Spike25MlKit(private val ctx: Context, private val tag: String, private val mode: Int,
                   private val allowDownload: Boolean, private val say: (String) -> Unit) {
    fun run() = runBlocking {
        val root = File(ctx.filesDir, "spike25")
        val out = File(root, "out/$tag").also { it.mkdirs() }
        File(out, "done").delete()
        val lines = mutableListOf("# tag=$tag engine=mlkit-genai-speech-1.0.0-alpha2 mode=${if (mode == 1) "advanced" else "basic"} locale=en-US")
        try {
            val rec = SpeechRecognition.getClient(speechRecognizerOptions {
                locale = Locale.US
                preferredMode = mode
            })
            val status = rec.checkStatus()
            lines += "# featureStatus=$status (0 unavailable, 1 downloadable, 2 downloading, 3 available)"
            say("ML Kit status $status")
            if (status == 1 || status == 2) {
                if (!allowDownload) { lines += "# not downloaded (run with --ez download true)"; return@runBlocking finish(out, lines) }
                val t = SystemClock.elapsedRealtime()
                var bytes = 0L
                rec.download().collect { d ->
                    when (d) {
                        is DownloadStatus.DownloadStarted -> {
                            bytes = d.bytesToDownload; say("download started $bytes bytes")
                            // Card 25 brief: anything over about 700 MB needs the owner's decision.
                            if (bytes > 700_000_000L) throw IllegalStateException("download of $bytes bytes is over 700 MB; stopped")
                        }
                        is DownloadStatus.DownloadProgress -> {}
                        is DownloadStatus.DownloadCompleted -> say("download completed")
                        is DownloadStatus.DownloadFailed -> { lines += "# downloadFailed ${d.e.message}"; say("download failed ${d.e.message}") }
                    }
                }
                lines += "# downloadBytes=$bytes downloadMs=${SystemClock.elapsedRealtime() - t} statusAfter=${rec.checkStatus()}"
            }
            val tw = SystemClock.elapsedRealtime()
            runCatching { rec.warmup() }.onFailure { lines += "# warmupError ${it.message}" }
            lines += "# coldLoadMs=${SystemClock.elapsedRealtime() - tw} (warmup())"
            lines += "id\taudioSec\tprocMs\trtf\tpeakPssKb\tsegments\ttext"
            val wavs = root.listFiles { f -> f.name.matches(Regex("s\\d\\d\\.wav")) }!!.sortedBy { it.name }
            val rtfs = mutableListOf<Double>()
            for (w in wavs) {
                val id = w.name.removeSuffix(".wav")
                val secs = (w.length() - 44) / 32000.0
                val src = AudioSource.fromFile(w)
                val sb = StringBuilder(); var finals = 0; var err = ""
                val t = System.nanoTime()
                try {
                    withTimeoutOrNull(120_000) {
                        rec.startRecognition(speechRecognizerRequest { audioSource = src }).collect { r ->
                            when (r) {
                                is SpeechRecognizerResponse.FinalTextResponse -> {
                                    finals++; if (r.text.isNotBlank()) sb.append(if (sb.isEmpty()) r.text.trim() else " " + r.text.trim())
                                }
                                is SpeechRecognizerResponse.ErrorResponse -> err = "ERROR ${r.e.errorCode} ${r.e.message}"
                                else -> {}
                            }
                        }
                    } ?: run { err = "TIMEOUT" }
                } catch (e: Throwable) { err = "EXC ${e.javaClass.simpleName} ${e.message}" } finally { runCatching { src.close() } }
                val ms = (System.nanoTime() - t) / 1e6
                rtfs += ms / 1000 / secs
                val text = if (err.isNotEmpty() && sb.isEmpty()) "" else sb.toString()
                File(out, "$id.$tag.txt").writeText(text)
                lines += "$id\t${"%.2f".format(secs)}\t${"%.0f".format(ms)}\t${"%.3f".format(ms / 1000 / secs)}\t${pss()}\t$finals\t$text"
                if (err.isNotEmpty()) lines += "# $id $err"
                say("$id ${"%.0f".format(ms)} ms $err")
            }
            rtfs.sort()
            if (rtfs.isNotEmpty()) lines += "# medianRtf=${"%.3f".format(rtfs[rtfs.size / 2])} worstRtf=${"%.3f".format(rtfs.last())} appPssKb=${pss()} (model runs outside this process)"
            rec.close()
        } catch (e: Throwable) {
            lines += "# ERROR ${e.javaClass.name}: ${e.message}"
            say("ERROR ${e.message}")
        }
        finish(out, lines)
    }

    private fun finish(out: File, lines: List<String>) {
        File(out, "summary.tsv").writeText(lines.joinToString("\n") + "\n")
        File(out, "done").writeText("ok\n")
    }

    private fun pss(): Int { val mi = Debug.MemoryInfo(); Debug.getMemoryInfo(mi); return mi.totalPss }
}
