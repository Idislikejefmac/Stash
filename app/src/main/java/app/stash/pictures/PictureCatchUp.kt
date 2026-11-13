package app.stash.pictures

import android.graphics.Bitmap
import app.stash.data.AttachmentRepository
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.PictureSizing
import app.stash.domain.PictureTextRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStream

/**
 * Card 16f item 12: reads the words of pictures stored while the switch was off, or when the reader timed out. Up to
 * [PictureTextRules.CATCH_UP_BATCH] pictures whose `ocr_engine` is null are opened from the **stored** copy, read, saved
 * with `updateOcr` (which re-indexes the note), and the engine name gets the suffix "/stored". Run after the daily
 * upkeep and when the switch is turned on. With the switch off it reads nothing. One picture at a time.
 */
class PictureCatchUp(
    private val attachments: AttachmentRepository,
    private val enabled: () -> Boolean,
    private val reader: () -> PictureTextReader,
    /** Decodes the plain bytes; the real one is `BitmapFactory.decodeStream`. */
    private val decode: (InputStream) -> Bitmap?,
    /** Called once for each note whose picture was read, e.g. to file a picture-only note again with its new title. */
    private val afterRead: suspend (captureId: String) -> Unit = {},
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val readTimeoutMs: Long = PictureSizing.READ_TIMEOUT_MS,
) {
    /** Returns how many pictures were read. */
    suspend fun run(): Int {
        if (!enabled()) return 0
        val todo = attachments.unread()
        if (todo.isEmpty()) return 0
        var read = 0
        for (a in todo) {
            if (!enabled()) break
            val words = readOne(a.id)
            if (words == null) {
                attachments.touchUnread(a.id)
                continue
            }
            attachments.setOcr(a.id, words.text, PictureTextRules.asStored(words.engine))
            read++
            try {
                afterRead(a.captureId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The words are saved; filing again can wait for the next read.
            }
        }
        Dbg.event(DbgComponent.PICTURE, DbgCode.CAUGHT_UP, n1 = read)
        return read
    }

    private suspend fun readOne(attachmentId: String): PictureText? = withContext(dispatcher) {
        val bitmap = try {
            attachments.openBytes(attachmentId).use { decode(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return@withContext null
        try {
            withTimeoutOrNull(readTimeoutMs) { reader().read(bitmap) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } finally {
            bitmap.recycle()
        }
    }
}
