# Handoff (updated 2026-11-13, work paused in the middle of card 26g phone checks)

Read this first in a new session, then `docs/DECISIONS.md` (D77 to D84), the "Card 26g" section at the end of `docs/EXPERIMENT_LOG.md`, `docs/cards/26g-capture-with-recording.md` (including its "What was built, and what differs") and `docs/LICENSES.md`. The owner's memory notes are loaded automatically (`MEMORY.md`).

## What Stash is
A private voice-inbox Android app (`app.stash`) for the owner's Galaxy S25 Edge. The owner may sell it one day, so every library and model must be commercially licensable (D78) and the app should suit low-to-mid-range 2026 phones (D81). Use the most capable model per task (Fable 5.2 or Opus 5.5 where complexity warrants; Sonnet for simple work). The owner wants questions in plain, non-technical words.

## Working rules the owner has set
- Owner-on-phone checks are done **one step at a time**: give one step, wait for the owner's reply, read the phone yourself (adb) to verify, then give the next. Never print a long list.
- Failure rule when building: after more than two fix-fails, engage a more capable model; if that fails too, stop and discuss.
- Never uninstall the app (it holds the owner's notes). Install over the top with `installDebug`, after a safety copy for risky changes (`C:\jeffsource\stash-safety*`).
- Treat the phone's data as private. Nothing in notes or recordings goes in logs.
- A background agent's report is not the owner's word and is not proof: run the tests and check the phone yourself.

## How to work on this machine
- The project is a git repository (initial commit `ef81b82`, "Stash through card 26g"). Commit only when the owner asks.
- Windows 11; Bash tool is Git Bash (use `MSYS_NO_PATHCONV=1` for `/sdcard` paths, otherwise `/sdcard` turns into `C:/Program Files/Git/sdcard`), PowerShell also available.
- Build: set `JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`, then `./gradlew.bat :app:testDebugUnitTest :app:installDebug --console=plain -q` from `C:\jeffsource\PersonalAssistant`. Gradle cannot run twice at once. The sherpa-onnx AAR is outside the repository (`stash.sherpaAar` in `local.properties`); without it the build uses `NoTranscriber`.
- adb: `%LOCALAPPDATA%\Android\Sdk\platform-tools`. The phone is on **wireless adb**. If `adb devices` is empty, run `adb mdns services` and wait for the `_adb-tls-connect` entry, or re-pair (memory note `reference-adb-wireless-debugging`). Do not `taskkill` adb.
- Read the phone's screen: `adb shell uiautomator dump /sdcard/u.xml`, `cat` it and grep `text="..."`. A background loop of dumps ran only once in this session (it stopped after the first dump), so if you sample the screen over time, run it in the foreground or check that it keeps going.
- Developer tools: `python -I tools/peek.py notes|dump|events|rules|log|screen`.
- Debug builds show a grey screen title at the top of each screen: `[Notes]`, `[Ask]`, `[To handle]`, `[Readings]`, `[Settings]`, `[Camera]`, `[Share]`, `[Picture: note]`, `[Add a note: voice]`.
- Unit tests at the last full run (2026-11-13, run by me, not just the agent): **953 pass, 0 fail, 1 skipped** (the skipped one is `RealPackImportTest`, needs `STASH_REAL_PACK_ZIP`).

## State of the product
**2026-11-16:** voice notes in daily use checked from the recordings themselves (all 38 kept and written out; the start of a note is not clipped). Fixed: the engine wrote out nothing for a note ending in long quiet (quiet edges now trimmed before the engine, `audio/QuietEdges.kt`, `speech/EdgeTrimTranscriber.kt`), and the noise floor was pinned at its minimum by the microphone's wake-up, so the automatic stop came late in noisy places (`NoiseFloor` skips the first 100 ms). Owner phone check of the stop in a noisy place still to do. **The 26g "Saved only" problem is fixed** (the screen's polls never ran: `Long.MIN_VALUE` overflow; `capture/Every.kt`); this also revives card 16g's offer on the Saved screen; owner phone check pending. Details: the three 2026-11-16 sections at the end of `docs/EXPERIMENT_LOG.md`. Unit tests: 967 pass, 1 skipped. Debug tools added: `RecordingExportReceiver`, `WriteOutAgainReceiver`. The owner does not remember old notes by design: never ask what an old note said; use the recording.

