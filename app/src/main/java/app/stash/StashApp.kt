package app.stash

import android.app.Activity
import android.app.Application
import android.os.Bundle
import app.stash.data.Container
import app.stash.domain.Capability
import app.stash.pipeline.TaskUpkeepWorker
import app.stash.triggers.NoteNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class StashApp : Application() {
    lateinit var container: Container
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = Container(this)
        container.startEventLog(scope)
        scope.launch {
            container.repo.seedIfNeeded()
        }
        // Card 05: once a day, wake snoozed tasks, stop asking about forgotten ones, and really delete notes
        // that have been in the bin for 30 days.
        TaskUpkeepWorker.schedule(this)
        // Anything still waiting to be read (captures from before this build, a pass that never ran) is read now.
        container.scheduler.enqueueNow()
        // Card 06: alarms for the daily digest, the afternoon check and the weekly review, planned from the tasks as they are.
        container.reminders.scheduleAlarms()
        container.reminders.replanSoon()
        // Card 16c: "Stash" in the share sheet's top row.
        app.stash.share.ShareShortcut.publish(this)
        NoteNotification.sync(this, container.capabilities.isOn(Capability.CAPTURE_NOTIFICATION))
        // Card 26f: count the started screens (the foreground pass runs only while one is up), and write out any
        // recording still waiting whenever Stash comes on screen (MainActivity, CaptureActivity or any other).
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (container.foreground.started()) runCatching { container.transcriptionScheduler.requestNow() }
            }
            override fun onActivityStopped(activity: Activity) = container.foreground.stopped()
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
