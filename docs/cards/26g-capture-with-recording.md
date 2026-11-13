# 26g · The capture screen records: the switch-over, the quick-note path, and the one switch

Read [00-READ-FIRST.md](00-READ-FIRST.md) first. Part of the card 26 split ([index](26-voice-notes-with-audio.md), D77, D82). Where 00-READ-FIRST says audio is never stored, this card wins.

**Status: built 2026-11-13, JVM only** (953 unit tests pass in both build configurations, with and without the engine AAR, one skipped in each; 51 new; no schema change, no new dependency). **Nothing was installed on the phone and no adb command was sent: every "Real phone" check below is still to do.** Route R1 (check C2 passed): `LOCKED_MIC_DEFAULT` is `OK`. See "What was built, and what differs" at the end, and `docs/EXPERIMENT_LOG.md`, "Card 26g", for the owner's steps.

## Goal

"Add a note" records his voice, saves the recording at once, plays the save tone, and lets him go. The words follow a moment later. If recording is switched off, has no model, or cannot get the microphone on a locked phone, the same screen takes the note the way it does today, through the phone's own recogniser, with nothing recorded, and says which it did.

## Depends on

26a, 26b, 26c, 26d, 26e, 26f. **Wait for the three open checks** (C1 in 26f, C2 in 26a, C3 in 26b) to be written in the experiment log before building: this is the card that changes how the owner's real notes are captured every day.

## Context

Read first: `capture/CaptureActivity.kt` (all of it: phases, timers, `SavedNote`, Undo, Private, Open Stash, the idle close), `capture/SavedNote.kt`, `capture/DoneChoice`, `capture/OpenStashChoice.kt`, `capture/Cues.kt`, `speech/SpeechEngine.kt`, `domain/Capability.kt`, `data/Capabilities.kt`, and the three check results.

What changes for him, said plainly so it can be put in the hand-back:

- **No words appear while he speaks.** Our engine works on a finished recording. He sees a level meter and a clock. The words appear after the save, on the same screen if he is still looking.
- The note ends when he taps **Done**, after a pause (2.5 seconds by default, 26a), or at three minutes. A pause no longer loses what came after it.
- The save tone means the **recording** is on disk (D6 still holds: the tone is "it took", not "it was understood").

The existing recogniser path is **kept whole**. It becomes the "quick note, no recording" route.

## Scope

### A. The one switch

1. Add `Capability.VOICE_RECORDING`: plain name "Keep a recording of each voice note", permission `RECORD_AUDIO`, **default on**, without-it text: "Voice notes still work. The phone writes down your words as you speak and no sound is kept. You can't listen again or have a note written out again." This switch **is** the hybrid setting: on means record and write out with Stash's own model; off means the quick note. There is no second setting for the same thing. Its explanation card is in `docs/EXPLANATION_CARDS.md` ("Keep the recording"); card 26k puts it on screen when card 23 exists.
2. Why default on: it is what the owner decided (D77); the engine is as accurate as the phone's recogniser on his voice (within noise); it writes out a whole note where the recogniser stops at a pause; and a wrong word can be heard and fixed. What it costs is said on the card: no live words, about 170 KB a minute, the words a moment later. Until a speech model pack is added (26d) the route is the quick note whatever the switch says, so **installing this card changes nothing by itself**.

### B. Choosing the route (`capture/VoiceRoute.kt`, pure)

3. `VoiceRoute.choose(recordingOn, engine: Availability, locked, lockedMic: LockedMic): Route`, where `Route` is `RECORD`, `QUICK(reason)` or `ASK_UNLOCK`:

   | Condition, first match wins | Route | What the screen says |
   | --- | --- | --- |
   | `VOICE_RECORDING` off | `QUICK(OFF)` | nothing extra |
   | engine not `READY` | `QUICK(NO_MODEL)` | "No recording: the speech model isn't added yet." |
   | locked, and `lockedMic` is `BLOCKED` | `QUICK(LOCKED)`, or `ASK_UNLOCK` if he chose that (item 4) | "Phone is locked: written down as you speak, no recording." with a button **Unlock to keep a recording** |
   | otherwise | `RECORD` | |

