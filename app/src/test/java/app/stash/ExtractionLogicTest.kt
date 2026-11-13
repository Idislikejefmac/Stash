package app.stash

import app.stash.data.CaptureEntity
import app.stash.data.Confidence
import app.stash.data.Direction
import app.stash.data.SubjectType
import app.stash.domain.Attention
import app.stash.domain.DateExpr
import app.stash.domain.ExtractedReading
import app.stash.domain.ExtractedRecord
import app.stash.domain.ExtractionChecks
import app.stash.domain.ExtractionJson
import app.stash.domain.NumberWords
import app.stash.domain.RecordKind
import app.stash.domain.SendableText
import app.stash.domain.SensitiveReason
import app.stash.domain.Sensitivity
import app.stash.domain.SuggestCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExtractionJsonTest {
    private val rec = ExtractedRecord(
        kind = RecordKind.TASK, title = "Buy milk", quote = "buy milk at Costco", item = "milk", atPlace = "Costco",
        due = DateExpr("tomorrow", named = "tomorrow"), tags = listOf("shopping"), sure = Confidence.HIGH,
        readings = listOf(ExtractedReading("price", "\$2", 2.0, "$")), perishable = true, photoHint = false,
        direction = Direction.RETURNED, subjectType = SubjectType.ITEM, whenExpr = DateExpr("Jan 5", month = 1, day = 5),
    )

    @Test fun roundTrips() {
        val json = ExtractionJson.serialise(listOf(rec, ExtractedRecord(RecordKind.OTHER, "t", "q")))
        assertEquals(listOf(rec, ExtractedRecord(RecordKind.OTHER, "t", "q")), ExtractionJson.parse(json))
    }

    @Test fun unknownKindBecomesOther() {
        val r = ExtractionJson.parse("""{"records":[{"kind":"banana","title":"t","quote":"q"}]}""")!!.single()
        assertEquals(RecordKind.OTHER, r.kind)
    }

    @Test fun missingKindBecomesOtherAndDefaultsAreFilledIn() {
        val r = ExtractionJson.parse("""{"records":[{"title":"t","quote":"q"}]}""")!!.single()
        assertEquals(RecordKind.OTHER, r.kind)
        assertEquals(Confidence.MEDIUM, r.sure)
        assertTrue(r.readings.isEmpty() && r.tags.isEmpty() && r.aliases.isEmpty())
    }

    @Test fun wrongShapesGiveNull() {
        assertNull(ExtractionJson.parse("not json"))
        assertNull(ExtractionJson.parse("""{"nope":[]}"""))
        assertNull(ExtractionJson.parse("""{"records":"x"}"""))
        assertNull(ExtractionJson.parse("""{"records":[1]}"""))
        assertNull(ExtractionJson.parse("""{"records":[{"kind":"task","quote":"q"}]}""")) // no title
        assertNull(ExtractionJson.parse("""{"records":[{"kind":"task","title":"t"}]}""")) // no quote
        assertNull(ExtractionJson.parse("""{"records":[{"title":"t","quote":"q","readings":"x"}]}"""))
        assertNull(ExtractionJson.parse("""{"records":[{"title":"t","quote":"q","readings":[{"value":"5"}]}]}"""))
        assertNull(ExtractionJson.parse("""{"records":[{"title":"t","quote":"q","tags":"x"}]}"""))
        assertNull(ExtractionJson.parse("""{"records":[{"title":"t","quote":"q","due":{"named":"soon"}}]}""")) // no "said"
        assertNull(ExtractionJson.parse(""))
    }

    @Test fun emptyRecordsListIsValid() = assertEquals(emptyList<ExtractedRecord>(), ExtractionJson.parse("""{"records":[]}"""))

    @Test fun nullWordsAndBlanksAreAbsentAndNumbersMayBeStrings() {
        val r = ExtractionJson.parse(
            """{"records":[{"kind":"reading","title":"t","quote":"q","item":"null","place":"  ","subject":null,
               "readings":[{"metric":"pH","value":"7.2","number":"7.2","unit":null}]}]}""",
        )!!.single()
        assertNull(r.item); assertNull(r.place); assertNull(r.subject)
        assertEquals(7.2, r.readings.single().number!!, 0.0)
    }

    @Test fun fencedJsonIsAccepted() {
        val r = ExtractionJson.parse("```json\n{\"records\":[{\"kind\":\"idea\",\"title\":\"t\",\"quote\":\"q\"}]}\n```")
        assertEquals(RecordKind.IDEA, r!!.single().kind)
    }

    @Test fun directionPlacedIsIgnored() {
        val r = ExtractionJson.parse("""{"records":[{"kind":"loan","title":"t","quote":"q","direction":"placed"}]}""")!!.single()
        assertNull(r.direction)
    }
}

