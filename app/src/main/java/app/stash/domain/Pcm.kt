package app.stash.domain

/*
 * Card 26a. Raw sound as the recorder hands it over: 16 kHz, mono, 16-bit, held in memory only.
 * No Android imports. Nothing here writes, logs or encodes anything.
 */

object AudioFormatConstants {
    const val SAMPLE_RATE = 16_000
    const val CHUNK_MS = 20
    const val CHUNK_SAMPLES = SAMPLE_RATE * CHUNK_MS / 1000 // 320

    /** Three minutes (card 26a item 5). Memory at the ceiling: 16,000 x 2 bytes x 180 s = 5.76 MB, 1.92 MB a minute. */
    const val MAX_NOTE_MS = 180_000L
    const val WARN_BEFORE_LIMIT_MS = 15_000L

    /** The same wait as the capture screen's SILENCE_LIMIT_MS: thinking before speaking is not a failure. */
    const val NOTHING_HEARD_MS = 25_000L

    fun samplesToMs(samples: Long): Long = samples * 1000L / SAMPLE_RATE
    fun msToSamples(ms: Long): Long = ms * SAMPLE_RATE / 1000L
}

class Pcm(val samples: ShortArray) {
    val durationMs: Long get() = AudioFormatConstants.samplesToMs(samples.size.toLong())

    /** Loudest absolute sample, 0 to 32768 (a full-scale negative sample counts as 32768). */
    fun peak(): Int {
        var p = 0
        for (s in samples) {
            val a = if (s < 0) -s.toInt() else s.toInt()
            if (a > p) p = a
        }
        return p
    }
}

/**
 * A growing buffer with a fixed ceiling. Stored as one-second blocks, so growing never copies what is already held;
 * [toPcm] makes the single contiguous copy at the end (peak use then about twice the note: 11.5 MB at three minutes).
 * Appended to from the recorder thread, read after that thread has finished; every method is synchronized.
 */
class PcmBuffer(private val maxSamples: Long = AudioFormatConstants.msToSamples(AudioFormatConstants.MAX_NOTE_MS)) {
    private val blocks = ArrayList<ShortArray>()
    private var inLast = 0
    private var total = 0L
    private var ceilingHit = false

    val hitCeiling: Boolean @Synchronized get() = ceilingHit
    val sampleCount: Long @Synchronized get() = total
    val durationMs: Long @Synchronized get() = AudioFormatConstants.samplesToMs(total)

    /** Appends up to [len] samples of [src]; anything past the ceiling is dropped and [hitCeiling] becomes true. Returns how many were kept. */
    @Synchronized
    fun append(src: ShortArray, len: Int = src.size): Int {
        val room = (maxSamples - total).coerceAtLeast(0)
        val take = minOf(len.toLong(), room).toInt()
        if (take < len) ceilingHit = true
        var done = 0
        while (done < take) {
            if (blocks.isEmpty() || inLast == BLOCK) { blocks.add(ShortArray(BLOCK)); inLast = 0 }
            val n = minOf(take - done, BLOCK - inLast)
            System.arraycopy(src, done, blocks[blocks.size - 1], inLast, n)
            inLast += n; done += n
        }
        total += take
        if (total >= maxSamples) ceilingHit = true
        return take
    }

    @Synchronized
    fun toPcm(): Pcm {
        val out = ShortArray(total.toInt())
        var pos = 0
        for ((i, b) in blocks.withIndex()) {
            val n = if (i == blocks.size - 1) inLast else b.size
            System.arraycopy(b, 0, out, pos, n)
            pos += n
        }
        return Pcm(out)
    }

    /** Lets go of every block. The buffer is empty afterwards. */
    @Synchronized
    fun release() { blocks.clear(); inLast = 0; total = 0; ceilingHit = false }

    private companion object { const val BLOCK = AudioFormatConstants.SAMPLE_RATE } // one second
}
