package app.stash.share

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stash.capture.InboundSink
import app.stash.data.AttachmentSource
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.ShareRules
import app.stash.pictures.Prepared
import app.stash.speech.SpeechEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds everything the annotate screen has made so far, so a rotation loses nothing and prepares nothing twice
 * (docs/cards/16c notes). Pictures are made ready one after another in the order they arrived. Save waits for them.
 * Everything here runs on the main thread; the slow parts (the preparer, the save) switch threads themselves.
 */
class ShareViewModel(private val deps: ShareDeps) : ViewModel() {
    var started = false
        private set

    // --- what the screen draws ---
    var text by mutableStateOf("")
        private set
    var markPrivate by mutableStateOf(false)
        private set
    val pictures = mutableStateListOf<Prepared>()
    var pending by mutableStateOf(0)
        private set
    var failed by mutableStateOf(0)
        private set
    var batchDone by mutableStateOf(0)
        private set
    var batchTotal by mutableStateOf(0)
        private set
    var listening by mutableStateOf(false)
        private set
    var hint by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var galleryNote by mutableStateOf(false)
        private set
    var saveRequested by mutableStateOf(false)
        private set
    var saved by mutableStateOf(false)
        private set
    var finishRequested by mutableStateOf(false)
        private set

    /** Pictures were shared but the PICTURES switch is off. With no words either, the screen is one message and Close. */
    var picturesOff by mutableStateOf(false)
        private set
    var closeOnly by mutableStateOf(false)
        private set
    var enlarged by mutableStateOf<Bitmap?>(null)
        private set
    var enlargedFor: Prepared? = null
        private set

    /** The phone is locked: the gallery button is hidden. Set by the activity on resume. */
    var locked by mutableStateOf(false)

    val voiceAvailable: Boolean get() = deps.voiceOn()
    val picturesAvailable: Boolean get() = deps.picturesOn()

    val saving: Boolean get() = saveRequested || saved
    val canSave: Boolean get() = !saving && ShareRules.canSave(pictures.size, pending, text)

    // --- private machinery ---
    private val queue = ArrayDeque<Pair<Uri, AttachmentSource>>()
    private var running = false
    private var cancelled = false
    private var writing = false
    private var autoStarted = false
    private var session = 0
    private var base = ""
    private var heard = ""
    private var timers: Job? = null
    private var engineUsed = false
    private val engine: SpeechEngine by lazy { engineUsed = true; deps.speech() }

    /** Takes what was shared. Called once, from `onCreate`, while the permission to read it is alive. */
    fun begin(content: SharedContent) {
        if (started) return
        started = true
        text = content.text.orEmpty()
        Dbg.event(DbgComponent.SHARE, DbgCode.RECEIVED, n1 = content.uris.size, n2 = if (text.isNotBlank()) 1 else 0)
        if (content.uris.isNotEmpty()) {
            if (!deps.picturesOn()) {
                picturesOff = true
                closeOnly = text.isBlank()
            } else {
                enqueue(content.uris, AttachmentSource.SHARE)
            }
        }
    }

    /** Pictures chosen in the system photo picker join the strip. */
    fun addPicked(uris: List<Uri>) {
        if (uris.isEmpty() || saving || cancelled || !deps.picturesOn()) return
        enqueue(uris.distinct(), AttachmentSource.PICKER)
    }

    fun onTextChange(s: String) {
        text = s
    }

    fun setPrivate(on: Boolean) {
        markPrivate = on
    }

    // --- pictures ---

    private fun enqueue(uris: List<Uri>, source: AttachmentSource) {
        if (!running) { batchDone = 0; batchTotal = 0 }
        for (u in uris) queue.addLast(u to source)
        batchTotal += uris.size
        pending += uris.size
        if (!running) {
            running = true
            viewModelScope.launch { drain() }
        }
    }

    private suspend fun drain() {
        try {
            while (queue.isNotEmpty()) {
                val (uri, source) = queue.removeFirst()
                // Finish the one in hand even if the screen goes away: its file must be handed back, then deleted or saved.
                val p = withContext(NonCancellable) {
                    try { deps.intake.prepare(uri, source) } catch (e: Exception) { null }
                }
                pending--
                batchDone++
                when {
                    p == null -> failed++
                    cancelled -> deps.intake.discard(p)
                    else -> { pictures.add(p); onPictureReady(inGallery = true) }
                }
            }
        } finally {
            running = false
        }
        tryProceedSave()
    }

