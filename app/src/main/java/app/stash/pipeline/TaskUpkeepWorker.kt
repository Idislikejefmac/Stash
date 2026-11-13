package app.stash.pipeline

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.stash.StashApp
import app.stash.data.Clock
import app.stash.data.Store
import app.stash.data.TaskRepository
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.TaskLifetimes
import app.stash.data.AttachmentRepository
import app.stash.data.CaptureRepository
import app.stash.data.SearchIndex
import app.stash.security.AttachmentStore
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** What one day's upkeep did. */
data class UpkeepResult(val storeAvailable: Boolean, val woke: Int = 0, val expired: Int = 0, val purged: Int = 0, val swept: Int = 0, val offersClosed: Int = 0)

/**
 * The daily upkeep (docs/cards/05 D): wake snoozed tasks whose date has come, stop asking about forgotten ones,
 * recompute `expires_on`, really delete notes that have been in the bin 30 days with their files, and sweep files
 * that have no row (card 16a). No notifications (card 06).
 * Plain Kotlin over the repositories, so it is tested without WorkManager.
 */
object TaskUpkeep {
    suspend fun run(
        store: () -> Store,
        clock: Clock = Clock.System,
        lifetimes: () -> TaskLifetimes = { TaskLifetimes() },
        zone: () -> ZoneId = { ZoneId.systemDefault() },
        today: () -> LocalDate = { LocalDate.now(zone()) },
        /** The note files (card 16a). Null: files of purged notes are left for a later sweep, and none is run. */
        attachments: AttachmentStore? = null,
    ): UpkeepResult {
        val db = (store() as? Store.Ready)?.db ?: run {
            Dbg.event(DbgComponent.UPKEEP, DbgCode.STORE_UNAVAILABLE)
            return UpkeepResult(false)
        }
        val u = TaskRepository(db, clock, lifetimes, zone).upkeep(today())
        val captures = CaptureRepository(db, SearchIndex(db), clock, attachments)
        val purged = captures.purgeDeletedOlderThan(30)
        // Card 16g: the "Add a picture?" offer ends by itself after 24 hours. No notification, no reminder.
        val offersClosed = captures.dismissStaleOffers()
        val swept = attachments?.let { AttachmentRepository(db, it, clock).sweep() } ?: 0
        Dbg.event(DbgComponent.UPKEEP, DbgCode.WOKE, n1 = u.woke)
        Dbg.event(DbgComponent.UPKEEP, DbgCode.EXPIRED, n1 = u.expired)
        Dbg.event(DbgComponent.UPKEEP, DbgCode.WRITTEN, n1 = purged)
        return UpkeepResult(true, u.woke, u.expired, purged, swept, offersClosed)
    }
}

class TaskUpkeepWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as StashApp).container
        val r = TaskUpkeep.run(store = container::store, lifetimes = { container.settings.lifetimes }, attachments = container.attachmentStore)
        // Card 06 B8: the 24-hour sweep (ignored digests, backing off, the digest stopping itself), then plan again.
        container.reminders.engine.sweep()
        // Card 16f item 12: after the daily upkeep, read pictures stored before the reader existed. Never fails the upkeep.
        try { container.pictureCatchUp.run() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { }
        container.reminders.replanSoon()
        return if (r.storeAvailable) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "stash-task-upkeep"

        /** Once a day. Kept if already scheduled. */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<TaskUpkeepWorker>(1, TimeUnit.DAYS).build(),
            )
        }
    }
}
