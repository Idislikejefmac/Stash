package app.stash.ask

import app.stash.data.AnswerTraceEntity
import app.stash.data.AnswerTraceRepository
import app.stash.data.Ids
import app.stash.data.MissReason
import app.stash.data.TraceOutcome
import app.stash.data.TraceRoute
import app.stash.data.WrittenBy
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.ReadingQuestion
import app.stash.domain.Searcher
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

/**
 * Where card 08 pass 2's bounded tool loop plugs in (section D). Pass 1 builds no loop and no model client: the app
 * passes null, so every question is answered by code or by plain search. A loop that is not [usable] (no key, switch
 * off, over the monthly limit, no network) is not asked.
 */
interface AskLoop {
    fun usable(): Boolean

    /** Lookups it makes must use `includeSensitive = false`. Its steps go into the trace as they are. */
    suspend fun answer(question: String): LoopOutcome
}

sealed interface LoopOutcome {
    val sourceIds: List<String>
    val steps: List<TraceStep>

    data class Answered(val text: String, override val sourceIds: List<String>, override val steps: List<TraceStep>, val promptVersion: String) : LoopOutcome

    /** [reason] is a code (`LIMIT`, `TIMEOUT`, `REFUSED`, `HTTP_429`, ...), never words. */
    data class GaveUp(val reason: String, override val sourceIds: List<String>, override val steps: List<TraceStep>, val promptVersion: String) : LoopOutcome
}

/** What the Ask screen shows for one question. */
data class AskResult(
    val traceId: String,
    /** The answer sentence, or the list's heading, or [AnswerWriter.NOTHING]. */
    val text: String,
    val kind: Kind,
    val sources: List<SourceNote>,
    val writtenBy: WrittenBy,
    /** Private notes that also match: a number only (card 08 item 15). */
    val privateCount: Int,
) {
    enum class Kind { ANSWER, LIST, NOTHING }
}

/**
 * One question, one pipeline (docs/cards/08 section E): router, then the coded answer; if there is none, the loop
 * when it is usable; otherwise, or when it gives up, plain search shown as a list; or "I don't have a note about
 * that". Exactly one answer trace is written per question, whatever the path, failures included. Every lookup here
 * passes `includeSensitive = false`: there is no app gate yet (card 02).
 */
