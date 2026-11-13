package app.stash.domain

import java.time.DayOfWeek
import java.time.LocalTime

/** The reminder settings (docs/cards/06 item 15). Times are the phone's local times. */
data class NudgeSettings(
    val dailyOn: Boolean = true,
    /** The digest stopped itself because it was being ignored (B9). Only the setting un-pauses it. */
    val dailyAutoPaused: Boolean = false,
    /** When the owner last turned the daily digest back on: ignored digests before this do not count towards a pause. */
    val dailyResumedAt: Long = 0L,
    val dailyTime: LocalTime = LocalTime.of(8, 30),
    val afternoonTime: LocalTime = LocalTime.of(16, 0),
    val weeklyOn: Boolean = true,
    val weeklyDay: DayOfWeek = DayOfWeek.SUNDAY,
    val weeklyTime: LocalTime = LocalTime.of(17, 0),
    val quietStart: LocalTime = LocalTime.of(21, 0),
    val quietEnd: LocalTime = LocalTime.of(8, 0),
    /** "Show the first task on the lock screen": default off. */
    val lockScreenFirstTask: Boolean = false,
) {
    /** Quiet hours may wrap midnight (21:00 to 08:00). Equal start and end means no quiet hours. */
    fun isQuiet(t: LocalTime): Boolean = when {
        quietStart == quietEnd -> false
        quietStart.isBefore(quietEnd) -> !t.isBefore(quietStart) && t.isBefore(quietEnd)
        else -> !t.isBefore(quietStart) || t.isBefore(quietEnd)
    }
}
