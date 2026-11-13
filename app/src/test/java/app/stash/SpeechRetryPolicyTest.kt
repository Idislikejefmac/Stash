package app.stash

import android.speech.SpeechRecognizer
import app.stash.speech.SpeechRetryPolicy
import app.stash.speech.SpeechRetryPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechRetryPolicyTest {
    private val connectionErrors = listOf(
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
    )

    @Test
    fun `a lost or busy recognizer is retried on a fresh one, then on the default one, then reported with its code`() {
        for (error in connectionErrors) {
            assertEquals(Decision.Restart(onDevice = true, delayMs = SpeechRetryPolicy.RESTART_DELAY_MS), SpeechRetryPolicy.decide(error, 0))
            assertEquals(Decision.Restart(onDevice = false, delayMs = SpeechRetryPolicy.RESTART_DELAY_MS), SpeechRetryPolicy.decide(error, 1))
            val last = SpeechRetryPolicy.decide(error, 2) as Decision.Fail
            assertFalse(last.silent)
            assertTrue("shows the code: ${last.message}", last.message.contains("code $error"))
        }
    }

    @Test
    fun `no speech is silent, so the caller can quietly listen again`() {
        for (error in listOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
            for (attempt in 0..3) {
                assertEquals(Decision.Fail("I didn't catch that.", silent = true), SpeechRetryPolicy.decide(error, attempt))
            }
        }
    }

    @Test
    fun `no message wrongly blames the connection`() {
        val messages = (1..15).flatMap { error -> (0..2).map { SpeechRetryPolicy.decide(error, it) } }
            .filterIsInstance<Decision.Fail>().map { it.message }
        assertTrue(messages.none { it.contains("needs a connection") })
    }

    @Test
    fun `permission and microphone errors are reported straight away`() {
        val perm = SpeechRetryPolicy.decide(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, 0) as Decision.Fail
        assertTrue(perm.message.contains("microphone"))
        assertFalse(perm.silent)
        val audio = SpeechRetryPolicy.decide(SpeechRecognizer.ERROR_AUDIO, 0) as Decision.Fail
        assertTrue(audio.message.contains("code ${SpeechRecognizer.ERROR_AUDIO}"))
    }

    @Test
    fun `unknown errors are reported with their code`() {
        val fail = SpeechRetryPolicy.decide(99, 0) as Decision.Fail
        assertTrue(fail.message.contains("code 99"))
    }
}
