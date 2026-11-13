package app.stash

import app.stash.ask.DateRange
import app.stash.ask.Lookups
import app.stash.data.Direction
import app.stash.data.Repository
import app.stash.data.StashDatabase
import app.stash.data.SubjectType
import app.stash.data.Volatility
import app.stash.domain.RecordKind
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
import java.time.LocalDate
import java.time.ZoneOffset

/** Card 08 section A: each read-only lookup against a seeded in-memory database, and Private never returned without true. */
@RunWith(RobolectricTestRunner::class)
class AskLookupsTest {
    private lateinit var db: StashDatabase
    private lateinit var repo: Repository
    private lateinit var seed: AskTestSeed
    private lateinit var lookups: Lookups
    private val clock = FakeClock(1_794_398_400_000L) // 2026-11-11 12:00 UTC
    private val today = LocalDate.of(2026, 11, 11)

    @Before fun setUp() {
        db = TestSupport.memoryDb()
        repo = TestSupport.repo(db, clock)
        seed = AskTestSeed(db, repo, clock)
        lookups = Lookups(repo.ask, clock::now, { ZoneOffset.UTC })
    }

    @After fun tearDown() = db.close()

    // ---- searchNotes

    @Test fun searchRanksSubjectAboveTitleAboveQuoteThenNewer() = runBlocking {
        val drill = seed.subject("drill")
        val titled = seed.note("Ordered a new drill bit set", RecordKind.EVENT, daysAgo = 1.0, title = "Drill bits ordered")
        val subj = seed.note("Lent it to Frank", RecordKind.LOAN, daysAgo = 9.0, title = "Lent to Frank", subject = drill)
        val quoted = seed.note("Picked up screws for the drill", RecordKind.EVENT, daysAgo = 0.5, title = "Picked up screws")
        seed.note("Pool test pH 7.2", RecordKind.READING)
        assertEquals(listOf(subj.recordId, titled.recordId, quoted.recordId), lookups.searchNotes("where is the drill", includeSensitive = false).map { it.id })
    }

    @Test fun moreWordsFoundRanksFirstAndCurrentAboveSuperseded() = runBlocking {
        val one = seed.note("My glasses are on the desk", RecordKind.PLACED, daysAgo = 1.0, title = "Glasses on the desk")
        val both = seed.note("Reading glasses in the nightstand drawer", RecordKind.PLACED, daysAgo = 5.0, title = "Glasses in the nightstand")
        assertEquals(listOf(both.recordId, one.recordId), lookups.searchNotes("glasses nightstand", includeSensitive = false).map { it.id })
        // an older current note above a newer one that it replaced
        val newer = seed.note("Passport in the desk", RecordKind.PLACED, daysAgo = 1.0)
        val current = seed.note("Passport in the safe", RecordKind.PLACED, daysAgo = 3.0, supersedes = newer.recordId)
        assertEquals(listOf(current.recordId, newer.recordId), lookups.searchNotes("passport", includeSensitive = false).map { it.id })
    }

    @Test fun searchByPlaceWithAPictureAndByPlaceAndDate() = runBlocking {
        val a = seed.note("Allegrini Valpolicella, nice label", RecordKind.OTHER, daysAgo = 7.0, placeText = "Total Wine", pictures = 1)
        val b = seed.note("A Barolo to try", RecordKind.OTHER, daysAgo = 20.0, placeText = "Total Wine", pictures = 2)
        val c = seed.note("Ask about the wine club", RecordKind.OTHER, daysAgo = 15.0, placeText = "Total Wine")
        seed.note("Kirkland olive oil", RecordKind.OTHER, daysAgo = 7.0, placeText = "Costco", pictures = 1)
        seed.note("A gift bottle", RecordKind.OTHER, daysAgo = 8.0, placeText = "Total Wine", pictures = 1, sensitive = true)
        val withPictures = lookups.searchNotes(null, place = "Total Wine", hasPicture = true, includeSensitive = false)
        assertEquals(listOf(a.recordId, b.recordId), withPictures.map { it.id })
        assertTrue(withPictures.all { it.hasPicture && it.place == "Total Wine" })
        val twoToThreeWeeks = DateRange(today.minusDays(21), today.minusDays(14))
        assertEquals(listOf(c.recordId, b.recordId), lookups.searchNotes(null, on = twoToThreeWeeks, place = "total wine", includeSensitive = false).map { it.id })
        assertEquals("2 weeks ago", lookups.searchNotes("barolo", includeSensitive = false).single().age)
        assertEquals(listOf(c.recordId), lookups.searchNotes(null, place = "Total Wine", hasPicture = false, includeSensitive = false).map { it.id })
        assertTrue(lookups.searchNotes("", includeSensitive = false).isEmpty())
        assertEquals(listOf(b.recordId), lookups.searchNotes("barolo", kind = RecordKind.OTHER, includeSensitive = false).map { it.id })
        assertTrue(lookups.searchNotes("barolo", kind = RecordKind.TASK, includeSensitive = false).isEmpty())
    }

