package app.stash.capture

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Short beeps. Android 17 can silently drop background sound, so these are only ever played
 * from the visible capture screen. Start = one short beep, saved = a different (two-note ack)
 * tone, failure = a low error tone.
 */
class Cues(context: Context? = null) {
    private val vibrator: Vibrator? = try {
        context?.getSystemService(Vibrator::class.java)
    } catch (e: Exception) {
        null
    }

    private var gen: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 80)
    } catch (e: Exception) {
        null
    }

    // Each cue also buzzes briefly, for when the sound is dropped or the phone is on silent.
    private fun buzz(ms: Long) {
        try {
            vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            // A missing buzz is not worth failing a cue.
        }
    }

    fun start() { gen?.startTone(ToneGenerator.TONE_PROP_BEEP, 120); buzz(40) }
    fun saved() { gen?.startTone(ToneGenerator.TONE_PROP_ACK, 250); buzz(80) }
    fun failed() { gen?.startTone(ToneGenerator.TONE_PROP_NACK, 300); buzz(250) }

    fun release() {
        gen?.release()
        gen = null
    }
}
