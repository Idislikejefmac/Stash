package app.stash

import android.app.NotificationManager
import android.content.Context
import app.stash.capture.CameraActivity
import app.stash.capture.InboundAttachment
import app.stash.capture.SavedNote
import app.stash.data.AttachmentKind
import app.stash.data.AttachmentSource
import app.stash.data.CaptureEntity
import app.stash.data.CaptureSource
import app.stash.data.ExtractionRunner
import app.stash.data.Ids
import app.stash.data.PhotoHint
import app.stash.data.Store
import app.stash.data.StashDatabase
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.PictureOffer
import app.stash.model.rules.RulesExtractor
import app.stash.pipeline.TaskUpkeep
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
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.File
import java.time.ZoneOffset

private const val HOUR = 3600_000L
private const val VALPOLICELLA = "It's a Valpolicella by Allegrini, in the Italian section, row 5, bucket 10"

/** Card 16g: the quiet "Add a picture?" offer. Time comes from a fake repository clock; nobody sleeps. */
@RunWith(RobolectricTestRunner::class)
class PictureOfferTest {
    private lateinit var db: StashDatabase
    private lateinit var repo: app.stash.data.Repository
    private lateinit var runner: ExtractionRunner
    private val clock = FakeClock(1_794_398_400_000L)
    private val store = TestSupport.tempStore()
    private val events = mutableListOf<Triple<DbgComponent, DbgCode, Long?>>()
    private var counter = 0

    @Before fun setUp() {
        db = TestSupport.memoryDb()
        repo = TestSupport.repo(db, clock, store)
        runner = ExtractionRunner(db, repo.records, clock) { ZoneOffset.UTC }
        Dbg.sink = app.stash.domain.EventSink { c, code, n1, _, _, _ -> synchronized(events) { events += Triple(c, code, n1) } }
    }

    @After fun tearDown() {
        Dbg.sink = null
        db.close()
    }

    private fun file(): InboundAttachment {
        val id = Ids.new(); val key = store.newKey(); val name = store.newFileName()
        val w = store.write(name, key, id, ByteArrayInputStream("pic-${counter++}".toByteArray()))
        return InboundAttachment(id, AttachmentKind.PHOTO, AttachmentSource.CAMERA, "image/webp", name, key, w.byteSize, w.sha256, 100, 100, null)
    }

    private suspend fun capture(text: String, sensitive: Boolean = false, files: List<InboundAttachment> = emptyList()): CaptureEntity {
        clock.advance(60_000)
        return repo.captures.insertCapture(rawText = text, text = text, source = CaptureSource.VOICE, sensitive = sensitive, attachments = files)
    }

    private suspend fun hint(c: CaptureEntity) = db.captures().get(c.id)!!.photoHint

    private suspend fun pendingNote(sensitive: Boolean = false): CaptureEntity {
        val c = capture(VALPOLICELLA, sensitive)
        runner.runLocal(c.id, RulesExtractor())
        return c
    }

    // ------------------------------------------------------------------ PictureOffer.show

    @Test fun showIsTrueForPendingNoPictureNotPrivateOneHourOld() {
        assertTrue(PictureOffer.show(PhotoHint.PENDING, 0, false, HOUR))
    }

    @Test fun showIsFalseForEachOfTheOtherCases() {
        assertFalse(PictureOffer.show(PhotoHint.NONE, 0, false, HOUR))
        assertFalse(PictureOffer.show(PhotoHint.DISMISSED, 0, false, HOUR))
        assertFalse(PictureOffer.show(PhotoHint.PENDING, 1, false, HOUR))
        assertFalse(PictureOffer.show(PhotoHint.PENDING, 0, true, HOUR))
        assertFalse(PictureOffer.show(PhotoHint.PENDING, 0, false, 25 * HOUR))
    }

    @Test fun showEndsExactlyAtTwentyFourHours() {
        assertTrue(PictureOffer.show(PhotoHint.PENDING, 0, false, 24 * HOUR - 1))
        assertFalse(PictureOffer.show(PhotoHint.PENDING, 0, false, 24 * HOUR))
    }

    // ------------------------------------------------------------------ the runner

