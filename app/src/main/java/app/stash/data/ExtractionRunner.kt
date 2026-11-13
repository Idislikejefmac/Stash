package app.stash.data

import androidx.room.withTransaction
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.EXTRACTION_SCHEMA_VERSION
import app.stash.domain.ExtractionChecks
import app.stash.domain.CheckedRecord
import app.stash.domain.ItemRecordRef
import app.stash.domain.ProjectionContext
import app.stash.domain.Projector
import app.stash.domain.RecordsFromPictures
import app.stash.model.ExtractContext
import app.stash.model.ExtractResult
import app.stash.model.KnownSubject
import app.stash.model.LocalExtractor
import app.stash.model.OpenTaskRef
import java.time.Instant
import java.time.ZoneId

/** When to try a capture again after a hosted model could not be reached (card 13). Pure. */
object RetryPolicy {
    /** After the 1st, 2nd, 3rd, 4th and 5th failed attempt: 1 minute, 10 minutes, 1 hour, 6 hours, 24 hours. */
    val BACK_OFF_MS = listOf(60_000L, 600_000L, 3_600_000L, 21_600_000L, 86_400_000L)

    /** The sixth failure gives up: the first try and five retries. */
    val MAX_FAILURES = BACK_OFF_MS.size + 1

    fun backoffMs(failures: Int): Long = BACK_OFF_MS[(failures - 1).coerceIn(0, BACK_OFF_MS.size - 1)]

    fun stateAfterFailure(failures: Int): ExtractState = if (failures >= MAX_FAILURES) ExtractState.GAVE_UP else ExtractState.RETRY
}

/**
 * Reads one capture with an extractor and stores what came out, safely (docs/cards/03). Nothing here is called
 * from the save path yet: the pipeline that decides when to run it is card 13. The promises it keeps:
 *
 * - the capture is never touched except its `extract_state` and `photo_hint`; its words stay as saved;
 * - every write (new subjects and metrics, the replaced records, pins, returned loans) is one transaction, so a
 *   failure leaves the old records exactly as they were;
 * - a failed run is recorded as a FAILED extraction and the capture goes to RETRY (then GAVE_UP), with no words in
 *   the error;
 * - pinned records are never removed by a re-read ([RecordRepository.replaceRecordsFor]);
 * - dates are resolved against the day the capture was made, so reading it again later gives the same answer.
 */
