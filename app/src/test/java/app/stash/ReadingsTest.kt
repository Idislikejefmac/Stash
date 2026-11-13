package app.stash

import app.stash.data.CaptureEntity
import app.stash.data.CaptureSource
import app.stash.data.ExtractionRunner
import app.stash.data.Repository
import app.stash.data.StashDatabase
import app.stash.data.Store
import app.stash.domain.ExtractedReading
import app.stash.domain.ExtractedRecord
import app.stash.domain.ExtractionJson
import app.stash.domain.RecordKind
import app.stash.model.ExtractContext
import app.stash.model.ExtractResult
import app.stash.model.rules.RulesExtractor
import app.stash.pipeline.ReadingHook
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.ZoneOffset

/** Card 07 pass 1 against a real in-memory database: rows, flags, queries, answers and privacy. */
@RunWith(RobolectricTestRunner::class)
class ReadingsTest {
    private lateinit var db: StashDatabase
    private lateinit var repo: Repository
    private lateinit var runner: ExtractionRunner
    private val DAY = 86_400_000L
    private val clock = FakeClock(1_794_398_400_000L) // 2026-11-11 12:00 UTC
    private val now = clock.now()
    private val rules = RulesExtractor()
    private lateinit var hook: ReadingHook

    @Before fun setUp() {
        db = TestSupport.memoryDb()
        repo = TestSupport.repo(db, clock)
        runner = ExtractionRunner(db, repo.records, clock) { ZoneOffset.UTC }
        hook = ReadingHook({ Store.Ready(db) }, clock)
    }

    @After fun tearDown() = db.close()

    /** Saves a note made [daysAgo] days ago, reads it with the rules and runs the reading hook, as the pipeline does. */
    private suspend fun say(text: String, daysAgo: Int = 0, sensitive: Boolean = false): CaptureEntity {
        val c = repo.captures.insertCapture(text, text, CaptureSource.VOICE, sensitive = sensitive, createdAt = now - daysAgo * DAY)
        val out = runner.runLocal(c.id, rules) as ExtractionRunner.Outcome.Stored
        hook.afterProjection(c.id, out.recordIds)
        return c
    }

    private suspend fun sayWith(text: String, readings: List<ExtractedReading>, daysAgo: Int = 0): CaptureEntity {
        val c = repo.captures.insertCapture(text, text, CaptureSource.VOICE, createdAt = now - daysAgo * DAY)
        val r = ExtractedRecord(RecordKind.READING, text, text, subject = "pool", readings = readings)
        val out = runner.run(c.id, "fake") { _, _ -> ExtractResult.Ok(listOf(r), ExtractionJson.serialise(listOf(r))) } as ExtractionRunner.Outcome.Stored
        hook.afterProjection(c.id, out.recordIds)
        return c
    }

    private suspend fun metric(label: String) = db.metrics().all().first { it.label == label }

    // ---- the owner's real sentence shapes

    @Test fun thePoolSentenceIsThreeReadingRowsNotOne() = runBlocking {
        val c = say("Pool test: free chlorine 5, pH 7.2, alkalinity 70", daysAgo = 2)
        val record = db.records().forCapture(c.id).single()
        assertEquals(RecordKind.READING, record.kind)
        val rows = db.readings().forRecord(record.id)
        assertEquals(listOf("free chlorine", "pH", "alkalinity"), rows.map { metricLabel(it.metricId) })
        assertEquals(listOf("5", "7.2", "70"), rows.map { it.valueText })
        assertEquals(listOf(5.0, 7.2, 70.0), rows.map { it.valueNum })
        // A unit is stored only if he said one.
        assertTrue(rows.all { it.unit == null })
        assertEquals(1, rows.map { it.subjectId }.distinct().size)
        assertNotNull(rows.first().subjectId)
        assertEquals(now - 2 * DAY, rows.first().measuredAt)
    }

    private suspend fun metricLabel(id: String) = db.metrics().get(id)!!.label

    @Test fun aPriceAtAPlaceIsAReadingWithAPlace() = runBlocking {
        say("The Tide is on sale for \$24.99 at Costco", daysAgo = 21)
        val m = metric("price")
        val row = repo.readings.latest(m.id, 1, includeSensitive = false).single()
        assertEquals("Tide", row.subjectName)
        assertEquals("\$24.99", row.valueText)
        assertEquals(24.99, row.valueNum!!, 0.0001)
        assertEquals("Costco", row.place)
        assertEquals(
            "Tide was \$24.99 at Costco, three weeks ago.",
            repo.readings.answer("What was the Tide price last time?", now, includeSensitive = false),
        )
    }

