package app.stash.domain

import kotlin.math.sqrt

/** Setting "End a voice note after a pause of" (card 26a item 5). Read by the capture screen in card 26g. */
enum class EndPause(
    val pauseMs: Long?,
    val label: String,
    /** 2026-11-16: the line under the meter while recording, so nobody has to guess whether the note ends by itself. */
    val recordingHint: String,
) {
    SHORT(2_500L, "2.5 seconds", "Stops by itself when you pause."),
    LONG(5_000L, "5 seconds", "Stops by itself when you pause."),
    ONLY_DONE(null, "Only when I tap Done", "Tap Done when you've finished.");

    companion object {
        fun parse(s: String?): EndPause = entries.firstOrNull { it.name == s } ?: SHORT
    }
}

enum class EndEvent { WARN_LIMIT, STOP_PAUSE, STOP_NOTHING_HEARD, STOP_LIMIT }

/** Every threshold of the level rule, in one place. Levels are RMS of a 20 ms chunk, 0 to 1 of full scale. */
object EndpointTuning {
    /** A chunk is speech when its level is at least this many times the noise floor... */
    const val SPEECH_OVER_FLOOR = 3.0
    /** ...and at least this absolute level (about -44 dBFS), so a digitally silent room has no hair trigger. */
    const val MIN_SPEECH_LEVEL = 0.006
    /** The floor never falls below this, for the same reason. */
    const val MIN_FLOOR = 0.0005
    /** The floor is the quietest one second seen so far: the lowest average of 50 chunks. */
    const val FLOOR_WINDOW_CHUNKS = 50
    /** The microphone's wake-up (about 70 ms of near-silence on the S25 Edge) is left out of the floor: 100 ms. */
    const val FLOOR_SKIP_CHUNKS = 5
    const val HEARD_SPEECH_MS = 300L
    /** The meter shows level / METER_FULL, capped at 1. */
    const val METER_FULL = 0.25
    /**
     * Card 26g (check C2's finding: keys tapped nearby kept a note open for 21 s). A sound counts as speech, and ends
     * a pause, only once it has lasted this many chunks in a row (60 ms). A key tap or a click is one or two chunks; a
     * spoken syllable is longer. Measured on the 15 corpus recordings (docs/spike25/audio): the longest gap inside
     * speech moves from 1.58 s to 1.78 s at worst, far from the 2.5 s pause. Nothing is trimmed either way.
     */
    const val MIN_SPEECH_RUN_CHUNKS = 3
}

/**
 * The only silence detection in the feature (card 26a item 3). It decides when to stop, never what to transcribe, and
 * nothing is trimmed. A sound must last 60 ms to count as speech (card 26g), so a key tap does not keep a note open. Time is counted in samples, so it needs no clock and a test can run three minutes in
 * milliseconds. Fed 20 ms chunks; returns an event on the chunk where one fires, and is silent after a stop event.
 */
class Endpointer(
    private val pause: EndPause = EndPause.SHORT,
    private val maxNoteMs: Long = AudioFormatConstants.MAX_NOTE_MS,
    private val nothingHeardMs: Long = AudioFormatConstants.NOTHING_HEARD_MS,
) {
    private var samples = 0L
    private var speechMs = 0L
    private var quietRunMs = 0L
    /** A sound that is loud enough but has not yet lasted [EndpointTuning.MIN_SPEECH_RUN_CHUNKS]: its chunks and their time. */
    private var loudRunChunks = 0
    private var loudRunMs = 0L
    private var warned = false
    private val floorTracker = NoiseFloor()

    var stopped: EndEvent? = null
        private set

    /** 0 to 1, for a meter; follows the last chunk. */
    var level = 0.0
        private set

    val heardSpeech: Boolean get() = speechMs >= EndpointTuning.HEARD_SPEECH_MS
    val elapsedMs: Long get() = AudioFormatConstants.samplesToMs(samples)
    val noiseFloor: Double get() = floorTracker.value

    fun feed(chunk: ShortArray, len: Int = chunk.size): EndEvent? {
        if (stopped != null || len <= 0) return null
        val rms = rmsOf(chunk, len)
        val chunkMs = AudioFormatConstants.samplesToMs(len.toLong()).coerceAtLeast(1L)
        level = (rms / EndpointTuning.METER_FULL).coerceIn(0.0, 1.0)
        samples += len

        floorTracker.update(rms)
        if (isSpeech(rms, noiseFloor)) {
            // Loud enough. It is speech once it has lasted long enough; then the whole run counts. Until then the
            // pause neither grows nor ends, so a word that starts just before the pause limit is not cut off.
            loudRunChunks++
            loudRunMs += chunkMs
            if (loudRunChunks >= EndpointTuning.MIN_SPEECH_RUN_CHUNKS) { speechMs += loudRunMs; loudRunMs = 0; quietRunMs = 0 }
        } else {
            // A tap or a click that ended before it could be speech is part of the pause it interrupted.
            quietRunMs += loudRunMs + chunkMs
            loudRunChunks = 0
            loudRunMs = 0
        }

        val now = elapsedMs
        val limit = pause.pauseMs
        val end = when {
            now >= maxNoteMs -> EndEvent.STOP_LIMIT
            heardSpeech && limit != null && quietRunMs >= limit -> EndEvent.STOP_PAUSE
            !heardSpeech && now >= nothingHeardMs -> EndEvent.STOP_NOTHING_HEARD
            else -> null
        }
        if (end != null) { stopped = end; return end }
        if (!warned && now >= maxNoteMs - AudioFormatConstants.WARN_BEFORE_LIMIT_MS) { warned = true; return EndEvent.WARN_LIMIT }
        return null
    }

    companion object {
        fun rmsOf(chunk: ShortArray, len: Int): Double = rmsOf(chunk, 0, len)

        /** RMS of [len] samples of [samples] from [from], 0 to 1 of full scale. Card 26e's PauseSplitter shares it. */
        fun rmsOf(samples: ShortArray, from: Int, len: Int): Double {
            var sum = 0.0
            for (i in from until from + len) { val v = samples[i] / 32768.0; sum += v * v }
            return sqrt(sum / len)
        }

        /** The level rule: a chunk is speech when it is clearly above the noise floor and above an absolute minimum. */
        fun isSpeech(rms: Double, floor: Double): Boolean =
            rms >= maxOf(floor * EndpointTuning.SPEECH_OVER_FLOOR, EndpointTuning.MIN_SPEECH_LEVEL)
    }
}

