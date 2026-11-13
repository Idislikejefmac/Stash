package app.stash.ask

import app.stash.data.AnswerTraceEntity
import app.stash.data.TraceOutcome
import app.stash.data.TraceRoute
import app.stash.data.WrittenBy
import org.json.JSONArray
import org.json.JSONObject

/**
 * One lookup in an answer trace (docs/cards/08 16a): its name, its arguments, and the ids and number of what it
 * returned. Ids only: never a note's words. Card 08 pass 2 adds the model's turns as their own steps.
 */
data class TraceStep(val lookup: String, val args: Map<String, String?>, val ids: List<String>, val count: Int = ids.size)

object TraceJson {
    fun steps(steps: List<TraceStep>): String = JSONArray().apply {
        for (s in steps) put(
            JSONObject().put("lookup", s.lookup)
                .put("args", JSONObject().apply { for ((k, v) in s.args) put(k, v ?: JSONObject.NULL) })
                .put("ids", JSONArray(s.ids)).put("count", s.count),
        )
    }.toString()

    fun parseSteps(json: String): List<TraceStep> = try {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            val args = o.optJSONObject("args")
            val ids = o.optJSONArray("ids")
            TraceStep(
                o.optString("lookup"),
                args?.keys()?.asSequence()?.associateWith { k -> if (args.isNull(k)) null else args.optString(k) }.orEmpty(),
                ids?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }.orEmpty(),
                o.optInt("count"),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun ids(ids: List<String>): String = JSONArray(ids.distinct()).toString()
}

/**
 * "Why this answer?" (docs/cards/08 16c): the trace in plain sentences. Pure. Arguments blanked by a purge are not
 * shown; the trace then says a note it used has been deleted.
 */
object TraceWords {
    fun routeLabel(name: String?): String? = when (name) {
        "where_is" -> "where is"
        "loans" -> "who has what"
        "open_tasks" -> "what to handle"
        "open_tasks_at_place" -> "what to do at a place"
        "last_reading" -> "last reading"
        "last_event" -> "when did I last"
        "on_date" -> "what did I note then"
        "about" -> "tell me about"
        else -> null
    }

    private fun arg(s: TraceStep, k: String): String? = s.args[k]?.takeIf { it.isNotBlank() }

    private fun found(n: Int, one: String, many: String) = when (n) {
        0 -> "found nothing"
        1 -> "found 1 $one"
        else -> "found $n $many"
    }

    fun step(s: TraceStep): String = when (s.lookup) {
        "where_is" -> "I looked up where ${arg(s, "item") ?: "it"} is and ${found(s.count, "note", "notes")}."
        "loans" -> "I looked up things lent or borrowed" + (arg(s, "person")?.let { " with $it" } ?: "") +
            (arg(s, "item")?.let { " ($it)" } ?: "") + " and ${found(s.count, "loan", "loans")}."
        "open_tasks" -> "I looked up your open tasks" + (arg(s, "at_place")?.let { " at $it" } ?: "") + " and ${found(s.count, "task", "tasks")}."
        "readings" -> "I looked up ${arg(s, "metric") ?: "readings"}" + (arg(s, "subject")?.let { " for $it" } ?: "") +
            " and ${found(s.count, "reading", "readings")}."
        "last_session" -> "I looked up the last ${arg(s, "subject") ?: ""} readings".replace("  ", " ") + " and ${found(s.count, "reading", "readings")}."
        "last_event" -> "I looked up events like \"${arg(s, "query") ?: "that"}\" and ${found(s.count, "event", "events")}."
        "captures_on" -> "I looked up what you noted ${arg(s, "said") ?: "then"} and ${found(s.count, "note", "notes")}."
        "about_subject" -> "I looked up notes about ${arg(s, "name") ?: "it"} and ${found(s.count, "note", "notes")}."
        "search_notes" -> "I matched the words of your question" + (arg(s, "query")?.let { " (\"$it\")" } ?: "") +
            " against your notes and ${found(s.count, "note", "notes")}."
        "model" -> "I asked the model, which asked for the lookups above."
        else -> "I looked something up and ${found(s.count, "note", "notes")}."
    }

    fun explain(t: AnswerTraceEntity): List<String> {
        val out = mutableListOf<String>()
        val label = routeLabel(t.routeName)
        out += if (label != null) "I took this as a '$label' question." else "I didn't recognise the kind of question."
        val steps = TraceJson.parseSteps(t.stepsJson)
        steps.forEachIndexed { i, s ->
            val line = step(s)
            out += if (i > 0 && s.lookup == "search_notes" && steps[i - 1].count == 0) "That found nothing, so $line" else line
        }
        out += when {
            t.outcome == TraceOutcome.NO_MATCH -> "Nothing matched, so the answer says I have no note about that."
            t.route == TraceRoute.SEARCH -> "The answer is the list of matching notes, made by the app, not the model."
            t.writtenBy == WrittenBy.MODEL -> "The answer was written by the model from what the lookups returned."
            t.route == TraceRoute.NONE && t.errorCode != null -> "Something went wrong while answering (code ${t.errorCode})."
            else -> "The answer was written by the app, not the model."
        }
        if (t.question.isBlank() && t.answerText.isBlank()) out += "A note this answer used has since been deleted, so its words are gone from here."
        if (t.markedWrong) out += "You said this was not it."
        return out
    }
}
