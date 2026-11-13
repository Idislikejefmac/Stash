package app.stash

import app.stash.capture.LockedChoice
import app.stash.capture.LockedMic
import app.stash.capture.QuickReason
import app.stash.capture.Route
import app.stash.capture.SavedNote
import app.stash.capture.VoiceRoute
import app.stash.capture.VoiceStarter
import app.stash.capture.WordsLater
import app.stash.data.Capabilities
import app.stash.data.PermissionCheck
import app.stash.data.TranscribeState
import app.stash.domain.Capability
import app.stash.pipeline.TranscribeNotice
import app.stash.speech.Availability
import app.stash.speech.SpeechEngine
import app.stash.speech.TranscribeFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Card 26g sections A and B, and the words of section C: the switch, the route, and what the screens say. Plain JVM. */
class VoiceRouteTest {
    private val notReady = listOf(
        Availability.NoModel, Availability.Off, Availability.LoadFailed(TranscribeFailure.MODEL_CORRUPT),
    )
    private val engines = notReady + Availability.Ready

    // ------------------------------------------------------------------ A. the one switch

    @Test fun `the switch is on by default, needs the microphone, and says what still works without it`() {
        val c = Capability.VOICE_RECORDING
        assertEquals("Keep a recording of each voice note", c.plainName)
        assertTrue(c.defaultOn)
        assertEquals(listOf("android.permission.RECORD_AUDIO"), c.permissions)
        assertEquals(
            "Voice notes still work. The phone writes down your words as you speak and no sound is kept. " +
                "You can't listen again or have a note written out again.",
            c.withoutIt,
        )
    }

    // ------------------------------------------------------------------ B. VoiceRoute.choose, the whole table

    @Test fun `the route for every combination, with both values of the lock setting`() {
        var rows = 0
        for (on in listOf(false, true)) for (engine in engines) for (locked in listOf(false, true))
            for (mic in LockedMic.entries) for (choice in LockedChoice.entries) {
                val expected = when {
                    !on -> Route.Quick(QuickReason.OFF)
                    engine != Availability.Ready -> Route.Quick(QuickReason.NO_MODEL)
                    locked && mic == LockedMic.BLOCKED ->
                        if (choice == LockedChoice.ASK_UNLOCK) Route.AskUnlock else Route.Quick(QuickReason.LOCKED)
                    else -> Route.Record
                }
                assertEquals("on=$on engine=$engine locked=$locked mic=$mic choice=$choice", expected, VoiceRoute.choose(on, engine, locked, mic, choice))
                rows++
            }
        assertEquals(2 * 4 * 2 * 2 * 2, rows)
    }

    @Test fun `each row of the card's table, first match wins`() {
        val ready = Availability.Ready
        // 1. The switch is off: the quick note, whatever else is true, and the screen says nothing extra.
        assertEquals(Route.Quick(QuickReason.OFF), VoiceRoute.choose(false, ready, locked = true, LockedMic.BLOCKED, LockedChoice.ASK_UNLOCK))
        assertNull(QuickReason.OFF.line)
        // 2. No model: the quick note, even when locked with a blocked microphone and "ask me to unlock".
        for (e in notReady) assertEquals(Route.Quick(QuickReason.NO_MODEL), VoiceRoute.choose(true, e, locked = true, LockedMic.BLOCKED, LockedChoice.ASK_UNLOCK))
        assertEquals("No recording: the speech model isn't added yet.", QuickReason.NO_MODEL.line)
        // 3. Locked and blocked: the quick note by default, or ask to unlock if he chose that.
        assertEquals(Route.Quick(QuickReason.LOCKED), VoiceRoute.choose(true, ready, locked = true, LockedMic.BLOCKED))
        assertEquals(Route.Quick(QuickReason.LOCKED), VoiceRoute.choose(true, ready, locked = true, LockedMic.BLOCKED, LockedChoice.QUICK_NOTE))
        assertEquals(Route.AskUnlock, VoiceRoute.choose(true, ready, locked = true, LockedMic.BLOCKED, LockedChoice.ASK_UNLOCK))
        assertEquals("Phone is locked: written down as you speak, no recording.", QuickReason.LOCKED.line)
        assertEquals("Unlock to keep a recording", VoiceRoute.UNLOCK_TO_RECORD)
        // 4. Otherwise: record. Unlocked with a blocked lock-screen microphone still records; so does locked with a working one.
        assertEquals(Route.Record, VoiceRoute.choose(true, ready, locked = false, LockedMic.BLOCKED, LockedChoice.ASK_UNLOCK))
        assertEquals(Route.Record, VoiceRoute.choose(true, ready, locked = true, LockedMic.OK, LockedChoice.ASK_UNLOCK))
        assertEquals(Route.Record, VoiceRoute.choose(true, ready, locked = false, LockedMic.OK))
    }