class SensitivityTest {
    private fun reason(s: String, extra: List<String> = emptyList()) = Sensitivity.check(s, extra)

    @Test fun markersMakeANotePrivate() {
        for (m in listOf("Private note: my dentist is Dr Lee", "private, call mom", "This is private. I owe Frank", "local note buy stamps",
            "Ok private note the thing", "um, this is a private note about work", "This is private")) {
            assertEquals(m, SensitiveReason.SAID, reason(m))
        }
    }

    @Test fun markerMustBeNearTheStartOrTheEnd() {
        // Past the first four words, "private" is just a word.
        assertNull(reason("I keep the spare key to the private jet hangar on the hook"))
        assertNull(reason("The road to the private beach is closed"))
        assertNull(reason("Pick up the new private school forms"))
    }

    @Test fun garbledOpeningStillCounts() {
        // Dictation heard "The private goat cake private gate code is 1234": the marker was garbled into 3rd place.
        val said = "The private goat cake private gate code is 1234"
        assertEquals(SensitiveReason.SAID, reason(said))
        assertEquals("The goat cake private gate code is 1234", Sensitivity.stripMarker(said))
        // Deliberately wide: the early-word rule also catches "I keep a private jet ...". One tap undoes a false alarm.
        assertEquals(SensitiveReason.SAID, reason("I keep a private jet hangar key on the hook"))
        assertEquals("the call mom", Sensitivity.stripMarker("the private note call mom"))
    }

    @Test fun trailingMarkerCounts() {
        for (m in listOf("the gate code is one two three, make that private", "Dentist is Dr Lee. Mark that private.",
            "call Frank tomorrow, this is private", "buy stamps and make this private", "my plan, this is a private note")) {
            assertEquals(m, SensitiveReason.SAID, reason(m))
        }
        assertEquals("the gate code is one two three", Sensitivity.stripMarker("the gate code is one two three, make that private"))
        assertEquals("Dentist is Dr Lee", Sensitivity.stripMarker("Dentist is Dr Lee. Mark that private."))
        assertNull(reason("I told them to make that a bigger font"))
        assertNull(reason("I want to make that privately owned"))
    }

    @Test fun confirmCaptureFromTheOwnerIsNotSensitive() {
        // Reported as sensitive=1 on the phone. No built-in rule fires on it ("lock", "pause" and "weight" are not secrets),
        // so only an always-private word of the owner's, or a tap on Private, can have set it.
        val s = "Confirm to that the pause and weight worked on the voice commands no lock up no busy signal that's great"
        assertNull(reason(s))
        assertNull(Sensitivity.check(s, listOf("passwords", "gate code")))
    }

    @Test fun sensitivePhrasesAreFound() {
        for (m in listOf("The wifi password is hunter2", "my passcode is blue", "the PIN for the safe", "pin number is on the card",
            "the combination is left right left", "the code is on the door", "the code for the gate", "security code on the back",
            "my social security card", "SSN is in the folder", "account number is in the drawer", "routing number",
            "card number", "license number", "licence number")) {
            assertTrue("should be private: $m", reason(m) != null)
        }
        assertEquals(SensitiveReason.AUTO_SECRET, reason("the wifi password is hunter2"))
        assertEquals(SensitiveReason.PERSONAL_CODE, reason("my account number is in the drawer"))
    }

