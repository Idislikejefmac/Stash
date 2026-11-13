package app.stash

import android.graphics.Bitmap
import app.stash.capture.InboundAttachment
import app.stash.data.AttachmentKind
import app.stash.data.AttachmentSource
import app.stash.data.CaptureSource
import app.stash.data.Confidence
import app.stash.data.ExtractState
import app.stash.data.ExtractionStatus
import app.stash.data.Ids
import app.stash.data.StashDatabase
import app.stash.data.Store
import app.stash.domain.Capability
import app.stash.domain.Dbg
import app.stash.domain.DbgCode
import app.stash.domain.DbgComponent
import app.stash.domain.RecordKind
import app.stash.domain.RecordsFromPictures
import app.stash.domain.SendableText
import app.stash.domain.TextStats
import app.stash.model.ExtractContext
import app.stash.model.ExtractResult
import app.stash.model.Extractor
import app.stash.model.rules.RulesExtractor
import app.stash.pictures.MlKitPictureText
import app.stash.pictures.NoPictureText
import app.stash.pictures.PictureCatchUp
import app.stash.pictures.PictureText
import app.stash.pictures.PictureTextReader
import app.stash.pipeline.ExtractionPipeline
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
import java.io.ByteArrayInputStream
import java.io.File
import java.time.ZoneOffset
import java.util.Random

/** Card 16f with real Room and real files: finding by the words on a picture, his words winning, picture-only notes, the catch-up. */
@RunWith(RobolectricTestRunner::class)
class PictureTextTest {
    private lateinit var db: StashDatabase
    private lateinit var dir: File
    private lateinit var store: app.stash.security.AttachmentStore
    private val clock = FakeClock(System.currentTimeMillis())
    private val repo by lazy { TestSupport.repo(db, clock, store) }
    private val events = mutableListOf<Triple<DbgComponent, DbgCode, Long?>>()

    /** A hosted model that must never be asked. */
    private val explodingModel = object : Extractor {
        override val name = "never"
        override suspend fun extract(text: SendableText, context: ExtractContext): ExtractResult = throw AssertionError("a hosted model was called")
    }

    @Before fun setUp() {
        db = TestSupport.memoryDb()
        dir = TestSupport.tempDir()
        store = app.stash.security.AttachmentStore(dir)
        Dbg.sink = app.stash.domain.EventSink { c, code, n1, _, _, _ -> synchronized(events) { events += Triple(c, code, n1) } }
    }

    @After fun tearDown() {
        Dbg.sink = null
        db.close()
        dir.deleteRecursively()
    }

    private fun pic(ocr: String?, engine: String? = if (ocr != null) "fake/1" else null): InboundAttachment {
        val plain = ByteArray(500).also { Random().nextBytes(it) }
        val id = Ids.new(); val key = store.newKey(); val name = store.newFileName()
        val w = store.write(name, key, id, ByteArrayInputStream(plain))
        return InboundAttachment(
            id, AttachmentKind.PHOTO, AttachmentSource.SHARE, "image/webp", name, key, w.byteSize, w.sha256, 1600, 1200,
            ocrText = ocr, ocrEngine = engine,
        )
    }

    private suspend fun note(text: String, vararg pics: InboundAttachment) =
        repo.captures.insertCapture(rawText = text, text = text, source = CaptureSource.SHARED, attachments = pics.toList())

    private suspend fun found(q: String): Set<String> = repo.index.search(q).map { it.captureId }.toSet()

    private fun pipeline(hosted: Extractor? = null) = ExtractionPipeline(
        store = { Store.Ready(db) }, rules = RulesExtractor(), hostedExtractor = { hosted }, hostedOn = { true },
        hasKey = { true }, networkAvailable = { true }, clock = clock, zone = { ZoneOffset.UTC },
    )

    // ------------------------------------------------------------------ finding by the words

    @Test fun `a note is found by his words and by the picture's words, and rebuild changes nothing`() = runBlocking {
        val c = note("wine for Saturday", pic("ALLEGRINl Valpolicella"))
        val other = note("a different note")
        for (q in listOf("Saturday", "Valpolicella", "ALLEGRINl")) assertEquals(q, setOf(c.id), found(q))
        assertEquals(setOf(other.id), found("different"))
        val before = listOf("Saturday", "Valpolicella", "ALLEGRINl").map { found(it) }
        repo.index.rebuild()
        assertEquals(before, listOf("Saturday", "Valpolicella", "ALLEGRINl").map { found(it) })
    }

