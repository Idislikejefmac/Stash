package app.stash

import app.stash.data.AppEventEntity
import app.stash.data.TranscribeState
import app.stash.data.TranscriptionStatus
import app.stash.debug.C1Report
import app.stash.pipeline.C1Outcome
import app.stash.pipeline.ForegroundTracker
import app.stash.pipeline.KillCheck
import app.stash.pipeline.PassWhere
import app.stash.pipeline.RunMarker
import app.stash.pipeline.StartRule
import app.stash.pipeline.TranscribeNotice
import app.stash.pipeline.TranscriptionPolicy
import app.stash.pipeline.TranscriptionPolicy.Row
import app.stash.pipeline.TranscriptionWorkShape
import app.stash.speech.RunPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Card 26f: the pure rules of the transcription pass (states, back-off, kills, starts, C1), with an injected clock. */
class TranscriptionPolicyTest {
    private val clock = FakeClock(1_800_000_000_000L)

    @Test fun pendingIsDueAtOnceAndRetryWaitsTheBackOffFromTheLastChange() {
        val t = clock.now()
        assertEquals(0L, TranscriptionPolicy.waitMs(TranscribeState.PENDING, 0, t, t))
        assertEquals(60_000L, TranscriptionPolicy.waitMs(TranscribeState.RETRY, 1, t, t))
        clock.advance(59_999)
        assertEquals(1L, TranscriptionPolicy.waitMs(TranscribeState.RETRY, 1, t, clock.now()))
        clock.advance(1)
        assertEquals(0L, TranscriptionPolicy.waitMs(TranscribeState.RETRY, 1, t, clock.now()))
        assertEquals(600_000L, TranscriptionPolicy.waitMs(TranscribeState.RETRY, 2, t, t))
        assertEquals(3_600_000L, TranscriptionPolicy.waitMs(TranscribeState.RETRY, 3, t, t))
        assertEquals(21_600_000L, TranscriptionPolicy.waitMs(TranscribeState.RETRY, 4, t, t))
        // RETRY with no counted attempt is due at once; DONE, GAVE_UP and "no recording" are never due.
        assertEquals(0L, TranscriptionPolicy.waitMs(TranscribeState.RETRY, 0, t, t))
        assertNull(TranscriptionPolicy.waitMs(TranscribeState.DONE, 0, t, t))
        assertNull(TranscriptionPolicy.waitMs(TranscribeState.GAVE_UP, 5, t, t))
        assertNull(TranscriptionPolicy.waitMs(null, 0, t, t))
    }

    @Test fun theBackOffIsExtractionsAndTheFifthAttemptGivesUp() {
        assertEquals(listOf(60_000L, 600_000L, 3_600_000L, 21_600_000L, 86_400_000L), TranscriptionPolicy.BACK_OFF_MS)
        assertEquals(5, TranscriptionPolicy.MAX_ATTEMPTS)
        assertFalse((1..4).any { TranscriptionPolicy.giveUp(it) })
        assertTrue(TranscriptionPolicy.giveUp(5))
        assertEquals(86_400_000L, TranscriptionPolicy.backoffMs(9))
    }

    private fun failed(code: String) = Row(TranscriptionStatus.FAILED, code)
    private val words = Row(TranscriptionStatus.CURRENT, null)

    @Test fun killsCountOnlySinceTheLastReset() {
        val bg = TranscriptionPolicy.KILLED_BG
        // Two background kills among the last two counted attempts: background passes leave the note alone.
        assertTrue(TranscriptionPolicy.skipInBackground(listOf(failed(bg), failed(bg)), attempts = 2, firstWritingOut = true))
        // "Try again" reset attempts to 0: the old kills no longer count.
        assertFalse(TranscriptionPolicy.skipInBackground(listOf(failed(bg), failed(bg)), attempts = 0, firstWritingOut = true))
        // Only the newest `attempts` failures count.
        assertFalse(TranscriptionPolicy.skipInBackground(listOf(failed(bg), failed("ENGINE_ERROR"), failed(bg)), attempts = 2, firstWritingOut = true))
        // For an "again" request, failures since the last row with words.
        assertEquals(1, TranscriptionPolicy.kills(listOf(failed(bg), words, failed(bg)), 0, false, bg))
        assertEquals("KILLED_FG", TranscriptionPolicy.killCode(PassWhere.FOREGROUND))
        assertEquals("KILLED_BG", TranscriptionPolicy.killCode(PassWhere.BACKGROUND))
    }

    @Test fun anAgainRequestIsDroppedAfterTwoForegroundKillsAPermanentFailureOrFiveFailures() {
        val fg = TranscriptionPolicy.KILLED_FG
        assertFalse(TranscriptionPolicy.abandonAgainRequest(listOf(failed(fg), words)))
        assertTrue(TranscriptionPolicy.abandonAgainRequest(listOf(failed(fg), failed(fg), words)))
        assertTrue(TranscriptionPolicy.abandonAgainRequest(listOf(failed(TranscriptionPolicy.UNREADABLE), words)))
        assertTrue(TranscriptionPolicy.abandonAgainRequest(List(5) { failed("OUT_OF_MEMORY") } + words))
        assertFalse(TranscriptionPolicy.abandonAgainRequest(List(4) { failed("OUT_OF_MEMORY") } + words))
    }

    @Test fun aMarkerFromAnotherProcessIsAKillAndOurOwnIsNot() {
        assertEquals(KillCheck.Verdict.None, KillCheck.judge(null, "me"))
        assertEquals(KillCheck.Verdict.OwnLeftover, KillCheck.judge(RunMarker("c", 1, PassWhere.FOREGROUND, "me"), "me"))
        assertEquals(KillCheck.Verdict.Killed("c", PassWhere.BACKGROUND), KillCheck.judge(RunMarker("c", 1, PassWhere.BACKGROUND, "dead"), "me"))
    }

