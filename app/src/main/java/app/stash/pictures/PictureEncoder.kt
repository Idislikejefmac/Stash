package app.stash.pictures

import android.graphics.Bitmap
import app.stash.domain.PictureSizing
import java.io.ByteArrayOutputStream

interface PictureEncoder {
    /** Scale with filtering to [width] x [height] (never enlarging; the caller worked that out) and write lossy WebP. */
    fun encode(bitmap: Bitmap, width: Int, height: Int): ByteArray

    /** A new bitmap at most [maxLongEdge] on the long edge; never the same object as [bitmap]. */
    fun thumbnail(bitmap: Bitmap, maxLongEdge: Int): Bitmap
}

/**
 * Re-encoding is what removes the camera's embedded data, including any position: `Bitmap.compress` writes pixels only.
 * Every stored picture goes through here, even one that needs no shrinking.
 */
class AndroidPictureEncoder(private val quality: Int = PictureSizing.WEBP_QUALITY) : PictureEncoder {
    override fun encode(bitmap: Bitmap, width: Int, height: Int): ByteArray {
        val scaled = if (width == bitmap.width && height == bitmap.height) bitmap else Bitmap.createScaledBitmap(bitmap, width, height, true)
        try {
            val out = ByteArrayOutputStream()
            if (!scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out)) throw PictureException(PictureFailure.ENCODE_FAILED)
            return out.toByteArray()
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    override fun thumbnail(bitmap: Bitmap, maxLongEdge: Int): Bitmap {
        val (w, h) = PictureSizing.scaled(bitmap.width, bitmap.height, maxLongEdge)
        val out = if (w == bitmap.width && h == bitmap.height) bitmap.copy(Bitmap.Config.ARGB_8888, false) else Bitmap.createScaledBitmap(bitmap, w, h, true)
        return out ?: throw PictureException(PictureFailure.OUT_OF_MEMORY)
    }
}
