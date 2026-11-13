package app.stash.domain

import app.stash.data.NudgeType
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime

/** The three alarms (docs/cards/06 item 10). */
enum class AlarmKind(val nudgeType: NudgeType) { DAILY(NudgeType.DAILY), AFTERNOON(NudgeType.DUE), WEEKLY(NudgeType.WEEKLY) }

/** When each alarm next fires. Pure: the time and the zone are arguments. */
object AlarmTimes {
    fun next(kind: AlarmKind, nowMs: Long, s: NudgeSettings, zone: ZoneId): ZonedDateTime {
        val now = java.time.Instant.ofEpochMilli(nowMs).atZone(zone)
        return when (kind) {
            AlarmKind.DAILY -> daily(now, s.dailyTime)
            AlarmKind.AFTERNOON -> daily(now, s.afternoonTime)
            AlarmKind.WEEKLY -> (0L..7L).map { now.toLocalDate().plusDays(it).atTime(s.weeklyTime).atZone(zone) }
                .first { it.dayOfWeek == s.weeklyDay && it.isAfter(now) }
        }
    }

    private fun daily(now: ZonedDateTime, t: java.time.LocalTime): ZonedDateTime {
        val today = now.toLocalDate().atTime(t).atZone(now.zone)
        return if (today.isAfter(now)) today else now.toLocalDate().plusDays(1).atTime(t).atZone(now.zone)
    }
}

/**
 * What the alarm will post, decided when the data was at hand and kept in a small preferences file (docs/cards/06 9a).
 * It holds a type, a day, a count and record ids: no titles. [lines] are the weekly review's counts, which hold no words.
 */
data class StoredPlan(
    val kind: AlarmKind,
    /** The local day the alarm this plan is for will fire on. */
    val forDate: String,
    val count: Int,
    val recordIds: List<String> = emptyList(),
    val lines: List<String> = emptyList(),
    /** The weekly review carries the "I've stopped the daily reminder" line. */
    val pausedNotice: Boolean = false,
) {
    fun toJson(): String = JSONObject()
        .put("kind", kind.name).put("forDate", forDate).put("count", count)
        .put("ids", JSONArray(recordIds)).put("lines", JSONArray(lines)).put("paused", pausedNotice)
        .toString()

    companion object {
        fun fromJson(s: String?): StoredPlan? = try {
            val o = JSONObject(s ?: return null)
            StoredPlan(
                AlarmKind.valueOf(o.getString("kind")), o.getString("forDate"), o.getInt("count"),
                o.getJSONArray("ids").let { a -> (0 until a.length()).map { a.getString(it) } },
                o.getJSONArray("lines").let { a -> (0 until a.length()).map { a.getString(it) } },
                o.optBoolean("paused", false),
            )
        } catch (e: Exception) {
            null
        }
    }
}

/** The words of each notification (docs/cards/06 item 11). Calm, short, no exclamation marks. */
object ReminderText {
    const val TITLE = "Stash"
    const val WEEKLY_PUBLIC = "Your weekly review is ready."
    const val DUE_PUBLIC = "Something you wanted to do is due today."

    fun dailyPublic(count: Int) = "You have ${if (count == 1) "1 thing" else "$count things"} you wanted to do."

    /**
     * The lock-screen text: a count, never words. With the setting "Show the first task on the lock screen" on, the
     * first title is added (daily and due only), and the caller must have left Private tasks out already.
     */
    fun publicText(plan: StoredPlan, firstTitle: String?, showFirst: Boolean): String {
        val base = when (plan.kind) {
            AlarmKind.DAILY -> dailyPublic(plan.count)
            AlarmKind.WEEKLY -> WEEKLY_PUBLIC
            AlarmKind.AFTERNOON -> DUE_PUBLIC
        }
        return if (showFirst && plan.kind != AlarmKind.WEEKLY && !firstTitle.isNullOrBlank()) "$base $firstTitle" else base
    }

    /** The unlocked version's lines: the first few titles and "and 1 more"; for the review, its counts. */
    fun privateLines(plan: StoredPlan, titles: List<String>): List<String> = when (plan.kind) {
        AlarmKind.DAILY -> titles.take(DigestPlanner.LISTED) + (plan.count - titles.take(DigestPlanner.LISTED).size).let { more ->
            if (more > 0) listOf("and $more more") else emptyList()
        }
        AlarmKind.AFTERNOON -> titles.take(1)
        AlarmKind.WEEKLY -> plan.lines
    }

    fun channelName(kind: AlarmKind) = when (kind) {
        AlarmKind.DAILY -> "Daily reminder"
        AlarmKind.WEEKLY -> "Weekly review"
        AlarmKind.AFTERNOON -> "Due today"
    }
}
