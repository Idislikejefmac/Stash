package app.stash.domain

/*
 * Card 26b (outcome E2). Opus packets in Ogg, written by the app itself. Pure Kotlin, in memory.
 *
 * Sources, read on 2026-11-12:
 *  - RFC 7845 "Ogg Encapsulation for the Opus Audio Codec" (https://www.rfc-editor.org/rfc/rfc7845.txt):
 *    section 3 (the ID header alone on the first page, with the beginning-of-stream flag; the comment header finishes
 *    its page; audio pages after), section 4 (header pages have granule 0; granule = 48 kHz samples decoded through the
 *    last packet that ends on the page; PCM position = granule - pre-skip; the last page's granule may trim the end),
 *    section 5.1 (OpusHead: "OpusHead", version 1, channel count, pre-skip u16 LE, input sample rate u32 LE,
 *    output gain s16 LE Q7.8, mapping family 0 with no table: 19 bytes) and 5.2 (OpusTags: "OpusTags", vendor
 *    length u32 LE, vendor UTF-8, comment count u32 LE, comments; no framing bit).
 *  - RFC 6716 "Definition of the Opus Audio Codec" section 3.1 (the TOC byte: config = top 5 bits, frame sizes per
 *    config, frame count code c) and 3.2.5 (code 3: frame count M in the low 6 bits of the second byte).
 */

/** The two Opus header packets (RFC 7845 section 5). */
object OpusHeaders {
    const val HEAD_BYTES = 19

    fun head(channels: Int, preSkip: Int, inputRate: Int, outputGain: Int = 0): ByteArray {
        require(channels in 1..2) { "mapping family 0 is mono or stereo" }
        require(preSkip in 0..0xffff)
        val b = ByteArray(HEAD_BYTES)
        "OpusHead".forEachIndexed { i, c -> b[i] = c.code.toByte() }
        b[8] = 1
        b[9] = channels.toByte()
        b[10] = preSkip.toByte(); b[11] = (preSkip ushr 8).toByte()
        for (i in 0 until 4) b[12 + i] = (inputRate ushr (8 * i)).toByte()
        b[16] = outputGain.toByte(); b[17] = (outputGain ushr 8).toByte()
        b[18] = 0
        return b
    }

    fun tags(vendor: String, comments: List<String> = emptyList()): ByteArray {
        val v = vendor.toByteArray(Charsets.UTF_8)
        val cs = comments.map { it.toByteArray(Charsets.UTF_8) }
        val b = ByteArray(8 + 4 + v.size + 4 + cs.sumOf { 4 + it.size })
        "OpusTags".forEachIndexed { i, c -> b[i] = c.code.toByte() }
        var p = 8
        fun le32(x: Int) { for (i in 0 until 4) b[p + i] = (x ushr (8 * i)).toByte(); p += 4 }
        le32(v.size); System.arraycopy(v, 0, b, p, v.size); p += v.size
        le32(cs.size)
        for (c in cs) { le32(c.size); System.arraycopy(c, 0, b, p, c.size); p += c.size }
        return b
    }

    class Head(val version: Int, val channels: Int, val preSkip: Int, val inputRate: Int, val outputGain: Int, val family: Int)

    /** Reads an OpusHead packet, or returns null if [b] is not one. */
    fun parseHead(b: ByteArray, at: Int = 0): Head? {
        if (b.size < at + HEAD_BYTES || String(b, at, 8, Charsets.US_ASCII) != "OpusHead") return null
        return Head(
            version = b[at + 8].toInt() and 0xff,
            channels = b[at + 9].toInt() and 0xff,
            preSkip = (b[at + 10].toInt() and 0xff) or ((b[at + 11].toInt() and 0xff) shl 8),
            inputRate = Ogg.le32(b, at + 12),
            outputGain = ((b[at + 16].toInt() and 0xff) or (b[at + 17].toInt() shl 8)).toShort().toInt(),
            family = b[at + 18].toInt() and 0xff,
        )
    }

    /** The vendor string of an OpusTags packet, or null. */
    fun parseVendor(b: ByteArray): String? {
        if (b.size < 12 || String(b, 0, 8, Charsets.US_ASCII) != "OpusTags") return null
        val n = Ogg.le32(b, 8)
        if (n < 0 || 12 + n > b.size) return null
        return String(b, 12, n, Charsets.UTF_8)
    }
}

/** How many 48 kHz samples an Opus packet decodes to, from its TOC byte (RFC 6716 section 3.1). */
object OpusToc {
    /** Frame length in 48 kHz samples for each config 0 to 31. */
    private val frame48k = IntArray(32) { config ->
        when {
            config < 12 -> intArrayOf(480, 960, 1920, 2880)[config % 4]   // SILK: 10, 20, 40, 60 ms
            config < 16 -> intArrayOf(480, 960)[config % 2]               // Hybrid: 10, 20 ms
            else -> intArrayOf(120, 240, 480, 960)[config % 4]            // CELT: 2.5, 5, 10, 20 ms
        }
    }

    fun samplesAt48k(packet: ByteArray): Int {
        if (packet.isEmpty()) return 0
        val toc = packet[0].toInt() and 0xff
        val frames = when (toc and 3) {
            0 -> 1
            1, 2 -> 2
            else -> if (packet.size < 2) 0 else packet[1].toInt() and 0x3f
        }
        return frame48k[toc ushr 3] * frames
    }
}

