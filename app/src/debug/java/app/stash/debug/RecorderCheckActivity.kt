package app.stash.debug

import android.Manifest
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.stash.StashApp
import app.stash.audio.MicRecorder
import app.stash.audio.RecordingResult
import app.stash.audio.RecordingSession
import app.stash.domain.AudioFormatConstants
import app.stash.domain.EndEvent
import app.stash.domain.EndPause
import app.stash.domain.MicHealth
import app.stash.domain.Pcm
import java.io.File

/**
 * Card 26a (DEBUG BUILDS ONLY). The only way to exercise the recorder until card 26g: records into memory, shows the
 * level, the level rule's events and the end reason, reports the loudest sample, plays the note back once from
 * memory, and writes the numbers (never any sound) to files/spike26/check.txt. Saves no sound anywhere.
 *
 * Start with: adb shell am start -n app.stash/app.stash.debug.RecorderCheckActivity
 * Add `--ez autostart true` to start recording the moment the screen is up (for the locked-phone check C2).
 */
class RecorderCheckActivity : ComponentActivity() {
    private lateinit var log: TextView
    private lateinit var meter: ProgressBar
    private lateinit var pauseButton: Button
    private lateinit var recordButton: Button
    private lateinit var stopButton: Button

    private val settings get() = (application as StashApp).container.settings
    private var session: RecordingSession? = null
    private var result: RecordingResult? = null
    private var track: AudioTrack? = null
    private var startedAt = 0L
    private var lockedAtStart = false
    private var pause = EndPause.SHORT
    private val firedEvents = mutableListOf<String>()
    @Volatile private var health = MicHealth.Status.UNKNOWN
    @Volatile private var endedBy: EndEvent? = null
    @Volatile private var failureText: String? = null

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
        pause = settings.endPause
        pauseButton = button("") { cyclePause() }
        recordButton = button("Record") { startRecording() }
        stopButton = button("Stop") { stopRecording() }
        button("Play it back once") { playBack() }
        meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        col.addView(meter, ViewGroup.LayoutParams.MATCH_PARENT, 60)
        log = TextView(this).apply { textSize = 15f; setTextIsSelectable(true) }
        col.addView(ScrollView(this).apply { addView(log) })
        setContentView(col)
        showPause()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
        } else if (intent.getBooleanExtra("autostart", false)) {
            startRecording()
        }
    }

    private fun showPause() { pauseButton.text = "End the note after a pause of: ${pause.label} (tap to change)" }

    private fun cyclePause() {
        if (session != null) return
        pause = EndPause.entries[(pause.ordinal + 1) % EndPause.entries.size]
        settings.endPause = pause
        showPause()
    }

    private fun say(line: String) = runOnUiThread { log.append(line + "\n\n") }

    private fun startRecording() {
        if (session != null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askMic.launch(Manifest.permission.RECORD_AUDIO); return
        }
        result = null; firedEvents.clear(); health = MicHealth.Status.UNKNOWN; endedBy = null; failureText = null
        lockedAtStart = (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        val s = RecordingSession(
            recorder = MicRecorder(applicationContext),
            pause = pause,
            clock = SystemClock::elapsedRealtime,
            onLevel = { lvl -> runOnUiThread { meter.progress = (lvl * 100).toInt() } },
            onEnd = { e ->
                // Runs on the recorder thread: only note it and ask the main thread to finish.
                firedEvents += "$e at ${SystemClock.elapsedRealtime() - startedAt} ms"
                if (e == EndEvent.WARN_LIMIT) say("WARN_LIMIT: 15 seconds left.")
                else { endedBy = e; runOnUiThread { stopRecording() } }
            },
            onHealth = { h ->
                health = h
                say("MicHealth: $h")
            },
            onFailure = { f -> failureText = f.toString(); say("Recorder failed after start: $f"); runOnUiThread { stopRecording() } },
        )
        startedAt = SystemClock.elapsedRealtime()
        val failure = s.start()
        if (failure != null) {
            failureText = failure.toString()
            say("Could not record: $failure")
            writeCheck(null)
            return
        }
        session = s
        say("Recording (keyguard locked at start: $lockedAtStart). Speak, then pause or tap Stop.")
    }

    private fun stopRecording() {
        val s = session ?: return
        session = null
        val r = s.finish()
        result = r
        meter.progress = 0
        val reason = endedBy?.name ?: "STOP_TAPPED"
        say(
            "Done. %.2f s. Loudest sample %d of 32767. MicHealth %s. Heard speech: %s. Ended by: %s. Verdict: %s. Hit the 3 minute ceiling: %s."
                .format(r.pcm.durationMs / 1000.0, r.loudest, r.health, r.heardSpeech, reason, r.verdict, r.hitCeiling)
        )
        writeCheck(r)
    }

    private fun writeCheck(r: RecordingResult?) {
        val reason = endedBy?.name ?: if (r != null) "STOP_TAPPED" else "NONE"
        val lines = buildString {
            appendLine("keyguardLockedAtStart=$lockedAtStart")
            appendLine("pauseSetting=${pause.name}")
            appendLine("durationMs=${r?.pcm?.durationMs ?: 0}")
            appendLine("loudestSample=${r?.loudest ?: 0}")
            appendLine("micHealth=${r?.health ?: health}")
            appendLine("heardSpeech=${r?.heardSpeech ?: false}")
            appendLine("endReason=$reason")
            appendLine("verdict=${r?.verdict ?: "NONE"}")
            appendLine("recorderFailure=${failureText ?: "none"}")
            appendLine("events=${firedEvents.joinToString("; ")}")
        }
        try {
            File(filesDir, "spike26").apply { mkdirs() }.let { File(it, "check.txt").writeText(lines) }
        } catch (_: Exception) {
            say("Could not write check.txt.")
        }
    }

    /** Plays the held note once, from memory. The sound is never written to disk. */
    private fun playBack() {
        val pcm: Pcm = result?.pcm ?: run { say("Nothing recorded yet."); return }
        if (session != null) { say("Stop recording first."); return }
        if (pcm.samples.isEmpty()) { say("The recording is empty."); return }
        track?.release()
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AudioFormatConstants.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setBufferSizeInBytes(pcm.samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(pcm.samples, 0, pcm.samples.size)
        track = t
        t.play()
        say("Playing %.1f s from memory.".format(pcm.durationMs / 1000.0))
    }

    override fun onDestroy() {
        session?.cancel(); session = null
        track?.release(); track = null
        result = null
        super.onDestroy()
    }
}
