package app.stash

import app.stash.ask.AboutSubject
import app.stash.ask.AnswerWriter
import app.stash.ask.CaptureHit
import app.stash.ask.DateRange
import app.stash.ask.EventHit
import app.stash.ask.LoanHit
import app.stash.ask.NoteHit
import app.stash.ask.QuestionDates
import app.stash.ask.Route
import app.stash.ask.Router
import app.stash.ask.TraceJson
import app.stash.ask.TraceStep
import app.stash.ask.TraceWords
import app.stash.ask.WhereIs
import app.stash.ask.Whereabout
import app.stash.data.AnswerTraceEntity
import app.stash.data.AnswerTraceRepository
import app.stash.data.Direction
import app.stash.data.TraceOutcome
import app.stash.data.TraceRoute
import app.stash.data.WrittenBy
import app.stash.domain.MetricInfo
import app.stash.domain.ReadingCatalog
import app.stash.domain.ReadingQuestion
import app.stash.domain.RecordKind
import app.stash.domain.SubjectInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Card 08 pass 1, pure parts: the router table, question dates, coded answer wording, and the trace in words. */
class AskRouterTest {
    private val today = LocalDate.of(2026, 11, 12) // a Thursday
    private val catalog = ReadingCatalog(
        metrics = listOf(
            MetricInfo("m-ph", "pH", "ph", emptyList(), "s-pool", "Pool", 3_000),
            MetricInfo("m-fc", "free chlorine", "free_chlorine", listOf("chlorine"), "s-pool", "Pool", 3_000),
            MetricInfo("m-price", "price", "price", emptyList(), "s-tide", "Tide", 2_000),
        ),
        subjects = listOf(SubjectInfo("s-pool", "Pool", emptyList(), 3_000), SubjectInfo("s-tide", "Tide", emptyList(), 2_000)),
    )

    private fun route(q: String) = Router.route(q, catalog, today)
    private fun day(m: Int, d: Int) = LocalDate.of(2026, m, d)

    // ---- the router table (at least 40 questions, the owner's three among them, and negatives)

    @Test fun theOwnersThreeQuestionsAreRoutedByCode() {
        assertEquals(Route.OpenTasks(null), route("What are the things I said I should handle?"))
        assertEquals(Route.LastReading(ReadingQuestion.LastValue("m-ph", named = false)), route("What was my pH last time?"))
        assertEquals(Route.WhereIs("glasses"), route("Where did I put my glasses?"))
    }

