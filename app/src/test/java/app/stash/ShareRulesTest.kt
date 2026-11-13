package app.stash

import app.stash.domain.ShareRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Card 16c: the screen's wording and small decisions, pure. */
class ShareRulesTest {
    @Test fun gettingReadyWording() {
        assertEquals("Getting 1 of 1 ready", ShareRules.gettingReady(1, 1))
        assertEquals("Getting 3 of 12 ready", ShareRules.gettingReady(3, 12))
    }

    @Test fun couldNotBeAddedWording() {
        assertNull(ShareRules.couldNotAdd(0))
        assertEquals("1 picture could not be added", ShareRules.couldNotAdd(1))
        assertEquals("2 pictures could not be added", ShareRules.couldNotAdd(2))
        assertEquals("12 pictures could not be added", ShareRules.couldNotAdd(12))
    }

    @Test fun saveNeedsAPictureOrWords() {
        assertFalse(ShareRules.canSave(ready = 0, pending = 0, text = ""))
        assertFalse(ShareRules.canSave(0, 0, "   \n"))
        assertTrue(ShareRules.canSave(1, 0, ""))
        assertTrue(ShareRules.canSave(0, 0, "milk"))
        // Still being made ready: Save is allowed and waits.
        assertTrue(ShareRules.canSave(0, 2, ""))
    }

    @Test fun whatCanReallyBeSavedOnceNothingIsPending() {
        assertFalse(ShareRules.hasContent(0, ""))
        assertFalse(ShareRules.hasContent(0, " "))
        assertTrue(ShareRules.hasContent(1, ""))
        assertTrue(ShareRules.hasContent(0, "a"))
    }

    @Test fun wordsHeardAreAddedAfterWhatIsThere() {
        assertEquals("hello", ShareRules.join("", "hello"))
        assertEquals("hello", ShareRules.join("   ", " hello "))
        assertEquals("a link https://x.test and more", ShareRules.join("a link https://x.test", "and more"))
        assertEquals("a link ", ShareRules.join("a link ", " "))
        assertEquals("a link and more", ShareRules.join("a link ", "and more"))
        assertEquals("keep", ShareRules.join("keep", ""))
    }

    @Test fun theOwnerDecisionIsOneValue() {
        // D80: listening starts by itself; to be reviewed after two weeks, turned off by changing this one value.
        assertTrue(ShareRules.AUTO_LISTEN)
        assertEquals(25_000L, ShareRules.SILENCE_LIMIT_MS)
        assertEquals(60_000L, ShareRules.HARD_LIMIT_MS)
    }

    @Test fun fixedWordsAreTheCards() {
        assertEquals("Anything to add about this?", ShareRules.TITLE)
        assertEquals("Pictures are switched off in Stash. Turn them on in Settings.", ShareRules.PICTURES_OFF)
        assertEquals("I couldn't save that. Try again.", ShareRules.SAVE_FAILED)
        assertEquals("This picture is still in your gallery too. Stash keeps its own copy.", ShareRules.GALLERY_NOTE)
    }
}