    @Test fun `after the picture is removed only his words find the note`() = runBlocking {
        val p = pic("ALLEGRINl Valpolicella")
        val c = note("wine for Saturday", p)
        assertTrue(repo.attachments.remove(p.id))
        assertEquals(setOf(c.id), found("Saturday"))
        assertTrue(found("Valpolicella").isEmpty())
        repo.index.rebuild()
        assertTrue(found("Valpolicella").isEmpty())
        assertEquals(setOf(c.id), found("Saturday"))
    }

    @Test fun `pictures added later are searchable at once`() = runBlocking {
        val c = note("a label")
        repo.attachments.addTo(c.id, listOf(pic("Chateau Margaux")))
        assertEquals(setOf(c.id), found("Margaux"))
    }

    @Test fun `words stored later with updateOcr make the note findable`() = runBlocking {
        val p = pic(null)
        val c = note("a label", p)
        assertTrue(found("Margaux").isEmpty())
        repo.attachments.setOcr(p.id, "Chateau Margaux", "fake/1")
        assertEquals(setOf(c.id), found("Margaux"))
    }

    @Test fun `a deleted note's picture words are not searchable, before or after new words arrive`() = runBlocking {
        val p = pic("Valpolicella")
        val c = note("wine", p)
        repo.deleteCapture(c.id)
        assertTrue(found("Valpolicella").isEmpty())
        repo.attachments.setOcr(p.id, "Valpolicella Classico", "fake/1")
        assertTrue(found("Classico").isEmpty())
        repo.index.rebuild()
        assertTrue(found("Valpolicella").isEmpty())
        repo.captures.restore(c.id)
        assertEquals(setOf(c.id), found("Valpolicella"))
    }

    // ------------------------------------------------------------------ his words win

    @Test fun `the records come from his words only, and both spellings are searchable`() = runBlocking {
        val c = note("Allegrini, row 5", pic("ALLEGRINl"))
        val model = FakeExtractor()
        pipeline(model).runPass()
        // Even a hosted model is shown his words only, never the picture's.
        assertEquals(listOf("Allegrini, row 5"), model.asked)
        val recs = db.records().forCapture(c.id)
        assertTrue(recs.isNotEmpty())
        for (r in recs) {
            assertFalse(r.title, r.title.contains("ALLEGRINl", ignoreCase = true))
            assertFalse(r.quote, r.quote.contains("ALLEGRINl", ignoreCase = true))
            assertTrue(r.quote.contains("Allegrini", ignoreCase = true))
        }
        assertEquals(setOf(c.id), found("Allegrini"))
        assertEquals(setOf(c.id), found("ALLEGRINl"))
        val extraction = db.extractions().forCapture(c.id).single { it.status == ExtractionStatus.CURRENT }
        assertFalse(extraction.outputJson.contains("ALLEGRINl", ignoreCase = true))
    }

    // ------------------------------------------------------------------ a picture with no words from him

    @Test fun `a picture-only note gets one reference titled with the first two lines, and no model is asked`() = runBlocking {
        val c = note("", pic("Allegrini\nValpolicella Classico\n2022"))
        val result = pipeline(explodingModel).runPass()
        assertEquals(1, result.processed)
        val r = db.records().forCapture(c.id).single()
        assertEquals(RecordKind.REFERENCE, r.kind)
        assertEquals("Allegrini · Valpolicella Classico", r.title)
        assertEquals(Confidence.LOW, r.confidence)
        assertNull("the quote check does not apply, so nothing is flagged", r.attention)
        assertEquals("picture-1", db.extractions().forCapture(c.id).single { it.status == ExtractionStatus.CURRENT }.extractor)
        assertEquals(ExtractState.DONE, db.captures().get(c.id)!!.extractState)
        assertTrue(RecordsFromPictures.isPictureText("picture-1"))
        assertEquals(setOf(c.id), found("Valpolicella"))
    }

    @Test fun `a picture-only note with nothing read is titled Picture and still saved`() = runBlocking {
        val c = note("", pic(null))
        pipeline(explodingModel).runPass()
        assertEquals("Picture", db.records().forCapture(c.id).single().title)
        assertEquals("", db.captures().get(c.id)!!.text)
    }

    @Test fun `words typed later are read by the normal extractors and the picture record is replaced`() = runBlocking {
        val c = note("", pic("Allegrini\nValpolicella"))
        pipeline().runPass()
        assertEquals("Allegrini · Valpolicella", db.records().forCapture(c.id).single().title)
        repo.captures.updateText(c.id, "I changed the furnace filter today")
        assertEquals(ExtractState.PENDING, db.captures().get(c.id)!!.extractState)
        pipeline().runPass()
        val recs = db.records().forCapture(c.id)
        assertTrue(recs.none { it.title.contains("Valpolicella") })
        assertEquals(RecordKind.EVENT, recs.single().kind)
        assertEquals(listOf("rules-2"), db.extractions().forCapture(c.id).filter { it.status == ExtractionStatus.CURRENT }.map { it.extractor })
    }