class ExtractionRunner(
    private val db: StashDatabase,
    private val records: RecordRepository,
    private val clock: Clock = Clock.System,
    private val defaultZone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    sealed interface Outcome {
        data class Stored(
            val records: Int,
            val extractionId: String,
            val recordIds: List<String> = emptyList(),
            /** The state the capture is in now: DONE, RETRY (a model could not be reached) or GAVE_UP. */
            val state: ExtractState = ExtractState.DONE,
            val attempts: Int = 0,
        ) : Outcome
        data class Failed(val reason: String, val retryable: Boolean) : Outcome
        data object NoSuchCapture : Outcome
    }

    /** Run an offline extractor. Private captures may use it: it sends nothing anywhere. */
    suspend fun runLocal(captureId: String, extractor: LocalExtractor): Outcome =
        run(captureId, extractor.name) { capture, ctx -> extractor.extract(capture.text, ctx) }

    /**
     * Run any extractor through [produce] (card 04 passes a hosted one, after `Sensitivity.sendable`). [produce] gets
     * the capture and the context; it may throw, which counts as a retryable failure.
     */
    suspend fun run(
        captureId: String,
        extractorName: String,
        produce: suspend (CaptureEntity, ExtractContext) -> ExtractResult,
    ): Outcome = readWith(captureId) { capture, ctx -> Produced(extractorName, produce(capture, ctx)) }

    /**
     * What the pipeline (card 13) hands back: which extractor's records are stored, and why the capture may need
     * another look later. [hostedFailure]: a hosted model was tried and failed, so the rules' records are stored and
     * the capture goes to RETRY (or GAVE_UP, or DONE if the failure cannot be retried). [waitingForNetwork]: a key
     * exists but there is no network, so the rules' records are stored and the capture goes to RETRY without counting
     * an attempt.
     */
    data class Produced(
        val extractorName: String,
        val result: ExtractResult,
        val hostedFailure: ExtractResult.Failed? = null,
        val hostedName: String? = null,
        val waitingForNetwork: Boolean = false,
    )

    /** Like [run], but [produce] chooses the extractor name and may report a hosted failure. It may throw. */
    suspend fun readWith(
        captureId: String,
        fallbackName: String = "unknown",
        produce: suspend (CaptureEntity, ExtractContext) -> Produced,
    ): Outcome {
        val capture = db.captures().get(captureId)?.takeIf { it.deletedAt == null } ?: return Outcome.NoSuchCapture
        val (context, openTaskIds) = buildContext(capture)
        var extractorName = fallbackName
        return try {
            val produced = produce(capture, context)
            extractorName = produced.extractorName
            when (val result = produced.result) {
                is ExtractResult.Failed -> fail(capture, extractorName, result.reason, result.retryable)
                is ExtractResult.Ok -> try {
                    store(capture, extractorName, result, context, openTaskIds, produced)
                } catch (e: Exception) {
                    fail(capture, extractorName, "store_" + e.javaClass.simpleName, retryable = true)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(capture, extractorName, e.javaClass.simpleName, retryable = true)
        }
    }

    private suspend fun store(
        capture: CaptureEntity,
        extractorName: String,
        ok: ExtractResult.Ok,
        context: ExtractContext,
        openTaskIds: Map<String, String>,
        produced: Produced? = null,
    ): Outcome {
        // Card 16f: a picture-only note's record quotes the picture's words, not his text, so the "quote is in his
        // text" check cannot apply to it (there is no text of his). Every other extractor is checked as before.
        val checked = if (RecordsFromPictures.isPictureText(extractorName)) {
            ok.records.map { CheckedRecord(it, null) }
        } else {
            ExtractionChecks.check(ok.records, capture.text, context.taskLabels)
        }
        val downgraded = checked.count { it.attention != null }
        if (downgraded > 0) Dbg.event(DbgComponent.CHECKS, DbgCode.DOWNGRADED, n1 = downgraded, n2 = checked.size, refId = capture.id)
        val now = clock.now()
        val zone = capture.zone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: defaultZone()
        val today = Instant.ofEpochMilli(capture.createdAt).atZone(zone).toLocalDate()
        val extractionId = Ids.new()
        val extraction = ExtractionEntity(
            id = extractionId, captureId = capture.id, at = now, extractor = extractorName,
            schemaVersion = EXTRACTION_SCHEMA_VERSION, textHash = Repository.sha256(capture.text),
            outputJson = ok.rawJson, status = ExtractionStatus.CURRENT, tokensIn = ok.tokensIn, tokensOut = ok.tokensOut,
        )
        val failure = produced?.hostedFailure
        val attemptsBefore = db.captures().get(capture.id)?.extractAttempts ?: 0
        val attemptsAfter = if (failure?.retryable == true) attemptsBefore + 1 else attemptsBefore
        val finalState = when {
            failure != null && failure.retryable -> RetryPolicy.stateAfterFailure(attemptsAfter)
            failure != null -> ExtractState.DONE
            produced?.waitingForNetwork == true -> ExtractState.RETRY
            else -> ExtractState.DONE
        }
        val ids = db.withTransaction {
            if (failure != null) {
                // The attempt that failed is written down: a code, never the model's reply or the words.
                db.extractions().insert(
                    ExtractionEntity(
                        id = Ids.new(), captureId = capture.id, at = now, extractor = produced?.hostedName ?: "hosted",
                        schemaVersion = EXTRACTION_SCHEMA_VERSION, textHash = Repository.sha256(capture.text),
                        outputJson = "", status = ExtractionStatus.FAILED, error = failure.reason.take(80),
                    ),
                )
            }
            val projection = Projector.project(
                capture, extractionId, checked,
                ProjectionContext(
                    existingSubjects = db.subjects().all(),
                    existingMetrics = db.metrics().all(),
                    currentItemRecords = db.whereabouts().currentItemRecords(capture.id).map {
                        ItemRecordRef(it.recordId, it.captureId, it.itemSubjectId, it.kind, it.direction, it.copyLabel, it.returnedAt, it.capturedAt)
                    },
                    openTasks = openTaskIds,
                    today = today, zone = zone, now = now,
                ),
            )
            projection.newSubjects.forEach { db.subjects().insert(it) }
            projection.newMetrics.forEach { db.metrics().insert(it) }
            val written = records.replaceRecordsFor(capture.id, extraction, projection.bundles, finalState, attemptsAfter)
            projection.olderToPin.forEach { db.records().pinBySystem(it, now) }
            projection.returned.forEach { db.whereabouts().markReturned(it.loanRecordId, it.returnedAt, now) }
            if (projection.photoHint) {
                val c = db.captures().get(capture.id)
                // Card 16g: never offered for a note that has a picture; DISMISSED never goes back to PENDING.
                if (c != null && c.photoHint == PhotoHint.NONE && db.attachments().picturesForCapture(c.id).isEmpty()) db.captures().update(c.copy(photoHint = PhotoHint.PENDING).stamped(clock))
            }
            written
        }
        return Outcome.Stored(ids.size, extractionId, ids, finalState, attemptsAfter)
    }

    /** A failed run: the capture and its records stay as they were; the attempt is written down. No words in the error. */
    private suspend fun fail(capture: CaptureEntity, extractorName: String, reason: String, retryable: Boolean): Outcome {
        try {
            db.withTransaction {
                db.extractions().insert(
                    ExtractionEntity(
                        id = Ids.new(), captureId = capture.id, at = clock.now(), extractor = extractorName,
                        schemaVersion = EXTRACTION_SCHEMA_VERSION, textHash = Repository.sha256(capture.text),
                        outputJson = "", status = ExtractionStatus.FAILED, error = reason.take(80),
                    ),
                )
                val c = db.captures().get(capture.id) ?: return@withTransaction
                val attempts = c.extractAttempts + 1
                val state = if (retryable && attempts < MAX_ATTEMPTS) ExtractState.RETRY else ExtractState.GAVE_UP
                db.captures().update(c.copy(extractState = state, extractAttempts = attempts).stamped(clock))
            }
        } catch (e: Exception) {
            // Even recording the failure failed: the capture is still saved and still PENDING, which is safe.
        }
        return Outcome.Failed(reason, retryable)
    }

    private suspend fun buildContext(capture: CaptureEntity): Pair<ExtractContext, Map<String, String>> {
        val metrics = db.metrics().all().groupBy { it.subjectId }
        val subjects = db.subjects().all().filter { !it.sensitive && it.mergedIntoId == null }
            .map { KnownSubject(it.name, metrics[it.id].orEmpty().map { m -> m.label }) }
        val open = db.tasks().openTasks(capture.id, MAX_TASK_LABELS)
        val labelled = open.mapIndexed { i, t -> "T${i + 1}" to t }
        return ExtractContext(
            words = db.vocab().all(),
            subjects = subjects,
            openTasks = labelled.map { (label, t) -> OpenTaskRef(label, t.title) },
        ) to labelled.associate { (label, t) -> label to t.recordId }
    }

    companion object {
        /** For a run that fails outright (nothing could be stored). Hosted failures follow [RetryPolicy]. */
        const val MAX_ATTEMPTS = 3
        const val MAX_TASK_LABELS = 20
    }
}