    @Test fun passPlacesUseTheCardsThreads() {
        assertEquals(RunPlace.ON_SCREEN, PassWhere.FOREGROUND.runPlace)
        assertEquals(2, PassWhere.FOREGROUND.runPlace.threads)
        assertEquals(1, PassWhere.BACKGROUND.runPlace.threads)
        assertEquals(0, PassWhere.FOREGROUND.eventCode); assertEquals(1, PassWhere.BACKGROUND.eventCode)
    }

    @Test fun checkC1IsOneConstantAndJ1UntilThePhoneSaysOtherwise() {
        assertEquals(C1Outcome.J1, TranscriptionWorkShape.OUTCOME)
        assertTrue(TranscriptionWorkShape.WORK_EXPEDITED); assertFalse(TranscriptionWorkShape.WORK_NEEDS_CHARGING)
        // The card's table.
        assertTrue(C1Outcome.J2.expedited); assertFalse(C1Outcome.J2.needsCharging)
        assertFalse(C1Outcome.J3.expedited); assertTrue(C1Outcome.J3.needsCharging)
        assertEquals("Recordings are written out when Stash is open or the phone is charging.", C1Outcome.J3.settingsLine)
        assertNull(C1Outcome.J1.settingsLine)
        // An expedited job cannot wait for charging.
        assertTrue(TranscriptionWorkShape.expedite(C1Outcome.J1)); assertFalse(TranscriptionWorkShape.expedite(C1Outcome.J3))
        assertEquals("stash-transcribe", TranscriptionWorkShape.NOW)
        assertEquals("stash-transcribe-later", TranscriptionWorkShape.LATER)
    }

    @Test fun theJobAlwaysStartsAndTheForegroundPassOnlyWhileAScreenIsUp() {
        assertEquals(StartRule.Starts(foreground = false, job = true), StartRule.decide(0))
        assertEquals(StartRule.Starts(foreground = true, job = true), StartRule.decide(2))
        val f = ForegroundTracker()
        assertTrue(f.started()); assertFalse(f.started()); assertEquals(2, f.count())
        f.stopped(); f.stopped(); f.stopped()
        assertEquals(0, f.count())
        assertTrue(f.started())
    }

    @Test fun aWaitingNoteSaysSoInPlainWords() {
        assertEquals(TranscribeNotice.NEEDS_MODEL, TranscribeNotice.line(TranscribeState.PENDING, hasWords = false, modelReady = false))
        assertTrue(TranscribeNotice.NEEDS_MODEL.contains("needs a speech model"))
        assertEquals(TranscribeNotice.WAITING, TranscribeNotice.line(TranscribeState.RETRY, false, true))
        assertEquals(TranscribeNotice.GAVE_UP, TranscribeNotice.line(TranscribeState.GAVE_UP, false, true))
        assertNull(TranscribeNotice.line(TranscribeState.DONE, true, true))
        assertNull(TranscribeNotice.line(null, true, false))
        assertEquals(TranscribeNotice.NO_WORDS, TranscribeNotice.line(TranscribeState.DONE, false, true))
    }

    // ---------------------------------------------------------------- the C1 report (debug tool)

    private var n = 0
    private fun ev(at: Long, component: String, code: String, ref: String, n2: Long? = null) =
        AppEventEntity("e${n++}", at, component, code, n2 = n2, refId = ref)

    @Test fun theC1ReportReadsTheEventsAndTakesTheWorstRun() {
        val fast = listOf(ev(0, "AUDIO", "STORED", "a"), ev(500, "TRANSCRIBE", "STARTED", "a", 1), ev(4_000, "TRANSCRIBE", "OK", "a"))
        val late = listOf(ev(10_000, "AUDIO", "STORED", "b"), ev(1_000_000, "TRANSCRIBE", "STARTED", "b", 1), ev(1_003_000, "TRANSCRIBE", "OK", "b"))
        val killed = listOf(ev(20_000, "AUDIO", "STORED", "c"), ev(20_100, "TRANSCRIBE", "STARTED", "c", 1), ev(90_000, "TRANSCRIBE", "KILLED", "c"))
        val other = listOf(ev(1, "AUDIO", "STORED", "not-ours"))

        val runs = C1Report.runs(fast + late + killed + other, setOf("a", "b", "c"))
        assertEquals(listOf("a", "b", "c"), runs.map { it.attachmentId })
        assertEquals(500L, runs[0].startedAfterMs); assertEquals(4_000L, runs[0].okAfterMs); assertEquals("job", runs[0].by)
        assertEquals(1, runs[2].killed); assertNull(runs[2].okAfterMs)

        assertEquals(C1Outcome.J1, C1Report.reading(C1Report.runs(fast, setOf("a"))))
        assertEquals(C1Outcome.J2, C1Report.reading(C1Report.runs(fast + late, setOf("a", "b"))))
        assertEquals(C1Outcome.J3, C1Report.reading(runs))
        assertNull(C1Report.reading(emptyList()))
        // Finished by a foreground pass (Stash was opened first): not the job, so not J1.
        val byScreen = listOf(ev(0, "AUDIO", "STORED", "d"), ev(500, "TRANSCRIBE", "STARTED", "d", 0), ev(900, "TRANSCRIBE", "OK", "d"))
        assertEquals(C1Outcome.J2, C1Report.reading(C1Report.runs(byScreen, setOf("d"))))
    }
}
