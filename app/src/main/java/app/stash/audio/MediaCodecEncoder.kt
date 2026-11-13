package app.stash.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.system.Os
import android.system.OsConstants
import app.stash.domain.AudioFormat
import app.stash.domain.AudioFormatConstants
import app.stash.domain.EncodedAudio
import app.stash.domain.OggOpusWriter
import app.stash.domain.OpusCodecConfig
import app.stash.domain.OpusContainer
import java.nio.ByteBuffer
import kotlin.random.Random

/** The phone's encoders (card 26b). [opusContainer] picks how Opus is put into Ogg: E2, the app's own writer, by default. */
class PlatformEncoders(private val opusContainer: OpusContainer = OpusContainer.DEFAULT) : EncoderFactory {
    override fun available(format: AudioFormat): Boolean {
        val mime = format.codecMime ?: return false
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
    }

    override fun create(format: AudioFormat): AudioEncoder = MediaCodecEncoder(format, opusContainer)
}

/**
 * One platform codec at 16 kHz mono, as card 25's spike ran it: input in 20 ms chunks, output collected as it comes.
 * Opus goes to Ogg through [OggOpusWriter] (E2) or MediaMuxer (E1); AAC goes to MPEG-4 through MediaMuxer. MediaMuxer
 * writes into an anonymous in-memory file (memfd, card 26b item 3); nothing is written to storage.
 */
class MediaCodecEncoder(private val format: AudioFormat, private val opusContainer: OpusContainer) : AudioEncoder {
    private var codec: MediaCodec? = null
    private var sink: PacketSink? = null
    private val info = MediaCodec.BufferInfo()
    private var samplesIn = 0L
    private var bytes = ByteArray(AudioFormatConstants.CHUNK_SAMPLES * 2)

    /** For the debug screen only: codec name, container, and what the codec said about its delay. */
    var diagnostics: String = ""
        private set

