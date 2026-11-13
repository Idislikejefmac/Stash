package app.stash.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import app.stash.StashApp
import app.stash.data.NudgeOutcome
import app.stash.domain.AlarmKind
import app.stash.domain.AlarmTimes
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.NudgeSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Keeps the three alarms registered (docs/cards/06 item 10): the daily digest, the afternoon check, the weekly review.
 *
 * Inexact alarms only, `setAndAllowWhileIdle`: a delay of some minutes is fine, and the exact-alarm permission is not
 * asked for (card 06; QUESTIONS.md Q26). Samsung may delay more for a "sleeping" app; that is measured, not assumed.
 */
class ReminderScheduler(private val context: Context, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    private fun pending(kind: AlarmKind, force: Boolean): PendingIntent = PendingIntent.getBroadcast(
        context, (if (force) 10 else 0) + kind.ordinal,
        Intent(context, ReminderReceiver::class.java).setAction(ReminderIntents.ACTION_ALARM)
            .putExtra(ReminderIntents.EXTRA_KIND, kind.name).putExtra(ReminderIntents.EXTRA_FORCE, force),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Register every alarm for its next time, or cancel them all (reminders off). Safe to call at any time. */
    fun registerAll(on: Boolean, s: NudgeSettings, now: Long = System.currentTimeMillis()) {
        for (kind in AlarmKind.entries) {
            val wanted = on && when (kind) {
                AlarmKind.DAILY, AlarmKind.AFTERNOON -> s.dailyOn || kind == AlarmKind.AFTERNOON
                AlarmKind.WEEKLY -> s.weeklyOn
            }
            if (!wanted) {
                alarms.cancel(pending(kind, false))
                continue
            }
            val at = AlarmTimes.next(kind, now, s, zone()).toInstant().toEpochMilli()
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(kind, false))
        }
    }

    /** Test builds only: one alarm [delayMs] from now that posts without the budget. */
    fun scheduleTest(kind: AlarmKind, delayMs: Long) {
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, pending(kind, true))
    }
}

/** What the container hands out: the engine, the alarms, and a scope to run them in. */
class ReminderService(
    private val context: Context,
    val engine: ReminderEngine,
    private val scheduler: ReminderScheduler,
    private val settings: () -> NudgeSettings,
    private val on: () -> Boolean,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Alarms to match the settings and the permission. At boot, on update, on a settings change, at app start. */
    fun scheduleAlarms() {
        try { scheduler.registerAll(on(), settings()) } catch (e: Exception) { Dbg.event(DbgComponent.REMINDER, DbgCode.THREW) }
    }

    /** Tasks changed (or the app started): plan again, in the background. */
    fun replanSoon() {
        scope.launch { if (on()) engine.replan() }
    }

    fun settingsChanged() {
        scheduleAlarms()
        replanSoon()
    }

    /** From an alarm or a notification button: run [work], then let the system know we are done. */
    fun runAsync(pending: BroadcastReceiver.PendingResult?, work: suspend () -> Unit) {
        scope.launch {
            try { work() } catch (e: Exception) { Dbg.event(DbgComponent.REMINDER, DbgCode.THREW) } finally { pending?.finish() }
        }
    }

    fun onAlarm(kind: AlarmKind, force: Boolean, pending: BroadcastReceiver.PendingResult?) = runAsync(pending) {
        // A forced (test) alarm is only honoured in a debuggable build.
        engine.onAlarm(kind, force && isDebuggable())
        scheduleAlarms()
    }

    fun isDebuggable(): Boolean = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Test build only: make something due and ring in about a minute. */
    suspend fun debugTest(kind: AlarmKind): TestOutcome {
        if (!isDebuggable()) return TestOutcome.NO_TASK
        val r = engine.debugMakeDue(kind)
        if (r == TestOutcome.READY) scheduler.scheduleTest(kind, 60_000L)
        return r
    }
}

/** The alarm fired. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = runCatching { AlarmKind.valueOf(intent.getStringExtra(ReminderIntents.EXTRA_KIND).orEmpty()) }.getOrNull() ?: return
        val service = (context.applicationContext as StashApp).container.reminders
        service.onAlarm(kind, intent.getBooleanExtra(ReminderIntents.EXTRA_FORCE, false), goAsync())
    }
}

/** Done, Later and swipe-away on a reminder. */
class NudgeActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as StashApp).container
        val postId = intent.getStringExtra(ReminderIntents.EXTRA_POST_ID) ?: return
        val recordId = intent.getStringExtra(ReminderIntents.EXTRA_RECORD_ID)
        val action = intent.action
        val notificationId = intent.getIntExtra(ReminderIntents.EXTRA_NOTIFICATION_ID, -1)
        container.reminders.runAsync(goAsync()) {
            when (action) {
                ReminderIntents.ACTION_DISMISS -> container.reminders.engine.outcome(postId, NudgeOutcome.DISMISSED)
                ReminderIntents.ACTION_DONE, ReminderIntents.ACTION_LATER -> {
                    if (recordId != null) container.reminders.engine.actOnTask(postId, recordId, done = action == ReminderIntents.ACTION_DONE)
                    if (notificationId >= 0) context.getSystemService(android.app.NotificationManager::class.java).cancel(notificationId)
                }
            }
        }
    }
}
