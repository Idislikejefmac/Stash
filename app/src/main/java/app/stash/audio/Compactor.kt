package app.stash.audio

import app.stash.domain.AudioFormat
import app.stash.domain.AudioFormatConstants
import app.stash.domain.AudioFormats
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.EncodedAudio
import app.stash.domain.Wav

/**
 * Card 26b item 5. Re-encodes a stored WAV fallback to the small format, not streaming (slow is fine). Card 26c
 * calls it later to replace the fallback file. Call off the main thread. Returns null if the encoder still fails,
 * or if [wav] is not a WAV this app can read; the WAV is then simply kept.
 */
class Compactor(private val factory: EncoderFactory) {
    fun compact(wav: ByteArray, format: AudioFormat = AudioFormats.DEFAULT_FORMAT): EncodedAudio? {
        if (format == AudioFormat.WAV || !factory.available(format)) return null
        val pcm = try { Wav.decode(wav) } catch (e: IllegalArgumentException) { return null }
        val enc = try { factory.create(format) } catch (t: Throwable) { return null }
        return try {
            enc.start()
            val s = pcm.samples
            var p = 0
            while (p < s.size) {
                val n = minOf(AudioFormatConstants.CHUNK_SAMPLES, s.size - p)
                enc.feed(s.copyOfRange(p, p + n), n)
                p += n
            }
            val out = enc.finish()
            Dbg.event(DbgComponent.AUDIO, DbgCode.ENCODED, out.bytes.size, out.format.ordinal)
            out
        } catch (t: Throwable) {
            enc.abort()
            Dbg.event(DbgComponent.AUDIO, DbgCode.FAILED, 200 + format.ordinal)
            null
        }
    }
}
