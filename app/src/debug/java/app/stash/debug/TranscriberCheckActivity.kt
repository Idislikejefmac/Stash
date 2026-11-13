package app.stash.debug

import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.stash.StashApp
import app.stash.domain.Wav
import app.stash.speech.TranscribeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Debug builds only (card 26d, phone checks). Writes out the owner's 15 corpus recordings (`files/spike25/sNN.wav`,
 * left by card 25) through `Container.transcriber`, the same seam the app uses, and measures cold and warm load and
 * memory. Results go to `files/spike26/transcriber-check/summary.tsv` in the card 25 spike's column layout, so the
 * texts can be compared line by line with `docs/spike25/results/moonshine-base-t2/summary.tsv`.
 *
 *   adb shell am start -n app.stash/app.stash.debug.TranscriberCheckActivity [--ez autostart true] [--es ids s01]
 *
 * Memory is total PSS sampled every 200 ms in this process (as card 25 did), with only this screen open.
 */
class TranscriberCheckActivity : ComponentActivity() {
    private lateinit var log: TextView
    @Volatile private var sampling = false
    @Volatile private var peakPss = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log = TextView(this).apply { textSize = 14f; setPadding(24, 24, 24, 24); setTextIsSelectable(true) }
        val run = Button(this).apply { text = "Write out the corpus through Container.transcriber"; minHeight = 168; setOnClickListener { start() } }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(run); addView(log) }
        setContentView(ScrollView(this).apply { addView(col) })
        say("Engine: ${(application as StashApp).container.transcriber.id ?: "none"}; " +
            "availability ${(application as StashApp).container.transcriber.availability()}")
        if (intent.getBooleanExtra("autostart", false)) start()
    }

    private fun say(s: String) = runOnUiThread { log.append(s + "\n") }

    private fun pss(): Int { val mi = Debug.MemoryInfo(); Debug.getMemoryInfo(mi); return mi.totalPss }

    private fun start() {
        val ids = (intent.getStringExtra("ids") ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val t = (application as StashApp).container.transcriber
        lifecycleScope.launch {
            val lines = mutableListOf<String>()
            val out = File(filesDir, "spike26/transcriber-check").apply { mkdirs() }
            withContext(Dispatchers.IO) {
                val wavs = File(filesDir, "spike25").listFiles { f -> f.name.matches(Regex("s\\d\\d\\.wav")) }.orEmpty()
                    .sortedBy { it.name }.filter { ids.isEmpty() || it.name.removeSuffix(".wav") in ids }
                t.release()
                SystemClock.sleep(500)
                val idle = pss()
                sampling = true
                peakPss = idle
                val sampler = Thread {
                    while (sampling) { val v = pss(); if (v > peakPss) peakPss = v; SystemClock.sleep(200) }
                }.apply { isDaemon = true; start() }
                lines += "# tag=transcriber-check id=${t.id} wavs=${wavs.size} pssIdleKb=$idle"
                lines += "id\taudioSec\tprocMs\trtf\tpeakPssKb\tsegments\ttext"
                var first = true
                for (w in wavs) {
                    val pcm = Wav.decode(w.readBytes())
                    peakPss = pss()
                    val t0 = SystemClock.elapsedRealtime()
                    val r = t.transcribe(pcm)
                    val wall = SystemClock.elapsedRealtime() - t0
                    val secs = pcm.durationMs / 1000.0
                    val id = w.name.removeSuffix(".wav")
                    when (r) {
                        is TranscribeResult.Ok -> {
                            if (first) lines += "# coldLoadMs=${wall - r.procMs} (first call wall $wall ms less engine time ${r.procMs} ms)"
                            lines += "$id\t${"%.2f".format(secs)}\t${r.procMs}\t${"%.3f".format(r.procMs / 1000.0 / secs)}\t$peakPss\t${r.chunks}\t${r.text}"
                            say("$id  ${r.procMs} ms  peak ${peakPss / 1024} MB")
                        }
                        is TranscribeResult.Failed -> {
                            lines += "# $id FAILED ${r.code} retryable=${r.retryable}"
                            say("$id FAILED ${r.code}")
                        }
                    }
                    first = false
                }
                val peakAll = peakPss
                t.release()
                SystemClock.sleep(1500)
                val afterRelease = pss()
                // Warm load: the same process, the files now in the page cache.
                val warm = wavs.firstOrNull()?.let { w ->
                    val pcm = Wav.decode(w.readBytes())
                    val t0 = SystemClock.elapsedRealtime()
                    val r = t.transcribe(pcm)
                    val wall = SystemClock.elapsedRealtime() - t0
                    t.release()
                    if (r is TranscribeResult.Ok) wall - r.procMs else -1
                }
                sampling = false
                sampler.join(1000)
                SystemClock.sleep(1000)
                lines += "# peakPssKb=$peakAll pssAfterReleaseKb=$afterRelease pssEndKb=${pss()} warmLoadMs=${warm ?: -1}"
                File(out, "summary.tsv").writeText(lines.joinToString("\n") + "\n")
                File(out, "done").writeText("ok\n")
            }
            say(lines.filter { it.startsWith("#") }.joinToString("\n"))
            say("Done: files/spike26/transcriber-check/summary.tsv")
        }
    }
}