    @Test fun theRouterTable() {
        val table: List<Pair<String, Route>> = listOf(
            "What should I handle?" to Route.OpenTasks(null),
            "What do I need to do today" to Route.OpenTasks(null),
            "What's on my list?" to Route.OpenTasks(null),
            "Anything I need to do?" to Route.OpenTasks(null),
            "What do I need at Costco?" to Route.OpenTasks("costco"),
            "What do I need from Home Depot?" to Route.OpenTasks("home depot"),
            "What was I going to buy at Costco?" to Route.OpenTasks("costco"),
            "What am I supposed to get at Target" to Route.OpenTasks("target"),
            "What's my latest free chlorine?" to Route.LastReading(ReadingQuestion.LastValue("m-fc", false)),
            "what's my pH" to Route.LastReading(ReadingQuestion.LastValue("m-ph", false)),
            "How is the pool?" to Route.LastReading(ReadingQuestion.LastSession("s-pool")),
            "Last pool test" to Route.LastReading(ReadingQuestion.LastSession("s-pool")),
            "What was the Tide price last time?" to Route.LastReading(ReadingQuestion.LastValue("m-price", false)),
            "Where is my passport" to Route.WhereIs("passport"),
            "Where are the car keys?" to Route.WhereIs("car keys"),
            "Where's my drill now?" to Route.WhereIs("drill"),
            "Where did I leave the ladder" to Route.WhereIs("ladder"),
            "Where do I keep the spare key" to Route.WhereIs("spare key"),
            "where were my sunglasses" to Route.WhereIs("sunglasses"),
            "Hey, where is my wallet?" to Route.WhereIs("wallet"),
            "Who has my guitar?" to Route.Loans(null, "guitar"),
            "Who did I lend the drill to?" to Route.Loans(null, "drill"),
            "Who borrowed my ladder" to Route.Loans(null, "ladder"),
            "What did I lend Frank?" to Route.Loans("frank", null),
            "What does Frank have?" to Route.Loans("frank", null),
            "What have I lent out?" to Route.Loans(null, null),
            "When did I last change the furnace filter?" to Route.LastEvent("change the furnace filter"),
            "When was the last time I washed the car" to Route.LastEvent("washed the car"),
            "When did I water the plants last" to Route.LastEvent("water the plants"),
            "What did I say yesterday?" to Route.OnDate(DateRange(day(11, 11), day(11, 11)), "yesterday"),
            "What did I note last Tuesday?" to Route.OnDate(DateRange(day(11, 10), day(11, 10)), "last tuesday"),
            "What did I say on the 3rd?" to Route.OnDate(DateRange(day(11, 3), day(11, 3)), "on the 3rd"),
            "What did I note this week?" to Route.OnDate(DateRange(day(11, 9), day(11, 12)), "this week"),
            "What did I write down today" to Route.OnDate(DateRange(today, today), "today"),
            "What did I say on November 3rd?" to Route.OnDate(DateRange(day(11, 3), day(11, 3)), "on november 3rd"),
            "What did I note 3 days ago" to Route.OnDate(DateRange(day(11, 9), day(11, 9)), "3 days ago"),
            "What did I note last week?" to Route.OnDate(DateRange(day(11, 2), day(11, 8)), "last week"),
            "Tell me about the pool" to Route.About("pool"),
            "What do I know about Frank?" to Route.About("frank"),
        )
        for ((q, want) in table) assertEquals(q, want, route(q))
        assertTrue(table.size + 3 >= 40)
    }

    @Test fun questionsTheRouterDoesNotKnowReturnNone() {
        val negatives = listOf(
            "What did I handle yesterday",
            "What was my plan last time",
            "Is it going to rain tomorrow",
            "I put my glasses in the drawer",
            "What did I say about the drill",
            "Where did I go yesterday",
            "How is the weather",
            "",
            "Remind me to call Frank",
            "What did I note in the summer",
            "When is my dentist appointment",
            "Who is Frank",
            "What did I say tomorrow",
        )
        for (q in negatives) assertNull(q, route(q))
    }

    @Test fun theNamesInARouteNameTheTraceRow() {
        assertEquals("where_is", Route.WhereIs("x").name)
        assertEquals("open_tasks_at_place", Route.OpenTasks("costco").name)
        assertEquals("open_tasks", Route.OpenTasks(null).name)
        assertEquals("r1", Router.VERSION)
    }

    // ---- question dates

    @Test fun timePhrasesResolveOnThePhone() {
        fun p(s: String) = QuestionDates.parse(s, today)
        assertEquals(DateRange(day(11, 11), day(11, 11)), p("yesterday"))
        assertEquals(DateRange(today, today), p("this morning"))
        assertEquals(DateRange(day(11, 9), today), p("this week"))
        assertEquals(DateRange(day(11, 2), day(11, 8)), p("last week"))
        assertEquals(DateRange(day(11, 10), day(11, 10)), p("on tuesday"))
        assertEquals(DateRange(today, today), p("thursday"))
        assertEquals(DateRange(day(11, 5), day(11, 5)), p("last thursday"))
        assertEquals(DateRange(day(10, 20), day(10, 20)), p("the 20th"))
        assertEquals(DateRange(day(11, 3), day(11, 3)), p("on the third"))
        assertEquals(DateRange(day(10, 26), day(11, 1)), p("2 weeks ago"))
        assertEquals(DateRange(day(11, 1), today), p("this month"))
        assertEquals(DateRange(day(10, 1), day(10, 31)), p("last month"))
        assertNull(p("tomorrow"))
        assertNull(p("in the summer"))
        assertNull(p("yesterday at costco"))
    }

    // ---- coded answers

    private fun w(where: String?, age: String, dir: Direction = Direction.PLACED, person: String? = null, id: String = "r") =
        Whereabout(id, "c-$id", "reading glasses", where, person, dir, age, null, "I'm putting my reading glasses $where")

