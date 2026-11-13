package app.stash.reminders

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import app.stash.R
import app.stash.domain.AlarmKind
import app.stash.domain.ReminderText
import app.stash.ui.MainActivity

/** Names shared by the notification, its receivers and the activity it opens. */
object ReminderIntents {
    const val ACTION_ALARM = "app.stash.reminders.ALARM"
    const val ACTION_DONE = "app.stash.reminders.DONE"
    const val ACTION_LATER = "app.stash.reminders.LATER"
    const val ACTION_DISMISS = "app.stash.reminders.DISMISS"
    const val EXTRA_KIND = "kind"
    const val EXTRA_FORCE = "force"
    const val EXTRA_POST_ID = "post_id"
    const val EXTRA_RECORD_ID = "record_id"
    const val EXTRA_NOTIFICATION_ID = "notification_id"
    /** Set on the intent that opens the app from a reminder: "to_handle" or "review". */
    const val EXTRA_OPEN = "open_reminder"
    const val OPEN_TO_HANDLE = "to_handle"
    const val OPEN_REVIEW = "review"
}

/**
 * Posts the three kinds of reminder (docs/cards/06 item 11). Nothing here decides whether to post: the engine asks
 * the budget first. Every notification has a public version (a count, never words) for the lock screen; the version
 * with titles, and its Done and Later actions, is only what shows once the phone is unlocked.
 */
class Notifier(private val context: Context) : NotificationPoster {
    private val nm = context.getSystemService(NotificationManager::class.java)

    private fun channelId(kind: AlarmKind) = "reminder_" + kind.name.lowercase()

    private fun ensureChannel(kind: AlarmKind) {
        // Default importance with a buzz and no sound.
        nm.createNotificationChannel(
            NotificationChannel(channelId(kind), ReminderText.channelName(kind), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(true)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
    }

    override fun post(spec: NotificationSpec) {
        ensureChannel(spec.plan.kind)
        nm.notify(notificationId(spec.plan.kind), build(spec))
    }

    fun notificationId(kind: AlarmKind) = 200 + kind.ordinal

    fun build(spec: NotificationSpec): Notification {
        val kind = spec.plan.kind
        val channel = channelId(kind)
        val id = notificationId(kind)

        // What the lock screen shows: a count. No titles, no places, no names, no actions.
        val public = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(ReminderText.TITLE)
            .setContentText(spec.publicText)
            .build()

        val b = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_note)
            .setContentTitle(ReminderText.TITLE)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setAutoCancel(true)
            .setContentIntent(openIntent(spec, id))
            .setDeleteIntent(broadcast(ReminderIntents.ACTION_DISMISS, spec, id, spec.postId.hashCode() xor 0x2))

        val lines = spec.lines
        if (lines == null) {
            // The titles could not be read: say only what the lock screen would say.
            b.setContentText(spec.publicText)
        } else {
            b.setContentText(lines.first())
            if (lines.size > 1) {
                b.setStyle(Notification.InboxStyle().also { s -> lines.forEach { s.addLine(it) } })
            }
            // Done and Later belong to the unlocked version only, and ask for the phone to be unlocked.
            val recordId = spec.actionRecordId
            if (recordId != null) {
                b.addAction(action("Done", ReminderIntents.ACTION_DONE, spec, id, recordId, 0x4))
                b.addAction(action("Later", ReminderIntents.ACTION_LATER, spec, id, recordId, 0x8))
            }
        }
        return b.build()
    }

    private fun action(label: String, what: String, spec: NotificationSpec, id: Int, recordId: String, salt: Int): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_note), label, broadcast(what, spec, id, spec.postId.hashCode() xor salt, recordId))
            .setAuthenticationRequired(true)
            .build()

    private fun broadcast(what: String, spec: NotificationSpec, id: Int, requestCode: Int, recordId: String? = null): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, NudgeActionReceiver::class.java).setAction(what)
                .putExtra(ReminderIntents.EXTRA_POST_ID, spec.postId)
                .putExtra(ReminderIntents.EXTRA_NOTIFICATION_ID, id)
                .also { if (recordId != null) it.putExtra(ReminderIntents.EXTRA_RECORD_ID, recordId) },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun openIntent(spec: NotificationSpec, id: Int): PendingIntent = PendingIntent.getActivity(
        context, spec.postId.hashCode(),
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(ReminderIntents.EXTRA_POST_ID, spec.postId)
            .putExtra(ReminderIntents.EXTRA_NOTIFICATION_ID, id)
            .putExtra(ReminderIntents.EXTRA_OPEN, if (spec.plan.kind == AlarmKind.WEEKLY) ReminderIntents.OPEN_REVIEW else ReminderIntents.OPEN_TO_HANDLE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