    @Test fun digitRunsMakeANotePrivate() {
        assertEquals(SensitiveReason.PERSONAL_CODE, reason("my code is 4 4 7 1 9 2"))
        assertEquals(SensitiveReason.PERSONAL_CODE, reason("it is 123-456-789"))
        assertEquals(SensitiveReason.PERSONAL_CODE, reason("the locker is 482913"))
    }

    @Test fun ordinarySentencesAreNotPrivate() {
        val fine = listOf(
            "I'm putting my reading glasses in the nightstand drawer",
            "I loaned my guitar to Frank on January fifth",
            "I need to remember to buy ground beef at Costco",
            "I just tested the pool water, free chlorine 5, pH 7.2, alkalinity 7",
            "Pool test: free chlorine 5, pH 7.2, alkalinity 70",
            "the Tide is on sale for 24.99 at Costco",
            "The Tide is on sale for \$24.99",
            "\$4.97 per bottle",
            "The Valpolicella was 1,250 dollars",
            "It cost \$1,250,000",
            "I am updating the app for stash right now",
            "I need to pin the map to the wall",
            "The furnace filter is 20 by 25 by 1",
            "Finish adding in the features for the app development",
        )
        for (s in fine) assertNull("should not be private: $s", reason(s))
    }

    @Test fun ownersExtraWordsMakeANotePrivate() {
        assertNull(reason("Frank's surprise party is Friday"))
        assertEquals(SensitiveReason.AUTO_SECRET, reason("Frank's surprise party is Friday", listOf("surprise")))
        assertNull(reason("Surprises are fun", listOf("surprise"))) // whole words only
    }

    @Test fun stripMarkerChangesTextButNotRaw() {
        assertEquals("my dentist is Dr Lee", Sensitivity.stripMarker("Private note: my dentist is Dr Lee"))
        assertEquals("call mom", Sensitivity.stripMarker("Ok, this is a private note, call mom"))
        assertEquals("buy milk", Sensitivity.stripMarker("buy milk"))
        // The marker is stripped from `text` only; raw_text is never passed through it.
        val raw = "Private note: call mom"
        val c = capture(raw, Sensitivity.stripMarker(raw), sensitive = true)
        assertEquals(raw, c.rawText)
        assertEquals("call mom", c.text)
    }

    @Test fun suggestHealthAndMoney() {
        assertEquals(SuggestCategory.HEALTH, Sensitivity.suggest("my blood pressure was 120 over 80"))
        assertEquals(SuggestCategory.HEALTH, Sensitivity.suggest("blood sugar 105 this morning"))
        assertEquals(SuggestCategory.HEALTH, Sensitivity.suggest("I weigh 180"))
        assertEquals(SuggestCategory.HEALTH, Sensitivity.suggest("take 20 mg of lisinopril"))
        assertEquals(SuggestCategory.HEALTH, Sensitivity.suggest("new prescription from Dr Lee"))
        assertEquals(SuggestCategory.MONEY, Sensitivity.suggest("my salary went up"))
        assertEquals(SuggestCategory.MONEY, Sensitivity.suggest("I owe Frank 50 dollars"))
        assertEquals(SuggestCategory.MONEY, Sensitivity.suggest("the balance is low"))
    }

    @Test fun suggestIgnoresPoolAndPriceSentences() {
        assertNull(Sensitivity.suggest("Pool test: free chlorine 5, pH 7.2, alkalinity 70"))
        assertNull(Sensitivity.suggest("I just tested the pool water, free chlorine 5, pH 7.2, alkalinity 7"))
        assertNull(Sensitivity.suggest("the Tide is on sale for 24.99 at Costco"))
        assertNull(Sensitivity.suggest("\$4.97 per bottle"))
        assertNull(Sensitivity.suggest("I need to remember to buy ground beef at Costco"))
    }

