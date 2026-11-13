package app.stash.capture

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.stash.ui.MainActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.stash.StashApp
import app.stash.audio.MicRecorder
import app.stash.audio.PlatformEncoders
import app.stash.audio.StreamingEncoder
import app.stash.data.CaptureSource
import app.stash.domain.Capability
import app.stash.domain.CameraRules
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.PictureOffer
import app.stash.domain.SensitiveReason
import app.stash.pipeline.TranscribeNotice
import app.stash.speech.AndroidSpeechEngine
import app.stash.speech.Availability
import app.stash.speech.SpeechEngine
import app.stash.ui.PrivateLabel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * The capture screen. Shows over the lock screen, beeps, listens, saves, confirms, closes itself.
 * It never shows stored notes: only the words being captured right now.
 *
 * Card 26g: a note goes one of two ways, chosen by [VoiceRoute] each time one starts.
 *  - Recorded: our own recorder and [RecordingSession]. The recording is saved first (the save tone means it is on
 *    disk), and its words arrive a moment later. No words are shown while he speaks.
 *  - Quick: the phone's own recogniser, exactly as before card 26 ([SpeechEngine], [save]). Nothing is recorded.
 */
class CaptureActivity : ComponentActivity() {
    private companion object {
        /** How long to keep quietly listening for someone who hasn't started speaking. */
        const val SILENCE_LIMIT_MS = 25_000L
        /** The most a single note can take. */
        const val HARD_LIMIT_MS = 60_000L
        /** The drifting "Saved" message fades out over this long. */
        const val FADE_MS = 1_800
        /** While ready, a recorded note's capture is looked at this often for its words (card 26g item 11). */
        const val WORDS_POLL_MS = 500L
        /**
         * Leaving the screen saves a recording. The save waits this long first and is called off if the screen is back:
         * a screen opened over the lock screen may be stopped and started again as the display wakes, and that must
         * not end the note. (Not yet seen on the phone; card 26g's Home and screen-off checks will show it.)
         */
        const val LEFT_SCREEN_GRACE_MS = 800L
    }

    private enum class Phase { NEED_MIC, STARTING, ASK_UNLOCK, LISTENING, RECORDING, SAVING, READY, FAILED, SAVE_FAILED, LEAVING_EMPTY }

    private var phase by mutableStateOf(Phase.LISTENING)
    private var heard by mutableStateOf("")
    private var message by mutableStateOf("")
    private var locked by mutableStateOf(false)
    private var waitingHint by mutableStateOf(false)

    /** Private was pressed (while listening, or on the saved screen), or the saved note came out Private. Its words are never drawn. */
    private var markedPrivate by mutableStateOf(false)

    /** The previous note, while the screen is ready for the next one. Null once its Undo/Private window is ended. */
    private var previous by mutableStateOf<SavedNote?>(null)
    private var undoOpen by mutableStateOf(false)

    /** Card 16g: "Add a picture" is drawn beside Undo. */
    private var offerVisible by mutableStateOf(false)
    private var readyJob: Job? = null

    /** The system unlock prompt is up: the idle close must not fire and kill the activity under it. */
    private var unlockPromptOpen = false

    /** Bumped on every new note so a stale delayed retry from an earlier one does nothing. */
    private var session = 0

    /** Open Stash was pressed while listening with words heard: save them, then open the app. */
    private var openAfterSave = false

    /** READY with no saved note (Open Stash with nothing heard, unlock prompt cancelled). Has its own idle deadline. */
    private var bareReady = false
    private var bareIdleAt = 0L

    private fun now() = android.os.SystemClock.elapsedRealtime()

    // --- card 26g: the recorded route ---

    /** The recording being made or saved right now; null on the quick route and once a note is settled. */
    private var rec: RecordingSession? = null
    private var recLevel by mutableStateOf(0f)
    private var recElapsedMs by mutableStateOf(0L)
    private var recHeard by mutableStateOf(false)
    private var recNearLimit by mutableStateOf(false)
    private var recTicker: Job? = null
    /** The delayed save after the screen was left ([LEFT_SCREEN_GRACE_MS]). */
    private var leftJob: Job? = null
    @Volatile private var visible = false

    /** Why this note is a quick note, when it is one: its line is said on screen. */
    private var quickReason by mutableStateOf<QuickReason?>(null)
    /** Steady small lines on the ready screen: "Stopped at 3:00. Saved.", the one-time notice, "couldn't write this one out". */
    private var readyLines by mutableStateOf<List<String>>(emptyList())
    /** "Close" was pressed after a failed save: asking "Throw this recording away?". */
    private var confirmDiscard by mutableStateOf(false)

    private val container get() = (application as StashApp).container