    /**
     * A picture already made ready elsewhere, in memory: the camera (docs/cards/16e). It joins the strip like any other,
     * but it never shows the "still in your gallery" line, because a camera picture is not in the gallery. Ignored (and
     * its file deleted) if the screen is already saving or was cancelled.
     */
    fun addPrepared(p: Prepared) {
        if (saving || cancelled) { deps.intake.discard(p); return }
        pictures.add(p)
        onPictureReady(inGallery = false)
    }

    private fun onPictureReady(inGallery: Boolean) {
        if (inGallery && !galleryNote && !deps.galleryNoteSeen()) {
            galleryNote = true
            deps.markGalleryNoteSeen()
        }
        if (ShareRules.AUTO_LISTEN && !autoStarted && deps.voiceOn()) {
            autoStarted = true
            startListening()
        }
    }

    fun remove(p: Prepared) {
        if (saving || cancelled) return
        if (!pictures.remove(p)) return
        if (enlargedFor === p) closeEnlarged()
        deps.intake.discard(p)
    }

    fun open(p: Prepared) {
        enlargedFor = p
        enlarged = p.thumbnail
        viewModelScope.launch {
            val big = deps.intake.preview(p)
            if (big != null && enlargedFor === p) enlarged = big
        }
    }

    fun closeEnlarged() {
        enlargedFor = null
        enlarged = null
    }

    // --- listening ---

    fun toggleMic() {
        if (listening) stopListening() else startListening()
    }