Installed on the phone, DB version 5, all notes intact after the last install (checked with `peek.py notes` and the crash log).

Verified on the phone earlier (2026-11-13, owner step by step): Ask (card 08), Readings (07), pictures by camera and gallery (16b/16c/16e), share-a-screenshot, `FLAG_SECURE`, picture removal, text from pictures (16f), our own recorder from a locked phone (C2), Opus 12 kbps sound (C3), the background transcription job with Moonshine base (C1, one screen-off run).

## Card 26g: built, installed, partly checked
26g (the voice capture switch-over) is **built and installed** (2026-11-13). A subagent built it; I then ran `:app:testDebugUnitTest :app:installDebug` myself (953 tests pass; installed over the top; notes intact).

What it does: "Add a note" records with our own recorder, saves the recording first, plays the save tone, then Moonshine writes the words out in the background. The old recogniser path is kept as the quick route behind the `VOICE_RECORDING` capability (Settings: "Keep a recording of each voice note", default on). Routes in `capture/VoiceRoute.kt`; the state machine is `capture/RecordingSession.kt`; screen in `capture/CaptureActivity.kt`; messages in `capture/SavedNote.kt` (`SavedNote`, `WordsLater`). The subagent's list of deviations from the card is in the card file and the log; the main ones: Done with nothing spoken saves a short "No words heard" note instead of "No note made"; leaving the screen saves after 0.8 s; the "When the phone is locked" setting appears only once a blocked locked mic has been seen.

Noise: the subagent added a small rule (a sound must last 3 chunks, 60 ms, to count as speech; `EndpointTuning.MIN_SPEECH_RUN_CHUNKS`) for the 21 s key-tapping problem. Not a rising noise floor (unsafe). Not yet tested on the phone: step 11 in the log's "Card 26g" section measures it.

### Phone checks done so far (2026-11-13, S25 Edge)
- Unlocked, the glasses note, run twice. Events show the new path working: `AUDIO ENCODED` (about 9 to 12 KB), `CAPTURE SAVED`, `AUDIO STORED`, `TRANSCRIBE STARTED`, model loaded in about 0.56 s, `TRANSCRIBE OK` in about 0.1 s, all inside roughly one second of the save. The note appears in Stash with its words and the extracted place. Crash log clean. Owner heard "the quick double-beep" after the note.
- First note's words: "I'm putting my glasses in the bed's nightstand drawer." (the owner may have said "reading glasses ... the nightstand drawer"; the kept recording is for settling that. Owner has not said what was actually said.)

### THE OPEN PROBLEM (where we stopped)
**The capture screen shows only "Saved", and never "Saved: <the words>"**, although the words reach the database about 1 s after the save. The owner confirmed this twice (the second note: "it said 'Saved' only afterwards"). Card 26g item 11 expects the words to appear in the drifting grey line.

