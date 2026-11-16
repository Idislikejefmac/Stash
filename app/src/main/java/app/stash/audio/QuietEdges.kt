package app.stash.audio

import app.stash.domain.AudioFormatConstants
import app.stash.domain.EndpointTuning
import app.stash.domain.Endpointer
import app.stash.domain.NoiseFloor
import app.stash.domain.Pcm

/*
 * 2026-11-16 (docs/EXPERIMENT_LOG.md, "Voice notes in real use"). The speech engine wrote out NOTHING for a clear 8 s note
 * that was followed by 6 s of quiet (the owner waiting for the automatic stop); the first 9 s alone gave every word.
 * So the quiet before the first and after the last speech is trimmed before a piece goes to the engine, keeping a
 * generous margin of [KEEP_MS] on each side. Nothing between the first and last speech is ever removed, so this is
 * not the "cut at every silence" that card 25 found cost words. Pure Kotlin, no Android import.
 *
 * "Speech" is decided exactly as the recorder's level rule and card 26e's PauseSplitter decide it (Endpointer.rmsOf,
 * Endpointer.isSpeech), with the noise floor taken over the whole piece as in [floor], and a sound counts only once it has lasted
 * [EndpointTuning.MIN_SPEECH_RUN_CHUNKS] chunks in a row, so a tap on Done at the end does not count as speech.
 */
object QuietEdges {
    /** Quiet kept before the first and after the last speech. */
    const val KEEP_MS = 1_000L

    /** The margin for the one retry when the engine still wrote out nothing ([EdgeTrimTranscriber]). */
    const val TIGHT_KEEP_MS = 250L

    private const val C = AudioFormatConstants.CHUNK_SAMPLES

    /** Samples from the start of the first sustained speech to the end of the last, or null if there is none. */
    fun speech(pcm: Pcm): IntRange? {
        val s = pcm.samples
        val count = s.size / C // a last partial chunk is never the start of speech; it is kept by the margin
        if (count == 0) return null
        val level = DoubleArray(count) { Endpointer.rmsOf(s, it * C, C) }
        val floor = floor(level)
        val run = EndpointTuning.MIN_SPEECH_RUN_CHUNKS
        var first = -1
        var last = -1
        var runStart = -1
        for (i in 0 until count) {
            if (Endpointer.isSpeech(level[i], floor)) {
                if (runStart < 0) runStart = i
                if (i - runStart + 1 >= run) {
                    if (first < 0) first = runStart
                    last = i
                }
            } else {
                runStart = -1
            }
        }
        if (first < 0) return null
        return (first * C) until ((last + 1) * C)
    }

    /**
     * The floor over a whole piece. This step sees the whole piece at once, so it can do better than the live
     * [NoiseFloor], whose quietest single chunk sticks for the whole note (the 04:26 note: 0.0025, where the room was
     * 0.0041, so coffee-making counted as speech). Here the floor is the lower of the quietest one-second average and
     * the level of the quietest fifth of the chunks (the gaps between words, so a piece that is speech throughout
     * never gets a floor at speech level), leaving out the microphone's wake-up, never below the minimum.
     * A piece shorter than a second uses [NoiseFloor].
     */
    fun floor(level: DoubleArray): Double {
        val skip = EndpointTuning.FLOOR_SKIP_CHUNKS
        val w = EndpointTuning.FLOOR_WINDOW_CHUNKS
        if (level.size < skip + w) return NoiseFloor().apply { level.forEach { update(it) } }.value
        val l = level.copyOfRange(skip, level.size)
        var sum = 0.0
        for (i in 0 until w) sum += l[i]
        var lowest = sum
        for (i in w until l.size) { sum += l[i] - l[i - w]; if (sum < lowest) lowest = sum }
        val quietFifth = l.sortedArray()[l.size / 5]
        return maxOf(minOf(lowest / w, quietFifth), EndpointTuning.MIN_FLOOR)
    }

    /** [speech] widened by [keepMs] on each side, within the piece. */
    fun keep(pcm: Pcm, speech: IntRange, keepMs: Long): IntRange {
        val margin = AudioFormatConstants.msToSamples(keepMs).toInt()
        val from = (speech.first - margin).coerceAtLeast(0)
        val to = (speech.last + margin).coerceAtMost(pcm.samples.size - 1)
        return from..to
    }

    fun slice(pcm: Pcm, range: IntRange): Pcm =
        if (range.first == 0 && range.last == pcm.samples.size - 1) pcm
        else Pcm(pcm.samples.copyOfRange(range.first, range.last + 1))
}