    @Test fun `adding a note is never refused, and only the asked-for setting asks to unlock`() {
        for (on in listOf(false, true)) for (engine in engines) for (locked in listOf(false, true)) for (mic in LockedMic.entries) {
            val r = VoiceRoute.choose(on, engine, locked, mic) // the default setting
            assertTrue("$r", r is Route.Quick || r == Route.Record)
        }
    }

    @Test fun `check C2 passed, so the lock screen records until this phone shows otherwise`() {
        assertEquals(LockedMic.OK, VoiceRoute.LOCKED_MIC_DEFAULT)
        assertEquals(LockedMic.OK, VoiceRoute.lockedMic(rememberedBlocked = false))
        assertEquals(LockedMic.BLOCKED, VoiceRoute.lockedMic(rememberedBlocked = true))
        // Route R2 (a phone where the check failed) is the same code with the other constant.
        assertEquals(LockedMic.BLOCKED, VoiceRoute.lockedMic(rememberedBlocked = false, default = LockedMic.BLOCKED))
        // Remembered blocked: the next locked note goes straight to the quick route; an unlocked one still records.
        val mic = VoiceRoute.lockedMic(rememberedBlocked = true)
        assertEquals(Route.Quick(QuickReason.LOCKED), VoiceRoute.choose(true, Availability.Ready, locked = true, mic))
        assertEquals(Route.Record, VoiceRoute.choose(true, Availability.Ready, locked = false, mic))
        // "Try recording on the lock screen again" clears it.
        assertEquals(Route.Record, VoiceRoute.choose(true, Availability.Ready, locked = true, VoiceRoute.lockedMic(false)))
    }

    @Test fun `the lock setting has two plain choices and defaults to the quick note`() {
        assertEquals(LockedChoice.QUICK_NOTE, LockedChoice.parse(null))
        assertEquals(LockedChoice.QUICK_NOTE, LockedChoice.parse("junk"))
        assertEquals(LockedChoice.ASK_UNLOCK, LockedChoice.parse("ASK_UNLOCK"))
        assertEquals("Take a quick note without a recording", LockedChoice.QUICK_NOTE.label)
        assertEquals("Ask me to unlock first", LockedChoice.ASK_UNLOCK.label)
        assertEquals("Unlock to add a voice note with a recording", VoiceRoute.ASK_UNLOCK_TITLE)
        assertEquals("Unlock", VoiceRoute.ASK_UNLOCK_BUTTON)
        assertEquals("Quick note instead", VoiceRoute.ASK_UNLOCK_QUICK)
        assertEquals("Say that again, please.", QuickReason.MIC_FAILED.line)
    }

    @Test fun `route codes for the event log are numbers that never collide`() {
        val all = listOf(Route.Record, Route.AskUnlock) + QuickReason.entries.map { Route.Quick(it) }
        assertEquals(all.size, all.map { VoiceRoute.code(it) }.toSet().size)
        assertEquals(0, VoiceRoute.code(Route.Record))
    }

    // ------------------------------------------------------------------ switch off, or no model: the existing flow

    private class Started {
        val speech = FakeSpeech()
        var recorderStarts = 0
        var asked = 0
        val starter = VoiceStarter(speech, startRecording = { recorderStarts++ }, askUnlock = { asked++ })
        val listener = object : SpeechEngine.Listener {
            override fun onFinal(text: String) {}
            override fun onFailure(message: String, noSpeech: Boolean) {}
        }
        fun go(route: Route) = starter.start(route, listener, listOf("Syd"))
    }

    private fun caps(recordingOn: Boolean, micGranted: Boolean = true) =
        Capabilities(MemorySwitches().also { it.setSwitch(Capability.VOICE_RECORDING, recordingOn) }, PermissionCheck { micGranted })

    @Test fun `with the switch off the note is taken the old way, the speech engine is started and the recorder is not`() {
        val s = Started()
        val on = caps(recordingOn = false).isOn(Capability.VOICE_RECORDING)
        s.go(VoiceRoute.choose(on, Availability.Ready, locked = false, LockedMic.OK))
        assertEquals(1, s.speech.starts)
        assertEquals(s.listener, s.speech.listener)
        assertEquals(0, s.recorderStarts)
    }

