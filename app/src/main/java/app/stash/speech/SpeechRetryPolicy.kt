package app.stash.speech

import android.speech.SpeechRecognizer

/**
 * What to do when the platform recognizer reports an error. Pure, so it can be tested without a
 * phone. Found on 2026-11-11: after a silent timeout, "Try again" made the recognizer service
 * report a lost connection every time, and the app showed "needs a connection" and a buzz in a
 * loop. A lost or busy service is retried on a fresh recognizer (after a pause), then on the
 * phone's default recognizer, and only then reported, with the error code.
 */
object SpeechRetryPolicy {
    sealed interface Decision {
        /** Start a fresh recognizer after [delayMs]; [onDevice] false means the phone's default one. */
        data class Restart(val onDevice: Boolean, val delayMs: Long) : Decision

        /** Report it. [silent] means "nobody spoke": the caller may quietly listen again. */
        data class Fail(val message: String, val silent: Boolean) : Decision
    }

    const val RESTART_DELAY_MS = 700L

    fun decide(error: Int, attempt: Int): Decision = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            Decision.Fail("I didn't catch that.", silent = true)
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            Decision.Fail("Stash needs the microphone permission.", silent = false)
        SpeechRecognizer.ERROR_AUDIO ->
            Decision.Fail("The microphone couldn't be used (code $error). Another app may be using it.", silent = false)
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            Decision.Fail("The phone's speech language pack isn't available (code $error).", silent = false)
        // Lost service, busy recognizer, network or server errors, rate limits, support checks.
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT, SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> when (attempt) {
            0 -> Decision.Restart(onDevice = true, delayMs = RESTART_DELAY_MS)
            1 -> Decision.Restart(onDevice = false, delayMs = RESTART_DELAY_MS)
            else -> Decision.Fail("Speech recognition isn't responding (code $error). Wait a moment and try again.", silent = false)
        }
        else -> Decision.Fail("Something went wrong with listening (code $error).", silent = false)
    }
}