    private fun capture(raw: String, text: String, sensitive: Boolean) = CaptureEntity(
        id = "c", createdAt = 1, updatedAt = 1, rawText = raw, text = text, sensitive = sensitive,
    )

    @Test fun sendableTextCannotBeBuiltForASensitiveCapture() {
        assertNull(Sensitivity.sendable(capture("a", "a", sensitive = true)))
        assertEquals("hello", Sensitivity.sendable(capture("hello", "hello", sensitive = false))!!.value)
    }

    @Test fun onlySensitivityMakesSendableText() {
        // A compile-time guarantee (private constructor) plus this guard on the one internal door.
        var root = File("src/main/java")
        if (!root.exists()) root = File("app/src/main/java")
        val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("SendableText.mint(") }
            .map { it.name }.toList()
        assertEquals(listOf("Sensitivity.kt"), offenders)
        // A hosted extractor takes SendableText, never a String.
        val iface = File(root, "app/stash/model/Extractor.kt").readText()
        assertTrue(iface.contains("suspend fun extract(text: SendableText"))
        val witness: (String) -> SendableText? = { Sensitivity.sendable(capture(it, it, false)) }
        assertTrue(witness("x") != null)
    }
}

class NumberWordsTest {
    @Test fun spokenAndWrittenNumbersMatch() {
        assertTrue(NumberWords.matches("7.2", "seven point two"))
        assertTrue(NumberWords.matches("7.2", "pH 7.2 and then"))
        assertTrue(NumberWords.matches("5", "free chlorine five"))
        assertTrue(NumberWords.matches("70", "alkalinity seventy"))
        assertTrue(NumberWords.matches("24.99", "twenty four ninety nine"))
        assertTrue(NumberWords.matches("24.99", "on sale for \$24.99"))
        assertTrue(NumberWords.matches("\$24.99", "24.99 at Costco"))
        assertTrue(NumberWords.matches("4.97", "four ninety seven a bottle"))
        assertTrue(NumberWords.matches("5.05", "five oh five"))
        assertTrue(NumberWords.matches("1250", "it was 1,250 dollars"))
        assertTrue(NumberWords.matches("125", "one hundred twenty five"))
        assertTrue(NumberWords.matches("2000", "two thousand"))
        assertTrue(NumberWords.matches("7.2", "seven-point-two"))
        assertTrue(NumberWords.matches("21", "twenty-one"))
        assertTrue(NumberWords.matches("7.2", "7 point 2"))
    }

    @Test fun nearMissesDoNotMatch() {
        assertFalse(NumberWords.matches("7.2", "pH 72"))
        assertFalse(NumberWords.matches("7.2", "pH 7 2"))
        assertFalse(NumberWords.matches("72", "pH 7.2"))
        assertFalse(NumberWords.matches("24.99", "twenty four"))
        assertFalse(NumberWords.matches("5", "fifty"))
        assertFalse(NumberWords.matches("5", "no numbers here"))
        assertFalse(NumberWords.matches("abc", "abc"))
        assertFalse(NumberWords.matches("7.2", "seven"))
    }

    @Test fun scanFindsSpansWithPositions() {
        val text = "free chlorine 5, pH 7.2 and alkalinity seventy ppm"
        val spans = NumberWords.scan(text)
        assertEquals(listOf("5", "7.2", "seventy"), spans.map { it.text })
        assertEquals(text.indexOf("7.2"), spans[1].start)
        assertEquals(listOf(5.0, 7.2, 70.0), spans.map { it.primary })
    }

    @Test fun parseWholeStringOnly() {
        assertEquals(7.2, NumberWords.parse("seven point two")!!, 0.0)
        assertEquals(24.99, NumberWords.parse("\$24.99")!!, 0.0)
        assertNull(NumberWords.parse("about seven"))
        assertNull(NumberWords.parse("hello"))
    }

    @Test fun pointAloneAndOhAloneAreNotNumbers() {
        assertTrue(NumberWords.scan("the point is clear, oh well").isEmpty())
    }
}

