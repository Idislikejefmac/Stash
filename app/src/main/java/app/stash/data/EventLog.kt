package app.stash.data

import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.EventSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Writes `Dbg.event`s to the `app_event` table and keeps the newest [KEEP]. Drops an event if the store is not available. */
class DbEventSink(
    private val store: () -> Store,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) : EventSink {
    /** The latest write, so tests can wait for it. */
    @Volatile
    var last: Job? = null
        private set

    override fun write(component: DbgComponent, code: DbgCode, n1: Long?, n2: Long?, refId: String?, ms: Long?) {
        val at = clock.now()
        last = scope.launch {
            val db = (store() as? Store.Ready)?.db ?: return@launch
            try {
                db.appEvents().insert(AppEventEntity(Ids.new(), at, component.name, code.name, n1, n2, refId, ms))
                db.appEvents().trimTo(KEEP)
            } catch (e: Exception) {
                // Losing an event is better than failing what was being logged.
            }
        }
    }

    companion object {
        const val KEEP = 5_000
    }
}
