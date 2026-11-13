package app.stash

import app.stash.domain.Capability
import app.stash.domain.ExtractionChecks
import app.stash.domain.PictureTextRules
import app.stash.domain.RecordKind
import app.stash.domain.RecordsFromPictures
import app.stash.domain.SearchDocs
import app.stash.domain.TextStats
import app.stash.data.Confidence
import app.stash.model.PictureOnlyExtractor
import app.stash.pictures.MlKitPictureText
import app.stash.pictures.NoPictureText
import app.stash.pictures.PictureTextChoice
import app.stash.pictures.PictureTextReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Card 16f, the pure parts: the draft wording, the title rule, the search document, the capability, the reader choice. */
class PictureTextRulesTest {
    @Test fun `the draft joins the first four non-empty lines`() {
        val text = "Valpolicella Classico\nAllegrini\n2022\n750 ml\nProduct of Italy"
        assertEquals("Read from the picture: Valpolicella Classico · Allegrini · 2022 · 750 ml", PictureTextRules.draftLine(text))
    }

    @Test fun `the draft with two lines shows two`() {
        assertEquals("Read from the picture: One · Two", PictureTextRules.draftLine("One\nTwo"))
    }

    @Test fun `no words means no draft`() {
        assertNull(PictureTextRules.draftLine(null))
        assertNull(PictureTextRules.draftLine(""))
        assertNull(PictureTextRules.draftLine("  \n \n"))
    }

    @Test fun `a blank first line is skipped`() {
        assertEquals("Read from the picture: Real · Words", PictureTextRules.draftLine("\n   \nReal\n\nWords"))
    }

    @Test fun `the title is the first two lines, cut at 80 characters, or Picture`() {
        assertEquals("Allegrini · Valpolicella", PictureTextRules.title("\nAllegrini\nValpolicella\n2022"))
        assertEquals("Only", PictureTextRules.title("Only"))
        assertEquals("Picture", PictureTextRules.title(null))
        assertEquals("Picture", PictureTextRules.title("  \n"))
        val long = "x".repeat(100) + "\nsecond"
        assertEquals(80, PictureTextRules.title(long).length)
    }

    @Test fun `text is cut at 4000 characters and the stats count lines and take the median height`() {
        assertEquals(4_000, PictureTextRules.cut("a".repeat(5_000)).length)
        assertEquals("short", PictureTextRules.cut("short"))
        assertEquals(TextStats(3, 12f), PictureTextRules.stats(listOf(30f, 12f, 10f)))
        assertEquals(TextStats(4, 11f), PictureTextRules.stats(listOf(10f, 12f, 30f, 9f)))
        assertEquals(TextStats(0, 0f), PictureTextRules.stats(emptyList()))
    }

    @Test fun `the engine name carries the library version, and the stored copy says so once`() {
        assertEquals("mlkit-text-latin/16.0.1", PictureTextRules.engine("16.0.1"))
        assertEquals("mlkit-text-latin/16.0.1/stored", PictureTextRules.asStored("mlkit-text-latin/16.0.1"))
        assertEquals("mlkit-text-latin/16.0.1/stored", PictureTextRules.asStored("mlkit-text-latin/16.0.1/stored"))
    }

    @Test fun `the engine string's version is the one pinned in the version catalogue`() {
        val toml = File("../gradle/libs.versions.toml").readText()
        val pinned = Regex("""mlkitTextRecognition\s*=\s*"([^"]+)"""").find(toml)!!.groupValues[1]
        assertEquals(pinned, MlKitPictureText.LIBRARY_VERSION)
    }

    @Test fun `the search document is his words then each picture's words`() {
        assertEquals("wine for Saturday\nALLEGRINl Valpolicella\nsecond", SearchDocs.captureBody("wine for Saturday", listOf("ALLEGRINl Valpolicella", " ", "second")))
        assertEquals("only picture words", SearchDocs.captureBody("", listOf("only picture words")))
        assertEquals("his words", SearchDocs.captureBody("his words"))
    }

    @Test fun `the picture-text switch needs no permission, is on by default, and says what still works`() {
        val c = Capability.PICTURE_TEXT
        assertTrue(c.permissions.isEmpty())
        assertTrue(c.defaultOn)
        assertEquals("Pictures are found by what you said, the place and the date, not by the words printed on them.", c.withoutIt)
    }

    @Test fun `the disclosure line is the fixed one`() {
        assertEquals(
            "The phone reads the words itself. The reader is made by Google and sends Google figures about how it is used and how it performs. It does not send your pictures or their words.",
            PictureTextRules.DISCLOSURE,
        )
    }

    @Test fun `with the switch off the library's reader is never built`() {
        var built = 0
        val off = PictureTextChoice.reader(false) { built++; NoPictureText }
        assertSame(NoPictureText, off)
        assertEquals(0, built)
        val fake = object : PictureTextReader { override suspend fun read(bitmap: android.graphics.Bitmap) = null }
        assertSame(fake, PictureTextChoice.reader(true) { fake })
    }

    @Test fun `the picture-only extractor makes one low-confidence reference titled from the first two lines`() {
        val ok = PictureOnlyExtractor.extract("Allegrini\nValpolicella\n2022")
        val r = ok.records.single()
        assertEquals(RecordKind.REFERENCE, r.kind)
        assertEquals(Confidence.LOW, r.sure)
        assertEquals("Allegrini · Valpolicella", r.title)
        assertEquals("Picture", PictureOnlyExtractor.extract(null).records.single().title)
        assertTrue(RecordsFromPictures.isPictureText(PictureOnlyExtractor.name))
        assertFalse(RecordsFromPictures.isPictureText("rules-2"))
    }

    /** Why the runner exempts picture-1 from the quote check: its quote is picture text and he said nothing. */
    @Test fun `the ordinary check would reject a picture-only record because its quote is not in his text`() {
        val rec = PictureOnlyExtractor.extract("Allegrini").records
        assertFalse(ExtractionChecks.quoteInText(rec.single().quote, ""))
        val checked = ExtractionChecks.check(rec, "")
        assertEquals("BAD_QUOTE", checked.single().attention)
    }
}