    @Test fun searchFindsANoteWithNoRecordsYet() = runBlocking {
        val c = repo.captures.insertCapture("call the plumber about the leak", "call the plumber about the leak", createdAt = clock.now() - 3_600_000L)
        val hits = lookups.searchNotes("plumber", includeSensitive = false)
        assertEquals(listOf(c.id), hits.map { it.id })
        assertNull(hits.single().recordId)
        assertEquals("1 hour ago", hits.single().age)
    }

    // ---- whereIs

    @Test fun whereIsGivesTheCurrentPlaceTwoEarlierOnesAndStaleness() = runBlocking {
        val glasses = seed.subject("reading glasses", volatility = Volatility.DAILY, aliases = "[\"specs\"]")
        val p1 = seed.note("glasses on the desk", RecordKind.PLACED, daysAgo = 30.0, item = "reading glasses", itemSubject = glasses, where = "on the desk", direction = Direction.PLACED)
        val p2 = seed.note("glasses in the car", RecordKind.PLACED, daysAgo = 20.0, item = "reading glasses", itemSubject = glasses, where = "in the car", direction = Direction.PLACED, supersedes = p1.recordId)
        val p3 = seed.note("glasses in the kitchen", RecordKind.PLACED, daysAgo = 10.0, item = "reading glasses", itemSubject = glasses, where = "in the kitchen", direction = Direction.PLACED, supersedes = p2.recordId)
        val p4 = seed.note("glasses in the nightstand drawer", RecordKind.PLACED, daysAgo = 3.0, item = "reading glasses", itemSubject = glasses, where = "in the nightstand drawer", direction = Direction.PLACED, supersedes = p3.recordId)
        val r = lookups.whereIs("my glasses", includeSensitive = false)!!
        assertEquals(p4.recordId, r.current.recordId)
        assertEquals("in the nightstand drawer", r.current.where)
        assertEquals("3 days ago", r.current.age)
        assertEquals(listOf(p3.recordId, p2.recordId), r.previous.map { it.recordId })
        assertTrue("a daily thing said 3 days ago may have moved", r.stale)
        assertEquals(p4.recordId, lookups.whereIs("specs", includeSensitive = false)!!.current.recordId)
        assertNull(lookups.whereIs("kayak", includeSensitive = false))
    }

    @Test fun stalenessFollowsVolatility() = runBlocking {
        val normal = seed.subject("ladder")
        val stored = seed.subject("christmas lights", volatility = Volatility.STORED)
        seed.note("ladder in the shed", RecordKind.PLACED, daysAgo = 30.0, item = "ladder", itemSubject = normal, where = "in the shed", direction = Direction.PLACED)
        seed.note("lights in the attic", RecordKind.PLACED, daysAgo = 200.0, item = "christmas lights", itemSubject = stored, where = "in the attic", direction = Direction.PLACED)
        seed.note("tent in the garage", RecordKind.PLACED, daysAgo = 100.0, item = "tent", where = "in the garage", direction = Direction.PLACED)
        assertFalse(lookups.whereIs("ladder", includeSensitive = false)!!.stale)
        assertFalse(lookups.whereIs("christmas lights", includeSensitive = false)!!.stale)
        assertTrue("unknown volatility counts as normal: 90 days", lookups.whereIs("tent", includeSensitive = false)!!.stale)
        assertEquals(2L, Lookups.staleAfterDays(Volatility.DAILY)); assertEquals(90L, Lookups.staleAfterDays(null)); assertEquals(365L, Lookups.staleAfterDays(Volatility.STORED))
    }

    // ---- loans

