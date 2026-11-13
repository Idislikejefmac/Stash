package app.stash

import app.stash.ask.AnswerWriter
import app.stash.ask.AskEngine
import app.stash.ask.AskLoop
import app.stash.ask.AskResult
import app.stash.ask.LookupSource
import app.stash.ask.Lookups
import app.stash.ask.LoopOutcome
import app.stash.ask.TraceJson
import app.stash.ask.TraceStep
import app.stash.data.AnswerTraceEntity
import app.stash.data.Direction
import app.stash.data.Ids
import app.stash.data.MissReason
import app.stash.data.Repository
import app.stash.data.StashDatabase
import app.stash.data.TraceOutcome
import app.stash.data.TraceRoute
import app.stash.data.WrittenBy
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.EventSink
import app.stash.domain.RecordKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.ZoneOffset

/** Card 08 section E and E2: the engine's order, one trace per question on every path, the miss log, and purges. */
@RunWith(RobolectricTestRunner::class)
class AskEngineTest {
    private lateinit var db: StashDatabase
    private lateinit var repo: Repository
    private lateinit var seed: AskTestSeed
    private val clock = FakeClock(1_794_398_400_000L)
    private val events = mutableListOf<List<Any?>>()

    @Before fun setUp() {
        db = TestSupport.memoryDb()
        repo = TestSupport.repo(db, clock)
        seed = AskTestSeed(db, repo, clock)
        Dbg.sink = EventSink { c, code, n1, n2, ref, ms -> synchronized(events) { events += listOf(c, code, n1, n2, ref, ms) } }
    }

    @After fun tearDown() {
        Dbg.sink = null
        db.close()
    }

    private fun engine(loop: AskLoop? = null, source: LookupSource = repo.ask) =
        AskEngine(Lookups(source, clock::now, { ZoneOffset.UTC }), repo.traces, clock::now, { ZoneOffset.UTC }, "test", loop)

    private class FakeLoop(private val usable: Boolean, private val out: (String) -> LoopOutcome) : AskLoop {
        val asked = mutableListOf<String>()
        override fun usable() = usable
        override suspend fun answer(question: String): LoopOutcome { asked += question; return out(question) }
    }

    private suspend fun traces(): List<AnswerTraceEntity> = repo.traces.newest()

    private suspend fun glasses() = seed.note(
        "I'm putting my reading glasses in the nightstand drawer", RecordKind.PLACED, daysAgo = 21.0,
        item = "reading glasses", where = "in the nightstand drawer", direction = Direction.PLACED,
    )

    // ---- the order: routed, routed but empty, loop, search, nothing

    @Test fun aRoutedQuestionIsAnsweredByCodeWithItsSourcesAndOneTrace() = runBlocking {
        val g = glasses()
        val loop = FakeLoop(true) { error("not asked") }
        val r = engine(loop).ask("Where did I put my glasses?")
        assertEquals("Nightstand drawer. You said so 3 weeks ago.", r.text)
        assertEquals(AskResult.Kind.ANSWER, r.kind)
        assertEquals(WrittenBy.CODE, r.writtenBy)
        assertEquals(listOf(g.recordId), r.sources.map { it.id })
        assertEquals(g.captureId, r.sources.single().captureId)
        assertTrue(loop.asked.isEmpty())
        val t = traces().single()
        assertEquals(r.traceId, t.id)
        assertEquals(TraceRoute.ROUTER, t.route); assertEquals("where_is", t.routeName); assertEquals(TraceOutcome.ANSWERED, t.outcome)
        assertEquals(listOf(TraceStep("where_is", mapOf("item" to "glasses"), listOf(g.recordId))), TraceJson.parseSteps(t.stepsJson))
        assertTrue(AnswerTraceEntityIds(t).containsAll(listOf(g.recordId, g.captureId)))
        assertEquals("r1", t.routerVersion); assertEquals("test", t.appVersion); assertNull(t.promptVersion); assertFalse(t.markedWrong)
        assertEquals("Where did I put my glasses?", t.question); assertEquals(r.text, t.answerText)
    }

    private fun AnswerTraceEntityIds(t: AnswerTraceEntity): List<String> =
        org.json.JSONArray(t.sourceIds).let { a -> (0 until a.length()).map { a.getString(it) } }

