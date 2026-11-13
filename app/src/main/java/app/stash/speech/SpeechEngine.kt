package app.stash.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent

/**
 * Live speech to text: the phone's own recogniser, behind a small interface. Implementations listen for themselves
 * and hand back words only; **nothing heard through this interface is ever stored as sound**.
 *
 * Which route stores what (card 26g, D77):
 *  - The quick note (the capture screen with recording off, no speech model, or a blocked recorder), the Share
 *    screen's microphone and the Ask tab's "Speak" all use this interface. Words only; no recording exists.
 *  - A recorded voice note does not come through here at all. The capture screen uses the app's own recorder
 *    (`app.stash.audio`, `capture/RecordingSession`), keeps the recording as the note's encrypted `AUDIO` attachment
 *    until the note or the recording is deleted, and has it written out by `speech/Transcriber`. It never leaves the phone.
 * All methods are called on the main thread.
 */
interface SpeechEngine {
    interface Listener {
        fun onListening() {}
        fun onPartial(text: String) {}
        fun onFinal(text: String)
        /** [message] is plain language, ready to show. [noSpeech] means nobody spoke. */
        fun onFailure(message: String, noSpeech: Boolean)
    }

    /** Name for the log and the Settings screen. */
    val name: String

    /** [biasWords] are the personal vocabulary, passed as hints where the platform supports it. */
    fun start(listener: Listener, biasWords: List<String>)

    /** Stop listening and deliver what was heard so far. */
    fun stop()

    /** Stop and throw everything away. */
    fun cancel()

    fun destroy()
}

/** Android's SpeechRecognizer, on-device when the phone has an on-device recognizer. */
class AndroidSpeechEngine(private val context: Context) : SpeechEngine {
    private var recognizer: SpeechRecognizer? = null
    private var listener: SpeechEngine.Listener? = null
    private var biasWords: List<String> = emptyList()
    private var lastPartial = ""
    private var finished = false
    private var attempt = 0

    /** Each recognizer gets a number; callbacks from an older one (e.g. just destroyed) are ignored. */
    private var generation = 0
    private val handler = Handler(Looper.getMainLooper())

    override var name: String = "android"
        private set

    override fun start(listener: SpeechEngine.Listener, biasWords: List<String>) {
        this.listener = listener
        this.biasWords = biasWords
        lastPartial = ""
        finished = false
        attempt = 0
        begin(preferOnDevice = true)
    }

    private fun begin(preferOnDevice: Boolean) {
        handler.removeCallbacksAndMessages(null)
        destroyRecognizer()
        val gen = ++generation
        val onDevice = preferOnDevice && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val r = when {
            onDevice -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { name = "android-on-device" }
            SpeechRecognizer.isRecognitionAvailable(context) -> SpeechRecognizer.createSpeechRecognizer(context).also { name = "android-default" }
            else -> {
                finish { it.onFailure("Speech recognition isn't available on this phone.", false) }
                return
            }
        }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { if (gen == generation) listener?.onListening() }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                if (gen != generation) return
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (t.isNotBlank()) {
                    lastPartial = t
                    listener?.onPartial(t)
                }
            }

            override fun onResults(results: Bundle?) {
                if (gen != generation) return
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                finish { it.onFinal(t.ifBlank { lastPartial }) }
            }

            override fun onError(error: Int) {
                if (gen != generation || finished) return
                // If something was heard before the error, keep it.
                if (lastPartial.isNotBlank() && error != SpeechRecognizer.ERROR_CLIENT) {
                    finish { it.onFinal(lastPartial) }
                    return
                }
                if (error == SpeechRecognizer.ERROR_CLIENT) return // we cancelled it ourselves
                val decision = SpeechRetryPolicy.decide(error, attempt)
                // Error codes and engine names only: never anything that was said.
                Dbg.event(DbgComponent.SPEECH, DbgCode.SPEECH_ERROR, n1 = error, n2 = attempt)
                when (decision) {
                    is SpeechRetryPolicy.Decision.Restart -> {
                        attempt++
                        handler.postDelayed({ if (!finished && gen == generation) begin(decision.onDevice) }, decision.delayMs)
                    }
                    is SpeechRetryPolicy.Decision.Fail -> finish { it.onFailure(decision.message, decision.silent) }
                }
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Stop about 1.8 s after speech ends (the platform may treat these as hints).
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            // API 33+; minSdk is 35 so no guard is needed here, but a future lower minSdk must add one.
            if (biasWords.isNotEmpty()) {
                putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(biasWords))
            }
        }
        r.startListening(intent)
    }

    private fun finish(block: (SpeechEngine.Listener) -> Unit) {
        if (finished) return
        finished = true
        val l = listener
        if (l != null) block(l)
    }

    override fun stop() { recognizer?.stopListening() }

    override fun cancel() {
        finished = true
        generation++ // anything still arriving from the old recognizer is ignored
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
    }

    private fun destroyRecognizer() {
        generation++ // late callbacks from the one being destroyed must not touch the next session
        recognizer?.destroy()
        recognizer = null
    }

    override fun destroy() {
        handler.removeCallbacksAndMessages(null)
        destroyRecognizer()
    }
}
