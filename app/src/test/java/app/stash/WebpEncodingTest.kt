package app.stash

import android.graphics.Bitmap
import android.graphics.Color
import app.stash.domain.WebpChunks
import app.stash.pictures.AndroidPictureEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Card 16b: the real platform encoder under Robolectric's native graphics: output is WebP with image chunks only. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WebpEncodingTest {
    @Test
    fun `the real encoder writes WebP with no embedded data, scaled`() {
        val bmp = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        for (x in 0 until 400) for (y in 0 until 300) bmp.setPixel(x, y, Color.rgb(x % 256, y % 256, (x + y) % 256))
        val bytes = AndroidPictureEncoder().encode(bmp, 200, 150)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals("WEBP", String(bytes, 8, 4))
        val chunks = WebpChunks.list(bytes)!!
        assertTrue("chunks: $chunks", chunks.none { it == "EXIF" || it == "XMP " })
        assertTrue(WebpChunks.onlyImageData(bytes))
        val thumb = AndroidPictureEncoder().thumbnail(bmp, 100)
        assertEquals(100, thumb.width)
        assertEquals(75, thumb.height)
    }
}
