package app.stash

import app.stash.domain.PictureKind
import app.stash.domain.PictureSize
import app.stash.domain.PictureSizing
import app.stash.domain.TextStats
import app.stash.domain.WebpChunks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Card 16b: the sizing rules and the WebP chunk lister, pure logic. */
class PictureSizingTest {
    private val tenPxLines = { lines: Int -> TextStats(lines, 10f) }

    @Test
    fun `the size table for every kind and setting`() {
        val t = PictureSizing::targetLongEdge
        assertEquals(1024, t(PictureKind.PHOTO, PictureSize.SMALL, null, null))
        assertEquals(1024, t(PictureKind.SCREENSHOT, PictureSize.SMALL, null, null))
        assertEquals(1600, t(PictureKind.PHOTO, PictureSize.STANDARD, null, null))
        assertEquals(1920, t(PictureKind.SCREENSHOT, PictureSize.STANDARD, null, null))
        assertEquals(2400, t(PictureKind.PHOTO, PictureSize.LARGE, null, null))
        assertEquals(2400, t(PictureKind.SCREENSHOT, PictureSize.LARGE, null, null))
    }

    @Test
    fun `small print raises Standard to 2400 for 12 lines of 10 px letters, not for 11`() {
        assertEquals(2400, PictureSizing.targetLongEdge(PictureKind.SCREENSHOT, PictureSize.STANDARD, tenPxLines(12)))
        assertEquals(2400, PictureSizing.targetLongEdge(PictureKind.PHOTO, PictureSize.STANDARD, tenPxLines(40)))
        assertEquals(1920, PictureSizing.targetLongEdge(PictureKind.SCREENSHOT, PictureSize.STANDARD, tenPxLines(11)))
        assertEquals(1600, PictureSizing.targetLongEdge(PictureKind.PHOTO, PictureSize.STANDARD, tenPxLines(11)))
    }

    @Test
    fun `readable letters do not raise it, and Small and Large are never changed`() {
        assertEquals(1600, PictureSizing.targetLongEdge(PictureKind.PHOTO, PictureSize.STANDARD, TextStats(30, 16f)))
        assertEquals(1024, PictureSizing.targetLongEdge(PictureKind.PHOTO, PictureSize.SMALL, tenPxLines(50)))
        assertEquals(2400, PictureSizing.targetLongEdge(PictureKind.PHOTO, PictureSize.LARGE, tenPxLines(50)))
    }

    @Test
    fun `letter height is scaled from the reading size to the stored size`() {
        // Read at 4096 px; stored at 1920 the 30 px letters become 14 px: small print.
        assertEquals(2400, PictureSizing.targetLongEdge(PictureKind.SCREENSHOT, PictureSize.STANDARD, TextStats(20, 30f), 4096))
        // Read at 2000 px; stored at 1920 the 20 px letters stay about 19 px: fine.
        assertEquals(1920, PictureSizing.targetLongEdge(PictureKind.SCREENSHOT, PictureSize.STANDARD, TextStats(20, 20f), 2000))
        // A picture read smaller than the stored size is never enlarged, so its letters do not grow.
        assertEquals(1600, PictureSizing.targetLongEdge(PictureKind.PHOTO, PictureSize.STANDARD, TextStats(20, 17f), 800))
    }

    @Test
    fun `the rule is behind one constant`() {
        // The constant is compile-time, so the switch-off branch cannot be driven here; this pins the values the rule uses.
        assertTrue(PictureSizing.SMALL_PRINT_BOOST)
        assertEquals(12, PictureSizing.SMALL_PRINT_MIN_LINES)
        assertEquals(16f, PictureSizing.SMALL_PRINT_MIN_LETTER_PX, 0f)
        assertEquals(2400, PictureSizing.SMALL_PRINT_LONG_EDGE)
        assertEquals(75, PictureSizing.WEBP_QUALITY)
        assertEquals(4096, PictureSizing.READING_LONG_EDGE)
    }

    @Test
    fun `scaled keeps the shape, rounds, never enlarges and never reaches zero`() {
        assertEquals(1600 to 1200, PictureSizing.scaled(4000, 3000, 1600))
        assertEquals(886 to 1920, PictureSizing.scaled(1080, 2340, 1920))
        assertEquals(800 to 600, PictureSizing.scaled(800, 600, 1600))
        assertEquals(1600 to 1200, PictureSizing.scaled(1600, 1200, 1600))
        assertEquals(1 to 1600, PictureSizing.scaled(1, 5000, 1600))
        assertEquals(1 to 1, PictureSizing.scaled(0, 0, 1600))
    }

    @Test
    fun `guessKind`() {
        val g = { name: String?, w: Int, h: Int -> PictureSizing.guessKind("image/png", w, h, 1080, 2340, name) }
        assertEquals(PictureKind.SCREENSHOT, g("Screenshot_20261112-101010.png", 4000, 3000))
        assertEquals(PictureKind.SCREENSHOT, g("screenshot (3).jpg", 4000, 3000))
        assertEquals(PictureKind.SCREENSHOT, g(null, 2340, 1080))
        assertEquals(PictureKind.SCREENSHOT, g(null, 1080, 2340))
        assertEquals(PictureKind.PHOTO, g("IMG_0001.jpg", 4000, 3000))
        assertEquals(PictureKind.PHOTO, g(null, 4000, 3000))
    }

    @Test
    fun `the setting parses, defaulting to Standard`() {
        assertEquals(PictureSize.STANDARD, PictureSize.parse(null))
        assertEquals(PictureSize.STANDARD, PictureSize.parse("huge"))
        assertEquals(PictureSize.LARGE, PictureSize.parse("LARGE"))
    }

    private fun riff(vararg chunks: Pair<String, Int>): ByteArray {
        val body = java.io.ByteArrayOutputStream()
        body.write("WEBP".toByteArray())
        for ((name, size) in chunks) {
            body.write(name.toByteArray())
            body.write(byteArrayOf(size.toByte(), (size shr 8).toByte(), 0, 0))
            body.write(ByteArray(size + (size and 1)))
        }
        val b = body.toByteArray()
        return "RIFF".toByteArray() + byteArrayOf(b.size.toByte(), (b.size shr 8).toByte(), 0, 0) + b
    }

    @Test
    fun `WebpChunks lists a plain lossy file`() {
        val bytes = riff("VP8 " to 101)
        assertEquals(listOf("VP8 "), WebpChunks.list(bytes))
        assertTrue(WebpChunks.onlyImageData(bytes))
    }

    @Test
    fun `WebpChunks finds an EXIF chunk after an odd-sized one`() {
        val bytes = riff("VP8X" to 10, "VP8 " to 33, "EXIF" to 20, "XMP " to 5)
        assertEquals(listOf("VP8X", "VP8 ", "EXIF", "XMP "), WebpChunks.list(bytes))
        assertFalse(WebpChunks.onlyImageData(bytes))
    }

    @Test
    fun `a colour profile alone is not embedded data`() {
        assertTrue(WebpChunks.onlyImageData(riff("VP8X" to 10, "ICCP" to 20, "VP8 " to 33)))
    }

    @Test
    fun `WebpChunks says null for what is not WebP`() {
        assertNull(WebpChunks.list("GIF89a-not-a-webp-file".toByteArray()))
        assertNull(WebpChunks.list(ByteArray(0)))
        assertNull(WebpChunks.list(("RIFF" + "\u0000\u0000\u0000\u0000" + "WAVE").toByteArray()))
        assertFalse(WebpChunks.onlyImageData(ByteArray(3)))
    }
}
