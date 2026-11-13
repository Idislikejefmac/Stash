package app.stash.debug

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Card 25 spike (throwaway, DEBUG ONLY). Runs one sherpa-onnx model folder over the owner's corpus
 * (`files/spike25/sNN.wav`, or a sub-folder such as `dec/opus16`) and writes, under
 * `files/spike25/out/<tag>/`, one `sNN.<tag>.txt` per sentence and a `summary.tsv` (timing, memory, text),
 * then a `done` marker. Models are pushed with adb to `files/spike25/models/<name>/`; they are never in
 * the APK or the repository. No logging of words (the summary file is the spike's own result file).
 */
class Spike25Runner(private val ctx: Context, private val p: Params, private val say: (String) -> Unit) {

    data class Params(
        val model: String,
        val tag: String,
        val threads: Int = 2,
        val vad: Boolean = false,
        val vadMax: Float = 20f,
        val vadPad: Float = 0f,
        val decoding: String = "greedy_search",
        val hotwords: String = "",
        val hotwordsScore: Float = 1.5f,
        val precision: String = "int8",
        val src: String = "",
        val ids: List<String> = emptyList(),
        val repeat: Int = 1,
        val pauseMs: Long = 0,
        val warmLoad: Boolean = true,
    )

    private enum class Kind { NEMO_CTC, MOONSHINE_V2, MOONSHINE_V1, WHISPER, NEMO_TRANSDUCER }

    private val root = File(ctx.filesDir, "spike25")
    private val out = File(root, "out/${p.tag}").also { it.mkdirs() }

    @Volatile private var peakPss = 0
    @Volatile private var phasePeak = 0
    @Volatile private var sampling = true

    fun run(): File {
        File(out, "done").delete()
        val summary = File(out, "summary.tsv")
        val lines = mutableListOf<String>()
        val sampler = Thread {
            val mi = Debug.MemoryInfo()
            while (sampling) {
                Debug.getMemoryInfo(mi)
                val v = mi.totalPss
                if (v > peakPss) peakPss = v
                if (v > phasePeak) phasePeak = v
                SystemClock.sleep(200)
            }
        }.apply { isDaemon = true; start() }
        try {
            val startPss = pssNow()
            val dir = File(root, "models/${p.model}")
            require(dir.isDirectory) { "No model folder ${dir.absolutePath}" }
            val t0 = SystemClock.elapsedRealtime()
            System.loadLibrary("sherpa-onnx-jni")
            val libMs = SystemClock.elapsedRealtime() - t0
            val (kind, cfg, files) = config(dir)
            lines += "# tag=${p.tag} model=${p.model} kind=$kind precision=${p.precision} threads=${p.threads} provider=cpu" +
                " decoding=${p.decoding} vad=${p.vad} vadMax=${p.vadMax} vadPad=${p.vadPad} hotwords=${p.hotwords.ifBlank { "-" }}" +
                " hotwordsScore=${p.hotwordsScore} src=${p.src.ifBlank { "wav" }} repeat=${p.repeat} sherpa-onnx=2.0.1" +
                " pid=${Process.myPid()}"
            lines += "# files: " + files.joinToString(" ") { "${it.name}=${it.length()}" }
            say("Loading ${p.model} ($kind)...")
            var t = SystemClock.elapsedRealtime()
            val rec = OfflineRecognizer(config = cfg)
            val coldMs = SystemClock.elapsedRealtime() - t
            val afterLoadPss = pssNow()
            lines += "# libLoadMs=$libMs coldLoadMs=$coldMs pssStartKb=$startPss pssAfterLoadKb=$afterLoadPss"
            say("Loaded: cold $coldMs ms.")
            val vad = if (p.vad) Vad(
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = File(root, "models/vad/silero_vad.onnx").absolutePath,
                        threshold = 0.5f, minSilenceDuration = 0.25f, minSpeechDuration = 0.25f,
                        windowSize = 512, maxSpeechDuration = p.vadMax,
                    ),
                    sampleRate = 16000, numThreads = 1, provider = "cpu", debug = false,
                )
            ) else null
            if (p.pauseMs > 0) {
                File(out, "ready").writeText("${Process.myPid()}\n")
                say("Pausing ${p.pauseMs} ms (pid ${Process.myPid()}).")
                SystemClock.sleep(p.pauseMs)
            }
            val srcDir = if (p.src.isBlank()) root else File(root, p.src)
            val wavs = srcDir.listFiles { f -> f.name.matches(Regex("s\\d\\d\\.wav")) }!!.sortedBy { it.name }
                .filter { p.ids.isEmpty() || it.name.removeSuffix(".wav") in p.ids }
            val bat0 = battery()
            val wall0 = SystemClock.elapsedRealtime()
            lines += "id\taudioSec\tprocMs\trtf\tpeakPssKb\tsegments\ttext"
            for (pass in 1..p.repeat) {
                for (w in wavs) {
                    val id = w.name.removeSuffix(".wav")
                    val audio = readWav(w)
                    val secs = audio.size / 16000.0
                    phasePeak = pssNow()
                    val ts = System.nanoTime()
                    val (text, segs) = if (vad != null) decodeWithVad(rec, vad, audio) else decode(rec, audio) to 1
                    val ms = (System.nanoTime() - ts) / 1e6
                    if (pass == 1) {
                        File(out, "$id.${p.tag}.txt").writeText(text.trim())
                        lines += "$id\t${"%.2f".format(secs)}\t${"%.0f".format(ms)}\t${"%.3f".format(ms / 1000 / secs)}\t$phasePeak\t$segs\t${text.trim()}"
                        say("$id  ${"%.2f".format(ms / 1000 / secs)}x  ${"%.0f".format(ms)} ms")
                    }
                }
                if (p.repeat > 1) say("Pass $pass of ${p.repeat} done.")
            }
            val wallMs = SystemClock.elapsedRealtime() - wall0
            val bat1 = battery()
            val rtfs = lines.filter { !it.startsWith("#") && !it.startsWith("id\t") }.map { it.split('\t')[3].toDouble() }.sorted()
            val med = if (rtfs.isEmpty()) 0.0 else rtfs[rtfs.size / 2].let { if (rtfs.size % 2 == 0) (it + rtfs[rtfs.size / 2 - 1]) / 2 else it }
            lines += "# medianRtf=${"%.3f".format(med)} worstRtf=${"%.3f".format(rtfs.maxOrNull() ?: 0.0)} peakPssKb=$peakPss" +
                " vmHwmKb=${vmHwm()} wallMs=$wallMs passes=${p.repeat}"
            lines += "# battery start=$bat0 end=$bat1"
            rec.release(); vad?.release()
            if (p.warmLoad) { // warm load: same process, files in the page cache; measured after the decode peak
                sampling = false
                t = SystemClock.elapsedRealtime()
                val again = OfflineRecognizer(config = cfg)
                lines += "# warmLoadMs=${SystemClock.elapsedRealtime() - t}"
                again.release()
            }
        } catch (e: Throwable) {
            lines += "# ERROR ${e.javaClass.name}: ${e.message}"
            say("ERROR ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            sampling = false
            sampler.join(1000)
            summary.writeText(lines.joinToString("\n") + "\n")
            File(out, "done").writeText("ok\n")
        }
        return summary
    }

    private fun decode(rec: OfflineRecognizer, samples: FloatArray): String {
        val s = rec.createStream()
        try {
            s.acceptWaveform(samples, 16000)
            rec.decode(s)
            return rec.getResult(s).text
        } finally { s.release() }
    }

    private fun decodeWithVad(rec: OfflineRecognizer, vad: Vad, samples: FloatArray): Pair<String, Int> {
        vad.reset()
        val sb = StringBuilder()
        var segs = 0
        val pad = (p.vadPad * 16000).toInt()
        var lastEnd = 0
        fun drain() {
            while (!vad.empty()) {
                val seg = vad.front(); vad.pop()
                // Optional padding from the original audio around each speech segment (the VAD starts a segment
                // at the first speech frame, which can clip the first word); never overlapping the previous one.
                val audio = if (pad == 0) seg.samples else {
                    val a = maxOf(lastEnd, seg.start.toInt() - pad)
                    val b = minOf(samples.size, seg.start.toInt() + seg.samples.size + pad)
                    lastEnd = b
                    samples.copyOfRange(a, maxOf(a, b))
                }
                val t = decode(rec, audio).trim()
                segs++
                if (t.isNotEmpty()) sb.append(if (sb.isEmpty()) t else " $t")
            }
        }
        var i = 0
        while (i < samples.size) {
            val chunk = FloatArray(512)
            System.arraycopy(samples, i, chunk, 0, minOf(512, samples.size - i))
            vad.acceptWaveform(chunk)
            drain()
            i += 512
        }
        vad.flush(); drain()
        return sb.toString() to segs
    }

    private fun config(dir: File): Triple<Kind, OfflineRecognizerConfig, List<File>> {
        val names = dir.list()!!.toSet()
        fun pick(vararg cands: String): File? = cands.firstOrNull { it in names }?.let { File(dir, it) }
        fun prec(base: String): File? = if (p.precision == "int8") pick("$base.int8.onnx", "$base.onnx") else pick("$base.onnx", "$base.int8.onnx")
        val tokens = pick("tokens.txt") ?: names.firstOrNull { it.endsWith("-tokens.txt") }?.let { File(dir, it) }
            ?: error("no tokens file")
        val feat = FeatureConfig(sampleRate = 16000, featureDim = 80, dither = 0f)
        val used = mutableListOf(tokens)
        val kind: Kind
        val mc: OfflineModelConfig
        when {
            "decoder_model_merged.ort" in names || "decoder_model_merged.onnx" in names -> {
                kind = Kind.MOONSHINE_V2
                val enc = pick("encoder_model.ort", "encoder_model.onnx")!!
                val dec = pick("decoder_model_merged.ort", "decoder_model_merged.onnx")!!
                used += listOf(enc, dec)
                mc = OfflineModelConfig(
                    moonshine = OfflineMoonshineModelConfig(encoder = enc.absolutePath, mergedDecoder = dec.absolutePath),
                    tokens = tokens.absolutePath, numThreads = p.threads, provider = "cpu", debug = false,
                )
            }
            "preprocess.onnx" in names -> {
                kind = Kind.MOONSHINE_V1
                val f = listOf(File(dir, "preprocess.onnx"), prec("encode")!!, prec("uncached_decode")!!, prec("cached_decode")!!)
                used += f
                mc = OfflineModelConfig(
                    moonshine = OfflineMoonshineModelConfig(
                        preprocessor = f[0].absolutePath, encoder = f[1].absolutePath,
                        uncachedDecoder = f[2].absolutePath, cachedDecoder = f[3].absolutePath,
                    ),
                    tokens = tokens.absolutePath, numThreads = p.threads, provider = "cpu", debug = false,
                )
            }
            names.any { it.contains("joiner") } -> {
                kind = Kind.NEMO_TRANSDUCER
                val enc = prec("encoder")!!; val dec = prec("decoder")!!; val joi = prec("joiner")!!
                used += listOf(enc, dec, joi)
                val bpe = pick("bpe.vocab")
                mc = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(encoder = enc.absolutePath, decoder = dec.absolutePath, joiner = joi.absolutePath),
                    tokens = tokens.absolutePath, numThreads = p.threads, provider = "cpu", debug = false,
                    modelType = "nemo_transducer",
                    modelingUnit = if (p.hotwords.isNotBlank()) "bpe" else "",
                    bpeVocab = if (p.hotwords.isNotBlank() && bpe != null) bpe.absolutePath else "",
                )
            }
            names.any { it.contains("-encoder") } -> {
                kind = Kind.WHISPER
                val stem = names.first { it.contains("-encoder") }.substringBefore("-encoder")
                val enc = prec("$stem-encoder")!!; val dec = prec("$stem-decoder")!!
                used += listOf(enc, dec)
                mc = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(encoder = enc.absolutePath, decoder = dec.absolutePath, language = "en", task = "transcribe"),
                    tokens = tokens.absolutePath, numThreads = p.threads, provider = "cpu", debug = false,
                    modelType = "whisper",
                )
            }
            else -> {
                kind = Kind.NEMO_CTC
                val m = prec("model")!!
                used += m
                mc = OfflineModelConfig(
                    nemo = OfflineNemoEncDecCtcModelConfig(model = m.absolutePath),
                    tokens = tokens.absolutePath, numThreads = p.threads, provider = "cpu", debug = false,
                )
            }
        }
        val rc = OfflineRecognizerConfig(
            featConfig = feat, modelConfig = mc, decodingMethod = p.decoding, maxActivePaths = 4,
            hotwordsFile = if (p.hotwords.isNotBlank()) File(dir, p.hotwords).absolutePath else "",
            hotwordsScore = p.hotwordsScore,
        )
        return Triple(kind, rc, used)
    }

    private fun pssNow(): Int { val mi = Debug.MemoryInfo(); Debug.getMemoryInfo(mi); return mi.totalPss }

    private fun vmHwm(): String = runCatching {
        File("/proc/self/status").readLines().first { it.startsWith("VmHWM") }.substringAfter(":").trim().removeSuffix("kB").trim()
    }.getOrDefault("?")

    private fun battery(): String {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val temp = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        val thermal = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
        return "level=$level tempTenthsC=$temp thermal=$thermal"
    }

    companion object {
        fun fromExtras(i: Intent): Params {
            val model = i.getStringExtra("model") ?: ""
            return Params(
                model = model,
                tag = i.getStringExtra("tag") ?: model,
                threads = i.getIntExtra("threads", 2),
                vad = i.getBooleanExtra("vad", false),
                vadMax = i.getFloatExtra("vadMax", 20f),
                vadPad = i.getFloatExtra("vadPad", 0f),
                decoding = i.getStringExtra("decoding") ?: "greedy_search",
                hotwords = i.getStringExtra("hotwords") ?: "",
                hotwordsScore = i.getFloatExtra("hwscore", 1.5f),
                precision = i.getStringExtra("precision") ?: "int8",
                src = i.getStringExtra("src") ?: "",
                ids = (i.getStringExtra("ids") ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() },
                repeat = i.getIntExtra("repeat", 1),
                pauseMs = i.getIntExtra("pauseMs", 0).toLong(),
                warmLoad = i.getBooleanExtra("warm", true),
            )
        }

        /** 16-bit PCM WAV (any rate, mono or stereo) to 16 kHz mono floats. */
        fun readWav(f: File): FloatArray {
            RandomAccessFile(f, "r").use { raf ->
                val bytes = ByteArray(raf.length().toInt()); raf.readFully(bytes)
                val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                var pos = 12
                var rate = 16000; var ch = 1
                var dataOff = -1; var dataLen = 0
                while (pos + 8 <= bytes.size) {
                    val id = String(bytes, pos, 4, Charsets.US_ASCII)
                    val len = b.getInt(pos + 4)
                    if (id == "fmt ") { ch = b.getShort(pos + 10).toInt(); rate = b.getInt(pos + 12) }
                    if (id == "data") { dataOff = pos + 8; dataLen = minOf(len, bytes.size - dataOff); break }
                    pos += 8 + len + (len and 1)
                }
                require(dataOff > 0) { "no data chunk in ${f.name}" }
                val frames = dataLen / 2 / ch
                val mono = FloatArray(frames) { k ->
                    var s = 0f
                    for (c in 0 until ch) s += b.getShort(dataOff + (k * ch + c) * 2) / 32768f
                    s / ch
                }
                return resampleTo16k(mono, rate)
            }
        }

        fun resampleTo16k(x: FloatArray, rate: Int): FloatArray {
            if (rate == 16000) return x
            if (rate % 16000 == 0) { // integer factor: boxcar average then decimate
                val k = rate / 16000
                return FloatArray(x.size / k) { i -> var s = 0f; for (j in 0 until k) s += x[i * k + j]; s / k }
            }
            val n = (x.size.toLong() * 16000 / rate).toInt()
            return FloatArray(n) { i ->
                val pos = i.toDouble() * rate / 16000; val a = pos.toInt(); val fr = (pos - a).toFloat()
                if (a + 1 < x.size) x[a] * (1 - fr) + x[a + 1] * fr else x[minOf(a, x.size - 1)]
            }
        }

        fun writeWav16k(f: File, pcm: ShortArray) {
            val b = ByteBuffer.allocate(44 + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()); b.putInt(36 + pcm.size * 2); b.put("WAVE".toByteArray())
            b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1)
            b.putInt(16000); b.putInt(32000); b.putShort(2); b.putShort(16)
            b.put("data".toByteArray()); b.putInt(pcm.size * 2)
            for (s in pcm) b.putShort(s)
            f.writeBytes(b.array())
        }
    }
}
