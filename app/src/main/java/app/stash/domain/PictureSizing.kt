package app.stash.domain

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Card 16b: how big the stored copy of a picture is. Pure Kotlin, no Android imports.
 * The owner's decision of 2026-11-11 (D72) and 2026-11-12 (D80): store the smallest legible copy.
 */

/** The "Picture size" setting. Applies to pictures added after the change. */
enum class PictureSize {
    SMALL, STANDARD, LARGE;

    companion object {
        fun parse(name: String?): PictureSize = entries.firstOrNull { it.name == name } ?: STANDARD
    }
}

/** What the picture is, for sizing. Callers that know (the camera) say so; otherwise [PictureSizing.guessKind]. */
enum class PictureKind { PHOTO, SCREENSHOT }

/** What the text reader (card 16f) saw: how many lines and the median letter height, in pixels of the picture it read. */
data class TextStats(val lines: Int, val medianLetterHeightPx: Float)

object PictureSizing {
    /** Small-print rule (D80). One line to turn it off. */
    const val SMALL_PRINT_BOOST = true

    /** 12 or more lines of text... */
    const val SMALL_PRINT_MIN_LINES = 12

    /** ...whose median letter, at the Standard size, would be under this many pixels. */
    const val SMALL_PRINT_MIN_LETTER_PX = 16f

    /** The long edge a picture with small print gets under Standard. */
    const val SMALL_PRINT_LONG_EDGE = 2400

    /** The longest edge of the bitmap the words are read from; the picture is never decoded larger than this first. */
    const val READING_LONG_EDGE = 4096

    /** The in-memory thumbnail's longest edge. */
    const val THUMBNAIL_LONG_EDGE = 384

    /** Lossy WebP quality. */
    const val WEBP_QUALITY = 75

    /** The reader gives up after this long; the picture is stored anyway. */
    const val READ_TIMEOUT_MS = 5_000L

    fun baseLongEdge(kind: PictureKind, size: PictureSize): Int = when (size) {
        PictureSize.SMALL -> 1024
        PictureSize.STANDARD -> if (kind == PictureKind.SCREENSHOT) 1920 else 1600
        PictureSize.LARGE -> 2400
    }

    /**
     * The long edge to store at. [text] is what the reader saw; [readLongEdge] is the long edge of the bitmap it read,
     * so the letter height can be scaled to the Standard size (null: the height is taken as already at that size).
     * The small-print rule applies to Standard only: Small is the owner's explicit choice and is never raised.
     */
    fun targetLongEdge(kind: PictureKind, size: PictureSize, text: TextStats? = null, readLongEdge: Int? = null): Int {
        val base = baseLongEdge(kind, size)
        if (!SMALL_PRINT_BOOST || size != PictureSize.STANDARD || text == null) return base
        if (text.lines < SMALL_PRINT_MIN_LINES) return base
        val scale = if (readLongEdge != null && readLongEdge > 0) min(1f, base.toFloat() / readLongEdge) else 1f
        return if (text.medianLetterHeightPx * scale < SMALL_PRINT_MIN_LETTER_PX) SMALL_PRINT_LONG_EDGE else base
    }

    /** Width and height after scaling the long edge to [targetLongEdge]. Keeps the shape, never below 1, never enlarges. */
    fun scaled(width: Int, height: Int, targetLongEdge: Int): Pair<Int, Int> {
        val w = max(1, width)
        val h = max(1, height)
        val long = max(w, h)
        if (long <= targetLongEdge) return w to h
        val f = targetLongEdge.toDouble() / long
        return max(1, (w * f).roundToInt()) to max(1, (h * f).roundToInt())
    }

    /** Screenshot when the name starts with "Screenshot" (any case) or the size equals the screen's, either way round. */
    fun guessKind(mime: String?, width: Int, height: Int, screenWidth: Int, screenHeight: Int, displayName: String?): PictureKind {
        if (displayName != null && displayName.trimStart().startsWith("screenshot", ignoreCase = true)) return PictureKind.SCREENSHOT
        val sameSize = (width == screenWidth && height == screenHeight) || (width == screenHeight && height == screenWidth)
        return if (sameSize && width > 0 && height > 0) PictureKind.SCREENSHOT else PictureKind.PHOTO
    }
}
