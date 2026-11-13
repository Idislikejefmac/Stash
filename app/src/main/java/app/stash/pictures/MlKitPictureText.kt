package app.stash.pictures

import android.graphics.Bitmap
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.PictureTextRules
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Card 16f: reads the printed words in a picture on the phone with ML Kit Text Recognition v2, Latin, bundled model
 * (`com.google.mlkit:text-recognition`; D76, D80). Nothing is sent to Google by this code: the picture is processed on
 * the phone. The library reports usage figures to Google (disclosed in Settings, [PictureTextRules.DISCLOSURE]).
 *
 * Only this class touches ML Kit. The container hands it out only while the PICTURE_TEXT switch is on, so with the
 * switch off no ML Kit class is loaded. Any failure gives null; no words are ever logged, only counts and times.
 */
class MlKitPictureText(
    private val clock: () -> Long = System::currentTimeMillis,
) : PictureTextReader {
    // One recogniser for the process: loading the model is the slow part.
    private val recogniser by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun read(bitmap: Bitmap): PictureText? {
        val started = clock()
        return try {
            val result = recognise(bitmap)
            val lines = result.textBlocks.flatMap { it.lines }
            val text = PictureTextRules.cut(lines.joinToString("\n") { it.text })
            val heights = lines.mapNotNull { l -> l.boundingBox?.height()?.toFloat() }
            val read = PictureText(text, PictureTextRules.stats(heights), PictureTextRules.engine(LIBRARY_VERSION))
            Dbg.event(DbgComponent.PICTURE, DbgCode.READ, n1 = lines.size, n2 = text.length, ms = clock() - started)
            read
        } catch (e: CancellationException) {
            // The preparer's 5-second limit ends here: a time-out is its own event, and the picture is stored anyway.
            Dbg.event(DbgComponent.PICTURE, DbgCode.TIMED_OUT, ms = clock() - started)
            throw e
        } catch (e: Throwable) {
            Dbg.event(DbgComponent.PICTURE, DbgCode.FAILED, n1 = 0, ms = clock() - started)
            null
        }
    }

    private suspend fun recognise(bitmap: Bitmap): Text = suspendCancellableCoroutine { cont ->
        recogniser.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
            .addOnFailureListener { if (cont.isActive) cont.resumeWith(Result.failure(it)) }
    }

    companion object {
        /** Kept in step with `mlkitTextRecognition` in gradle/libs.versions.toml (a test checks). */
        const val LIBRARY_VERSION = "16.0.1"
    }
}