    @Test fun aPriceSaidTwiceAnswersWithTheLastAndTheOneBefore() = runBlocking {
        say("The Tide is on sale for \$26.49 at Target", daysAgo = 40)
        say("The Tide is on sale for \$24.99 at Costco", daysAgo = 21)
        assertEquals(
            "Tide was \$24.99 at Costco, three weeks ago. Before that: \$26.49 at Target, five weeks ago.",
            repo.readings.answer("what was the Tide price last time", now, includeSensitive = false),
        )
    }

    // ---- the questions, end to end

    private suspend fun threePoolTests() {
        say("Pool test: free chlorine 3, pH 7.6, alkalinity 80", daysAgo = 14)
        say("Pool test: free chlorine 4, pH 7.4, alkalinity 90", daysAgo = 9)
        say("Pool test: free chlorine 5, pH 7.2, alkalinity 70", daysAgo = 2)
    }

    @Test fun whatWasMyPhLastTimeGivesTheLastAndThePreviousValue() = runBlocking {
        threePoolTests()
        assertEquals(
            "pH was 7.2, two days ago. Before that: 7.4, nine days ago.",
            repo.readings.answer("What was my pH last time?", now, includeSensitive = false),
        )
    }

    @Test fun howIsThePoolGivesTheLastSession() = runBlocking {
        threePoolTests()
        assertEquals(
            "Your last pool test was two days ago: free chlorine 5, pH 7.2, alkalinity 70.",
            repo.readings.answer("how is the pool", now, includeSensitive = false),
        )
        assertEquals(
            repo.readings.answer("how is the pool", now, includeSensitive = false),
            repo.readings.answer("last pool test", now, includeSensitive = false),
        )
    }

    @Test fun aQuestionAboutSomethingNeverLoggedIsNotAReadingsQuestion() = runBlocking {
        threePoolTests()
        assertNull(repo.readings.answer("what was my plan last time", now, includeSensitive = false))
        assertNull(repo.readings.answer("how is the car", now, includeSensitive = false))
    }

    // ---- queries (A)

    @Test fun latestSeriesSessionsAndMetrics() = runBlocking {
        threePoolTests()
        val ph = metric("pH")
        assertEquals(listOf("7.2", "7.4"), repo.readings.latest(ph.id, 2, includeSensitive = false).map { it.valueText })
        assertEquals(
            listOf("7.6", "7.4"),
            repo.readings.series(ph.id, now - 20 * DAY, now - 5 * DAY, includeSensitive = false).map { it.valueText },
        )
        val pool = db.subjects().all().single { it.normName == "pool" }
        val session = repo.readings.latestSession(pool.id, includeSensitive = false)!!
        assertEquals(listOf("free chlorine", "pH", "alkalinity"), session.rows.map { it.metricLabel })
        assertEquals(listOf("5", "7.2", "70"), session.rows.map { it.valueText })
        assertEquals(3, repo.readings.sessionsFor(pool.id, includeSensitive = false).size)
        assertEquals(
            sessionDates(),
            repo.readings.sessionsFor(pool.id, includeSensitive = false).map { it.measuredAt },
        )
        assertEquals(listOf("free chlorine", "pH", "alkalinity"), repo.readings.metricsFor(pool.id, includeSensitive = false).map { it.label })
        assertNull(repo.readings.latestSession("nope", includeSensitive = false))
    }

    private fun sessionDates() = listOf(14, 9, 2).map { now - it * DAY }

    @Test fun findMetricMatchesLabelKeyAliasAndBothSubjects() = runBlocking {
        threePoolTests()
        say("Spa test: pH 7.8, free chlorine 3", daysAgo = 1)
        assertEquals(2, repo.readings.findMetric("ph", includeSensitive = false).size)
        assertEquals(2, repo.readings.findMetric("pH", includeSensitive = false).size)
        assertEquals(listOf("Spa", "Pool"), repo.readings.findMetric("pH", includeSensitive = false).map { it.subjectName })
        assertEquals(2, repo.readings.findMetric("free chlorine", includeSensitive = false).size)
        assertTrue(repo.readings.findMetric("chlorine", includeSensitive = false).isEmpty())
        // "chlorine" is an alias once one is stored.
        val fc = db.metrics().all().first { it.label == "free chlorine" && it.subjectId == db.subjects().all().single { s -> s.normName == "pool" }.id }
        db.metrics().update(fc.copy(aliases = "[\"chlorine\"]"))
        assertEquals(listOf("Pool"), repo.readings.findMetric("chlorine", includeSensitive = false).map { it.subjectName })
        assertTrue(repo.readings.findMetric("plan", includeSensitive = false).isEmpty())
    }

