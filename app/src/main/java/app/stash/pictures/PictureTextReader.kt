package app.stash.pictures

import android.graphics.Bitmap
import app.stash.domain.TextStats

/** The words read from a picture, with what the sizing rule needs and which engine read them. */
class PictureText(val text: String, val stats: TextStats, val engine: String)

/** The seam card 16f plugs the on-phone text reader into. Reads the picture before it is shrunk. */
interface PictureTextReader {
    suspend fun read(bitmap: Bitmap): PictureText?
}

/** Reads nothing: the PICTURE_TEXT switch is off (card 16f). */
object NoPictureText : PictureTextReader {
    override suspend fun read(bitmap: Bitmap): PictureText? = null
}

/** Card 16f: which reader to use. The factory is not called when the switch is off, so ML Kit is never touched then. */
object PictureTextChoice {
    fun reader(on: Boolean, mlKit: () -> PictureTextReader): PictureTextReader = if (on) mlKit() else NoPictureText
}
