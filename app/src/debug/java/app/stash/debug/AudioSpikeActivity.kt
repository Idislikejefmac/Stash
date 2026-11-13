package app.stash.debug

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Card 24 spike. DEBUG BUILDS ONLY. Throwaway: no schema, no encryption, nothing in the release path.
 * Results are shown on screen only (the project forbids logging).
 *
 * Questions: can the app record the microphone itself and feed that same audio to Android's
 * recogniser (EXTRA_AUDIO_SOURCE)? Can a saved recording be transcribed again later? How big is
 * the stored audio (raw PCM vs AAC)?
 *
 * Start with: adb shell am start -n app.stash/app.stash.debug.AudioSpikeActivity
 */
class AudioSpikeActivity : ComponentActivity() {
    private companion object {
        const val RATE = 16_000
        const val AAC_BITRATE = 32_000
    }

    private lateinit var log: TextView
    private val spikeDir by lazy { File(filesDir, "spike").also { it.mkdirs() } }
    private val pcmFile by lazy { File(spikeDir, "last.pcm") }
    private val m4aFile by lazy { File(spikeDir, "last.m4a") }

    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    @Volatile private var recording = false
    @Volatile private var peak = 0
    private var recordStartedAt = 0L
    private var recognizer: SpeechRecognizer? = null
    private var player: MediaPlayer? = null
    private var sessionStartedAt = 0L
    private var mode = 0

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        say(if (granted) "Microphone allowed. Tap Record." else "Microphone refused.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 48, 24, 24) }
        fun button(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label
            setOnClickListener { action() }
            col.addView(this, ViewGroup.LayoutParams.MATCH_PARENT, 160)
        }
        val modeNames = listOf(
            "on-device, all extras", "default service, all extras",
            "on-device, audio source only", "default service, audio source only",
            "CONCURRENT: normal dictation + our own recorder",
            "FILE descriptor (not a pipe) for 'Transcribe again'",
        )
        lateinit var modeButton: Button
        modeButton = button("Mode: ${modeNames[0]} (tap to change)") {
            mode = (mode + 1) % modeNames.size
            modeButton.text = "Mode: ${modeNames[mode]} (tap to change)"
        }
        button("1. Record (own recorder, fed to recogniser)") { startRecording() }
        button("2. Stop") { stopRecording() }
        button("3. Play the saved audio (m4a)") { play() }
        button("4. Transcribe the saved audio again (from file)") { retranscribe() }
        button("5. Normal dictation (no pipe), to compare") { normalDictation() }
        button("Delete spike files") { spikeDir.deleteRecursively(); say("Deleted.") }
        log = TextView(this).apply { textSize = 15f; setTextIsSelectable(true) }
        col.addView(ScrollView(this).apply { addView(log) })
        setContentView(col)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
        say("On-device recogniser available: ${SpeechRecognizer.isOnDeviceRecognitionAvailable(this)}")
    }

    private fun say(line: String) = runOnUiThread { log.append(line + "\n\n") }

    // ---- 1 and 2: record ourselves, feed the recogniser through a pipe, keep the PCM ----

