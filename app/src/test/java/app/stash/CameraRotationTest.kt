package app.stash

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import app.stash.domain.JpegOrientation
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Card 16e item 6, with the platform's own decoder (the one card 16b uses): a 40 by 20 picture whose bytes say
 * "turn 90" decodes upright as 20 by 40 once, and a picture the camera calls upright is not turned by an old tag.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CameraRotationTest {
    private fun landscapeJpeg(): ByteArray {
        val b = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        b.eraseColor(0xFF3366CC.toInt())
        val out = ByteArrayOutputStream()
        check(b.compress(Bitmap.CompressFormat.JPEG, 90, out))
        return out.toByteArray()
    }

    private fun decodedSize(jpeg: ByteArray): Pair<Int, Int> {
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(jpeg))) { d, _, _ -> d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
        return bmp.width to bmp.height
    }

    @Test fun aRotationOf90IsAppliedOnceByTheDecoder() {
        assertEquals(40 to 20, decodedSize(landscapeJpeg()))
        assertEquals(20 to 40, decodedSize(JpegOrientation.withOrientation(landscapeJpeg(), 90)))
        assertEquals(20 to 40, decodedSize(JpegOrientation.withOrientation(landscapeJpeg(), 270)))
    }

    @Test fun aRotationOf0And180KeepTheShape() {
        assertEquals(40 to 20, decodedSize(JpegOrientation.withOrientation(landscapeJpeg(), 0)))
        assertEquals(40 to 20, decodedSize(JpegOrientation.withOrientation(landscapeJpeg(), 180)))
    }

    @Test fun aTagAlreadyInsideThePictureIsReplacedSoNothingIsTurnedTwice() {
        val alreadyTagged = JpegOrientation.withOrientation(landscapeJpeg(), 90)
        assertEquals(20 to 40, decodedSize(alreadyTagged))
        // The camera says the pixels are already upright (rotation 0): the old tag must go.
        assertEquals(40 to 20, decodedSize(JpegOrientation.withOrientation(alreadyTagged, 0)))
        // The camera says 90 and the picture says 90 too: still one turn, not two.
        assertEquals(20 to 40, decodedSize(JpegOrientation.withOrientation(alreadyTagged, 90)))
    }
}
