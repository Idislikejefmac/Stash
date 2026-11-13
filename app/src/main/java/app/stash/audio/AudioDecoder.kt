package app.stash.audio

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import app.stash.domain.AudioFormat
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.OggOpusInfo
import app.stash.domain.OggOpusWriter
import app.stash.domain.Pcm
import app.stash.domain.Resampler
import app.stash.domain.Wav
import java.nio.ByteOrder

/** A stored recording back to 16 kHz mono samples, behind a seam so later cards test with a fake. */
interface Decoder {
    /** Off the main thread. Throws if the bytes cannot be read. */
    fun decode(bytes: ByteArray, mime: String): Pcm
}

/**
 * Card 26b item 6. WAV is read directly. Anything else goes through MediaExtractor over an in-memory
 * [MediaDataSource] (never a file path) and the platform decoder, then [Resampler] (Opus decodes at 48 kHz).
 */
class AudioDecoder : Decoder {
    override fun decode(bytes: ByteArray, mime: String): Pcm {
        if (mime == AudioFormat.WAV.mime) return Wav.decode(bytes)
        return try {
            val pcm = decodeWithCodec(bytes)
            trimOwnOgg(bytes, mime, pcm)
        } catch (t: Throwable) {
            Dbg.event(DbgComponent.AUDIO, DbgCode.FAILED, 100 + (app.stash.domain.AudioFormats.byMime(mime)?.ordinal ?: 9))
            throw t
        }
    }

    /**
     * An Ogg file this app wrote says its exact length in its last granule; cut any codec padding past it. Never adds
     * sound and never cuts other files.
     */
    private fun trimOwnOgg(bytes: ByteArray, mime: String, pcm: Pcm): Pcm {
        if (mime != AudioFormat.OPUS_12.mime) return pcm
        val info = OggOpusInfo.read(bytes) ?: return pcm
        if (info.vendor != OggOpusWriter.VENDOR) return pcm
        val want = (info.playable48k / 3).toInt()
        return if (want in 1 until pcm.samples.size) Pcm(pcm.samples.copyOf(want)) else pcm
    }

    private fun decodeWithCodec(bytes: ByteArray): Pcm {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(BytesSource(bytes))
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw IllegalArgumentException("no audio track")
            val fmt = ex.getTrackFormat(track)
            ex.selectTrack(track)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var out = ShortArray(1 shl 16)
            var count = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idle = 0
            while (true) {
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(c.getInputBuffer(i)!!, 0)
                        if (n < 0) { c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { c.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (inputDone && ++idle > 500) throw IllegalStateException("decoder gave no end of stream")
                    continue
                }
                idle = 0
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = c.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = c.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    val sb = c.getOutputBuffer(o)!!.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                    sb.position(info.offset); sb.limit(info.offset + info.size)
                    val shorts = sb.asShortBuffer()
                    val n = shorts.remaining()
                    if (count + n > out.size) out = out.copyOf(maxOf(out.size * 2, count + n))
                    shorts.get(out, count, n)
                    count += n
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            return Pcm(Resampler.to16k(Resampler.downmix(out, count, channels), rate))
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            ex.release()
        }
    }
}

/** The recording's bytes as a seekable source for MediaExtractor; nothing touches storage. */
private class BytesSource(private val b: ByteArray) : MediaDataSource() {
    override fun getSize(): Long = b.size.toLong()
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= b.size) return -1
        val n = minOf(size.toLong(), b.size - position).toInt()
        System.arraycopy(b, position.toInt(), buffer, offset, n)
        return n
    }
    override fun close() {}
}
