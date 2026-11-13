package app.stash.triggers

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.stash.R
import app.stash.StashApp
import app.stash.capture.CaptureActivity
import app.stash.domain.Capability

private fun capturePendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(
    context,
    1,
    Intent(context, CaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)

/** The ongoing "Add a note" notification. Shown on the lock screen; says nothing private. */
object NoteNotification {
    private const val CHANNEL = "capture"
    private const val ID = 1

    fun sync(context: Context, enabled: Boolean) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!enabled) {
            nm.cancel(ID)
            return
        }
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Add a note", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        val pi = capturePendingIntent(context)
        // An activity action normally makes the phone ask to unlock first. Marking it as not
        // needing authentication is what lets the capture screen open over the lock screen.
        val action = Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_note), "Add a note", pi)
            .setAuthenticationRequired(false)
            .build()
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle("Stash")
            .setContentText("Tap Add a note, then speak")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(pi)
            .addAction(action)
            .build()
        nm.notify(ID, n)
    }
}

/** Quick Settings tile: starts the capture screen. */
class AddNoteTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Add a note"
            updateTile()
        }
    }

    override fun onClick() {
        startActivityAndCollapse(capturePendingIntent(this))
    }
}

/** Puts the notification back after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as StashApp).container
        NoteNotification.sync(context, container.capabilities.isOn(Capability.CAPTURE_NOTIFICATION))
        // After a restart or an update, read anything that was still waiting.
        container.scheduler.enqueueNow()
        // Card 26f: and write out any recording still waiting (the job; no screen is up at boot).
        container.transcriptionScheduler.requestNow()
        // Alarms do not survive a restart or an update: register them again.
        container.reminders.scheduleAlarms()
        container.reminders.replanSoon()
    }
}
