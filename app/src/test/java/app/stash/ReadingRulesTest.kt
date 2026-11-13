package app.stash

import app.stash.domain.Attention
import app.stash.domain.MetricInfo
import app.stash.domain.ReadingAnswers
import app.stash.domain.ReadingCatalog
import app.stash.domain.ReadingFact
import app.stash.domain.ReadingQuestion
import app.stash.domain.ReadingQuestions
import app.stash.domain.ReadingRules
import app.stash.domain.ReadingSession
import app.stash.domain.SubjectInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Card 07 pass 1: the pure rules, the wording and the question patterns. */
class ReadingRulesTest {
    private val DAY = 86_400_000L
    private val now = 1_794_398_400_000L

    private fun fact(
        metric: String, value: String, num: Double?, daysAgo: Int, subject: String? = "Pool", unit: String? = null,
        place: String? = null, recordId: String = "r$daysAgo$metric", quote: String = "pool test",
    ) = ReadingFact(
        "id-$recordId-$metric", recordId, "c-$recordId", subject?.lowercase(), subject, "m-$metric", metric, value, num, unit,
        now - daysAgo * DAY, place, null, quote,
    )

    // ---- outlier (B3)

    @Test fun outlierFlagsA72AgainstSevenPointFourAndNotA7Point8() {
        val history = listOf(7.4, 7.6, 7.2)
        assertEquals(7.4, ReadingRules.outlierMedian(72.0, history)!!, 0.0001)
        assertNull(ReadingRules.outlierMedian(7.8, history))
    }

    @Test fun outlierNeedsThreeEarlierValues() {
        assertNull(ReadingRules.outlierMedian(72.0, listOf(7.4, 7.6)))
        assertNull(ReadingRules.outlierMedian(72.0, emptyList()))
    }

    @Test fun outlierNeverFlagsAZeroMedianOrAWordValue() {
        assertNull(ReadingRules.outlierMedian(5.0, listOf(0.0, 0.0, 0.0)))
        assertNull(ReadingRules.outlierMedian(null, listOf(7.4, 7.6, 7.2)))
    }

    @Test fun outlierEdgesAreExactlyFiveTimes() {
        val h = listOf(10.0, 10.0, 10.0)
        assertNull(ReadingRules.outlierMedian(50.0, h)) // exactly 5 times: not more than
        assertEquals(10.0, ReadingRules.outlierMedian(50.1, h)!!, 0.0001)
        assertNull(ReadingRules.outlierMedian(2.0, h)) // exactly one fifth: not less than
        assertEquals(10.0, ReadingRules.outlierMedian(1.9, h)!!, 0.0001)
    }

    @Test fun reasonKeepsTheMedianAndReadsBack() {
        assertEquals("ODD_VALUE:pH:7.4", ReadingRules.oddValueReason("pH", 7.4))
        assertEquals("ODD_VALUE:free chlorine:5", ReadingRules.oddValueReason("free chlorine", 5.0))
        assertEquals("7.4", ReadingRules.medianOf("ODD_VALUE:pH:7.4"))
        assertNull(ReadingRules.medianOf(Attention.UNIT_CHANGED))
        assertEquals(7.5, ReadingRules.median(listOf(7.4, 7.6))!!, 0.0001)
    }

    // ---- unit drift (B4)

    @Test fun unitDriftNeedsTwoUnitsThatDiffer() {
        assertTrue(ReadingRules.unitDrifted("ppm", "mg/L"))
        assertFalse(ReadingRules.unitDrifted("ppm", "PPM"))
        assertFalse(ReadingRules.unitDrifted("ppm", null)) // a unit is never invented, so none is never a change
        assertFalse(ReadingRules.unitDrifted(null, "ppm"))
        assertEquals("ppm", ReadingRules.unitToStore(null, "ppm"))
        assertNull(ReadingRules.unitToStore("mg/L", "ppm")) // the first unit heard stays
    }

