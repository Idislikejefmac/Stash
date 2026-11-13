package app.stash

import app.stash.capture.SavedNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedNoteTest {
    @Test fun undoWindowIsFiveSeconds() {
        val n = SavedNote.of("a", "milk", false, 1000)
        assertTrue(n.undoOpen(5999)); assertFalse(n.undoOpen(6000))
    }

    @Test fun idleClosesAfterTenSecondsAndTouchRestarts() {
        val n = SavedNote.of("a", "milk", false, 0)
        assertFalse(n.idleExpired(9999)); assertTrue(n.idleExpired(10_000))
        assertFalse(n.touched(8000).idleExpired(17_999))
        assertTrue(n.touched(8000).idleExpired(18_000))
    }

    @Test fun touchDoesNotExtendUndo() {
        assertEquals(5000, SavedNote.of("a", "x", false, 0).touched(4000).undoUntil)
    }

    @Test fun privateExtendsUndoAndDropsWords() {
        val n = SavedNote.of("a", "secret", false, 0).markedPrivate(4000)
        assertEquals(7000, n.undoUntil); assertNull(n.words); assertEquals("Saved", n.message())
    }

    @Test fun privateExtraNeverShortens() {
        assertEquals(5000, SavedNote.of("a", "x", false, 0).markedPrivate(0).undoUntil)
    }

    @Test fun privateNoteNeverCarriesWords() {
        val n = SavedNote.of("a", "my pin is 1234", true, 0)
        assertNull(n.words); assertEquals("Saved", n.message())
    }

    @Test fun messageTruncates() {
        val m = SavedNote.of("a", "x".repeat(100), false, 0).message()
        assertTrue(m.endsWith("...")); assertTrue(m.length < 80)
        assertEquals("Saved: hi", SavedNote.of("a", "hi", false, 0).message())
    }
}
