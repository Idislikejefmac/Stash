package app.stash.audio

import app.stash.domain.AudioFormatConstants
import app.stash.domain.Endpointer
import app.stash.domain.NoiseFloor
import app.stash.domain.Pcm

/*
 * Card 26e item 1. Where a long recording is cut before it is written out, so that the engine's memory stays bounded
 * by the length of one piece and not of the whole note. Pure Kotlin, no Android import.
 *
 * Rules (card 25 showed that cutting at every silence cost words, so this is deliberately different):
 *  - a recording no longer than maxMs is one piece: short notes are never cut;
 *  - a long one is cut only inside a real pause, in a few large pieces: for each piece, the quietest stretch of at
 *    least minPauseMs in which every 20 ms chunk is "not speech", between 60% and 133% of targetMs from the piece's
 *    start, and the cut is in the middle of that stretch (stretches within QUIET_TOLERANCE of the quietest count as
 *    equally quiet, and the one nearest an even share of the rest wins, so no piece is a scrap);
 *  - no sample is ever thrown away: every sample belongs to a piece, and nothing is trimmed, so no first word is clipped;
 *  - with no such pause before maxMs, the cut is at the quietest 100 ms of the window and the two pieces overlap by
 *    300 ms (the cut is marked forced, so the joiner can remove a word repeated across it).
 * "Speech" is decided exactly as the recorder's level rule decides it (Endpointer.rmsOf, Endpointer.isSpeech and the
 * NoiseFloor it tracks), measured over the whole recording.
 */

/** One piece of a recording: sample indexes [range] (inclusive). [forcedEnd]: the cut after it was forced (overlap). */
data class Piece(val range: IntRange, val forcedEnd: Boolean = false) {
    val samples: Int get() = if (range.isEmpty()) 0 else range.last - range.first + 1
}

/** Chooses the pieces a recording is written out in. [PauseSplitter.DEFAULT] is the rule the app uses. */
fun interface Splitter {
    fun split(pcm: Pcm): List<Piece>
}

object PauseSplitter {
    /** The length the walk aims for. */
    const val TARGET_MS = 15_000L
    /** No piece is longer than this; a recording up to this long is never cut. Sets the engine's memory bound. */
    const val MAX_MS = 20_000L
    /** The shortest gap that counts as a real pause. */
    const val MIN_PAUSE_MS = 400L
    /** The search window for a cut, as a share of [TARGET_MS] from the piece's start. */
    const val WINDOW_FROM = 0.60
    const val WINDOW_TO = 1.33
    /** With no pause: the length of the quietest stretch the forced cut is put in. */
    const val FORCED_QUIET_MS = 100L
    /** With no pause: how much the two pieces share across a forced cut. */
    const val OVERLAP_MS = 300L
    /**
     * Places whose mean level is within this factor of the quietest one (or of the noise floor) count as equally
     * quiet; the one nearest an even share of the rest is used. (Speech is at least 3 times the floor.)
     */
    const val QUIET_TOLERANCE = 2.0

    val DEFAULT: Splitter = Splitter { split(it) }

    private const val C = AudioFormatConstants.CHUNK_SAMPLES
    private const val CHUNK_MS = AudioFormatConstants.CHUNK_MS.toLong()