    @Test fun whereIsSaysTheAgeAndWhenItMayHaveMoved() {
        assertEquals(
            "Nightstand drawer. You said so 3 weeks ago.",
            AnswerWriter.whereIs(WhereIs(w("in the nightstand drawer", "3 weeks ago"), emptyList(), stale = false))!!.text,
        )
        val stale = AnswerWriter.whereIs(
            WhereIs(w("in the nightstand drawer", "3 weeks ago", id = "a"), listOf(w("on the kitchen counter", "2 months ago", id = "b")), stale = true),
        )!!
        assertEquals("Nightstand drawer. You said so 3 weeks ago, so it may have moved. Before that: on the kitchen counter, 2 months ago.", stale.text)
        assertEquals(listOf("a", "b"), stale.sources.map { it.id })
        assertEquals("Frank has it. You said so 2 days ago.", AnswerWriter.whereIs(WhereIs(w(null, "2 days ago", Direction.LENT, "frank"), emptyList(), false))!!.text)
        assertNull(AnswerWriter.whereIs(null))
    }

    @Test fun loansNameThePersonAndSince() {
        val l = LoanHit("r1", "c1", "my guitar", "Frank", Direction.LENT, "on January 5th", "3 weeks ago")
        assertEquals("Frank has your guitar, since January 5th.", AnswerWriter.loans(listOf(l))!!.text)
        assertEquals("Frank has your guitar (you said so 3 weeks ago).", AnswerWriter.loans(listOf(l.copy(saidDate = null)))!!.text)
        assertEquals("You have Frank's drill (you said so 2 days ago).", AnswerWriter.loans(listOf(LoanHit("r2", "c2", "the drill", "frank", Direction.BORROWED, null, "2 days ago")))!!.text)
        assertNull(AnswerWriter.loans(emptyList()))
    }

    @Test fun lastEventIsSaidBackAsYouDidWithItsAge() {
        assertEquals("You changed the furnace filter", AnswerWriter.youDid("I changed the furnace filter today."))
        assertEquals("You watered your plants", AnswerWriter.youDid("I watered my plants on Tuesday"))
        assertNull(AnswerWriter.youDid("Furnace filter changed"))
        val a = AnswerWriter.lastEvent(
            listOf(
                EventHit("r1", "c1", "Changed the furnace filter", "I changed the furnace filter today", "5 weeks ago"),
                EventHit("r2", "c2", "Changed the furnace filter", "I changed the furnace filter", "4 months ago"),
            ),
        )!!
        assertEquals("You changed the furnace filter 5 weeks ago. Before that: 4 months ago.", a.text)
        assertEquals(listOf("r1", "r2"), a.sources.map { it.id })
        assertEquals("You noted \"Furnace filter changed\", yesterday.", AnswerWriter.lastEvent(listOf(EventHit("r", "c", "Furnace filter changed.", "furnace filter changed", "yesterday")))!!.text)
        assertNull(AnswerWriter.lastEvent(emptyList()))
    }

    @Test fun onADateListsWhatWasNoted() {
        val a = AnswerWriter.onDate(
            "last tuesday",
            listOf(CaptureHit("c1", "Buy milk", "Buy milk at Costco", "2 days ago", listOf("r1")), CaptureHit("c2", "Pool test", "Pool test: pH 7.2", "2 days ago", emptyList())),
        )!!
        assertEquals("Last Tuesday you noted 2 things: Buy milk at Costco; Pool test: pH 7.2.", a.text)
        assertEquals(listOf("r1", "c2"), a.sources.map { it.id })
        assertNull(AnswerWriter.onDate("yesterday", emptyList()))
    }

