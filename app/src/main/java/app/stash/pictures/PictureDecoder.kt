package app.stash.pictures

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import app.stash.domain.PictureSizing
import java.nio.ByteBuffer
import kotlin.math.max

/** Where a picture comes from: a content Uri (never copied to a file) or bytes already in memory. */
sealed interface PictureInput {
    val displayName: String?

    class FromUri(val uri: Uri, override val displayName: String? = null) : PictureInput
    class FromBytes(val bytes: ByteArray, override val displayName: String? = null) : PictureInput
}

/** Codes only, never text from the file. They go into `Dbg.event` as a number. */
enum class PictureFailure(val code: Int) { UNDECODABLE(1), OUT_OF_MEMORY(2), ENCODE_FAILED(3), STORE_FAILED(4) }

/** A picture cannot be turned into a stored copy. Carries a code and nothing from the picture. */
class PictureException(val failure: PictureFailure) : Exception("picture failed: ${failure.name}")

/**
 * [bitmap] is at most 4,096 px on the long edge and upright; [originalWidth] and [originalHeight] are the picture's
 * own size (upright).
 */
class DecodedPicture(val bitmap: Bitmap, val originalWidth: Int, val originalHeight: Int, val mime: String?)

sealed interface DecodeResult {
    class Ok(val picture: DecodedPicture) : DecodeResult
    class Failed(val failure: PictureFailure) : DecodeResult
}

interface PictureDecoder {
    fun decode(input: PictureInput): DecodeResult
}

/**
 * The platform's `ImageDecoder`: software allocator (the bitmap can be read and written back out), the stored rotation
 * applied by the decoder itself, an animated picture gives its first frame, and a picture bigger than 4,096 px on
 * the long edge is decoded at a smaller size so a 200-megapixel photo never needs its full pixels in memory.
 */
class AndroidPictureDecoder(private val resolver: ContentResolver) : PictureDecoder {
    override fun decode(input: PictureInput): DecodeResult = try {
        val source = when (input) {
            is PictureInput.FromUri -> ImageDecoder.createSource(resolver, input.uri)
            is PictureInput.FromBytes -> ImageDecoder.createSource(ByteBuffer.wrap(input.bytes))
        }
        var origW = 0
        var origH = 0
        var mime: String? = null
        val bitmap: Bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            origW = info.size.width
            origH = info.size.height
            mime = info.mimeType
            if (max(origW, origH) > PictureSizing.READING_LONG_EDGE) {
                val (w, h) = PictureSizing.scaled(origW, origH, PictureSizing.READING_LONG_EDGE)
                decoder.setTargetSize(w, h)
            }
        }
        DecodeResult.Ok(DecodedPicture(bitmap, origW, origH, mime))
    } catch (e: OutOfMemoryError) {
        DecodeResult.Failed(PictureFailure.OUT_OF_MEMORY)
    } catch (e: Exception) {
        // A video, a broken file, a Uri that cannot be opened: a code, not the exception's message.
        DecodeResult.Failed(PictureFailure.UNDECODABLE)
    }
}