    @Test fun aMetricNameUnderTwoSubjectsAnswersForTheNewestAndNamesIt() = runBlocking {
        threePoolTests()
        say("Spa test: pH 7.8, free chlorine 3", daysAgo = 1)
        assertEquals(
            "Spa pH was 7.8, yesterday.",
            repo.readings.answer("what was my pH", now, includeSensitive = false),
        )
        assertEquals(
            "pH was 7.2, two days ago. Before that: 7.4, nine days ago.",
            repo.readings.answer("what was the pool pH", now, includeSensitive = false),
        )
    }

    // ---- flags (B3, B4)

    @Test fun aMisheard72IsStoredAsSaidAndFlaggedAndA7Point8IsNot() = runBlocking {
        say("Pool test: free chlorine 3, pH 7.4, alkalinity 80", daysAgo = 14)
        say("Pool test: free chlorine 4, pH 7.6, alkalinity 90", daysAgo = 9)
        say("Pool test: free chlorine 5, pH 7.2, alkalinity 70", daysAgo = 4)
        val ok = say("Pool test: free chlorine 5, pH 7.8, alkalinity 70", daysAgo = 3)
        assertNull(db.records().forCapture(ok.id).single().attention)
        val odd = say("Pool test: free chlorine 5, pH 72, alkalinity 70", daysAgo = 1)
        val record = db.records().forCapture(odd.id).single()
        // The median of 7.4, 7.6, 7.2 and 7.8 is 7.5.
        assertEquals("ODD_VALUE:pH:7.5", record.attention)
        val row = db.readings().forRecord(record.id).first { metricLabelSync(it.metricId) == "pH" }
        assertEquals("72", row.valueText)
        assertEquals(72.0, row.valueNum)
        assertFalse(record.pinned)
    }

    private fun metricLabelSync(id: String) = runBlocking { metricLabel(id) }

    @Test fun fewerThanThreeEarlierValuesNeverFlag() = runBlocking {
        say("Pool test: free chlorine 5, pH 7.4, alkalinity 70", daysAgo = 5)
        say("Pool test: free chlorine 5, pH 7.6, alkalinity 70", daysAgo = 4)
        val c = say("Pool test: free chlorine 5, pH 72, alkalinity 70", daysAgo = 1)
        assertNull(db.records().forCapture(c.id).single().attention)
    }

    @Test fun theFirstUnitHeardBecomesTheMetricsUnitAndAnotherIsFlagged() = runBlocking {
        sayWith("chlorine 5", listOf(ExtractedReading("chlorine", "5", 5.0, null)), daysAgo = 6)
        assertNull(metric("chlorine").unit)
        val second = sayWith("chlorine 4 ppm", listOf(ExtractedReading("chlorine", "4", 4.0, "ppm")), daysAgo = 4)
        assertEquals("ppm", metric("chlorine").unit)
        assertNull(db.records().forCapture(second.id).single().attention)
        val same = sayWith("chlorine 3 ppm", listOf(ExtractedReading("chlorine", "3", 3.0, "ppm")), daysAgo = 3)
        assertNull(db.records().forCapture(same.id).single().attention)
        val third = sayWith("chlorine 3 mg per liter", listOf(ExtractedReading("chlorine", "3", 3.0, "mg/L")), daysAgo = 1)
        assertEquals("UNIT_CHANGED", db.records().forCapture(third.id).single().attention)
        // Nothing was changed: the unit he said is stored, and the metric keeps the first one.
        assertEquals("mg/L", db.readings().forRecord(db.records().forCapture(third.id).single().id).single().unit)
        assertEquals("ppm", metric("chlorine").unit)
    }

    // ---- privacy

    @Test fun privateReadingsAreNeverReturnedUnlessTheCallerSaysSo() = runBlocking {
        say("Pool test: free chlorine 5, pH 7.2, alkalinity 70", daysAgo = 3)
        say("Pool test: free chlorine 4, pH 7.0", daysAgo = 1, sensitive = true)
        val ph = metric("pH")
        assertEquals(listOf("7.2"), repo.readings.latest(ph.id, 5, includeSensitive = false).map { it.valueText })
        assertEquals(listOf("7.0", "7.2"), repo.readings.latest(ph.id, 5, includeSensitive = true).map { it.valueText })
        assertEquals(1, repo.readings.sessionsFor(db.subjects().all().single { it.normName == "pool" }.id, includeSensitive = false).size)
        assertEquals(2, repo.readings.sessionsFor(db.subjects().all().single { it.normName == "pool" }.id, includeSensitive = true).size)
        assertEquals(
            "pH was 7.2, three days ago.",
            repo.readings.answer("what was my pH", now, includeSensitive = false),
        )
        assertEquals(
            "pH was 7.0, yesterday. Before that: 7.2, three days ago.",
            repo.readings.answer("what was my pH", now, includeSensitive = true),
        )
    }