    fun split(
        pcm: Pcm,
        targetMs: Long = TARGET_MS,
        maxMs: Long = MAX_MS,
        minPauseMs: Long = MIN_PAUSE_MS,
    ): List<Piece> {
        val n = pcm.samples.size
        val maxSamples = AudioFormatConstants.msToSamples(maxMs).toInt()
        if (n <= maxSamples) return listOf(Piece(0 until n))

        val levels = Levels(pcm.samples)
        val target = AudioFormatConstants.msToSamples(targetMs).toInt()
        val half = AudioFormatConstants.msToSamples(OVERLAP_MS).toInt() / 2
        val pauseChunks = ceilDiv(minPauseMs, CHUNK_MS).toInt().coerceAtLeast(1)
        val quietChunks = ceilDiv(FORCED_QUIET_MS, CHUNK_MS).toInt().coerceAtLeast(1)

        val out = ArrayList<Piece>()
        var start = 0
        while (n - start > maxSamples) {
            val lo = start + (target * WINDOW_FROM).toInt()
            val hi = start + minOf((target * WINDOW_TO).toInt(), maxSamples)
            // Among equally quiet places, aim at pieces of equal length (about targetMs each), so the last piece is
            // never a scrap: the rest is shared out over ceil(rest / target) pieces.
            val rest = n - start
            val aim = start + (rest / ((rest + target - 1) / target))
            // A real pause: the quietest run of pauseChunks chunks that are all "not speech", wholly inside [lo, hi).
            val pause = levels.quietest(firstChunkAtOrAfter(lo), hi / C, pauseChunks, aim, needAllQuiet = true)
            if (pause != null) {
                val cut = ((2 * pause + pauseChunks) * C) / 2
                out += Piece(start until cut)
                start = cut
                continue
            }
            // No pause: the quietest 100 ms, early enough that the first piece with its half of the overlap stays
            // within maxSamples. The pieces overlap by OVERLAP_MS around the cut.
            val forcedHi = start + minOf((target * WINDOW_TO).toInt(), maxSamples - half)
            val q = levels.quietest(firstChunkAtOrAfter(lo), forcedHi / C, quietChunks, aim, needAllQuiet = false)
            val cut = if (q != null) ((2 * q + quietChunks) * C) / 2 else start + minOf(target, maxSamples - half)
            out += Piece(start until minOf(cut + half, n), forcedEnd = true)
            start = cut - half
        }
        out += Piece(start until n)
        return out
    }

    private fun firstChunkAtOrAfter(sample: Int) = (sample + C - 1) / C
    private fun ceilDiv(a: Long, b: Long) = (a + b - 1) / b

    /** Chunk levels over the whole recording, the floor they imply, and which chunks are speech. */
    private class Levels(samples: ShortArray) {
        val count = (samples.size + C - 1) / C
        private val sum = DoubleArray(count + 1)
        private val quiet = IntArray(count + 1)
        val floor: Double

        init {
            val level = DoubleArray(count) { i ->
                val from = i * C
                Endpointer.rmsOf(samples, from, minOf(C, samples.size - from))
            }
            floor = NoiseFloor().apply { level.forEach { update(it) } }.value
            for (i in 0 until count) {
                sum[i + 1] = sum[i] + level[i]
                quiet[i + 1] = quiet[i] + if (Endpointer.isSpeech(level[i], floor)) 0 else 1
            }
        }

        /**
         * The first chunk of the quietest run of [len] chunks within chunks [from, toExclusive) (all of them not
         * speech, if [needAllQuiet]). Runs whose mean level is within [QUIET_TOLERANCE] of the quietest (or of the
         * floor, if that is higher) count as equally quiet, since two pauses in one room differ only by noise; among
         * those the one whose middle is nearest sample [aim] wins.
         */
        fun quietest(from: Int, toExclusive: Int, len: Int, aim: Int, needAllQuiet: Boolean): Int? {
            val first = from.coerceAtLeast(0)
            val last = minOf(toExclusive, count) - len
            fun ok(j: Int) = !needAllQuiet || quiet[j + len] - quiet[j] == len
            fun mean(j: Int) = (sum[j + len] - sum[j]) / len
            var lowest = Double.MAX_VALUE
            for (j in first..last) if (ok(j)) lowest = minOf(lowest, mean(j))
            if (lowest == Double.MAX_VALUE) return null
            val asQuiet = QUIET_TOLERANCE * maxOf(lowest, floor)
            var best: Int? = null
            var bestDist = Long.MAX_VALUE
            var bestMean = Double.MAX_VALUE
            for (j in first..last) {
                if (!ok(j)) continue
                val m = mean(j)
                if (m > asQuiet) continue
                val dist = kotlin.math.abs(((2L * j + len) * C) / 2 - aim)
                if (dist < bestDist || (dist == bestDist && m < bestMean)) { best = j; bestDist = dist; bestMean = m }
            }
            return best
        }
    }
}
