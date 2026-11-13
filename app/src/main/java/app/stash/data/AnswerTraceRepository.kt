package app.stash.data

import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject

/**
 * The answer traces (docs/cards/08 E2) and the miss log. Card 08's `AskEngine` writes one trace per question; the
 * newest [KEEP] are kept. A trace never leaves the phone (card 20 will turn it into tokens before anything does).
 */
class AnswerTraceRepository(private val db: StashDatabase, private val clock: Clock = Clock.System) {
    companion object {
        const val KEEP = 300

        /** True when [sourceIdsJson] (a JSON list) names any of [ids]. Bad JSON names nothing. */
        fun names(sourceIdsJson: String, ids: Set<String>): Boolean = try {
            val a = JSONArray(sourceIdsJson)
            (0 until a.length()).any { a.optString(it) in ids }
        } catch (e: Exception) {
            false
        }

        /** The steps with every lookup argument blanked (they are the question's words); ids and counts stay. */
        fun blankArgs(stepsJson: String): String = try {
            val a = JSONArray(stepsJson)
            for (i in 0 until a.length()) {
                val step = a.optJSONObject(i) ?: continue
                val args = step.optJSONObject("args") ?: continue
                val blank = JSONObject()
                for (k in args.keys()) blank.put(k, "")
                step.put("args", blank)
            }
            a.toString()
        } catch (e: Exception) {
            "[]"
        }

        /**
         * Docs/STORAGE.md rule 7: a trace that used a purged note keeps its route, ids and counts, and loses the words
         * (question, answer, lookup arguments). Call inside the purge's transaction. Returns how many were blanked.
         */
        suspend fun blankFor(db: StashDatabase, ids: Set<String>, now: Long): Int {
            if (ids.isEmpty()) return 0
            var n = 0
            for (t in db.answerTraces().newestFirst()) {
                if (!names(t.sourceIds, ids)) continue
                db.answerTraces().update(t.copy(question = "", answerText = "", stepsJson = blankArgs(t.stepsJson), updatedAt = now))
                n++
            }
            if (n > 0) app.stash.domain.Dbg.event(app.stash.domain.DbgComponent.ASK, app.stash.domain.DbgCode.BLANKED, n1 = n)
            return n
        }
    }

    /** Writes one trace and trims the table to the newest [KEEP]. */
    suspend fun write(t: AnswerTraceEntity) = db.withTransaction {
        db.answerTraces().insert(t)
        db.answerTraces().trimTo(KEEP)
    }

    suspend fun get(id: String): AnswerTraceEntity? = db.answerTraces().get(id)

    suspend fun newest(): List<AnswerTraceEntity> = db.answerTraces().newestFirst()

    /** "That's not it": the trace is marked and the question goes to the miss log (MARKED_WRONG). Null if it is gone. */
    suspend fun markWrong(id: String): AnswerTraceEntity? = db.withTransaction {
        val t = db.answerTraces().get(id) ?: return@withTransaction null
        if (!t.markedWrong) {
            db.answerTraces().update(t.copy(markedWrong = true).stamped(clock))
            db.missLog().insert(MissLogEntity(Ids.new(), clock.now(), t.question, MissReason.MARKED_WRONG))
        }
        db.answerTraces().get(id)
    }

    /** Nothing was found at any step (card 08 item 16). */
    suspend fun recordMiss(question: String, reason: MissReason) {
        db.missLog().insert(MissLogEntity(Ids.new(), clock.now(), question, reason))
    }

    /** The owner deleted one trace. */
    suspend fun delete(id: String): Boolean = db.answerTraces().delete(id) > 0

    /** "Delete everything" (card 10) clears them all. */
    suspend fun deleteAll() = db.answerTraces().deleteAll()
}
