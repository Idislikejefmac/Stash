package app.stash.pipeline

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.stash.StashApp
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import java.util.concurrent.TimeUnit

/** Asks for a pass of the pipeline. The phone's implementation uses WorkManager; tests use a counter. */
interface ExtractionScheduler {
    /** Run as soon as possible. After every insert, at app start, at boot, after "Read it again". */
    fun enqueueNow()

    /** Run again later (a retry's back-off), or once a network is there. */
    fun enqueueLater(delayMs: Long, needsNetwork: Boolean)
}

class WorkManagerScheduler(private val context: Context) : ExtractionScheduler {
    private val wm get() = WorkManager.getInstance(context)

    // No network constraint: rules need none. Appended, so a capture saved while a pass is running is not missed.
    override fun enqueueNow() {
        wm.enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<ExtractionWorker>().build())
    }

    // Kept apart from [NOW] so a request that has to wait never holds up one that does not.
    override fun enqueueLater(delayMs: Long, needsNetwork: Boolean) {
        val request = OneTimeWorkRequestBuilder<ExtractionWorker>().setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        if (needsNetwork) request.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        wm.enqueueUniqueWork(LATER, ExistingWorkPolicy.REPLACE, request.build())
    }

    private companion object {
        const val NOW = "stash-extract"
        const val LATER = "stash-extract-later"
    }
}

/**
 * Reads the captures that are waiting (docs/cards/13 B). It never touches the database except through
 * `Container.store()`; when that is not available it returns `retry()`, which WorkManager backs off and repeats.
 */
class ExtractionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as StashApp).container
        val pass = container.pipeline.runPass()
        if (!pass.storeAvailable) return Result.retry()
        if (pass.needsNetwork) container.scheduler.enqueueLater(0, needsNetwork = true)
        else pass.nextDelayMs?.let { container.scheduler.enqueueLater(it, needsNetwork = false) }
        return Result.success()
    }
}
