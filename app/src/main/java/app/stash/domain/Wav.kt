package app.stash.domain

/*
 * Card 26b. The WAV fallback: a 44-byte RIFF header and the samples (16-bit little-endian PCM, 16 kHz mono).
 * No codec is involved, so it cannot fail. Pure Kotlin; works on byte arrays only, never on files.
 */
object Wav {
    const val HEADER_BYTES = 44

    fun encode(pcm: Pcm): ByteArray = encode(pcm.samples, pcm.samples.size)

    fun encode(samples: ShortArray, count: Int = samples.size, rate: Int = AudioFormatConstants.SAMPLE_RATE): ByteArray {
        val dataBytes = count * 2
        val out = ByteArray(HEADER_BYTES + dataBytes)
        fun ascii(at: Int, s: String) { for (i in s.indices) out[at + i] = s[i].code.toByte() }
        fun le32(at: Int, v: Int) { for (i in 0 until 4) out[at + i] = (v ushr (8 * i)).toByte() }
        fun le16(at: Int, v: Int) { out[at] = v.toByte(); out[at + 1] = (v ushr 8).toByte() }
        ascii(0, "RIFF"); le32(4, 36 + dataBytes); ascii(8, "WAVE")
        ascii(12, "fmt "); le32(16, 16); le16(20, 1); le16(22, 1); le32(24, rate); le32(28, rate * 2); le16(32, 2); le16(34, 16)
        ascii(36, "data"); le32(40, dataBytes)
        var o = HEADER_BYTES
        for (i in 0 until count) { val s = samples[i].toInt(); out[o++] = s.toByte(); out[o++] = (s shr 8).toByte() }
        return out
    }

    class Header(val channels: Int, val rate: Int, val bits: Int, val format: Int, val dataOffset: Int, val dataBytes: Int)

    /** Reads the header of a RIFF/WAVE file; other chunks are skipped. Throws [IllegalArgumentException] if it is not one. */
    fun header(b: ByteArray): Header {
        require(b.size >= 12 && ascii(b, 0, 4) == "RIFF" && ascii(b, 8, 4) == "WAVE") { "not a WAV" }
        var p = 12
        var fmt: IntArray? = null
        while (p + 8 <= b.size) {
            val id = ascii(b, p, 4)
            val size = le32(b, p + 4)
            val body = p + 8
            if (id == "fmt ") {
                require(size >= 16 && body + 16 <= b.size) { "short fmt chunk" }
                fmt = intArrayOf(le16(b, body), le16(b, body + 2), le32(b, body + 4), le16(b, body + 14))
            } else if (id == "data") {
                val f = fmt ?: throw IllegalArgumentException("data before fmt")
                val avail = (b.size - body).coerceAtLeast(0)
                val n = if (size < 0 || size > avail) avail else size // a cut-off file keeps what is there
                return Header(channels = f[1], rate = f[2], bits = f[3], format = f[0], dataOffset = body, dataBytes = n)
            }
            if (size < 0) break
            p = body + size + (size and 1) // chunks are padded to an even length
        }
        throw IllegalArgumentException("no data chunk")
    }

    /** 16-bit PCM WAV, any channel count, 16 kHz or a whole multiple of it, to 16 kHz mono. */
    fun decode(b: ByteArray): Pcm {
        val h = header(b)
        require(h.format == 1 && h.bits == 16) { "only 16-bit PCM" }
        require(h.channels >= 1) { "no channels" }
        val n = h.dataBytes / 2
        val inter = ShortArray(n) { i -> ((b[h.dataOffset + 2 * i].toInt() and 0xff) or (b[h.dataOffset + 2 * i + 1].toInt() shl 8)).toShort() }
        return Pcm(Resampler.to16k(Resampler.downmix(inter, n, h.channels), h.rate))
    }

    private fun ascii(b: ByteArray, at: Int, n: Int) = String(CharArray(n) { (b[at + it].toInt() and 0xff).toChar() })
    private fun le16(b: ByteArray, at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)
    private fun le32(b: ByteArray, at: Int) = le16(b, at) or (le16(b, at + 2) shl 16)
}
