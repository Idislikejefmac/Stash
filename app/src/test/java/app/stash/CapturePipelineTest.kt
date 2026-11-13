package app.stash

import app.stash.capture.CaptureEntry
import app.stash.capture.DirectSink
import app.stash.capture.Inbound
import app.stash.capture.InboundSink
import app.stash.data.CaptureEntity
import app.stash.data.CaptureSource
import app.stash.data.DbEventSink
import app.stash.data.ExtractState
import app.stash.data.ExtractionStatus
import app.stash.data.LocationFix
import app.stash.data.RetryPolicy
import app.stash.data.Store
import app.stash.data.StashDatabase
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.RecordKind
import app.stash.model.ExtractContext
import app.stash.model.ExtractResult
import app.stash.model.Extractor
import app.stash.model.rules.RulesExtractor
import app.stash.model.hosted.HttpPoster
import app.stash.domain.ExtractionJson
import app.stash.domain.Outbound
import app.stash.domain.SendableText
import app.stash.pipeline.AfterProjection
import app.stash.pipeline.ExtractionPipeline
import app.stash.pipeline.MemoryForceList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
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
import java.time.ZoneOffset

/** A hosted extractor that answers from a script and remembers what it was asked. It never touches a network. */
class FakeExtractor(private val script: MutableList<ExtractResult> = mutableListOf()) : Extractor {
    override val name = "fake-model"
    var calls = 0
    val asked = mutableListOf<String>()

    fun then(r: ExtractResult) = apply { script += r }

    override suspend fun extract(text: SendableText, context: ExtractContext): ExtractResult {
        calls++
        asked += text.value
        if (script.isNotEmpty()) return script.removeAt(0)
        // Default: the rules' reading, as if a model agreed with them.
        val recs = RulesExtractor().parse(text.value, context)
        return ExtractResult.Ok(recs, ExtractionJson.serialise(recs), tokensIn = 12, tokensOut = 7)
    }
}

/** The pipeline against a real (in-memory) Room database, with a fake hosted model and a fake clock. */
@RunWith(RobolectricTestRunner::class)
class CapturePipelineTest {
    private lateinit var db: StashDatabase
    private lateinit var repo: app.stash.data.Repository
    private val clock = FakeClock(1_794_398_400_000L)
    private var store: () -> Store = { Store.Ready(db) }
    private var online = true
    private var hostedOn = true
    private var hasKey = true
    private var hosted: Extractor? = null
    private val hooks = mutableListOf<AfterProjection>()
    private val force = MemoryForceList()
    private val events = mutableListOf<Triple<DbgComponent, DbgCode, Long?>>()
    private val saved = mutableListOf<String>()

    private fun pipeline() = ExtractionPipeline(
        store = { store() }, rules = RulesExtractor(), hostedExtractor = { hosted }, hostedOn = { hostedOn },
        hasKey = { hasKey }, networkAvailable = { online }, hooks = { hooks.toList() }, force = force, clock = clock,
        zone = { ZoneOffset.UTC },
    )

    @Before fun setUp() {
        db = TestSupport.memoryDb()
        repo = TestSupport.repo(db, clock)
        Dbg.sink = app.stash.domain.EventSink { c, code, n1, _, _, _ -> events += Triple(c, code, n1) }
    }

    @After fun tearDown() {
        Dbg.sink = null
        db.close()
    }

    private val sink get() = DirectSink(repo.captures, { listOf("Stargate") }, afterSave = { saved += "x" })

    private suspend fun say(text: String, source: CaptureSource = CaptureSource.VOICE, loc: LocationFix? = null): CaptureEntity {
        clock.advance(60_000)
        val r = sink.accept(Inbound.Capture.now(text, text, source, true, loc, "test", clock, "UTC"))
        return (r as InboundSink.Result.Saved).capture
    }

    private suspend fun state(c: CaptureEntity) = db.captures().get(c.id)!!
    private suspend fun kinds(c: CaptureEntity) = db.records().forCapture(c.id).map { it.kind }
    private suspend fun extractors(c: CaptureEntity) = db.extractions().forCapture(c.id).filter { it.status == ExtractionStatus.CURRENT }.map { it.extractor }

    // ------------------------------------------------------------------ the sink