    override fun start() {
        val mime = requireNotNull(format.codecMime) { "WAV has no codec" }
        val mf = MediaFormat.createAudioFormat(mime, AudioFormatConstants.SAMPLE_RATE, 1).apply {
            if (format == AudioFormat.AAC_32) setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, format.bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val c = MediaCodec.createEncoderByType(mime)
        codec = c
        try {
            c.configure(mf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            sink = when {
                format.isOpus && opusContainer == OpusContainer.OWN_WRITER -> OwnOggSink()
                format.isOpus -> MuxerSink(MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
                else -> MuxerSink(MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }
            c.start()
            diagnostics = "codec=${c.name} container=${sink?.name}"
        } catch (t: Throwable) {
            abort()
            throw t
        }
    }

    override fun feed(chunk: ShortArray, len: Int) {
        val c = checkNotNull(codec) { "not started" }
        val n = len * 2
        if (bytes.size < n) bytes = ByteArray(n)
        for (i in 0 until len) { val s = chunk[i].toInt(); bytes[2 * i] = s.toByte(); bytes[2 * i + 1] = (s shr 8).toByte() }
        var off = 0
        while (off < n) {
            val idx = c.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val buf = c.getInputBuffer(idx)!!
                buf.clear()
                val take = minOf(buf.remaining(), n - off)
                buf.put(bytes, off, take)
                val pts = (samplesIn + off / 2) * 1_000_000L / AudioFormatConstants.SAMPLE_RATE
                c.queueInputBuffer(idx, 0, take, pts, 0)
                off += take
            }
            drain(c, untilEnd = false)
        }
        samplesIn += len
    }

    override fun finish(): EncodedAudio {
        val c = checkNotNull(codec) { "not started" }
        var queued = false
        while (!queued) {
            val idx = c.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val pts = samplesIn * 1_000_000L / AudioFormatConstants.SAMPLE_RATE
                c.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                queued = true
            } else {
                drain(c, untilEnd = false)
            }
        }
        drain(c, untilEnd = true)
        val s = checkNotNull(sink)
        val out = s.finish(samplesIn)
        diagnostics += " " + s.details()
        release()
        return EncodedAudio(out, format.mime, AudioFormatConstants.samplesToMs(samplesIn), format)
    }

    /** Takes what the codec has ready. With [untilEnd] it waits for the end-of-stream buffer (the caller bounds the time). */
    private fun drain(c: MediaCodec, untilEnd: Boolean) {
        val s = checkNotNull(sink)
        var idle = 0
        while (true) {
            val o = c.dequeueOutputBuffer(info, if (untilEnd) 10_000 else 0)
            if (o != MediaCodec.INFO_TRY_AGAIN_LATER) idle = 0
            when {
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!untilEnd) return
                    if (++idle > 500) throw IllegalStateException("no end of stream after 5 s")
                }
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> s.onFormat(c.outputFormat)
                o >= 0 -> {
                    val data = c.getOutputBuffer(o)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        if (info.size > 0) s.onConfig(copy(data, info))
                    } else if (info.size > 0) {
                        s.write(data, info)
                    }
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    override fun abort() {
        try { sink?.abort() } catch (_: Throwable) {}
        sink = null
        release()
    }

    private fun release() {
        val c = codec ?: return
        codec = null
        try { c.stop() } catch (_: Throwable) {}
        try { c.release() } catch (_: Throwable) {}
    }
}

private fun copy(data: ByteBuffer, info: MediaCodec.BufferInfo): ByteArray {
    val b = ByteArray(info.size)
    val d = data.duplicate()
    d.position(info.offset); d.limit(info.offset + info.size)
    d.get(b)
    return b
}

/** Where the encoder's packets go. */
private interface PacketSink {
    val name: String
    fun onFormat(f: MediaFormat)
    fun onConfig(csd: ByteArray)
    fun write(data: ByteBuffer, info: MediaCodec.BufferInfo)
    fun finish(inputSamples: Long): ByteArray
    fun abort()
    fun details(): String
}

/** E2: the app's own Ogg page writer. Pre-skip comes from the codec's stated delay (see [OpusCodecConfig]). */
private class OwnOggSink : PacketSink {
    override val name = "own-ogg"
    private var delayNs: Long? = null
    private var headPreSkip: Int? = null
    private var writer: OggOpusWriter? = null

    private fun learn(csd: ByteArray) {
        OpusCodecConfig.delayNs(csd)?.let { if (delayNs == null) delayNs = it }
        OpusCodecConfig.headPreSkip(csd)?.let { if (headPreSkip == null) headPreSkip = it }
    }

    override fun onFormat(f: MediaFormat) {
        for (k in listOf("csd-0", "csd-1", "csd-2")) {
            val b = f.getByteBuffer(k) ?: continue
            val a = ByteArray(b.remaining()); b.duplicate().get(a)
            // csd-1 of an Opus track is the codec delay in nanoseconds (8 bytes); csd-2 the seek pre-roll, also 8 bytes.
            if (k == "csd-2") continue
            learn(a)
        }
    }

    override fun onConfig(csd: ByteArray) = learn(csd)

    override fun write(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        val w = writer ?: OggOpusWriter(
            serial = Random.nextInt(),
            preSkip = OpusCodecConfig.preSkipFromDelay(delayNs) ?: OpusCodecConfig.DEFAULT_PRE_SKIP,
        ).also { writer = it }
        w.addPacket(copy(data, info))
    }

    override fun finish(inputSamples: Long): ByteArray {
        val w = writer ?: OggOpusWriter(Random.nextInt(), OpusCodecConfig.preSkipFromDelay(delayNs) ?: OpusCodecConfig.DEFAULT_PRE_SKIP)
        return w.finish(inputSamples)
    }

    override fun abort() { writer = null }
    override fun details() = "delayNs=$delayNs opusHeadPreSkip=$headPreSkip preSkipUsed=${writer?.preSkip} packets=${writer?.packets}"
}

/** E1 (and AAC): MediaMuxer into an anonymous in-memory file, read back at the end and closed. */
private class MuxerSink(private val outputFormat: Int) : PacketSink {
    override val name = if (outputFormat == MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG) "muxer-ogg-memfd" else "muxer-mp4-memfd"
    private val fd = Os.memfd_create("stash-rec", OsConstants.MFD_CLOEXEC)
    private val muxer: MediaMuxer = try { MediaMuxer(fd, outputFormat) } catch (t: Throwable) { Os.close(fd); throw t }
    private var track = -1
    private var started = false
    private var closed = false
    private var size = 0L

    override fun onFormat(f: MediaFormat) {
        if (started) return
        track = muxer.addTrack(f)
        muxer.start()
        started = true
    }

    override fun onConfig(csd: ByteArray) {} // the muxer takes codec data from the format, never as a sample

    override fun write(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        check(started) { "muxer not started" }
        muxer.writeSampleData(track, data, info)
    }

    override fun finish(inputSamples: Long): ByteArray {
        check(started) { "no output format from the codec" }
        muxer.stop()
        muxer.release()
        size = Os.fstat(fd).st_size
        require(size in 1..Int.MAX_VALUE.toLong()) { "muxer wrote $size bytes" }
        val out = ByteArray(size.toInt())
        var off = 0
        while (off < out.size) {
            val n = Os.pread(fd, out, off, out.size - off, off.toLong())
            if (n <= 0) break
            off += n
        }
        close()
        require(off == out.size) { "short read $off of ${out.size}" }
        return out
    }

    override fun abort() {
        try { if (started) muxer.stop() } catch (_: Throwable) {}
        try { muxer.release() } catch (_: Throwable) {}
        close()
    }

    private fun close() { if (!closed) { closed = true; try { Os.close(fd) } catch (_: Throwable) {} } }

    override fun details() = "memfdBytes=$size"
}