    @Test fun theValpolicellaSentenceThroughTheRulesMakesTheOfferShow() = runBlocking {
        val c = pendingNote()
        assertEquals(PhotoHint.PENDING, hint(c))
        assertTrue(repo.captures.pictureOfferShown(c.id))
        // Tapping the button starts the camera for that capture: the intent carries the id and nothing else.
        val intent = CameraActivity.intent(TestSupport.context, c.id)
        assertEquals(CameraActivity::class.java.name, intent.component!!.className)
        assertEquals(c.id, intent.getStringExtra(CameraActivity.EXTRA_CAPTURE_ID))
        assertEquals(1, intent.extras!!.keySet().size)
    }

    @Test fun aNoteThatAlreadyHasAPictureIsNeverOffered() = runBlocking {
        val c = capture(VALPOLICELLA, files = listOf(file()))
        runner.runLocal(c.id, RulesExtractor())
        assertEquals(PhotoHint.NONE, hint(c))
        assertFalse(repo.captures.pictureOfferShown(c.id))
    }

    @Test fun readingADismissedNoteAgainLeavesItDismissed() = runBlocking {
        val c = pendingNote()
        assertTrue(repo.captures.setPhotoHint(c.id, PhotoHint.DISMISSED))
        repo.captures.requestReread(c.id)
        runner.runLocal(c.id, RulesExtractor())
        assertEquals(PhotoHint.DISMISSED, hint(c))
    }

    @Test fun aNoteWithoutAPhysicalThingGetsNoHint() = runBlocking {
        val c = capture("I need to remember to call the dentist tomorrow")
        runner.runLocal(c.id, RulesExtractor())
        assertEquals(PhotoHint.NONE, hint(c))
    }

    // ------------------------------------------------------------------ closing the offer

    @Test fun noThanksDismissesAndWritesNoRevision() = runBlocking {
        val c = pendingNote()
        val before = db.revisions().forCapture(c.id).size
        assertTrue(repo.captures.setPhotoHint(c.id, PhotoHint.DISMISSED))
        assertEquals(before, db.revisions().forCapture(c.id).size)
        assertFalse(repo.captures.pictureOfferShown(c.id))
        assertFalse(repo.captures.setPhotoHint(c.id, PhotoHint.DISMISSED)) // already so: nothing changes
        assertFalse(repo.captures.setPhotoHint("no-such-note", PhotoHint.DISMISSED))
    }

    @Test fun addingAPictureToAPendingNoteDismissesAndRemovingItDoesNotBringTheOfferBack() = runBlocking {
        val c = pendingNote()
        val f = file()
        repo.attachments.addTo(c.id, listOf(f))
        assertEquals(PhotoHint.DISMISSED, hint(c))
        repo.attachments.remove(f.id)
        assertEquals(PhotoHint.DISMISSED, hint(c))
        assertFalse(repo.captures.pictureOfferShown(c.id))
        // Reading it again does not bring it back either.
        repo.captures.requestReread(c.id)
        runner.runLocal(c.id, RulesExtractor())
        assertEquals(PhotoHint.DISMISSED, hint(c))
    }

    @Test fun removingThePictureOfANoteThatNeverHadTheOfferDoesNotStartOne() = runBlocking {
        val f = file()
        val c = capture(VALPOLICELLA, files = listOf(f))
        repo.attachments.remove(f.id)
        repo.captures.requestReread(c.id)
        runner.runLocal(c.id, RulesExtractor())
        assertFalse(PhotoHint.PENDING == hint(c))
    }

    @Test fun upkeepDismissesATwentyFiveHourOldOfferAndLeavesAnHourOldOne() = runBlocking {
        val old = pendingNote()
        clock.advance(24 * HOUR)
        val young = pendingNote()
        clock.advance(HOUR) // old is now 25 hours and 1 minute old, young is 1 hour old
        val r = TaskUpkeep.run(store = { Store.Ready(db) }, clock = clock, attachments = store)
        assertEquals(1, r.offersClosed)
        assertEquals(PhotoHint.DISMISSED, hint(old))
        assertEquals(PhotoHint.PENDING, hint(young))
        assertTrue(Triple(DbgComponent.OFFER, DbgCode.DISMISSED, 2L) in events)
    }

    @Test fun theOfferExpiresByTheRepositoryClockWithNoSleeping() = runBlocking {
        val c = pendingNote()
        clock.advance(23 * HOUR)
        assertTrue(repo.captures.pictureOfferShown(c.id))
        clock.advance(2 * HOUR) // 25 hours: the row has stopped showing it even before the upkeep has run
        assertFalse(repo.captures.pictureOfferShown(c.id))
        assertEquals(1, repo.captures.dismissStaleOffers())
        assertEquals(0, repo.captures.dismissStaleOffers())
        assertEquals(PhotoHint.DISMISSED, hint(c))
    }

