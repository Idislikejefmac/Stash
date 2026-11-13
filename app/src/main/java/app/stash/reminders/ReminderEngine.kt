package app.stash.reminders

import app.stash.data.Clock
import app.stash.data.Direction
import app.stash.data.Ids
import app.stash.data.NudgeLogEntity
import app.stash.data.NudgeOutcome
import app.stash.data.NudgeType
import app.stash.data.StashDatabase
import app.stash.data.Store
import app.stash.data.TaskItems
import app.stash.data.TaskRepository
import app.stash.data.TaskState
import app.stash.data.stamped
import app.stash.domain.AlarmKind
import app.stash.domain.AlarmTimes
import app.stash.domain.Attention
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.DigestPlanner
import app.stash.domain.LoanFact
import app.stash.domain.NotificationBudget
import app.stash.domain.NudgePlan
import app.stash.domain.NudgeSettings
import app.stash.domain.NudgeSweep
import app.stash.domain.PostedNudge
import app.stash.domain.RecordIds
import app.stash.domain.ReminderText
import app.stash.domain.SnoozePreset
import app.stash.domain.StoredPlan
import app.stash.domain.Verdict
import app.stash.domain.WeeklyReview
import app.stash.domain.WeeklySnapshot
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Where the alarm's plan is kept so that posting never needs the database (docs/cards/06 9a). */
interface PlanStore {
    fun get(kind: AlarmKind): StoredPlan?
    fun put(plan: StoredPlan)
    fun clear(kind: AlarmKind)
}

/** The posts of the last few days, kept beside the plans so the budget works with the database closed. */
interface PostLedger {
    fun recent(): List<PostedNudge>
    fun add(p: PostedNudge)
}

/** What gets posted. [lines] is null when the titles could not be read: then only the public text is posted. */
data class NotificationSpec(
    val postId: String,
    val plan: StoredPlan,
    val publicText: String,
    val lines: List<String>?,
    /** For "due today": the task the Done and Later actions act on. Only ever set with [lines]. */
    val actionRecordId: String?,
)

fun interface NotificationPoster {
    fun post(spec: NotificationSpec)
}

/** The owner's reminder settings and the two flags the engine changes. */
class ReminderSettingsAccess(
    val get: () -> NudgeSettings,
    val autoPause: (Long) -> Unit,
    val noticePending: () -> Boolean,
    val noticeShown: () -> Unit,
)

enum class PostResult { POSTED, NOTHING_TO_SAY, REFUSED, OFF }

/** Which debug test reminder (test builds only). */
enum class TestOutcome { READY, NO_TASK, NOTHING_TO_REVIEW }

/**
 * Card 06 in one place: plan when the data is at hand, post from the plan, ask the budget first, write the log.
 * Plain Kotlin over the repositories, so it runs under JVM tests with a fixed clock and a store that may be closed.
 */