    @Test fun flagsAreSaidPlainlyAndNeverJudge() {
        assertEquals("Very different from the usual (7.4). Check it was heard right.", ReadingRules.flagText("ODD_VALUE:pH:7.4"))
        assertEquals("The unit is not the one used before.", ReadingRules.flagText("UNIT_CHANGED"))
        assertNull(ReadingRules.flagText("BAD_QUOTE"))
        assertNull(ReadingRules.flagText(null))
    }

    // ---- wording (B6)

    @Test fun agoUsesWordsUpToTwelve() {
        assertEquals("two days ago", ReadingAnswers.ago(now - 2 * DAY, now))
        assertEquals("nine days ago", ReadingAnswers.ago(now - 9 * DAY, now))
        assertEquals("three weeks ago", ReadingAnswers.ago(now - 21 * DAY, now))
        assertEquals("yesterday", ReadingAnswers.ago(now - DAY, now))
    }

    @Test fun lastValueWithTwoValues() {
        val rows = listOf(fact("pH", "7.2", 7.2, 2), fact("pH", "7.6", 7.6, 9))
        assertEquals("pH was 7.2, two days ago. Before that: 7.6, nine days ago.", ReadingAnswers.lastValue(rows, now))
    }

    @Test fun lastValueWithOneValueHasNoBeforeThat() {
        assertEquals("pH was 7.2, two days ago.", ReadingAnswers.lastValue(listOf(fact("pH", "7.2", 7.2, 2)), now))
    }

    @Test fun lastValueWithAWordIsQuoted() {
        assertEquals(
            "Water clarity was 'cloudy', two days ago.",
            ReadingAnswers.lastValue(listOf(fact("water clarity", "cloudy", null, 2)), now),
        )
    }

    @Test fun aPriceNamesItsPlace() {
        val rows = listOf(fact("price", "\$24.99", 24.99, 21, subject = "Tide", unit = "\$", place = "Costco"))
        assertEquals("Tide was \$24.99 at Costco, three weeks ago.", ReadingAnswers.lastValue(rows, now))
    }

    @Test fun aPriceWithTwoPlacesNamesBoth() {
        val rows = listOf(
            fact("price", "\$24.99", 24.99, 21, subject = "Tide", unit = "\$", place = "Costco"),
            fact("price", "\$26.49", 26.49, 40, subject = "Tide", unit = "\$", place = "Target"),
        )
        assertEquals(
            "Tide was \$24.99 at Costco, three weeks ago. Before that: \$26.49 at Target, five weeks ago.",
            ReadingAnswers.lastValue(rows, now),
        )
    }

    @Test fun anAmbiguousNameSaysWhichSubject() {
        val rows = listOf(fact("pH", "7.2", 7.2, 2))
        assertEquals("Pool pH was 7.2, two days ago.", ReadingAnswers.lastValue(rows, now, named = true))
    }

    @Test fun valueDisplayShowsAUnitOnlyWhenSaid() {
        assertEquals("7.2", ReadingAnswers.display(fact("pH", "7.2", 7.2, 1)))
        assertEquals("70 ppm", ReadingAnswers.display(fact("alk", "70", 70.0, 1, unit = "ppm")))
        assertEquals("\$24.99", ReadingAnswers.display(fact("price", "24.99", 24.99, 1, unit = "\$")))
        assertEquals("\$24.99/lb", ReadingAnswers.display(fact("price", "\$24.99", 24.99, 1, unit = "\$/lb")))
        assertEquals("98.6", ReadingAnswers.display(fact("temp", "98.6", 98.6, 1)))
    }

    @Test fun lastSessionListsTheValuesInOrder() {
        val rows = listOf(
            fact("free chlorine", "5", 5.0, 2, recordId = "s"), fact("pH", "7.2", 7.2, 2, recordId = "s"),
            fact("alkalinity", "70", 70.0, 2, recordId = "s"),
        )
        val session = ReadingSession("s", "c", now - 2 * DAY, rows)
        assertEquals(
            "Your last pool test was two days ago: free chlorine 5, pH 7.2, alkalinity 70.",
            ReadingAnswers.lastSession("Pool", session, now),
        )
    }