    @Test fun aboutCountsByKindAndShowsTheNewest() {
        fun n(id: String, title: String, kind: RecordKind, age: String) = NoteHit(id, "c-$id", kind, title, title, age, null, false)
        val a = AnswerWriter.about(
            AboutSubject(
                "pool",
                mapOf(RecordKind.READING to 3, RecordKind.TASK to 1),
                mapOf(RecordKind.READING to listOf(n("a", "Pool test", RecordKind.READING, "2 days ago"), n("b", "Pool test", RecordKind.READING, "9 days ago")), RecordKind.TASK to listOf(n("t", "Clean the pool filter", RecordKind.TASK, "yesterday"))),
            ),
        )!!
        assertEquals("About pool you have 4 notes: 3 readings, 1 task. Newest: Clean the pool filter (yesterday); Pool test (2 days ago); Pool test (9 days ago).", a.text)
        assertNull(AnswerWriter.about(null))
    }

    @Test fun placeWordsDropOnlyIn() {
        assertEquals("nightstand drawer", AnswerWriter.placeWords("in the nightstand drawer"))
        assertEquals("on the top shelf", AnswerWriter.placeWords("on the top shelf"))
        assertEquals("garage", AnswerWriter.placeWords("in garage."))
    }

    // ---- the trace in words, and its JSON

    private fun trace(steps: List<TraceStep>, route: TraceRoute = TraceRoute.ROUTER, routeName: String? = "last_reading", outcome: TraceOutcome = TraceOutcome.ANSWERED, q: String = "what was my pH") =
        AnswerTraceEntity("t", 1, q, route, routeName, TraceJson.steps(steps), "[\"r1\"]", "pH was 7.2", WrittenBy.CODE, outcome, null, 3, false, "test", "r1", null, 1)

    @Test fun whyThisAnswerReadsLikeTheCard() {
        val lines = TraceWords.explain(trace(listOf(TraceStep("readings", mapOf("metric" to "pH", "subject" to "Pool"), listOf("r1", "r2")))))
        assertEquals(
            listOf("I took this as a 'last reading' question.", "I looked up pH for Pool and found 2 readings.", "The answer was written by the app, not the model."),
            lines,
        )
        val fell = TraceWords.explain(
            trace(
                listOf(TraceStep("where_is", mapOf("item" to "kayak"), emptyList()), TraceStep("search_notes", mapOf("query" to "kayak"), emptyList())),
                route = TraceRoute.NONE, routeName = "where_is", outcome = TraceOutcome.NO_MATCH,
            ),
        )
        assertEquals("I looked up where kayak is and found nothing.", fell[1])
        assertEquals("That found nothing, so I matched the words of your question (\"kayak\") against your notes and found nothing.", fell[2])
        assertEquals("Nothing matched, so the answer says I have no note about that.", fell[3])
    }

    @Test fun traceStepsRoundTripAndBlankingKeepsIdsAndCounts() {
        val steps = listOf(TraceStep("where_is", mapOf("item" to "glasses"), listOf("r1", "r2")), TraceStep("search_notes", mapOf("query" to null), emptyList()))
        assertEquals(steps, TraceJson.parseSteps(TraceJson.steps(steps)))
        val blank = TraceJson.parseSteps(AnswerTraceRepository.blankArgs(TraceJson.steps(steps)))
        assertEquals(listOf(mapOf("item" to ""), mapOf("query" to "")), blank.map { it.args })
        assertEquals(steps.map { it.ids to it.count }, blank.map { it.ids to it.count })
        assertFalse(AnswerTraceRepository.blankArgs(TraceJson.steps(steps)).contains("glasses"))
        assertTrue(AnswerTraceRepository.names("[\"a\",\"b\"]", setOf("b")))
        assertFalse(AnswerTraceRepository.names("[\"a\"]", setOf("ab")))
        assertFalse(AnswerTraceRepository.names("not json", setOf("a")))
        val gone = TraceWords.explain(trace(TraceJson.parseSteps(AnswerTraceRepository.blankArgs(TraceJson.steps(steps))), q = "").copy(answerText = ""))
        assertTrue(gone.toString(), gone.any { it.contains("has since been deleted") })
        assertFalse(gone.toString(), gone.any { it.contains("glasses") })
    }

    @Test fun thePrivateLineIsACountOnly() {
        assertNull(app.stash.ui.privateLine(0))
        assertEquals("1 private note also matches. Private notes are not shown here yet.", app.stash.ui.privateLine(1))
        assertEquals("2 private notes also match. Private notes are not shown here yet.", app.stash.ui.privateLine(2))
    }
}
