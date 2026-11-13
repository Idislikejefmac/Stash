package app.stash.pictures

import android.graphics.Bitmap
import app.stash.capture.InboundAttachment
import app.stash.data.AttachmentKind
import app.stash.data.AttachmentSource
import app.stash.data.Ids
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.PictureKind
import app.stash.domain.PictureSize
import app.stash.domain.PictureSizing
import app.stash.security.AttachmentStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import kotlin.math.max

/** A stored copy ([attachment] is already written by the store) and a small in-memory thumbnail, never written to disk. */
class Prepared(val attachment: InboundAttachment, val thumbnail: Bitmap)

/**
 * The one function every later card calls to take in a picture (docs/cards/16b). One picture at a time (a mutex: a
 * reading bitmap is about 50 MB), on a background dispatcher. Order: decode at reading size, read the words, choose the
 * target size, encode, write through the store, make the thumbnail, recycle the large bitmaps. Nothing decrypted or
 * decoded is written to disk.
 */
class PicturePreparer(
    private val decoder: PictureDecoder,
    private val encoder: PictureEncoder,
    private val store: AttachmentStore,
    private val reader: () -> PictureTextReader,
    private val size: () -> PictureSize,
    private val screen: () -> Pair<Int, Int>,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val readTimeoutMs: Long = PictureSizing.READ_TIMEOUT_MS,
) {
    private val one = Mutex()

    /**
     * [sizeOverride] is for the debug screen, which tries all three sizes; real callers leave it null (the setting).
     * Throws [PictureException] with a code only.
     */
    suspend fun prepare(
        input: PictureInput,
        kindHint: AttachmentKind?,
        source: AttachmentSource,
        sizeOverride: PictureSize? = null,
    ): Prepared = one.withLock {
        withContext(dispatcher) { run(input, kindHint, source, sizeOverride ?: size()) }
    }

    private suspend fun run(input: PictureInput, kindHint: AttachmentKind?, source: AttachmentSource, setting: PictureSize): Prepared {
        val decoded = when (val r = decoder.decode(input)) {
            is DecodeResult.Ok -> r.picture
            is DecodeResult.Failed -> fail(r.failure)
        }
        val bitmap = decoded.bitmap
        try {
            val text = readWords(bitmap)
            val kind = when (kindHint) {
                AttachmentKind.SCREENSHOT -> PictureKind.SCREENSHOT
                AttachmentKind.PHOTO -> PictureKind.PHOTO
                else -> {
                    val (sw, sh) = screen()
                    PictureSizing.guessKind(decoded.mime, decoded.originalWidth, decoded.originalHeight, sw, sh, input.displayName)
                }
            }
            val target = PictureSizing.targetLongEdge(kind, setting, text?.stats, max(bitmap.width, bitmap.height))
            val (w, h) = PictureSizing.scaled(bitmap.width, bitmap.height, target)
            val bytes = try {
                encoder.encode(bitmap, w, h)
            } catch (e: PictureException) {
                fail(e.failure)
            } catch (e: OutOfMemoryError) {
                fail(PictureFailure.OUT_OF_MEMORY)
            }
            val id = Ids.new()
            val key = store.newKey()
            val fileName = store.newFileName()
            val written = try {
                store.write(fileName, key, id, ByteArrayInputStream(bytes))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(PictureFailure.STORE_FAILED)
            }
            val thumb = try {
                encoder.thumbnail(bitmap, PictureSizing.THUMBNAIL_LONG_EDGE)
            } catch (e: CancellationException) {
                store.delete(fileName)
                throw e
            } catch (e: Throwable) {
                store.delete(fileName)
                fail(PictureFailure.OUT_OF_MEMORY)
            }
            val attachment = InboundAttachment(
                id = id,
                kind = if (kind == PictureKind.SCREENSHOT) AttachmentKind.SCREENSHOT else AttachmentKind.PHOTO,
                source = source,
                mime = MIME,
                fileName = fileName,
                key = key,
                byteSize = written.byteSize,
                sha256 = written.sha256,
                width = w,
                height = h,
                ocrText = text?.text,
                ocrEngine = text?.engine,
            )
            return Prepared(attachment, thumb)
        } finally {
            bitmap.recycle()
        }
    }

    /** The reader gets 5 seconds; a time-out or a failure means "no words", and the picture is stored anyway. */
    private suspend fun readWords(bitmap: Bitmap): PictureText? = try {
        withTimeoutOrNull(readTimeoutMs) { reader().read(bitmap) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Deletes the stored file again (the owner cancelled) and frees the thumbnail. */
    fun discard(prepared: Prepared) {
        store.delete(prepared.attachment.fileName)
        prepared.thumbnail.recycle()
    }

    private fun fail(f: PictureFailure): Nothing {
        Dbg.event(DbgComponent.PICTURE, DbgCode.FAILED, f.code)
        throw PictureException(f)
    }

    companion object {
        const val MIME = "image/webp"
    }
}
