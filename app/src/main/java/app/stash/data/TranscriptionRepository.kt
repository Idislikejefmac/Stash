package app.stash.data

import androidx.room.withTransaction
import app.stash.domain.EncodedAudio
import app.stash.domain.SensitiveReason
import app.stash.domain.Sensitivity

/*
 * Card 26c. For a recorded note the recording is what he said; the words are the app's reading of it and can be
 * redone. `capture.raw_text` is the first writing-out, written once (D36 stays literally true); every writing-out is a
 * `transcription` row; `capture.text` changes only through a named action that leaves a revision.
 */

/** How long a writing-out took, for the event log and Settings. Numbers only. */
data class TranscriptionTimings(val audioMs: Long? = null, val procMs: Long? = null, val chunks: Int? = null)

/** A small persisted set of capture ids (like the "read it again" list). The app keeps it in preferences; tests in memory. */
interface CaptureIdSet {
    fun add(captureId: String)
    fun remove(captureId: String): Boolean
    operator fun contains(captureId: String): Boolean
}

class MemoryCaptureIdSet : CaptureIdSet {
    private val ids = mutableSetOf<String>()
    @Synchronized override fun add(captureId: String) { ids += captureId }
    @Synchronized override fun remove(captureId: String) = ids.remove(captureId)
    @Synchronized override fun contains(captureId: String) = captureId in ids
}

/**
 * Every writing-out of a note's recording, and the ways one becomes the note's words (card 26c section C). Each
 * public function is one database transaction. The owner's corrections ([correct]) and his "always private" words are
 * passed in, as `CaptureEntry` and `DirectSink` take them.
 */