class ExtractionChecksTest {
    private val text = "I need to remember to buy milk at Costco and I just tested the pool, pH 7.2"
    private fun check(vararg r: ExtractedRecord, t: String = text, labels: Set<String> = emptySet()) =
        ExtractionChecks.check(r.toList(), t, labels)

    private fun task(quote: String = "buy milk at Costco", title: String = "Buy milk") =
        ExtractedRecord(RecordKind.TASK, title, quote, item = "milk", atPlace = "Costco")

    @Test fun aGoodRecordPassesUntouched() {
        val r = task()
        val c = check(r, t = "I need to remember to buy milk at Costco").single()
        assertEquals(r, c.record)
        assertNull(c.attention)
    }

    @Test fun quoteMatchIgnoresCasePunctuationAndSpaces() {
        assertTrue(ExtractionChecks.quoteInText("BUY   milk, at costco!", "I need to buy milk at Costco."))
        assertFalse(ExtractionChecks.quoteInText("buy bread", "I need to buy milk"))
        assertFalse(ExtractionChecks.quoteInText("", "anything"))
        assertFalse(ExtractionChecks.quoteInText("buy mil", "buy milk")) // whole words only
    }

    @Test fun badQuoteBecomesAReferenceWithTheWholeText() {
        val c = check(task(quote = "buy bread at Costco"), t = "I need to buy milk").single()
        assertEquals(RecordKind.REFERENCE, c.record.kind)
        assertEquals(Attention.BAD_QUOTE, c.attention)
        assertEquals("Buy milk", c.record.title)
        assertEquals("I need to buy milk", c.record.quote)
    }

    @Test fun placedNeedsItemAndWhere() {
        val bad = ExtractedRecord(RecordKind.PLACED, "Keys", "keys in the bowl", item = "keys")
        val c = check(bad, t = "my keys in the bowl").single()
        assertEquals(RecordKind.REFERENCE, c.record.kind)
        assertEquals(Attention.MISSING_FIELD, c.attention)
        assertEquals("Keys", c.record.title)
        assertEquals("keys in the bowl", c.record.quote)
        val ok = ExtractedRecord(RecordKind.PLACED, "Keys", "keys in the bowl", item = "keys", where = "in the bowl")
        assertNull(check(ok, t = "my keys in the bowl").single().attention)
    }

    @Test fun loanNeedsItemAndPersonOrReturned() {
        val t = "Frank gave the guitar back"
        val noPerson = ExtractedRecord(RecordKind.LOAN, "Guitar", t, item = "guitar")
        assertEquals(Attention.MISSING_FIELD, check(noPerson, t = t).single().attention)
        val returned = noPerson.copy(direction = Direction.RETURNED)
        assertNull(check(returned, t = t).single().attention)
        val lent = noPerson.copy(person = "Frank", direction = Direction.LENT)
        assertNull(check(lent, t = t).single().attention)
        val noItem = ExtractedRecord(RecordKind.LOAN, "Loan", t, person = "Frank")
        assertEquals(Attention.MISSING_FIELD, check(noItem, t = t).single().attention)
    }

    @Test fun taskNeedsATitle() {
        val c = check(task(title = "  "), t = "buy milk at Costco").single()
        assertEquals(RecordKind.REFERENCE, c.record.kind)
        assertEquals(Attention.MISSING_FIELD, c.attention)
        assertEquals("buy milk at Costco", c.record.title) // filled from the quote so it is never blank
    }

