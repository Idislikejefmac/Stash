package app.stash.domain

/** Card 16b: the chunk names of a WebP file, to prove nothing (EXIF, XMP, ICC profile) is embedded. Pure. */
object WebpChunks {
    /** The chunk names in order (`VP8 `, `VP8L`, `VP8X`, `EXIF`, `XMP `, `ICCP`, ...), or null when [bytes] is not a WebP file. */
    fun list(bytes: ByteArray): List<String>? {
        if (bytes.size < 12) return null
        if (ascii(bytes, 0) != "RIFF" || ascii(bytes, 8) != "WEBP") return null
        val names = ArrayList<String>()
        var pos = 12
        while (pos + 8 <= bytes.size) {
            names.add(ascii(bytes, pos))
            val size = le32(bytes, pos + 4)
            if (size < 0) break
            val next = pos.toLong() + 8 + size + (size and 1)
            if (next > bytes.size) break
            pos = next.toInt()
        }
        return names
    }

    /**
     * Chunks that carry pixels or what is needed to show them. `ICCP` is the colour profile the platform writes for the
     * bitmap's colour space (measured: Skia adds it, a few hundred bytes); it holds no camera or position data and
     * wide-colour pictures need it, so it is allowed. `EXIF` and `XMP ` are the embedded data and are not.
     */
    private val IMAGE_CHUNKS = setOf("VP8 ", "VP8L", "VP8X", "ALPH", "ANIM", "ANMF", "ICCP")

    /** True when [bytes] is a WebP file whose every chunk is an image chunk. */
    fun onlyImageData(bytes: ByteArray): Boolean = list(bytes)?.all { it in IMAGE_CHUNKS } == true

    private fun ascii(b: ByteArray, at: Int) = String(b, at, 4, Charsets.ISO_8859_1)

    private fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)
}