    @Test fun `with no speech model the note is taken the old way whatever the switch says`() {
        for (engine in notReady) {
            val s = Started()
            val on = caps(recordingOn = true).isOn(Capability.VOICE_RECORDING)
            assertTrue(on)
            s.go(VoiceRoute.choose(on, engine, locked = false, LockedMic.OK))
            assertEquals(1, s.speech.starts)
            assertEquals(0, s.recorderStarts)
        }
        // NoTranscriber (a build with no engine, or tier 0) is "no model": installing this card changes nothing by itself.
        assertEquals(Route.Quick(QuickReason.NO_MODEL), VoiceRoute.choose(true, app.stash.speech.NoTranscriber.availability(), false, LockedMic.OK))
    }

    @Test fun `with the switch on and a model the recorder is started and the speech engine is not`() {
        val s = Started()
        s.go(VoiceRoute.choose(caps(true).isOn(Capability.VOICE_RECORDING), Availability.Ready, locked = true, LockedMic.OK))
        assertEquals(0, s.speech.starts)
        assertEquals(1, s.recorderStarts)
        assertEquals(0, s.asked)
    }

    @Test fun `asking to unlock starts neither until he chooses`() {
        val s = Started()
        s.go(Route.AskUnlock)
        assertEquals(0, s.speech.starts)
        assertEquals(0, s.recorderStarts)
        assertEquals(1, s.asked)
    }

    @Test fun `with every capability off a voice route is still chosen, and it is the quick note`() {
        val allOff = Capabilities(MemorySwitches(default = false), PermissionCheck { false })
        Capability.entries.forEach { assertFalse(allOff.isOn(it)) }
        for (engine in engines) for (locked in listOf(false, true)) {
            assertEquals(Route.Quick(QuickReason.OFF), VoiceRoute.choose(allOff.isOn(Capability.VOICE_RECORDING), engine, locked, LockedMic.OK))
        }
    }

    @Test fun `the capture screen leaves the quick route as it was and never draws live words for a recording`() {
        val src = File("src/main/java/app/stash/capture/CaptureActivity.kt").readText()
        // The quick note still goes through the speech engine and the same save as before.
        assertTrue("container.entry.save(raw, CaptureSource.VOICE, locked, markPrivate = markedPrivate)" in src)
        assertTrue("starter = VoiceStarter(engine, startRecording = { startRecording() })" in src)
        // The recorder is made in one place only, for the recorded route.
        assertEquals(1, Regex("MicRecorder\\(").findAll(src).count())
        // Leaving the screen saves; only Cancel and an answered question discard.
        assertTrue("EndReason.LEFT_SCREEN" in src)
        assertEquals(1, Regex("\\.discard\\(\\)").findAll(src).count())
    }

    // ------------------------------------------------------------------ the SavedNote message

    @Test fun `Saved before the words, Saved with the words after, cut to the same length as before`() {
        val n = SavedNote.recorded("a", isPrivate = false, now = 1_000)
        assertEquals("Saved", n.message())
        assertTrue(n.watching)
        assertNull(n.words)
        val after = n.arrived(WordsLater.Update.Words("I'm putting my reading glasses in the nightstand drawer"))
        assertEquals("Saved: I'm putting my reading glasses in the nightstand drawer", after.message())
        assertFalse(after.watching) // shown once
        val long = n.arrived(WordsLater.Update.Words("x".repeat(200)))
        assertEquals("Saved: " + "x".repeat(SavedNote.MESSAGE_CHARS) + "...", long.message())
        // The words do not hold the screen open: the Undo and idle times are untouched.
        assertEquals(n.undoUntil, after.undoUntil)
        assertEquals(n.idleAt, after.idleAt)
        assertEquals(6_000, n.undoUntil)
        assertEquals(11_000, n.idleAt)
    }

    @Test fun `a Private note never shows its words, however they arrive`() {
        // Private pressed while recording: saved Private.
        val pressed = SavedNote.recorded("a", isPrivate = true, now = 0)
        assertEquals("Saved", pressed.message())
        assertFalse(pressed.watching) // the screen does not even look
        val forced = pressed.arrived(WordsLater.Update.Words("the gate code is 4471"))
        assertNull(forced.words)
        assertEquals("Saved", forced.message())
        // Private pressed on the ready screen, before the words came.
        val later = SavedNote.recorded("a", false, 0).markedPrivate(1_000)
        assertFalse(later.watching)
        assertEquals("Saved", later.arrived(WordsLater.Update.Words("the gate code is 4471")).message())
        // The words themselves said "private note": the capture is Private when they arrive.
        val said = SavedNote.recorded("a", false, 0).arrived(WordsLater.Update.Private)
        assertTrue(said.isPrivate)
        assertNull(said.words)
        assertEquals("Saved", said.message())
        assertFalse(said.watching)
    }