4. `lockedMic` comes from check C2 and from experience:
   - **R1 (C2 worked):** the constant `LOCKED_MIC_DEFAULT` is `OK`. Recording is used on the lock screen like anywhere else.
   - **R2 (C2 blocked):** the constant is `BLOCKED`. The default behaviour is the quick note while locked (adding a note while locked must never be refused: rule 5). A setting "When the phone is locked" offers the alternative: **"Take a quick note without a recording"** (default) or **"Ask me to unlock first"** (`ASK_UNLOCK`: the screen says "Unlock to add a voice note with a recording" with **Unlock** and **Quick note instead**; Unlock uses the keyguard dismissal `openStash()` already uses, then records).
   - **Either way, at run time:** if `MicHealth` (26a) reports `BLOCKED` or the recorder fails to start, the screen stops the recorder, switches to the quick route on the spot ("Say that again, please."), and, if the phone was locked, remembers `lockedMic = BLOCKED` in preferences so the next locked note goes straight to the quick route. Settings has "Try recording on the lock screen again" to clear it.

### C. The recording flow in `CaptureActivity`

5. Keep the screen's structure, its `showWhenLocked` behaviour, Undo, Private, Open Stash and the idle close. Put the new flow in a small state holder, `capture/RecordingSession` (pure state machine with the recorder, encoder and saver passed in, so it is unit-tested with fakes): `RECORDING → FINISHING → SAVED | EMPTY | SAVE_FAILED | SWITCHED_TO_QUICK`.
6. Start: the start cue, the existing 300 ms wait so the beep is not recorded, then `MicRecorder.start` feeding both the `Endpointer` and the `StreamingEncoder`.
7. While recording the screen shows: "Recording…", the level meter, the elapsed time, "Take your time." while nothing has been heard, and from 15 seconds before the limit "Stops at 3:00". If Private was pressed: the existing Private label. Buttons, 56 dp or more:
   - **Done**: stop and save. If nothing was heard and the recording is `EMPTY` (26a), behave as today's Done with nothing heard ("No note made", close).
   - **Cancel**: stop, throw the sound away, close. Nothing was on disk, so nothing needs deleting. **Cancel is the only thing that discards a recording.**
   - **Private**: as today; the note is saved Private from the start.
   - **Open Stash**: as today (save if something was heard, then open after the unlock).
8. Anything else that ends the recording **saves it**: the pause rule, the three-minute limit (the normal save cue, and the line "Stopped at 3:00. Saved."), the back gesture, Home, the screen turning off, a phone call (`onStop` while recording). If the screen is no longer visible the tone may not sound (Android 17 drops background sound); the note is saved regardless.
9. **The save, in this order and nothing else before the tone:** `recorder.stop()`; `RecordingVerdict` (an `EMPTY` recording is not saved: "I didn't catch that.", no error tone, as today); `encoder.finish()` (26b: at most 1.5 seconds, then the `WAV` fallback); `RecordingSaver.save(...)` on the IO dispatcher; on `Saved`: save cue, `READY`, Undo for 5 seconds as today. **Nothing about transcription is awaited.** Then `TranscriptionScheduler.requestNow()` (it is already called by the sink; calling it here too is harmless).
10. On `Failed`: keep the encoded bytes in memory, show "I couldn't save that note." with **Save again** (the same bytes, not a new recording) and **Close** (asks "Throw this recording away?" first). The failure cue plays.
11. **The words, afterwards.** While the screen is in `READY`, watch the saved capture. When its words arrive and it is not Private, show them once in the existing drifting grey line ("Saved: …", same length limit). A Private note shows only "Saved", as today. If the note comes back as `GAVE_UP`, show "Saved. I couldn't write this one out; you can listen to it in Stash." If nothing arrives before the screen closes, nothing more is shown here.
12. The quick route is today's code path, unchanged, entered through `VoiceRoute`. Update the comment on `SpeechEngine` ("audio is never stored") to say which route stores what.
13. Notes list (`ui/MainActivity.kt`), the minimum so that no note is ever a blank row: a recorded note with no words shows one of "Voice note, 0:12 · Writing it out…", "Voice note, 0:12 · Not written out yet", "Voice note, 0:12 · I couldn't write this one out", "Voice note, 0:12 · No words heard". Listening and fixing are card 26h.
14. A one-time line on the `READY` screen after the first recorded note: "Stash now keeps a recording of each voice note. You can turn that off in Settings." (When card 23 exists its first-use explanation card replaces this.)

