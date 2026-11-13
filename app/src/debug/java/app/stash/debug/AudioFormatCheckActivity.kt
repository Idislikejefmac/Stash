package app.stash.debug

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioTrack
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.stash.audio.AudioDecoder
import app.stash.audio.AudioEncoder
import app.stash.audio.EncoderFactory
import app.stash.audio.MediaCodecEncoder
import app.stash.audio.MicFailure
import app.stash.audio.MicRecorder
import app.stash.audio.Recorder
import app.stash.audio.RecordingSession
import app.stash.audio.StreamingEncoder
import app.stash.domain.AudioFormatConstants
import app.stash.domain.EncodedAudio
import app.stash.domain.EndEvent
import app.stash.domain.EndPause
import app.stash.domain.OpusContainer
import app.stash.domain.Pcm
import java.io.File
import app.stash.domain.AudioFormat as StoredFormat
import android.media.AudioFormat as PlatformAudioFormat

/**
 * Card 26b (DEBUG BUILDS ONLY). Records one note and encodes it WHILE it is being recorded, five ways at once:
 *  1. OPUS_12 through the app's own Ogg writer (E2, the default)
 *  2. OPUS_12 through MediaMuxer into an in-memory file (E1)
 *  3. OPUS_24 (own writer)
 *  4. forced AAC_32: Opus asked for, the Opus encoder hidden, so the real fallback path picks AAC in MPEG-4 (memfd)
 *  5. forced WAV: Opus asked for, the encoder made to fail on the 40th chunk, so the real fallback returns WAV
 * For each it shows bytes, bytes a minute, ms from Stop to finish, the most chunks ever waiting, decode time, decoded
 * length against the original, and a Play button (decoded from memory). "Only row 1" runs E2 alone, for a clean
 * Stop-to-finish time. Results are appended to files/spike26/format.txt (numbers and codec names; never sound).
 * A separate screen because RecorderCheckActivity (26a) is not to be edited by this card.
 *
 * Start with: adb shell am start -n app.stash/app.stash.debug.AudioFormatCheckActivity
 */
class AudioFormatCheckActivity : ComponentActivity() {
    private class Row(val label: String, val requested: StoredFormat, val factory: EncoderFactory) {
        var enc: StreamingEncoder? = null
        var out: EncodedAudio? = null
        var decoded: Pcm? = null
        var line = ""
    }

