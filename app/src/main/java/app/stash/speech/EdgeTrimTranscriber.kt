package app.stash.speech

import app.stash.audio.QuietEdges
import app.stash.domain.AudioFormatConstants
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.Pcm

/*
 * 2026-11-16. Sits between card 26e's ChunkedTranscriber and the engine, so every piece goes through it. Long quiet
 * before the first or after the last speech is trimmed to [QuietEdges.KEEP_MS] (the engine wrote out nothing for a
 * note that ended in 6 s of quiet). If the engine still writes out nothing although the piece has speech, it tries
 * once more on the speech with only [QuietEdges.TIGHT_KEEP_MS] around it. A piece with no speech by the level rule goes
 * to the engine unchanged, so the engine, not this rule, has the last word on "no words". Pure Kotlin.
 *
 * Events (numbers only): TRANSCRIBE TRIMMED (n1 ms cut from the start, n2 ms cut from the end); TRANSCRIBE RETRIED
 * (n1 ms of audio in the retry, n2 1 if the retry found words).
 */
class EdgeTrimTranscriber(private val inner: Transcriber) : Transcriber {
    override val id: String? get() = inner.id

    override fun availability(): Availability = inner.availability()

    override fun release() = inner.release()

    override suspend fun transcribe(audio: Pcm): TranscribeResult = transcribe(audio, TranscribeOptions())

    override suspend fun transcribe(audio: Pcm, options: TranscribeOptions): TranscribeResult {
        val speech = QuietEdges.speech(audio) ?: return inner.transcribe(audio, options)
        // The engine chooses its pack by the note's length, not by the trimmed length.
        val opts = options.copy(noteMs = options.noteMs ?: audio.durationMs)
        val wide = QuietEdges.keep(audio, speech, QuietEdges.KEEP_MS)
        val first = inner.transcribe(trimmed(audio, wide), opts)
        if (first !is TranscribeResult.Ok || first.text.isNotBlank()) return first
        val tight = QuietEdges.keep(audio, speech, QuietEdges.TIGHT_KEEP_MS)
        if (tight == wide) return first
        val again = inner.transcribe(trimmed(audio, tight), opts)
        Dbg.event(
            DbgComponent.TRANSCRIBE, DbgCode.RETRIED,
            n1 = AudioFormatConstants.samplesToMs(tight.last - tight.first + 1L),
            n2 = if (again is TranscribeResult.Ok && again.text.isNotBlank()) 1 else 0,
        )
        return when (again) {
            is TranscribeResult.Ok -> again.copy(procMs = first.procMs + again.procMs)
            // The first try did finish: a failure on the retry does not turn a finished note into a failed one.
            is TranscribeResult.Failed -> first
        }
    }

    private fun trimmed(audio: Pcm, range: IntRange): Pcm {
        val out = QuietEdges.slice(audio, range)
        if (out !== audio) {
            Dbg.event(
                DbgComponent.TRANSCRIBE, DbgCode.TRIMMED,
                n1 = AudioFormatConstants.samplesToMs(range.first.toLong()),
                n2 = AudioFormatConstants.samplesToMs((audio.samples.size - 1 - range.last).toLong()),
            )
        }
        return out
    }
}
