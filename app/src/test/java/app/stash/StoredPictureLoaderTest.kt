package app.stash

import android.graphics.Bitmap
import android.graphics.Color
import app.stash.capture.InboundAttachment
import app.stash.data.AttachmentInfo
import app.stash.data.AttachmentKind
import app.stash.data.AttachmentSource
import app.stash.data.CaptureSource
import app.stash.data.Ids
import app.stash.data.StashDatabase
import app.stash.ui.pictures.PictureResult
import app.stash.ui.pictures.StoredPictureLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Card 16d item 2: decode in memory from the real encrypted store, cache, forget, and "Picture missing". */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class StoredPictureLoaderTest {
    private lateinit var db: StashDatabase
    private val store = TestSupport.tempStore()
    private lateinit var repo: app.stash.data.Repository
    private var opens = 0

    @Before fun setUp() {
        db = TestSupport.memoryDb()
        repo = TestSupport.repo(db, FakeClock(), store)
    }

    @After fun tearDown() = db.close()

    private fun png(w: Int, h: Int): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.RED) }
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun stored(bytes: ByteArray): AttachmentInfo = runBlocking {
        val id = Ids.new(); val key = store.newKey(); val name = store.newFileName()
        val w = store.write(name, key, id, ByteArrayInputStream(bytes))
        val a = InboundAttachment(id, AttachmentKind.PHOTO, AttachmentSource.SHARE, "image/png", name, key, w.byteSize, w.sha256, 1000, 500)
        val c = repo.captures.insertCapture("x", "x", CaptureSource.SHARED, attachments = listOf(a))
        repo.attachments.pictures(c.id).single()
    }

    private fun loader(cache: Long = StoredPictureLoader.CACHE_BYTES) =
        StoredPictureLoader({ id -> opens++; repo.attachments.openBytes(id) }, Dispatchers.Unconfined, cache)

    @Test fun decodesToTheSizeAskedForAndNeverBelowIt() = runBlocking {
        val info = stored(png(1000, 500))
        val r = loader().load(info, 250) as PictureResult.Ready
        assertEquals(250, r.bitmap.width) // sampled by 4, long edge stays at or above 250
        val full = loader().load(info, 4096) as PictureResult.Ready
        assertEquals(1000, full.bitmap.width)
    }

    @Test fun theSecondAskIsServedFromMemoryAndClearForgetsIt() = runBlocking {
        val info = stored(png(100, 50))
        val l = loader()
        l.load(info, 4096); l.load(info, 4096)
        assertEquals(1, opens)
        assertTrue(l.cachedBytes() > 0)
        l.clear()
        assertEquals(0, l.cachedBytes())
        l.load(info, 4096)
        assertEquals(2, opens)
        // a different size is a different entry
        l.load(info, 25)
        assertEquals(3, opens)
    }

    @Test fun theCacheNeverHoldsMoreThanItsBudget() = runBlocking {
        val l = loader(cache = 40_000) // a 100x50 ARGB bitmap is 20,000 bytes
        val infos = List(5) { stored(png(100, 50 + it)) }
        infos.forEach { l.load(it, 4096) }
        assertTrue(l.cachedBytes() <= 40_000)
    }

    @Test fun aMissingOrDamagedFileGivesMissingAndNeverThrows() = runBlocking {
        val gone = stored(png(10, 10))
        // the file vanishes from disk behind the row
        store.list().forEach { store.delete(it) }
        assertEquals(PictureResult.Missing, loader().load(gone, 100))
        // bytes that are no picture
        val junk = stored("not a picture at all".toByteArray())
        assertEquals(PictureResult.Missing, loader().load(junk, 100))
        // a row that no longer exists
        assertEquals(PictureResult.Missing, loader().load(gone.copy(id = "no-such-row"), 100))
    }
}