    @Test fun loansAreTheOnesNotBackYet() = runBlocking {
        val drill = seed.subject("drill")
        val guitar = seed.note("I loaned my guitar to Frank on January 5th", RecordKind.LOAN, daysAgo = 21.0, item = "guitar", person = "Frank", direction = Direction.LENT, saidDate = "January 5th")
        val lent = seed.note("Sam borrowed the drill", RecordKind.LOAN, daysAgo = 14.0, item = "drill", itemSubject = drill, person = "Sam", direction = Direction.LENT)
        seed.note("Sam gave back the drill", RecordKind.LOAN, daysAgo = 2.0, item = "drill", itemSubject = drill, person = "Sam", direction = Direction.RETURNED, supersedes = lent.recordId)
        seed.note("Lent the ladder to Ann", RecordKind.LOAN, daysAgo = 5.0, item = "ladder", person = "Ann", direction = Direction.LENT, returnedAt = clock.now() - 1000)
        val bor = seed.note("I borrowed a saw from Frank", RecordKind.LOAN, daysAgo = 1.0, item = "saw", person = "Frank", direction = Direction.BORROWED)
        assertEquals(listOf(bor.recordId, guitar.recordId), lookups.loans(null, includeSensitive = false).map { it.recordId })
        assertEquals(listOf(bor.recordId, guitar.recordId), lookups.loans("frank", includeSensitive = false).map { it.recordId })
        assertEquals(listOf(guitar.recordId), lookups.loans(null, includeSensitive = false, item = "my guitar").map { it.recordId })
        assertTrue(lookups.loans("sam", includeSensitive = false).isEmpty())
        val g = lookups.loans("frank", includeSensitive = false).last()
        assertEquals("January 5th", g.saidDate); assertEquals("3 weeks ago", g.age); assertEquals(Direction.LENT, g.direction)
    }

    // ---- lastEvent

    @Test fun lastEventFindsTheNewestMatchingEvents() = runBlocking {
        val old = seed.note("I changed the furnace filter", RecordKind.EVENT, daysAgo = 120.0)
        val recent = seed.note("I changed the furnace filter today", RecordKind.EVENT, daysAgo = 35.0)
        seed.note("I changed the oil in the car", RecordKind.EVENT, daysAgo = 3.0)
        seed.note("Change the furnace filter soon", RecordKind.TASK, daysAgo = 1.0)
        val r = lookups.lastEvent("change the furnace filter", includeSensitive = false)
        assertEquals(listOf(recent.recordId, old.recordId), r.map { it.recordId })
        assertEquals(listOf("5 weeks ago", "4 months ago"), r.map { it.age })
        // a date he said beats the time he said it
        val said = seed.note("I washed the car", RecordKind.EVENT, daysAgo = 1.0, happenedOn = "2026-11-01")
        assertEquals("10 days ago", lookups.lastEvent("wash the car", includeSensitive = false).single { it.recordId == said.recordId }.age)
        assertTrue(lookups.lastEvent("", includeSensitive = false).isEmpty())
    }

    // ---- readings, tasks, about, capturesOn

    @Test fun readingsByNameAndTheLastSession() = runBlocking {
        seed.said("Pool test: free chlorine 4, pH 7.6, alkalinity 80", daysAgo = 9)
        seed.said("Pool test: free chlorine 5, pH 7.2, alkalinity 70", daysAgo = 2)
        val ph = lookups.readings("pool", "pH", 5, includeSensitive = false)
        assertEquals(listOf("7.2", "7.6"), ph.map { it.value })
        assertEquals(listOf("2 days ago", "9 days ago"), ph.map { it.age })
        assertEquals(listOf("7.2"), lookups.readings(null, "ph", 1, includeSensitive = false).map { it.value })
        assertEquals(listOf("free chlorine", "pH", "alkalinity"), lookups.readings("pool", null, includeSensitive = false).map { it.metric })
        assertTrue(lookups.readings("pool", "turbidity", includeSensitive = false).isEmpty())
        assertTrue(lookups.readings("spa", null, includeSensitive = false).isEmpty())
    }

    @Test fun openTasksAtAPlaceAndAboutASubject() = runBlocking {
        val milk = seed.note("Buy milk at Costco", RecordKind.TASK, daysAgo = 2.0, title = "Buy milk", atPlace = "Costco")
        val car = seed.note("Clean out the back of the car soon", RecordKind.TASK, daysAgo = 5.0, title = "Clean out the back of the car")
        assertEquals(setOf(milk.recordId, car.recordId), lookups.openTasks(null, null, includeSensitive = false).hits.map { it.recordId }.toSet())
        assertEquals(listOf(milk.recordId), lookups.openTasks(null, "costco", includeSensitive = false).hits.map { it.recordId })
        assertEquals(listOf(car.recordId), lookups.openTasks("car", null, includeSensitive = false).hits.map { it.recordId })
        assertEquals("2 days ago", lookups.openTasks(null, "costco", includeSensitive = false).hits.single().age)
    }