    private lateinit var engine: SpeechEngine
    private lateinit var cues: Cues
    private lateinit var starter: VoiceStarter
    private var savedId: String? = null
    private var timers: Job? = null
    private var started = false

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) begin() else {
            phase = Phase.NEED_MIC
            message = "Stash needs the microphone. Open Stash once and allow it."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Show over the lock screen without unlocking, and wake the screen.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        engine = AndroidSpeechEngine(this)
        cues = Cues(this)
        starter = VoiceStarter(engine, startRecording = { startRecording() })

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { CaptureScreen(); app.stash.ui.ScreenName("Add a note: voice") }
            }
        }
        begin()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Tapped again while open: only restart if we are not in the middle of something.
        if (phase == Phase.FAILED || phase == Phase.NEED_MIC) begin() else if (phase == Phase.READY) nextNote()
    }

    override fun onStart() {
        super.onStart()
        visible = true
        leftJob?.cancel()
    }

    /**
     * Card 26g item 8: Home, the back gesture, the screen turning off or a call while recording saves the note up to
     * that point. Only Cancel discards a recording. A failed save is tried once more rather than left behind.
     */
    override fun onStop() {
        super.onStop()
        visible = false
        val s = rec ?: return
        when (phase) {
            Phase.RECORDING, Phase.STARTING -> if (isFinishing) finishRecording(RecordingSession.EndReason.LEFT_SCREEN) else saveIfStillAway(s)
            Phase.SAVE_FAILED -> container.captureScope.launch { s.saveAgain() }
            else -> {}
        }
    }

    private fun saveIfStillAway(s: RecordingSession) {
        leftJob?.cancel()
        leftJob = container.captureScope.launch {
            delay(LEFT_SCREEN_GRACE_MS)
            if (!visible) s.finish(RecordingSession.EndReason.LEFT_SCREEN)
        }
    }

    /** "Add a note": the previous note's Undo/Private window ends and a fresh listening cycle starts. */
    private fun nextNote() {
        readyJob?.cancel()
        previous = null
        undoOpen = false
        offerVisible = false
        bareReady = false
        started = false
        readyLines = emptyList()
        begin()
    }

    /** Ticks while ready: hides Undo when its window ends, and closes when nobody has touched anything. */
    private fun startReadyTicker() {
        readyJob?.cancel()
        readyJob = lifecycleScope.launch {
            var lastPoll = Long.MIN_VALUE
            var lastWordsPoll = Long.MIN_VALUE
            while (phase == Phase.READY) {
                val p = previous
                val t = now()
                if (p != null) undoOpen = p.undoOpen(t)
                // Card 26g item 11: a recorded note's words arrive after the save. Look, but never wait for them.
                if (p != null && p.watching && t - lastWordsPoll >= WORDS_POLL_MS) {
                    lastWordsPoll = t
                    checkWords(p)
                }
                // Card 16g: while this note's Undo window is open, look at its picture hint twice a second.
                if (p != null && undoOpen && t - lastPoll >= PictureOffer.POLL_MS) {
                    lastPoll = t
                    checkOffer(p)
                }
                if (p == null) offerVisible = false
                if (p == null && !bareReady) { closeSelf(); return@launch }
                val expired = if (p == null) t >= bareIdleAt else p.idleExpired(t)
                if (expired && !unlockPromptOpen) { closeSelf(); return@launch }
                delay(100)
            }
        }
    }

    /**
     * Card 16g item 4. Reads the saved note's hint (one row by id). The button is drawn only when the camera is
     * offered (the picker cannot open from this screen), the note is not Private and the rules say show. The first time
     * it appears the note's Undo and idle windows are extended. A hint that goes away (Private pressed) hides it.
     */
    private suspend fun checkOffer(p: SavedNote) {
        val container = (application as StashApp).container
        val shown = !p.isPrivate && cameraOffered() &&
            withContext(Dispatchers.IO) { runCatching { container.repo.captures.pictureOfferShown(p.id) }.getOrDefault(false) }
        val cur = previous
        if (cur == null || cur.id != p.id) return
        offerVisible = shown
        if (shown && !cur.offered) {
            previous = cur.offerAppeared(now())
            Dbg.event(DbgComponent.OFFER, DbgCode.OFFERED, n1 = 1, refId = p.id)
        }
    }

    /**
     * Card 26g item 11. Reads the saved capture (one row by id) and lets [WordsLater] decide. Words are shown once in
     * the drifting line and only for a note that is not Private; a note that could not be written out says so.
     */
    private suspend fun checkWords(p: SavedNote) {
        val c = withContext(Dispatchers.IO) { runCatching { container.repo.captures.get(p.id) }.getOrNull() }
        val cur = previous
        if (cur == null || cur.id != p.id || !cur.watching) return
        val update = WordsLater.of(
            found = c != null && c.deletedAt == null, sensitive = c?.sensitive ?: false,
            state = c?.transcribeState, text = c?.text.orEmpty(),
        )
        if (update == WordsLater.Update.Waiting) return
        previous = cur.arrived(update)
        if (update == WordsLater.Update.Private) offerVisible = false
        if (update == WordsLater.Update.GaveUp) readyLines = readyLines + WordsLater.GAVE_UP_LINE
    }

    /** "Add a picture" beside Undo: the camera for the note just spoken. The words stay the note's words. */
    private fun acceptOffer() {
        val p = previous ?: return
        Dbg.event(DbgComponent.OFFER, DbgCode.ACCEPTED, n1 = 1, refId = p.id)
        takePicture(p.id)
    }

    private fun touched() {
        if (phase != Phase.READY) return
        val p = previous
        if (p == null) { if (bareReady) bareIdleAt = now() + SavedNote.IDLE_MS } else previous = p.touched(now())
    }

    override fun onResume() {
        super.onResume()
        // The lock state can change while this screen is open.
        locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
    }

    /**
     * "Open Stash". The lock state is read now, not at create. Unlocked: open the app and leave.
     * Locked: ask for unlock first; the app is not started until the keyguard is dismissed, so no notes show while locked.
     */
    private fun openStash() {
        val km = getSystemService(KeyguardManager::class.java)
        locked = km.isKeyguardLocked
        if (!km.isKeyguardLocked) { launchMainAndLeave(); return }
        unlockPromptOpen = true
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                unlockPromptOpen = false
                locked = false
                launchMainAndLeave()
            }

            override fun onDismissCancelled() = promptEnded()
            override fun onDismissError() = promptEnded()
        })
    }

    /**
     * "Take a picture" (docs/cards/16e item 8): the camera for a new note. While locked the label is "Unlock to take a
     * picture" and the system is asked to unlock first when [CameraRules.UNLOCK_FIRST_WHEN_LOCKED] is on (the documented
     * fallback; the real lock-screen behaviour is a phone check). The camera runs in its own task, so this screen can close.
     */
    private fun takePicture(forCapture: String? = null) {
        val km = getSystemService(KeyguardManager::class.java)
        locked = km.isKeyguardLocked
        if (!CameraRules.needsUnlockFirst(km.isKeyguardLocked)) { launchCameraAndLeave(forCapture); return }
        unlockPromptOpen = true
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                unlockPromptOpen = false
                locked = false
                launchCameraAndLeave(forCapture)
            }

            override fun onDismissCancelled() = promptEnded()
            override fun onDismissError() = promptEnded()
        })
    }

    private fun launchCameraAndLeave(captureId: String? = null) {
        startActivity(CameraActivity.intent(this, captureId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        closeSelf()
    }

    /** The camera button is drawn only when the camera and pictures are switched on and the phone has a camera. */
    private fun cameraOffered(): Boolean {
        val caps = (application as StashApp).container.capabilities
        return CameraRules.buttonVisible(
            caps.enabled(Capability.CAMERA), caps.isOn(Capability.PICTURES),
            packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
        )
    }

    private fun promptEnded() {
        unlockPromptOpen = false
        touched() // the idle count starts again from now
    }

    /**
     * "Open Stash" while listening. Words heard: stop the engine; the final text goes down the same path as Done
     * (so Private is honoured) and the app opens once it is saved. Nothing heard: cancel and open now; if the
     * unlock prompt is cancelled the screen is left in READY.
     */
    private fun openStashWhileListening() {
        if (phase != Phase.LISTENING || openAfterSave) return
        when (OpenStashChoice.whileListening(heard)) {
            OpenStashChoice.WhileListening.SAVE_THEN_OPEN -> { openAfterSave = true; engine.stop() }
            OpenStashChoice.WhileListening.CANCEL_THEN_OPEN -> {
                timers?.cancel()
                engine.cancel()
                session++ // a pending delayed restart from this listening cycle does nothing
                started = false
                waitingHint = false
                bareReady = true
                bareIdleAt = now() + SavedNote.IDLE_MS
                phase = Phase.READY
                startReadyTicker()
                openStash()
            }
        }
    }

    /** Done with nothing heard: "I am done". Show a floating "No note made", then close. */
    private fun leaveEmpty() {
        timers?.cancel()
        engine.cancel()
        floatEmptyAndClose()
    }

    private fun floatEmptyAndClose() {
        session++
        started = false
        waitingHint = false
        phase = Phase.LEAVING_EMPTY
        lifecycleScope.launch {
            delay(DoneChoice.EMPTY_FLOAT_MS.toLong())
            closeSelf()
        }
    }

    private fun launchMainAndLeave() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        closeSelf()
    }

    @androidx.compose.runtime.Composable
    private fun OpenStashButton() {
        OutlinedButton(onClick = {
            when (phase) {
                Phase.LISTENING -> openStashWhileListening()
                Phase.RECORDING -> openStashWhileRecording()
                else -> openStash()
            }
        }, modifier = Modifier.heightIn(min = 56.dp)) {
            Text(OpenStashChoice.label(locked))
        }
    }

    private fun begin(force: Route? = null) {
        if (!(application as StashApp).container.capabilities.enabled(Capability.VOICE_CAPTURE)) {
            phase = Phase.FAILED
            message = "Voice notes are switched off. Open Stash to type a note."
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            phase = Phase.NEED_MIC
            message = "Allow the microphone to add notes."
            askMic.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (started) return
        started = true
        openAfterSave = false
        bareReady = false
        val mySession = ++session
        heard = ""
        message = ""
        markedPrivate = false
        waitingHint = false
        quickReason = null
        readyLines = emptyList()
        confirmDiscard = false
        phase = Phase.STARTING
        lifecycleScope.launch {
            // Card 26g: recorded, or the quick note as before. Decided afresh for every note.
            val route = force ?: chooseRoute()
            if (mySession != session || phase != Phase.STARTING) return@launch
            Dbg.event(DbgComponent.CAPTURE, DbgCode.STARTED, n1 = VoiceRoute.code(route), n2 = if (locked) 1 else 0)
            if (route == Route.AskUnlock) { phase = Phase.ASK_UNLOCK; return@launch }
            startNote(route, mySession)
        }
    }

    /** Reads the switch, the speech model, the lock and what is known about the locked microphone ([VoiceRoute.choose]). */
    private suspend fun chooseRoute(): Route {
        val c = container
        locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val on = c.capabilities.isOn(Capability.VOICE_RECORDING)
        val engineState = if (!on) Availability.Off else withContext(Dispatchers.IO) {
            runCatching { c.transcriber.availability() }.getOrDefault(Availability.NoModel)
        }
        return VoiceRoute.choose(on, engineState, locked, VoiceRoute.lockedMic(c.settings.lockedMicBlocked), c.settings.lockedChoice)
    }

    /** The start cue, the 300 ms wait so the beep is not recorded, then the recorder or the recogniser. */
    private suspend fun startNote(route: Route, mySession: Int) {
        // The codec is started while the cue plays, so the recorder opens the moment the wait is over.
        val s = if (route == Route.Record) newRecording(mySession) else null
        rec = s
        if (s != null) container.captureScope.launch { s.prepare() }
        cues.start()
        delay(300) // let the beep finish so it isn't recorded
        if (mySession != session || phase != Phase.STARTING) {
            if (s != null) { if (rec === s) rec = null; container.captureScope.launch { s.cancel() } }
            return
        }
        val words = if (route is Route.Quick) withContext(Dispatchers.IO) { container.repo.vocabWords() } else emptyList()
        if (mySession != session || phase != Phase.STARTING) return
        if (route is Route.Quick) {
            vocabulary = words
            quickReason = route.reason
            phase = Phase.LISTENING
        }
        starter.start(route, listener, words)
        if (route is Route.Quick) armTimers()
    }

    // ------------------------------------------------------------------ card 26g: the recorded route

    private fun newRecording(mySession: Int): RecordingSession {
        val c = container
        fun onUi(block: () -> Unit) = runOnUiThread { if (!isDestroyed && mySession == session) block() }
        var made: RecordingSession? = null
        val s = RecordingSession(
            recorder = MicRecorder(applicationContext),
            encoder = StreamingEncoder(PlatformEncoders()),
            save = { encoded, wasLocked, asPrivate -> withContext(Dispatchers.IO) { c.recordingSaver.save(encoded, wasLocked, asPrivate) } },
            format = c.settings.recordingQuality.format,
            pause = c.settings.endPause,
            locked = locked,
            // The application's scope: stopping and saving must outlive this screen.
            launch = { block -> c.captureScope.launch { block() } },
            hooks = object : RecordingSession.Hooks {
                override fun onState(state: RecordingSession.State) = onUi { made?.let { onRecordingState(it, state) } }
                // Sound only from the visible screen; the note is saved whether or not the tone is heard.
                override fun cueSaved() = onUi { cues.saved() }
                override fun cueFailed() = onUi { cues.failed() }
                override fun lockedMicBlocked() { c.settings.lockedMicBlocked = true }
                // Already asked for by the sink; asking again is harmless. Nothing waits for it.
                override fun afterSaved() { c.transcriptionScheduler.requestNow() }
            },
        )
        made = s
        return s
    }

    /** [VoiceStarter] calls this for the recorded route, after the cue. The session was made in [startNote]. */
    private fun startRecording() {
        val s = rec ?: return
        if (markedPrivate) s.markPrivate()
        recLevel = 0f
        recElapsedMs = 0
        recHeard = false
        recNearLimit = false
        phase = Phase.RECORDING
        recTicker?.cancel()
        recTicker = lifecycleScope.launch {
            while (phase == Phase.RECORDING && rec === s) {
                recLevel = s.level.toFloat()
                recElapsedMs = s.elapsedMs
                recHeard = s.heardSpeech
                recNearLimit = s.nearLimit
                delay(100)
            }
        }
        container.captureScope.launch { s.start() }
        if (!visible) saveIfStillAway(s)
    }

    private fun finishRecording(reason: RecordingSession.EndReason) {
        val s = rec ?: return
        leftJob?.cancel()
        container.captureScope.launch { s.finish(reason) }
    }

    /** Cancel while recording: the only thing that discards a recording. Nothing was on disk. */
    private fun cancelRecording() {
        val s = rec
        rec = null
        session++
        leftJob?.cancel()
        if (s != null) container.captureScope.launch { s.cancel() }
        closeSelf()
    }

    /** "Open Stash" while recording: the note is saved first (an empty recording is not), then the app opens. */
    private fun openStashWhileRecording() {
        if (phase != Phase.RECORDING || openAfterSave) return
        openAfterSave = true
        finishRecording(RecordingSession.EndReason.OPEN_STASH)
    }

    /** On the main thread, for the session this screen is showing. */
    private fun onRecordingState(s: RecordingSession, state: RecordingSession.State) {
        if (rec !== s) return
        when (state) {
            RecordingSession.State.FINISHING -> { recTicker?.cancel(); phase = Phase.SAVING }
            RecordingSession.State.SAVED -> recordingSaved(s)
            RecordingSession.State.EMPTY -> recordingEmpty(s)
            RecordingSession.State.SAVE_FAILED -> {
                confirmDiscard = false
                message = RecordingSession.SAVE_FAILED_LINE
                phase = Phase.SAVE_FAILED
            }
            RecordingSession.State.SWITCHED_TO_QUICK -> switchToQuick()
            RecordingSession.State.IDLE, RecordingSession.State.RECORDING, RecordingSession.State.CANCELLED -> {}
        }
    }

    private fun recordingSaved(s: RecordingSession) {
        val c = s.saved ?: return
        rec = null
        savedId = c.id
        heard = ""
        markedPrivate = false
        // The words are not known yet. A Private note's words are never drawn, now or when they arrive.
        previous = SavedNote.recorded(c.id, c.sensitive, now())
        undoOpen = true
        offerVisible = false
        started = false
        val lines = mutableListOf<String>()
        if (s.endReason == RecordingSession.EndReason.LIMIT) lines += RecordingSession.LIMIT_SAVED_LINE
        val settings = container.settings
        if (visible && !settings.recordingNoticeShown) {
            settings.recordingNoticeShown = true
            lines += WordsLater.FIRST_TIME_LINE
        }
        readyLines = lines
        phase = Phase.READY
        startReadyTicker()
        if (openAfterSave) { openAfterSave = false; openStash() }
    }

    /** Nothing was recorded (26a's EMPTY). Done: "No note made". Open Stash: just open. Otherwise as today's silence. */
    private fun recordingEmpty(s: RecordingSession) {
        rec = null
        when (s.endReason) {
            RecordingSession.EndReason.DONE -> floatEmptyAndClose()
            RecordingSession.EndReason.OPEN_STASH -> {
                openAfterSave = false
                session++
                started = false
                bareReady = true
                bareIdleAt = now() + SavedNote.IDLE_MS
                phase = Phase.READY
                startReadyTicker()
                openStash()
            }
            RecordingSession.EndReason.LEFT_SCREEN -> closeSelf()
            else -> fail("I didn't catch that.", beep = false)
        }
    }

    /**
     * Item 4, "at run time": the recorder gave only silence or would not start. The session has already stopped it
     * (and remembered the fact if the phone was locked). The same note carries on through the phone's recogniser.
     */
    private fun switchToQuick() {
        rec = null
        recTicker?.cancel()
        leftJob?.cancel()
        val mySession = session
        quickReason = QuickReason.MIC_FAILED
        phase = Phase.STARTING
        lifecycleScope.launch { startNote(Route.Quick(QuickReason.MIC_FAILED), mySession) }
    }

    private fun saveAgain() {
        val s = rec ?: return
        confirmDiscard = false
        container.captureScope.launch { s.saveAgain() }
    }

    private fun discardRecording() {
        rec?.discard()
        rec = null
        closeSelf()
    }

    /**
     * "Unlock to keep a recording" (on a locked quick note) and "Unlock" (when he chose to be asked first): the
     * keyguard dismissal [openStash] uses, then a fresh note, which is recorded because the phone is no longer locked.
     */
    private fun unlockToRecord() {
        timers?.cancel()
        engine.cancel()
        session++ // anything still pending from the quick note does nothing
        waitingHint = false
        heard = ""
        phase = Phase.ASK_UNLOCK
        val wasPrivate = markedPrivate // Private pressed on the quick note still holds for the recorded one
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) { started = false; begin(); markedPrivate = wasPrivate; return }
        unlockPromptOpen = true
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                unlockPromptOpen = false
                locked = false
                started = false
                begin()
                markedPrivate = wasPrivate
            }

            override fun onDismissCancelled() { unlockPromptOpen = false }
            override fun onDismissError() { unlockPromptOpen = false }
        })
    }

    /** "Quick note instead": a locked note through the phone's recogniser, nothing recorded. */
    private fun quickNoteInstead() {
        started = false
        begin(force = Route.Quick(QuickReason.LOCKED))
    }

    // ------------------------------------------------------------------ the quick route (unchanged from before card 26g)

    /**
     * Someone thinking before they speak is not a failure (2026-11-11: a pause ended the note and
     * the retries then looped). While nothing has been heard, listening quietly starts again for up
     * to [SILENCE_LIMIT_MS]; only after that does it give up. [HARD_LIMIT_MS] from the start is the
     * most a note can take.
     */
    private fun armTimers() {
        timers?.cancel()
        timers = lifecycleScope.launch {
            delay(SILENCE_LIMIT_MS)
            if (phase == Phase.LISTENING && heard.isBlank()) {
                engine.cancel()
                fail("I didn't catch that.", beep = false)
                return@launch
            }
            delay(HARD_LIMIT_MS - SILENCE_LIMIT_MS)
            if (phase == Phase.LISTENING) engine.stop()
        }
    }

    private var vocabulary: List<String> = emptyList()

    private val listener: SpeechEngine.Listener = object : SpeechEngine.Listener {
        override fun onPartial(text: String) {
            if (phase != Phase.LISTENING) return
            heard = text
            waitingHint = false
        }

        override fun onFinal(text: String) {
            if (phase != Phase.LISTENING) return // stale: cancelled, already saving, or closing
            timers?.cancel()
            if (text.isBlank()) fail("I didn't catch that.", beep = false) else save(text)
        }

        override fun onFailure(message: String, noSpeech: Boolean) {
            if (phase != Phase.LISTENING) return
            if (noSpeech && phase == Phase.LISTENING && heard.isBlank()) {
                // Nobody spoke yet: keep the screen as it is and listen again; the silence timer decides when to stop.
                waitingHint = true
                val mine = session
                lifecycleScope.launch {
                    delay(400)
                    if (mine == session && phase == Phase.LISTENING && heard.isBlank()) engine.start(this@CaptureActivity.listener, vocabulary)
                }
                return
            }
            timers?.cancel()
            fail(message, beep = !noSpeech)
        }
    }

    private fun save(raw: String) {
        phase = Phase.SAVING
        heard = raw
        lifecycleScope.launch {
            val container = (application as StashApp).container
            val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked
            // Save first: the tone and "Saved" come only after the words are in the database. Understanding is later.
            val result = try {
                withContext(Dispatchers.IO) { container.entry.save(raw, CaptureSource.VOICE, locked, markPrivate = markedPrivate) }
            } catch (e: Exception) {
                InboundSink.Result.Failed(e.javaClass.simpleName)
            }
            when (result) {
                is InboundSink.Result.Saved -> {
                    savedId = result.capture.id
                    // A Private note's words are not drawn on a screen that may be over the lock screen.
                    val priv = result.capture.sensitive
                    heard = ""
                    markedPrivate = false
                    cues.saved()
                    previous = SavedNote.of(result.capture.id, result.capture.text, priv, now())
                    undoOpen = true
                    offerVisible = false
                    started = false // the engine is idle; the next "Add a note" starts a fresh cycle
                    phase = Phase.READY
                    startReadyTicker()
                    if (openAfterSave) { openAfterSave = false; openStash() }
                }
                is InboundSink.Result.Failed -> fail("I couldn't save that note.")
            }
        }
    }

    /**
     * The Private button. While listening it only remembers the wish (the note is saved Private, and what is
     * on screen is hidden). On the saved screen it marks the just-saved capture Private. Nothing here sends anything:
     * it changes one flag, and the read-it-later pass already skips a Private capture.
     */
    private fun markPrivate() {
        if (phase == Phase.READY) {
            val p = previous ?: return
            previous = p.markedPrivate(now())
            offerVisible = false // Private notes are left out of suggestions (card 16g)
            val id = p.id
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    (application as StashApp).container.repo.captures.setSensitive(id, true, SensitiveReason.SAID.code)
                }
            }
            return
        }
        markedPrivate = true
        heard = ""
        rec?.markPrivate() // a recording in progress is saved Private from the start
    }

    /** Within five seconds of saving: take the note back out (it is soft-deleted, so it can still be restored). */
    private fun undo() {
        val p = previous ?: return
        if (!p.undoOpen(now())) return
        val id = p.id
        previous = null
        undoOpen = false
        offerVisible = false
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { (application as StashApp).container.repo.deleteCapture(id) }
            Dbg.event(DbgComponent.CAPTURE, DbgCode.UNDONE, refId = id)
            // Undo ends the visit, as before.
            if (phase == Phase.READY) closeSelf()
        }
    }

    /** [beep] is false for plain silence: nobody did anything wrong, so no error tone. */
    private fun fail(msg: String, beep: Boolean = true) {
        started = false
        openAfterSave = false
        waitingHint = false
        message = msg
        phase = Phase.FAILED
        if (beep) cues.failed()
    }

    private fun closeSelf() {
        readyJob?.cancel()
        timers?.cancel()
        recTicker?.cancel()
        engine.destroy()
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        // A recording still running is saved, never dropped; a failed save gets one more try (card 26g item 8).
        rec?.let { s ->
            leftJob?.cancel()
            container.captureScope.launch {
                when (s.finish(RecordingSession.EndReason.LEFT_SCREEN)) {
                    RecordingSession.State.SAVE_FAILED -> s.saveAgain()
                    RecordingSession.State.IDLE -> s.cancel() // never started: only the codec needs letting go
                    else -> {}
                }
            }
        }
        rec = null
        recTicker?.cancel()
        timers?.cancel()
        readyJob?.cancel()
        engine.destroy()
        cues.release()
        super.onDestroy()
    }

    @androidx.compose.runtime.Composable
    private fun CaptureScreen() {
        Column(
            Modifier.fillMaxSize().padding(24.dp).pointerInput(Unit) {
                // Any touch counts as "somebody is here": the idle close starts counting again.
                awaitPointerEventScope {
                    while (true) { awaitPointerEvent(PointerEventPass.Initial); touched() }
                }
            },
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val title = when (phase) {
                Phase.NEED_MIC -> "Microphone needed"
                Phase.STARTING -> ""
                Phase.ASK_UNLOCK -> VoiceRoute.ASK_UNLOCK_TITLE
                Phase.LISTENING -> "Listening..."
                Phase.RECORDING -> "Recording..."
                Phase.SAVING -> "Saving..."
                Phase.READY -> "Ready"
                Phase.FAILED, Phase.SAVE_FAILED -> message
                Phase.LEAVING_EMPTY -> ""
            }
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            if (phase == Phase.NEED_MIC) Text(message)
            // Card 26g: which quick note this is, said plainly ("No recording: ...", "Say that again, please.").
            val quickLine = quickReason?.line
            if (quickLine != null && (phase == Phase.LISTENING || phase == Phase.STARTING)) {
                Text(quickLine, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            }
            if (phase == Phase.RECORDING) RecordingStatus()
            if (phase == Phase.LISTENING && waitingHint && heard.isBlank()) {
                Text("Take your time. Still listening.", style = MaterialTheme.typography.titleMedium)
            }
            if (markedPrivate && (phase == Phase.LISTENING || phase == Phase.RECORDING)) {
                PrivateLabel(true, text = "Private. Its words are not shown.", base = MaterialTheme.typography.titleMedium)
            }
            if (heard.isNotBlank() && (phase == Phase.LISTENING || phase == Phase.SAVING) && !markedPrivate) {
                Text(heard, style = MaterialTheme.typography.titleLarge, modifier = Modifier.heightIn(min = 56.dp))
            }
            if (phase == Phase.READY) {
                SavedDrift()
                readyLines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
            }
            if (phase == Phase.LEAVING_EMPTY) Drift("empty", DoneChoice.EMPTY_MESSAGE, DoneChoice.EMPTY_FLOAT_MS)
            Spacer(Modifier.height(24.dp))
            when (phase) {
                Phase.LISTENING -> Column(horizontalAlignment = Alignment.CenterHorizontally) { Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = {
                        when (DoneChoice.of(heard)) {
                            DoneChoice.SAVE -> engine.stop()
                            DoneChoice.LEAVE_EMPTY -> leaveEmpty()
                        }
                    }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Done") }
                    OutlinedButton(onClick = { engine.cancel(); closeSelf() }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Cancel") }
                    if (!markedPrivate) {
                        OutlinedButton(onClick = { markPrivate() }, modifier = Modifier.heightIn(min = 56.dp)) { PrivateLabel(false) }
                    }
                    }
                    Spacer(Modifier.height(16.dp))
                    if (quickReason == QuickReason.LOCKED) {
                        OutlinedButton(onClick = { unlockToRecord() }, modifier = Modifier.heightIn(min = 56.dp)) { Text(VoiceRoute.UNLOCK_TO_RECORD) }
                        Spacer(Modifier.height(16.dp))
                    }
                    if (OpenStashChoice.available(listening = true, saving = false) && !openAfterSave) OpenStashButton()
                }
                Phase.RECORDING -> Column(horizontalAlignment = Alignment.CenterHorizontally) { Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = { finishRecording(RecordingSession.EndReason.DONE) }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Done") }
                    OutlinedButton(onClick = { cancelRecording() }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Cancel") }
                    if (!markedPrivate) {
                        OutlinedButton(onClick = { markPrivate() }, modifier = Modifier.heightIn(min = 56.dp)) { PrivateLabel(false) }
                    }
                    }
                    Spacer(Modifier.height(16.dp))
                    if (!openAfterSave) OpenStashButton()
                }
                Phase.STARTING -> OutlinedButton(onClick = { session++; closeSelf() }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Cancel") }
                Phase.ASK_UNLOCK -> Column(horizontalAlignment = Alignment.CenterHorizontally) { Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = { unlockToRecord() }, modifier = Modifier.heightIn(min = 56.dp)) { Text(VoiceRoute.ASK_UNLOCK_BUTTON) }
                    OutlinedButton(onClick = { quickNoteInstead() }, modifier = Modifier.heightIn(min = 56.dp)) { Text(VoiceRoute.ASK_UNLOCK_QUICK) }
                    }
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { closeSelf() }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Close") }
                }
                Phase.SAVE_FAILED -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!confirmDiscard) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Button(onClick = { saveAgain() }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Save again") }
                            OutlinedButton(onClick = { confirmDiscard = true }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Close") }
                        }
                    } else {
                        Text(RecordingSession.DISCARD_QUESTION, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Button(onClick = { confirmDiscard = false }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Keep it") }
                            OutlinedButton(onClick = { discardRecording() }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Throw it away") }
                        }
                    }
                }
                Phase.FAILED, Phase.NEED_MIC -> Column(horizontalAlignment = Alignment.CenterHorizontally) { Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = { begin() }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Try again") }
                    OutlinedButton(onClick = { closeSelf() }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Close") }
                    }
                    Spacer(Modifier.height(16.dp))
                    OpenStashButton()
                }
                Phase.READY -> {
                    Button(
                        onClick = { nextNote() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                    ) { Text("Add a note", style = MaterialTheme.typography.headlineSmall) }
                    Spacer(Modifier.height(16.dp))
                    if (cameraOffered()) {
                        OutlinedButton(onClick = { takePicture() }, modifier = Modifier.heightIn(min = 56.dp)) { Text(CameraRules.takeLabel(locked)) }
                        Spacer(Modifier.height(16.dp))
                    }
                    OutlinedButton(onClick = { closeSelf() }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Close") }
                    Spacer(Modifier.height(16.dp))
                    OpenStashButton()
                    val p = previous
                    if (p != null && undoOpen) {
                        Spacer(Modifier.height(24.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Previous note:", style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { undo() }, modifier = Modifier.heightIn(min = 40.dp)) { Text("Undo") }
                            if (offerVisible && !p.isPrivate) {
                                OutlinedButton(onClick = { acceptOffer() }, modifier = Modifier.heightIn(min = 40.dp)) { Text(PictureOffer.ADD_LABEL) }
                            }
                            if (!p.isPrivate) {
                                OutlinedButton(onClick = { markPrivate() }, modifier = Modifier.heightIn(min = 40.dp)) { PrivateLabel(false) }
                            } else {
                                PrivateLabel(true, text = "Private", base = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                else -> {}
            }
            if (locked) {
                Spacer(Modifier.height(24.dp))
                Text("Phone is locked. Nothing else is shown here.", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth())
            }
        }
    }

    /** Grey "Saved" that drifts up and fades over [FADE_MS]. Fixed height, so it never moves the buttons. */
    @androidx.compose.runtime.Composable
    private fun SavedDrift() {
        val p = previous
        // A recorded note drifts "Saved" at once, and "Saved: ..." once more when its words arrive (never if Private).
        if (p != null) Drift(p.id + if (p.words != null) ":words" else "", p.message(), FADE_MS) else Box(Modifier.fillMaxWidth().height(48.dp))
    }

    /** While recording: the level, the clock, and the two hints. No words: they are written out after the save. */
    @androidx.compose.runtime.Composable
    private fun RecordingStatus() {
        Box(
            Modifier.fillMaxWidth().height(12.dp).background(Color.LightGray).clearAndSetSemantics { },
        ) {
            Box(Modifier.fillMaxWidth(recLevel.coerceIn(0f, 1f)).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
        }
        Spacer(Modifier.height(8.dp))
        Text(TranscribeNotice.clock(recElapsedMs), style = MaterialTheme.typography.titleLarge)
        if (!recHeard) Text("Take your time.", style = MaterialTheme.typography.titleMedium)
        if (recNearLimit) Text("Stops at 3:00", style = MaterialTheme.typography.titleMedium)
    }

    /** Grey [text] that drifts up and fades over [ms]. */
    @androidx.compose.runtime.Composable
    private fun Drift(key: String, text: String, ms: Int) {
        val progress = remember(key) { Animatable(0f) }
        LaunchedEffect(key) { progress.animateTo(1f, tween(ms, easing = LinearEasing)) }
        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
            if (progress.value < 1f) {
                Text(
                    text,
                    color = Color.Gray,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer {
                        alpha = 1f - progress.value
                        translationY = -24.dp.toPx() * progress.value
                    },
                )
            }
        }
    }
}