    @Test fun routedButEmptyGoesToAUsableLoop() = runBlocking {
        val g = glasses()
        val loop = FakeLoop(true) { LoopOutcome.Answered("In the nightstand drawer.", listOf(g.recordId), listOf(TraceStep("model", emptyMap(), listOf(g.recordId))), "a1") }
        val r = engine(loop).ask("Where is my kayak?")
        assertEquals(listOf("Where is my kayak?"), loop.asked)
        assertEquals("In the nightstand drawer.", r.text)
        assertEquals(WrittenBy.MODEL, r.writtenBy)
        assertEquals(listOf(g.recordId), r.sources.map { it.id })
        val t = traces().single()
        assertEquals(TraceRoute.LOOP, t.route); assertEquals(WrittenBy.MODEL, t.writtenBy); assertEquals("a1", t.promptVersion)
        assertEquals(listOf("where_is", "model"), TraceJson.parseSteps(t.stepsJson).map { it.lookup })
    }

    @Test fun noLoopFallsToPlainSearchWithTheCardsHeading() = runBlocking {
        val g = glasses()
        val r = engine(null).ask("anything about the nightstand")
        assertEquals(AnswerWriter.LIST_NO_MODEL, r.text)
        assertEquals(AskResult.Kind.LIST, r.kind)
        assertEquals(listOf(g.recordId), r.sources.map { it.id })
        assertEquals("3 weeks ago", r.sources.single().age)
        val t = traces().single()
        assertEquals(TraceRoute.SEARCH, t.route); assertNull(t.routeName); assertEquals(TraceOutcome.ANSWERED, t.outcome)
        // a routed question whose lookup is empty falls to search too, and its trace says both
        val r2 = engine(FakeLoop(false) { error("not usable, not asked") }).ask("Where is the nightstand key?")
        assertEquals(AnswerWriter.LIST_NO_MODEL, r2.text)
        val t2 = traces().first()
        assertEquals("where_is", t2.routeName); assertEquals(TraceRoute.SEARCH, t2.route)
        assertEquals(listOf("where_is" to 0, "search_notes" to 1), TraceJson.parseSteps(t2.stepsJson).map { it.lookup to it.count })
    }

    @Test fun aLoopThatGivesUpGivesTheListWithItsOwnHeading() = runBlocking {
        val g = glasses()
        val r = engine(FakeLoop(true) { LoopOutcome.GaveUp("LIMIT", listOf(g.recordId), emptyList(), "a1") }).ask("how are things with the nightstand")
        assertEquals(AnswerWriter.LIST_GAVE_UP, r.text)
        val t = traces().single()
        assertEquals(TraceRoute.SEARCH, t.route); assertEquals(TraceOutcome.GAVE_UP, t.outcome); assertEquals("LIMIT", t.errorCode)
        // gave up and nothing matched either
        val r2 = engine(FakeLoop(true) { LoopOutcome.GaveUp("TIMEOUT", emptyList(), emptyList(), "a1") }).ask("what about the kayak")
        assertEquals(AnswerWriter.NOTHING, r2.text)
        assertEquals(TraceOutcome.GAVE_UP, traces().first().outcome)
    }

    @Test fun nothingAnywhereSaysSoAndGoesToTheMissLog() = runBlocking {
        glasses()
        val r = engine().ask("Where is my kayak?")
        assertEquals("I don't have a note about that.", r.text)
        assertEquals(AskResult.Kind.NOTHING, r.kind)
        val t = traces().single()
        assertEquals(TraceRoute.NONE, t.route); assertEquals(TraceOutcome.NO_MATCH, t.outcome); assertEquals("[]", t.sourceIds)
        val miss = db.missLog().all().single()
        assertEquals("Where is my kayak?", miss.question); assertEquals(MissReason.NO_MATCH, miss.reason)
    }