    private fun startRecording() {
        if (recording) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askMic.launch(Manifest.permission.RECORD_AUDIO); return
        }
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        val toRecognizer: OutputStream = ParcelFileDescriptor.AutoCloseOutputStream(writeSide)

        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
            )
            .setBufferSizeInBytes(minBuf * 4)
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            say("AudioRecord did not start (state ${rec.state})."); readSide.close(); toRecognizer.close(); return
        }
        audioRecord = rec

        sessionStartedAt = SystemClock.elapsedRealtime()
        // Mode 4: no pipe. The recogniser opens the microphone itself while our recorder runs too.
        val concurrent = mode == 4
        if (!concurrent) {
            startRecognizer(readSide, "pipe (live)")
        }
        readSide.close() // the recogniser holds its own duplicate

        recording = true
        peak = 0
        recordStartedAt = SystemClock.elapsedRealtime()
        rec.startRecording()
        if (concurrent) startRecognizer(null, "CONCURRENT (recogniser owns mic, we record too)")
        recordThread = Thread {
            val buf = ByteArray(3200)
            var pipeOk = true
            FileOutputStream(pcmFile).use { file ->
                while (recording) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    file.write(buf, 0, n)
                    var i = 0
                    while (i + 1 < n) {
                        val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toInt()
                        val a = if (s < 0) -s else s
                        if (a > peak) peak = a
                        i += 2
                    }
                    if (pipeOk) {
                        try { toRecognizer.write(buf, 0, n) } catch (e: Exception) { pipeOk = false }
                    }
                }
            }
            try { toRecognizer.close() } catch (_: Exception) {}
        }.also { it.start() }
        say("Recording... speak, then tap Stop.")
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        val seconds = (SystemClock.elapsedRealtime() - recordStartedAt) / 1000.0
        recordThread?.join()
        audioRecord?.stop(); audioRecord?.release(); audioRecord = null
        runOnUiThread { recognizer?.stopListening() }
        val pcm = pcmFile.length()
        Thread {
            val t0 = SystemClock.elapsedRealtime()
            val ok = runCatching { encodeAac(pcmFile, m4aFile) }
            val ms = SystemClock.elapsedRealtime() - t0
            say(
                "Recorded %.1f s. Loudest sample %d of 32767 (0 or a few = silence). PCM %d bytes (%.0f KB/min). AAC %s (encode %d ms)."
                    .format(seconds, peak, pcm, pcm / seconds * 60 / 1024, ok.fold(
                        { "${m4aFile.length()} bytes (%.0f KB/min)".format(m4aFile.length() / seconds * 60 / 1024) },
                        { "FAILED: ${it.javaClass.simpleName}" }), ms)
            )
        }.start()
    }

    // ---- 4: transcribe the stored file again ----

    private fun retranscribe() {
        if (!pcmFile.exists()) { say("No saved audio yet."); return }
        if (recording) { say("Stop recording first."); return }
        if (mode == 5) {
            // A real file descriptor instead of a pipe.
            val fd = ParcelFileDescriptor.open(pcmFile, ParcelFileDescriptor.MODE_READ_ONLY)
            sessionStartedAt = SystemClock.elapsedRealtime()
            startRecognizer(fd, "file descriptor (from file)")
            fd.close()
            say("Gave the recogniser a file descriptor.")
            return
        }
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        sessionStartedAt = SystemClock.elapsedRealtime()
        startRecognizer(readSide, "pipe (from file)")
        readSide.close()
        Thread {
            var sent = 0L
            var closedEarly = false
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { out ->
                    FileInputStream(pcmFile).use { input ->
                        val buf = ByteArray(3200)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            sent += n
                            // Pace at roughly real time: the recogniser may expect a live stream.
                            Thread.sleep(n * 1000L / (RATE * 2) / 2)
                        }
                    }
                }
            } catch (e: java.io.IOException) {
                closedEarly = true
            }
            say("Feeder: sent $sent of ${pcmFile.length()} bytes" + if (closedEarly) " (recogniser closed the pipe early)." else ".")
        }.start()
        say("Feeding the saved file to the recogniser...")
    }

    // ---- 5: the app's normal route, for comparison ----

    private fun normalDictation() {
        if (recording) { say("Stop recording first."); return }
        sessionStartedAt = SystemClock.elapsedRealtime()
        startRecognizer(null, "normal (microphone owned by the recogniser)")
        say("Listening the normal way. Speak the same sentence.")
    }

    private fun startRecognizer(audio: ParcelFileDescriptor?, label: String) {
        recognizer?.destroy()
        // Mode: 0 on-device + all extras, 1 default service + all extras,
        // 2 on-device + audio source only, 3 default service + audio source only.
        val onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(this) && (mode >= 4 || mode % 2 == 0)
        val r = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        else SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        val engine = if (onDevice) "on-device" else "default"
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                val ms = SystemClock.elapsedRealtime() - sessionStartedAt
                say("[$label, $engine, ${ms} ms from start] text: \"$t\"")
            }
            override fun onError(error: Int) {
                val ms = SystemClock.elapsedRealtime() - sessionStartedAt
                say("[$label, $engine, ${ms} ms] ERROR code $error")
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            if (audio != null) {
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audio)
                if (mode < 2) {
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)
                }
            }
        }
        r.startListening(intent)
    }

    // ---- 3: playback ----

    private fun play() {
        if (!m4aFile.exists()) { say("No saved audio yet."); return }
        player?.release()
        player = MediaPlayer().apply {
            setDataSource(m4aFile.absolutePath)
            setOnCompletionListener { say("Playback finished.") }
            prepare(); start()
        }
        say("Playing the AAC copy (${m4aFile.length()} bytes).")
    }

    // ---- AAC encoder: raw PCM file to an M4A file ----

    private fun encodeAac(pcm: File, out: File) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        out.delete()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxerStarted = false
        var inputDone = false
        var ptsUs = 0L
        val info = MediaCodec.BufferInfo()
        try {
            FileInputStream(pcm).use { input ->
                while (true) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val chunk = ByteArray(minOf(buf.capacity(), 8192))
                            val n = input.read(chunk)
                            if (n <= 0) {
                                codec.queueInputBuffer(i, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                buf.clear(); buf.put(chunk, 0, n)
                                codec.queueInputBuffer(i, 0, n, ptsUs, 0)
                                ptsUs += n / 2L * 1_000_000L / RATE
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true
                        }
                        o >= 0 -> {
                            val data = codec.getOutputBuffer(o)!!
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0 && muxerStarted) muxer.writeSampleData(track, data, info)
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }
            }
        } finally {
            codec.stop(); codec.release()
            if (muxerStarted) muxer.stop()
            muxer.release()
        }
    }

    override fun onDestroy() {
        recording = false
        recognizer?.destroy()
        player?.release()
        audioRecord?.release()
        super.onDestroy()
    }
}