    fun startListening() {
        if (listening || saving || cancelled || !deps.voiceOn()) return
        base = text
        heard = ""
        hint = null
        listening = true
        val mine = ++session
        viewModelScope.launch {
            deps.cues.start()
            delay(ShareRules.CUE_GAP_MS) // let the beep finish so it is not recorded
            if (mine != session || !listening) return@launch
            val words = try { deps.vocabulary() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
            if (mine != session || !listening) return@launch
            vocabulary = words
            engine.start(listener, words)
            armTimers(mine)
        }
    }

    private var vocabulary: List<String> = emptyList()

    /** Tapping the text field, Save or Cancel ends listening. Words heard so far are kept. */
    fun stopListening() {
        if (!listening) return
        if (!engineUsed || heard.isBlank()) {
            if (engineUsed) engine.cancel()
            endListening(null)
            return
        }
        engine.stop() // the final words arrive in the listener; if they never do, give up after a moment
        val mine = session
        viewModelScope.launch {
            delay(3_000)
            if (mine == session && listening) endListening(null)
        }
    }

    private fun armTimers(mine: Int) {
        timers?.cancel()
        timers = viewModelScope.launch {
            delay(ShareRules.SILENCE_LIMIT_MS)
            if (mine == session && listening && heard.isBlank()) {
                engine.cancel()
                endListening(ShareRules.NOTHING_HEARD)
                return@launch
            }
            delay(ShareRules.HARD_LIMIT_MS - ShareRules.SILENCE_LIMIT_MS)
            if (mine == session && listening) engine.stop()
        }
    }

    private fun endListening(newHint: String?) {
        timers?.cancel()
        session++
        listening = false
        hint = newHint
        tryProceedSave()
    }

    private val listener: SpeechEngine.Listener = object : SpeechEngine.Listener {
        override fun onPartial(text: String) {
            if (!listening) return
            heard = text
            this@ShareViewModel.text = ShareRules.join(base, text)
        }

        override fun onFinal(text: String) {
            if (!listening) return
            if (text.isBlank() && heard.isBlank()) { endListening(ShareRules.NOTHING_HEARD); return }
            this@ShareViewModel.text = ShareRules.join(base, text.ifBlank { heard })
            endListening(null)
        }

        override fun onFailure(message: String, noSpeech: Boolean) {
            if (!listening) return
            if (noSpeech && heard.isBlank()) {
                // Nobody has spoken yet: listen again; the silence timer decides when to stop.
                val mine = session
                viewModelScope.launch {
                    delay(400)
                    if (mine == session && listening && heard.isBlank()) engine.start(this@ShareViewModel.listener, vocabulary)
                }
                return
            }
            endListening(message)
        }

    }

    // --- saving and leaving ---

    /** Save was tapped. If pictures are still being made ready, or he is still speaking, the save happens when they are done. */
    fun onSave() {
        if (!canSave) return
        message = null
        saveRequested = true
        if (listening) stopListening()
        tryProceedSave()
    }

    private fun tryProceedSave() {
        if (!saveRequested || cancelled || writing || saved || pending > 0 || listening) return
        if (!ShareRules.hasContent(pictures.size, text)) {
            saveRequested = false
            message = ShareRules.NOTHING_TO_SAVE
            return
        }
        writing = true
        val items = pictures.map { it.attachment }
        val raw = text
        viewModelScope.launch {
            val result = try {
                deps.save(raw, deps.isLocked(), markPrivate, items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                InboundSink.Result.Failed(e.javaClass.simpleName)
            }
            writing = false
            when (result) {
                is InboundSink.Result.Saved -> {
                    saved = true
                    Dbg.event(DbgComponent.SHARE, DbgCode.SAVED, n1 = items.size, n2 = if (raw.isNotBlank()) 1 else 0)
                    deps.cues.saved()
                    delay(deps.savedShownMs)
                    finishRequested = true
                }
                is InboundSink.Result.Failed -> {
                    // Nothing was saved. The prepared files are kept, so Save can be pressed again.
                    saveRequested = false
                    message = ShareRules.SAVE_FAILED
                    deps.cues.failed()
                }
            }
        }
    }

    /** Cancel, the back gesture, or Close: stop listening, delete every prepared file, leave. Nothing is saved. */
    fun cancel() {
        if (cancelled || saved || writing) return
        cancelled = true
        saveRequested = false
        shutEngine()
        queue.clear()
        pending = if (running) 1 else 0
        Dbg.event(DbgComponent.SHARE, DbgCode.CANCELLED, n1 = pictures.size, n2 = if (text.isNotBlank()) 1 else 0)
        discardAll()
        finishRequested = true
    }

    private fun discardAll() {
        closeEnlarged()
        val all = pictures.toList()
        pictures.clear()
        for (p in all) deps.intake.discard(p)
    }

    private fun shutEngine() {
        timers?.cancel()
        session++
        listening = false
        if (engineUsed) engine.cancel()
    }

    override fun onCleared() {
        shutEngine()
        if (!saved) {
            cancelled = true
            discardAll()
        }
        if (engineUsed) engine.destroy()
        deps.cues.release()
    }
}

/**
 * What the annotate screen draws for [vm] (docs/cards/16c). One place, used by the share screen and by the camera's own
 * annotate step (card 16e, which passes [cameraVisible]).
 */
fun annotateUiFor(vm: ShareViewModel, cameraVisible: Boolean = false): AnnotateUi {
    val voice = vm.voiceAvailable
    return AnnotateUi(
        thumbs = vm.pictures.map { AnnotateThumb(it.attachment.id, it.thumbnail) },
        progress = if (vm.pending > 0) ShareRules.gettingReady((vm.batchDone + 1).coerceAtMost(vm.batchTotal), vm.batchTotal) else null,
        notAdded = ShareRules.couldNotAdd(vm.failed),
        galleryNote = if (vm.galleryNote) ShareRules.GALLERY_NOTE else null,
        draftLine = app.stash.domain.PictureTextRules.draftLine(vm.pictures.firstOrNull()?.attachment?.ocrText),
        notice = if (vm.picturesOff) ShareRules.PICTURES_OFF else null,
        text = vm.text,
        listening = vm.listening,
        micAvailable = voice,
        voiceHint = vm.hint ?: if (!voice) ShareRules.VOICE_OFF else null,
        galleryVisible = !vm.locked && vm.picturesAvailable && !vm.saving,
        cameraVisible = cameraVisible && !vm.saving,
        markPrivate = vm.markPrivate,
        saveEnabled = vm.canSave,
        saveLabel = if (vm.saveRequested && !vm.saved) "Saving..." else "Save",
        cancelEnabled = !vm.saving,
        message = vm.message,
        saved = vm.saved,
        enlarged = vm.enlarged,
    )
}
