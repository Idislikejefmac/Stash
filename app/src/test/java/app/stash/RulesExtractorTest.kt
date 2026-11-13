package app.stash

import app.stash.data.Confidence
import app.stash.data.Direction
import app.stash.domain.ExtractedRecord
import app.stash.domain.ExtractionChecks
import app.stash.domain.ExtractionJson
import app.stash.domain.RecordKind
import app.stash.model.ExtractContext
import app.stash.model.ExtractResult
import app.stash.model.KnownSubject
import app.stash.model.rules.RulesExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesExtractorTest {
    private val x = RulesExtractor()
    private fun one(text: String, ctx: ExtractContext = ExtractContext()): ExtractedRecord {
        val all = x.parse(text, ctx)
        assertEquals("one record for: $text", 1, all.size)
        return all.single()
    }

    // ---- the owner's real dictated sentences ----

    @Test fun candleAtRossIsAShoppingTaskNotOther() {
        val s = "I'm just looking at my warm tobacco pipe candle and I want to remember to purchase one the next time I go to Ross"
        val r = one(s)
        assertEquals(RecordKind.TASK, r.kind)
        assertEquals("Ross", r.atPlace)
        assertEquals("warm tobacco pipe candle", r.item)
        assertTrue(r.item!!.contains("candle"))
        assertEquals(listOf("shopping"), r.tags)
        assertNull(r.due) // "the next time" is a place, not a date
        assertEquals("Purchase warm tobacco pipe candle at Ross", r.title)
        assertTrue(ExtractionChecks.quoteInText(r.quote, s))
        // It survives the checks as a task, with no attention flag.
        val checked = ExtractionChecks.check(listOf(r), s).single()
        assertEquals(RecordKind.TASK, checked.record.kind)
        assertNull(checked.attention)
    }

    @Test fun finishAddingFeaturesIsATask() {
        val r = one("Finish adding in the features for the app development")
        assertEquals(RecordKind.TASK, r.kind)
        assertEquals("Finish adding in the features for the app development", r.title)
        assertNull(r.due)
    }

    @Test fun updatingTheAppRightNowIsAnEventNotATask() {
        val r = one("I am updating the app for stash right now")
        assertEquals(RecordKind.EVENT, r.kind)
        assertNull(r.due)
    }

    @Test fun readingGlassesPlaced() {
        val r = one("I'm putting my reading glasses in the nightstand drawer")
        assertEquals(RecordKind.PLACED, r.kind)
        assertEquals("reading glasses", r.item)
        assertEquals("in the nightstand drawer", r.where)
        assertNull(r.spot) // "nightstand drawer" is not "drawer 3"
    }

    @Test fun guitarLoanWithSpokenDate() {
        val r = one("I loaned my guitar to Frank on January fifth")
        assertEquals(RecordKind.LOAN, r.kind)
        assertEquals("guitar", r.item)
        assertEquals("Frank", r.person)
        assertEquals(Direction.LENT, r.direction)
        assertEquals(1, r.whenExpr?.month)
        assertEquals(5, r.whenExpr?.day)
        assertEquals("January fifth", r.whenExpr?.said)
    }

    @Test fun groundBeefAtCostco() {
        val r = one("I need to remember to buy ground beef at Costco")
        assertEquals(RecordKind.TASK, r.kind)
        assertEquals("ground beef", r.item)
        assertEquals("Costco", r.atPlace)
        assertEquals(Confidence.MEDIUM, r.sure)
    }

    @Test fun poolReadingKeepsValuesAsSpoken() {
        val s = "I just tested the pool water, free chlorine 5, pH 7.2, alkalinity 7"
        val r = one(s)
        assertEquals(RecordKind.READING, r.kind)
        assertEquals("pool", r.subject)
        assertEquals(
            listOf("free chlorine" to "5", "pH" to "7.2", "alkalinity" to "7"),
            r.readings.map { it.metric to it.value },
        )
        assertEquals(listOf(5.0, 7.2, 7.0), r.readings.map { it.number })
        // Passes its own checks: every number is in the quote.
        assertNull(ExtractionChecks.check(listOf(r), s).single().attention)
    }

    @Test fun poolReadingWithNoCommasAndAlkalinity70() {
        val r = one("Pool test free chlorine 5 pH 7.2 alkalinity 70")
        assertEquals(RecordKind.READING, r.kind)
        assertEquals("Pool", r.subject)
        assertEquals(3, r.readings.size)
        assertEquals(listOf("5", "7.2", "70"), r.readings.map { it.value })
    }

    @Test fun poolReadingSaidInWords() {
        val r = one("Pool test free chlorine five pH seven point two alkalinity seventy")
        assertEquals(RecordKind.READING, r.kind)
        assertEquals(listOf("five", "seven point two", "seventy"), r.readings.map { it.value })
        assertEquals(listOf(5.0, 7.2, 70.0), r.readings.map { it.number })
    }

    @Test fun poolNumbersAreNeverCorrected() {
        // The recogniser may hear "72" for 7.2. The value stays as heard.
        val r = one("Pool test free chlorine 5 pH 72 alkalinity 70")
        assertEquals("72", r.readings[1].value)
        assertEquals(72.0, r.readings[1].number)
    }

    @Test fun tidePriceAtCostco() {
        val r = one("The Tide is on sale for 24.99 at Costco")
        assertEquals(RecordKind.READING, r.kind)
        assertEquals("Tide", r.subject)
        assertEquals("Costco", r.place)
        val p = r.readings.single()
        assertEquals("price", p.metric)
        assertEquals("24.99", p.value)
        assertEquals(24.99, p.number!!, 0.0)
        assertNull(p.unit) // he did not say a currency, so none is invented
    }

    @Test fun tidePriceWithDollarSign() {
        val r = one("the Tide is on sale for \$24.99 at Costco")
        val p = r.readings.single()
        assertEquals("\$24.99", p.value)
        assertEquals("$", p.unit)
        assertEquals("Costco", r.place)
    }

    @Test fun tidePriceSaidInWords() {
        val s = "the Tide is on sale for twenty four ninety nine at Costco"
        val r = one(s)
        assertEquals("twenty four ninety nine", r.readings.single().value)
        assertEquals(24.99, r.readings.single().number!!, 0.0)
        assertNull(ExtractionChecks.check(listOf(r), s).single().attention)
    }

    @Test fun pricePerBottle() {
        val r = one("The Valpolicella is \$4.97 per bottle at Total Wine")
        val p = r.readings.single()
        assertEquals("$/bottle", p.unit)
        assertEquals("Total Wine", r.place)
    }

    @Test fun cleanOutTheCarSoon() {
        val r = one("I have to remember to clean out the back of the car soon")
        assertEquals(RecordKind.TASK, r.kind)
        assertEquals("Clean out the back of the car", r.title)
        assertEquals("soon", r.due?.named)
        assertEquals("soon", r.due?.said)
    }

    // ---- other shapes ----

    @Test fun valpolicellaGivesSpotAndPhotoHint() {
        val r = one("It's a Valpolicella by Allegrini, in the Italian section, row 5, bucket 10")
        assertEquals(RecordKind.REFERENCE, r.kind)
        assertEquals("Italian section, row 5, bucket 10", r.spot)
        assertEquals(true, r.photoHint)
        assertNull(r.place)
    }

    @Test fun cleanedOutTheCarIsAnEvent() {
        val r = one("I cleaned out the car")
        assertEquals(RecordKind.EVENT, r.kind)
    }

    @Test fun boughtJeansAtRoss() {
        val r = one("Just bought some jeans at Ross")
        assertEquals(RecordKind.EVENT, r.kind)
        assertEquals("Ross", r.place)
    }

    @Test fun changedFilterToday() {
        val r = one("I changed the furnace filter today")
        assertEquals(RecordKind.EVENT, r.kind)
        assertEquals("today", r.whenExpr?.named)
    }

    @Test fun loanBorrowedAndReturned() {
        assertEquals(Direction.BORROWED, one("I borrowed a ladder from Frank").direction)
        val back = one("Frank gave my guitar back")
        assertEquals(RecordKind.LOAN, back.kind)
        assertEquals(Direction.RETURNED, back.direction)
        assertEquals("Frank", back.person)
        assertEquals("guitar", back.item)
        val back2 = one("I got my drill back from Syd")
        assertEquals(Direction.RETURNED, back2.direction)
        assertEquals("Syd", back2.person)
        val back3 = one("I returned the ladder to Frank")
        assertEquals(Direction.RETURNED, back3.direction)
        assertEquals("ladder", back3.item)
    }

    @Test fun returnedToAPlaceIsNotALoan() {
        assertEquals(RecordKind.EVENT, one("I returned the library book to the library").kind)
    }

    @Test fun taskDueHints() {
        assertEquals("tomorrow", one("Remember to call the dentist tomorrow").due?.named)
        assertEquals("this_weekend", one("I should mow the lawn this weekend").due?.named)
        assertEquals("next_week", one("Don't forget to send the form next week").due?.named)
        val fri = one("I need to pay the bill by Friday").due!!
        assertEquals("fri", fri.weekday)
        assertEquals("by", fri.strictness)
        assertEquals(RecordKind.TASK, one("I must renew the registration").kind)
        assertEquals(RecordKind.TASK, one("I want to learn guitar").kind)
        assertEquals(Confidence.LOW, one("I should call mom").sure)
    }

    @Test fun rememberToBuyWithoutPlace() {
        val r = one("Remember to buy milk")
        assertEquals("milk", r.item)
        assertNull(r.atPlace)
        assertEquals("Buy milk", r.title)
    }

    @Test fun twoClausesFirstTaskWins() {
        val s = "I put the ladder in the shed and I need to buy batteries at Costco"
        val r = one(s)
        assertEquals(RecordKind.TASK, r.kind)
        assertEquals("batteries", r.item)
        // Only the task clause is the quote; the ladder half stays in the capture's text and its search index.
        assertEquals("I need to buy batteries at Costco", r.quote)
    }

    @Test fun ideas() {
        assertEquals(RecordKind.IDEA, one("What if we put a hot tub in the back").kind)
        assertEquals(RecordKind.IDEA, one("I wonder if the pool pump is too big").kind)
    }

    @Test fun placedFurnitureIsIn() {
        val r = one("My passport is in the top drawer")
        assertEquals(RecordKind.PLACED, r.kind)
        assertEquals("passport", r.item)
    }

    @Test fun fillerWordsAreSkipped() {
        assertEquals(RecordKind.PLACED, one("Um, I put my keys on the hook by the door").kind)
        assertEquals(RecordKind.TASK, one("Okay so I need to remember to water the plants").kind)
    }

    @Test fun spotWithBinNumber() {
        val r = one("I put the spare filter in the garage, shelf 3")
        assertEquals(RecordKind.PLACED, r.kind)
        assertEquals("shelf 3", r.spot)
    }

    @Test fun containerWordGivesAPhotoHintButATaskDoesNot() {
        assertEquals(true, one("The red bottle of cleaner is under the sink").photoHint)
        assertNull(one("I need to remember to buy a new bottle of olive oil").photoHint)
    }

    @Test fun knownMetricsFromContextHelpReadings() {
        val ctx = ExtractContext(subjects = listOf(KnownSubject("hot tub", listOf("bromine"))))
        val r = one("Hot tub bromine 4 pH 7.4", ctx)
        assertEquals(RecordKind.READING, r.kind)
        assertEquals("Hot tub", r.subject)
        assertEquals(listOf("bromine", "pH"), r.readings.map { it.metric })
    }

    @Test fun unitsAfterNumbers() {
        val r = one("Pool test chlorine 3 ppm pH 7.4")
        assertEquals("ppm", r.readings[0].unit)
        assertNull(r.readings[1].unit)
        assertEquals("pH", r.readings[1].metric)
    }

    // ---- ten that must be OTHER (and never invent) ----

    @Test fun mustBeOther() {
        val others = listOf(
            "The wifi password is hunter2",
            "Meeting is at 3",
            "Nice weather today",
            "Frank is a good guy",
            "The game starts at seven",
            "Syd has the spare key in the frunk",
            "What a great day",
            "Testing one two three",
            "Thinking about the trip to Denver",
            "I am going to Costco",
            "Remember the Alamo",
            "I need 2 apples and 3 oranges",
        )
        for (s in others) {
            val r = one(s)
            assertEquals("should be OTHER: $s", RecordKind.OTHER, r.kind)
            assertTrue(r.readings.isEmpty())
            assertEquals(s, r.quote)
        }
    }

    @Test fun emptyTextGivesNoRecordAndTheChecksMakeOther() {
        assertTrue(x.parse("").isEmpty())
        assertTrue(x.parse("   .  ").isEmpty())
        assertEquals(RecordKind.OTHER, ExtractionChecks.check(x.parse(""), "").single().record.kind)
    }

    @Test fun everyRecordPassesItsOwnChecksWithNoAttention() {
        val texts = listOf(
            "I'm putting my reading glasses in the nightstand drawer",
            "I loaned my guitar to Frank on January fifth",
            "I need to remember to buy ground beef at Costco",
            "I just tested the pool water, free chlorine 5, pH 7.2, alkalinity 7",
            "The Tide is on sale for 24.99 at Costco",
            "I have to remember to clean out the back of the car soon",
            "Finish adding in the features for the app development",
            "I am updating the app for stash right now",
            "Just bought some jeans at Ross",
            "It's a Valpolicella by Allegrini, in the Italian section, row 5, bucket 10",
            "Frank gave my guitar back",
        )
        for (s in texts) {
            val c = ExtractionChecks.check(x.parse(s), s).single()
            assertNull("attention for: $s", c.attention)
            assertEquals("kind changed by checks for: $s", x.parse(s).single().kind, c.record.kind)
        }
    }

    @Test fun extractWrapsRecordsAndJsonRoundTrips() {
        val res = x.extract("I need to remember to buy milk at Costco", ExtractContext()) as ExtractResult.Ok
        assertEquals("rules-2", x.name)
        val parsed = ExtractionJson.parse(res.rawJson)
        assertNotNull(parsed)
        assertEquals(res.records, parsed)
        assertFalse(res.records.isEmpty())
    }
}