    @Test fun `what the watch finds on the saved capture`() {
        assertEquals(WordsLater.Update.Waiting, WordsLater.of(found = true, sensitive = false, state = TranscribeState.PENDING, text = ""))
        assertEquals(WordsLater.Update.Waiting, WordsLater.of(true, false, TranscribeState.RETRY, ""))
        assertEquals(WordsLater.Update.Words("milk"), WordsLater.of(true, false, TranscribeState.DONE, "milk"))
        assertEquals(WordsLater.Update.NoWords, WordsLater.of(true, false, TranscribeState.DONE, " "))
        assertEquals(WordsLater.Update.GaveUp, WordsLater.of(true, false, TranscribeState.GAVE_UP, ""))
        assertEquals(WordsLater.Update.Gone, WordsLater.of(false, false, null, ""))
        // Private wins over everything: no state of a Private capture hands its words over.
        for (state in TranscribeState.entries + null) {
            assertEquals(WordsLater.Update.Private, WordsLater.of(true, sensitive = true, state = state, text = "the gate code is 4471"))
        }
        val n = SavedNote.recorded("a", false, 0)
        assertEquals(n, n.arrived(WordsLater.Update.Waiting))
        for (end in listOf(WordsLater.Update.NoWords, WordsLater.Update.GaveUp, WordsLater.Update.Gone)) {
            assertEquals("Saved", n.arrived(end).message())
            assertFalse(n.arrived(end).watching)
        }
        assertTrue(WordsLater.GAVE_UP_LINE.startsWith("Saved. I couldn't write this one out"))
        assertEquals("Stash now keeps a recording of each voice note. You can turn that off in Settings.", WordsLater.FIRST_TIME_LINE)
    }

    @Test fun `a quick note's message is as it was`() {
        assertEquals("Saved: milk", SavedNote.of("a", "milk", false, 0).message())
        assertFalse(SavedNote.of("a", "milk", false, 0).watching)
        assertEquals("Saved", SavedNote.of("a", "my pin is 1234", true, 0).message())
    }

    // ------------------------------------------------------------------ the Notes list row (item 13)

    @Test fun `a recorded note with no words is never a blank row`() {
        val t = TranscribeNotice
        assertEquals("Voice note, 0:12 · Writing it out…", t.voiceNoteRow(TranscribeState.PENDING, false, modelReady = true, durationMs = 12_400))
        assertEquals("Voice note, 0:12 · Not written out yet", t.voiceNoteRow(TranscribeState.RETRY, false, true, 12_000))
        assertEquals("Voice note, 0:12 · I couldn't write this one out", t.voiceNoteRow(TranscribeState.GAVE_UP, false, true, 12_999))
        assertEquals("Voice note, 0:12 · No words heard", t.voiceNoteRow(TranscribeState.DONE, false, true, 12_000))
        // The model was removed after the note was recorded: say what it is waiting for.
        for (s in listOf(TranscribeState.PENDING, TranscribeState.RETRY)) {
            assertEquals("Voice note, 3:00 · Not written out yet: needs the speech model (Settings, Voice notes)", t.voiceNoteRow(s, false, false, 180_000))
        }
        // The length is not known for a moment: still not blank.
        assertEquals("Voice note · Writing it out…", t.voiceNoteRow(TranscribeState.PENDING, false, true, null))
        // A note with words, or with no recording, has no such line.
        for (s in TranscribeState.entries) assertNull(t.voiceNoteRow(s, hasWords = true, modelReady = true, durationMs = 5_000))
        assertNull(t.voiceNoteRow(null, false, true, null))
        // Every state of a wordless recorded note has a line.
        for (s in TranscribeState.entries) for (ready in listOf(false, true)) assertTrue(t.voiceNoteRow(s, false, ready, 1_000)!!.startsWith("Voice note, 0:01"))
    }

    @Test fun `the clock reads minutes and seconds`() {
        assertEquals("0:00", TranscribeNotice.clock(0))
        assertEquals("0:07", TranscribeNotice.clock(7_900))
        assertEquals("1:05", TranscribeNotice.clock(65_000))
        assertEquals("3:00", TranscribeNotice.clock(180_000))
        assertEquals("0:00", TranscribeNotice.clock(-5))
    }
}
