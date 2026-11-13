package app.stash.speech

import app.stash.audio.PauseSplitter
import app.stash.audio.Piece
import app.stash.audio.Splitter
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.Pcm

/*
 * Card 26e item 2. Writes out a long note in the pieces [Splitter] chooses, with the same loaded engine, in order,
 * and joins the words. A note that is one piece goes to the engine unchanged, so short notes give exactly the words
 * they gave before this card. Pure Kotlin.
 */
class ChunkedTranscriber(
    private val inner: Transcriber,
    private val splitter: Splitter = PauseSplitter.DEFAULT,
) : Transcriber {
    override val id: String? get() = inner.id

    override fun availability(): Availability = inner.availability()

    override fun release() = inner.release()

    override suspend fun transcribe(audio: Pcm): TranscribeResult = transcribe(audio, TranscribeOptions())

    override suspend fun transcribe(audio: Pcm, options: TranscribeOptions): TranscribeResult {
        val pieces = splitter.split(audio)
        if (pieces.size <= 1) return inner.transcribe(audio, options)
        Dbg.event(DbgComponent.TRANSCRIBE, DbgCode.SPLIT, n1 = pieces.size, n2 = pieces.count { it.forcedEnd }, ms = audio.durationMs)
        // Every piece is told the whole note's length, so the engine chooses its pack for the note, not the piece.
        val each = options.copy(noteMs = options.noteMs ?: audio.durationMs)
        val texts = ArrayList<String>(pieces.size)
        var procMs = 0L
        for (p in pieces) {
            when (val r = inner.transcribe(slice(audio, p), each)) {
                // Half a note is never stored as if it were whole: one failed piece fails the job (26f retries the note).
                is TranscribeResult.Failed -> return r
                is TranscribeResult.Ok -> { texts += r.text; procMs += r.procMs }
            }
        }
        return TranscribeResult.Ok(join(texts, pieces), procMs, pieces.size, null)
    }

    companion object {
        private fun slice(audio: Pcm, p: Piece): Pcm =
            if (p.range.first == 0 && p.samples == audio.samples.size) audio
            else Pcm(audio.samples.copyOfRange(p.range.first, p.range.first + p.samples))

        /**
         * Joins the pieces' words with one space. After a forced cut (the pieces overlap by 300 ms), when the last two
         * words of one piece equal the first two of the next (ignoring case and punctuation), they are dropped once
         * from the next. Never after a natural cut, where no sound is shared.
         */
        fun join(texts: List<String>, pieces: List<Piece>): String {
            val words = texts.map { t -> t.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList() }
            for (i in 1 until words.size) {
                if (!pieces[i - 1].forcedEnd) continue
                val before = words[i - 1]
                val after = words[i]
                if (before.size < 2 || after.size < 2) continue
                if (norm(before[before.size - 2]) == norm(after[0]) && norm(before[before.size - 1]) == norm(after[1]) && norm(after[0]).isNotEmpty()) {
                    after.removeAt(0); after.removeAt(0)
                }
            }
            return words.filter { it.isNotEmpty() }.joinToString(" ") { it.joinToString(" ") }
        }

        private fun norm(w: String) = w.lowercase().filter { it.isLetterOrDigit() || it == '\'' }
    }
}
