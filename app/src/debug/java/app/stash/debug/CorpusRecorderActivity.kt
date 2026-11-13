package app.stash.debug

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.io.RandomAccessFile

/**
 * Card 25 spike, leg A. DEBUG BUILDS ONLY, throwaway. No transcription of our own here.
 *
 * Reads `files/spike25/corpus.txt` (pushed with adb; one line per sentence: `id<TAB>text<TAB>key tokens`,
 * lines starting with # ignored; a text may start with a tag such as [noise], [quiet], [silence], [free]).
 * Records each sentence as 16 kHz mono 16-bit PCM WAV (`sNN.wav`) and can store the phone's own live
 * recogniser text for the same sentence (`sNN.phone.txt`). The owner's recordings stay on the phone and
 * out of the repository; delete `files/spike25/` when the spike is over.
 *
 * Start: adb shell am start -n app.stash/app.stash.debug.CorpusRecorderActivity
 */
class CorpusRecorderActivity : ComponentActivity() {
    private companion object { const val RATE = 16_000 }

    private data class Item(val id: String, val text: String, val tokens: String)

    private val dir by lazy { File(filesDir, "spike25").also { it.mkdirs() } }
    private var items: List<Item> = emptyList()
    private var index = 0

    private lateinit var title: TextView
    private lateinit var sentence: TextView
    private lateinit var status: TextView

    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var recording = false
    @Volatile private var peak = 0
    private var player: MediaPlayer? = null
    private var recognizer: SpeechRecognizer? = null

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 40, 24, 24) }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label; setOnClickListener { action() }
            col.addView(this, ViewGroup.LayoutParams.MATCH_PARENT, 150)
        }
        title = TextView(this).apply { textSize = 16f }
        sentence = TextView(this).apply { textSize = 24f; setPadding(0, 16, 0, 16) }
        status = TextView(this).apply { textSize = 15f; setTextIsSelectable(true) }
        col.addView(title); col.addView(sentence)
        button("Record") { startRecording() }
        button("Stop") { stopRecording() }
        button("Play it back") { play() }
        button("Phone's own recogniser, live (baseline)") { baseline(playRecording = false) }
        button("Play THIS recording aloud while the recogniser listens") { baseline(playRecording = true) }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun navButton(label: String, step: Int) = Button(this).apply {
            text = label; setOnClickListener { go(index + step) }
            nav.addView(this, LinearLayout.LayoutParams(0, 150, 1f))
        }
        navButton("< Previous", -1); navButton("Next >", +1)
        col.addView(nav)
        col.addView(ScrollView(this).apply { addView(status) })
        setContentView(col)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
        load()
        // Automation hook for the PC-speaker test: am start ... --ei idx 4 --ez listen true
        if (intent.hasExtra("idx")) go(intent.getIntExtra("idx", 0))
        if (intent.getBooleanExtra("listen", false)) baseline(playRecording = false, external = true)
    }

    private fun load() {
        val f = File(dir, "corpus.txt")
        if (!f.exists()) {
            title.text = "No corpus file"
            sentence.text = "Push files/spike25/corpus.txt with adb."
            return
        }
        items = f.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull {
            val p = it.split('\t')
            if (p.size >= 2) Item(p[0].trim(), p[1].trim(), p.getOrElse(2) { "" }.trim()) else null
        }
        go(items.indexOfFirst { !File(dir, "${it.id}.wav").exists() }.let { if (it < 0) 0 else it })
    }

    private fun go(i: Int) {
        if (items.isEmpty() || recording) return
        index = i.coerceIn(0, items.lastIndex)
        val it = items[index]
        val done = items.count { f -> File(dir, "${f.id}.wav").exists() }
        title.text = "Sentence ${index + 1} of ${items.size}   (recorded: $done)"
        sentence.text = it.text
        val wav = File(dir, "${it.id}.wav")
        val phone = File(dir, "${it.id}.phone.txt")
        status.text = buildString {
            if (it.tokens.isNotBlank()) append("Key tokens: ${it.tokens}\n\n")
            append(if (wav.exists()) "Recorded: ${wav.length()} bytes (${(wav.length() - 44) / (RATE * 2.0)} s)\n" else "Not recorded yet.\n")
            if (phone.exists()) append("Phone recogniser heard: \"${phone.readText()}\"\n")
        }
    }

    private fun startRecording() {
        if (recording || items.isEmpty()) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askMic.launch(Manifest.permission.RECORD_AUDIO); return
        }
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
            .setBufferSizeInBytes(minBuf * 4).build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) { status.text = "Recorder did not start."; return }
        audioRecord = rec
        val target = File(dir, "${items[index].id}.wav")
        peak = 0
        recording = true
        rec.startRecording()
        status.text = "Recording... say it, then tap Stop."
        thread = Thread {
            RandomAccessFile(target, "rw").use { f ->
                f.setLength(0)
                f.write(ByteArray(44)) // header filled in at the end
                val buf = ByteArray(3200)
                var total = 0L
                while (recording) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    f.write(buf, 0, n)
                    total += n
                    var i = 0
                    while (i + 1 < n) {
                        val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toInt()
                        val a = if (s < 0) -s else s
                        if (a > peak) peak = a
                        i += 2
                    }
                }
                f.seek(0); f.write(wavHeader(total))
            }
        }.also { it.start() }
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        thread?.join()
        audioRecord?.stop(); audioRecord?.release(); audioRecord = null
        go(index)
        status.append("\nLoudest sample: $peak of 32767" + if (peak < 1500) "  (very quiet: redo it?)" else "")
    }

    private fun play() {
        val f = File(dir, "${items.getOrNull(index)?.id}.wav")
        if (!f.exists() || recording) return
        player?.release()
        player = MediaPlayer().apply { setDataSource(f.absolutePath); prepare(); start() }
    }

    /** The phone's own recogniser, live, for the same sentence. Text only; no audio is kept. */
    private fun baseline(playRecording: Boolean, external: Boolean = false) {
        if (recording || items.isEmpty()) return
        val wav = File(dir, "${items[index].id}.wav")
        if (playRecording && !external && !wav.exists()) { status.append("\nRecord this sentence first."); return }
        recognizer?.destroy()
        val r = if (SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        else SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        val id = items[index].id
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                runOnUiThread {
                    if (external) {
                        status.append("\nListening for sound from another speaker (the PC).")
                    } else if (playRecording) {
                        status.append("\nPlaying the recording aloud; keep quiet and hold the phone still.")
                        player?.release()
                        player = MediaPlayer().apply { setDataSource(wav.absolutePath); prepare(); start() }
                    } else status.append("\nListening (baseline)... say the sentence.")
                }
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                File(dir, if (playRecording || external) "$id.playback.txt" else "$id.phone.txt").writeText(t)
                runOnUiThread { go(index) }
            }
            override fun onError(error: Int) {
                if (external) File(dir, "$id.playback.err").writeText(error.toString())
                runOnUiThread { status.append("\nBaseline error code $error. Try again.") }
            }
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        })
    }

    private fun wavHeader(dataBytes: Long): ByteArray {
        val byteRate = RATE * 2
        val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((36 + dataBytes).toInt()); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1)
        b.putInt(RATE); b.putInt(byteRate); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dataBytes.toInt())
        return b.array()
    }

    override fun onDestroy() {
        recording = false
        recognizer?.destroy(); player?.release(); audioRecord?.release()
        super.onDestroy()
    }
}