## Out of scope

Playing a recording, fixing words, writing out again (26h). The Share screen's microphone (card 16c) and the Ask tab's "Speak" button: both stay on the phone's recogniser and keep no sound; a question is never recorded. Showing words while he speaks. Recording through a headset. A widget or new trigger.

## Acceptance checks

**Unit tests (JVM)**

- `VoiceRoute.choose`: the full truth table in item 3, both values of the lock setting.
- `RecordingSession` with fakes: Done saves once and plays the save cue after the sink returned `Saved`, never before; Cancel saves nothing and calls `abort` on the encoder; the pause rule, the limit and `onStop` each save; an `EMPTY` recording saves nothing; a slow encoder ends in a `WAV` save; a `Failed` save keeps the bytes and "Save again" sends the same bytes; `MicHealth BLOCKED` switches to quick and records the lock fact only when locked.
- Private pressed while recording: the capture is inserted `sensitive`, and the words that arrive later are never handed to the screen.
- The `SavedNote` message: "Saved" before the words, "Saved: …" after, never the words of a Private note.
- With `VOICE_RECORDING` off, or no model, the capture flow is the existing one (the fake `SpeechEngine` is started and the recorder is not).
- Card 13's "every capability off" test still passes.

**Real phone (S25 Edge); experiment log, "Card 26"**

- Unlocked: "I'm putting my reading glasses in the nightstand drawer". Time from the end of speech to the tone (2.5 s pause plus the save; record the save part: bar under 500 ms). The words appear on the screen; the note is in Stash with a record; `peek.py events` shows `AUDIO/STORED` before `TRANSCRIBE/STARTED`.
- Locked, from the notification and from the tile: whichever of R1 or R2 the log says, the screen does what item 4 says for it, and a note is saved every time.
- A two-minute ramble with pauses: saved whole, written out whole.
- Aeroplane mode: all of the above works.
- "Private note the gate code is 4 4 7 1": the words are not drawn on the capture screen; in Stash the note is Private.
- Press Home in the middle of a sentence; let the screen time out in the middle of another: both are saved up to that point.
- Tap Cancel after five seconds of speech: no note, and `adb shell run-as app.stash ls files/att` has no new file.
- Turn the switch off: a note is taken the old way and has no recording. Remove the model pack with the switch on: the same, with the "speech model isn't added" line.
- Undo within 5 seconds: the note leaves the list (its recording goes with it after 30 days, card 16a).
- Ask the owner after a day: does not seeing the words while speaking bother him? Write his words down.

## Notes and risks