    @Test fun savingPutsTheWordsInFirstAndAsksForAReading() = runBlocking {
        val c = say("I changed the furnace filter today")
        assertEquals(ExtractState.PENDING, state(c).extractState)
        assertTrue(db.records().forCapture(c.id).isEmpty())
        assertEquals(1, saved.size)
        // Nothing was read yet: no extraction exists.
        assertTrue(db.extractions().forCapture(c.id).isEmpty())
    }

    @Test fun theSinkKeepsTheIdAndTimeFixedAtTheDoor() = runBlocking {
        val item = Inbound.Capture("fixed-id-123", 42L, "UTC", "raw", "raw", CaptureSource.TYPED, false, LocationFix(1.0, 2.0, 5.0), "v")
        val c = (sink.accept(item) as InboundSink.Result.Saved).capture
        assertEquals("fixed-id-123", c.id)
        assertEquals(42L, c.createdAt)
        assertEquals(CaptureSource.TYPED, c.source)
        assertEquals(1.0, c.lat!!, 0.0)
        assertEquals("UTC", c.zone)
    }

    @Test fun aPrivateNoteIsMarkedAndTheMarkerLeavesTheTextButNotTheRawWords() = runBlocking {
        val c = say("private note the gate code is 4 4 7 1")
        val s = state(c)
        assertTrue(s.sensitive)
        assertEquals("SAID", s.sensitiveReason)
        assertEquals("the gate code is 4 4 7 1", s.text)
        assertEquals("private note the gate code is 4 4 7 1", s.rawText)
    }

    @Test fun anAlwaysPrivateWordMakesANotePrivate() = runBlocking {
        val c = say("watching Stargate tonight")
        assertTrue(state(c).sensitive)
        assertEquals("AUTO_SECRET", state(c).sensitiveReason)
        assertFalse(state(say("watching a film tonight")).sensitive)
    }

    @Test fun theEntryCorrectsTheWordsBeforeTheSinkSeesThem() = runBlocking {
        val entry = CaptureEntry({ it.replace("sid", "Syd") }, sink, { null }, "test", clock)
        val c = (entry.save("sid has the key", CaptureSource.TYPED, false) as InboundSink.Result.Saved).capture
        assertEquals("Syd has the key", c.text)
        assertEquals("sid has the key", c.rawText)
    }

    // ------------------------------------------------------------------ the worker's logic

    @Test fun rulesAloneFileANoteWhenNoModelIsRegistered() = runBlocking {
        val c = say("I changed the furnace filter today")
        val pass = pipeline().runPass()
        assertTrue(pass.storeAvailable)
        assertEquals(1, pass.processed)
        assertEquals(listOf(RecordKind.EVENT), kinds(c))
        assertEquals(listOf("rules-2"), extractors(c))
        assertEquals(ExtractState.DONE, state(c).extractState)
        assertNull(pass.nextDelayMs)
        assertFalse(pass.needsNetwork)
    }

    @Test fun aHostedReadIsUsedWhenItWorks() = runBlocking {
        hosted = FakeExtractor()
        val c = say("I changed the furnace filter today")
        pipeline().runPass()
        assertEquals(listOf("fake-model"), extractors(c))
        assertEquals(ExtractState.DONE, state(c).extractState)
        assertEquals(1, (hosted as FakeExtractor).calls)
        val x = db.extractions().forCapture(c.id).single()
        assertEquals(12, x.tokensIn)
    }

    @Test fun aRetryableHostedFailureKeepsTheRulesRecordsAndBacksOffThenSucceeds() = runBlocking {
        val fake = FakeExtractor().then(ExtractResult.Failed("timeout", retryable = true))
        hosted = fake
        val c = say("I changed the furnace filter today")
        val first = pipeline().runPass()
        assertEquals(listOf("rules-2"), extractors(c))
        assertEquals(ExtractState.RETRY, state(c).extractState)
        assertEquals(1, state(c).extractAttempts)
        assertEquals(RetryPolicy.backoffMs(1), first.nextDelayMs)
        assertEquals(1, db.extractions().forCapture(c.id).count { it.status == ExtractionStatus.FAILED })
        assertEquals("timeout", db.extractions().forCapture(c.id).single { it.status == ExtractionStatus.FAILED }.error)
        // Too early: not tried again.
        clock.advance(30_000)
        assertEquals(0, pipeline().runPass().processed)
        assertEquals(1, fake.calls)
        // After the wait it is read again, and this time the model answers.
        clock.advance(60_000)
        pipeline().runPass()
        assertEquals(2, fake.calls)
        assertEquals(listOf("fake-model"), extractors(c))
        assertEquals(ExtractState.DONE, state(c).extractState)
    }

