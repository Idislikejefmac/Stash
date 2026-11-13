package app.stash

import android.graphics.Bitmap
import app.stash.data.AttachmentKind
import app.stash.data.AttachmentSource
import app.stash.domain.PictureSize
import app.stash.domain.TextStats
import app.stash.pictures.DecodeResult
import app.stash.pictures.DecodedPicture
import app.stash.pictures.PictureDecoder
import app.stash.pictures.PictureEncoder
import app.stash.pictures.PictureException
import app.stash.pictures.PictureFailure
import app.stash.pictures.PictureInput
import app.stash.pictures.PicturePreparer
import app.stash.pictures.PictureText
import app.stash.pictures.PictureTextReader
import app.stash.security.AttachmentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.InputStream

/** Card 16b: the preparer's order and failure behaviour, with a fake decoder, encoder and reader and a real store. */
@RunWith(RobolectricTestRunner::class)
class PicturePreparerTest {
    private val calls = mutableListOf<String>()
    private lateinit var dir: File
    private lateinit var decoded: Bitmap
    private val encodeSizes = mutableListOf<Pair<Int, Int>>()
    private var decodeFailure: PictureFailure? = null

    private val decoder = object : PictureDecoder {
        override fun decode(input: PictureInput): DecodeResult {
            calls += "decode"
            decodeFailure?.let { return DecodeResult.Failed(it) }
            decoded = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)
            return DecodeResult.Ok(DecodedPicture(decoded, 4000, 3000, "image/jpeg"))
        }
    }

    private val encoder = object : PictureEncoder {
        override fun encode(bitmap: Bitmap, width: Int, height: Int): ByteArray {
            calls += "encode"
            encodeSizes += width to height
            return ByteArray(1000) { it.toByte() }
        }

        override fun thumbnail(bitmap: Bitmap, maxLongEdge: Int): Bitmap {
            calls += "thumbnail"
            return Bitmap.createBitmap(384, 288, Bitmap.Config.ARGB_8888)
        }
    }

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "pp-" + java.util.UUID.randomUUID()).also { it.mkdirs() }
    }

    private fun preparer(
        reader: PictureTextReader = object : PictureTextReader {
            override suspend fun read(bitmap: Bitmap): PictureText? { calls += "read"; return null }
        },
        store: AttachmentStore = AttachmentStore(dir),
        size: PictureSize = PictureSize.STANDARD,
        timeoutMs: Long = 5_000,
    ) = PicturePreparer(decoder, encoder, store, { reader }, { size }, { 1080 to 2340 }, Dispatchers.Default, timeoutMs)

    private fun prepare(p: PicturePreparer, hint: AttachmentKind? = AttachmentKind.PHOTO) =
        runBlocking { p.prepare(PictureInput.FromBytes(ByteArray(1)), hint, AttachmentSource.PICKER) }

    @Test
    fun `the words are read before the picture is encoded`() {
        val prepared = prepare(preparer())
        assertEquals(listOf("decode", "read", "encode", "thumbnail"), calls)
        assertEquals(1600 to 1200, encodeSizes.single())
        val a = prepared.attachment
        assertEquals("image/webp", a.mime)
        assertEquals(1000L, a.byteSize)
        assertEquals(1600, a.width)
        assertEquals(1200, a.height)
        assertEquals(AttachmentKind.PHOTO, a.kind)
        assertEquals(setOf(a.fileName), AttachmentStore(dir).list())
        assertTrue("the reading bitmap is recycled", decoded.isRecycled)
        assertEquals(384, prepared.thumbnail.width)
        val back = AttachmentStore(dir).open(a.fileName, a.key, a.id).readBytes()
        assertEquals(1000, back.size)
    }

    @Test
    fun `a reader that hangs is abandoned after the limit and the picture is still stored`() {
        val hang = object : PictureTextReader {
            override suspend fun read(bitmap: Bitmap): PictureText? { calls += "read"; awaitCancellation() }
        }
        val started = System.nanoTime()
        val prepared = prepare(preparer(reader = hang, timeoutMs = 100))
        assertTrue("gave up quickly", (System.nanoTime() - started) / 1_000_000 < 4_000)
        assertNull(prepared.attachment.ocrText)
        assertEquals(1, AttachmentStore(dir).list().size)
    }

    @Test
    fun `a reader that throws means no words, not a failed picture`() {
        val bad = object : PictureTextReader {
            override suspend fun read(bitmap: Bitmap): PictureText? = throw IllegalStateException("boom")
        }
        assertNotNull(prepare(preparer(reader = bad)))
        assertEquals(1, AttachmentStore(dir).list().size)
    }

    @Test
    fun `words read are kept with the engine, and small print raises Standard to 2400`() {
        val words = object : PictureTextReader {
            override suspend fun read(bitmap: Bitmap) = PictureText("hello", TextStats(14, 12f), "fake")
        }
        val prepared = prepare(preparer(reader = words), hint = null)
        // 4000 x 3000, 4000 px reading edge, 12 px letters become 6 px at 1600: raised.
        assertEquals(2400 to 1800, encodeSizes.single())
        assertEquals("hello", prepared.attachment.ocrText)
        assertEquals("fake", prepared.attachment.ocrEngine)
    }

    @Test
    fun `no hint guesses the kind from the picture`() {
        val prepared = prepare(preparer(), hint = null)
        assertEquals(AttachmentKind.PHOTO, prepared.attachment.kind)
        val shot = runBlocking {
            preparer().prepare(PictureInput.FromBytes(ByteArray(1), displayName = "Screenshot_1.png"), null, AttachmentSource.SHARE)
        }
        assertEquals(AttachmentKind.SCREENSHOT, shot.attachment.kind)
        assertEquals(1920 to 1440, encodeSizes.last())
    }

    @Test
    fun `a store failure leaves no file and says so with a code`() {
        val failing = object : AttachmentStore(dir) {
            override fun write(fileName: String, key: ByteArray, attachmentId: String, plain: InputStream): Written =
                throw java.io.IOException("disk full")
        }
        try {
            prepare(preparer(store = failing))
            fail("expected a failure")
        } catch (e: PictureException) {
            assertEquals(PictureFailure.STORE_FAILED, e.failure)
            assertFalse(e.message!!.contains("disk"))
        }
        assertTrue(AttachmentStore(dir).list().isEmpty())
        assertTrue(AttachmentStore(dir).temps().isEmpty())
        assertTrue(decoded.isRecycled)
        assertFalse("no thumbnail is made after a failed store", "thumbnail" in calls)
    }

    @Test
    fun `a picture that cannot be decoded is a code, nothing is stored`() {
        decodeFailure = PictureFailure.UNDECODABLE
        try {
            prepare(preparer())
            fail("expected a failure")
        } catch (e: PictureException) {
            assertEquals(PictureFailure.UNDECODABLE, e.failure)
        }
        assertEquals(listOf("decode"), calls)
        assertTrue(AttachmentStore(dir).list().isEmpty())
    }

    @Test
    fun `discard deletes the file again`() {
        val p = preparer()
        val prepared = prepare(p)
        assertEquals(1, AttachmentStore(dir).list().size)
        p.discard(prepared)
        assertTrue(AttachmentStore(dir).list().isEmpty())
        assertTrue(prepared.thumbnail.isRecycled)
    }

    @Test
    fun `the three sizes give three target sizes`() {
        for (s in PictureSize.entries) prepare(preparer(size = s))
        assertEquals(listOf(1024 to 768, 1600 to 1200, 2400 to 1800), encodeSizes)
    }

    @Test
    fun `nothing is left in the temporary folder`() {
        prepare(preparer())
        assertEquals(1, dir.list()!!.size)
    }
}