/**
 * The noise floor as the level rule tracks it: the quietest one-second average of chunk levels seen so far (the
 * quietest single chunk until a full second has been seen), never below [EndpointTuning.MIN_FLOOR]. Shared by the
 * [Endpointer] (card 26a) and card 26e's PauseSplitter, so both call the same sound "speech".
 *
 * The first [EndpointTuning.FLOOR_SKIP_CHUNKS] chunks are left out (2026-11-16): on the S25 Edge every recording
 * starts with about 70 ms of near-silence while the microphone wakes, which pinned the floor at its minimum for the
 * whole note, so ordinary room noise counted as speech and the pause stop came late or never. Checked against the
 * owner's 38 real recordings: no note stops earlier than it did; in noisy places the last "speech" moves about 1 s
 * nearer the last word.
 */
class NoiseFloor {
    private var floor = Double.MAX_VALUE
    private val window = DoubleArray(EndpointTuning.FLOOR_WINDOW_CHUNKS)
    private var seen = 0
    private var windowFill = 0
    private var windowPos = 0
    private var windowSum = 0.0
    private var minChunkSoFar = Double.MAX_VALUE

    /** 0 until the first chunk after the skipped start. */
    val value: Double get() = if (floor == Double.MAX_VALUE) 0.0 else floor

    fun update(rms: Double) {
        if (seen++ < EndpointTuning.FLOOR_SKIP_CHUNKS) return
        if (rms < minChunkSoFar) minChunkSoFar = rms
        if (windowFill < window.size) {
            window[windowFill++] = rms; windowSum += rms
        } else {
            windowSum += rms - window[windowPos]; window[windowPos] = rms
            windowPos = (windowPos + 1) % window.size
        }
        // Until a full second has been seen, the quietest single chunk stands in for the floor.
        val candidate = if (windowFill < window.size) minChunkSoFar else windowSum / window.size
        if (candidate < floor) floor = candidate
        if (floor < EndpointTuning.MIN_FLOOR) floor = EndpointTuning.MIN_FLOOR
    }
}

/** Android hands a silenced app zeros. After [DECIDE_AFTER_MS] of sound that is all exactly zero, report BLOCKED. */
class MicHealth {
    enum class Status { UNKNOWN, OK, BLOCKED }

    private var samples = 0L
    private var sawNonZero = false

    fun feed(chunk: ShortArray, len: Int = chunk.size) {
        if (!sawNonZero) {
            for (i in 0 until len) if (chunk[i].toInt() != 0) { sawNonZero = true; break }
        }
        samples += len
    }

    val status: Status get() = when {
        sawNonZero -> Status.OK
        AudioFormatConstants.samplesToMs(samples) >= DECIDE_AFTER_MS -> Status.BLOCKED
        else -> Status.UNKNOWN
    }

    companion object { const val DECIDE_AFTER_MS = 1_500L }
}

enum class NoteVerdict { KEEP, EMPTY }

object RecordingVerdict {
    /**
     * The peak of a very quiet room on the phone is not measured yet (card 26a phone checks), so this is a provisional
     * constant: 40 of 32767 is about -58 dBFS, below any real sentence and above digital silence.
     */
    const val EMPTY_PEAK = 40

    /** EMPTY only when the loudest sample is below [EMPTY_PEAK] and no speech was seen. When in doubt, keep it. */
    fun of(pcm: Pcm, heardSpeech: Boolean, loudest: Int = pcm.peak()): NoteVerdict =
        if (loudest < EMPTY_PEAK && !heardSpeech) NoteVerdict.EMPTY else NoteVerdict.KEEP
}
