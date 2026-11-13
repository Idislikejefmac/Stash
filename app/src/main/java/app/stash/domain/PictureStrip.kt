package app.stash.domain

import kotlin.math.max
import kotlin.math.min

/** The words of the pictures-on-a-note screens (docs/cards/16d), in one place so a test can pin them. */
object PictureWords {
    const val ADD_FAILED = "I couldn't add that picture."
    const val MISSING = "Picture missing"
    const val REMOVE_ONE = "Remove this picture? It can't be brought back."
    const val REMOVE_NOTE = "This note is only this picture. Remove the note?"
    const val READ_FROM = "Read from the picture"

    /** The small label at the start of a strip. */
    fun count(n: Int) = if (n == 1) "1 picture" else "$n pictures"

    /** The one row that stands in for a Private note's strip until "Show" is tapped. */
    fun privateRow(n: Int) = (if (n == 1) "1 private picture" else "$n private pictures") + " · Show"

    /** What TalkBack says for a thumbnail. [index] counts from 0. */
    fun position(index: Int, count: Int) = "Picture ${index + 1} of $count"

    /** The top of the viewer. [index] counts from 0. */
    fun viewerTitle(index: Int, count: Int) = "${index + 1} of $count"

    fun adding(current: Int, total: Int) = "Adding $current of $total"

    /** Settings, under storage: "Pictures: 42, using 11 MB". */
    fun storageLine(count: Int, bytes: Long): String {
        if (count <= 0) return "Pictures: none"
        val mb = (bytes + 524_288L) / 1_048_576L
        return "Pictures: $count, using " + if (bytes < 1_048_576L) "less than 1 MB" else "$mb MB"
    }
}

enum class RemoveKind { PICTURE, NOTE }

/** Which question removing a picture asks, and where the viewer goes afterwards (card 16d items 10 to 12). */
object PictureRemoval {
    /** A note with no words and one picture would be left empty: the question is about the whole note then. */
    fun kind(noteText: String, pictureCount: Int): RemoveKind =
        if (noteText.isBlank() && pictureCount <= 1) RemoveKind.NOTE else RemoveKind.PICTURE

    fun question(kind: RemoveKind) = if (kind == RemoveKind.NOTE) PictureWords.REMOVE_NOTE else PictureWords.REMOVE_ONE

    /** The picture to show after the one at [removed] went; null means none is left and the viewer closes. */
    fun nextIndex(removed: Int, remaining: Int): Int? = if (remaining <= 0) null else min(removed, remaining - 1)
}

/** Whether a note's pictures may be drawn now. Until card 02's second unlock exists, a tap on "Show" is the unlock. */
object PicturePrivacy {
    fun mayDraw(sensitive: Boolean, shown: Boolean) = !sensitive || shown
}

/** Sizes for decoding in memory. */
object PictureViewing {
    /** The largest power of two that keeps the long edge at or above [target]; 1 when the picture is already small. */
    fun sampleSize(width: Int, height: Int, target: Int): Int {
        val long = max(width, height)
        var s = 1
        while (target > 0 && long / (s * 2) >= target) s *= 2
        return s
    }
}

/**
 * A byte-budgeted, least-recently-used cache (docs/cards/16d item 2: at most 8 MB). An entry larger than the whole
 * budget is not kept. Thread-safe.
 */
class SizedLruCache<K, V : Any>(private val maxBytes: Long, private val sizeOf: (V) -> Long) {
    private val map = LinkedHashMap<K, V>(16, 0.75f, true)
    private var total = 0L

    @Synchronized fun get(key: K): V? = map[key]

    @Synchronized fun put(key: K, value: V) {
        val size = sizeOf(value)
        map.remove(key)?.let { total -= sizeOf(it) }
        if (size > maxBytes) return
        map[key] = value
        total += size
        val it = map.entries.iterator()
        while (total > maxBytes && it.hasNext()) {
            val e = it.next()
            total -= sizeOf(e.value)
            it.remove()
        }
    }

    @Synchronized fun clear() {
        map.clear()
        total = 0
    }

    @Synchronized fun bytes(): Long = total
    @Synchronized fun count(): Int = map.size
}

/** Pinch to zoom and drag to pan, as numbers. The picture scales about its centre; the offset keeps it on screen. */
data class ZoomState(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
    val zoomed: Boolean get() = scale > 1.01f

    fun transformed(zoom: Float, panX: Float, panY: Float, viewWidth: Float, viewHeight: Float): ZoomState {
        val s = (scale * zoom).coerceIn(1f, MAX_SCALE)
        if (s <= 1f) return ZoomState()
        val maxX = viewWidth * (s - 1f) / 2f
        val maxY = viewHeight * (s - 1f) / 2f
        return ZoomState(s, (x + panX).coerceIn(-maxX, maxX), (y + panY).coerceIn(-maxY, maxY))
    }

    companion object {
        const val MAX_SCALE = 6f
    }
}
