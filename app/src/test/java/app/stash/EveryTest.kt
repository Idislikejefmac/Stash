package app.stash

import app.stash.capture.Every
import app.stash.capture.SavedNote
import app.stash.capture.WordsLater
import app.stash.data.TranscribeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11-16: the capture screen's polls never ran (Long.MIN_VALUE overflow). */
class EveryTest {
    @Test fun dueAtOnceThenOncePerInterval() {
        for (start in listOf(0L, 1L, 123_456_789L, 1_794_838_616_477L)) { // elapsedRealtime-like and wall-clock-like
            val e = Every(500)
            assertTrue("first ask at $start", e.due(start))
            assertFalse(e.due(start + 100))
            assertFalse(e.due(start + 499))
            assertTrue(e.due(start + 500))
            assertFalse(e.due(start + 900))
            assertTrue(e.due(start + 1_000))
        }
    }

    @Test fun theRecordingHintMatchesThePauseSetting() {
        assertEquals("Stops by itself when you pause.", app.stash.domain.EndPause.SHORT.recordingHint)
        assertEquals("Stops by itself when you pause.", app.stash.domain.EndPause.LONG.recordingHint)
        assertEquals("Tap Done when you've finished.", app.stash.domain.EndPause.ONLY_DONE.recordingHint)
        for (p in app.stash.domain.EndPause.entries) assertEquals(p.pauseMs != null, p.recordingHint.startsWith("Stops by itself"))
    }

    @Test fun theOldTestWasAlwaysFalse() {
        // What the screen used to compute: now - Long.MIN_VALUE overflows.
        @Suppress("INTEGER_OVERFLOW") val wrapped = 123_456_789L - Long.MIN_VALUE
        assertTrue(wrapped < 500)
    }

    /** The ticker as the screen runs it, every 100 ms: the words of a recorded note reach the drifting line. */
    @Test fun aRecordedNotesWordsAreShownWhenTheyArrive() {
        val poll = Every(500)
        var note = SavedNote.recorded("c1", isPrivate = false, now = 0)
        assertEquals("Saved", note.message())
        val wordsAt = 1_000L
        for (t in 0L..3_000L step 100L) {
            if (note.watching && poll.due(t)) {
                val text = if (t >= wordsAt) "I'm putting my glasses in the nightstand drawer." else ""
                val state = if (t >= wordsAt) TranscribeState.DONE else TranscribeState.PENDING
                note = note.arrived(WordsLater.of(found = true, sensitive = false, state = state, text = text))
            }
        }
        assertEquals("Saved: I'm putting my glasses in the nightstand drawer.", note.message())
        assertFalse(note.watching)
    }
}
