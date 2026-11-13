package app.stash.domain

/*
 * Card 26b (outcome E2). The Ogg container, written and read in pure Kotlin, in memory.
 *
 * Source: RFC 3533 "The Ogg Encapsulation Format Version 0", section 6 (page header fields, sizes, flags, lacing,
 * least significant byte first) read on 2026-11-12 from https://www.rfc-editor.org/rfc/rfc3533.txt. The RFC names
 * the CRC polynomial (0x04c11db7) and says the CRC field is zero while it is computed, but not the initial value or
 * bit order; those are libogg's (initial value 0, not reflected, no final XOR), and were checked against 267 pages
 * of a file the phone's own MediaMuxer wrote in card 25 (docs/spike25/results/enc/opus12/s13.ogg): every CRC matched.
 */

object OggCrc {
    private val table = IntArray(256) { i ->
        var r = i shl 24
        repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
        r
    }

    fun of(b: ByteArray, from: Int = 0, to: Int = b.size): Int {
        var crc = 0
        for (i in from until to) crc = (crc shl 8) xor table[((crc ushr 24) xor (b[i].toInt() and 0xff)) and 0xff]
        return crc
    }
}

/** One page as read back. [lacing] are the segment table values; [body] is the packet data on this page. */
class OggPage(
    val flags: Int,
    val granule: Long,
    val serial: Int,
    val sequence: Int,
    val lacing: IntArray,
    val body: ByteArray,
    val size: Int,
) {
    val continued: Boolean get() = flags and Ogg.CONTINUED != 0
    val bos: Boolean get() = flags and Ogg.BOS != 0
    val eos: Boolean get() = flags and Ogg.EOS != 0
    /** Packets that end on this page. */
    val packetsEnding: Int get() = lacing.count { it < 255 }
}

object Ogg {
    const val CONTINUED = 0x01
    const val BOS = 0x02
    const val EOS = 0x04
    const val HEADER_BYTES = 27
    const val MAX_SEGMENTS = 255

    /** Every page in [b], in order. Throws [IllegalArgumentException] on a bad capture pattern, version, CRC or a cut-off page. */
    fun readPages(b: ByteArray): List<OggPage> {
        val pages = ArrayList<OggPage>()
        var p = 0
        while (p < b.size) {
            require(p + HEADER_BYTES <= b.size) { "cut-off page header at $p" }
            require(b[p] == 'O'.code.toByte() && b[p + 1] == 'g'.code.toByte() && b[p + 2] == 'g'.code.toByte() && b[p + 3] == 'S'.code.toByte()) { "no OggS at $p" }
            require(b[p + 4].toInt() == 0) { "unknown Ogg version" }
            val n = b[p + 26].toInt() and 0xff
            require(p + HEADER_BYTES + n <= b.size) { "cut-off segment table at $p" }
            val lacing = IntArray(n) { b[p + HEADER_BYTES + it].toInt() and 0xff }
            val bodyLen = lacing.sum()
            val size = HEADER_BYTES + n + bodyLen
            require(p + size <= b.size) { "cut-off page body at $p" }
            val stored = le32(b, p + 22)
            val copy = b.copyOfRange(p, p + size)
            for (i in 22 until 26) copy[i] = 0
            require(OggCrc.of(copy) == stored) { "CRC mismatch on page at $p" }
            pages += OggPage(
                flags = b[p + 5].toInt() and 0xff,
                granule = le64(b, p + 6),
                serial = le32(b, p + 14),
                sequence = le32(b, p + 18),
                lacing = lacing,
                body = b.copyOfRange(p + HEADER_BYTES + n, p + size),
                size = size,
            )
            p += size
        }
        return pages
    }

    /** The packets carried by [pages], joined across page boundaries. A packet left unfinished at the end is dropped. */
    fun packets(pages: List<OggPage>): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val cur = GrowingBytes(1024)
        for (pg in pages) {
            var off = 0
            for (l in pg.lacing) {
                cur.write(pg.body, off, l)
                off += l
                if (l < 255) { out += cur.toByteArray(); cur.reset() }
            }
        }
        return out
    }

    internal fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)

    internal fun le64(b: ByteArray, at: Int): Long = (le32(b, at).toLong() and 0xffffffffL) or (le32(b, at + 4).toLong() shl 32)
}