    @Test fun aSubjectOnlyAPrivateNoteMadeIsInvisibleWithoutPermission() = runBlocking {
        say("Pool test: free chlorine 4, pH 7.0", daysAgo = 1, sensitive = true)
        assertTrue(repo.readings.summaries(includeSensitive = false).isEmpty())
        assertTrue(repo.readings.catalog(includeSensitive = false).metrics.isEmpty())
        assertNull(repo.readings.answer("what was my pH", now, includeSensitive = false))
        assertFalse(repo.readings.summaries(includeSensitive = true).isEmpty())
    }

    @Test fun aPublicReadingIsNeverFlaggedAgainstPrivateHistory() = runBlocking {
        for (d in listOf(8, 7, 6)) say("Pool test: free chlorine 4, pH 100", daysAgo = d, sensitive = true)
        val c = say("Pool test: free chlorine 5, pH 7.2, alkalinity 70", daysAgo = 1)
        assertNull(db.records().forCapture(c.id).single().attention)
    }

    @Test fun nothingInTheAppAsksForPrivateReadings() {
        // No app gate exists yet (card 02): the only callers in the app pass false.
        val offenders = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().filter { l ->
                (l.contains("readings.") || l.contains("ReadingRepository")) && l.contains("includeSensitive = true")
            }.map { f.name + ": " + it.trim() }
        }.toList()
        assertEquals(emptyList<String>(), offenders)
    }

    // ---- deleted notes, the context, and the values staying as said

    @Test fun aDeletedNoteTakesItsReadingsOutOfTheAnswers() = runBlocking {
        threePoolTests()
        val c = say("Pool test: free chlorine 5, pH 7.0, alkalinity 70", daysAgo = 1)
        assertEquals("pH was 7.0, yesterday. Before that: 7.2, two days ago.", repo.readings.answer("what was my pH", now, includeSensitive = false))
        repo.captures.softDelete(c.id)
        assertEquals("pH was 7.2, two days ago. Before that: 7.4, nine days ago.", repo.readings.answer("what was my pH", now, includeSensitive = false))
    }

    @Test fun metricsMadeHereAppearInTheModelsContext() = runBlocking {
        threePoolTests()
        val c = repo.captures.insertCapture("hello", "hello", CaptureSource.VOICE, createdAt = now)
        var seen: ExtractContext? = null
        runner.run(c.id, "spy") { _, ctx -> seen = ctx; ExtractResult.Ok(emptyList(), "[]") }
        val pool = seen!!.subjects.single { it.name.equals("pool", ignoreCase = true) }
        assertTrue(pool.metrics.containsAll(listOf("free chlorine", "pH", "alkalinity")))
    }

    @Test fun readingAgainKeepsEveryValueAsSaid() = runBlocking {
        val c = say("Pool test: free chlorine 5, pH 72, alkalinity 70", daysAgo = 1)
        runner.runLocal(c.id, rules)
        val rows = db.readings().forRecord(db.records().forCapture(c.id).single().id)
        assertEquals(listOf("5", "72", "70"), rows.map { it.valueText })
        assertEquals(1, db.readings().refs().map { it.recordId }.distinct().size)
    }

    @Test fun theHookNeverThrowsWhenTheStoreIsGone() = runBlocking {
        ReadingHook({ Store.NotAvailable }, clock).afterProjection("x", listOf("y"))
        Unit
    }

    @Test fun theSummariesListEachMetricWithItsNewestValue() = runBlocking {
        threePoolTests()
        say("The Tide is on sale for \$24.99 at Costco", daysAgo = 21)
        val s = repo.readings.summaries(includeSensitive = false)
        assertEquals(4, s.size)
        val ph = s.single { it.metric.label == "pH" }
        assertEquals("7.2", ph.latest.valueText)
        assertEquals(3, ph.count)
        assertEquals("Pool", ph.metric.subjectName)
        assertEquals("Costco", s.single { it.metric.label == "price" }.latest.place)
    }
}