    @Test fun readingNeedsReadingsWhoseNumbersAreInTheQuote() {
        val none = ExtractedRecord(RecordKind.READING, "pH", "pH 7.2")
        assertEquals(Attention.MISSING_FIELD, check(none, t = "pH 7.2").single().attention)
        val good = none.copy(readings = listOf(ExtractedReading("pH", "7.2", 7.2)))
        assertNull(check(good, t = "pH 7.2").single().attention)
        val words = none.copy(quote = "pH seven point two", readings = listOf(ExtractedReading("pH", "seven point two", 7.2)))
        assertNull(check(words, t = "pH seven point two").single().attention)
        val wrong = none.copy(readings = listOf(ExtractedReading("pH", "7.2", 7.2)), quote = "pH 72")
        val c = check(wrong, t = "pH 72").single()
        assertEquals(Attention.NUMBER_NOT_IN_TEXT, c.attention)
        assertEquals(RecordKind.REFERENCE, c.record.kind)
        val noNumber = none.copy(readings = listOf(ExtractedReading("water", "cloudy", null)), quote = "pH 7.2")
        assertNull(check(noNumber, t = "pH 7.2").single().attention) // a non-numeric value is fine
    }

    @Test fun priceNumbersMatchMoneyFormsAndWords() {
        val r = ExtractedRecord(RecordKind.READING, "Tide", "Tide on sale for twenty four ninety nine",
            readings = listOf(ExtractedReading("price", "twenty four ninety nine", 24.99)))
        assertNull(check(r, t = "Tide on sale for twenty four ninety nine").single().attention)
        val dollars = r.copy(quote = "Tide on sale for \$24.99")
        assertNull(check(dollars, t = "Tide on sale for \$24.99").single().attention)
    }

    @Test fun closesMustBeALabelFromTheContext() {
        val e = ExtractedRecord(RecordKind.EVENT, "Cleaned the car", "I cleaned the car", closes = "T3")
        assertEquals(Attention.UNKNOWN_TASK_LABEL, check(e, t = "I cleaned the car").single().attention)
        assertEquals(Attention.UNKNOWN_TASK_LABEL, check(e, t = "I cleaned the car", labels = setOf("T1")).single().attention)
        assertNull(check(e, t = "I cleaned the car", labels = setOf("T3")).single().attention)
    }

    @Test fun moreThanEightRecordsBecomeOneReference() {
        val many = (1..9).map { ExtractedRecord(RecordKind.TASK, "t$it", "milk") }
        val c = ExtractionChecks.check(many, "milk and more milk").single()
        assertEquals(RecordKind.REFERENCE, c.record.kind)
        assertEquals(Attention.TOO_MANY, c.attention)
        assertEquals("milk and more milk", c.record.quote)
        assertEquals(8, ExtractionChecks.check((1..8).map { ExtractedRecord(RecordKind.IDEA, "t$it", "milk") }, "milk").size)
    }

    @Test fun zeroRecordsBecomeOneOther() {
        val c = ExtractionChecks.check(emptyList(), "  something odd ").single()
        assertEquals(RecordKind.OTHER, c.record.kind)
        assertEquals("something odd", c.record.quote)
        assertNull(c.attention)
    }

    @Test fun coverageUnderHalfFlagsTheFirstRecord() {
        val t = "I need to remember to buy milk at Costco and the garage door opener battery is dying and the neighbor wants the ladder back"
        val r = ExtractedRecord(RecordKind.TASK, "Buy milk", "buy milk at Costco")
        val c = ExtractionChecks.check(listOf(r), t)
        assertEquals(Attention.PARTLY_FILED, c.first().attention)
        val whole = ExtractedRecord(RecordKind.TASK, "Buy milk", "buy milk at Costco")
        assertNull(ExtractionChecks.check(listOf(whole), "buy milk at Costco").single().attention)
    }

    @Test fun coverageFlagOnlyOnTheFirstAndKeepsAnEarlierReason() {
        val t = "buy milk and then something about many other unrelated words here today"
        val a = ExtractedRecord(RecordKind.TASK, "Buy milk", "buy milk")
        val b = ExtractedRecord(RecordKind.TASK, "Buy bread", "buy bread") // bad quote
        val c = ExtractionChecks.check(listOf(b, a), t)
        assertEquals(Attention.BAD_QUOTE, c[0].attention)
        assertNull(c[1].attention)
    }
}
