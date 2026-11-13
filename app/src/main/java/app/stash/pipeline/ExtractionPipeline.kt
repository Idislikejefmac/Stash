package app.stash.pipeline

import app.stash.data.CaptureEntity
import app.stash.data.CaptureRepository
import app.stash.data.Clock
import app.stash.data.ExtractState
import app.stash.data.ExtractionRunner
import app.stash.data.RecordRepository
import app.stash.data.Repository
import app.stash.data.RetryPolicy
import app.stash.data.SearchIndex
import app.stash.data.StashDatabase
import app.stash.data.Store
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.Sensitivity
import app.stash.model.ExtractResult
import app.stash.model.Extractor
import app.stash.model.LocalExtractor
import app.stash.model.PictureOnlyExtractor
import kotlinx.coroutines.CancellationException
import java.time.ZoneId

/** A later card's work that follows a stored extraction (card 05's task closer, 07's outlier check, 17's trip builder). */
fun interface AfterProjection {
    suspend fun afterProjection(captureId: String, recordIds: List<String>)
}

/** Captures the owner asked to have read again even though some of their records are pinned. */
interface ForceList {
    fun add(captureId: String)

    /** True once, if the capture was asked for. */
    fun take(captureId: String): Boolean
}

class MemoryForceList : ForceList {
    private val ids = mutableSetOf<String>()

    @Synchronized override fun add(captureId: String) { ids += captureId }

    @Synchronized override fun take(captureId: String): Boolean = ids.remove(captureId)
}

/** What one pass over the waiting captures found, so the worker can decide what to schedule next. */
data class PassResult(
    val storeAvailable: Boolean = true,
    val processed: Int = 0,
    /** Shortest wait until a capture that failed is due again, or null. */
    val nextDelayMs: Long? = null,
    /** A capture is waiting for a network. */
    val needsNetwork: Boolean = false,
)

/**
 * The background half of capture (docs/cards/13 section B). One pass reads every capture that is PENDING or due for
 * a retry, oldest first. Plain Kotlin over [ExtractionRunner], so it is tested without WorkManager.
 *
 * - Rules always run; they are the floor and the only thing a Private capture ever meets.
 * - A hosted extractor runs too, only if the capture is not Private ([Sensitivity.sendable]), the hosted capability
 *   is on, an extractor is registered, a key exists and there is a network.
 * - The database is reached only through [store]; "not available" ends the pass so the worker can ask to be retried.
 */