class ReminderEngine(
    private val store: () -> Store,
    private val plans: PlanStore,
    private val ledger: PostLedger,
    private val poster: NotificationPoster,
    private val settings: ReminderSettingsAccess,
    private val enabled: () -> Boolean,
    private val tasks: (StashDatabase) -> TaskRepository,
    private val clock: Clock = Clock.System,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val lock = Mutex()

    private fun db(): StashDatabase? = try { (store() as? Store.Ready)?.db } catch (e: Exception) { null }

    private fun localDay(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone()).toLocalDate()

    // ------------------------------------------------------------------ planning

    /** Plan every alarm's next post and keep the result. Returns false when the database is not there. */
    suspend fun replan(): Boolean = lock.withLock { replanLocked() }

    private suspend fun replanLocked(): Boolean {
        val db = db() ?: run { Dbg.event(DbgComponent.REMINDER, DbgCode.STORE_UNAVAILABLE); return false }
        val now = clock.now()
        val z = zone()
        sweepLocked(db, now)
        tasks(db).wakeDue(localDay(now))
        backfillNudges(db, z)
        val s = settings.get()
        for (kind in AlarmKind.entries) {
            val forDate = AlarmTimes.next(kind, now, s, z).toLocalDate()
            val plan = planFor(db, kind, forDate, now, s, z, pickups = forDate == localDay(now) && java.time.Instant.ofEpochMilli(now).atZone(z).toLocalTime() >= s.dailyTime)
            if (plan == null) plans.clear(kind) else plans.put(plan)
        }
        Dbg.event(DbgComponent.REMINDER, DbgCode.WRITTEN)
        return true
    }

    /** Tasks from before this card, or woken ones, that have no nudge date yet get their first one. */
    private suspend fun backfillNudges(db: StashDatabase, z: ZoneId) {
        for (i in TaskItems.from(db.captures().live(), includeSensitive = false)) {
            val t = i.task
            if (t.state != TaskState.OPEN || t.nextNudgeOn != null || t.nudgeCount != 0 || t.quiet) continue
            val first = NudgePlan.firstNudgeOn(t, Instant.ofEpochMilli(i.capture.createdAt).atZone(z).toLocalDate()) ?: continue
            db.tasks().update(t.copy(nextNudgeOn = first.toString()).stamped(clock))
        }
    }

    private suspend fun planFor(
        db: StashDatabase,
        kind: AlarmKind,
        forDate: LocalDate,
        now: Long,
        s: NudgeSettings,
        z: ZoneId,
        pickups: Boolean,
    ): StoredPlan? {
        val captures = db.captures().live()
        val items = TaskItems.from(captures, includeSensitive = false)
        val log = db.nudgeLog().since(now - 3 * DAY_MS)
        return when (kind) {
            AlarmKind.DAILY -> DigestPlanner.planDaily(items, log, forDate, s, z)
                ?.let { StoredPlan(kind, forDate.toString(), it.total, it.recordIds) }
            AlarmKind.AFTERNOON -> DigestPlanner.planDueToday(items, log, forDate, s, z, pickups)
                ?.let { StoredPlan(kind, forDate.toString(), 1, listOf(it.recordId)) }
            AlarmKind.WEEKLY -> {
                if (!s.weeklyOn) return null
                val review = review(db, forDate, now, s)
                if (review.isEmpty) null
                else StoredPlan(kind, forDate.toString(), review.lines().size, emptyList(), review.lines().map { it.text }, review.pausedNotice)
            }
        }
    }

    /** The weekly review as it stands now (the screen reads this too). Private rows are never part of it. */
    suspend fun review(db: StashDatabase, today: LocalDate, now: Long, s: NudgeSettings): WeeklyReview {
        val captures = db.captures().live().filter { !it.capture.sensitive }
        val superseded = captures.flatMap { it.records }.mapNotNull { it.record.supersedesRecordId }.toSet()
        val loans = captures.flatMap { c ->
            c.records.filter { it.record.deletedAt == null && it.record.id !in superseded }.mapNotNull { r ->
                val w = r.whereabouts ?: return@mapNotNull null
                if ((w.direction != Direction.LENT && w.direction != Direction.BORROWED) || w.returnedAt != null) return@mapNotNull null
                LoanFact(r.record.id, c.capture.createdAt, w.dueBackOn, sensitive = false)
            }
        }
        val attention = captures.flatMap { it.records }.count {
            it.record.deletedAt == null && it.record.attention != null && !it.record.attention.startsWith(Attention.DUPLICATE_OF_PREFIX)
        }
        val snapshot = WeeklySnapshot(TaskItems.from(captures, includeSensitive = false), loans, attention)
        return DigestPlanner.planWeekly(snapshot, today, now, s, settings.noticePending())
    }

    suspend fun reviewNow(): WeeklyReview? {
        val db = db() ?: return null
        val now = clock.now()
        return review(db, localDay(now), now, settings.get())
    }

    // ------------------------------------------------------------------ posting

    /**
     * An alarm fired. Posts from the stored plan, asks the budget first, then writes the log, moves the tasks'
     * nudge dates on and plans again. [force] is only for the test build's "in a minute" buttons: it skips the
     * budget and builds the plan afresh.
     */
    suspend fun onAlarm(kind: AlarmKind, force: Boolean = false): PostResult = lock.withLock {
        if (!enabled()) return PostResult.OFF
        val now = clock.now()
        val z = zone()
        val s = settings.get()
        val today = localDay(now)

        var plan = if (force) debugPlan(kind, now, s, z) else plans.get(kind)
        if (!force && (plan == null || plan.forDate != today.toString())) {
            // The plan is missing or from another day: try once to make it, with the database if it is there.
            val db = db()
            plan = if (db == null) null else try {
                planFor(db, kind, today, now, s, z, pickups = true)
            } catch (e: Exception) { null }
        }
        if (plan == null || plan.forDate != today.toString()) {
            finishAlarm(now)
            return PostResult.NOTHING_TO_SAY
        }
        if (!force) {
            val verdict = NotificationBudget.check(kind.nudgeType, now, ledger.recent(), s, z)
            if (verdict != Verdict.ALLOW) {
                Dbg.event(DbgComponent.REMINDER, DbgCode.REFUSED, n1 = verdict.ordinal)
                finishAlarm(now)
                return PostResult.REFUSED
            }
        }

        val postId = Ids.new()
        // The titles are only for the unlocked version, and only if the database can be read now.
        val titles = titlesFor(plan.recordIds)
        val first = titles?.firstOrNull()
        val spec = NotificationSpec(
            postId = postId,
            plan = plan,
            publicText = ReminderText.publicText(plan, first, s.lockScreenFirstTask),
            lines = titles?.let { ReminderText.privateLines(plan, it) }?.takeIf { it.isNotEmpty() },
            actionRecordId = if (kind == AlarmKind.AFTERNOON && titles != null) plan.recordIds.firstOrNull() else null,
        )
        poster.post(spec)
        ledger.add(PostedNudge(now, kind.nudgeType))
        Dbg.event(DbgComponent.REMINDER, DbgCode.OK, n1 = kind.ordinal, n2 = plan.count)
        afterPost(postId, plan, now, today)
        plans.clear(kind)
        finishAlarm(now)
        PostResult.POSTED
    }

    private suspend fun finishAlarm(@Suppress("UNUSED_PARAMETER") now: Long) {
        // Ask for a re-plan: the next occurrence of each alarm gets its plan from the data as it is now.
        try { replanLocked() } catch (e: Exception) { Dbg.event(DbgComponent.REMINDER, DbgCode.THREW) }
    }

    /** Best effort with the database: the log row, and each listed task's next nudge. A failure is an event, never a crash. */
    private suspend fun afterPost(postId: String, plan: StoredPlan, now: Long, today: LocalDate) {
        val db = db() ?: run { Dbg.event(DbgComponent.REMINDER, DbgCode.STORE_UNAVAILABLE); return }
        try {
            db.nudgeLog().insert(NudgeLogEntity(postId, now, plan.kind.nudgeType, RecordIds.encode(plan.recordIds), NudgeOutcome.SHOWN))
            if (plan.kind != AlarmKind.WEEKLY) {
                for (id in plan.recordIds) {
                    val t = db.tasks().get(id) ?: continue
                    if (t.state != TaskState.OPEN) continue
                    // Not an owner action: `last_active_at` is left alone, so ignoring still counts as ignoring.
                    db.tasks().update(t.copy(nextNudgeOn = NudgePlan.nextAfter(t, today)?.toString(), nudgeCount = t.nudgeCount + 1).stamped(clock))
                }
            } else if (plan.pausedNotice) {
                settings.noticeShown()
            }
        } catch (e: Exception) {
            Dbg.event(DbgComponent.REMINDER, DbgCode.THREW)
        }
    }

    /** Titles of the tasks, in plan order, for the unlocked version. Null when the database cannot be read. Never a Private task. */
    private suspend fun titlesFor(ids: List<String>): List<String>? {
        if (ids.isEmpty()) return emptyList()
        return try {
            val db = (store() as? Store.Ready)?.db ?: return null
            val byId = TaskItems.from(db.captures().live(), includeSensitive = false)
                .filter { it.task.state == TaskState.OPEN }.associateBy { it.record.id }
            ids.mapNotNull { byId[it]?.record?.title }
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ outcomes

    /** The owner opened, swiped away or acted on a notification. Opening never goes back to "shown", and so on. */
    suspend fun outcome(postId: String, outcome: NudgeOutcome) = lock.withLock {
        val db = db() ?: return@withLock
        try {
            val row = db.nudgeLog().get(postId) ?: return@withLock
            val merged = NudgeSweep.merge(row.outcome, outcome)
            if (merged != row.outcome) db.nudgeLog().setOutcome(postId, merged)
        } catch (e: Exception) {
            Dbg.event(DbgComponent.REMINDER, DbgCode.THREW)
        }
    }

    /** "Done" or "Later" on the due-today notification. Returns false when the task could not be changed. */
    suspend fun actOnTask(postId: String, recordId: String, done: Boolean): Boolean {
        val db = db() ?: return false
        val repo = tasks(db)
        val changed = if (done) repo.markDone(recordId) else repo.snooze(recordId, SnoozePreset.TOMORROW.resolve(localDay(clock.now())))
        outcome(postId, NudgeOutcome.ACTED)
        return changed != null
    }

    /** 24-hour sweep: shown and not opened becomes ignored, tasks back off, five ignored digests pause the digest. */
    suspend fun sweep() = lock.withLock { db()?.let { sweepLocked(it, clock.now()) } }

    private suspend fun sweepLocked(db: StashDatabase, now: Long) {
        try {
            val log = db.nudgeLog().since(now - 30 * DAY_MS)
            val ids = log.flatMap { RecordIds.decode(it.recordIds) }.distinct()
            val rows = ids.mapNotNull { db.tasks().get(it) }.associateBy { it.recordId }
            val r = NudgeSweep.sweep(log, rows, now)
            for ((id, o) in r.outcomes) db.nudgeLog().setOutcome(id, o)
            for (t in r.tasks) db.tasks().update(t.stamped(clock))
            val s = settings.get()
            val after = log.map { it.copy(outcome = r.outcomes[it.id] ?: it.outcome) }
            if (!s.dailyAutoPaused && s.dailyOn && NudgeSweep.shouldAutoPause(after, s.dailyResumedAt)) {
                settings.autoPause(now)
                Dbg.event(DbgComponent.REMINDER, DbgCode.SKIPPED, n1 = 5)
            }
        } catch (e: Exception) {
            Dbg.event(DbgComponent.REMINDER, DbgCode.THREW)
        }
    }

    // ------------------------------------------------------------------ test build only

    /** Make the oldest open, non-private task due now so a test reminder has something to say. */
    suspend fun debugMakeDue(kind: AlarmKind): TestOutcome = lock.withLock {
        val db = db() ?: return@withLock TestOutcome.NO_TASK
        val now = clock.now()
        val s = settings.get()
        if (kind == AlarmKind.WEEKLY) {
            return@withLock if (review(db, localDay(now), now, s).isEmpty) TestOutcome.NOTHING_TO_REVIEW else TestOutcome.READY
        }
        val pick = DigestPlanner.eligible(TaskItems.from(db.captures().live(), includeSensitive = false))
            .minByOrNull { it.capture.createdAt } ?: return@withLock TestOutcome.NO_TASK
        db.tasks().update(pick.task.copy(nextNudgeOn = localDay(now).toString()).stamped(clock))
        TestOutcome.READY
    }

    private suspend fun debugPlan(kind: AlarmKind, now: Long, s: NudgeSettings, z: ZoneId): StoredPlan? {
        val db = db() ?: return null
        val today = localDay(now)
        // A test build asked for this one: leave out the weekly-day, paused and already-sent rules.
        val relaxed = s.copy(weeklyOn = false, dailyOn = true, dailyAutoPaused = false)
        val items = TaskItems.from(db.captures().live(), includeSensitive = false)
        return when (kind) {
            AlarmKind.DAILY -> DigestPlanner.planDaily(items, emptyList(), today, relaxed, z)?.let { StoredPlan(kind, today.toString(), it.total, it.recordIds) }
            AlarmKind.AFTERNOON -> DigestPlanner.eligible(items).filter { NudgePlan.isDue(it.task, today) }.minByOrNull { it.capture.createdAt }
                ?.let { StoredPlan(kind, today.toString(), 1, listOf(it.record.id)) }
            AlarmKind.WEEKLY -> review(db, today, now, s).takeIf { !it.isEmpty }
                ?.let { StoredPlan(kind, today.toString(), it.lines().size, emptyList(), it.lines().map { l -> l.text }, it.pausedNotice) }
        }
    }

    private companion object {
        const val DAY_MS = 24L * 3600_000L
    }
}
