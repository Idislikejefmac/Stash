package app.stash.debug

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteOrder

/**
 * Card 25 spike, leg C (throwaway, DEBUG ONLY). Encodes each `files/spike25/sNN.wav` with MediaCodec
 * (AAC-LC in MP4, Opus in Ogg), decodes it back to 16 kHz mono WAV under `files/spike25/dec/<setting>/`,
 * and writes `files/spike25/enc/report.tsv` (bytes, seconds, bytes per minute, errors) and
 * `files/spike25/enc/codecs.txt` (the phone's audio encoders).
 */
class Spike25Codec(private val root: File, private val say: (String) -> Unit) {

    data class Setting(val name: String, val mime: String, val bitrate: Int, val ogg: Boolean)

    val settings = listOf(
        Setting("aac32", MediaFormat.MIMETYPE_AUDIO_AAC, 32_000, false),
        Setting("aac16", MediaFormat.MIMETYPE_AUDIO_AAC, 16_000, false),
        Setting("opus24", MediaFormat.MIMETYPE_AUDIO_OPUS, 24_000, true),
        Setting("opus16", MediaFormat.MIMETYPE_AUDIO_OPUS, 16_000, true),
        Setting("opus12", MediaFormat.MIMETYPE_AUDIO_OPUS, 12_000, true),
    )

    fun run() {
        val encRoot = File(root, "enc").also { it.mkdirs() }
        File(encRoot, "codecs.txt").writeText(listEncoders())
        val report = mutableListOf("setting\tid\tencBytes\taudioSec\tbytesPerMin\tdecodedRate\tdecodedSec\tencMs\terror")
        val wavs = root.listFiles { f -> f.name.matches(Regex("s\\d\\d\\.wav")) }!!.sortedBy { it.name }
        for (s in settings) {
            val encDir = File(encRoot, s.name).also { it.mkdirs() }
            val decDir = File(root, "dec/${s.name}").also { it.mkdirs() }
            for (w in wavs) {
                val id = w.name.removeSuffix(".wav")
                val pcm = Spike25Runner.readWav(w)
                val secs = pcm.size / 16000.0
                val outF = File(encDir, "$id.${if (s.ogg) "ogg" else "m4a"}")
                try {
                    val t = System.nanoTime()
                    encode(pcm, s, outF)
                    val encMs = (System.nanoTime() - t) / 1_000_000
                    val (rate, dec) = decode(outF)
                    Spike25Runner.writeWav16k(File(decDir, "$id.wav"), dec)
                    report += "${s.name}\t$id\t${outF.length()}\t${"%.2f".format(secs)}\t${"%.0f".format(outF.length() * 60 / secs)}" +
                        "\t$rate\t${"%.2f".format(dec.size / 16000.0)}\t$encMs\t"
                } catch (e: Throwable) {
                    report += "${s.name}\t$id\t-\t${"%.2f".format(secs)}\t-\t-\t-\t-\t${e.javaClass.simpleName}: ${e.message}"
                }
            }
            say("${s.name} done")
        }
        File(encRoot, "report.tsv").writeText(report.joinToString("\n") + "\n")
        File(encRoot, "done").writeText("ok\n")
    }

    private fun listEncoders(): String = buildString {
        for (info in MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos) {
            if (!info.isEncoder) continue
            for (type in info.supportedTypes) {
                if (!type.startsWith("audio/")) continue
                val caps = info.getCapabilitiesForType(type).audioCapabilities
                append("${info.name}\t$type\trates=${caps?.supportedSampleRates?.joinToString(",") ?: "?"}")
                append("\tsampleRateRanges=${caps?.supportedSampleRateRanges?.joinToString(",") ?: "?"}")
                append("\tbitrates=${caps?.bitrateRange}\tmaxCh=${caps?.maxInputChannelCount}\thw=${info.isHardwareAccelerated}\n")
            }
        }
    }

    private fun encode(pcm: FloatArray, s: Setting, out: File) {
        val format = MediaFormat.createAudioFormat(s.mime, 16000, 1).apply {
            if (s.mime == MediaFormat.MIMETYPE_AUDIO_AAC) setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, s.bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val bytes = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val v = (pcm[i] * 32768f).toInt().coerceIn(-32768, 32767)
            bytes[2 * i] = (v and 0xff).toByte(); bytes[2 * i + 1] = (v shr 8).toByte()
        }
        val codec = MediaCodec.createEncoderByType(s.mime)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        out.delete()
        val muxer = MediaMuxer(out.absolutePath,
            if (s.ogg) MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1; var started = false; var inputDone = false; var off = 0; var ptsUs = 0L
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = minOf(buf.capacity(), 640 * 4, bytes.size - off)
                        if (n <= 0) {
                            codec.queueInputBuffer(i, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            buf.clear(); buf.put(bytes, off, n); off += n
                            codec.queueInputBuffer(i, 0, n, ptsUs, 0)
                            ptsUs += n / 2L * 1_000_000L / 16000
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true }
                    o >= 0 -> {
                        val data = codec.getOutputBuffer(o)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && started) muxer.writeSampleData(track, data, info)
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            codec.stop(); codec.release()
            if (started) muxer.stop()
            muxer.release()
        }
    }

    /** Decodes back to 16 kHz mono; returns the decoder's own output rate and the samples. */
    private fun decode(f: File): Pair<Int, ShortArray> {
        val ex = MediaExtractor(); ex.setDataSource(f.absolutePath)
        val fmt = ex.getTrackFormat(0); ex.selectTrack(0)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val outS = ArrayList<Short>()
        val info = MediaCodec.BufferInfo(); var inDone = false
        try {
            while (true) {
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    val sb = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    sb.position(info.offset / 2); sb.limit((info.offset + info.size) / 2)
                    while (sb.hasRemaining()) outS.add(sb.get())
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally { codec.stop(); codec.release(); ex.release() }
        val frames = outS.size / ch
        val mono = FloatArray(frames) { k -> var s = 0f; for (c in 0 until ch) s += outS[k * ch + c] / 32768f; s / ch }
        val r = Spike25Runner.resampleTo16k(mono, rate)
        return rate to ShortArray(r.size) { (r[it] * 32768f).toInt().coerceIn(-32768, 32767).toShort() }
    }
}
