package app.stash.ui.pictures

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.stash.data.AttachmentInfo
import app.stash.domain.PictureViewing
import app.stash.domain.SizedLruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

sealed interface PictureResult {
    class Ready(val bitmap: Bitmap) : PictureResult
    /** The file is gone or damaged: the screen draws a grey tile that says "Picture missing". */
    data object Missing : PictureResult
}

/**
 * Turns a stored picture into a bitmap in memory (docs/cards/16d item 2). Nothing decrypted is written anywhere.
 * Screens get this interface, so a test can give a fake that counts what it is asked for.
 */
interface PictureLoader {
    /** Decodes to at most [maxEdge] on the long edge (never smaller than the stored size allows). Never throws. */
    suspend fun load(picture: AttachmentInfo, maxEdge: Int): PictureResult

    /** Forget every decoded picture: the activity stopped, or the app locked. */
    fun clear()
}

/** The real loader: opens through [open] (the attachment repository), decodes on [dispatcher], keeps at most 8 MB. */
class StoredPictureLoader(
    private val open: suspend (attachmentId: String) -> InputStream,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    cacheBytes: Long = CACHE_BYTES,
) : PictureLoader {
    private val cache = SizedLruCache<Pair<String, Int>, Bitmap>(cacheBytes) { it.allocationByteCount.toLong() }

    override suspend fun load(picture: AttachmentInfo, maxEdge: Int): PictureResult {
        val key = picture.id to maxEdge
        cache.get(key)?.let { if (!it.isRecycled) return PictureResult.Ready(it) }
        return withContext(dispatcher) {
            try {
                val bytes = open(picture.id).use { it.readBytes() }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val opts = BitmapFactory.Options().apply { inSampleSize = PictureViewing.sampleSize(bounds.outWidth, bounds.outHeight, maxEdge) }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                if (bmp == null) PictureResult.Missing else {
                    cache.put(key, bmp)
                    PictureResult.Ready(bmp)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PictureResult.Missing
            } catch (e: OutOfMemoryError) {
                PictureResult.Missing
            }
        }
    }

    override fun clear() = cache.clear()

    /** For tests. */
    fun cachedBytes(): Long = cache.bytes()

    companion object {
        const val CACHE_BYTES = 8L * 1024 * 1024
    }
}