    @Test fun theTaskAndReadingQuestionsOfCards05And07AreRoutes() = runBlocking {
        val milk = seed.note("Buy milk at Costco", RecordKind.TASK, daysAgo = 2.0, title = "Buy milk", atPlace = "Costco")
        seed.said("Pool test: free chlorine 4, pH 7.6, alkalinity 80", daysAgo = 9)
        seed.said("Pool test: free chlorine 5, pH 7.2, alkalinity 70", daysAgo = 2)
        val e = engine()
        assertEquals("You have 1 thing. It is: Buy milk (you said that 2 days ago).", e.ask("What are the things I said I should handle?").text)
        assertEquals("At Costco you have 1 thing: Buy milk (you said that 2 days ago).", e.ask("what do I need at Costco").text)
        assertEquals("pH was 7.2, two days ago. Before that: 7.6, nine days ago.", e.ask("What was my pH last time?").text)
        assertTrue(e.ask("How is the pool?").text.startsWith("Your last pool"))
        val names = traces().map { it.routeName }
        assertEquals(listOf("last_reading", "last_reading", "open_tasks_at_place", "open_tasks"), names)
        val readings = TraceJson.parseSteps(traces()[1].stepsJson).single()
        assertEquals(mapOf("metric" to "pH", "subject" to "Pool"), readings.args)
        assertEquals(2, readings.count)
        assertEquals(
            listOf("I took this as a 'last reading' question.", "I looked up pH for Pool and found 2 readings.", "The answer was written by the app, not the model."),
            e.why(traces()[1].id),
        )
        // no tasks at all is still an answer from code (card 05's wording)
        repo.tasks.markDone(milk.recordId)
        assertEquals("You have nothing on your list.", engine().ask("what should I handle").text)
    }

    @Test fun everyPathWritesExactlyOneTraceIncludingAFailure() = runBlocking {
        glasses()
        val e = engine(FakeLoop(true) { LoopOutcome.Answered("x", emptyList(), emptyList(), "a1") })
        val questions = listOf("Where did I put my glasses?", "Where is my kayak?", "nightstand please", "What did I say yesterday?")
        for ((i, q) in questions.withIndex()) {
            e.ask(q)
            assertEquals(q, i + 1, traces().size)
        }
        val broken = object : LookupSource by repo.ask {
            override suspend fun notes(includeSensitive: Boolean) = throw IllegalStateException("disk")
        }
        val r = engine(source = broken).ask("Where did I put my glasses?")
        assertEquals("I couldn't work that out.", r.text)
        assertEquals(5, traces().size)
        val t = traces().first()
        assertEquals(TraceRoute.NONE, t.route); assertEquals(TraceOutcome.GAVE_UP, t.outcome); assertEquals("THREW", t.errorCode)
        assertEquals("where_is", t.routeName)
        assertTrue(events.any { it[0] == DbgComponent.ASK && it[1] == DbgCode.THREW && it[4] == t.id })
    }

    @Test fun privateMatchesAreACountOnlyAndNeverReachTheLoopOrTheTrace() = runBlocking {
        glasses()
        seed.note("The safe combination is 4417 in the blue book", RecordKind.REFERENCE, daysAgo = 1.0, sensitive = true)
        seed.note("The safe is behind the coats", RecordKind.REFERENCE, daysAgo = 2.0)
        val loop = FakeLoop(true) { q -> LoopOutcome.Answered("Behind the coats.", emptyList(), emptyList(), "a1") }
        val r = engine(loop).ask("tell me the safe combination")
        assertEquals(1, r.privateCount)
        assertFalse(r.toString().contains("4417"))
        assertEquals(listOf("tell me the safe combination"), loop.asked)
        val t = traces().single()
        assertFalse(t.toString().contains("4417"))
        assertFalse(t.toString().contains("blue book"))
        // no private match: no line
        assertEquals(0, engine().ask("where did I put my glasses").privateCount)
    }

    @Test fun purgingASourceBlanksTheTracesWordsAndKeepsItsIds() = runBlocking {
        val g = glasses()
        val other = seed.note("Pool test pH 7.2", RecordKind.READING)
        engine().ask("Where did I put my glasses?")
        engine().ask("anything on the pool")
        repo.captures.softDelete(g.captureId)
        assertTrue(repo.captures.purge(g.captureId))
        val (pool, glassesTrace) = traces()
        assertEquals("", glassesTrace.question); assertEquals("", glassesTrace.answerText)
        assertEquals(mapOf("item" to ""), TraceJson.parseSteps(glassesTrace.stepsJson).single().args)
        assertEquals(listOf(g.recordId), TraceJson.parseSteps(glassesTrace.stepsJson).single().ids)
        assertTrue(engine().why(glassesTrace.id)!!.any { it.contains("has since been deleted") })
        assertEquals("anything on the pool", pool.question)
        assertTrue(AnswerTraceEntityIds(pool).contains(other.recordId))
        assertTrue(events.any { it[0] == DbgComponent.ASK && it[1] == DbgCode.BLANKED && it[2] == 1L })
    }