    // ------------------------------------------------------------------ pictures not read yet

    private class FakeReader(private val answer: (Int) -> PictureText?) : PictureTextReader {
        var calls = 0
        override suspend fun read(bitmap: Bitmap): PictureText? = answer(++calls)
    }

    private fun catchUp(
        reader: PictureTextReader,
        on: Boolean = true,
        reread: MutableList<String> = mutableListOf(),
    ) = PictureCatchUp(
        attachments = repo.attachments, enabled = { on }, reader = { reader },
        decode = { Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888) },
        afterRead = { id -> if (repo.captures.get(id)!!.text.isBlank()) reread += id },
    )

    private fun words(n: Int) = PictureText("Words number $n\nsecond line", TextStats(2, 20f), "mlkit-text-latin/16.0.1")

    @Test fun `unread pictures are read from the stored copy and indexed, and one already read is left alone`() = runBlocking {
        val a = pic(null); val b = pic(null); val done = pic("Already read", "mlkit-text-latin/16.0.1")
        val ca = note("first", a); val cb = note("second", b); val cd = note("third", done)
        val reader = FakeReader { words(it) }
        assertEquals(2, catchUp(reader).run())
        assertEquals(2, reader.calls)
        val rows = db.attachments().forCapture(ca.id) + db.attachments().forCapture(cb.id)
        for (r in rows) {
            assertEquals("mlkit-text-latin/16.0.1/stored", r.ocrEngine)
            assertTrue(r.ocrText!!.startsWith("Words number"))
        }
        val untouched = db.attachments().forCapture(cd.id).single()
        assertEquals("Already read", untouched.ocrText)
        assertEquals("mlkit-text-latin/16.0.1", untouched.ocrEngine)
        assertEquals(setOf(ca.id, cb.id), found("number"))
        assertTrue(events.any { it.first == DbgComponent.PICTURE && it.second == DbgCode.CAUGHT_UP && it.third == 2L })
    }

    @Test fun `with the switch off nothing is read`() = runBlocking {
        val c = note("first", pic(null))
        val reader = FakeReader { words(it) }
        assertEquals(0, catchUp(reader, on = false).run())
        assertEquals(0, reader.calls)
        assertNull(db.attachments().forCapture(c.id).single().ocrEngine)
    }

    @Test fun `a picture the reader cannot read goes to the back of the line and does not block others`() = runBlocking {
        note("bad", pic(null)); note("good", pic(null))
        // The first read fails (whichever picture that was); the second succeeds.
        assertEquals(1, catchUp(FakeReader { if (it == 1) null else words(it) }).run())
        assertEquals(1, db.attachments().unreadPictures(10).size)
        clock.advance()
        assertEquals(1, catchUp(FakeReader { words(it) }).run())
        assertTrue(db.attachments().unreadPictures(10).isEmpty())
    }

    @Test fun `only 20 are read in one go, and a deleted note's pictures are skipped`() = runBlocking {
        repeat(22) { note("n$it", pic(null)) }
        val gone = note("gone", pic(null))
        repo.deleteCapture(gone.id)
        val reader = FakeReader { words(it) }
        assertEquals(20, catchUp(reader).run())
        assertEquals(20, reader.calls)
        assertEquals(2, db.attachments().unreadPictures(50).size)
    }

    @Test fun `a picture-only note is filed again with its title once its words are read`() = runBlocking {
        val c = note("", pic(null))
        pipeline().runPass()
        assertEquals("Picture", db.records().forCapture(c.id).single().title)
        val again = mutableListOf<String>()
        assertEquals(1, catchUp(FakeReader { words(it) }, reread = again).run())
        assertEquals(listOf(c.id), again)
    }

    // ------------------------------------------------------------------ the container

    @Test fun `the container hands out the do-nothing reader with the switch off and the library's with it on`() {
        val container = app.stash.data.Container(TestSupport.context as android.app.Application)
        container.settings.setSwitch(Capability.PICTURE_TEXT, false)
        assertTrue(container.pictureText === NoPictureText)
        container.settings.setSwitch(Capability.PICTURE_TEXT, true)
        assertTrue(container.pictureText is MlKitPictureText)
    }
}