class ExtractionPipeline(
    private val store: () -> Store,
    private val rules: LocalExtractor,
    private val hostedExtractor: () -> Extractor?,
    private val hostedOn: () -> Boolean,
    private val hasKey: () -> Boolean,
    private val networkAvailable: () -> Boolean,
    private val hooks: () -> List<AfterProjection> = { emptyList() },
    private val force: ForceList = MemoryForceList(),
    private val clock: Clock = Clock.System,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    suspend fun runPass(): PassResult {
        val db = (store() as? Store.Ready)?.db ?: run {
            Dbg.event(DbgComponent.WORKER, DbgCode.STORE_UNAVAILABLE)
            return PassResult(storeAvailable = false)
        }
        val index = SearchIndex(db)
        val captures = CaptureRepository(db, index, clock)
        val runner = ExtractionRunner(db, RecordRepository(db, index, clock), clock, zone)

        var processed = 0
        var nextDelay: Long? = null
        var needsNetwork = false
        fun later(ms: Long) { nextDelay = nextDelay?.let { minOf(it, ms) } ?: ms }

        val online = networkAvailable()
        for (c in captures.toRead()) {
            val forced = force.take(c.id)
            if (!forced && c.extractState == ExtractState.RETRY) {
                if (c.extractAttempts == 0) {
                    if (!online) { needsNetwork = true; continue }
                } else {
                    val wait = c.updatedAt + RetryPolicy.backoffMs(c.extractAttempts) - clock.now()
                    if (wait > 0) { later(wait); continue }
                }
            }
            // Nothing new to learn: the owner has acted on this capture and its words are the ones already read.
            if (!forced && captures.hasPinnedRecord(c.id) && captures.currentTextHash(c.id) == Repository.sha256(c.text)) {
                captures.markDone(c.id)
                Dbg.event(DbgComponent.EXTRACT, DbgCode.SKIPPED, refId = c.id)
                continue
            }
            val stored = readOne(db, runner, c, online) ?: continue
            processed++
            if (stored.state == ExtractState.RETRY) {
                if (stored.attempts == 0) needsNetwork = true else later(RetryPolicy.backoffMs(stored.attempts))
            }
            runHooks(c.id, stored.recordIds)
        }
        if (nextDelay != null || needsNetwork) Dbg.event(DbgComponent.WORKER, DbgCode.RESCHEDULED, n1 = nextDelay ?: 0L, n2 = if (needsNetwork) 1 else 0)
        return PassResult(true, processed, nextDelay, needsNetwork)
    }

    private suspend fun readOne(db: StashDatabase, runner: ExtractionRunner, c: CaptureEntity, online: Boolean): ExtractionRunner.Outcome.Stored? {
        val started = clock.now()
        Dbg.event(DbgComponent.EXTRACT, DbgCode.STARTED, n1 = c.extractAttempts, refId = c.id)
        var tokens: Pair<Int?, Int?>? = null
        val outcome = runner.readWith(c.id, fallbackName = rules.name) { capture, ctx ->
            // Card 16f: a note that is only a picture is filed by the small local extractor; no rules, no model. The
            // picture's words never enter any other extraction.
            if (capture.text.isBlank()) {
                val pictures = db.attachments().pictureInfoForCapture(capture.id)
                if (pictures.isNotEmpty()) {
                    return@readWith ExtractionRunner.Produced(PictureOnlyExtractor.name, PictureOnlyExtractor.extract(pictures.first().ocrText))
                }
            }
            val local = rules.extract(capture.text, ctx)
            val sendable = Sensitivity.sendable(capture)
            val hosted = hostedExtractor()
            if (local !is ExtractResult.Ok || sendable == null || hosted == null || !hostedOn()) {
                return@readWith ExtractionRunner.Produced(rules.name, local)
            }
            // A hosted read is possible. Without a key nothing more can be done; without a network, wait for one.
            if (!hasKey()) return@readWith ExtractionRunner.Produced(rules.name, local)
            if (!online) return@readWith ExtractionRunner.Produced(rules.name, local, waitingForNetwork = true)
            val remote = try {
                hosted.extract(sendable, ctx)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ExtractResult.Failed(e.javaClass.simpleName, retryable = true)
            }
            when (remote) {
                is ExtractResult.Ok -> {
                    tokens = remote.tokensIn to remote.tokensOut
                    ExtractionRunner.Produced(hosted.name, remote)
                }
                is ExtractResult.Failed ->
                    ExtractionRunner.Produced(rules.name, local, hostedFailure = remote, hostedName = hosted.name)
            }
        }
        val ms = clock.now() - started
        return when (outcome) {
            is ExtractionRunner.Outcome.Stored -> {
                Dbg.event(DbgComponent.EXTRACT, DbgCode.OK, n1 = tokens?.first, n2 = tokens?.second, refId = c.id, ms = ms)
                Dbg.event(DbgComponent.PROJECT, DbgCode.WRITTEN, n1 = outcome.records, refId = c.id)
                if (outcome.state == ExtractState.GAVE_UP) Dbg.event(DbgComponent.EXTRACT, DbgCode.GAVE_UP, n1 = outcome.attempts, refId = c.id)
                outcome
            }
            is ExtractionRunner.Outcome.Failed -> {
                Dbg.event(DbgComponent.EXTRACT, DbgCode.FAILED, n1 = if (outcome.retryable) 1 else 0, refId = c.id, ms = ms)
                null
            }
            ExtractionRunner.Outcome.NoSuchCapture -> null
        }
    }

    private suspend fun runHooks(captureId: String, recordIds: List<String>) {
        for (hook in hooks()) {
            try {
                hook.afterProjection(captureId, recordIds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The records are already written; the hook's failure is an event, nothing more.
                Dbg.event(DbgComponent.HOOK, DbgCode.THREW, refId = captureId)
            }
        }
    }
}
