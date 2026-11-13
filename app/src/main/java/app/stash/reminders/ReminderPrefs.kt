package app.stash.reminders

import android.content.Context
import app.stash.data.NudgeType
import app.stash.domain.AlarmKind
import app.stash.domain.PostedNudge
import app.stash.domain.StoredPlan

/**
 * The small preferences file that holds what the alarm will post (type, day, count and record ids; no titles) and
 * the last few days of posts. With it the alarm can post, and the budget can be checked, with the database closed.
 */
class ReminderPrefs(context: Context) : PlanStore, PostLedger {
    private val prefs = context.getSharedPreferences("reminder_plans", Context.MODE_PRIVATE)

    @Synchronized override fun get(kind: AlarmKind): StoredPlan? = StoredPlan.fromJson(prefs.getString("plan_" + kind.name, null))

    @Synchronized override fun put(plan: StoredPlan) {
        prefs.edit().putString("plan_" + plan.kind.name, plan.toJson()).apply()
    }

    @Synchronized override fun clear(kind: AlarmKind) {
        prefs.edit().remove("plan_" + kind.name).apply()
    }

    @Synchronized override fun recent(): List<PostedNudge> =
        prefs.getString("ledger", "").orEmpty().split('\n').mapNotNull { line ->
            val p = line.split(':')
            val at = p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val type = runCatching { NudgeType.valueOf(p.getOrNull(1).orEmpty()) }.getOrNull() ?: return@mapNotNull null
            PostedNudge(at, type)
        }

    @Synchronized override fun add(p: PostedNudge) {
        val keepFrom = p.at - 3 * 24L * 3600_000L
        val all = (recent() + p).filter { it.at >= keepFrom }
        prefs.edit().putString("ledger", all.joinToString("\n") { "${it.at}:${it.type.name}" }).apply()
    }
}