class TranscriptionRepository(
    private val db: StashDatabase,
    private val index: SearchIndex,
    private val clock: Clock = Clock.System,
    private val correct: suspend (String) -> String = { it },
    private val alwaysPrivateWords: suspend () -> List<String> = { emptyList() },
    /** Notes that are written out already and for which the owner asked "write it out again" (item 13). */
    private val askedAgain: CaptureIdSet = MemoryCaptureIdSet(),
) {
    private val revisions = RevisionWriter(db, clock)

    enum class First {
        /** There were words: they are the note's words now, and it will be read. */
        WORDS,
        /** The engine heard nothing: the note is written out, with no words. */
        NO_WORDS,
        /** Not waiting for a first writing-out (already done, or no such note): nothing changed. */
        REFUSED,
    }

    /**
     * The first writing-out (item 9). Allowed only while `transcribe_state` is PENDING or RETRY. Inserts the row as
     * CURRENT. With words: `raw_text` becomes the engine's words (only if it is blank), `text` the corrected words with
     * any spoken "private note" marker taken out; the Private check may set `sensitive` and never clears it; the note
     * goes to extract PENDING with no attempts; the search index follows. Always ends `transcribe_state = DONE`.
     */
    suspend fun applyFirst(captureId: String, engine: String, text: String, timings: TranscriptionTimings = TranscriptionTimings()): First {
        val words = text.trim()
        val prepared = if (words.isNotEmpty()) prepare(words) else null
        return db.withTransaction {
            val c = db.captures().get(captureId) ?: return@withTransaction First.REFUSED
            if (c.transcribeState != TranscribeState.PENDING && c.transcribeState != TranscribeState.RETRY) return@withTransaction First.REFUSED
            db.transcriptions().moveStatus(captureId, TranscriptionStatus.CURRENT, TranscriptionStatus.REPLACED)
            db.transcriptions().insert(row(c.id, engine, text, TranscriptionStatus.CURRENT, timings))
            if (prepared == null) {
                db.captures().update(c.copy(transcribeState = TranscribeState.DONE).stamped(clock))
                return@withTransaction First.NO_WORDS
            }
            val newText = prepared.textFor(c)
            val updated = c.copy(
                rawText = if (c.rawText.isBlank()) text else c.rawText,
                text = newText,
                transcribeState = TranscribeState.DONE,
                extractState = ExtractState.PENDING, extractAttempts = 0,
            ).withPrivate(prepared.reason).stamped(clock)
            db.captures().update(updated)
            if (updated.deletedAt == null) index.indexCapture(updated)
            First.WORDS
        }
    }

    /**
     * A writing-out failed (item 10): a FAILED row with the reason [code] and no words. While the note waits for its
     * first writing-out, it goes to RETRY (one more attempt when [countAttempt]), or to GAVE_UP when the failure cannot
     * be retried or [giveUp] says so (card 26f owns that policy). The recording is untouched. Returns the state now.
     */
    suspend fun recordFailure(
        captureId: String,
        engine: String,
        code: String,
        retryable: Boolean,
        countAttempt: Boolean,
        giveUp: (attempts: Int) -> Boolean = { false },
    ): TranscribeState? = db.withTransaction {
        val c = db.captures().get(captureId) ?: return@withTransaction null
        db.transcriptions().insert(
            row(c.id, engine, "", TranscriptionStatus.FAILED, TranscriptionTimings()).copy(error = safeCode(code)),
        )
        if (c.transcribeState != TranscribeState.PENDING && c.transcribeState != TranscribeState.RETRY) return@withTransaction c.transcribeState
        val attempts = c.transcribeAttempts + if (countAttempt) 1 else 0
        val state = if (!retryable || giveUp(attempts)) TranscribeState.GAVE_UP else TranscribeState.RETRY
        db.captures().update(c.copy(transcribeState = state, transcribeAttempts = attempts).stamped(clock))
        state
    }

    /**
     * "Write it out again" on a note that already has words (item 11): the row goes in as OFFERED with
     * `asked_by_owner`, or as REPLACED at once when the corrected new words equal `capture.text` (or there are none).
     * An earlier offer still open is closed (REPLACED). `capture.text` is not touched. Returns the new row, or null if
     * there is no such note.
     */
    suspend fun offer(captureId: String, engine: String, text: String, timings: TranscriptionTimings = TranscriptionTimings()): TranscriptionEntity? {
        val words = text.trim()
        val prepared = if (words.isNotEmpty()) prepare(words) else null
        val out = db.withTransaction {
            val c = db.captures().get(captureId) ?: return@withTransaction null
            val same = prepared == null || prepared.textFor(c) == c.text
            db.transcriptions().moveStatus(captureId, TranscriptionStatus.OFFERED, TranscriptionStatus.REPLACED)
            val r = row(c.id, engine, text, if (same) TranscriptionStatus.REPLACED else TranscriptionStatus.OFFERED, timings).copy(askedByOwner = true)
            db.transcriptions().insert(r)
            r
        }
        if (out != null) askedAgain.remove(captureId)
        return out
    }

    /**
     * The owner chose this writing-out (item 12): the CURRENT row becomes REPLACED and this one CURRENT; `text`
     * becomes its corrected words (the Private check as in [applyFirst], never clearing); a `TEXT_REWRITTEN` revision
     * holds the old and new text; the note will be read again. `raw_text` is not changed. Allowed for an OFFERED row,
     * or an earlier (REPLACED) one with words. Returns false if refused.
     */
    suspend fun use(transcriptionId: String): Boolean {
        val t = db.transcriptions().get(transcriptionId) ?: return false
        if (t.status != TranscriptionStatus.OFFERED && t.status != TranscriptionStatus.REPLACED) return false
        if (t.text.isBlank()) return false
        val prepared = prepare(t.text.trim())
        return db.withTransaction {
            val row = db.transcriptions().get(transcriptionId) ?: return@withTransaction false
            if (row.status != TranscriptionStatus.OFFERED && row.status != TranscriptionStatus.REPLACED) return@withTransaction false
            val c = db.captures().get(row.captureId) ?: return@withTransaction false
            db.transcriptions().moveStatus(c.id, TranscriptionStatus.CURRENT, TranscriptionStatus.REPLACED)
            db.transcriptions().setStatus(row.id, TranscriptionStatus.CURRENT)
            val newText = prepared.textFor(c)
            var updated = c.withPrivate(prepared.reason)
            if (newText != c.text) {
                updated = updated.copy(text = newText, extractState = ExtractState.PENDING, extractAttempts = 0)
                revisions.write(RevisionKind.TEXT_REWRITTEN, captureId = c.id, old = c.text, new = newText)
            }
            if (updated != c) {
                updated = updated.stamped(clock)
                db.captures().update(updated)
                if (updated.deletedAt == null) index.indexCapture(updated)
            }
            true
        }
    }

    /** The owner kept his words (item 12): an OFFERED row becomes REPLACED; nothing else changes. */
    suspend fun keepMine(transcriptionId: String): Boolean = db.withTransaction {
        val t = db.transcriptions().get(transcriptionId) ?: return@withTransaction false
        if (t.status != TranscriptionStatus.OFFERED) return@withTransaction false
        db.transcriptions().setStatus(t.id, TranscriptionStatus.REPLACED)
        true
    }

    enum class AskAgain {
        /** It had given up: it waits for a first writing-out again. */
        WAITING_AGAIN,
        /** It is written out: the next writing-out will be offered beside his words. */
        OFFER_NEXT,
        /** No such note, no recording, or it is already waiting. */
        NOTHING,
    }

    /** Item 13: GAVE_UP goes back to PENDING with no attempts; DONE is marked as wanting a new offer. */
    suspend fun askAgain(captureId: String): AskAgain {
        val r = db.withTransaction {
            val c = db.captures().get(captureId) ?: return@withTransaction AskAgain.NOTHING
            when (c.transcribeState) {
                TranscribeState.GAVE_UP -> {
                    db.captures().update(c.copy(transcribeState = TranscribeState.PENDING, transcribeAttempts = 0).stamped(clock))
                    AskAgain.WAITING_AGAIN
                }
                TranscribeState.DONE -> if (db.attachments().audioForCapture(captureId).isEmpty()) AskAgain.NOTHING else AskAgain.OFFER_NEXT
                else -> AskAgain.NOTHING
            }
        }
        if (r == AskAgain.OFFER_NEXT) askedAgain.add(captureId)
        return r
    }

    /** Did the owner ask for this written-out note to be written out again (and no offer has been made since)? */
    fun wantsNewOffer(captureId: String): Boolean = captureId in askedAgain

    /** Item 14: every writing-out of the note, newest first, for the note's page. */
    suspend fun history(captureId: String): List<TranscriptionEntity> = db.transcriptions().forCapture(captureId)

    // ---------------------------------------------------------------------------------------------------------

    private class Prepared(val corrected: String, val stripped: String, val reason: SensitiveReason?) {
        /** The words the note would use, for [c]: the marker is taken out when the note is or becomes Private. */
        fun textFor(c: CaptureEntity): String =
            if ((reason != null || c.sensitive) && stripped.isNotBlank()) stripped else corrected
    }

    /** Correct the engine's words and check them for Private, as `CaptureEntry` and `DirectSink` do for dictated words. */
    private suspend fun prepare(words: String): Prepared {
        val corrected = correct(words).trim()
        val list = alwaysPrivateWords()
        val reason = Sensitivity.check(corrected, list) ?: Sensitivity.check(words, list)
        return Prepared(corrected, Sensitivity.stripMarker(corrected), reason)
    }

    /** May set Private; never clears it, and never replaces the reason the note already has. */
    private fun CaptureEntity.withPrivate(reason: SensitiveReason?): CaptureEntity =
        if (reason != null && !sensitive) copy(sensitive = true, sensitiveReason = reason.code) else this

    private suspend fun row(captureId: String, engine: String, text: String, status: TranscriptionStatus, t: TranscriptionTimings) =
        TranscriptionEntity(
            id = Ids.new(), captureId = captureId,
            attachmentId = db.attachments().audioForCapture(captureId).firstOrNull()?.id,
            at = clock.now(), engine = engine, text = text, status = status,
            audioMs = t.audioMs, procMs = t.procMs, chunks = t.chunks,
        )

    companion object {
        /** A reason code is letters, digits and `_`, at most 40 characters: never words. */
        fun safeCode(code: String): String = code.filter { it.isLetterOrDigit() && it.code < 128 || it == '_' }.take(40).ifEmpty { "UNKNOWN" }
    }
}