    @Test fun backOffRunsOneMinuteTenMinutesAnHourSixHoursADayThenGivesUp() = runBlocking {
        assertEquals(listOf(60_000L, 600_000L, 3_600_000L, 21_600_000L, 86_400_000L), (1..5).map { RetryPolicy.backoffMs(it) })
        val fake = FakeExtractor()
        repeat(6) { fake.then(ExtractResult.Failed("timeout", retryable = true)) }
        hosted = fake
        val c = say("I changed the furnace filter today")
        for (i in 1..5) {
            val pass = pipeline().runPass()
            assertEquals(ExtractState.RETRY, state(c).extractState)
            assertEquals(i, state(c).extractAttempts)
            assertEquals(RetryPolicy.backoffMs(i), pass.nextDelayMs)
            clock.advance(RetryPolicy.backoffMs(i) + 1)
        }
        val last = pipeline().runPass()
        assertEquals(ExtractState.GAVE_UP, state(c).extractState)
        assertEquals(6, fake.calls)
        assertNull(last.nextDelayMs)
        // The words and the simple filing are still there.
        assertEquals(listOf(RecordKind.EVENT), kinds(c))
        // A gave-up capture is not read again by itself.
        clock.advance(86_400_000)
        assertEquals(0, pipeline().runPass().processed)
        assertEquals(6, fake.calls)
    }

    @Test fun aHostedFailureThatCannotBeRetriedEndsAsDoneWithRulesRecords() = runBlocking {
        hosted = FakeExtractor().then(ExtractResult.Failed("bad key", retryable = false))
        val c = say("I changed the furnace filter today")
        val pass = pipeline().runPass()
        assertEquals(ExtractState.DONE, state(c).extractState)
        assertEquals(listOf("rules-2"), extractors(c))
        assertNull(pass.nextDelayMs)
    }

    @Test fun aHostedExtractorThatThrowsCountsAsARetryableFailure() = runBlocking {
        val boom = object : Extractor {
            override val name = "boom"
            override suspend fun extract(text: SendableText, context: ExtractContext): ExtractResult = throw IllegalStateException("with words in it")
        }
        hosted = boom
        val c = say("I changed the furnace filter today")
        pipeline().runPass()
        assertEquals(ExtractState.RETRY, state(c).extractState)
        assertEquals("IllegalStateException", db.extractions().forCapture(c.id).single { it.status == ExtractionStatus.FAILED }.error)
        assertEquals(listOf(RecordKind.EVENT), kinds(c))
    }

    @Test fun aKeyWithNoNetworkFilesByRulesAndWaitsForANetworkWithoutCountingAnAttempt() = runBlocking {
        val fake = FakeExtractor()
        hosted = fake
        online = false
        val c = say("I changed the furnace filter today")
        val pass = pipeline().runPass()
        assertEquals(0, fake.calls)
        assertEquals(ExtractState.RETRY, state(c).extractState)
        assertEquals(0, state(c).extractAttempts)
        assertTrue(pass.needsNetwork)
        assertEquals(listOf(RecordKind.EVENT), kinds(c))
        // Still offline: nothing is redone.
        assertEquals(0, pipeline().runPass().processed)
        // Online again: the model reads it.
        online = true
        pipeline().runPass()
        assertEquals(1, fake.calls)
        assertEquals(ExtractState.DONE, state(c).extractState)
    }

    @Test fun noKeyOrCapabilityOffMeansRulesAndDone() = runBlocking {
        val fake = FakeExtractor()
        hosted = fake
        hasKey = false
        val a = say("I changed the furnace filter today")
        pipeline().runPass()
        hasKey = true
        hostedOn = false
        val b = say("I changed the oil today")
        pipeline().runPass()
        assertEquals(0, fake.calls)
        assertEquals(ExtractState.DONE, state(a).extractState)
        assertEquals(ExtractState.DONE, state(b).extractState)
    }

