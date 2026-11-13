package app.stash

import app.stash.capture.DoneChoice
import app.stash.capture.OpenStashChoice
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class OpenStashChoiceTest {
    @Test fun labelFollowsLockState() {
        assertEquals("Open Stash", OpenStashChoice.label(false))
        assertEquals("Unlock to open Stash", OpenStashChoice.label(true))
    }

    @Test fun offeredWhileListeningButNotSaving() {
        assertTrue(OpenStashChoice.available(listening = true, saving = false))
        assertFalse(OpenStashChoice.available(listening = false, saving = true))
        assertFalse(OpenStashChoice.available(listening = true, saving = true))
        assertTrue(OpenStashChoice.available(listening = false, saving = false))
    }

    @Test fun openWhileListeningDependsOnWhatWasHeard() {
        assertEquals(OpenStashChoice.WhileListening.CANCEL_THEN_OPEN, OpenStashChoice.whileListening(""))
        assertEquals(OpenStashChoice.WhileListening.CANCEL_THEN_OPEN, OpenStashChoice.whileListening("  "))
        assertEquals(OpenStashChoice.WhileListening.SAVE_THEN_OPEN, OpenStashChoice.whileListening("buy milk"))
    }

    @Test fun doneWithNothingHeardLeavesEmpty() {
        assertEquals(DoneChoice.LEAVE_EMPTY, DoneChoice.of(""))
        assertEquals(DoneChoice.LEAVE_EMPTY, DoneChoice.of("  "))
        assertEquals(DoneChoice.SAVE, DoneChoice.of("hello"))
        assertEquals("No note made", DoneChoice.EMPTY_MESSAGE)
    }
}
