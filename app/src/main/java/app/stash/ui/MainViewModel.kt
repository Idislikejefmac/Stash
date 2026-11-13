package app.stash.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.stash.StashApp
import app.stash.capture.Cues
import app.stash.capture.InboundSink
import app.stash.data.CaptureSource
import app.stash.data.CorrectionEntity
import app.stash.domain.Capability
import app.stash.domain.SensitiveReason
import app.stash.data.CaptureWithRecords
import app.stash.data.PictureStats
import app.stash.domain.PictureWords
import app.stash.share.PreparerIntake
import app.stash.ui.pictures.PictureAdder
import app.stash.ui.pictures.PictureHost
import app.stash.ui.pictures.PictureSession
import app.stash.ui.pictures.RepositoryPictureHost
import app.stash.ui.pictures.StoredPictureLoader
import app.stash.data.TaskEntity
import app.stash.data.TaskItems
import app.stash.domain.CloseSetting
import app.stash.domain.SnoozePreset
import app.stash.domain.TaskSections
import app.stash.data.NudgeOutcome
import app.stash.domain.AlarmKind
import app.stash.domain.NudgeSettings
import app.stash.domain.WeeklyReview
import app.stash.reminders.ReminderIntents
import app.stash.reminders.TestOutcome
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import app.stash.speech.AndroidSpeechEngine
import app.stash.speech.SpeechEngine
import app.stash.speech.ImportFailure
import app.stash.speech.ImportResult
import app.stash.speech.ImportWords
import app.stash.speech.KnownPack
import app.stash.domain.Notice
import app.stash.domain.OpenSourceNotices
import app.stash.triggers.NoteNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AskState(
    val question: String = "",
    val listening: Boolean = false,
    /** The answer (card 08): its words, sources and trace. Null until a question has been asked. */
    val result: app.stash.ask.AskResult? = null,
    /** "Why this answer?", once opened: the trace in plain sentences. */
    val why: List<String>? = null,
    /** After "That's not it": the plain search list (empty: nothing matched). */
    val wrongList: List<app.stash.ask.SourceNote>? = null,
    val message: String? = null,
)

/** Settings, "Voice notes" (card 26d): which speech model is on the phone, and an import in progress. */
data class SpeechModelState(
    val engineBuilt: Boolean,
    val installed: KnownPack? = null,
    val busy: Boolean = false,
    val progress: String? = null,
    val message: String? = null,
)