    @Test fun aPrivateCaptureNeverReachesTheHostedExtractor() = runBlocking {
        var transportCalled = false
        val transport = HttpPoster { _, _, _, _ -> transportCalled = true; throw java.io.IOException("offline") }
        val hostedOverTransport = object : Extractor {
            override val name = "hosted-over-transport"
            override suspend fun extract(text: SendableText, context: ExtractContext): ExtractResult {
                transport.post(Outbound.EXTRACT_NOTE, "https://example.invalid", emptyMap(), text.value)
                return ExtractResult.Failed("unreachable", retryable = true)
            }
        }
        hosted = hostedOverTransport
        val said = say("private note I changed the furnace filter today")
        val auto = say("my wifi password is hunter2 and I changed the filter")
        val digits = say("I changed the filter and the account is 123456789")
        pipeline().runPass()
        assertFalse(transportCalled)
        for (c in listOf(said, auto, digits)) {
            assertTrue(state(c).sensitive)
            assertEquals(ExtractState.DONE, state(c).extractState)
            assertEquals(listOf("rules-2"), extractors(c))
        }
        // The control: the same words, not private, do go to it.
        val open = say("I changed the furnace filter today")
        pipeline().runPass()
        assertTrue(transportCalled)
        assertEquals(ExtractState.RETRY, state(open).extractState)
    }

    @Test fun aPinnedRecordSurvivesAReadAndAnUnchangedCaptureIsSkipped() = runBlocking {
        val c = say("I need to remember to buy milk at Costco")
        pipeline().runPass()
        val mine = db.records().forCapture(c.id).single()
        repo.records.pin(mine.id)
        // Automatic: nothing new to learn, so it is skipped and marked done.
        db.captures().update(state(c).copy(extractState = ExtractState.PENDING))
        val pass = pipeline().runPass()
        assertEquals(0, pass.processed)
        assertEquals(ExtractState.DONE, state(c).extractState)
        assertEquals(1, db.extractions().forCapture(c.id).size)
        assertTrue(events.any { it.first == DbgComponent.EXTRACT && it.second == DbgCode.SKIPPED })
        assertEquals(mine.id, db.records().forCapture(c.id).single().id)
    }

    @Test fun readItAgainReadsEvenWithAPinnedRecordAndDropsADuplicateOfIt() = runBlocking {
        val c = say("I need to remember to buy milk at Costco")
        pipeline().runPass()
        val mine = db.records().forCapture(c.id).single()
        repo.records.pin(mine.id)
        repo.captures.requestReread(c.id)
        force.add(c.id)
        val pass = pipeline().runPass()
        assertEquals(1, pass.processed)
        val now = db.records().forCapture(c.id)
        assertEquals(listOf(mine.id), now.map { it.id }) // the new twin was discarded, the pinned one stays
        assertTrue(now.single().pinned)
        assertEquals(2, db.extractions().forCapture(c.id).size)
        assertEquals(ExtractState.DONE, state(c).extractState)
    }

    @Test fun anEditedCaptureWithPinnedRecordsIsReadAndTheNewWordsAddRecords() = runBlocking {
        val c = say("I need to remember to buy milk at Costco")
        pipeline().runPass()
        repo.records.pin(db.records().forCapture(c.id).single().id)
        repo.captures.updateText(c.id, "I changed the furnace filter today")
        val pass = pipeline().runPass()
        assertEquals(1, pass.processed)
        val kinds = kinds(c)
        assertTrue(RecordKind.TASK in kinds && RecordKind.EVENT in kinds)
    }

    @Test fun aThrowingHookLeavesTheRecordsWrittenAndLaterHooksRun() = runBlocking {
        var second: Pair<String, Int>? = null
        hooks += AfterProjection { _, _ -> throw IllegalStateException("hook broke") }
        hooks += AfterProjection { id, ids -> second = id to ids.size }
        val c = say("I changed the furnace filter today")
        pipeline().runPass()
        assertEquals(listOf(RecordKind.EVENT), kinds(c))
        assertEquals(ExtractState.DONE, state(c).extractState)
        assertEquals(c.id to 1, second)
        assertTrue(events.any { it.first == DbgComponent.HOOK && it.second == DbgCode.THREW })
    }