    @Test fun aboutCountsTheSubjectsNotesByKind() = runBlocking {
        val pool = seed.subject("pool", type = SubjectType.THING)
        seed.note("pool test pH 7.2", RecordKind.READING, daysAgo = 2.0, subject = pool)
        seed.note("pool test pH 7.4", RecordKind.READING, daysAgo = 9.0, subject = pool)
        seed.note("pool test pH 7.0", RecordKind.READING, daysAgo = 16.0, subject = pool)
        seed.note("clean the pool filter", RecordKind.TASK, daysAgo = 1.0, subject = pool)
        seed.note("the car needs washing", RecordKind.TASK, daysAgo = 1.0)
        val a = lookups.aboutSubject("the pool", includeSensitive = false)!!
        assertEquals(mapOf(RecordKind.READING to 3, RecordKind.TASK to 1), a.counts)
        assertEquals(2, a.newest.getValue(RecordKind.READING).size)
        assertEquals("pool", a.name)
        assertNull(lookups.aboutSubject("boat", includeSensitive = false))
    }

    @Test fun capturesOnTheGivenLocalDates() = runBlocking {
        val y = seed.note("Buy stamps", RecordKind.TASK, daysAgo = 1.0)
        val y2 = seed.note("Pool test pH 7.2", RecordKind.READING, daysAgo = 1.1)
        seed.note("Old note", RecordKind.OTHER, daysAgo = 3.0)
        val yesterday = today.minusDays(1)
        val r = lookups.capturesOn(DateRange(yesterday, yesterday), includeSensitive = false)
        assertEquals(listOf(y2.captureId, y.captureId), r.map { it.captureId })
        assertEquals(listOf("yesterday", "yesterday"), r.map { it.age })
    }

    // ---- Private: never without true

    @Test fun noLookupReturnsAPrivateNoteOrANoteAboutAPrivateSubjectWithoutTrue() = runBlocking {
        val secret = seed.subject("safe", sensitive = true)
        val p1 = seed.note("The safe combination is in the blue book", RecordKind.PLACED, daysAgo = 1.0, item = "safe combination", where = "in the blue book", direction = Direction.PLACED, sensitive = true)
        val p2 = seed.note("Moved the safe key to the closet", RecordKind.PLACED, daysAgo = 2.0, subject = secret, item = "safe key", itemSubject = secret, where = "in the closet", direction = Direction.PLACED)
        val p3 = seed.note("Lent my diary to Ann", RecordKind.LOAN, daysAgo = 1.0, item = "diary", person = "Ann", direction = Direction.LENT, sensitive = true)
        val p4 = seed.note("I changed the safe batteries", RecordKind.EVENT, daysAgo = 1.0, sensitive = true)
        val p5 = seed.note("Buy a new safe lock", RecordKind.TASK, daysAgo = 1.0, sensitive = true)
        val p6 = seed.note("Safe test reading", RecordKind.READING, daysAgo = 1.0, subject = secret)
        seed.said("Pool test: free chlorine 5, pH 9.9", daysAgo = 1, sensitive = true)
        val private = setOf(p1, p2, p3, p4, p5, p6).flatMap { listOf(it.recordId, it.captureId) }.toSet()
        val yesterday = today.minusDays(1)
        val seen = mutableListOf<String>()
        seen += lookups.searchNotes("safe", includeSensitive = false).map { it.id }
        seen += lookups.searchNotes(null, on = DateRange(today.minusDays(3), today), includeSensitive = false).map { it.id }
        seen += listOfNotNull(lookups.whereIs("safe combination", includeSensitive = false)?.current?.recordId, lookups.whereIs("safe key", includeSensitive = false)?.current?.recordId)
        seen += lookups.loans(null, includeSensitive = false).map { it.recordId }
        seen += lookups.lastEvent("change the safe batteries", includeSensitive = false).map { it.recordId }
        seen += lookups.openTasks(null, null, includeSensitive = false).hits.map { it.recordId }
        seen += lookups.capturesOn(DateRange(yesterday.minusDays(1), yesterday), includeSensitive = false).flatMap { it.recordIds + it.captureId }
        seen += lookups.aboutSubject("safe", includeSensitive = false)?.newest?.values?.flatten()?.map { it.id }.orEmpty()
        seen += lookups.notesById(private.toList(), includeSensitive = false).map { it.id }
        assertTrue(seen.toString(), seen.none { it in private })
        assertTrue(lookups.readings("pool", "pH", includeSensitive = false).isEmpty())
        // with true (a caller behind the app gate, card 02) they are there; and the count sees them without their words
        assertNotNull(lookups.whereIs("safe key", includeSensitive = true))
        assertEquals(p3.recordId, lookups.loans(null, includeSensitive = true).single().recordId)
        assertEquals(listOf("9.9"), lookups.readings("pool", "pH", includeSensitive = true).map { it.value })
        assertEquals(5, lookups.privateMatchCount("safe"))
    }
}