/**
 * What the platform's Opus encoder says about itself, read from its codec-specific data. Android may hand over a plain
 * OpusHead, an 8-byte codec delay in nanoseconds (csd-1), or both wrapped in "AOPUSHDR"/"AOPUSDLY" markers (each
 * marker followed by an 8-byte little-endian length, then the data; Android's OpusHeader.cpp). Only the delay is used:
 * card 25's files show this phone's encoder writing pre-skip 104 in its OpusHead, which is its 6.5 ms delay counted at
 * 16 kHz, not at the 48 kHz RFC 7845 requires (312).
 */
object OpusCodecConfig {
    /** libopus's look-ahead, 6.5 ms, at 48 kHz: the pre-skip used when the encoder says nothing usable. */
    const val DEFAULT_PRE_SKIP = 312

    /** The codec delay in nanoseconds, if [csd] carries one (an 8-byte value or an AOPUSDLY marker). */
    fun delayNs(csd: ByteArray): Long? {
        val m = indexOf(csd, "AOPUSDLY")
        if (m >= 0 && csd.size >= m + 8 + 8 + 8) return Ogg.le64(csd, m + 16)
        if (csd.size == 8) return Ogg.le64(csd, 0)
        return null
    }

    /** The pre-skip field of an OpusHead inside [csd], whatever wraps it; reported, not used. */
    fun headPreSkip(csd: ByteArray): Int? {
        val at = indexOf(csd, "OpusHead")
        return if (at < 0) null else OpusHeaders.parseHead(csd, at)?.preSkip
    }

    /** Pre-skip at 48 kHz from a delay in nanoseconds; null if implausible (none, or over 100 ms). */
    fun preSkipFromDelay(ns: Long?): Int? {
        if (ns == null || ns <= 0 || ns > 100_000_000L) return null
        return ((ns * 48_000L + 500_000_000L) / 1_000_000_000L).toInt()
    }

    private fun indexOf(b: ByteArray, s: String): Int {
        val t = s.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..b.size - t.size) {
            for (j in t.indices) if (b[i + j] != t[j]) continue@outer
            return i
        }
        return -1
    }
}

/**
 * Ogg Opus in memory, as RFC 7845 lays it out: page 0 the OpusHead alone (beginning of stream, granule 0); page 1
 * the OpusTags, ending its page (granule 0); then audio pages of up to [packetsPerPage] packets (50 = one second of
 * 20 ms packets, about 77 bytes of page overhead a second). Each audio page's granule is the 48 kHz sample count
 * through its last packet; the last page has the end-of-stream flag and its granule trims the encoder's padding, so
 * the decoded length equals the input length.
 */
class OggOpusWriter(
    serial: Int,
    val preSkip: Int,
    private val inputRate: Int = AudioFormatConstants.SAMPLE_RATE,
    vendor: String = VENDOR,
    private val packetsPerPage: Int = 50,
    private val maxBodyBytes: Int = 8_000,
) {
    private val stream = OggStreamWriter(serial)
    private var granule = 0L
    var packets = 0
        private set

    init {
        stream.addPacket(OpusHeaders.head(1, preSkip, inputRate), 0L)
        stream.flush()
        stream.addPacket(OpusHeaders.tags(vendor), 0L)
        stream.flush()
    }

    fun addPacket(packet: ByteArray) {
        if (stream.openPackets >= packetsPerPage || stream.openBodyBytes >= maxBodyBytes) stream.flush()
        granule += OpusToc.samplesAt48k(packet)
        stream.addPacket(packet, granule)
        packets++
    }

    /**
     * Ends the stream. [inputSamples] is how many samples (at the input rate) were fed to the encoder; the last
     * granule becomes pre-skip plus that length at 48 kHz, if the packets cover it.
     */
    fun finish(inputSamples: Long?): ByteArray {
        if (inputSamples != null) {
            val end = preSkip + inputSamples * 48_000L / inputRate
            if (end in 1 until granule) stream.setOpenGranule(end)
        }
        return stream.finish()
    }

    companion object {
        /** Written as the OpusTags vendor string, so the reader knows the last granule can be trusted for the length. */
        const val VENDOR = "Stash"
    }
}

/** What an Ogg Opus file says about itself, read with [Ogg.readPages]. */
class OggOpusInfo(val head: OpusHeaders.Head, val vendor: String?, val lastGranule: Long, val audioPackets: Int, val pages: Int) {
    /** Playable samples at 48 kHz (RFC 7845 section 4: granule minus pre-skip). */
    val playable48k: Long get() = (lastGranule - head.preSkip).coerceAtLeast(0)

    companion object {
        /** Null if [b] is not a well-formed Ogg Opus stream (bad CRC, no OpusHead, no OpusTags). */
        fun read(b: ByteArray): OggOpusInfo? = try {
            val pages = Ogg.readPages(b)
            val packets = Ogg.packets(pages)
            val head = packets.getOrNull(0)?.let { OpusHeaders.parseHead(it) }
            val vendor = packets.getOrNull(1)?.let { OpusHeaders.parseVendor(it) }
            if (head == null || packets.size < 2 || vendor == null) null
            else OggOpusInfo(head, vendor, pages.lastOrNull { it.granule >= 0 }?.granule ?: 0L, packets.size - 2, pages.size)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
