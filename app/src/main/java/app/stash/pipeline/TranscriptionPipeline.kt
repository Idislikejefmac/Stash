package app.stash.pipeline

import app.stash.audio.Decoder
import app.stash.data.AttachmentRepository
import app.stash.data.Clock
import app.stash.data.RecordingRepository
import app.stash.data.StashDatabase
import app.stash.data.Store
import app.stash.data.TranscribeState
import app.stash.data.TranscriptionRepository
import app.stash.data.TranscriptionTimings
import app.stash.domain.AudioFormat
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.EncodedAudio
import app.stash.domain.Pcm
import app.stash.security.AttachmentUnreadable
import app.stash.speech.Availability
import app.stash.speech.TranscribeFailure
import app.stash.speech.TranscribeOptions
import app.stash.speech.TranscribeResult
import app.stash.speech.Transcriber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import java.util.UUID

/** What one transcription pass did, so its starter can decide what to schedule next. */
data class TranscriptionPassResult(
    val storeAvailable: Boolean = true,
    /** Another pass was running in this process; this one did nothing. */
    val alreadyRunning: Boolean = false,
    /** Notes written out (first writing-outs and owner's "again" requests), whatever the engine heard. */
    val written: Int = 0,
    /** Notes are waiting and no speech model is ready: the pass stopped without counting an attempt. */
    val modelMissing: Boolean = false,
    /** Shortest wait until a note that failed is due again, or null. */
    val nextDelayMs: Long? = null,
    /** Notes a background pass left for a foreground one (killed twice in the background). */
    val leftForForeground: Int = 0,
    /** The engine was asked to write something out (so it was released afterwards). */
    val engineUsed: Boolean = false,
)

/**
 * Card 26f section A: the transcription pass. Plain Kotlin over the repositories, tested with fakes; the same
 * [runPass] is started in the app's own process (FOREGROUND) and by the WorkManager job (BACKGROUND).
 *
 * - One pass at a time in the process ([lock]); a second caller returns at once with "already running".
 * - The database only through [store]; "not available" changes nothing and ends the pass.
 * - The recording is never deleted or changed here, except that a WAV fallback is replaced by its compacted copy
 *   (16a's replaceFile: new file first, then the old one is removed). GAVE_UP deletes nothing.
 * - A run marker brackets each call into the engine, so a process that dies mid-note is detected by the next pass and
 *   counted as a kill, never as a fault in the recording.
 * - Private notes are written out like any other: the words never leave the phone (nothing here can send), and
 *   26c's `applyFirst` applies the Private check to the new words.
 */