/** One line of "What Stash may use". */
data class CapabilityRow(val capability: Capability, val switchOn: Boolean, val permissionGranted: Boolean, val on: Boolean)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as StashApp).container
    private val repo = container.repo
    val settings = container.settings

    val notes: StateFlow<List<CaptureWithRecords>> =
        repo.observeCaptures().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val vocab: StateFlow<List<String>> =
        repo.observeVocab().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val corrections: StateFlow<List<CorrectionEntity>> =
        repo.observeCorrections().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val today = MutableStateFlow(LocalDate.now())

    /** The "To handle" screen. Private tasks are not drawn here: the app gate (card 02) that would allow it does not exist yet. */
    val taskSections: StateFlow<TaskSections> =
        combine(notes, today) { n, d -> TaskSections.build(TaskItems.from(n, includeSensitive = false), d, System.currentTimeMillis()) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, TaskSections())

    /**
     * The "Readings" tab (card 07): one line per metric. Private readings are not drawn: the app gate (card 02) that would
     * allow it does not exist yet, so includeSensitive is false here and in every call below.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val readingSummaries: StateFlow<List<app.stash.data.MetricSummary>> =
        notes.mapLatest { withContext(Dispatchers.IO) { repo.readings.summaries(includeSensitive = false) } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Every value of one metric, newest first (the plain list, card 07 pass 1). */
    suspend fun metricValues(metricId: String): List<app.stash.domain.ReadingFact> =
        withContext(Dispatchers.IO) { repo.readings.all(metricId, includeSensitive = false) }

    val pictureSize = MutableStateFlow(settings.pictureSize)

    // --- pictures on a note (card 16d) ---

    private val pictureLoader = StoredPictureLoader({ id -> repo.attachments.openBytes(id) })
    /** What is shown and open right now; cleared when the activity stops. */
    val pictures = PictureSession(pictureLoader)
    val pictureHost: PictureHost = RepositoryPictureHost(repo, pictureLoader)
    private val adder by lazy {
        PictureAdder(PreparerIntake(container.picturePreparer, container.attachmentStore, app.contentResolver), container.sink)
    }
    /** A note being added to: "k of n". */
    val adding = MutableStateFlow<Map<String, Pair<Int, Int>>>(emptyMap())
    val pictureMessage = MutableStateFlow<String?>(null)
    val pictureStats = MutableStateFlow(PictureStats(0, 0))

    fun refreshPictureStats() {
        viewModelScope.launch { pictureStats.value = withContext(Dispatchers.IO) { repo.attachments.pictureStats() } }
    }

    /** Picked in the system photo picker: prepared, then handed to the sink as one [app.stash.capture.Inbound.AddAttachments]. */
    fun addPictures(captureId: String, uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        pictureMessage.value = null
        if (!container.capabilities.isOn(Capability.PICTURES)) {
            pictureMessage.value = app.stash.domain.ShareRules.PICTURES_OFF
            return
        }
        viewModelScope.launch {
            val outcome = try {
                adder.add(captureId, uris) { k, n -> adding.value = adding.value + (captureId to (k to n)) }
            } finally {
                adding.value = adding.value - captureId
            }
            pictureMessage.value = when {
                outcome.failed -> PictureWords.ADD_FAILED
                else -> app.stash.domain.ShareRules.couldNotAdd(outcome.couldNotRead)
            }
        }
    }

    fun setPictureSize(s: app.stash.domain.PictureSize) {
        settings.pictureSize = s
        pictureSize.value = s
    }

    val closeSetting = MutableStateFlow(settings.closeSetting)

    fun setCloseSetting(c: CloseSetting) {
        settings.closeSetting = c
        closeSetting.value = c
    }

    /** The day can change while the app is open. */
    fun refreshToday() { today.value = LocalDate.now() }

    suspend fun markDone(recordId: String): TaskEntity? = withContext(Dispatchers.IO) { repo.tasks.markDone(recordId) }
    suspend fun dropTask(recordId: String): TaskEntity? = withContext(Dispatchers.IO) { repo.tasks.drop(recordId) }
    suspend fun reopenTask(recordId: String): TaskEntity? = withContext(Dispatchers.IO) { repo.tasks.reopen(recordId) }
    suspend fun bringBackTask(recordId: String): TaskEntity? = withContext(Dispatchers.IO) { repo.tasks.bringBack(recordId) }
    suspend fun snoozeTask(recordId: String, preset: SnoozePreset): TaskEntity? =
        withContext(Dispatchers.IO) { repo.tasks.snooze(recordId, preset.resolve(LocalDate.now())) }
    suspend fun undoTask(previous: TaskEntity) = withContext(Dispatchers.IO) { repo.tasks.restore(previous) }

    val ask = MutableStateFlow(AskState())
    val hostedEnabled = MutableStateFlow(settings.hostedEnabled)
    val notificationEnabled = MutableStateFlow(settings.notificationEnabled)
    val hasKey = MutableStateFlow(settings.apiKey != null)

    private val engine: SpeechEngine = AndroidSpeechEngine(app)
    private val cues = Cues(app)

    val alwaysPrivate = MutableStateFlow(settings.alwaysPrivateWords)
    val capabilityRows = MutableStateFlow(rows())
    /** Shown under the typed-note box: "Saved", or what went wrong. */
    val typedStatus = MutableStateFlow<String?>(null)

    private fun rows() = Capability.entries.map {
        CapabilityRow(it, container.capabilities.switchOn(it), container.capabilities.permissionGranted(it), container.capabilities.isOn(it))
    }

    // --- reminders (card 06) ---

    val nudge = MutableStateFlow(settings.nudge)

    /** Where a tap on a reminder asked the app to go: "to_handle" or "review". Cleared once the screen has gone there. */
    val openRequest = MutableStateFlow<String?>(null)
    val review = MutableStateFlow<WeeklyReview?>(null)
    val reminderTestAvailable: Boolean get() = container.reminders.isDebuggable()

    fun setNudge(n: NudgeSettings) {
        settings.nudge = n
        nudge.value = settings.nudge
        container.reminders.settingsChanged()
    }

    fun setDailyOn(on: Boolean) {
        settings.setDailyOn(on, System.currentTimeMillis())
        nudge.value = settings.nudge
        container.reminders.settingsChanged()
    }

    /** A reminder was tapped: mark it opened and go to the screen it names. */
    fun handleReminderIntent(intent: android.content.Intent?) {
        val open = intent?.getStringExtra(ReminderIntents.EXTRA_OPEN) ?: return
        intent.removeExtra(ReminderIntents.EXTRA_OPEN)
        val postId = intent.getStringExtra(ReminderIntents.EXTRA_POST_ID)
        if (postId != null) viewModelScope.launch(Dispatchers.IO) { container.reminders.engine.outcome(postId, NudgeOutcome.OPENED) }
        openRequest.value = open
        if (open == ReminderIntents.OPEN_REVIEW) loadReview()
    }

    fun loadReview() {
        viewModelScope.launch(Dispatchers.IO) { review.value = container.reminders.engine.reviewNow() }
    }

    suspend fun testReminder(kind: AlarmKind): String = when (withContext(Dispatchers.IO) { container.reminders.debugTest(kind) }) {
        TestOutcome.READY -> "Set. Lock your phone now. It should ring in about a minute."
        TestOutcome.NO_TASK -> "There is no open to-do to use. Add a note like: remind me to water the plants soon."
        TestOutcome.NOTHING_TO_REVIEW -> "There is nothing to put in a review yet."
    }

    /** Called when the screen comes back, since permissions are changed outside the app. */
    fun refreshCapabilities() {
        nudge.value = settings.nudge
        container.reminders.scheduleAlarms()
        capabilityRows.value = rows()
        hostedEnabled.value = settings.hostedEnabled
        notificationEnabled.value = settings.notificationEnabled
        lockedMicBlocked.value = settings.lockedMicBlocked // the capture screen may have set it
        refreshSpeechReady()
    }

    // --- card 26g: the voice-note settings ---

    val endPause = MutableStateFlow(settings.endPause)
    val lockedMicBlocked = MutableStateFlow(settings.lockedMicBlocked)
    val lockedChoice = MutableStateFlow(settings.lockedChoice)

    fun setEndPause(p: app.stash.domain.EndPause) {
        settings.endPause = p
        endPause.value = p
    }

    /** "Try recording on the lock screen again": forget that the locked microphone was blocked. */
    fun tryLockedRecordingAgain() {
        settings.lockedMicBlocked = false
        lockedMicBlocked.value = false
    }

    fun setLockedChoice(c: app.stash.capture.LockedChoice) {
        settings.lockedChoice = c
        lockedChoice.value = c
    }

    /** The length of a note's recording, for its row while it has no words (card 26g item 13). Null: no recording. */
    suspend fun recordingMs(captureId: String): Long? =
        withContext(Dispatchers.IO) { runCatching { repo.recordings.info(captureId)?.durationMs }.getOrNull() }

    /** Card 26f: whether a note waiting for its words can be written out now (a speech model is ready). */
    val speechModelReady = MutableStateFlow(false)

    fun refreshSpeechReady() {
        viewModelScope.launch {
            speechModelReady.value = withContext(Dispatchers.IO) {
                runCatching { container.transcriber.availability() == app.stash.speech.Availability.Ready }.getOrDefault(false)
            }
        }
    }

    fun setCapability(c: Capability, on: Boolean) {
        container.capabilities.setSwitch(c, on)
        if (c == Capability.CAPTURE_NOTIFICATION) NoteNotification.sync(getApplication(), container.capabilities.isOn(c))
        // Card 16f item 12: turning picture reading on reads the pictures that were stored without it.
        if (c == Capability.PICTURE_TEXT && on) viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            try { container.pictureCatchUp.run() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
        }
        refreshCapabilities()
    }

    /** Type a note instead of speaking it: the same door, the same save-then-read. */
    fun saveTyped(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { container.entry.save(t, CaptureSource.TYPED, capturedLocked = false) }
            if (result is InboundSink.Result.Saved) {
                cues.saved()
                typedStatus.value = "Saved"
            } else {
                cues.failed()
                typedStatus.value = "I couldn't save that note."
            }
            // A passing message, not a button: it goes away by itself (owner, phone check 2026-11-13).
            val shown = typedStatus.value
            kotlinx.coroutines.delay(2_500)
            if (typedStatus.value == shown) typedStatus.value = null
        }
    }

    fun clearTypedStatus() { typedStatus.value = null }

    /** Turning Private on is always allowed; the screen asks before turning it off. */
    fun setPrivate(n: CaptureWithRecords, on: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        repo.captures.setSensitive(n.capture.id, on, if (on) SensitiveReason.SAID.code else null)
    }

    fun readAgain(n: CaptureWithRecords) = viewModelScope.launch(Dispatchers.IO) { container.readAgain(n.capture.id) }

    fun addPrivateWord(w: String) {
        settings.alwaysPrivateWords = settings.alwaysPrivateWords + w
        alwaysPrivate.value = settings.alwaysPrivateWords
    }

    fun removePrivateWord(w: String) {
        settings.alwaysPrivateWords = settings.alwaysPrivateWords - w
        alwaysPrivate.value = settings.alwaysPrivateWords
    }

    fun setQuestion(q: String) { ask.value = ask.value.copy(question = q) }

    /**
     * One pipeline for every question (docs/cards/08 section E): the router and coded answers (the task and readings
     * questions of cards 05 and 07 are routes now), then plain search. Nothing is sent anywhere: card 08 pass 2 adds
     * the tool loop. Each question leaves one answer trace.
     */
    fun submitQuestion() {
        val q = ask.value.question.trim()
        if (q.isEmpty()) return
        viewModelScope.launch {
            ask.value = ask.value.copy(result = null, why = null, wrongList = null, message = "Looking...")
            val r = withContext(Dispatchers.IO) { container.askEngine.ask(q) }
            ask.value = ask.value.copy(result = r, why = null, wrongList = null, message = null)
        }
    }

    /** "Why this answer?" under the answer: opens (or closes) the trace in plain words. */
    fun toggleWhy() {
        val r = ask.value.result ?: return
        if (ask.value.why != null) {
            ask.value = ask.value.copy(why = null)
            return
        }
        viewModelScope.launch {
            val lines = withContext(Dispatchers.IO) { container.askEngine.why(r.traceId) } ?: listOf("This answer's record has been deleted.")
            ask.value = ask.value.copy(why = lines)
        }
    }

    /** "That's not it": marked on the trace, added to the miss log, and the plain search list shown. */
    fun markWrong() {
        val r = ask.value.result ?: return
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { container.askEngine.markWrong(r.traceId) }
            ask.value = ask.value.copy(wrongList = list, why = null)
        }
    }

    fun startVoice(): Boolean {
        if (ask.value.listening) {
            engine.stop()
            return true
        }
        ask.value = ask.value.copy(listening = true, message = "Listening...", result = null, why = null, wrongList = null)
        viewModelScope.launch {
            val words = withContext(Dispatchers.IO) { repo.vocabWords() }
            engine.start(object : SpeechEngine.Listener {
                override fun onPartial(text: String) { ask.value = ask.value.copy(question = text) }
                override fun onFinal(text: String) {
                    viewModelScope.launch {
                        val fixed = withContext(Dispatchers.IO) { repo.correct(text) }
                        ask.value = ask.value.copy(question = fixed, listening = false, message = null)
                        submitQuestion()
                    }
                }
                override fun onFailure(message: String, noSpeech: Boolean) {
                    ask.value = ask.value.copy(listening = false, message = message)
                }
            }, words)
        }
        return true
    }

    // --- the quiet "Add a picture?" offer on a note's row (card 16g) ---
    private val offerLogged = mutableSetOf<String>()

    /** Is the offer showing for this note? One row and one count, by id. The first time it shows, one counted event. */
    suspend fun offerShown(captureId: String): Boolean {
        val shown = withContext(Dispatchers.IO) { repo.captures.pictureOfferShown(captureId) }
        if (shown && offerLogged.add(captureId)) app.stash.domain.Dbg.event(app.stash.domain.DbgComponent.OFFER, app.stash.domain.DbgCode.OFFERED, n1 = 2, refId = captureId)
        return shown
    }

    /** "Add a picture" on the offer line. The caller then opens the same choice as the row's ordinary button. */
    fun offerAccepted(captureId: String) =
        app.stash.domain.Dbg.event(app.stash.domain.DbgComponent.OFFER, app.stash.domain.DbgCode.ACCEPTED, n1 = 2, refId = captureId)

    /** "No thanks": the offer is closed for good. No revision, no message. */
    fun dismissOffer(captureId: String) = viewModelScope.launch(Dispatchers.IO) {
        if (repo.captures.setPhotoHint(captureId, app.stash.data.PhotoHint.DISMISSED)) {
            app.stash.domain.Dbg.event(app.stash.domain.DbgComponent.OFFER, app.stash.domain.DbgCode.DISMISSED, n1 = 1, refId = captureId)
        }
    }

    fun deleteNote(n: CaptureWithRecords) = viewModelScope.launch(Dispatchers.IO) { repo.deleteCapture(n.capture.id) }

    fun correctWord(n: CaptureWithRecords, heard: String, meant: String) =
        viewModelScope.launch(Dispatchers.IO) {
            repo.correctWord(n.capture.id, heard, meant)
            // A fixed word can change a reading ("for" heard for "4"): read the note again so every list follows the
            // corrected words. Pinned records stay (owner, phone check 2026-11-13).
            container.readAgain(n.capture.id)
        }

    fun addWord(w: String) = viewModelScope.launch(Dispatchers.IO) {
        if (w.isNotBlank()) repo.addWord(w.trim())
    }

    fun removeWord(w: String) = viewModelScope.launch(Dispatchers.IO) { repo.removeWord(w) }

    fun addCorrection(heard: String, meant: String) = viewModelScope.launch(Dispatchers.IO) {
        if (heard.isNotBlank() && meant.isNotBlank()) repo.putCorrection(heard.trim().lowercase(), meant.trim())
    }

    fun removeCorrection(heard: String) = viewModelScope.launch(Dispatchers.IO) { repo.removeCorrection(heard) }

    fun setHosted(on: Boolean) { setCapability(Capability.HOSTED_MODEL, on) }

    fun setNotification(on: Boolean) { setCapability(Capability.CAPTURE_NOTIFICATION, on) }

    fun saveKey(key: String) {
        settings.apiKey = key.ifBlank { null }
        hasKey.value = settings.apiKey != null
    }

    fun exportText(): String = repo.exportText(notes.value)

    // --- the speech model pack and the open source notices (card 26d) ---

    val speechModel = MutableStateFlow(SpeechModelState(engineBuilt = container.speechEngineBuilt))

    fun refreshSpeechModel() {
        viewModelScope.launch {
            val p = withContext(Dispatchers.IO) { container.modelLocator.find()?.pack }
            speechModel.value = speechModel.value.copy(installed = p)
        }
    }

    /** The file the owner picked in the system file picker. Read once, verified, installed; nothing is downloaded. */
    fun importSpeechModel(uri: android.net.Uri) {
        if (speechModel.value.busy) return
        speechModel.value = speechModel.value.copy(busy = true, message = null, progress = "Adding the speech model...")
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                container.transcriber.release() // a loaded model may be replaced
                var shown = -1L
                val input = runCatching { getApplication<Application>().contentResolver.openInputStream(uri) }.getOrNull()
                input?.use {
                    container.modelImporter.import(it) { done, total ->
                        val pct = if (total > 0) done * 100 / total else 0
                        if (pct != shown) {
                            shown = pct
                            speechModel.value = speechModel.value.copy(progress = "Adding the speech model: $pct%")
                        }
                    }
                } ?: ImportResult.Failed(ImportFailure.IO_ERROR)
            }
            val p = withContext(Dispatchers.IO) { container.modelLocator.find()?.pack }
            speechModel.value = speechModel.value.copy(busy = false, progress = null, message = ImportWords.message(result), installed = p)
            refreshSpeechReady()
        }
    }

    fun removeSpeechModel() {
        val p = speechModel.value.installed ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                container.transcriber.release()
                container.modelPacks.remove(p.id)
            }
            val left = withContext(Dispatchers.IO) { container.modelLocator.find()?.pack }
            speechModel.value = speechModel.value.copy(installed = left, message = "Speech model removed.")
            refreshSpeechReady()
        }
    }

    val notices: List<Notice> = OpenSourceNotices.forBuild(container.speechEngineBuilt)

    /** One notice's full text: its files, in order, unchanged. */
    suspend fun noticeText(n: Notice): String = withContext(Dispatchers.IO) {
        val assets = getApplication<Application>().assets
        n.assetPaths.joinToString("\n\n") { path ->
            runCatching { assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) } }.getOrDefault("(This text is missing from this build.)")
        }
    }

    override fun onCleared() { engine.destroy(); cues.release() }
}