    @Test fun answerNeverSaysWhetherAValueIsGood() {
        val words = listOf(
            ReadingAnswers.lastValue(listOf(fact("pH", "7.2", 7.2, 2), fact("pH", "7.6", 7.6, 9)), now),
            ReadingRules.flagText("ODD_VALUE:pH:7.4").orEmpty(),
        ).joinToString(" ").lowercase()
        for (w in listOf("good", "bad", "too high", "too low", "should", "safe", "dangerous", "ideal", "normal")) {
            assertFalse(w, words.contains(w))
        }
    }

    // ---- the question patterns (D12)

    private val catalog = ReadingCatalog(
        metrics = listOf(
            MetricInfo("m-ph", "pH", "ph", emptyList(), "pool", "Pool", now - 2 * DAY),
            MetricInfo("m-fc", "free chlorine", "free chlorine", listOf("chlorine"), "pool", "Pool", now - 2 * DAY),
            MetricInfo("m-price", "price", "price", emptyList(), "tide", "Tide", now - 21 * DAY),
            MetricInfo("m-sph", "pH", "ph", emptyList(), "spa", "Spa", now - 30 * DAY),
        ),
        subjects = listOf(
            SubjectInfo("pool", "Pool", emptyList(), now - 2 * DAY),
            SubjectInfo("tide", "Tide", emptyList(), now - 21 * DAY),
            SubjectInfo("spa", "Spa", emptyList(), now - 30 * DAY),
        ),
    )

    private fun parse(q: String) = ReadingQuestions.parse(q, catalog)

    @Test fun whatWasMyMetricLastTime() {
        // pH exists under Pool and Spa: the one logged most recently, named.
        assertEquals(ReadingQuestion.LastValue("m-ph", named = true), parse("What was my pH last time?"))
        assertEquals(ReadingQuestion.LastValue("m-ph", named = true), parse("what's my latest pH"))
        assertEquals(ReadingQuestion.LastValue("m-fc", named = false), parse("what was my free chlorine"))
    }

    @Test fun anAliasMatches() {
        assertEquals(ReadingQuestion.LastValue("m-fc", named = false), parse("what was my chlorine last time"))
    }

    @Test fun theSubjectsMetricNamedInTheQuestionPicksThatOne() {
        assertEquals(ReadingQuestion.LastValue("m-sph", named = false), parse("what was the spa pH last time"))
        assertEquals(ReadingQuestion.LastValue("m-ph", named = false), parse("what is the pH of the pool"))
    }

    @Test fun aPriceQuestion() {
        assertEquals(ReadingQuestion.LastValue("m-price", named = false), parse("What was the Tide price last time?"))
        assertEquals(ReadingQuestion.LastValue("m-price", named = false), parse("what was the price of Tide"))
        assertEquals(ReadingQuestion.LastValue("m-price", named = false), parse("what was Tide's price"))
    }

    @Test fun lastSubjectTestAndHowIsTheSubject() {
        assertEquals(ReadingQuestion.LastSession("pool"), parse("last pool test"))
        assertEquals(ReadingQuestion.LastSession("pool"), parse("what was my last pool test"))
        assertEquals(ReadingQuestion.LastSession("pool"), parse("last pool readings"))
        assertEquals(ReadingQuestion.LastSession("pool"), parse("what were the last pool results"))
        assertEquals(ReadingQuestion.LastSession("pool"), parse("how is the pool"))
    }

    @Test fun negativesDoNotMatch() {
        assertNull(parse("what was my plan last time"))
        assertNull(parse("how is the car"))
        assertNull(parse("where did I put my keys"))
        assertNull(parse("what should I handle"))
        assertNull(parse("last week I went to the store"))
        assertNull(parse("what was my pool")) // a subject alone is not a metric and has no "test" word
        assertNull(ReadingQuestions.parse("what was my pH", ReadingCatalog(emptyList(), emptyList())))
    }

    @Test fun aMetricCalledPlanMatchesOnlyOnceItExists() {
        val withPlan = catalog.copy(metrics = catalog.metrics + MetricInfo("m-plan", "plan", "plan", emptyList(), null, null, now))
        assertEquals(ReadingQuestion.LastValue("m-plan", named = false), ReadingQuestions.parse("what was my plan last time", withPlan))
    }
}