    @Test fun thePurgeDeletedUpkeepAlsoClosesOldOffers() = runBlocking {
        val c = pendingNote()
        clock.advance(25 * HOUR)
        repo.purgeDeleted()
        assertEquals(PhotoHint.DISMISSED, hint(c))
    }

    // ------------------------------------------------------------------ Private

    @Test fun aPrivateNoteIsNotOfferedButCanStillTakeAPicture() = runBlocking {
        val c = pendingNote(sensitive = true)
        assertFalse(repo.captures.pictureOfferShown(c.id))
        val updated = repo.attachments.addTo(c.id, listOf(file()))
        assertEquals(1, repo.attachments.pictures(c.id).size)
        assertTrue(updated!!.sensitive)
    }

    @Test fun makingANotePrivateOnTheSavedScreenWithdrawsTheOffer() = runBlocking {
        val c = pendingNote()
        assertTrue(repo.captures.pictureOfferShown(c.id))
        repo.captures.setSensitive(c.id, true, "said")
        assertFalse(repo.captures.pictureOfferShown(c.id))
    }

    @Test fun aNoteInTheBinIsNotOffered() = runBlocking {
        val c = pendingNote()
        repo.deleteCapture(c.id)
        assertFalse(repo.captures.pictureOfferShown(c.id))
    }

    // ------------------------------------------------------------------ SavedNote timings

    @Test fun anOfferTwoSecondsAfterSavingKeepsUndoAndIdleOpenUntilTenSeconds() {
        val n = SavedNote.of("a", "words", false, 0).offerAppeared(2_000)
        assertTrue(n.undoOpen(9_999)); assertFalse(n.undoOpen(10_000))
        assertFalse(n.idleExpired(9_999)); assertTrue(n.idleExpired(10_000))
        assertTrue(n.offered)
    }

    @Test fun withNoOfferTheTimingsAreUnchanged() {
        val n = SavedNote.of("a", "words", false, 0)
        assertEquals(5_000, n.undoUntil); assertEquals(10_000, n.idleAt); assertFalse(n.offered)
    }

    @Test fun aLateOfferNeverShortensTheWindowsAndOnlyTheFirstOneExtends() {
        val n = SavedNote.of("a", "words", false, 0).touched(9_000) // idle until 19 000
        val o = n.offerAppeared(4_000)
        assertEquals(12_000, o.undoUntil) // 4 000 + 8 000
        assertEquals(19_000, o.idleAt)
        assertEquals(o, o.offerAppeared(9_000)) // a second appearance changes nothing
    }

    // ------------------------------------------------------------------ nothing else ever mentions the offer

    @Test fun noNotificationIsPostedAndNoAttentionIsSet() = runBlocking {
        val manager = TestSupport.context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val c = pendingNote()
        repo.captures.pictureOfferShown(c.id)
        clock.advance(25 * HOUR)
        TaskUpkeep.run(store = { Store.Ready(db) }, clock = clock, attachments = store)
        val d = pendingNote()
        repo.attachments.addTo(d.id, listOf(file()))
        repo.captures.setPhotoHint(d.id, PhotoHint.DISMISSED)
        assertEquals(0, shadowOf(manager).allNotifications.size)
        for (id in listOf(c.id, d.id)) assertTrue(db.records().forCapture(id).all { it.attention == null })
    }

    @Test fun theOfferCodeNamesNoNotificationOrAttentionApi() {
        val src = File("src/main/java/app/stash/domain/PictureOffer.kt").readText()
        for (word in listOf("NotificationManager", "NotificationCompat", "AlarmManager", "import app.stash.reminders", "Attention")) assertFalse("PictureOffer mentions $word", src.contains(word, ignoreCase = false))
    }

    @Test fun theEventsAreCountsOnly() = runBlocking {
        val c = pendingNote()
        Dbg.event(DbgComponent.OFFER, DbgCode.OFFERED, n1 = 1, refId = c.id)
        repo.attachments.addTo(c.id, listOf(file()))
        assertTrue(Triple(DbgComponent.OFFER, DbgCode.DISMISSED, 3L) in events)
        assertNull(events.firstOrNull { it.first == DbgComponent.OFFER && it.second == DbgCode.OFFERED && it.third == null })
    }
}