    @Test fun thatsNotItMarksTheTraceLogsTheMissAndGivesTheList() = runBlocking {
        val g = glasses()
        val e = engine()
        val r = e.ask("Where did I put my glasses?")
        val list = e.markWrong(r.traceId)
        assertEquals(listOf(g.recordId), list.map { it.id })
        assertTrue(traces().single().markedWrong)
        val miss = db.missLog().all().single()
        assertEquals(MissReason.MARKED_WRONG, miss.reason); assertEquals("Where did I put my glasses?", miss.question)
        e.markWrong(r.traceId) // twice is once
        assertEquals(1, db.missLog().all().size)
        assertTrue(e.why(r.traceId)!!.last().contains("not it"))
        assertTrue(e.markWrong("no-such-trace").isEmpty())
        assertTrue(events.any { it[1] == DbgCode.MARKED_WRONG && it[4] == r.traceId })
    }

    @Test fun onlyTheNewest300TracesAreKeptAndTheOwnerCanDeleteThem() = runBlocking {
        repeat(305) { i ->
            repo.traces.write(
                AnswerTraceEntity(Ids.new(), i.toLong(), "q$i", TraceRoute.NONE, null, "[]", "[]", "a", WrittenBy.CODE, TraceOutcome.NO_MATCH, null, 0, false, "t", "r1", null, i.toLong()),
            )
        }
        val kept = traces()
        assertEquals(300, kept.size)
        assertEquals("q304", kept.first().question); assertEquals("q5", kept.last().question)
        assertTrue(repo.traces.delete(kept.first().id))
        assertEquals(299, traces().size)
        repo.traces.deleteAll()
        assertTrue(traces().isEmpty())
    }

    @Test fun eventsCarryCodesAndTheTraceIdOnly() = runBlocking {
        glasses()
        val r = engine().ask("Where did I put my glasses?")
        val asked = events.single { it[0] == DbgComponent.ASK && it[1] == DbgCode.ASKED }
        assertEquals(1L, asked[2]); assertEquals(1L, asked[3]); assertEquals(r.traceId, asked[4])
    }

    // ---- source scans

    private fun mainSources() = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test fun nothingInTheAppAsksForPrivateNotes() {
        // No app gate exists yet (card 02). TaskHook compares tasks within one privacy class (card 05) and is the one exception.
        val offenders = mainSources().filter { it.name != "TaskHook.kt" }.flatMap { f ->
            f.readLines().filter { it.contains("includeSensitive = true") || it.contains("includeSensitive=true") }.map { f.name + ": " + it.trim() }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test fun theAskCodeOpensNoConnectionAndLogsOnlyThroughDbg() {
        val ask = File("src/main/java/app/stash/ask").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() +
            listOf("data/AskRepository.kt", "data/AnswerTraceRepository.kt", "ui/AskAnswer.kt").map { File("src/main/java/app/stash/$it") }
        assertTrue(ask.size >= 8)
        val banned = listOf("java.net", "HttpPoster", "UrlConnection", "Socket", "android.util.Log", "println(", "Log.d(", "model.hosted")
        val hits = ask.flatMap { f -> f.readLines().filter { l -> banned.any { l.contains(it) } }.map { f.name + ": " + it.trim() } }
        assertEquals(emptyList<String>(), hits)
    }

    @Test fun theViewModelNoLongerAnswersQuestionsItself() {
        val vm = File("src/main/java/app/stash/ui/MainViewModel.kt").readText()
        for (gone in listOf("HostedAnswerer", "TaskQuestions.parse", "readings.answer(", "AnswerComposer", "Searcher.search(")) assertFalse(gone, vm.contains(gone))
        assertTrue(vm.contains("askEngine.ask("))
    }
}