/**
 * A note's recording (card 26c section D), reached as `Repository.recordings`. Screens get [RecordingInfo] only; the
 * bytes are opened through `AttachmentRepository.openRecording`, by the audio and pipeline packages alone.
 */
class RecordingRepository(
    private val db: StashDatabase,
    private val attachments: () -> AttachmentRepository,
) {
    suspend fun info(captureId: String): RecordingInfo? = db.attachments().recordingInfo(captureId).firstOrNull()

    /** Count, bytes and total length of every recording kept (card 26j shows them). */
    suspend fun totals(): AudioTotals = db.attachments().audioTotals()

    enum class Removal {
        REMOVED,
        /** The note has no recording. */
        NO_RECORDING,
        /**
         * The note has no words: removing the recording would leave a note with neither. The caller deletes the note
         * instead.
         */
        NO_WORDS,
    }

    /**
     * Item 15: remove the note's recording and keep its words. 16a's remove does the work (row, tombstone and an
     * `AUDIO_REMOVED` revision in one transaction, then the file). `transcription` rows and the words stay.
     */
    suspend fun remove(captureId: String): Removal {
        val audio = db.attachments().audioForCapture(captureId).firstOrNull() ?: return Removal.NO_RECORDING
        return when (attachments().removeIf(audio.id) { _, c -> c != null && c.text.isNotBlank() }) {
            AttachmentRepository.Removal.REMOVED -> Removal.REMOVED
            AttachmentRepository.Removal.NO_ROW -> Removal.NO_RECORDING
            AttachmentRepository.Removal.REFUSED -> Removal.NO_WORDS
        }
    }

    /**
     * Item 16: the recording gets new bytes (26b's compaction of a WAV fallback), under a new file name and key; the old
     * file is deleted afterwards. False if [attachmentId] is not a recording.
     */
    suspend fun replaceFile(attachmentId: String, encoded: EncodedAudio): Boolean {
        val a = db.attachments().get(attachmentId) ?: return false
        if (a.kind != AttachmentKind.AUDIO) return false
        return attachments().replaceFile(attachmentId, encoded.bytes, encoded.mime)
    }
}