class TranscriptionPipeline(
    private val store: () -> Store,
    private val parts: (StashDatabase) -> Parts,
    private val transcriber: () -> Transcriber,
    private val decoder: Decoder,
    /** 26b's Compactor: a WAV fallback to the small format, or null if it cannot. */
    private val compact: (ByteArray) -> EncodedAudio?,
    /** Asks the extraction pipeline for a pass (card 13's `ExtractionScheduler.enqueueNow`). */
    private val askExtraction: () -> Unit,
    private val markers: RunMarkerStore,
    private val clock: Clock = Clock.System,
    private val lock: Mutex = PROCESS_LOCK,
    private val processToken: String = PROCESS_TOKEN,
) {
    class Parts(
        val transcriptions: TranscriptionRepository,
        val attachments: AttachmentRepository,
        val recordings: RecordingRepository,
    )

    /** WAV recordings this process could not compact; tried again after a restart, never in a loop. */
    private val compactionFailed = java.util.Collections.synchronizedSet(HashSet<String>())

    suspend fun runPass(where: PassWhere): TranscriptionPassResult {
        if (!lock.tryLock()) return TranscriptionPassResult(alreadyRunning = true)
        try {
            return pass(where)
        } finally {
            lock.unlock()
        }
    }

    private class Tally(val db: StashDatabase) {
        var written = 0
        var modelMissing = false
        var next: Long? = null
        var leftForForeground = 0
        var engineUsed = false
        fun later(ms: Long) { next = next?.let { minOf(it, ms) } ?: ms }
    }

    private suspend fun pass(where: PassWhere): TranscriptionPassResult {
        val db = (store() as? Store.Ready)?.db ?: run {
            Dbg.event(DbgComponent.WORKER, DbgCode.STORE_UNAVAILABLE)
            return TranscriptionPassResult(storeAvailable = false)
        }
        val p = parts(db)
        val tally = Tally(db)
        checkForKill(db, p, tally)

        val tried = HashSet<String>()
        loop@ while (true) {
            val now = clock.now()
            val due = ArrayList<Pair<String, Boolean>>() // capture id, true for a first writing-out
            for (c in db.transcriptions().waitingCaptures()) {
                if (c.id in tried) continue
                val wait = TranscriptionPolicy.waitMs(c.transcribeState, c.transcribeAttempts, c.updatedAt, now) ?: continue
                if (wait > 0) tally.later(wait) else due += c.id to true
            }
            for (id in db.transcriptions().doneCaptureIds()) {
                if (id !in tried && p.transcriptions.wantsNewOffer(id)) due += id to false
            }
            if (due.isEmpty()) break
            for ((id, first) in due) {
                tried += id
                val c = db.captures().get(id) ?: continue
                if (c.deletedAt != null) continue
                val rows = rowsOf(p, id)
                if (first && where == PassWhere.BACKGROUND && TranscriptionPolicy.skipInBackground(rows, c.transcribeAttempts, true)) {
                    tally.leftForForeground++; continue
                }
                if (!first) {
                    if (TranscriptionPolicy.abandonAgainRequest(rows)) continue
                    if (where == PassWhere.BACKGROUND && TranscriptionPolicy.skipInBackground(rows, 0, false)) { tally.leftForForeground++; continue }
                }
                val t = transcriber()
                if (t.availability() != Availability.Ready) {
                    // Leave every note as it is; count nothing; a pass runs again when a pack is imported.
                    tally.modelMissing = true
                    Dbg.event(DbgComponent.TRANSCRIBE, DbgCode.MODEL_MISSING, n1 = due.size)
                    break@loop
                }
                if (!writeOut(p, t, id, first, where, tally)) break@loop
            }
        }
        if (tally.engineUsed) runCatching { transcriber().release() }
        compactOne(db, p)
        tally.next?.let { Dbg.event(DbgComponent.TRANSCRIBE, DbgCode.RESCHEDULED, n1 = it) }
        return TranscriptionPassResult(
            storeAvailable = true, written = tally.written, modelMissing = tally.modelMissing, nextDelayMs = tally.next,
            leftForForeground = tally.leftForForeground, engineUsed = tally.engineUsed,
        )
    }

    private suspend fun rowsOf(p: Parts, captureId: String) =
        p.transcriptions.history(captureId).map { TranscriptionPolicy.Row(it.status, it.error) }

    /** A marker this process did not write: the process died while the engine was on that note (item 3). */
    private suspend fun checkForKill(db: StashDatabase, p: Parts, tally: Tally) {
        when (val v = KillCheck.judge(markers.read(), processToken)) {
            KillCheck.Verdict.None -> return
            KillCheck.Verdict.OwnLeftover -> { markers.clear(); return }
            is KillCheck.Verdict.Killed -> {
                val c = db.captures().get(v.captureId)
                if (c != null && c.deletedAt == null) {
                    val first = c.transcribeState == TranscribeState.PENDING || c.transcribeState == TranscribeState.RETRY
                    val code = TranscriptionPolicy.killCode(v.where)
                    val fgKills = if (v.where == PassWhere.FOREGROUND) {
                        TranscriptionPolicy.kills(rowsOf(p, c.id), c.transcribeAttempts, first, TranscriptionPolicy.KILLED_FG) + 1
                    } else 0
                    val info = p.recordings.info(c.id)
                    Dbg.event(DbgComponent.TRANSCRIBE, DbgCode.KILLED, n1 = info?.durationMs, n2 = v.where.eventCode, refId = info?.attachmentId)
                    val state = p.transcriptions.recordFailure(
                        c.id, engineName(), code, retryable = true, countAttempt = first,
                        giveUp = { a -> TranscriptionPolicy.giveUp(a) || fgKills >= TranscriptionPolicy.FOREGROUND_KILLS_BEFORE_GIVING_UP },
                    )
                    afterFailure(state, c.id, info?.attachmentId, tally)
                }
                markers.clear()
            }
        }
    }

    private fun engineName(): String = runCatching { transcriber().id }.getOrNull() ?: "none"

    /** Returns false when the pass must stop (the engine says its model is gone). */
    private suspend fun writeOut(p: Parts, t: Transcriber, captureId: String, first: Boolean, where: PassWhere, tally: Tally): Boolean {
        val info = p.recordings.info(captureId)
        if (info == null) {
            permanent(p, captureId, null, first, TranscriptionPolicy.NO_RECORDING, TranscriptionPolicy.EVENT_NO_RECORDING, tally)
            return true
        }
        val bytes = try {
            p.attachments.openRecording(info.attachmentId).use { it.readBytes() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AttachmentUnreadable) {
            permanent(p, captureId, info.attachmentId, first, TranscriptionPolicy.UNREADABLE, TranscriptionPolicy.EVENT_UNREADABLE_BASE + e.reason.code, tally)
            return true
        } catch (e: Exception) {
            permanent(p, captureId, info.attachmentId, first, TranscriptionPolicy.UNREADABLE, TranscriptionPolicy.EVENT_UNREADABLE_BASE, tally)
            return true
        }
        val pcm: Pcm = try {
            decoder.decode(bytes, info.mime)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            failed(p, captureId, info.attachmentId, first, TranscribeResult.Failed(TranscribeFailure.OUT_OF_MEMORY, retryable = true), tally)
            return true
        } catch (e: Throwable) {
            permanent(p, captureId, info.attachmentId, first, TranscriptionPolicy.UNDECODABLE, TranscriptionPolicy.EVENT_UNDECODABLE, tally)
            return true
        }

        markers.write(RunMarker(captureId, clock.now(), where, processToken))
        Dbg.event(DbgComponent.TRANSCRIBE, DbgCode.STARTED, n1 = pcm.durationMs, n2 = where.eventCode, refId = info.attachmentId)
        tally.engineUsed = true
        val result = try {
            t.transcribe(pcm, TranscribeOptions(place = where.runPlace, noteMs = pcm.durationMs))
        } catch (e: CancellationException) {
            throw e // the job was stopped: not a kill and not a failure; the note stays as it is
        } catch (e: OutOfMemoryError) {
            TranscribeResult.Failed(TranscribeFailure.OUT_OF_MEMORY, retryable = true)
        } catch (e: Exception) {
            TranscribeResult.Failed(TranscribeFailure.ENGINE_ERROR, retryable = true)
        } finally {
            markers.clear()
        }

        when (result) {
            is TranscribeResult.Ok -> {
                val engine = t.id ?: "unknown"
                val timings = TranscriptionTimings(audioMs = pcm.durationMs, procMs = result.procMs, chunks = result.chunks)
                if (first) {
                    if (p.transcriptions.applyFirst(captureId, engine, result.text, timings) == TranscriptionRepository.First.WORDS) {
                        // The words are the note's now: read them exactly as a typed or dictated note is read.
                        runCatching { askExtraction() }
                    }
                } else {
                    p.transcriptions.offer(captureId, engine, result.text, timings)
                }
                tally.written++
                Dbg.event(DbgComponent.TRANSCRIBE, DbgCode.OK, n1 = result.chunks, n2 = result.text.trim().length, refId = info.attachmentId, ms = result.procMs)
            }
            is TranscribeResult.Failed -> {
                if (result.code == TranscribeFailure.MODEL_MISSING || result.code == TranscribeFailure.NO_ENGINE) {
                    // The pack went away between the check and the call: not the note's fault, no attempt.
                    tally.modelMissing = true
                    Dbg.event(DbgComponent.TRANSCRIBE, DbgCode.MODEL_MISSING, n1 = 1)
                    return false
                }
                failed(p, captureId, info.attachmentId, first, result, tally)
            }
        }
        return true
    }

    private suspend fun failed(p: Parts, captureId: String, attachmentId: String?, first: Boolean, f: TranscribeResult.Failed, tally: Tally) {
        Dbg.event(DbgComponent.TRANSCRIBE, DbgCode.FAILED, n1 = f.code.code, n2 = if (f.retryable) 1 else 0, refId = attachmentId)
        val state = p.transcriptions.recordFailure(
            captureId, engineName(), f.code.name, retryable = f.retryable, countAttempt = first, giveUp = TranscriptionPolicy::giveUp,
        )
        afterFailure(state, captureId, attachmentId, tally)
    }

    /** Retrying cannot help (no recording, unreadable or undecodable file): GAVE_UP at once. Nothing is deleted. */
    private suspend fun permanent(p: Parts, captureId: String, attachmentId: String?, first: Boolean, code: String, eventCode: Int, tally: Tally) {
        Dbg.event(DbgComponent.TRANSCRIBE, DbgCode.FAILED, n1 = eventCode, n2 = 0, refId = attachmentId)
        val state = p.transcriptions.recordFailure(captureId, engineName(), code, retryable = false, countAttempt = first)
        afterFailure(state, captureId, attachmentId, tally)
    }

    private suspend fun afterFailure(state: TranscribeState?, captureId: String, attachmentId: String?, tally: Tally) {
        when (state) {
            TranscribeState.GAVE_UP -> Dbg.event(DbgComponent.TRANSCRIBE, DbgCode.GAVE_UP, n1 = attempts(tally, captureId), refId = attachmentId)
            TranscribeState.RETRY -> attempts(tally, captureId)?.let { tally.later(TranscriptionPolicy.backoffMs(it)) }
            else -> {}
        }
    }

    private suspend fun attempts(tally: Tally, captureId: String): Int? = tally.db.captures().get(captureId)?.transcribeAttempts

    /** Item 4: one recording kept as WAV (26b's fallback) is compacted per pass; its file is replaced, never lost. */
    private suspend fun compactOne(db: StashDatabase, p: Parts) {
        val ids = db.transcriptions().wavRecordingIds(AudioFormat.WAV.mime, compactionFailed.size + 1)
        val id = ids.firstOrNull { it !in compactionFailed } ?: return
        try {
            val wav = p.attachments.openRecording(id).use { it.readBytes() }
            val small = compact(wav)
            if (small == null || small.format == AudioFormat.WAV || !p.recordings.replaceFile(id, small)) compactionFailed += id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            compactionFailed += id
        }
    }

    companion object {
        /** Only one transcription at a time in the whole process (card 26f item 1). */
        val PROCESS_LOCK = Mutex()

        /** Written into every run marker, so a pass can tell a marker of a dead process from its own. */
        val PROCESS_TOKEN: String = UUID.randomUUID().toString()
    }
}