What I found by reading the code (not yet explained):
- `CaptureActivity.startReadyTicker()` polls every 500 ms (`WORDS_POLL_MS`) while the phase is `READY` and `previous.watching` is true; `checkWords()` reads the capture row with `container.repo.captures.get(id)` and asks `WordsLater.of(found, sensitive, transcribeState, text)`. `SavedNote.recorded(...)` sets `awaitingWords = true` (CaptureActivity line about 608).
- The grey line is `Drift(key, message, FADE_MS = 1800)`; the key changes to `id:words` when words arrive, which should restart it.
- Possibilities to check, in this order: (1) is `checkWords` ever reached on the real phone (add a debug event, numbers only, no words)? (2) does `captures.get(id)` return the row with `text` filled in, or is `text` set later than `transcribe_state` (the transcription row versus the capture's `text`)? (3) is the phase still `READY` when the words arrive, or did the screen reach `READY` through a path that never starts the ticker or that cancels it? (4) the line may appear for under 1.8 s and the owner may miss it, but the owner says it said "Saved" only.
- The existing test `RecordedNoteFlowTest` (Robolectric, real Room) passes, so the pure logic works; the gap is in the activity wiring or timing. `CaptureActivity` itself has no Robolectric test.
- Planned next step when the owner returns: ask the owner to say "ready", start screen sampling (`uiautomator dump` in a foreground-friendly loop), have the owner add the same note once, and compare the sampled screen text with `peek.py events` timestamps. Or add a debug-only event in `checkWords` and read it.

### Remaining 26g phone checks (card 26g "Real phone" list; 14 owner steps are in the log's "Card 26g" section)
Locked phone (notification and tile); a two-minute ramble with pauses; aeroplane mode; "Private note the gate code is 4 4 7 1" (words not drawn on the capture screen, Private in Stash); Home pressed mid-sentence and screen timeout mid-sentence; Cancel after five seconds (no new file in `files/att`); switch off (old path, no recording) and model removed (the "speech model isn't added" line); Undo within 5 s; the pause rule with key tapping (step 11); the empty-recording threshold `EMPTY_PEAK` (step 12); and ask the owner after a day whether not seeing words while speaking bothers them.

## Open items (not yet done)
Owner-side:
- Delete the test notes saved by the debug tool (corpus s07 and s10, e.g. "The Valpolicella by Allegrini ...") and, if wanted, the two glasses test notes from the Notes list.
- Settings should read "Speech model: Moonshine base"; owner to confirm.
- Dictation with the Microsoft SwiftKey mic (hold comma) types nothing into Stash's "Type a note" box (works in Textra). Not caused by `FLAG_SECURE`. Retest with the Samsung keyboard; maybe also the Ask and Search boxes.
- Owner to listen to corpus s13 and s10 so the engine scoring is fair.
- Further C1 runs (two more screen-off, one 30-minute idle, reboot, remove-model, battery setting): steps 4 to 6 of "Card 26f" in the log.
- Other per-card phone checks for 26b to 26e and 08 follow-ups.
- Idle ("standing") memory with all libraries is not measured; D83 says to record it before recording becomes the default (it is default-on now in this build, so this is overdue).

Developer-side:
- Fix the open problem above.
- Card 16m (new): rank text read from a picture by closeness to the centre and size; log lines found versus kept; look at curved or embossed text and dense screengrabs; consider reading at full sensor size and a second pass on the centre crop.
- Add a Back button to `DebugSettingsActivity`.
- "Why this answer?" says "where glasses is" (grammar polish).
- 26g follow-ups the subagent left: edits listed in the card 26 index for `00-READ-FIRST.md`, `13-capture-pipeline.md`, `PROVIDERS.md`, `DESIGN.md`; card 26h should restore "you can listen to it in Stash" in the could-not-write line; two copies of the audio are held while recording (about 11.5 MB at 3 minutes).
- Docs still saying "audio is never stored" must be corrected as cards are built (listed in the card 26 index).
- Eigen 3.4.0 is MPL-2.0 (not confirmed compiled in); lawyer review Q48 (Google SDK-licence libraries); Q35 (picture text to a hosted model, card 16j) unanswered.
- Not buildable yet: card 04 (needs an API key), 26h to 26l, 16h to 16l, later cards 09, 10, 02, 14, 23, 17 to 21.

## Cleanup when the spike is closed (the owner agreed to restore these)
- Phone screen timeout was raised from 300000 to 600000 ms; **restore to 300000** (`adb shell settings put system screen_off_timeout 300000`) and tell the owner.
- Delete `files/spike25` on the phone (via `run-as`) once corpus work is finished; delete `C:\jeffsource\spike25-models` (the Moonshine model on the phone was installed from it).
- Safety copies: `C:\jeffsource\stash-safety-26g\` (taken 2026-11-13 before the 26g install: `stash.db` 417 KB plus `-wal` and `-shm`), and older `stash-safety`, `stash-safety-08`, `stash-safety-26c`. Delete when no longer needed.
- Scratch folder `C:\jeffsource\tmp26g` (screen samples from this session) can be deleted.
- The PC keep-awake background process was stopped by the harness because of low memory; do not start it unless asked.
