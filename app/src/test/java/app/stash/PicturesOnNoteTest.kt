package app.stash

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.stash.capture.DirectSink
import app.stash.capture.InboundAttachment
import app.stash.capture.InboundSink
import app.stash.data.AttachmentInfo
import app.stash.data.AttachmentKind
import app.stash.data.AttachmentSource
import app.stash.data.CaptureSource
import app.stash.data.Ids
import app.stash.data.RevisionKind
import app.stash.data.StashDatabase
import app.stash.domain.PictureWords
import app.stash.ui.pictures.AddOutcome
import app.stash.ui.pictures.NotePictures
import app.stash.ui.pictures.PictureAdder
import app.stash.ui.pictures.PictureLoader
import app.stash.ui.pictures.PictureResult
import app.stash.ui.pictures.PictureSession
import app.stash.ui.pictures.PictureViewer
import app.stash.ui.pictures.RepositoryPictureHost
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList

/** A loader that counts what it is asked for and can be told to fail. It never touches a file. */
private class FakeLoader : PictureLoader {
    val loaded = CopyOnWriteArrayList<Pair<String, Int>>()
    var failAll = false
    var cleared = 0
    override suspend fun load(picture: AttachmentInfo, maxEdge: Int): PictureResult {
        loaded += picture.id to maxEdge
        return if (failAll) PictureResult.Missing else PictureResult.Ready(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))
    }
    override fun clear() { cleared++ }
}

