package app.stash

import app.stash.domain.PicturePrivacy
import app.stash.domain.PictureRemoval
import app.stash.domain.PictureViewing
import app.stash.domain.PictureWords
import app.stash.domain.RemoveKind
import app.stash.domain.SizedLruCache
import app.stash.domain.ZoomState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Card 16d's pure rules: wording, which question to ask, the 8 MB cache, zoom, decode size. Plain JVM. */
class PictureStripTest {
    @Test fun wordingIsTheCards() {
        assertEquals("1 picture", PictureWords.count(1))
        assertEquals("5 pictures", PictureWords.count(5))
        assertEquals("3 private pictures · Show", PictureWords.privateRow(3))
        assertEquals("1 private picture · Show", PictureWords.privateRow(1))
        assertEquals("Picture 2 of 5", PictureWords.position(1, 5))
        assertEquals("2 of 5", PictureWords.viewerTitle(1, 5))
        assertEquals("Adding 2 of 5", PictureWords.adding(2, 5))
        assertEquals("I couldn't add that picture.", PictureWords.ADD_FAILED)
        assertEquals("Picture missing", PictureWords.MISSING)
        assertEquals("Remove this picture? It can't be brought back.", PictureRemoval.question(RemoveKind.PICTURE))
        assertEquals("This note is only this picture. Remove the note?", PictureRemoval.question(RemoveKind.NOTE))
    }

    @Test fun storageLine() {
        assertEquals("Pictures: none", PictureWords.storageLine(0, 0))
        assertEquals("Pictures: 42, using 11 MB", PictureWords.storageLine(42, 11L * 1_048_576 + 100))
        assertEquals("Pictures: 3, using less than 1 MB", PictureWords.storageLine(3, 900_000))
    }

    @Test fun onlyAWordlessNoteWithItsLastPictureAsksAboutTheNote() {
        assertEquals(RemoveKind.NOTE, PictureRemoval.kind("", 1))
        assertEquals(RemoveKind.NOTE, PictureRemoval.kind("   ", 1))
        assertEquals(RemoveKind.PICTURE, PictureRemoval.kind("", 2))
        assertEquals(RemoveKind.PICTURE, PictureRemoval.kind("a bottle", 1))
    }

    @Test fun afterRemovingTheViewerMovesToTheNextOrCloses() {
        assertEquals(1, PictureRemoval.nextIndex(1, 2)) // the old third is now second
        assertEquals(1, PictureRemoval.nextIndex(2, 2)) // removed the last: show the new last
        assertEquals(0, PictureRemoval.nextIndex(0, 2))
        assertNull(PictureRemoval.nextIndex(0, 0))
    }

    @Test fun aPrivateNoteIsDrawnOnlyAfterShow() {
        assertTrue(PicturePrivacy.mayDraw(sensitive = false, shown = false))
        assertFalse(PicturePrivacy.mayDraw(sensitive = true, shown = false))
        assertTrue(PicturePrivacy.mayDraw(sensitive = true, shown = true))
    }

    @Test fun sampleSizeKeepsTheLongEdgeAtOrAboveTheTarget() {
        assertEquals(1, PictureViewing.sampleSize(300, 200, 384))
        assertEquals(4, PictureViewing.sampleSize(1920, 1080, 384)) // 480
        assertEquals(1, PictureViewing.sampleSize(2400, 1800, 2400))
        assertEquals(2, PictureViewing.sampleSize(4000, 3000, 2000))
    }

    @Test fun theCacheStaysUnderItsBudgetAndDropsTheOldestFirst() {
        val c = SizedLruCache<String, ByteArray>(100) { it.size.toLong() }
        c.put("a", ByteArray(40)); c.put("b", ByteArray(40))
        c.get("a") // a is now the newest
        c.put("c", ByteArray(40))
        assertEquals(80, c.bytes())
        assertNull(c.get("b"))
        assertTrue(c.get("a") != null && c.get("c") != null)
        c.put("huge", ByteArray(101)) // larger than the whole budget: not kept, nothing evicted
        assertNull(c.get("huge"))
        assertEquals(2, c.count())
        c.put("a", ByteArray(10)) // replacing counts the new size only
        assertEquals(50, c.bytes())
        c.clear()
        assertEquals(0, c.bytes()); assertEquals(0, c.count())
    }

    @Test fun eightMegabytesIsTheDefaultBudget() {
        assertEquals(8L * 1024 * 1024, app.stash.ui.pictures.StoredPictureLoader.CACHE_BYTES)
    }

    @Test fun zoomIsClampedAndPanStaysOnTheScreen() {
        var z = ZoomState()
        assertFalse(z.zoomed)
        z = z.transformed(100f, 0f, 0f, 1000f, 800f)
        assertEquals(ZoomState.MAX_SCALE, z.scale, 0f)
        z = z.transformed(1f, 99_999f, -99_999f, 1000f, 800f)
        assertEquals(1000f * (ZoomState.MAX_SCALE - 1) / 2, z.x, 0.01f)
        assertEquals(-800f * (ZoomState.MAX_SCALE - 1) / 2, z.y, 0.01f)
        assertTrue(z.zoomed)
        // zooming out re-clamps the pan, and back to 1 resets everything
        z = z.transformed(0.5f, 0f, 0f, 1000f, 800f)
        assertEquals(1000f * (z.scale - 1) / 2, z.x, 0.01f)
        assertEquals(ZoomState(), z.transformed(0.01f, 0f, 0f, 1000f, 800f))
        // at 1x a drag does nothing
        assertEquals(ZoomState(), ZoomState().transformed(1f, 50f, 50f, 1000f, 800f))
    }
}
