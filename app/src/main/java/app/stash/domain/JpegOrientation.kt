package app.stash.domain

/**
 * Applies the camera's rotation exactly once (docs/cards/16e item 6). The camera hands over JPEG bytes and a
 * rotation (`rotationDegrees`); the JPEG may or may not carry its own orientation tag as well. If both were used the
 * picture would be turned twice. So the tag inside the bytes is made equal to the camera's rotation (any other tag is
 * dropped), and the picture decoder, which turns a picture by its tag, does the one rotation. Pure Kotlin.
 */
object JpegOrientation {
    private const val E = 'E'.code
    private const val X = 'x'.code
    private const val I = 'i'.code
    private const val F = 'f'.code

    /** EXIF orientation value for a clockwise rotation of [degrees] (0, 90, 180, 270; anything else is read as the nearest). */
    fun tagFor(degrees: Int): Int = when (((degrees % 360) + 360) % 360) {
        in 45..134 -> 6
        in 135..224 -> 3
        in 225..314 -> 8
        else -> 1
    }

    /** The bytes with one Exif segment holding only the orientation for [degrees]. Anything that is not a JPEG is returned unchanged. */
    fun withOrientation(jpeg: ByteArray, degrees: Int): ByteArray {
        if (!isJpeg(jpeg)) return jpeg
        val out = java.io.ByteArrayOutputStream(jpeg.size + 40)
        out.write(0xFF)
        out.write(0xD8)
        out.write(exifSegment(tagFor(degrees)))
        var i = 2
        while (i + 1 < jpeg.size) {
            if (jpeg[i] != 0xFF.toByte()) break
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker == 0xFF) { i++; continue } // fill byte
            if (marker == 0xDA || marker == 0xD9 || marker in 0xD0..0xD7 || marker == 0x01) break // scan data follows
            if (i + 3 >= jpeg.size) break
            val len = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            if (len < 2 || i + 2 + len > jpeg.size) break
            if (!(marker == 0xE1 && isExif(jpeg, i + 4, len - 2))) out.write(jpeg, i, 2 + len)
            i += 2 + len
        }
        out.write(jpeg, i, jpeg.size - i)
        return out.toByteArray()
    }

    /** The orientation tag (1..8) of the first Exif segment, or null if there is none. */
    fun read(jpeg: ByteArray): Int? {
        if (!isJpeg(jpeg)) return null
        var i = 2
        while (i + 3 < jpeg.size) {
            if (jpeg[i] != 0xFF.toByte()) return null
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker == 0xFF) { i++; continue }
            if (marker == 0xDA || marker == 0xD9) return null
            val len = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            if (len < 2 || i + 2 + len > jpeg.size) return null
            if (marker == 0xE1 && isExif(jpeg, i + 4, len - 2)) return orientationIn(jpeg, i + 10, len - 8)
            i += 2 + len
        }
        return null
    }

    private fun isJpeg(b: ByteArray) = b.size >= 4 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

    private fun isExif(b: ByteArray, start: Int, size: Int): Boolean =
        size >= 6 && b[start].toInt() == E && b[start + 1].toInt() == X && b[start + 2].toInt() == I &&
            b[start + 3].toInt() == F && b[start + 4].toInt() == 0 && b[start + 5].toInt() == 0

    /** Reads tag 0x0112 from the TIFF block at [t] ([size] bytes long). */
    private fun orientationIn(b: ByteArray, t: Int, size: Int): Int? {
        if (size < 8) return null
        val big = when {
            b[t].toInt() == 'M'.code && b[t + 1].toInt() == 'M'.code -> true
            b[t].toInt() == 'I'.code && b[t + 1].toInt() == 'I'.code -> false
            else -> return null
        }
        fun u16(o: Int): Int? = if (o < 0 || o + 2 > size) null else {
            val a = b[t + o].toInt() and 0xFF
            val c = b[t + o + 1].toInt() and 0xFF
            if (big) (a shl 8) or c else (c shl 8) or a
        }
        fun u32(o: Int): Int? {
            val first = u16(o) ?: return null
            val second = u16(o + 2) ?: return null
            return if (big) (first shl 16) or second else (second shl 16) or first
        }
        val ifd = u32(4) ?: return null
        val n = u16(ifd) ?: return null
        for (k in 0 until n) {
            val e = ifd + 2 + 12 * k
            if (u16(e) == 0x0112) return u16(e + 8)?.takeIf { it in 1..8 }
        }
        return null
    }

    /** FF E1, length, "Exif\0\0", a big-endian TIFF block with one directory holding one entry: Orientation. */
    private fun exifSegment(orientation: Int): ByteArray {
        val tiff = byteArrayOf(
            'M'.code.toByte(), 'M'.code.toByte(), 0, 0x2A, 0, 0, 0, 8, // header: big-endian, first directory at 8
            0, 1, // one entry
            0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0, // tag 0x0112, SHORT, count 1, value
            0, 0, 0, 0, // no next directory
        )
        val body = byteArrayOf(E.toByte(), X.toByte(), I.toByte(), F.toByte(), 0, 0) + tiff
        val len = body.size + 2
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + body
    }
}