    @Test fun aStoreThatIsNotAvailableEndsThePassForTheWorkerToRetry() = runBlocking {
        val c = say("I changed the furnace filter today")
        store = { Store.NotAvailable }
        val pass = pipeline().runPass()
        assertFalse(pass.storeAvailable)
        assertEquals(ExtractState.PENDING, state(c).extractState)
        assertTrue(events.any { it.second == DbgCode.STORE_UNAVAILABLE })
        store = { Store.Ready(db) }
        assertEquals(1, pipeline().runPass().processed)
    }

    @Test fun oldestIsReadFirstAndADeletedCaptureIsNotRead() = runBlocking {
        val order = mutableListOf<String>()
        hooks += AfterProjection { id, _ -> order += id }
        val a = say("I changed the furnace filter today")
        val b = say("I changed the oil today")
        val gone = say("I changed the tire today")
        repo.deleteCapture(gone.id)
        pipeline().runPass()
        assertEquals(listOf(a.id, b.id), order)
    }

    @Test fun eventsHoldCodesAndNumbersAndNoWords() = runBlocking {
        val c = say("private note the secret phrase is purple elephant")
        pipeline().runPass()
        val codes = events.map { it.first to it.second }
        assertTrue(DbgComponent.CAPTURE to DbgCode.SAVED in codes)
        assertTrue(DbgComponent.EXTRACT to DbgCode.STARTED in codes)
        assertTrue(DbgComponent.EXTRACT to DbgCode.OK in codes)
        assertTrue(DbgComponent.PROJECT to DbgCode.WRITTEN in codes)
        assertNotNull(c)
    }

    @Test fun theEventTableKeepsTheNewestAndStoresNoText() = runBlocking {
        val sink = DbEventSink({ Store.Ready(db) }, CoroutineScope(Dispatchers.Unconfined), clock)
        for (i in 1..5) {
            clock.advance(10)
            sink.write(DbgComponent.EXTRACT, DbgCode.OK, i.toLong(), null, "row-$i", 3L)
            sink.last?.join()
        }
        assertEquals(5, db.appEvents().count())
        db.appEvents().trimTo(3)
        val left = db.appEvents().newest(10)
        assertEquals(listOf(5L, 4L, 3L), left.map { it.n1 })
        assertEquals("EXTRACT", left.first().component)
        assertEquals("OK", left.first().code)
    }

    // ------------------------------------------------------------------ real sentences (docs/cards/13, owner's own words)

    private suspend fun filed(text: String): List<Pair<RecordKind, String>> {
        val c = say(text)
        pipeline().runPass()
        return db.records().forCapture(c.id).map { it.kind to it.title }
    }

    @Test fun theSushiAndOatMilkSentenceIsNowAnEventNotPlainOther() = runBlocking {
        val r = filed("I bought sushi roasted almonds and raw walnuts at and some oat milk at the Sprouts").single()
        assertEquals(RecordKind.EVENT, r.first)
    }

    @Test fun theChemicalsAtLeslieSentenceIsNowAnEventWithItsPlace() = runBlocking {
        val c = say("I just bought four pack of chlorine and a four pack of muriatic acid at Leslie's Pool Supply")
        pipeline().runPass()
        val r = db.records().forCapture(c.id).single()
        assertEquals(RecordKind.EVENT, r.kind)
        assertEquals("Leslie's Pool Supply", r.placeText)
    }

    @Test fun theCarWashedSentenceStaysOtherButIsSavedAndSaysSo() = runBlocking {
        val c = say("I just had the car washed")
        pipeline().runPass()
        assertEquals(listOf(RecordKind.OTHER), kinds(c))
        assertEquals("rules-2", extractors(c).single())
        assertEquals(ExtractState.DONE, state(c).extractState) // eligible for a model later (card 04)
    }

    @Test fun theThinkingAboutGoingSentenceStaysOtherWithTheSimpleRules() = runBlocking {
        val r = filed("I'm thinking about going to get the car washed and going to Leslie's pools and then getting some sushi on the way home").single()
        assertEquals(RecordKind.OTHER, r.first)
    }

    @Test fun theUpdatingTheAppSentenceIsAnEvent() = runBlocking {
        assertEquals(RecordKind.EVENT, filed("I am updating the app for stash right now").single().first)
    }
}