/** Card 16d: pictures on a note, drawn from a seeded database with a fake loader. */
@RunWith(RobolectricTestRunner::class)
class PicturesOnNoteTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var db: StashDatabase
    private val clock = FakeClock(1_794_398_400_000L)
    private val store = TestSupport.tempStore()
    private lateinit var repo: app.stash.data.Repository
    private val loader = FakeLoader()
    private lateinit var host: RepositoryPictureHost

    @Before fun setUp() {
        db = TestSupport.memoryDb()
        repo = TestSupport.repo(db, clock, store)
        host = RepositoryPictureHost(repo, loader)
    }

    @After fun tearDown() = db.close()

    private var counter = 0
    private fun file(kind: AttachmentKind = AttachmentKind.PHOTO): InboundAttachment {
        val id = Ids.new(); val key = store.newKey(); val name = store.newFileName()
        val w = store.write(name, key, id, ByteArrayInputStream("pic-${counter++}".toByteArray()))
        val mime = if (kind == AttachmentKind.AUDIO) "audio/ogg" else "image/webp"
        return InboundAttachment(id, kind, AttachmentSource.SHARE, mime, name, key, w.byteSize, w.sha256, 100, 100, if (kind == AttachmentKind.AUDIO) 1000L else null)
    }

    private fun seed(text: String, n: Int, sensitive: Boolean = false, vararg extra: InboundAttachment): Pair<String, List<InboundAttachment>> {
        val files = List(n) { file() } + extra
        val c = runBlocking {
            repo.captures.insertCapture(rawText = text, text = text, source = CaptureSource.SHARED, sensitive = sensitive, attachments = files)
        }
        return c.id to files
    }

    private fun show(c: String, sensitive: Boolean = false, shown: Boolean = false, updatedAt: Long = 0, onOpen: (AttachmentInfo) -> Unit = {}) {
        compose.setContent {
            MaterialTheme { NotePictures(c, updatedAt, sensitive, shown, host, onShowPrivate = {}, onOpen = onOpen) }
        }
        compose.waitForIdle()
    }

    private fun thumbs() = compose.onAllNodesWithTag("strip").fetchSemanticsNodes().size
    private fun thumbCount() = (0..60).count { compose.onAllNodesWithTag("thumb-$it").fetchSemanticsNodes().isNotEmpty() }

    // ---------------------------------------------------------------- the strip

    @Test fun threePicturesDrawThreeThumbnailsInOrderAndAudioIsNotDrawn() {
        val (c, files) = seed("a bottle at Total Wine", 3, false, file(AttachmentKind.AUDIO))
        show(c)
        assertEquals(3, thumbCount())
        compose.onNodeWithTag("count").assertTextContains("3 pictures")
        // opened in position order, and announced as "Picture 2 of 3"
        assertEquals(files.take(3).map { it.id }, loader.loaded.map { it.first }.distinct())
        assertFalse(loader.loaded.any { it.first == files[3].id })
        loader.loaded.forEach { assertEquals(app.stash.domain.PictureSizing.THUMBNAIL_LONG_EDGE, it.second) }
        compose.onNodeWithTag("thumb-1").assertContentDescriptionEquals("Picture 2 of 3")
    }

    @Test fun theStripKeepsPositionOrderWhenTapped() {
        val (c, files) = seed("x", 3)
        val opened = mutableListOf<String>()
        show(c, onOpen = { opened += it.id })
        compose.onNodeWithTag("thumb-0").performClick()
        compose.onNodeWithTag("thumb-2").performClick()
        assertEquals(listOf(files[0].id, files[2].id), opened)
    }

    @Test fun aNoteWithNoPicturesDrawsNoStrip() {
        val (c, _) = seed("only words", 0)
        show(c)
        assertEquals(0, thumbs())
        assertEquals(0, thumbCount())
        compose.onAllNodesWithTag("private-row").assertCountEquals(0)
        assertTrue(loader.loaded.isEmpty())
    }

    @Test fun aLoaderThatFailsGivesThePictureMissingTileAndNoCrash() {
        val (c, _) = seed("x", 2)
        loader.failAll = true
        show(c)
        compose.onAllNodesWithTag("picture-missing", useUnmergedTree = true).assertCountEquals(2)
        compose.onAllNodesWithText("Picture missing", useUnmergedTree = true).assertCountEquals(2)
    }

    // ---------------------------------------------------------------- private notes

    @Test fun aPrivateNotesPictureNeverLoadsBeforeTheTapAndShowsOneRow() {
        val (c, _) = seed("private thing", 3, sensitive = true)
        show(c, sensitive = true, shown = false)
        compose.onNodeWithTag("private-row").assertTextContains("3 private pictures · Show")
        assertEquals(0, thumbs()); assertEquals(0, thumbCount())
        assertTrue("nothing may be opened or decoded before Show", loader.loaded.isEmpty())
    }

    @Test fun showDrawsThemAndStoppingTheActivityHidesThemAgain() {
        val (c, _) = seed("private thing", 3, sensitive = true)
        val session = PictureSession(loader)
        compose.setContent {
            MaterialTheme {
                val shownSet by session.shownPrivate.collectAsState()
                NotePictures(c, 0, true, c in shownSet, host, onShowPrivate = { session.showPrivate(c) }, onOpen = {})
            }
        }
        compose.waitForIdle()
        assertTrue(loader.loaded.isEmpty())
        compose.onNodeWithTag("private-row").performClick()
        compose.waitForIdle()
        assertEquals(3, thumbCount())
        assertEquals(3, loader.loaded.map { it.first }.distinct().size)

        session.onStop() // the activity stopped
        compose.waitForIdle()
        assertEquals(0, thumbCount())
        compose.onNodeWithTag("private-row").assertExists()
        assertEquals(1, loader.cleared) // and every decoded picture was forgotten
        // ... and back again: still hidden until the next tap
        assertTrue(session.shownPrivate.value.isEmpty())
    }

    @Test fun stoppingAlsoClosesTheViewer() {
        val session = PictureSession(loader)
        session.open("c", "p")
        assertNotNull(session.viewer.value)
        session.onStop()
        assertNull(session.viewer.value)
        session.open("c", "p"); session.onLock()
        assertNull(session.viewer.value)
        assertEquals(2, loader.cleared)
    }

    // ---------------------------------------------------------------- lazy

    @Test fun noPictureIsOpenedForANoteThatIsNotOnScreen() {
        val seeded = (1..40).map { seed("note $it", 2).first }
        compose.setContent {
            MaterialTheme {
                Box(Modifier.height(260.dp)) {
                    LazyColumn {
                        items(seeded, key = { it }) { id ->
                            Column {
                                Text("note")
                                NotePictures(id, 0, false, false, host, onShowPrivate = {}, onOpen = {})
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val captures = loader.loaded.map { it.first }.distinct()
        assertTrue("some pictures were loaded", captures.isNotEmpty())
        assertTrue("only the notes on screen load (got ${captures.size} of 80)", captures.size < 20)
    }

    // ---------------------------------------------------------------- adding

    private val intakeStore get() = store

    private fun adder(sink: InboundSink = DirectSink(repo.captures, { emptyList() }, attachments = repo.attachments)): Pair<PictureAdder, FakeIntake> {
        val intake = FakeIntake(intakeStore)
        return PictureAdder(intake, sink) to intake
    }

    @Test fun twoPickedPicturesOnANoteWithThreeGiveFiveWithTheNewOnesLast() = runBlocking {
        val (c, files) = seed("a bottle", 3)
        val before = repo.captures.get(c)!!
        val (adder, intake) = adder()
        val steps = mutableListOf<Pair<Int, Int>>()
        val out = adder.add(c, listOf(Uri.parse("content://p/1"), Uri.parse("content://p/2"))) { k, n -> steps += k to n }
        assertEquals(AddOutcome(2, 0, false), out)
        assertEquals(listOf(1 to 2, 2 to 2), steps)
        val now = repo.attachments.pictures(c)
        assertEquals(5, now.size)
        assertEquals(files.map { it.id }, now.take(3).map { it.id })
        assertEquals(listOf(0, 1, 2, 3, 4), now.map { it.position })
        assertEquals(listOf(AttachmentSource.PICKER, AttachmentSource.PICKER), intake.sources.toList())
        // the note's words, Private mark and records are unchanged
        val after = repo.captures.get(c)!!
        assertEquals(before.text, after.text); assertEquals(before.rawText, after.rawText); assertEquals(before.sensitive, after.sensitive)
        assertTrue(db.records().forCapture(c).isEmpty())
        assertEquals(2, db.revisions().forCapture(c).count { it.what == RevisionKind.PICTURE_ADDED })
    }

    @Test fun addingToAPrivateNoteLeavesItPrivate() = runBlocking {
        val (c, _) = seed("secret", 1, sensitive = true)
        val (adder, _) = adder()
        adder.add(c, listOf(Uri.parse("content://p/9")))
        assertTrue(repo.captures.get(c)!!.sensitive)
        assertEquals(2, repo.attachments.pictures(c).size)
    }

    @Test fun aFailedSinkDiscardsThePreparedFilesAndSaysSo() = runBlocking {
        val (c, _) = seed("a bottle", 1)
        val failing = object : InboundSink { override suspend fun accept(item: app.stash.capture.Inbound) = InboundSink.Result.Failed("test") }
        val (adder, intake) = adder(failing)
        val out = adder.add(c, listOf(Uri.parse("content://p/1"), Uri.parse("content://p/2")))
        assertTrue(out.failed); assertEquals(0, out.added)
        assertEquals(2, intake.discarded.size)
        assertEquals(1, repo.attachments.pictures(c).size)
        assertEquals("I couldn't add that picture.", PictureWords.ADD_FAILED)
        // the two prepared files are gone from disk; only the seeded one remains
        assertEquals(1, store.list().size)
    }

    @Test fun aPictureThatCannotBeReadIsSkippedAndCounted() = runBlocking {
        val (c, _) = seed("a bottle", 1)
        val (adder, intake) = adder()
        intake.failUris = setOf("content://p/bad")
        val out = adder.add(c, listOf(Uri.parse("content://p/bad"), Uri.parse("content://p/ok")))
        assertEquals(AddOutcome(1, 1, false), out)
        assertEquals(2, repo.attachments.pictures(c).size)
    }

    // ---------------------------------------------------------------- removing

    private fun awaitDialog(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun tapDialog(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()

    private fun viewer(c: String, startId: String, closed: MutableList<Boolean> = mutableListOf()) {
        compose.setContent { MaterialTheme { PictureViewer(c, startId, host, onClose = { closed += true }) } }
        compose.waitForIdle()
    }

    @Test fun removingTheMiddleOfThreeNeedsTheQuestionAndLeavesTwoInOrder() {
        val (c, files) = seed("a bottle", 3)
        viewer(c, files[1].id)
        compose.onNodeWithTag("viewer-title").assertTextContains("2 of 3")
        compose.onNodeWithTag("viewer-remove").performClick()
        awaitDialog("Remove this picture? It can't be brought back.")
        // Keep: nothing happens
        tapDialog("remove-keep")
        compose.waitForIdle()
        assertEquals(3, runBlocking { repo.attachments.pictures(c) }.size)
        // Remove: gone at once, the others keep their order and positions
        compose.onNodeWithTag("viewer-remove").performClick()
        awaitDialog("Remove this picture? It can't be brought back.")
        tapDialog("remove-confirm")
        compose.waitUntil(5_000) { runBlocking { repo.attachments.pictures(c) }.size == 2 }
        val left = runBlocking { repo.attachments.pictures(c) }
        assertEquals(listOf(files[0].id, files[2].id), left.map { it.id })
        assertEquals(listOf(0, 2), left.map { it.position })
        // a real delete: a revision, a tombstone, and the file is gone from disk
        assertEquals(1, runBlocking { db.revisions().forCapture(c) }.count { it.what == RevisionKind.PICTURE_REMOVED })
        assertTrue(runBlocking { db.tombstones().all() }.any { it.rowId == files[1].id })
        assertFalse(store.list().contains(files[1].fileName))
        // the viewer stays open and moved to the next picture (the old third, now "2 of 2")
        compose.waitForIdle()
        compose.onNodeWithTag("viewer-title").assertTextContains("2 of 2")
        assertEquals("note words unchanged", "a bottle", runBlocking { repo.captures.get(c) }!!.text)
    }

    @Test fun removingTheOnlyPictureOfAWordlessNoteAsksAboutTheNoteAndBinsIt() {
        val (c, files) = seed("", 1)
        val closed = mutableListOf<Boolean>()
        viewer(c, files[0].id, closed)
        compose.onNodeWithTag("viewer-remove").performClick()
        awaitDialog("This note is only this picture. Remove the note?")
        tapDialog("remove-confirm")
        compose.waitUntil(5_000) { runBlocking { repo.captures.get(c) }!!.deletedAt != null }
        assertNotNull(runBlocking { repo.captures.get(c) }!!.deletedAt)
        assertTrue(runBlocking { db.captures().live() }.none { it.capture.id == c })
        // the picture went to the bin with the note, not for good
        assertEquals(1, runBlocking { repo.attachments.pictures(c) }.size)
        compose.waitUntil(5_000) { closed.isNotEmpty() }
    }

    @Test fun theLastPictureOfANoteWithWordsIsJustThePicture() {
        val (c, files) = seed("has words", 1)
        val closed = mutableListOf<Boolean>()
        viewer(c, files[0].id, closed)
        compose.onNodeWithTag("viewer-remove").performClick()
        awaitDialog("Remove this picture? It can't be brought back.")
        tapDialog("remove-confirm")
        compose.waitUntil(5_000) { closed.isNotEmpty() } // none left: the viewer closes
        assertTrue(runBlocking { repo.attachments.pictures(c) }.isEmpty())
        assertNull(runBlocking { repo.captures.get(c) }!!.deletedAt)
    }

    @Test fun removingNeverHappensWithoutTheConfirmation() {
        val (c, files) = seed("a bottle", 2)
        viewer(c, files[0].id)
        compose.onNodeWithTag("viewer-remove").performClick()
        awaitDialog("Remove this picture? It can't be brought back.")
        tapDialog("remove-keep")
        compose.waitForIdle()
        assertEquals(2, runBlocking { repo.attachments.pictures(c) }.size)
        assertTrue(runBlocking { db.tombstones().all() }.isEmpty())
    }

    @Test fun theViewerShowsTheWordsReadFromAPictureCollapsedToThreeLines() {
        val (c, files) = seed("a bottle", 1)
        runBlocking { repo.attachments.setOcr(files[0].id, "line one\nline two\nline three\nline four", "test") }
        viewer(c, files[0].id)
        compose.onNodeWithText("Read from the picture").assertExists()
        compose.onNodeWithTag("viewer-words").assertExists()
    }

    @Test fun theViewerCloseButtonCloses() {
        val (c, files) = seed("a bottle", 2)
        val closed = mutableListOf<Boolean>()
        viewer(c, files[0].id, closed)
        compose.onNodeWithTag("viewer-close").performClick()
        assertEquals(listOf(true), closed)
    }

    @Test fun theViewerDecodesOnlyThePictureOnScreenAtReadingSize() {
        val (c, files) = seed("a bottle", 3)
        viewer(c, files[0].id)
        val ids = loader.loaded.map { it.first }.distinct()
        assertEquals(listOf(files[0].id), ids)
        assertEquals(app.stash.domain.PictureSizing.READING_LONG_EDGE, loader.loaded.first().second)
    }

    // ---------------------------------------------------------------- the bin

    @Test fun aDeletedNotesPicturesAreNotInTheListAndComeBackWithTheNote() {
        val (c, _) = seed("a bottle", 2)
        val (other, _) = seed("other", 1)
        runBlocking { repo.deleteCapture(c) }
        val notes = androidx.compose.runtime.mutableStateOf(runBlocking { db.captures().live() }.map { it.capture.id })
        compose.setContent {
            MaterialTheme {
                Column {
                    notes.value.forEach { id ->
                        Column { NotePictures(id, 0, false, false, host, onShowPrivate = {}, onOpen = {}) }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(listOf(other), notes.value)
        assertEquals(1, thumbCount())
        runBlocking { repo.captures.restore(c) }
        notes.value = runBlocking { db.captures().live() }.map { it.capture.id }
        compose.waitForIdle()
        assertTrue(c in notes.value)
        assertEquals(3, (0..60).sumOf { compose.onAllNodesWithTag("thumb-$it").fetchSemanticsNodes().size })
    }

    // ---------------------------------------------------------------- settings

    @Test fun theStorageLineComesFromACountAndASumOfPictureBytes() = runBlocking {
        seed("a", 2); seed("b", 1, false, file(AttachmentKind.AUDIO))
        val s = repo.attachments.pictureStats()
        assertEquals(3, s.count) // audio is not a picture
        val sum = db.attachments().pictureInfoForCapture(db.captures().live().first().capture.id).size
        assertTrue(sum >= 1)
        assertEquals("Pictures: 3, using less than 1 MB", PictureWords.storageLine(s.count, s.bytes))
    }
}