    private lateinit var log: TextView
    private lateinit var rowsBox: LinearLayout
    private lateinit var modeButton: Button
    private var onlyFirst = false
    private var threeMinutes = false
    private var session: RecordingSession? = null
    private var rows: List<Row> = emptyList()
    private var original: Pcm? = null
    private var track: AudioTrack? = null
    @Volatile private var stopping = false

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        say(if (granted) "Microphone allowed. Tap a Record button." else "Microphone refused.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 48, 24, 24) }
        fun button(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label; setOnClickListener { action() }; col.addView(this, ViewGroup.LayoutParams.MATCH_PARENT, 150)
        }
        modeButton = button("") { if (session == null) { onlyFirst = !onlyFirst; showMode() } }
        button("Record a sentence (ends 2.5 s after you stop speaking)") { record(three = false) }
        button("Record 3 minutes (ends by itself at the limit)") { record(three = true) }
        button("Stop") { stop() }
        button("Play the original (uncompressed, from memory)") { original?.let { play(it, "original") } ?: say("Nothing recorded yet.") }
        rowsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(rowsBox)
        log = TextView(this).apply { textSize = 14f; setTextIsSelectable(true) }
        col.addView(log)
        setContentView(ScrollView(this).apply { addView(col) })
        showMode()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) askMic.launch(Manifest.permission.RECORD_AUDIO)
        val p = app.stash.audio.PlatformEncoders()
        say("Encoders on this phone: Opus ${p.available(StoredFormat.OPUS_12)}, AAC ${p.available(StoredFormat.AAC_32)}.")
    }

    private fun showMode() { modeButton.text = if (onlyFirst) "Encoders: only OPUS_12 own writer (tap to change)" else "Encoders: all five at once (tap to change)" }

    private fun say(s: String) = runOnUiThread { log.append(s + "\n\n") }

    private fun makeRows(): List<Row> {
        val own = app.stash.audio.PlatformEncoders(OpusContainer.OWN_WRITER)
        val mux = app.stash.audio.PlatformEncoders(OpusContainer.MUXER)
        val noOpus = object : EncoderFactory {
            override fun available(format: StoredFormat) = !format.isOpus && mux.available(format)
            override fun create(format: StoredFormat) = mux.create(format)
        }
        val failing = object : EncoderFactory {
            override fun available(format: StoredFormat) = own.available(format)
            override fun create(format: StoredFormat): AudioEncoder = FailOn40(own.create(format))
        }
        val all = listOf(
            Row("OPUS_12, own Ogg writer (E2)", StoredFormat.OPUS_12, own),
            Row("OPUS_12, MediaMuxer in memory (E1)", StoredFormat.OPUS_12, mux),
            Row("OPUS_24, own Ogg writer", StoredFormat.OPUS_24, own),
            Row("Forced AAC_32 (Opus hidden)", StoredFormat.OPUS_12, noOpus),
            Row("Forced WAV (encoder fails at chunk 40)", StoredFormat.OPUS_12, failing),
        )
        return if (onlyFirst) all.take(1) else all
    }

    private class FailOn40(private val inner: AudioEncoder) : AudioEncoder by inner {
        private var n = 0
        override fun feed(chunk: ShortArray, len: Int) {
            if (++n == 40) throw IllegalStateException("forced failure at chunk 40")
            inner.feed(chunk, len)
        }
    }

    private fun record(three: Boolean) {
        if (session != null || stopping) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { askMic.launch(Manifest.permission.RECORD_AUDIO); return }
        threeMinutes = three
        rowsBox.removeAllViews(); original = null
        val rs = makeRows()
        rows = rs
        Thread {
            // Starting codecs takes a little while: never on the main thread.
            for (r in rs) {
                val e = StreamingEncoder(r.factory)
                val got = e.start(r.requested)
                r.enc = e
                if (got != r.requested) say("${r.label}: started as $got (${e.problem ?: "no reason"})")
            }
            runOnUiThread { beginMic(rs) }
        }.start()
    }

    private fun beginMic(rs: List<Row>) {
        val tee = TeeRecorder(MicRecorder(applicationContext)) { chunk, len -> for (r in rs) r.enc?.feed(chunk, len) }
        val s = RecordingSession(
            recorder = tee,
            pause = if (threeMinutes) EndPause.ONLY_DONE else EndPause.SHORT,
            clock = SystemClock::elapsedRealtime,
            onEnd = { e ->
                val ends = if (threeMinutes) e == EndEvent.STOP_LIMIT else e != EndEvent.WARN_LIMIT
                if (ends) runOnUiThread { stop() }
            },
            onFailure = { f -> say("Recorder failed: $f"); runOnUiThread { stop() } },
        )
        val failure = s.start()
        if (failure != null) { say("Could not record: $failure"); for (r in rs) r.enc?.abort(); return }
        session = s
        say(if (threeMinutes) "Recording for 3 minutes. Talk, or leave a radio on. Stop ends it early." else "Recording. Say a sentence, then stay quiet.")
    }

    private fun stop() {
        val s = session ?: return
        session = null
        stopping = true
        val rs = rows
        Thread {
            val result = s.finish() // the microphone is off from here: this is "Stop"
            original = result.pcm
            // Every encoder finishes at once, each timed from its own finish() call.
            val ts = rs.map { r -> Thread { r.out = r.enc?.finish() }.also { it.start() } }
            ts.forEach { it.join() }
            val dec = AudioDecoder()
            val sb = StringBuilder()
            sb.append("run at ${System.currentTimeMillis()} durationMs=${result.pcm.durationMs} loudest=${result.loudest} encoders=${rs.size}\n")
            for (r in rs) {
                val e = r.enc ?: continue
                val o = r.out ?: continue
                var decodeMs = -1L; var decodedMs = -1L; var err = "none"
                try {
                    val t = SystemClock.elapsedRealtime()
                    val p = dec.decode(o.bytes, o.mime)
                    decodeMs = SystemClock.elapsedRealtime() - t
                    decodedMs = p.durationMs; r.decoded = p
                } catch (t: Throwable) { err = "${t.javaClass.simpleName}: ${t.message}" }
                val perMin = if (o.durationMs > 0) o.bytes.size * 60_000L / o.durationMs else 0
                val diag = (e.lastEncoder as? MediaCodecEncoder)?.diagnostics ?: ""
                r.line = "${r.label}: format=${o.format} bytes=${o.bytes.size} bytesPerMin=$perMin stopToFinishMs=${e.finishMs} " +
                    "maxQueuedChunks=${e.maxQueued} decodeMs=$decodeMs decodedMs=$decodedMs originalMs=${result.pcm.durationMs} " +
                    "diffMs=${if (decodedMs >= 0) decodedMs - result.pcm.durationMs else "n/a"} problem=${e.problem ?: "none"} decodeError=$err $diag"
                sb.append(r.line).append('\n')
            }
            try {
                File(filesDir, "spike26").apply { mkdirs() }.let { File(it, "format.txt").appendText(sb.toString() + "\n") }
            } catch (_: Exception) { say("Could not write format.txt.") }
            runOnUiThread {
                stopping = false
                for (r in rs) {
                    rowsBox.addView(TextView(this).apply { text = r.line; textSize = 13f; setTextIsSelectable(true) })
                    rowsBox.addView(Button(this).apply {
                        text = "Play: ${r.label}"
                        setOnClickListener { r.decoded?.let { play(it, r.label) } ?: say("${r.label} did not decode.") }
                    }, ViewGroup.LayoutParams.MATCH_PARENT, 150)
                }
                say("Done. Written to files/spike26/format.txt.")
            }
        }.start()
    }

    /** Plays [pcm] (16 kHz mono) once, from memory, streamed so a three-minute note needs no huge buffer. */
    private fun play(pcm: Pcm, what: String) {
        if (session != null) { say("Stop recording first."); return }
        track?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(PlatformAudioFormat.Builder().setEncoding(PlatformAudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(AudioFormatConstants.SAMPLE_RATE).setChannelMask(PlatformAudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        t.play()
        Thread { try { t.write(pcm.samples, 0, pcm.samples.size) } catch (_: Exception) {} }.start()
        say("Playing $what, %.1f s.".format(pcm.durationMs / 1000.0))
    }

    override fun onDestroy() {
        session?.cancel(); session = null
        for (r in rows) r.enc?.abort()
        track?.release(); track = null
        original = null; rows = emptyList()
        super.onDestroy()
    }
}

/** Passes every chunk to [also] as well as to the session (on the recorder thread). */
private class TeeRecorder(private val inner: Recorder, private val also: (ShortArray, Int) -> Unit) : Recorder {
    override fun start(onChunk: (ShortArray, Int) -> Unit, onError: (MicFailure) -> Unit): MicFailure? =
        inner.start({ c, n -> also(c, n); onChunk(c, n) }, onError)
    override fun stop(): Pcm = inner.stop()
    override fun cancel() = inner.cancel()
}