- The biggest change in feel is the missing live words. If the owner dislikes it, the switch gives him the old behaviour at once; a streaming engine (Moonshine's streaming models, MIT) would be a new card and a new measurement.
- With the pause rule and a noisy room the note may run to the limit. The clock and Done are always on screen.
- Do not hold the screen open waiting for the words. The idle close stays as it is.
- A note saved while the phone is locked shows its words on the capture screen when they arrive. That is allowed (the adding screen shows only the thing being added now), exactly as "Saved: …" is today.

**Suggested builder: Opus.** It rewires the screen through which every note enters; any mistake in the save order or the fallbacks loses a note.

## What was built, and what differs from the card (2026-11-13)

Built (under `app/src/main/java/app/stash/` unless noted):
- `domain/Capability.kt`: `VOICE_RECORDING` ("Keep a recording of each voice note", `RECORD_AUDIO`, default on, the card's without-it sentence). It appears in Settings, "What Stash may use", like every other switch.
- `capture/VoiceRoute.kt` (pure): `LockedMic`, `LockedChoice` (the setting "When the phone is locked"), `QuickReason` (each with the line the screen says), `Route` (`Record`, `Quick(reason)`, `AskUnlock`), `VoiceRoute.choose` (item 3's table), `LOCKED_MIC_DEFAULT = OK`, `lockedMic(rememberedBlocked)`, and `VoiceStarter` (starts the speech engine for a quick note and the recorder for a recorded one; the screen starts every note through it).
- `capture/RecordingSession.kt` (plain Kotlin): the state machine of item 5. It wraps 26a's `audio.RecordingSession` (level rule, blocked-microphone check, loudest sample, events) and feeds every chunk to 26b's `StreamingEncoder` as well. `prepare()` (start the codec while the cue plays), `start()`, `finish(reason)` (the save, in item 9's order), `cancel()`, `markPrivate()`, `saveAgain()`, `discard()`. The recorder, encoder, save function and a launcher are passed in.
- `capture/SavedNote.kt`: `SavedNote.recorded(...)`, `watching`, `arrived(update)`; `WordsLater` (what the watch found on the saved capture: waiting, words, no words, gave up, Private, gone) and the two lines of items 11 and 14.
- `capture/CaptureActivity.kt`: phases `STARTING`, `ASK_UNLOCK`, `RECORDING` and `SAVE_FAILED` beside the old ones. The quick route's code (`listener`, `armTimers`, `save`, the Done and Open Stash choices) is untouched and is entered through `VoiceRoute`. Leaving the screen saves; Cancel discards; a failed save offers "Save again" and "Close".
- `data/Container.kt`: `Settings.lockedMicBlocked`, `lockedChoice`, `recordingNoticeShown` (preferences); `Container.captureScope` (the application-lifetime scope the stop-and-save runs in, so it outlives the screen).
- `domain/Endpointer.kt`: a sound counts as speech only after 60 ms in a row (see "The pause rule and noise" below). `audio/Recorder.kt`: two read-only accessors (`heardSpeech`, `elapsedMs`) on 26a's session.
- `pipeline/TranscriptionPolicy.kt`: `TranscribeNotice.voiceNoteRow` and `clock` (item 13's four lines). `ui/MainActivity.kt` draws it; `ui/MainViewModel.kt` reads the recording's length.
- `ui/SpeechModelSettings.kt` (Settings, "Voice notes"): "End a voice note after a pause of" (2.5 seconds, 5 seconds, Only when I tap Done). 26a made the preference; it had no row until now. And, only while the locked microphone is remembered as blocked: a line saying so, **Try recording on the lock screen again**, and "When the phone is locked" with its two choices.
- `speech/SpeechEngine.kt`: the comment now says which route stores what (item 12).
- Events: `CAPTURE STARTED` with n1 = the route (0 recorded; 1 switch off, 2 no model, 3 locked, 4 recorder failed; 9 asked to unlock) and n2 = 1 if locked. Numbers only.
- Tests: `RecordingSessionTest` (21, plain JVM), `VoiceRouteTest` (19, plain JVM), `RecordedNoteFlowTest` (6, Robolectric: the real saver, sink, Room and encrypted files), and 5 new in `RecorderLogicTest`. Every line of "Unit tests (JVM)" has a test; card 13's "every capability off" test passes unchanged.

**The pause rule and noise (check C2's finding: keys tapped nearby kept a note open for 20.9 s).**
- What was found in the code: a chunk is "speech" when it is 3 times the noise floor and above an absolute level (0.006). The floor only ever falls, and the recorder's first chunks are near silence, so on the phone the floor sits at its minimum and the absolute level decides. All 15 corpus recordings show a floor of exactly the minimum. A key tap is far above that level, and each tap started the pause again.
- **Changed (small):** a sound must last three chunks in a row (60 ms) before it counts as speech or ends a pause; then the whole run counts. A tap or click is one or two chunks. While a sound is still too short to judge, the pause neither grows nor fires, so a word that begins just before the 2.5 s mark is not cut off.
- **Checked on real speech, on the PC:** the level rule was run over the 15 corpus recordings with and without the change (numbers only). The longest gap inside speech moves from 1.58 s to 1.78 s at worst (the 27.7 s ramble); every other file moves by 0.12 s or less. One file's last "speech" was a single loud chunk 0.8 s after the words (most likely the tap on Stop); the new rule no longer counts it.
- **Not changed, and why:** a floor that rises with the room. It would help with steady noise that starts after he begins (a tap running, a fan), which today runs a note to Done or to three minutes, as the card's Notes and risks accept. But a rising floor measured over a window would also rise during a long stretch of unbroken talking, and then call the talking a pause: it could end a note while he is still speaking. That is not small and not safe without recordings of noisy rooms to test against, so it is left for a measured change.
- **Not verified:** how long a real key tap rings on the S25's microphone. If taps there last longer than 40 ms above the level, they still count. The phone check is in the experiment log.

What differs from the card, and why:
- **"…; you can listen to it in Stash."** is not in the line for a note that could not be written out. Listening is card 26h and does not exist yet, so the line says "Saved. I couldn't write this one out. Its recording is kept." Card 26h should put the card's wording back (`WordsLater.GAVE_UP_LINE`).
- **"Recording..."** with three dots, like the screen's "Listening..." and "Saving...", not the single character "…".
- **"Stopped at 3:00. Saved.", the one-time line and the could-not-write line are steady small lines** on the ready screen, not part of the drifting grey line, which fades in under two seconds.
- **An empty recording is 26a's `EMPTY` verdict, everywhere** (Done, Open Stash, the pause rule, leaving the screen): the loudest sample under 40 of 32767 and no speech seen. A real room is louder than that, so **Done or Open Stash pressed without speaking will usually save a short voice note** ("Voice note, 0:02 · No words heard") instead of "No note made", and 25 seconds of nobody speaking saves a 25-second one. This is the card's rule (item 7; 26a: "when in doubt, keep it"; only Cancel discards) and Undo is on screen for five seconds, but it is a change from today. `EMPTY_PEAK` is still 26a's provisional 40; the silent-room measurement in 26a's phone steps would let it be raised.
- **Leaving the screen saves after a wait of 0.8 s**, called off if the screen is back by then (`LEFT_SCREEN_GRACE_MS`). A screen opened over the lock screen may be stopped and started again as the display wakes; without the wait that would end a note in its first second. This is a precaution, not something seen: the phone check says whether it is needed. The back gesture and the screen being destroyed save at once.
- **A microphone that stops part-way** (a read error, for instance when a call takes it) saves what was recorded so far, like everything else that ends a note. The card names only the blocked microphone and a failed start.
- **A failed save is tried once more by itself if he leaves the screen** on "I couldn't save that note." The bytes exist only in memory, so this is their last chance.
- **"Throw this recording away?" is asked on the screen itself** (with **Keep it** and **Throw it away**), not in a dialog window: the screen may be over the lock screen.
- **The codec is started while the start cue plays** (`prepare()`), so the recorder opens as soon as the 300 ms wait is over. 26b asked for the codec to be started off the main thread; it is.
- **The setting "When the phone is locked" is shown only once this phone has shown the locked microphone to be blocked.** Under R1 it would otherwise be a setting that does nothing.
- **`Private` pressed on a locked quick note is kept** if he then taps "Unlock to keep a recording".
- **The Notes list line** adds one case to the card's four: "Voice note, 0:12 · Not written out yet: needs the speech model (Settings, Voice notes)" when the model was removed after the note was recorded (rule 6: what a missing model prevents is said on screen). 26f's older sentences (`TranscribeNotice.line`) are kept for its tests and are no longer drawn.
- **"Recording quality" has no Settings row.** The stored format follows the preference 26b made (default Small, Opus 12 kbps, as check C3 chose); the card asks for no row.
- **Two copies of the sound are held while recording** (the recorder's and the encoder's own, about 11.5 MB at three minutes, plus one more for an instant at Stop). 26b noted that 26g could drop one; it was left, because the recorder's copy is what saves the note as WAV if the encoder cannot be started.
- **Not done here** (other cards' text, listed in the index): the lines in `00-READ-FIRST.md`, `13-capture-pipeline.md`, `PROVIDERS.md` and `DESIGN.md` that the index says to change. `README.md`'s "Audio is never saved" line was changed.

Unverified, needs the owner (nothing was installed and no adb command was sent): every "Real phone" check of the card. The steps are in `docs/EXPERIMENT_LOG.md`, "Card 26g". D83 also asks for the app's idle memory with all libraries to be measured before recording is the default; that has not been done.