/**
 * Writes one logical Ogg stream into memory. A packet is laced as 255-byte segments and one shorter one (0 if its
 * length is a multiple of 255). A page never holds more than 255 segments; a packet that needs more is continued on
 * the next page, and a page on which no packet ends carries granule -1 (RFC 3533 section 6).
 *
 * The open page is only written out when the next packet arrives and it is full, or at [finish], so the last page
 * can always carry the end-of-stream flag.
 */
class OggStreamWriter(private val serial: Int) {
    private val out = GrowingBytes()
    private var sequence = 0
    private var flagsNext = Ogg.BOS

    // The open page.
    private val lacing = ArrayList<Int>()
    private val body = GrowingBytes()
    private var pageGranule = -1L
    private var pagePackets = 0
    private var pageContinued = false

    val pagesWritten: Int get() = sequence
    val openPackets: Int get() = pagePackets
    val openBodyBytes: Int get() = body.size

    /**
     * Adds [packet], whose end is at [granule]. If the open page already has packets and the packet's segments do not
     * fit, the page is written first, so a small packet is never split.
     */
    fun addPacket(packet: ByteArray, granule: Long) {
        val segs = packet.size / 255 + 1
        if (lacing.isNotEmpty() && lacing.size + segs > Ogg.MAX_SEGMENTS) flush()
        var off = 0
        var left = packet.size
        while (true) {
            if (lacing.size == Ogg.MAX_SEGMENTS) { flush(); pageContinued = true }
            val l = minOf(255, left)
            lacing += l
            body.write(packet, off, l)
            off += l; left -= l
            if (l < 255) break
        }
        pageGranule = granule
        pagePackets++
    }

    /** Writes the open page now (used to end a header page). Does nothing if the page is empty. */
    fun flush(eos: Boolean = false) {
        if (lacing.isEmpty() && !eos) return
        var flags = flagsNext
        if (pageContinued) flags = flags or Ogg.CONTINUED
        if (eos) flags = flags or Ogg.EOS
        val n = lacing.size
        val page = ByteArray(Ogg.HEADER_BYTES + n + body.size)
        page[0] = 'O'.code.toByte(); page[1] = 'g'.code.toByte(); page[2] = 'g'.code.toByte(); page[3] = 'S'.code.toByte()
        page[4] = 0
        page[5] = flags.toByte()
        val g = if (pagePackets == 0) -1L else pageGranule
        for (i in 0 until 8) page[6 + i] = (g ushr (8 * i)).toByte()
        putLe32(page, 14, serial)
        putLe32(page, 18, sequence)
        page[26] = n.toByte()
        for (i in 0 until n) page[Ogg.HEADER_BYTES + i] = lacing[i].toByte()
        body.copyInto(page, Ogg.HEADER_BYTES + n)
        putLe32(page, 22, OggCrc.of(page))
        out.write(page, 0, page.size)
        sequence++
        flagsNext = 0
        lacing.clear(); body.reset(); pagePackets = 0; pageGranule = -1L; pageContinued = false
    }

    /** Overrides the granule of the open page (the end trim of the last page). */
    fun setOpenGranule(granule: Long) { if (pagePackets > 0) pageGranule = granule }

    /** Writes the last page with the end-of-stream flag and returns the whole stream. */
    fun finish(): ByteArray {
        flush(eos = true)
        return out.toByteArray()
    }

    private fun putLe32(b: ByteArray, at: Int, v: Int) { for (i in 0 until 4) b[at + i] = (v ushr (8 * i)).toByte() }
}

/** A byte buffer that grows by doubling; no Android or IO classes. */
internal class GrowingBytes(initial: Int = 4096) {
    private var buf = ByteArray(initial)
    var size = 0
        private set

    fun write(src: ByteArray, off: Int, len: Int) {
        if (size + len > buf.size) {
            var cap = maxOf(buf.size, 16)
            while (cap < size + len) cap *= 2
            buf = buf.copyOf(cap)
        }
        System.arraycopy(src, off, buf, size, len)
        size += len
    }

    fun reset() { size = 0 }
    fun copyInto(dst: ByteArray, at: Int) = System.arraycopy(buf, 0, dst, at, size)
    fun toByteArray(): ByteArray = buf.copyOf(size)
}
