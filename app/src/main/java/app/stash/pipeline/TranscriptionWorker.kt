package app.stash.pipeline

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.stash.StashApp
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import java.util.concurrent.TimeUnit

/** The WorkManager half of starting a pass. The phone uses [WorkManagerTranscriptionWork]; tests a counter. */
interface TranscriptionWork {
    /** Unique work `stash-transcribe`, appended so a recording saved while a job runs is not missed. */
    fun enqueueNow()

    /** Unique work `stash-transcribe-later`: the nearest back-off, or a look in case a running pass dies. */
    fun enqueueLater(delayMs: Long)
}

/**
 * Card 26f section B: the two ways to start [TranscriptionPipeline.runPass]. [requestNow] runs a foreground pass in
 * the application's scope while a screen of the app is started (never a screen's own scope: it dies with the
 * screen), and always enqueues the job. The pipeline's lock makes sure only one of them writes a note out.
 */
class TranscriptionScheduler(
    private val foreground: ForegroundTracker,
    /** Launches in the application's scope. */
    private val launch: (suspend () -> Unit) -> Unit,
    private val work: TranscriptionWork,
    private val pipeline: () -> TranscriptionPipeline,
) {
    fun requestNow() {
        val starts = StartRule.decide(foreground.count())
        if (starts.foreground) {
            launch {
                val r = pipeline().runPass(PassWhere.FOREGROUND)
                r.nextDelayMs?.let { runCatching { work.enqueueLater(it) } }
            }
        }
        if (starts.job) runCatching { work.enqueueNow() }
    }
}

/** What the job does with a pass's result, as plain Kotlin so it is tested without WorkManager (item 7). */
object TranscriptionJobLogic {
    enum class Outcome { SUCCESS, RETRY }

    suspend fun run(pass: suspend () -> TranscriptionPassResult, later: (Long) -> Unit): Outcome {
        val r = pass()
        if (r.alreadyRunning) {
            // A foreground pass is writing out now. If the process dies under it, this later job finishes the work.
            later(TranscriptionWorkShape.ALREADY_RUNNING_RECHECK_MS)
            Dbg.event(DbgComponent.TRANSCRIBE, DbgCode.RESCHEDULED, n1 = TranscriptionWorkShape.ALREADY_RUNNING_RECHECK_MS, n2 = 1)
            return Outcome.SUCCESS
        }
        if (!r.storeAvailable) return Outcome.RETRY
        // The pass has logged TRANSCRIBE RESCHEDULED (n1 = the delay) already.
        r.nextDelayMs?.let(later)
        return Outcome.SUCCESS
    }
}

class WorkManagerTranscriptionWork(private val context: Context) : TranscriptionWork {
    private val wm get() = WorkManager.getInstance(context)

    private fun request(delayMs: Long) = OneTimeWorkRequestBuilder<TranscriptionWorker>().apply {
        if (delayMs > 0) setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
        // Check C1 decides these two (TranscriptionWorkShape.OUTCOME). No notification is needed for an expedited
        // job on Android 12 and later, and this app's minSdk is 35. An expedited request cannot carry a delay.
        if (delayMs <= 0 && TranscriptionWorkShape.expedite()) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        if (TranscriptionWorkShape.WORK_NEEDS_CHARGING) setConstraints(Constraints.Builder().setRequiresCharging(true).build())
    }.build()

    override fun enqueueNow() {
        wm.enqueueUniqueWork(TranscriptionWorkShape.NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request(0))
    }

    override fun enqueueLater(delayMs: Long) {
        wm.enqueueUniqueWork(TranscriptionWorkShape.LATER, ExistingWorkPolicy.REPLACE, request(delayMs.coerceAtLeast(1)))
    }
}

/**
 * Card 26f item 7: a background pass. No foreground service and no notification (item 10). Returns `retry()` when the
 * store is not available, so WorkManager backs off and repeats.
 */
class TranscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as StashApp).container
        val outcome = TranscriptionJobLogic.run(
            pass = { container.transcriptionPipeline.runPass(PassWhere.BACKGROUND) },
            later = { container.transcriptionWork.enqueueLater(it) },
        )
        return if (outcome == TranscriptionJobLogic.Outcome.RETRY) Result.retry() else Result.success()
    }
}

/** The run marker in a small preferences file of its own (`transcribe_run`), written with `commit()` so it is on disk. */
class PrefsRunMarkerStore(context: Context) : RunMarkerStore {
    private val prefs = context.getSharedPreferences("transcribe_run", Context.MODE_PRIVATE)

    @Synchronized override fun read(): RunMarker? {
        val id = prefs.getString("capture_id", null) ?: return null
        val where = runCatching { PassWhere.valueOf(prefs.getString("where", "") ?: "") }.getOrNull() ?: PassWhere.BACKGROUND
        return RunMarker(id, prefs.getLong("started_at", 0), where, prefs.getString("token", "") ?: "")
    }

    @Synchronized override fun write(marker: RunMarker) {
        prefs.edit().putString("capture_id", marker.captureId).putLong("started_at", marker.startedAt)
            .putString("where", marker.where.name).putString("token", marker.processToken).commit()
    }

    @Synchronized override fun clear() {
        prefs.edit().clear().commit()
    }
}