class AskEngine(
    private val lookups: Lookups,
    private val traces: AnswerTraceRepository,
    private val now: () -> Long,
    private val zone: () -> ZoneId,
    private val appVersion: String,
    /** Card 08 pass 2. Null in pass 1. */
    private val loop: AskLoop? = null,
    private val ticker: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private class Run {
        val steps = mutableListOf<TraceStep>()
        var routeName: String? = null
        var reached = TraceRoute.NONE
    }

    suspend fun ask(question: String): AskResult {
        val start = ticker()
        val run = Run()
        val id = Ids.new()
        return try {
            answer(question, run, id, start)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val ms = ticker() - start
            val text = "I couldn't work that out."
            runCatching {
                traces.write(trace(id, question, run, TraceRoute.NONE, emptyList(), text, WrittenBy.CODE, TraceOutcome.GAVE_UP, "THREW", ms, null))
            }
            Dbg.event(DbgComponent.ASK, DbgCode.THREW, n1 = code(run.reached), refId = id, ms = ms)
            AskResult(id, text, AskResult.Kind.NOTHING, emptyList(), WrittenBy.CODE, 0)
        }
    }

    private suspend fun answer(question: String, run: Run, id: String, start: Long): AskResult {
        val t = now()
        val today = Instant.ofEpochMilli(t).atZone(zone()).toLocalDate()
        val route = Router.route(question, lookups.catalog(includeSensitive = false), today)
        run.routeName = route?.name
        if (route != null) {
            run.reached = TraceRoute.ROUTER
            val coded = runRoute(route, run, t, today)
            if (coded != null) return finish(question, run, id, start, TraceRoute.ROUTER, coded.text, AskResult.Kind.ANSWER, coded.sources, WrittenBy.CODE, TraceOutcome.ANSWERED)
        }

        var gaveUp: LoopOutcome.GaveUp? = null
        val l = loop
        if (l != null && l.usable()) {
            run.reached = TraceRoute.LOOP
            when (val out = l.answer(question)) {
                is LoopOutcome.Answered -> {
                    run.steps += out.steps
                    val sources = lookups.notesById(out.sourceIds, includeSensitive = false).map(AnswerWriter::source)
                    return finish(question, run, id, start, TraceRoute.LOOP, out.text, AskResult.Kind.ANSWER, sources, WrittenBy.MODEL, TraceOutcome.ANSWERED, prompt = out.promptVersion, extraIds = out.sourceIds)
                }
                is LoopOutcome.GaveUp -> {
                    run.steps += out.steps
                    gaveUp = out
                }
            }
        }

        run.reached = TraceRoute.SEARCH
        val hits = lookups.searchNotes(question, includeSensitive = false)
        run.steps += TraceStep("search_notes", mapOf("query" to Searcher.keywords(question).joinToString(" ")), hits.map { it.id })
        val outcome = if (gaveUp != null) TraceOutcome.GAVE_UP else null
        if (hits.isNotEmpty()) {
            val heading = if (gaveUp != null) AnswerWriter.LIST_GAVE_UP else AnswerWriter.LIST_NO_MODEL
            return finish(question, run, id, start, TraceRoute.SEARCH, heading, AskResult.Kind.LIST, hits.map(AnswerWriter::source), WrittenBy.CODE,
                outcome ?: TraceOutcome.ANSWERED, error = gaveUp?.reason, prompt = gaveUp?.promptVersion)
        }
        traces.recordMiss(question, MissReason.NO_MATCH)
        return finish(question, run, id, start, TraceRoute.NONE, AnswerWriter.NOTHING, AskResult.Kind.NOTHING, emptyList(), WrittenBy.CODE,
            outcome ?: TraceOutcome.NO_MATCH, error = gaveUp?.reason, prompt = gaveUp?.promptVersion)
    }

    /** The coded answer for a route, or null when its lookup found nothing (the engine then goes on). */
    private suspend fun runRoute(route: Route, run: Run, t: Long, today: java.time.LocalDate): Answer? {
        fun step(lookup: String, args: Map<String, String?>, ids: List<String>) { run.steps += TraceStep(lookup, args, ids) }
        return when (route) {
            is Route.WhereIs -> {
                val r = lookups.whereIs(route.item, includeSensitive = false)
                step("where_is", mapOf("item" to route.item), listOfNotNull(r?.current?.recordId) + r?.previous?.map { it.recordId }.orEmpty())
                AnswerWriter.whereIs(r)
            }
            is Route.Loans -> {
                val r = lookups.loans(route.person, includeSensitive = false, item = route.item)
                step("loans", mapOf("person" to route.person, "item" to route.item), r.map { it.recordId })
                AnswerWriter.loans(r)
            }
            is Route.OpenTasks -> {
                val r = lookups.openTasks(subject = null, atPlace = route.atPlace, includeSensitive = false)
                step("open_tasks", mapOf("at_place" to route.atPlace), r.hits.map { it.recordId })
                AnswerWriter.openTasks(r, route.atPlace, today, t)
            }
            is Route.LastReading -> when (val q = route.question) {
                is ReadingQuestion.LastValue -> {
                    val rows = lookups.metricReadings(q.metricId, 2, includeSensitive = false)
                    val first = rows.firstOrNull()
                    step("readings", mapOf("metric" to first?.metricLabel, "subject" to first?.subjectName), rows.map { it.recordId }.distinct())
                    AnswerWriter.lastValue(rows, t, q.named)
                }
                is ReadingQuestion.LastSession -> {
                    val name = lookups.catalog(includeSensitive = false).subjects.firstOrNull { it.subjectId == q.subjectId }?.name
                    val s = lookups.lastSession(q.subjectId, includeSensitive = false)
                    run.steps += TraceStep("last_session", mapOf("subject" to name), listOfNotNull(s?.recordId), s?.rows?.size ?: 0)
                    if (name == null) null else AnswerWriter.lastSession(name, s, t)
                }
            }
            is Route.LastEvent -> {
                val r = lookups.lastEvent(route.query, includeSensitive = false)
                step("last_event", mapOf("query" to route.query), r.map { it.recordId })
                AnswerWriter.lastEvent(r)
            }
            is Route.OnDate -> {
                val r = lookups.capturesOn(route.range, includeSensitive = false)
                step("captures_on", mapOf("said" to route.said, "from" to route.range.from.toString(), "to" to route.range.to.toString()), r.map { it.captureId })
                AnswerWriter.onDate(route.said, r)
            }
            is Route.About -> {
                val r = lookups.aboutSubject(route.subject, includeSensitive = false)
                run.steps += TraceStep("about_subject", mapOf("name" to route.subject), r?.newest?.values?.flatten()?.map { it.id }.orEmpty(), r?.total ?: 0)
                AnswerWriter.about(r)
            }
        }
    }

    private suspend fun finish(
        question: String, run: Run, id: String, start: Long, route: TraceRoute, text: String, kind: AskResult.Kind,
        sources: List<SourceNote>, writtenBy: WrittenBy, outcome: TraceOutcome,
        error: String? = null, prompt: String? = null, extraIds: List<String> = emptyList(),
    ): AskResult {
        // After any answer: how many Private notes also match. A number only; never stored in the trace.
        val privateCount = lookups.privateMatchCount(question)
        val ms = ticker() - start
        val ids = sources.map { it.id } + sources.map { it.captureId } + extraIds
        traces.write(trace(id, question, run, route, ids, text, writtenBy, outcome, error, ms, prompt))
        Dbg.event(DbgComponent.ASK, DbgCode.ASKED, n1 = code(route), n2 = code(outcome), refId = id, ms = ms)
        return AskResult(id, text, kind, sources, writtenBy, privateCount)
    }

    private fun trace(
        id: String, question: String, run: Run, route: TraceRoute, sourceIds: List<String>, text: String, writtenBy: WrittenBy,
        outcome: TraceOutcome, error: String?, ms: Long, prompt: String?,
    ): AnswerTraceEntity {
        val t = now()
        return AnswerTraceEntity(
            id = id, at = t, question = question, route = route, routeName = run.routeName, stepsJson = TraceJson.steps(run.steps),
            sourceIds = TraceJson.ids(sourceIds), answerText = text, writtenBy = writtenBy, outcome = outcome, errorCode = error,
            msTotal = ms.coerceAtLeast(0), markedWrong = false, appVersion = appVersion, routerVersion = Router.VERSION,
            promptVersion = prompt, updatedAt = t,
        )
    }

    /** "That's not it" (card 08 item 17): the trace is marked, the question goes to the miss log, and the plain search list comes back. */
    suspend fun markWrong(traceId: String): List<SourceNote> {
        val t = traces.markWrong(traceId) ?: return emptyList()
        Dbg.event(DbgComponent.ASK, DbgCode.MARKED_WRONG, refId = traceId)
        if (t.question.isBlank()) return emptyList()
        return lookups.searchNotes(t.question, includeSensitive = false).map(AnswerWriter::source)
    }

    /** "Why this answer?": the trace in plain sentences, or null when it has been deleted. */
    suspend fun why(traceId: String): List<String>? = traces.get(traceId)?.let(TraceWords::explain)

    private fun code(r: TraceRoute) = when (r) { TraceRoute.NONE -> 0; TraceRoute.ROUTER -> 1; TraceRoute.LOOP -> 2; TraceRoute.SEARCH -> 3 }
    private fun code(o: TraceOutcome) = when (o) { TraceOutcome.ANSWERED -> 1; TraceOutcome.GAVE_UP -> 2; TraceOutcome.NO_MATCH -> 3 }
}
