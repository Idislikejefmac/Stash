# Experiment log

What was tried on real phones, what was measured, and what worked. Newest entries at the bottom of each section. Keep adding to it: the point is to never re-try something that already failed. Each card's acceptance checks say what to record here. (The spike tables just below belong to the first design's archived cards; they are kept empty for when that work resumes.)

**Status key:** ✅ worked (seen on the phone) · 🧪 built and tested, not yet confirmed on the phone · ❌ tried and failed · ↩️ tried and reverted · ⏳ open

## The phones

Fill this in before the first spike; several design choices depend on it.

| Person | Phone | Android version | One UI / Moto version | Security patch | Default assistant today | Auto Blocker (Samsung) | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Owner | Galaxy S25 Edge | 17 | | | | | |
| Wife | Motorola razr ultra 2025 | | | | | n/a | |
| Parents | Galaxy Z Flip 7 | | | | | | |

## Triggers from a locked phone (spike 01)

| Status | Date | Phone | Trigger | Reached the capture screen without unlocking? | Microphone recorded real audio? | Start and save cues heard? | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- |

## Cover screens (spike 02)

| Status | Date | Phone | What | Result | Notes |
| --- | --- | --- | --- | --- | --- |

## Speech to text on the phone (spike 03)

| Status | Date | Phone | Engine and model | Sentences right / total | Names right ("Frank", "Costco", "frunk") | Seconds from end of speech to text | Size on disk | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |

## Language model: structuring and answering (spike 04)

| Status | Date | Model | Sentences structured correctly / total | Tokens in / out per call (median) | Cost per 100 notes | Notes |
| --- | --- | --- | --- | --- | --- | --- |

## Place reminders and battery management (spike 05)

| Status | Date | Phone | Setup (battery setting, location permission) | Arrivals notified / arrivals made | Delay after arriving | Notes |
| --- | --- | --- | --- | --- | --- | --- |

## Installing and updating (spike 06)

| Status | Date | Phone | Method | Result | Notes |
| --- | --- | --- | --- | --- | --- |

## Open questions

Add anything a spike raised that no card answers yet.

## Prototype build (2026-11-11, thin prototype, S25 Edge only)

Nothing below has been run on a phone yet: the builder had no device. Every row is 🧪 (built, unit-tested where pure logic, not confirmed on the phone). Owner: fill in the result column using the "Owner test script" in README.md.

| Status | Date | Phone | What | Result | Notes |
| --- | --- | --- | --- | --- | --- |
| 🧪 | 2026-11-11 | S25 Edge | Notification action "Add a note" opens CaptureActivity from the **locked** screen without unlocking | not yet run | Built with `Notification.Action.Builder.setAuthenticationRequired(false)`, activity has `showWhenLocked` and `turnScreenOn`. If it asks for the PIN, record that: the notification trigger then needs the user to unlock first. |
| 🧪 | 2026-11-11 | S25 Edge | Quick Settings tile "Add a note" from the locked shade | not yet run | Uses `startActivityAndCollapse(PendingIntent)`. A locked phone may refuse to run the tile or ask for unlock; record which. The tile must be added to the panel by hand. |
| 🧪 | 2026-11-11 | S25 Edge | Microphone gives real speech (not silence) when capture starts from the locked screen | not yet run | Android allows the microphone from a visible activity started by a user action; to be seen. |
| 🧪 | 2026-11-11 | S25 Edge | Start beep and saved tone audible (Android 17 background-audio hardening) | not yet run | Played with `ToneGenerator` on `STREAM_MUSIC` from the visible activity. Check with the phone on silent / vibrate too. |
| 🧪 | 2026-11-11 | S25 Edge | Which speech recognizer is used: `android-on-device` or `android-default`; works with airplane mode on? | not yet run | The engine name is stored only in code; to find out, try with airplane mode on. |
| 🧪 | 2026-11-11 | S25 Edge | Dictation of "Syd", "Costco", "frunk", "Frank", "Tesla"; "Sid" rewritten to "Syd" | not yet run | Rule Sid to Syd is seeded; the word list is passed as `EXTRA_BIASING_STRINGS`. |
| 🧪 | 2026-11-11 | S25 Edge | Does the end-of-speech silence setting (1.8 s) take effect | not yet run | Samsung and Google recognizers may ignore the extras. |
| 🧪 | 2026-11-11 | S25 Edge | Notification survives the user swiping it away / after reboot | not yet run | Android lets users dismiss ongoing notifications; the app re-posts it at boot, on app update and when the app is opened. |

## Revision 1 (2026-11-11)

Findings that fed the design revision. Nothing here was run by the designer on a phone; each row says where it came from.

| Status | Date | Phone | What | Result | Source and notes |
| --- | --- | --- | --- | --- | --- |
| ✅ | 2026-11-11 | S25 Edge | First real note, spoken: "I am updating the app for stash right now" | Transcribed correctly. Filed as `OTHER`. | Reported in `REVISION_1_BRIEF.md`; not observed by the designer. Which trigger was used, whether the phone was locked, and whether a key was set were not recorded. The `OTHER` result matches the code: `RulesStructurer` has no pattern for this sentence. This is the gap revision 1 addresses (new kind `EVENT`, model extraction). |
| ⏳ | 2026-11-11 | S25 Edge | The "Prototype build" rows above (locked notification, locked tile, cues, aeroplane-mode speech, end-of-speech setting) | Still not recorded | The prototype has been used on the phone, but none of the test-script results have been written here. `docs/PRIVACY.md`'s statement that speech stays on the phone depends on the aeroplane-mode row. |
| 🧪 | 2026-11-11 | — (code reading) | Save path order in the prototype | `Repository.saveNote` calls the hosted model before inserting the note; the HTTP timeout is 15 s | Read in `data/Container.kt` and `model/hosted/Anthropic.kt`. Means a slow network delays the save tone. Fixed by card 03. |
| 🧪 | 2026-11-11 | — (code reading) | Private notes in the prototype | `localOnly` exists as a column but nothing ever sets it; with a key set, every note's text and the word list are sent | Searched `app/src/main`. Fixed by card 03. |
| 🧪 | 2026-11-11 | — (code reading) | Network code location | The only file using `java.net` / `HttpURLConnection` is `model/hosted/Anthropic.kt` | Searched `app/src`. No test enforces it yet (card 03). |

### To record next (no code needed)

| What | How | Why it matters |
| --- | --- | --- |
| Owner test script steps 12–15 | README | Whether capture works locked; whether speech is on the phone |
| Pool sentence, ten times (RISKS.md R12) | Dictate "Pool test: free chlorine 5, pH 7.2, alkalinity 70, cyanuric acid 40" from a card with natural pauses; note cut-offs and wrong numbers | Decides how much the readings feature can rely on the recogniser |

## Language model: extraction and questions (new cards 04 and 08)

| Status | Date | Model and prompt version | Seed set: tasks found / false tasks / reading numbers exact / right record count | Tokens in / out per call (median) | Cost per 100 notes | Notes |
| --- | --- | --- | --- | --- | --- | --- |

## Revisions 2 and 3 (2026-11-11)

Findings from reading the prototype's code and build files. **Nothing here was run on a phone.** Facts from the web are not experiments and are in DESIGN.md, STORAGE.md and PROVIDERS.md with their confidence, not here.

| Status | Date | Phone | What | Result | Source and notes |
| --- | --- | --- | --- | --- | --- |
| 🧪 | 2026-11-11 | — (code reading) | How the app is signed | `app/build.gradle.kts` has no `signingConfigs` and no `buildTypes` block, so every build is signed with the building PC's automatic debug key | Means a build from another PC, or a release build with another key, cannot be installed over the app without an uninstall, which deletes the notes. `allowBackup` is false, so nothing else holds a copy. RISKS.md R21; fixed by card 01 item 0. |
| 🧪 | 2026-11-11 | — (code reading) | Logging in the prototype | No call to `android.util.Log`, `println` or `printStackTrace` anywhere under `app/src/main` | Searched. So no note text is in logcat today; `tools/peek.py log` shows only system lines about the app. Card 13 adds a test to keep it so. |
| 🧪 | 2026-11-11 | — (code reading) | Unused columns in the `note` table | `placeName` and `expiresAt` exist and nothing writes them (`Repository.saveNote` sets neither) | Card 01's migration drops them and its test documents the assumption. |
| 🧪 | 2026-11-11 | — (code reading) | Position stored with a note | `Repository.lastKnownLocation()` returns the newest *last known* fix from any provider; it does not ask for a fresh one | A note's coordinates can be stale by minutes and hundreds of metres. Matters for trips (RISKS.md R23). |
| 🧪 | 2026-11-11 | — (manifest reading) | Ways into the app | One exported activity (the launcher), the tile service, the boot receiver. No share target, no camera, no media or Bluetooth permission | Baseline for cards 16, 18, 21 and for card 02's manifest test. |
| ⏳ | 2026-11-11 | S25 Edge | Owner test script; ten dictations of the pool sentence | **Still not recorded** | Third revision running. They take twenty-five minutes together and three documents hedge on their results. |

### To record next, in this order (no app code needed)

| # | What | How | Time | Decides |
| --- | --- | --- | --- | --- |
| 1 | Copy the debug keystore somewhere safe | `C:\Users\<you>\.android\debug.keystore` | 2 min | Whether a PC problem can cost you the notes |
| 2 | Owner test script steps 12–15 | README | 10 min | Whether capture works locked; whether speech is on the phone |
| 3 | Pool sentence, ten times; then a price sentence five times ("the Tide is on sale for twenty four ninety nine") | RISKS.md R12 | 20 min | How far readings and prices can rely on the recogniser |
| 4 | Ten labels and five shelf tags through the phone's own text selection | RISKS.md R31 | 20 min | Whether "find the bottle by its label" is realistic |
| 5 | For one week: on each outing, did you make a note? | paper | — | Whether trips-from-notes will have anything to show (R23) |
| 6 | The desk spike, once there is a key | card 04 section 0 | 2 h | RISKS.md R11, the central one |

## SQLCipher spike (card 15)

| Status | Date | Library version | Loaded on the phone? | Counts match / FTS works | FTS4 · FTS5 · JSON1 in compile options | Cold open plain / encrypted (ms) | Search plain / encrypted (ms) | APK size before / after | 16 KB aligned? | Verdict |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |

## Providers: conformance runs (cards 04 and 22)

| Status | Date | Provider and model, prompt version | Tasks found / false tasks / reading numbers exact / right record count | Tokens in / out (median) | Cost per 100 notes | Notes |
| --- | --- | --- | --- | --- | --- | --- |

## Tier 0 day (RISKS.md R22)

| Status | Date | Notes made | Sorted by rules | "Not sorted" | Questions answered / list only | Owner's verdict |
| --- | --- | --- | --- | --- | --- | --- |

## Pictures and codes (cards 16 and 21)

| Status | Date | What | Result | Notes |
| --- | --- | --- | --- | --- |

## Departures (card 18, stage 2 week)

| Status | Date | Signal | Departures made / noticed | Seconds from car connect to reminder | False starts | Battery setting | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- |


## Card 01: data model and migration (2026-11-11)

| Status | Date | Where | What | Result | Notes |
| --- | --- | --- | --- | --- | --- |
| ✅ | 2026-11-11 | Developer PC (JVM, Robolectric 4.16.1, `sdk=36`) | Migration 1 to 2 on a real version-1 file built from the committed `1.json`, then opened through Room | 92 unit tests pass (28 new). Room's own schema check on open passed, so tables, indices and the FTS table match the entities | Robolectric has no API 37 yet; 36 is used. `MigrationTestHelper` is instrumented only and `connectedAndroidTest` uninstalls the app, so it was not used. |
| ✅ | 2026-11-11 | S25 Edge (R3CY40Y9N3W) | In-place `installDebug` over the prototype, then app opened | Database went from version 1 to 2. Before: 4 notes in `note` (the phone held 4, not the 2 in the earlier backup, because the owner had added 2 since). After: 4 captures, 4 records, 4 extractions; every text identical (compared `peek.py notes` before and after); all PENDING; 5 words and 1 correction unchanged | Same signing key, so no uninstall. Process alive, nothing in the crash log. |
| ✅ | 2026-11-11 | S25 Edge | Safety copy | `files/stash-v1-backup.db` exists after the first launch (40,960 bytes); pulled with `exec-out`, it is at `user_version` 1 and holds 4 notes | Made before Room opened the file. |
| ✅ | 2026-11-11 | S25 Edge | Debug dump | `python -I tools/peek.py dump 100` prints the same 4 captures with their records | Receiver exists only in `src/debug`. |
| ⏳ | 2026-11-11 | S25 Edge | Add a note by voice after the migration; ask "where did I put ..." about an old note; view the Notes list on screen | Not done | The screen was off and voice cannot be driven from the PC. The new save path is covered by JVM tests only. Owner: please do one capture and one question. |

Finding: under Robolectric the manifest's `StashApp` runs its own start-up (opens the real database file, seeds the word list) beside every test. `robolectric.properties` now sets `application=android.app.Application`; without it, tests were polluted by the seed words.

## 2026-11-11: pause while thinking, then a retry loop (owner, locked phone, notification shade)

- **Seen:** From the lock screen, the shade's "Add a note" opened the capture screen without unlocking, and a note saved normally. On another attempt the owner paused to think; it timed out ("I didn't catch that", 6 s), and every "Try again" then failed at once with "Speech recognition needs a connection" and a busy-tone buzz, until stopped.
- **Log evidence (a different, successful run):** the recogniser is Google's on-device engine (SodaSpeechRecognizer via AiAi) and it works offline. The failed run's log had already scrolled away, so the actual error code was not captured. `RemoteSpeechRecognitionService: Connection to speech recognition service lost` appears after the app destroys a recognizer.
- **Hypothesis (not proven):** the app destroyed the recognizer at the timeout and created a new one immediately; the service reported a lost connection, which the app showed as "needs a connection"; a late callback from the destroyed recognizer could also end the new session.
- **Change made:** a pure retry policy (`SpeechRetryPolicy`, unit-tested): lost/busy/network errors restart on a fresh recognizer after 700 ms, then on the default recognizer, then are reported with the error code; callbacks from an older recognizer are ignored; silence no longer fails (it quietly listens again for up to 25 s with "Take your time"), with no error tone; the engine logs error code and engine name (never speech) under the tag `StashSpeech`.
- **To verify on the phone (owner):** pause 10 s before speaking (should keep listening, no buzz); pause 30 s (should end with "I didn't catch that", no buzz); then Try again (should listen). If it ever loops again, `python tools/peek.py log 200` and look for `StashSpeech` lines.

## Card 03: extraction core (2026-11-11)

| Status | Date | Where | What | Result | Notes |
| --- | --- | --- | --- | --- | --- |
| ✅ | 2026-11-11 | Developer PC (JVM) | Unit tests for the extraction shape, sensitivity, number words, checks, date resolution, projection, subject resolution, the rules extractor and `ExtractionRunner` over a real in-memory Room database | 246 tests pass (154 new). The owner's real sentences (candle at Ross, finish adding features, updating the app, glasses, guitar loan on January fifth, ground beef at Costco, pool reading, vague "clean the car soon", Tide price) all come out as the card describes | The rules file the sentences as: candle = task (item "warm tobacco pipe candle", place Ross); "Finish adding..." = task; "I am updating the app..." = event, no task; pool = one reading, three values as spoken |
| ⏳ | 2026-11-11 | S25 Edge | Install over the app and confirm the notes are intact | Not run: the phone was not connected (`adb devices` empty) | Nothing in this card changes the schema (`2.json` unchanged) and nothing calls the new code from the app, so the installed build's behaviour is unchanged. Do `./gradlew installDebug` and `python -I tools\peek.py notes` when the phone is plugged in |
| ⏳ | 2026-11-11 | S25 Edge | Do the owner's dictated sentences survive the recogniser and the rules the same way as typed text | Not yet run | Needs his voice; the card 13 pipeline must exist first to see the records in the app |

Finding: `RecordRepository.replaceRecordsFor` deletes a re-read capture's unpinned records after the new projection was computed, so a "which placements are current" query must ignore supersession links that come from the capture being re-read; otherwise a later placement's own older link hides the very record it should supersede. Fixed in `WhereaboutsDao.currentItemRecords(exceptCaptureId)` and covered by a test.

## Card 24: keep the audio of a voice note (2026-11-12, S25 Edge, debug spike `AudioSpikeActivity`)

| Status | Date | What | Result | Notes |
| --- | --- | --- | --- | --- |
| ✅ | 2026-11-12 | Our own `AudioRecord` (16 kHz mono PCM) | Works. Loudest sample 21575/32767 on a real sentence | Raw PCM about 1,870 KB/min |
| ✅ | 2026-11-12 | AAC (MediaCodec, 32 kbps, M4A) of that audio | 262-271 KB/min; encode about 0.4 s for 3 s of audio; plays back | Size is not a problem: about a quarter of a megabyte per minute |
| ❌ | 2026-11-12 | Feed our audio to the recogniser live through a pipe (`EXTRA_AUDIO_SOURCE`) | `ERROR_CLIENT` (5) after about 13 ms, in all four combinations: on-device or default service, with all format extras or the audio source alone | The recogniser refuses the audio-source request outright. Do not retry |
| ❌ | 2026-11-12 | Our recorder and the recogniser using the microphone at the same time | Recorder got real audio; recogniser returned `ERROR_NO_MATCH` (7), i.e. heard nothing | The microphone goes to one of them. Do not retry |
| ❌ | 2026-11-12 | Transcribe a saved file by giving the recogniser a file descriptor | `ERROR_CLIENT` (5) after 6 ms | Pipe from file also failed: 0 of 140,800 bytes read |
| ⏳ | 2026-11-12 | Locked-phone run of the spike | Not run; superseded by the verdict | Needed again for whichever engine is chosen |

**Verdict C:** the phone's recogniser cannot be given audio, so keeping the audio and using that recogniser are mutually exclusive. To keep the audio and still get text, the app must record itself and use a transcription engine it ships (D7, D8 reopened by D77). The same engine can re-transcribe a saved note later, which is the owner's actual complaint (odd transcriptions that become "the fact"). Next: a spike measuring a bundled engine (sherpa-onnx or Whisper-class, English) on this phone: APK size, seconds per note, and accuracy on the owner's own sentences, side by side with the phone's recogniser.

Process finding: a spike result shown only on screen was lost when the activity restarted; read it from the phone with `uiautomator dump` straight after each run. adb over Wi-Fi (paired 2026-11-12) is steadier than this PC's USB cable.

## Card 25: corpus and the phone's recogniser baseline (2026-11-12, S25 Edge)

15 sentences recorded by the owner as 16 kHz mono WAV (some with a tap running, one quiet, one with leading silence); the same sentences said again to the phone's live recogniser (on-device, no word hints, no correction rules). Recordings and texts are in `docs/spike25/` (kept out of any repository).

| Status | Sentence | Phone's recogniser heard | Note |
| --- | --- | --- | --- |
| ✅ | s03, s04, s06, s11 (noise), s13 (quiet), s14 (silence first) | exact words | plain sentences were right, including noisy and quiet ones |
| ❌ | s01 "cyanuric acid" | "cyanoric acid" | rare word |
| ❌ | s02 "twenty four ninety nine" | "2499" | price lost its decimal point |
| ❌ | s05, s12 "Syd" | "Sid" | name (the app's correction rule fixes this) |
| ❌ | s07 "Valpolicella by Allegrini" | "Devalocello by Al Greenie" | both wine names wrong |
| ❌ | s08 "Cavit", "eleven ninety nine" | "Cavett", "1199" | brand and price |
| ❌ | s09, s15 "and the" | "in the", "in pH" | small words |
| ⏳ | s10 (27.7 s ramble) | not comparable | The text heard was from a second, faster re-saying of the sentence from memory, not from the recording, so it says nothing about the recogniser on the recorded speech. Redo by playing the recording aloud while the recogniser listens (button added 2026-11-12). An earlier version of this row wrongly called the result invented detail; corrected.

Owner's text for s10 (reference, with fillers "um", "uh... yeah", which scoring ignores): "So I got up um a little late today, more like after 7 I made coffee as usual, did what dishes we had then I came in to work with the agents on the immersive sim and get started on my workday here. Uh... yeah. And I had tuna for lunch".

Findings: the baseline is good on plain speech and fails on **names, brand and wine words, and numbers and prices** (long free speech is untested, see s10), which are the things the app most needs to find again. (s10 is not part of this finding: see its row.) Caveat: this pass did not use the app's word-hint list or correction rules, so it overstates the failures on Syd, Costco and Tesla. Also: the first press of the baseline button after idle gave `ERROR_SERVER_DISCONNECTED` (11) once; the second press worked. Harmless; consistent with the earlier lost-connection finding.

### Card 25 baseline: decisions (2026-11-12)

| Status | What | Result | Notes |
| --- | --- | --- | --- |
| ❌ | Play the recording through the phone's own speaker while its recogniser listens | `ERROR_NO_MATCH` (7), no text, on sentence 9 and others | Hypothesis, not proven: echo cancellation on the recogniser's microphone path removes sound the phone itself plays. Do not retry. |
| ↩️ | Play the recording from the PC through headphones held at the phone's microphone | Built (`CorpusRecorderActivity` accepts `--ei idx N --ez listen true`) but **not run** | Owner judged it over-engineered for one number. The "said it again" results (s01 to s09, s11 to s15) are the baseline. |
| ⏳ | s10 (30-second free speech) baseline | Not scored | The recogniser stops at pauses and filler words ("um", "uh"), so it cannot be given a fair word-accuracy score on long, pause-heavy speech. That limit is itself a finding for the engine comparison: file-based engines transcribe the whole recording. |

## Card 25: engine results (2026-11-12, S25 Edge SM-S937U, Android 17, debug build)

Every number below was measured on the phone over wireless adb on 2026-11-12, except the rows marked **estimate**, which are calculations. Raw outputs are in `docs/spike25/results/<run>/summary.tsv` (git-ignored with the corpus, D53); scoring is `python -I docs/spike25/score.py` (and `--detail <run>`).

### Set-up and versions

| What | Value |
| --- | --- |
| Runner | sherpa-onnx **v2.0.1** (newest numbered release on the day; v2.0.2 to v2.1.1 do not exist), `sherpa-onnx-2.0.1.aar`, 49,441,341 bytes, sha256 `a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7`, from github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1. Kept outside the repository (`C:\jeffsource\spike25-models\aar\`); `debugImplementation(files(...))`, debug-only `abiFilters arm64-v8a`; release classpath checked: no sherpa, ONNX or ML Kit entry |
| Code | `app/src/debug/spike25/java/app/stash/debug/` (`EngineSpikeActivity`, `Spike25Runner`, `Spike25Codec`, `Spike25MlKit`, `Spike25Worker`/`Spike25WorkReceiver`); compiled only when the AAR file exists. Runs in the app's own process: a separate `:spike25` process crashed in `StashApp.onCreate` (WorkManager not initialised there) and would have run the app's start-up twice, so it was dropped. Cold load = first load after `am kill app.stash` (cached process only; no force-stop) |
| Settings, every run | CPU provider, int8 (as packaged), `num_threads` 2 unless stated, greedy search unless stated, no NNAPI/GPU/NPU, 16 kHz WAV straight from `files/spike25/` |
| Memory | `Debug.getMemoryInfo().totalPss` sampled every 200 ms inside the app (= dumpsys TOTAL PSS) **and** `dumpsys meminfo <pid>` polled about once a second from the PC (catches less on 2-second runs; both stored per run). Figures below are the in-app peak |
| Phone | `getconf PAGE_SIZE` = **4096** (4 KB kernel). CPU: 6 cores at 3.53 GHz plus 2 at 4.47 GHz, all big cores (no slow cores to pin to) |
| ML Kit GenAI Speech Recognition | `com.google.mlkit:genai-speech-recognition:1.0.0-alpha2` (debugImplementation, measured only, owner's yes 2026-11-12). Needed `-Xskip-metadata-version-check` on debug Kotlin compiles (the library is built with Kotlin 2.4 metadata; the project is on 2.2.10). Runs under Google's terms and reports usage metrics to Google |

Model files (all downloaded 2026-11-12 to `C:\jeffsource\spike25-models\`, never into the repository; sizes and sha256 in `docs/LICENSES.md`): `sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8` (131.7 MB), `sherpa-onnx-moonshine-base-en-quantized-2026-02-27` (141.3 MB), `sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27` (44.2 MB, extra, see below), Whisper `base.en` int8 (160.6 MB) and `small.en` int8 (375.5 MB) from huggingface.co/csukuangfj, `sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8` (661.3 MB), `silero_vad.onnx` (643,854 bytes).

### 16 KB alignment (leg E)

| Check | Result |
| --- | --- |
| `zipalign -c -P 16 -v 4` (SDK build-tools 36.0.0) on the debug APK | **Verification successful**; every `lib/arm64-v8a/*.so` OK |
| ELF LOAD segment alignment (no NDK on this PC, so no `llvm-objdump`; read with a 20-line Python ELF program-header reader in the scratchpad) | `libonnxruntime.so`, `libsherpa-onnx-c-api.so`, `libsherpa-onnx-cxx-api.so`, `libsherpa-onnx-jni.so`: every LOAD at `2**14`. **All 16 KB aligned** |
| Native library load | Loaded first time (`nativeloader ... libsherpa-onnx-jni.so ... ok`); no load error with any model |
| "Page size compat mode" warning on first launch | None seen, but the kernel is 4 KB, so the warning could not appear on this phone |

### Accuracy (the corpus: 15 sentences, 195 reference words and 49 key tokens; 147 and 43 without s10)

Scoring: lower case, no punctuation, numbers as digits ("five" = "5", "seven point two" = "7.2", "fifth" = "5th" = "5", "3:30" = "three thirty", "20x25x1" = "20 by 25 by 1"); a price spoken as "twenty four ninety nine" and written "24.99" both become "24 99" and match, "2499" does not; fillers (um, uh, yeah) ignored. Key tokens counted by the script (contiguous match after the same normalisation) and checked by eye in the `--detail` output. **With 147 to 195 words, a difference of one or two words or one or two key tokens is noise.** The baseline is a different saying of the same sentences (live), so it is compared on "no s10" only.

"As recorded (inferred)": every file-based engine, across three different model families, heard "**I put the password, I put the passport** in the bedroom drawer" (s13) and "...and **I think** I had tuna for lunch" (s10). The recordings very probably contain those words, which the reference text does not. That costs every engine 4 to 6 words that the baseline (a separate saying) does not pay. The last WER column uses the inferred text for those two sentences only. **Not confirmed: the owner should listen to s13 and s10.**

| Run | WER (all 15) | Keys (all) | WER (no s10) | Keys (no s10) | WER no s10, as recorded |
| --- | --- | --- | --- | --- | --- |
| **Phone's recogniser (baseline, live)** | n/a | n/a | **0.122** | **35/43** | 0.122 |
| Parakeet TDT-CTC 110M | 0.138 | 39/49 | 0.129 | 35/43 | 0.099 |
| Parakeet 110M + VAD | 0.149 | 40/49 | 0.136 | 36/43 | 0.106 |
| Moonshine base | **0.113** | **43/49** | **0.102** | **37/43** | **0.073** |
| Moonshine base + VAD | 0.159 | 41/49 | 0.163 | 35/43 | 0.132 |
| Moonshine base + VAD, 0.3 s padding, 10 s max | 0.154 | 41/49 | 0.150 | 35/43 | 0.119 |
| Whisper base.en | 0.154 | 40/49 | 0.136 | 35/43 | 0.106 |
| Whisper base.en + VAD | 0.210 | 36/49 | 0.197 | 31/43 | 0.166 |
| ML Kit GenAI, Basic mode | 0.138 | 43/49 | 0.109 | **38/43** | 0.079 |
| ML Kit GenAI, Advanced mode | 0.133 | 43/49 | 0.116 | 37/43 | 0.086 |
| Whisper small.en | **0.092** | 43/49 | **0.075** | 37/43 | **0.046** |
| Parakeet-TDT 0.6B v2 (ceiling), greedy | 0.108 | 41/49 | 0.109 | 35/43 | 0.079 |
| Parakeet-TDT 0.6B v2 (ceiling), modified beam search | 0.097 | 43/49 | 0.095 | 37/43 | 0.066 |
| Moonshine tiny (extra) | 0.154 | 39/49 | 0.150 | 34/43 | 0.119 |

Findings:
- **Every candidate is within noise of the baseline on key tokens** (34 to 38 of 43, baseline 35). No small engine is clearly more accurate than the phone's recogniser; the best (Moonshine base, ML Kit Basic) gain about 2 key tokens and 2 to 3 words. The ceiling model (Parakeet v2, 630 MB) gets no more key tokens than Moonshine base (37/43). Whisper small.en has the lowest WER but the same key tokens.
- Misses common to almost every engine: "Syd" (heard "Sid", "sit"; a homophone the app's correction rule already fixes), "frunk" ("front", "frk", "franc"), "Cavit" ("Cabot", "caveat", "Cavet"). Engines that wrote prices kept the separation ("24.99", "$24.99", or "twenty four ninety nine"); the baseline wrote "2499" and "1199" (ML Kit Basic and Moonshine tiny each wrote "1199" once).
- **The VAD hurt every engine here** (by 3 to 6 words): it starts a segment at the first speech frame, which cut the first word ("The dentist" became "dentist"), and splitting s07 in two broke the wine names. Padding (0.3 s) fixed the clipped words but not the split. On the silence-first and noise sentences (s14, s11, s12), the engines without VAD did as well or better, and **no engine invented text on the leading silence**, Whisper included. The VAD's real use is memory (below), not accuracy.
- The long ramble (s10) was transcribed whole by every file engine (Moonshine base 7/48 word errors, 6/6 key tokens), which the live recogniser cannot do.

### Word steering (hotwords)

| Engine | Result |
| --- | --- |
| Parakeet-TDT 0.6B v2 (sherpa-onnx `nemo_transducer`, `modified_beam_search`, 20-word list, score 1.5) | **Rejected by the release**: `offline-model-config.cc:Validate:114 bpe_vocab: '' does not exist`, "failed to create native OfflineRecognizer". The packaged model folder has no `bpe.vocab` (archive lists encoder, decoder, joiner, tokens, README only). Reported without steering, as the card directs. The "without" run used `modified_beam_search` (row above) |
| Parakeet 110M (CTC), Moonshine | No steering exists in sherpa-onnx for these; not run |
| Whisper (whisper.cpp `initial_prompt`) | **Not run.** Whisper small.en is within the card's "about two key tokens" of the best (37 vs 38), so the leg qualified, but whisper.cpp must be built from source with the NDK, which is not installed here; and Whisper small.en fails the D81 size, memory and mid-range speed bars whatever its accuracy, so steering could not make it a verdict-A candidate |
| ML Kit GenAI | No word-list option in the alpha API |

### Speed, load, memory, size (S25 Edge)

Real-time factor (RTF) = processing seconds / audio seconds, model load excluded. Mid-range estimate = S25 RTF x 10 s x **3 (a guess, per D81)**, using the 1-thread figure where measured. `taskset` could not be used to pin the app's threads to fewer or slower cores: `taskset: failed to get pid's affinity: Permission denied` both as the adb shell user and through `run-as app.stash`; and the S25 has no slow cores anyway. `num_threads` 1 worked.

| Candidate | RTF median / worst (2 thr) | RTF 1 thr | 4 thr | **Est. mid-range, 10 s note, warm** (bar 5 s) | Load cold / warm | Model on disk (bar 200 MB) | Peak PSS, notes up to 8.4 s (bar 400 MB) | Peak PSS incl. 27.7 s ramble |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Parakeet 110M | 0.011 / 0.014 | 0.014 | 0.012 | 0.4 s (passes) | 0.58 s / 0.52 s | 131.7 MB (passes) | 400 MB (on the bar) | 552 MB (misses by 38%); with VAD 437 to 451 MB |
| Moonshine base | 0.015 / 0.024 | 0.018 | 0.015 | 0.5 s (passes) | 0.52 s / 0.43 s | 141.3 MB (passes) | 351 MB (passes by 12%) | 486 MB (misses by 22%); with VAD 444 MB |
| Moonshine tiny (extra) | 0.009 / 0.015 | n/r | n/r | 0.3 s (passes) | 0.26 s / 0.23 s | 44.2 MB (passes) | 243 MB (passes) | 376 MB (passes by 6%) |
| Whisper base.en | 0.069 / 0.125 | n/r | n/r | 2.1 s (passes) | 0.55 s / 0.77 s | 160.6 MB (passes) | 505 MB (misses by 26%) | 660 MB (misses by 65%) |
| ML Kit Basic | 0.063 / 0.081 | n/a | n/a | about 1.9 s (guess; the model runs in Google's system service) | `warmup()` 0 ms (already on the phone) | none in the app; system-managed, already present | app 127 MB; **the model's memory is in a system process and was not measured** | same |
| ML Kit Advanced | 0.384 / 0.469 | n/a | n/a | about 11.5 s (misses by 2.3x) | `warmup()` 1.7 s | none in the app; 63.9 MB system download (10.4 s) | app 126 MB; model outside, not measured | same |
| Whisper small.en | 0.201 / 0.352 | n/r | n/r | 6.0 s (misses by 20%) | 0.93 s / 1.95 s | 375.5 MB (misses by 88%) | 1,095 MB (misses by 2.7x) | 1,352 MB |
| Parakeet v2 (ceiling) | 0.035 / 0.039 (0.038 with beam search) | n/r | n/r | 1.1 s | 1.33 s / 1.30 s | 661.3 MB (misses by 3.3x) | 947 MB | 1,213 MB |

(n/r = not run: threads 1 and 4 only for the leaders.) Memory notes: the ONNX Runtime memory pool grows on the long ramble and does not shrink afterwards. A breakdown taken right after loading Moonshine base showed native heap 194 MB (the model), graphics 45 MB (the spike's own screen) and 46 MB of mapped native libraries in the APK. A background job has no screen, so its total is lower (next table). The app itself idles at about 26 MB (main process, no screen) to 76 MB (spike screen open).

**Runner size in the APK:** the four arm64 libraries are 31.4 MB, stored uncompressed (about 11.7 MB deflated, which is roughly what a store download would cost). The models compress poorly: Moonshine base 141 MB to 107 MB, Parakeet 110M 132 MB to 101 MB, Moonshine tiny 44 MB to 29 MB. **Delivery:** about 107 MB compressed plus 12 MB of libraries fits under Google Play's 200 MB base-download limit (as remembered; not checked today), but only narrowly. An install-time asset pack or a first-use download of about 141 MB is the realistic route. A download is a new outbound request and needs its own explanation card (card 23).

### Background job and battery

| Status | Check | Result |
| --- | --- | --- |
| ✅ | WorkManager job, Moonshine base, s10 (27.7 s), app process cold, **screen on** | Started 59 ms after enqueue, finished: load 1.08 s, transcription 2.0 s (RTF 0.073, about 3x slower than the same sentence in the foreground, 0.024: background CPU limits), peak PSS **418 MB** (misses the 400 MB guide by 5%), not killed |
| ✅ | WorkManager job, s01 (8.4 s), screen on | Moonshine base: load 1.23 s, RTF 0.052, peak **401 MB** (on the bar). Parakeet 110M: load 1.27 s, RTF 0.029, peak **299 MB** (passes by 25%) |
| ⏳ | Same job with the **screen off** (does Samsung delay it; is it killed) | **Needs the owner** (it means switching his screen off). Steps: phone unlocked and idle, run `adb shell am broadcast -n app.stash/app.stash.debug.Spike25WorkReceiver --es model moonshine-base --es tag work-screenoff --es ids s10 --ei delaySec 60`, press the power button within 60 s, wait 2 minutes, unlock, then `adb exec-out run-as app.stash cat files/spike25/out/work-screenoff/work.txt` (start and end times, `interactiveAtStart=false` expected) and `.../summary.tsv` |
| ⏳ | Leg D11: start our recorder from the **locked** phone through the notification action | **Needs the owner** (needs him to lock the phone, tap a notification and speak). Steps: lock the phone, open the Stash notification's record action, say a sentence, then check the saved file's loudest sample as in card 24 (`AudioSpikeActivity` has `showWhenLocked`) |
| ✅ (rough) | Battery and heat, Moonshine base, screen on, 2 threads | Ten passes take only 21 s, too short to move the gauge, so **100 passes** of the corpus (about 168 minutes of audio, 258 s of work) were run: battery **67% to 64%**, battery temperature **28.7 to 34.3 °C**, thermal status 0 (none) throughout. Rough: one run, screen on, gauge in whole percent |

### Audio size (leg C), best open engine (Moonshine base) on each

Encoders on this phone: `c2.android.aac.encoder`, `c2.sec.aac.encoder`, `OMX.google.aac.encoder` (AAC, 8 to 48 kHz) and `c2.android.opus.encoder` (Opus, 8/12/16/24/48 kHz, 6 to 510 kbps; software). Opus written to Ogg with `MediaMuxer` `MUXER_OUTPUT_OGG`: **worked**, with no errors on any of the 75 files. Opus decodes at 48 kHz (resampled to 16 kHz for the engine).

| Setting | Measured bytes per minute | WER (all) | Keys (all) | Keys (no s10) | vs the WAV run (43/49) |
| --- | --- | --- | --- | --- | --- |
| WAV 16 kHz (reference) | 1,920,000 | 0.113 | 43/49 | 37/43 | n/a |
| AAC-LC 32 kbps | 257,000 | 0.159 | 40/49 | 34/43 | loses 3 |
| AAC-LC 16 kbps | 134,000 | 0.164 | 40/49 | 35/43 | loses 3 |
| Opus 24 kbps | 259,000 | 0.123 | 43/49 | 37/43 | none lost |
| Opus 16 kbps | 200,000 | 0.113 | 42/49 | 36/43 | loses 1 (noise) |
| **Opus 12 kbps** | **170,000** | 0.108 | 43/49 | 37/43 | **none lost** |

The Opus encoder's output is well above its nominal bitrate (12 kbps should be about 90 KB/min; it measured 170 KB/min, Ogg included), so "16 kbps" is not smaller than "12". Engine-wise, the **smallest legible setting is Opus 12 kbps, about 170 KB a minute** (10 MB an hour). AAC is worse for the engine at both rates. **Not yet checked: whether a person can understand it by ear (needs the owner).** All 75 encoded files are in `docs/spike25/results/enc/<setting>/` to play on the PC. Listen to `opus12/s07.ogg` (wine names) and `opus12/s13.ogg` (quiet).

### Examples, heard vs written (harmless)

| Said | Phone's recogniser (live) | Moonshine base | Parakeet 110M | ML Kit Basic |
| --- | --- | --- | --- | --- |
| The Valpolicella by Allegrini is in the Italian section, row 5, bucket 10 | Devalocello by Al Greenie is in Italian section row 5 bucket 10 | The Valpolicella by Allegrini is in the Italian section row 5 bucket 10. | The Val Picella by Allegrini is in the Italian section row five Bcket ten. | The valve policella by Allegrini is in the Italian section row 5, bucket 10. |
| The Tide is on sale for twenty four ninety nine at Costco | The tide is on sale for 2499 at Costco | Tied his own sale for twenty-four ninety-nine at Costco. | Tide is on sale for twenty four ninety nine c at Costicco | Tide is on sale for 24.99 at Costco. |
| Total Wine has the Cavit Pinot Grigio for eleven ninety nine | Total Wine has the Cavett Pinot Grigio for 1199 | Total wine has the Cabot Pinot Grigio for eleven ninety nine. | Total Wine has the caveat Pinogrigio for eleven ninety nine. | Total Wine has the Cabot Pinot Grigio for 1199? |
| Syd has the spare key in the frunk | Sid has the spare key in the frunk | Sit as a spare key in the front. | Sit as a spare key in the frk. | Sit as a spare key in the frunk. |

### Licences (leg F, copied from `docs/LICENSES.md`)

| Item | Licence | Commercial use | Attribution |
| --- | --- | --- | --- |
| sherpa-onnx 2.0.1 (runner) | Apache-2.0 | yes | licence text |
| ONNX Runtime (inside the AAR) | MIT, Microsoft | yes | licence and copyright line; ThirdPartyNotices |
| Moonshine base and tiny, English | MIT (English models; the package's LICENSE file, read 2026-11-12, says so; non-English Moonshine models are non-commercial and were not downloaded) | yes | MIT text |
| Parakeet TDT-CTC 110M, Parakeet-TDT 0.6B v2 | CC-BY-4.0 (the v2 package README says so too) | yes | credit NVIDIA, licence link, say the files were converted |
| Whisper base.en, small.en | MIT (OpenAI) | yes | MIT text |
| Silero VAD | MIT | yes | MIT text |
| ML Kit GenAI Speech Recognition 1.0.0-alpha2 | Google ML Kit terms plus "ML Kit GenAI API Additional Terms of Service" (read 2026-11-12, last modified 14 May 2025): preview or experimental services may not be used in production; users must be 18 or over; the app must tell users about metrics processing; no clinical use. Benchmarking is not mentioned | **not while alpha** | n/a; disclose metrics to Google |

### Verdict proposed (the owner decides), against the D81 bars

| Candidate | Accuracy vs baseline | Mid-range speed (bar 5 s) | Memory (bar about 400 MB) | Disk (bar 200 MB) | 16 KB | Licence | Fits verdict A? |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **Moonshine base** | keys +2, WER 0.02 better (both within noise: "as good as") | 0.5 s fg, 1.6 s bg: passes 3x to 10x | short notes 351 MB fg (passes by 12%), 401 MB bg (on the bar); 28 s ramble 418 MB bg (misses by 5%), 486 MB fg (misses by 22%) | 141 MB (passes by 29%) | yes | MIT | **yes, except long notes miss memory by 5 to 22%** |
| Parakeet 110M | keys equal, WER 0.007 worse (noise) | 0.4 s fg, 0.9 s bg | short 400 fg, 299 bg; ramble 552 fg (misses by 38%) | 132 MB | yes | CC-BY-4.0 | near: equal accuracy, ramble memory misses more |
| Moonshine tiny (extra) | keys 1 below, WER 0.03 worse | 0.3 s | 243 to 376 MB (passes) | 44 MB | yes | MIT | the only one that passes every size and memory bar; accuracy about the baseline's, slightly below |
| Whisper base.en | keys equal, WER 0.014 worse | 2.1 s | 505 to 660 MB (misses by 26 to 65%) | 161 MB | yes | MIT | no (memory) |
| ML Kit Basic | keys +3, WER 0.013 better | about 1.9 s (guess) | not measurable from the app | 0 in the app | n/a | Google terms; alpha may not be used in production | **no: not usable in production while alpha**. Basic mode is on API 31+, so mid-range reach looks good |
| ML Kit Advanced | keys +2 | about 11.5 s | not measured | 64 MB system download | n/a | as above | no (speed; listed only for flagship chips: SM8750, SM8845, SM8850, SM8950, SM8975, MT6991/3/5, Exynos 2600, Pixel 10/11) |
| Whisper small.en | keys +2, best WER | 6.0 s (misses by 20%) | 1.1 to 1.35 GB | 376 MB | yes | MIT | no (size, memory, speed) |
| Parakeet v2 (ceiling only) | keys +2 with beam search | 1.1 s | 0.95 to 1.2 GB | 661 MB | yes | CC-BY-4.0 | not a candidate (D81) |

**Proposal: verdict A, narrowly, with Moonshine base** (MIT, 141 MB, about 31 MB of native libraries) as the default engine for card 26: record, save, transcribe in the background, keep the audio as Opus 12 kbps, replay and re-transcribe. Reasons: it is as accurate as the phone's recogniser on his voice (not more; the gains are within noise); it transcribes long, pause-heavy speech whole, which the live recogniser cannot; it is 3 to 10 times inside the mid-range speed bar; its libraries are 16 KB aligned; its licence is clean. Its one miss is memory on long notes, by 5% (background job) to 22% (with a screen open). **Card 26 must cap that**, for example by splitting long recordings only at real pauses (the plain VAD split cost accuracy here) or by limiting ONNX Runtime's memory pool, then re-measure on the 28-second note. If the owner treats 400 MB as a hard line, the fallbacks are **Moonshine tiny** (passes every bar, 44 MB, accuracy one key token below the baseline) as the default with base as an opt-in, or **verdict B**. The ceiling run says a large opt-in model is **not worth offering**: Parakeet v2 at 630 MB gets no more key tokens than Moonshine base, and Whisper small.en gains about 4 words in 147 over Moonshine base.

What would change this: a real mid-range phone (the 3x factor is a guess); the owner's ear test of Opus 12 kbps; screen-off and locked-phone runs; a NeMo `bpe.vocab` that makes Parakeet hotwords work (the names, brands and wine words that all engines miss are exactly what steering targets); ML Kit GenAI leaving alpha.

Not run, and why: Whisper prompt (whisper.cpp needs an NDK build; see steering); fp16/fp32 for the winner (no float package of the newest Moonshine base packaging is published; the older `moonshine-base-en-int8` is a different export); threads 1 and 4 for non-leaders; screen-off WorkManager and locked-phone recording (need the owner); the ear test (needs the owner); a cheaper phone (none available).

Process findings: the spike screen's results were always pulled from files (`summary.tsv`), never read off the screen. `adb push` of a scratchpad path failed under `MSYS_NO_PATHCONV=1` (Git Bash path not converted); use the Windows form of the path. On the phone, the models left in `files/spike25/models/` are Moonshine base and tiny, Parakeet 110M and the VAD (303 MB); the large models were deleted. The encoded copies (`enc/`, `dec/`) and results (`out/`) are there too. The owner's `sNN.wav` and `sNN.phone.txt` files were not touched. Delete `files/spike25/` when the spike is closed.

## Card 16a: attachment table and encrypted file store (2026-11-12)

| Status | Date | Where | What | Result | Notes |
| --- | --- | --- | --- | --- | --- |
| ✅ | 2026-11-12 | Developer PC (JVM, Robolectric) | `./gradlew testDebugUnitTest` | 423 tests pass, 0 fail (29 new: `AttachmentStoreTest` 11, `AttachmentTest` 15, `MigrationTest` +2, `ConsistencyCheckTest` +1) | Covers every unit check on the card |
| ✅ | 2026-11-12 | Developer PC (JVM) | Tink 1.24.0 `AesGcmHkdfStreaming(rawKey, "HmacSha256", 32, 4096, 0)` round trip | Constructor exists (`javap` on the Maven Central jar, sha1 `c2ca30c9d3654a37a3966dff064c7bf063e81f9a`); 0 B, 1 B, 4,096 B, 300 KB and 3 MB read back identical; flipped byte, wrong key, other attachment id, truncation, unknown version and missing file all fail as `AttachmentUnreadable` | JVM only; the phone is the next row |
| ✅ | 2026-11-12 | Developer PC (JVM, Robolectric) | Migration 2 to 3 on a real version-2 file built from the committed `2.json`, 3 captures with records, opened through Room | Version 3; every capture and record column unchanged; `attachment` empty with its index; search and the consistency check clean | Room's own schema check on open passed. The SQL equals `3.json` (test) |
| ✅ | 2026-11-12 | Developer PC | Debug APK size, before and after Tink | 72,116,326 → 74,507,889 bytes (+2.39 MB, dex only; unminified debug build, the card 25 spike libraries in both) | "Before" is the APK built on this PC at 18:14 the same day, just before this work. Release size not measured |
| ⏳ | 2026-11-12 | S25 Edge | Install over the existing app; the migration 2 to 3 on his real notes | Not run: needs the owner | Steps below. Nothing was installed and no adb command was sent |
| ⏳ | 2026-11-12 | S25 Edge | Debug self-test: 1 MB through `AttachmentRepository` and the store, read back, removed; `files/att` empty afterwards | Not run: needs the owner | Proves Tink's streaming AEAD on the phone. The rows go to a throwaway in-memory database, not his notes |

**Needs the owner (in this order; never uninstall, which would destroy the notes):**

1. Before: `python -I tools\peek.py notes > notes-before.txt`. Optional safety copy (only a table is added, but rule 14): `adb exec-out run-as app.stash cat databases/stash.db > stash-v2-copy.db` (and the same for `stash.db-wal` if it exists).
2. `.\gradlew.bat installDebug` from this PC (same debug key, so it installs over the app), then open Stash once.
3. After: `python -I tools\peek.py notes > notes-after.txt` and compare with the "before" file (same notes, same words). `python -I tools\peek.py dump 5`: `version` is 3 and `attachment` is an empty list. `adb logcat -b crash -d`: nothing from `app.stash`.
4. Self-test: `adb shell am broadcast -n app.stash/app.stash.debug.AttachmentSelfTestReceiver`, wait a few seconds, then `adb shell run-as app.stash cat files/debug/attachment-selftest.json` (expect `"pass":true` and the `writeMs` and `readMs` for 1 MB). Then `adb shell run-as app.stash ls -l files/att` is empty.
5. Write the results here.

### Card 16a: phone check done (2026-11-12, S25 Edge, wireless adb)

| Status | What | Result | Notes |
| --- | --- | --- | --- |
| ✅ | Safety copy before the migration | `C:\jeffsource\stash-safety\` holds `stash.db` (331,776 bytes), `-wal`, `-shm` and `notes-before.txt` | Made before the install; outside the project |
| ✅ | In-place `installDebug` over the existing app; app opened once | 10 notes before, 10 after; text and ids identical (`diff` of `peek.py notes`) | Same signing key, no uninstall. No `app.stash` entry in the crash log |
| ✅ | Schema 2 to 3 migration on the real database | `peek.py dump` lists the `attachment` table with 0 rows; every other table present | |
| ✅ | Attachment store self-test (`AttachmentSelfTestReceiver`) | `pass: true`; 1,048,576 bytes written 33 ms, read back 15 ms, identical; removed; file gone | Rows in a throwaway in-memory database; real `files/att/` used and left empty afterwards |
| ⏳ | Samsung Smart Switch honours `data_extraction_rules.xml` for `files/att/` | Not checked | Needs a real device transfer; low priority |
| ⏳ | A voice capture after the upgrade | Owner not asked yet | Still pending from card 01 as well |

## Card 16b: picture size and format (2026-11-12)

| Status | Date | Where | What | Result | Notes |
| --- | --- | --- | --- | --- | --- |
| ✅ | 2026-11-12 | Developer PC (JVM, Robolectric) | `./gradlew testDebugUnitTest` | 446 tests pass, 0 fail (23 new: `PictureSizingTest` 12, `PicturePreparerTest` 10, `WebpEncodingTest` 1) | No schema change, no new dependency |
| ✅ | 2026-11-12 | Developer PC (Robolectric native graphics) | Real `Bitmap.compress(WEBP_LOSSY, 75)` through `AndroidPictureEncoder` | Output is `RIFF....WEBP` with chunks `VP8X, ICCP, VP8 `: no `EXIF`, no `XMP ` | Skia writes an `ICCP` colour profile; allowed by `WebpChunks.onlyImageData`, see the card. Not a phone result |
| ⏳ | 2026-11-12 | S25 Edge | Debug screen `PictureSizeActivity` with five pictures at three sizes: bytes, ms, chunk list | Not run: needs the owner | Steps below |
| ⏳ | 2026-11-12 | S25 Edge | Portrait and landscape come out upright; 200 MP photo without out-of-memory; HEIC reads | Not run: needs the owner | |
| ⏳ | 2026-11-12 | S25 Edge | Is the smallest print readable in the Standard and Small copies of a text-heavy screenshot? | Not run: needs the owner | Write his answers |

**Needs the owner (nothing installed, no adb command sent):**

1. `.\gradlew.bat installDebug` (over the existing app, never uninstall), then `adb shell am start -n app.stash/app.stash.debug.PictureSizeActivity`.
2. Tap "Pick a picture" and choose each of: a gallery photo taken with location tagging on; a 200-megapixel photo if the camera has that mode; a screenshot of a text-heavy page; a HEIC photo; a PNG. For each, copy down the three result lines (stored size, bytes, ms, chunk list). Pass: every output is WebP with only image chunks (`EXIF` and `XMP ` absent), no crash, Standard photos roughly 100 to 500 KB.
3. Pick one portrait and one landscape photo: "View standard" must show them upright.
4. With the text-heavy screenshot, tap "View standard" and pinch to zoom: can he read the smallest print? Then "View small". Write his answers. If Standard is not readable, try quality 85 for screenshots before raising the size and record both.
5. Tap "Discard all", then `adb shell run-as app.stash ls files/att` must be empty and `adb shell run-as app.stash ls cache` must hold no picture (both only read).
6. Write each result in this table.


## Card 16c: Share to Stash and the "Anything to add?" screen (2026-11-12)

| Status | Date | Where | What | Result | Notes |
| --- | --- | --- | --- | --- | --- |
| ✅ | 2026-11-12 | Developer PC (JVM, Robolectric) | `./gradlew testDebugUnitTest` | 489 tests pass, 0 fail (43 new: `ShareRulesTest` 7, `ShareViewModelTest` 3, `ShareEntryTest` 7, `ShareActivityTest` 26) | No schema change, no new dependency |
| ⏳ | 2026-11-12 | S25 Edge | Screenshot toolbar Share: is Stash offered? Pick it, say a sentence, Save; count the taps | Not run: needs the owner | Also: is Stash in the top row of the share sheet (the shortcut)? |
| ⏳ | 2026-11-12 | S25 Edge | Five gallery pictures shared, Save without words; `files/att` sizes against the originals | Not run: needs the owner | |
| ⏳ | 2026-11-12 | S25 Edge | Share a web link from the browser: the address is in the text field | Not run: needs the owner | |
| ⏳ | 2026-11-12 | S25 Edge | Recent-apps card blank or absent; screenshot refused | Not run: needs the owner | |
| ⏳ | 2026-11-12 | S25 Edge | Share a picture, Cancel: `files/att` has no new file | Not run: needs the owner | |
| ⏳ | 2026-11-12 | S25 Edge | Rotate with three pictures ready: nothing lost, nothing prepared twice | Not run: needs the owner | |
| ⏳ | 2026-11-12 | S25 Edge | Does listening start by itself, and is it startling (D80: review after two weeks) | Not run: needs the owner | |

**Needs the owner (nothing installed, no adb command sent):**

1. `.\gradlew.bat installDebug` over the existing app (never uninstall), then open Stash once so the share shortcut is published.
2. Take a screenshot in another app, tap Share on the toolbar. Is Stash offered (in the list, and in the top row)? Pick it, say a sentence, tap Save. Count the taps from the screenshot to "Saved". If it is not offered from the toolbar, repeat from the gallery's Share.
3. In the gallery select five pictures, Share, Stash, Save without words. `adb shell run-as app.stash ls -l files/att` shows five files; write their sizes next to the originals'.
4. Share a link from the browser: the address is in the text field. Save.
5. With the annotate screen open press recent apps (card blank or absent) and try a screenshot (refused).
6. Share a picture, then Cancel: `files/att` has no new file.
7. Share three pictures, rotate the phone: three pictures still there, `files/att` still three files.
8. Turn Pictures off in Settings (the new row) and share a picture: the message and Close.
9. Write each result in the table above.


## Card 16d: Pictures on a note (2026-11-12)

| Status | Date | Where | What | Result | Notes |
| --- | --- | --- | --- | --- | --- |
| ✅ | 2026-11-12 | Developer PC (JVM, Robolectric) | `./gradlew testDebugUnitTest` | 529 tests pass, 0 fail (40 new: `PictureStripTest` 9, `ScreenshotToggleAbsentFromReleaseTest` 3, `PicturesOnNoteTest` 21, `StoredPictureLoaderTest` 4, `ScreenFlagTest` 3) | No schema change, no new dependency |
| ✅ | 2026-11-12 | Developer PC | The release source set holds none of the debug switch's names | `ScreenshotToggleAbsentFromReleaseTest` passes (scans `app/src/main` and `app/src/release`) | Not a check of a built APK |
| ❌ | 2026-11-12 | Developer PC (Robolectric) | Launch the real `MainActivity` with `@Config(application = StashApp::class)` | "WorkManager is not initialized properly" from `StashApp.onCreate` | Not retried; the flag is covered by a source test and a real-window test instead |
| ⏳ | 2026-11-12 | S25 Edge | Five shared pictures: five thumbnails in order; open the third, swipe to the fifth, zoom in on small print | Not run: needs the owner | |
| ⏳ | 2026-11-12 | S25 Edge | Add two from the gallery (they go last); remove the first; `files/att` file count 5, 7, 6 | Not run: needs the owner | |
| ⏳ | 2026-11-12 | S25 Edge | Delete the whole note: its pictures vanish from the app | Not run: needs the owner | |
| ⏳ | 2026-11-12 | S25 Edge | Private note with pictures: only the row until Show | Not run: needs the owner | |
| ⏳ | 2026-11-12 | S25 Edge | Recent apps shows no picture; screenshot refused; with the debug switch on, `peek.py screen` sees it | Not run: needs the owner | |
| ⏳ | 2026-11-12 | S25 Edge | Thirty pictures in one note: stutter, `dumpsys meminfo` before and after | Not run: needs the owner | |
| ⏳ | 2026-11-12 | S25 Edge | 200% font and TalkBack: "Picture 2 of 5"; Add and Remove reachable | Not run: needs the owner | |
| ⏳ | 2026-11-12 | Release APK | `grep -c ScreenshotToggle` over its dex files prints 0 (also `DebugSecureInit`, `DebugSettingsActivity`) | Not run: no release build made | Card 14's checklist should carry this line |

**Needs the owner (nothing installed, no adb command sent):**

1. `.\gradlew.bat installDebug` over the existing app (never uninstall). Open Stash: screenshots of it are now refused (`FLAG_SECURE`, the owner's D80 decision). For development: `adb shell am start -n app.stash/app.stash.debug.DebugSettingsActivity`, turn on "Allow screenshots of the main screen", go back to Stash: `python tools/peek.py screen` now shows it. Turn it off again before checks 7 and 8.
2. Share five pictures into one note (card 16c). Open Stash, Notes: five thumbnails in the order shared. Open the third, swipe to the fifth, pinch to zoom in on small print, drag to pan.
3. On that note tap Add a picture and choose two from the gallery: "Adding 2 of 2" shows, then they appear last. Open the first picture, tap Remove picture, Keep (nothing changes), Remove picture again, Remove: the rest keep their order. `adb shell run-as app.stash ls files/att | wc -l` goes 5, 7, 6.
4. Delete the whole note: its pictures vanish. (They leave the disk at the 30-day purge.)
5. Mark a note with pictures Private: only "N private pictures · Show" shows until you tap it; leave the app and come back: hidden again.
6. Make a note that is only a picture (share one with no words) and remove its picture: "This note is only this picture. Remove the note?"; Remove: the note is gone from the list.
7. With pictures showing, open recent apps: the Stash card shows no picture. Take a screenshot: refused.
8. Share thirty pictures into one note and scroll the list and the strip: note any stutter, and `adb shell dumpsys meminfo app.stash` before and after.
9. At 200% font size and with TalkBack: each thumbnail is announced "Picture 2 of 5"; Add a picture and Remove picture can be reached.
10. Settings: "Pictures: N, using M MB" matches the number of files in `files/att`.
11. When a release build exists: `unzip -p app-release.apk 'classes*.dex' | grep -c ScreenshotToggle` must print 0; same for `DebugSecureInit` and `DebugSettingsActivity`.
12. Write each result in the table above.


## Card 16e: A camera inside Stash (2026-11-12)

| Status | Date | Where | What | Result | Notes |
| --- | --- | --- | --- | --- | --- |
| ✅ | 2026-11-12 | Developer PC (JVM, Robolectric) | `./gradlew testDebugUnitTest` | 581 tests pass, 0 fail (52 new: `CameraRulesTest` 19, `CameraActivityTest` 21, `CameraEntryTest` 5, `CameraSourceScanTest` 4, `CameraRotationTest` 3) | One new dependency family (CameraX 1.6.2, below); no schema change |
| ❌ | 2026-11-12 | Developer PC (Robolectric) | First run of `CameraActivityTest`: expected the permission question as a started activity (`nextStartedActivityForResult`) | The permission test failed: no activity was started | Cause: the request goes through `Activity.requestPermissions`, which Robolectric records as `lastRequestedPermission`. Test changed to read that; passes |
| ✅ | 2026-11-12 | Developer PC | Rotation applied once, with the platform decoder (Robolectric native graphics) | A 40 by 20 JPEG whose bytes say "turn 90" decodes as 20 by 40 once; a camera rotation of 0 over a picture already tagged 90 decodes as 40 by 20 (old tag replaced, not added) | Proves the rule, not the phone: which value the S25 Edge camera reports is the phone check below |
| ✅ | 2026-11-12 | Developer PC | A scan of `app/src/main` finds none of the gallery or file forms of taking a picture | `CameraSourceScanTest` (also: one `takePicture(` call, the executor-and-callback form; no network words in the camera files; no `Log.` or `println(`) | |
| ✅ | 2026-11-12 | Developer PC | Debug APK built with CameraX | 74,918,507 bytes before, 83,848,843 after: **+8.93 MB** (debug, unminified, both include the card 25 spike libraries) | Release with R8 not measured |
| ✅ | 2026-11-12 | Developer PC | `zipalign -c -P 16 -v 4 app-debug.apk` (build-tools 36.0.0) | "Verification successful"; every `.so` reported OK, including the two CameraX ones: `libimage_processing_util_jni.so` (32,528 bytes) and `libsurface_util_jni.so` (4,896 bytes) | CameraX native libraries are 16 KB aligned in the built APK |
| ⏳ | 2026-11-12 | S25 Edge | Photo into Stash, "test bottle", Save; Samsung Gallery and Google Photos do not show it; `files/att` has one file (write its size) | Not run: needs the owner | |
| ⏳ | 2026-11-12 | S25 Edge | Upright and sideways pictures are both upright in the strip | Not run: needs the owner | The unit tests trust the camera rotation and write it into the picture's own tag; if a picture comes out turned twice or not at all, write which |
| ⏳ | 2026-11-12 | S25 Edge | Three photos into one note with Add another, Camera: one note, three pictures, in order | Not run: needs the owner | |
| ⏳ | 2026-11-12 | S25 Edge | From a note row: Add a picture, Take a picture, two shots, Done: they are on that note, last | Not run: needs the owner | |
| ⏳ | 2026-11-12 | S25 Edge | **Lock screen (RISKS R33).** Camera over the lock screen: opened and took a picture / asked to unlock / preview black / other; again after a restart, one unlock, lock again | Not run: needs the owner | The build ships the documented fallback (label "Unlock to take a picture", unlock first) because the real behaviour is unknown; to run the spike set `CameraRules.UNLOCK_FIRST_WHEN_LOCKED = false` |
| ⏳ | 2026-11-12 | S25 Edge | If it worked while locked: after Save the phone returns to the lock screen; the picture is absent from everything visible while locked | Not run: needs the owner | |
| ⏳ | 2026-11-12 | S25 Edge | Seconds from tapping the shutter to the annotate screen showing the picture | Not run: needs the owner | The app also logs `CAMERA/TAKEN` with the milliseconds to prepared |

**Library facts read on 2026-11-12 (Google Maven metadata and POMs):** `androidx.camera:camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`, all **1.6.2**. The metadata lists 1.7.0-rc01 and rc02 as newest (release candidates); 1.6.2 is the newest stable. Every POM names "The Apache Software License, Version 2.0"; `camera-core` also lists "BSD-3-Clause". Resolved extras (`debugRuntimeClasspath`), each POM licence read: `camera-camera2-pipe` 1.6.2, `camera-video` 1.6.2 (brought in by `camera-view`), `featurecombinationquery` 1.6.2, `viewfinder-core` 1.5.1, `concurrent-futures` and `concurrent-futures-ktx` 1.1.0, `exifinterface` 1.4.2, `core-backported-fixes` 1.0.0, `tracing` and `tracing-ktx` 1.3.0, `jspecify` 1.0.0 (all Apache 2.0); `dagger` 2.59 ("Apache 2.0"), `javax.inject` 1 (Apache 2.0), `atomicfu` 0.28.0 ("Apache-2.0"), `listenablefuture` 9999.0-empty-to-avoid-conflict-with-guava (no licence in its POM; its parent guava-parent says Apache 2.0), `auto-value-annotations` 1.6.3 (no licence in its POM; the parent chain auto-parent 7 says "Apache 2.0"). Only POM fields were read, not licence texts or NOTICE files.

**Needs the owner (nothing installed, no adb command sent):**

1. `.\gradlew.bat installDebug` over the existing app (never uninstall). Open Stash, Settings: a "Take pictures inside Stash" switch is on. On the Ask or Notes screen tap **Take a picture**; allow the camera when asked.
2. Take one photo, tap Save after typing "test bottle". Open Samsung Gallery and Google Photos: the photo is in neither. `adb shell run-as app.stash ls -l files/att`: write the new file size.
3. Hold the phone upright and take one; turn it sideways and take one: both are upright in the strip. If not, write whether the picture was turned twice or not at all.
4. Take three photos into one note: after the first, tap Camera (beside Gallery), repeat. One note, three pictures, in the order taken.
5. On a note row tap Add a picture, then Take a picture, take two, tap Done: they are on that note, last.
6. Time the seconds from the shutter to the annotate screen showing the picture.
7. Lock screen: lock the phone, start a note from the lock-screen notification, let it save, tap the button on the ready screen. As built the label says "Unlock to take a picture" and the phone asks to unlock first. To run the spike itself, set `CameraRules.UNLOCK_FIRST_WHEN_LOCKED = false`, rebuild, install, and write exactly which happened: camera opened and took a picture / the phone asked to unlock / the preview was black / something else. Do it a second time after restarting the phone, unlocking once and locking again. Then set the constant to match what the phone did (true for the unlock fallback) and say so here.
8. If it worked while locked: after Save does the phone return to the lock screen, and is the picture absent from everything visible while locked?
9. Switch the camera off in Settings: no camera button on the main screen or the ready screen, and Add a picture goes straight to the gallery. Deny the permission: the camera screen says so and offers the gallery.
10. Write each result in the table above.


## Card 16f: Reading the words in a picture (2026-11-12)

Built and tested on the JVM only. Nothing was installed on the phone and no adb command was sent. 610 unit tests pass (29 new). No schema change.

| Check | Result | Status |
| --- | --- | --- |
| Newest stable of `com.google.mlkit:text-recognition` on Google Maven (maven-metadata read 2026-11-12) | 16.0.1 (lastUpdated 2024-08-07); older: 16.0.0 and 16.0.0-beta1 to beta6. Pinned as `mlkitTextRecognition = "16.0.1"` | done |
| POM licence of `text-recognition:16.0.1` | "ML Kit Terms of Service", https://developers.google.com/ml-kit/terms (Google's own terms, not an open licence; owner accepted under D78/D80) | done |
| Kotlin metadata / compiler flag | None needed. Compiles with Kotlin 2.2.10, no `-Xskip-metadata-version-check` | done |
| Transitive libraries and the licence each POM states | See the list below | done (POM fields only, not licence texts) |
| Debug APK size before and after | 83,848,843 bytes before (the existing `app-debug.apk` of 19:59, which includes the card 25 spike libraries) and 94,218,879 after: **+10,370,036 bytes (about 9.9 MiB)**. Of that, `libmlkit_google_ocr_pipeline.so` is 11,064,544 bytes uncompressed (arm64-v8a only in debug), plus the model files under `assets/mlkit-google-ocr-models/` (about 1.5 MB uncompressed) and the support libraries. A release build, which compresses native libraries, was not measured. The "before" file was not rebuilt from the same source, so treat the delta as close, not exact | measured |
| `zipalign -c -P 16 -v 4` on the debug APK (build-tools 36.0.0) | "Verification successful"; `libmlkit_google_ocr_pipeline.so` at offset 49250304 is OK, as are all other `.so` files in the APK | pass |
| ELF `p_align` of the library's native code | `libmlkit_google_ocr_pipeline.so` and `libimage_processing_util_jni.so`: all LOAD segments 0x4000 (16 KB) | pass |
| First read does not crash on Android 17; reading time; labels; screenshot; aeroplane mode | not run | needs the owner |

Transitive libraries (resolved, from the release runtime classpath; licence = the POM's own licence field):
- `com.google.mlkit:text-recognition:16.0.1`: ML Kit Terms of Service. Its POM lists as dependencies:
- `com.google.mlkit:text-recognition-bundled-common:17.0.0`, `com.google.mlkit:common:18.11.0`, `com.google.mlkit:vision-common:17.3.0`, `com.google.mlkit:vision-interfaces:16.3.0`, `com.google.android.gms:play-services-mlkit-text-recognition:19.0.1`, `com.google.android.gms:play-services-mlkit-text-recognition-common:19.1.0`: all "ML Kit Terms of Service".
- `com.google.android.gms:play-services-base:18.5.0`, `play-services-basement:18.4.0` (resolved to 18.9.0), `play-services-tasks:18.2.0`, `com.google.android.odml:image:1.0.0-beta1`: "Android Software Development Kit License" (https://developer.android.com/studio/terms.html). Another proprietary licence: it needs the same "ask first" treatment as the ML Kit terms.
- `com.google.android.datatransport:transport-api:2.2.1`, `transport-backend-cct:2.3.3`, `transport-runtime:2.2.6`; `com.google.firebase:firebase-components:16.1.0`, `firebase-encoders:16.1.0`, `firebase-encoders-json:17.1.0`, `firebase-annotations:16.0.0`: "The Apache Software License, Version 2.0".
- Also on the path: `com.google.code.gson:gson:2.13.2` (Apache-2.0), `jsr305:3.0.2` (Apache 2.0), `auto-value-annotations:1.6.3` (no licence in its own POM; already recorded in the card 16e entry).
- Note: `transport-backend-cct` is a Google data transport library (probably the route for the library's usage figures; not verified). Merged manifest permissions and any network code that ML Kit brings were NOT inspected in this card beyond the library list; the "What leaves my phone" screen and card 23's explanation card carry the fixed disclosure.

**Needs the owner (nothing installed here):**

1. `.\gradlew.bat installDebug` over the existing app (never uninstall: it destroys the notes). Settings: a switch "Read the words in pictures" is on, with the Google disclosure line under it.
2. Share a screenshot of an article, and type nothing. On the annotate screen, "Read from the picture: ..." shows up to four lines. Save. In Notes the note is titled with the first two lines (or "Picture"). Search for a word that appears only in the picture: the note is found. Write the result.
3. Take or share ten wine or product labels in shop lighting (RISKS.md R31). For each, write whether the name, the maker and the shelf-tag price were read correctly. Pass: the name is findable by search for at least 8 of 10. If it fails, say so here and in the hand-back; the feature still works by his words, place and date.
4. Switch on aeroplane mode and add a picture: its words are still read. Write the result.
5. `python tools/peek.py events` (debug build): the `PICTURE READ` events carry lines, characters and milliseconds. Write the milliseconds for a label and for a full screenshot. A `FAILED` or `TIMED_OUT` event means the picture was stored without words.
6. Turn the switch off, add a picture (no words read, no ML Kit use), turn the switch on: a `CAUGHT_UP` event with the number of pictures read; the picture is now findable and its engine ends in `/stored`.
7. Tap a note's "Read from the picture (show)": the words open under the strip. For a Private note they are behind "Show".
8. Write the first-read result on Android 17 (no crash; the library has native code) and the release-build APK size if you build one.



## Card 16g: The quiet "Add a picture?" offer (2026-11-12)

Built and tested on the JVM only. Nothing was installed on the phone and no adb command was sent. 632 unit tests pass (22 new). No schema change, no new dependency.

| Check | Result | Status |
| --- | --- | --- |
| `PictureOffer.show`: true for PENDING, no picture, not Private, 1 h old; false for NONE, DISMISSED, one picture, Private, 25 h | as stated | pass (JVM) |
| Upkeep closes a 25 h PENDING and leaves a 1 h one; expiry uses the repository clock, no sleeping | as stated | pass (JVM) |
| Add a picture closes the offer; removing it does not bring it back; re-read leaves DISMISSED; a note with a picture stays NONE | as stated | pass (JVM) |
| `SavedNote`: offer at 2 s keeps Undo and idle open until 10 s; unchanged with no offer | as stated | pass (JVM) |
| Nothing posts a notification or sets `record.attention` (shadow notification manager empty across the flow) | as stated | pass (JVM) |
| Saved screen drawing the button, the 500 ms poll, the row line, tapping through to the camera | no Robolectric test drives `CaptureActivity` | needs the owner |
| Seconds from save tone to the button with the model off; the next-day expiry; lock screen; model on | not run | needs the owner |

Phone steps are listed at the end of `docs/cards/16g-picture-offer.md`.


## Card 26a: the recorder, the level rule and the locked-phone check (2026-11-12, JVM only so far)

No phone result exists yet; nothing below marked "not run" has been tried. Built without touching the phone.

| Status | Date | What | Result | Notes |
| --- | --- | --- | --- | --- |
| ✅ | 2026-11-12 | JVM tests, `RecorderLogicTest` (23) and `RecorderCheckAbsentFromReleaseTest` (2): buffer, level rule, `MicHealth`, verdict, session with a fake recorder | 657 of 657 pass (632 before) | Synthetic samples (quiet pattern about 0.0015 of full scale, 220 Hz tone); time counted in samples, no clock |
| ⏳ | | C2: record from the locked phone | not run | needs the owner; steps are at the end of `docs/cards/26a-recorder.md` |
| ⏳ | | Unlocked: "Syd has the spare key in the frunk"; pause of 4 s with the 5 s setting; 25 s of nothing; quiet sentence; tap running; a call or screen-off during recording | not run | same steps |
| ⏳ | | `EMPTY_PEAK` | provisional 40 of 32767, **not measured**; set it from the silent-room and quiet-sentence peaks | |

Memory: 16,000 samples a second x 2 bytes = 1.92 MB a minute. The ceiling is 180 s, so 5.76 MB held; ending the note copies the blocks into one array once (about 11.5 MB for an instant), then the recorder lets go of its blocks. A note cannot exceed the ceiling: `PcmBuffer` drops what does not fit.


## Card 26b: the stored format, Opus while recording, fallbacks, decoding (2026-11-12, JVM and PC only so far)

Nothing was installed on the phone and no adb command was sent. 686 unit tests pass (29 new). No schema change, no new dependency.

| Status | Date | What | Result | Notes |
| --- | --- | --- | --- | --- |
| ✅ | 2026-11-12 | JVM tests, `AudioCodecLogicTest` (18) and `StreamingEncoderTest` (11): resampler, WAV, Ogg CRC and pages, Opus headers, TOC, fallback rule with fake encoders, compactor, source checks | 686 of 686 pass (657 before) | Fake encoders only: no Android codec runs on the JVM |
| ✅ | 2026-11-12 | Card 25's 15 `opus12` files read page by page on the PC (`MediaMuxer` output from the S25) | **One 20 ms packet per Ogg page** in every file. 143,491 of 285,941 bytes (50.2%) are Opus packets; the rest is 28-byte page headers. At 24 kbps, 67.3% are packets | So "Opus 12 kbps = 170 KB a minute" was page overhead, not the encoder: the packets alone are about 85 KB a minute |
| ✅ | 2026-11-12 | The same packets put 50 to a page (as the app's own writer does), computed on the PC | **12 kbps: 91,093 bytes a minute; 24 kbps: 180,034; 16 kbps: 120,991** (100.7 s of speech) | A calculation from real packets, not a phone measurement. The packets are identical, so card 25's key-token result stands |
| ✅ | 2026-11-12 | Other things the phone's Ogg muxer wrote | No end-of-stream flag on the last page; granule = start of each page's packet, not its end; `OpusHead` pre-skip 104 (6.5 ms counted at 16 kHz; RFC 7845 wants 48 kHz, 312); vendor "libopus"; all 267 page CRCs of `s13.ogg` match libogg's CRC | Harmless for decoding; the app's writer follows the RFC |
| ↩️ | 2026-11-12 | E1 or E2 | **E2 (the app's own Ogg writer) made the default** on the size finding above. E1 (MediaMuxer into `memfd`) is built too, used for AAC, and run beside E2 by the debug screen | Not a phone result. If the phone cannot read E2's files, one constant (`OpusContainer.DEFAULT`) switches back |
| ⏳ | | E1 works (muxer accepts the memory file), E2 decodes (`MediaExtractor` reads the app's Ogg), length within 50 ms | not run | needs the owner; steps at the end of `docs/cards/26b-audio-format.md` |
| ⏳ | | 10 s and 3 min notes in `OPUS_12`: bytes a minute, Stop to `finish` (bars 300 ms and 1 s), queue not growing, warmth | not run | same steps |
| ⏳ | | Forced `AAC_32` and forced `WAV` play back | not run | same steps |
| ⏳ | | **Check C3** (12 kbps by ear) | not run | Q1 / Q2 / Q3; same steps |


## Card 26c: the recording store, the `transcription` table, migration 3 to 4 (2026-11-12, JVM only so far)

Nothing was installed on the phone and no adb command was sent. 715 unit tests pass (29 new; 686 before). Schema version 4. No new dependency.

| Status | Date | Where | What | Result | Notes |
| --- | --- | --- | --- | --- | --- |
| ✅ | 2026-11-12 | Developer PC (JVM, Robolectric) | `.\gradlew.bat testDebugUnitTest` | 715 tests pass, 0 fail (29 new: `RecordingStoreTest` 22, `MigrationV4Test` 3, `RecordingSourceScanTest` 3, `ConsistencyCheckTest` +1) | Covers every unit check on the card |
| ✅ | 2026-11-12 | Developer PC (JVM, Robolectric) | Migration 3 to 4 on a real version-3 file built from the committed `3.json`: six notes with records, a task, a reading, a placement, revisions, a note in the bin, a tombstone, and three `PHOTO` attachments with real encrypted files | Version 4. Every table except `capture` is identical cell for cell, the keys included. `capture` rows are identical with `transcribe_state` null and `transcribe_attempts` 0 appended. `transcription` is empty and has its index. Search, the picture bytes and the consistency check are all clean | Room's own schema check on open passed. The SQL equals `4.json` (test) |
| ⏳ | | S25 Edge | Install over the existing app; the migration 3 to 4 on his real notes | Not run: needs the owner | Steps below |
| ⏳ | | S25 Edge | Self-test: 10 s of tone saved through `RecordingSaver`, read back, written out, removed, purged; nothing left in `files/att` | Not run: needs the owner | The rows go to a throwaway in-memory database, not his notes |

**Needs the owner (in this order; never uninstall, which would destroy the notes):**

1. **Safety copy first.** This migration changes the `capture` table, which holds his notes.
   1. `python -I tools\peek.py notes > notes-before-26c.txt`
   2. `mkdir C:\jeffsource\stash-safety-26c`
   3. `adb exec-out run-as app.stash cat databases/stash.db > C:\jeffsource\stash-safety-26c\stash.db`, and the same for `stash.db-wal` and `stash.db-shm` if they exist. All three commands only read the phone.
   4. Check that the copy is not empty: `stash.db` should be a few hundred KB.
2. Note what is already in the file folder: `adb shell run-as app.stash ls files/att > att-before-26c.txt`.
3. `.\gradlew.bat installDebug` from this PC. It uses the same debug key, so it installs over the app. Then open Stash once.
4. After:
   1. `python -I tools\peek.py notes > notes-after-26c.txt`, and compare it with the "before" file: the same notes and the same words.
   2. `python -I tools\peek.py dump 5`: `version` must be 4, `transcription` an empty list, and every `capture` row must show `transcribe_state` null and `transcribe_attempts` 0.
   3. `adb logcat -b crash -d`: nothing from `app.stash`.
5. Self-test:
   1. `adb shell am broadcast -n app.stash/app.stash.debug.RecordingSelfTestReceiver`, and wait a few seconds.
   2. `adb shell run-as app.stash cat files/debug/recording-selftest.json`: expect `"pass":true`, `leftoverFiles` 0, and the `saveMs` and `readMs` values.
   3. `adb shell run-as app.stash ls files/att` must list the same files as `att-before-26c.txt`.
6. Write the results here.

### Phone check after cards 16b to 26c (2026-11-12, S25 Edge, wireless adb)

First install since card 16a: everything from cards 16b, 16c, 16d, 16e, 16f, 16g, 26a, 26b and 26c, plus the card 25 spike code, over the existing app.

| Status | What | Result | Notes |
| --- | --- | --- | --- |
| ✅ | Safety copy before the install | `C:\jeffsource\stash-safety-26c\` holds `stash.db` (344,064 bytes), `-wal`, `-shm`, `notes-before.txt` | Outside the project |
| ✅ | In-place `installDebug`, app opened | 10 notes before, 10 after; text and ids identical (`diff` of `peek.py notes`) | Same signing key; no uninstall; crash log empty for `app.stash` |
| ✅ | Schema 3 to 4 migration on the real database | `peek.py dump`: `database version 4`; `transcription` table present and empty; `attachment` empty | |
| ✅ | Attachment store self-test (16a) | `pass: true`; 1 MB written 29 ms, read 16 ms | |
| ✅ | Recording store self-test (26c) | `pass: true`; 320,044 bytes; first writing-out `WORDS`; recording removed; purged; `leftoverFiles` 0; save 15 ms, read 5 ms | Throwaway in-memory database; `files/att` empty afterwards |
| ⏳ | All the per-card phone checks listed in the "Card 16b" to "Card 26b" sections | Not run | They need the owner (pictures, camera, voice, lock screen) |

## Card 26d: the speech engine seam, sherpa-onnx in the real build, model packs, open source notices (2026-11-12, JVM and PC only so far)

Nothing was installed on the phone and no adb command was sent. 753 unit tests pass (38 new; 715 before), in both build configurations. No schema change.

| Status | Date | Where | What | Result | Notes |
| --- | --- | --- | --- | --- | --- |
| ✅ | 2026-11-12 | Developer PC (JVM, Robolectric) | `.\gradlew.bat testDebugUnitTest` with `stash.sherpaAar` set (in `local.properties`) | 753 pass, 0 fail, 0 skipped | Includes the real-pack import below |
| ✅ | 2026-11-12 | Developer PC | The same with `-Pstash.sherpaAar=` (no engine; `NoTranscriber`) | 753 pass, 0 fail, 1 skipped (the real-pack test, which needs `STASH_REAL_PACK_ZIP`) | `compileDebugKotlin` ran with `src/nosherpa/` |
| ✅ | 2026-11-12 | Developer PC | `tools/make_model_pack.py` on the card 25 folders in `C:\jeffsource\spike25-models\` | Every file matched; base zip 141,314,584 bytes (sha256 `2de3eaf6...4b0d`), tiny zip 44,257,224 bytes (`d5d90c0a...e11`); entries stored, 4 a pack | The official `.tar.bz2` archives are no longer on the PC; the script also reads them |
| ✅ | 2026-11-12 | Developer PC (JVM) | `ModelImporter` on the real base zip, real `KnownModels` hashes | Installed, 141,313,910 bytes verified, 460 and 658 ms in two runs; `ModelLocator` finds it | `RealPackImportTest` |
| ✅ | 2026-11-12 | Developer PC | A wrong `stash.sherpaAar` path; a file with the wrong hash | Plain messages: "...there is no file there..." at configuration; "...is not sherpa-onnx-2.0.1.aar: its sha256 is ..., expected a8d09d1d..." from `verifySherpaAar` | |
| ✅ | 2026-11-12 | Developer PC | APK sizes (unsigned release; arm64 only from this card on) | Before this card, no engine, all four ABIs: 81,048,163 bytes. After, without the AAR: 50,988,872. After, with the AAR: 82,774,158, so **the engine adds 31,785,286 bytes** (31.4 MB of libraries, stored uncompressed, plus about 0.4 MB of classes and 0.1 MB of compressed licence texts) | The fall from 81.0 to 51.0 MB is `abiFilters` arm64 dropping ML Kit's and CameraX's other-ABI libraries. Debug APKs: before 95,005,522 (spike AAR and ML Kit GenAI, arm64), after with the AAR 103,153,508, without 94,219,198; the debug figures come from incremental packaging and are not comparable |
| ✅ | 2026-11-12 | Developer PC | `zipalign -c -P 16 -v 4` (build-tools 36.0.0) on the release-type APK with the engine | **Verification successful**; `libonnxruntime.so`, `libsherpa-onnx-c-api.so`, `libsherpa-onnx-cxx-api.so`, `libsherpa-onnx-jni.so` and the other four `.so` all OK | Also successful on the debug APK and the release APK without the engine |
| ✅ | 2026-11-12 | Developer PC | Classpaths | No `genai` entry in the debug or release runtime classpath; the release APK holds `com.k2fsa.sherpa.onnx.OfflineRecognizer` and no `app/stash/debug/` class | |
| ⏳ | | S25 Edge | Every "Real phone" check of card 26d | Not run: needs the owner | Steps below |

**Needs the owner (in this order; never uninstall, which would destroy the notes):**

1. **Safety copy first**, as for card 26c: `adb exec-out run-as app.stash cat databases/stash.db > C:\jeffsource\stash-safety-26d\stash.db` (and `-wal`, `-shm` if present). These only read the phone.
2. `.\gradlew.bat installDebug` (the engine is included because `local.properties` names the AAR). Open Stash once. `adb logcat -b crash -d`: nothing from `app.stash`.
3. **Settings.** Settings → "Voice notes" must say "Speech model: none..." and offer "Add the speech model". Nothing native is loaded yet: `adb shell run-as app.stash cat /proc/$(adb shell pidof app.stash)/maps | findstr sherpa` should print nothing.
4. **Import through the PC route.** `python -I tools\make_model_pack.py C:\jeffsource\spike25-models\moonshine-base\sherpa-onnx-moonshine-base-en-quantized-2026-02-27 --push`. It prints `files/debug/model-import.json`: expect `"result": "INSTALLED"`, `"pack": "moonshine-base-en-2026-02-27"`, `bytes` 141313910, `ms`, and `modelsDir` listing the four files and `.verified`. Write the time here.
5. **Flip one byte.** Make a copy of the zip, change one byte inside the decoder (for example with a hex editor at offset 50,000,000), and push it the same way: `adb exec-in run-as app.stash sh -c "mkdir -p files/model-import && cat > files/model-import/pack.zip" < bad.zip`, then `adb shell am broadcast -n app.stash/app.stash.debug.ModelImportReceiver`. Expect `"result": "WRONG_FILE"`, `"file": "decoder_model_merged.ort"`, the base pack still installed and no `.tmp` folder in `modelsDir`.
6. **Import through the file picker.** Copy `moonshine-base-en-2026-02-27.zip` to the phone's Downloads (Explorer over USB). Settings → "Remove" the model, then "Add the speech model", pick the zip. Expect the percentage line, then "Speech model: Moonshine base, 141 MB · Remove" and the licence line. Time it.
7. **The corpus through `Container.transcriber`.** With only this screen open: `adb shell am start -n app.stash/app.stash.debug.TranscriberCheckActivity --ez autostart true`. When it says Done: `adb exec-out run-as app.stash cat files/spike26/transcriber-check/summary.tsv > tc.tsv`. The `text` column (7th) must equal `docs/spike25/results/moonshine-base-t2/summary.tsv` sentence for sentence (compare with `python -I -c "import sys;r=lambda f:{l.split('\t')[0]:l.rstrip('\n').split('\t')[-1] for l in open(f,encoding='utf-8') if l[:1]=='s'};a,b=r('tc.tsv'),r('docs/spike25/results/moonshine-base-t2/summary.tsv');print([k for k in b if a.get(k)!=b[k]] or 'all equal')"`). **Any difference means the configuration was not copied faithfully: stop and say so.** Write `coldLoadMs`, `warmLoadMs`, `peakPssKb`, `pssAfterReleaseKb` and `pssIdleKb` from the `#` lines.
8. **An 8-second note alone:** run step 7 again with `--es ids s01` and write the same numbers (expect a peak of about 350 MB, and after release a figure near the idle one).
9. **Notices at 200% font size.** Phone font size to the largest, Settings → "Open source notices": three entries; open each and scroll to the end.
10. **Aeroplane mode** on for steps 4 to 9 (they need no network).
11. Write the results here.

## Card 26e: long notes cut at real pauses, and the base-or-tiny rule (2026-11-12, JVM only so far)

Nothing was installed on the phone and no adb command was sent. 770 unit tests pass (17 new; 753 before) in both build configurations. No schema change, no new dependency. **No outcome (M1, M2 or M3) yet: it needs the phone run below.** Until then `DefaultPack.DEFAULT_PACK_ORDER` is base then tiny with no length limit, which is 26d's behaviour.

| Status | Date | Where | What | Result | Notes |
| --- | --- | --- | --- | --- | --- |
| ✅ | 2026-11-12 | Developer PC (JVM, Robolectric) | `.\gradlew.bat testDebugUnitTest` with `stash.sherpaAar` set (in `local.properties`) | 770 pass, 0 fail, 1 skipped (`RealPackImportTest`, which needs `STASH_REAL_PACK_ZIP`) | `LongNotesTest` 17 of them |
| ✅ | 2026-11-12 | Developer PC | The same with `-Pstash.sherpaAar=` (no engine) | 770 pass, 0 fail, 1 skipped | `src/nosherpa/` compiled |
| ✅ | 2026-11-12 | Developer PC (JVM, synthetic sound) | `PauseSplitter`: tone = speech, low noise = pause | 19 s: one piece. 49 s with a 0.7 s pause every 6 s: at least 3 pieces, each 9 to 20 s, no forced cut, every cut inside a pause with at least 200 ms of quiet each side, every sample once. 50 s of continuous tone: forced cuts, 300 ms overlaps, every piece at most 20 s. A 0.3 s gap of digital silence at the aim is not used when a 0.6 s pause is in the window. Three minutes: 9 to 20 pieces, none over 20 s | Synthetic only. Says nothing about real speech: that is the phone run |
| ✅ | 2026-11-12 | Developer PC (JVM) | `ChunkedTranscriber` with a fake engine | Joined in order with one space; a two-word repeat dropped once after a forced cut and kept after a natural one; a failure in piece 2 of 3 fails the job and piece 3 never starts; `chunks` = pieces; a one-piece note reaches the engine as the same object | |
| ✅ | 2026-11-12 | Developer PC | Can ONNX Runtime's memory arena be limited through sherpa-onnx 2.0.1? | No: `OfflineModelConfig` (read with `javap` from the AAR's `classes.jar`) has no arena or memory field | Not tried further (card: report, do not depend on it) |
| ⏳ | | S25 Edge | Item 4 table (base and tiny, three lengths, in a WorkManager job), item 6 score, the outcome, the corpus through the wrapper | Not run: needs the owner | Steps below |

**Needs the owner (in this order; never uninstall, which would destroy the notes):**

1. **Safety copy first**, as for 26c and 26d: `adb exec-out run-as app.stash cat databases/stash.db > C:\jeffsource\stash-safety-26e\stash.db` (and `-wal`, `-shm` if present). These only read the phone.
2. `.\gradlew.bat installDebug` (the engine is included because `local.properties` names the AAR). Open Stash once. `adb logcat -b crash -d`: nothing from `app.stash`.
3. **Both packs.** If 26d's steps were not run: `python -I tools\make_model_pack.py C:\jeffsource\spike25-models\moonshine-base\sherpa-onnx-moonshine-base-en-quantized-2026-02-27 --push`. Then the tiny pack the same way: `python -I tools\make_model_pack.py C:\jeffsource\spike25-models\moonshine-tiny\sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27 --push`. Each must print `"result": "INSTALLED"`.
4. **The recording is still there:** `adb shell run-as app.stash ls -l files/spike25/s10.wav` (27.7 s of 16 kHz WAV is about 886 KB).
5. **Six runs, each from a freshly killed process.** For each pack `base`, `tiny` and each length `s10`, `x3`, `3min` (six runs): phone unlocked with the screen on (as card 25's job run), Stash **not** on screen (press Home), then:
   - `adb shell am kill app.stash`
   - `adb shell am broadcast -n app.stash/app.stash.debug.LongNoteMemoryReceiver --es pack base --es length s10` (change `base` and `s10` each time)
   - wait until `adb shell run-as app.stash cat files/spike26/long-notes/base-s10/work.txt` prints `endedAt` (a few seconds for `s10`; allow a minute for `3min`).

   Then `adb exec-out run-as app.stash cat files/spike26/long-notes/table.tsv` (numbers only). In every row `processAgeMs` must be a few thousand at most: a larger figure means the kill did not take; repeat that run. If a run's `result` is `FAILED_OUT_OF_MEMORY` or the process died (no row, no `endedAt`), write that down: it is a result.
   For comparison with card 25's 418 MB, one extra run uncut: `--es pack base --es length s10 --ez uncut true --es tag base-s10-uncut`.
6. **Write the table here:** length, pieces, `peakPssKb` in the job, `procMs` and `wallMs`, for base and tiny, plus `pssIdleKb`, `availMemMb` and `totalMemMb`. A cheaper phone: the peak is the figure that carries over (the model is the same size everywhere); compare it with that phone's free memory. If an older or cheaper phone is to hand, repeat step 5 on it: worth more than this estimate.
7. **Score the ramble (item 6).** In `cmd` (PowerShell's `>` changes the encoding): `mkdir docs\spike25\results\moonshine-base-26e-cut` then `adb exec-out run-as app.stash cat files/spike26/long-notes/base-s10/summary.tsv > docs\spike25\results\moonshine-base-26e-cut\summary.tsv`, then `python -I docs\spike25\score.py --detail moonshine-base-t2 moonshine-base-26e-cut`. Write s10's words wrong uncut (card 25: 7 of 48) and cut, and the difference. Only those numbers go in this log, never the texts.
8. **The outcome.** Put base's three background-job peaks (in MB) and the difference from step 7 into `DefaultPackRule.decide` (for example in a scratch unit test), with `MemoryGuide.HARD_LINE` as the owner decides (question 5; `false` today). Write "M1", "M2 (L = … s)" or "M3" here, in those words. Then set `DefaultPack.MEASURED`, `DEFAULT_PACK_ORDER` and `BASE_UP_TO_MS` to the decision, and `KnownModels.BASE.workingSetBytes` / `TINY.workingSetBytes` to the measured `peakPssKb - pssIdleKb` of the `s10` runs, rounded up. For M2, Settings must also say both packs are needed (not built here).
9. **The corpus through the wrapper.** `adb shell am start -n app.stash/app.stash.debug.TranscriberCheckActivity --ez autostart true`, then compare as in 26d's step 7. The 14 sentences other than s10 must give exactly 26d's texts, with `segments` 1. s10 (27.7 s) is now cut: write its `segments` (expect 2) and leave its text to step 7's score.
10. Aeroplane mode on throughout (nothing here needs the network).

## Card 26f: the transcription pass, retries, kill detection, and check C1 (2026-11-12, JVM only so far)

Nothing was installed on the phone and no adb command was sent. 805 unit tests pass (35 new; 770 before) in both build configurations. No schema change, no new dependency. **Check C1 has not been run: no outcome yet.** Until it is, `TranscriptionWorkShape.OUTCOME` is J1 (expedited job, no charging constraint), as the card says to start.

| Status | Date | Where | What | Result | Notes |
| --- | --- | --- | --- | --- | --- |
| ✅ | 2026-11-12 | Developer PC (JVM, Robolectric) | `.\gradlew.bat testDebugUnitTest` with `stash.sherpaAar` set (in `local.properties`) | 805 pass, 0 fail, 1 skipped (`RealPackImportTest`, which needs `STASH_REAL_PACK_ZIP`) | `TranscriptionJobTest` 25, `TranscriptionPolicyTest` 10 |
| ✅ | 2026-11-12 | Developer PC | The same with `-Pstash.sherpaAar=` (no engine) | 805 pass, 0 fail, 1 skipped | `src/nosherpa/` compiled |
| ✅ | 2026-11-12 | Developer PC (JVM, fake transcriber and decoder, real Room and encrypted files) | The card's unit checks | All pass: written out, corrected, extraction asked once; back-off then success; GAVE_UP at the fifth failure with the recording kept; no model stays PENDING with no attempt and is written out after `requestNow()`; unreadable and undecodable files GAVE_UP at once, others done; two background kills, then only a foreground pass; two foreground kills GAVE_UP; "again" makes an offer; two callers enter the engine once; store not available retries and changes nothing; Private written out and nothing reaches the outbound gate; `release()` only after real work; WAV compacted | Fakes only. Says nothing about Samsung's job scheduling: that is C1 |
| ✅ | 2026-11-12 | Developer PC (the live WorkManager page, "Define work requests") | Does an expedited `CoroutineWorker` need a notification on Android 12 and later? | No: the foreground service and its notification are only for "platform versions older than Android 12"; minSdk is 35 | No `getForegroundInfo()`, no notification, no service |
| ⏳ | | S25 Edge | With the app open: save the 8-second recording, time from `AUDIO STORED` to `TRANSCRIBE OK` | Not run: needs the owner | Step 2 below |
| ⏳ | | S25 Edge | **Check C1** (screen off): J1, J2 or J3 | Not run: needs the owner | Step 3 below |
| ⏳ | | S25 Edge | Reboot with a note PENDING; remove and re-add the pack; battery setting | Not run: needs the owner | Steps 4 to 6 below |

**Needs the owner (in this order; never uninstall, which would destroy the notes).** The debug tool saves corpus recordings as **real notes** among his notes, through the same path card 26g will use. Delete them from the Notes list at the end (step 7). The files it writes hold numbers, names and row ids only.

1. **Prepare.** Make a safety copy as for 26c to 26e: `adb exec-out run-as app.stash cat databases/stash.db > C:\jeffsource\stash-safety-26f\stash.db` (and `-wal`, `-shm` if present). Then `.\gradlew.bat installDebug` (the engine is included because `local.properties` names the AAR). Open Stash once. The base pack must be installed (26d's steps; Settings, Voice notes says "Speech model: Moonshine base"). Check the corpus is still there: `adb shell run-as app.stash ls -l files/spike25/` (s10.wav is the 27.7 s ramble, about 886 KB). **Use wireless adb and unplug the cable for steps 3 and 4**: a phone on USB is charging, and charging changes what Samsung allows.
2. **With the app open.** Stash on screen (the Notes tab). Pick the corpus sentence of about 8 seconds, `sNN.wav` (the report prints `durationMs`, so a wrong guess shows):
   - `adb shell am broadcast -n app.stash/app.stash.debug.TranscriptionCheckReceiver --es step save --es file spike25/sNN.wav`
   - The new note shows "Saved with its recording. Not written out yet." for a moment, then its words.
   - `adb shell am broadcast -n app.stash/app.stash.debug.TranscriptionCheckReceiver --es step report`, then `adb exec-out run-as app.stash cat files/debug/transcribe-report.json`.
   - Write down `okAfterMs` (expect about a second) and `by` (must be `foreground`). Compare the words on screen with the corpus text by eye; do not copy them here.
3. **Check C1, the screen off.** Phone unlocked, battery not charging, Stash on screen, then:
   1. `adb shell am broadcast -n app.stash/app.stash.debug.TranscriptionCheckReceiver --es step arm --ei delay 60` (saves `spike25/s10.wav` from an alarm 60 seconds later).
   2. Press Home at once (do not swipe Stash from recents). Within the minute, press the power button. Do not touch the phone.
   3. Wait three minutes (the minute until the save, then the card's two minutes).
   4. Unlock. **Do not open Stash** (opening it starts a foreground pass, which would finish the note in the job's place).
   5. `adb shell am broadcast -n app.stash/app.stash.debug.TranscriptionCheckReceiver --es step report`, then `adb exec-out run-as app.stash cat files/debug/transcribe-report.json` and `adb exec-out run-as app.stash cat files/debug/transcribe-check.json`.
   6. **How to read it.** In the save line, `screenOnAtSave` must be `false` (if it is `true`, the screen was not off when the note was saved: repeat). Also note `standbyBucket` (10 active, 20 working set, 30 frequent, 40 rare, 45 restricted) and `ignoringBatteryOptimizations`. In the report's line for that note:
      - `startedAfterMs` is the delay from stored to started;
      - `okAfterMs` says whether it finished (null: it did not);
      - `by` must be `job`;
      - `killed` is the number of `KILLED` events.

      `python tools\peek.py events 40` shows the same events (`AUDIO STORED`, `TRANSCRIBE STARTED` with n2 = 1 for the job, `TRANSCRIBE OK`, any `KILLED`). Peak memory is not logged by the job; 26e's run gives it.
   7. Repeat 1 to 6 three times. Then once more after the phone has been left alone, screen off, for 30 minutes (Samsung's "sleeping apps"): arm with `--ei delay 1800`, press Home and power at once, leave it 33 minutes, then 4 to 6.
   8. **The answer.** The report's `reading` is the worst run so far:
      - J1: every run finished by the job, started within 90 s;
      - J2: all finished, but some late (write the delays seen);
      - J3: any killed, or not finished after the wait (write what was seen: `KILLED`, or no `STARTED` at all).

      If the runs differ, take the worst, as the card says. Write "C1: J1", "C1: J2 (delays …)" or "C1: J3 (…)" in this log. Then set `TranscriptionWorkShape.OUTCOME` in `app/src/main/java/app/stash/pipeline/TranscriptionPolicy.kt` (the one constant) and run the tests. Under J3, card 26e's pack choice for background work is to be revisited (not built).
4. **Reboot with a note waiting**, in aeroplane mode, the pack installed. Press Home (Stash not on screen), then in one line: `adb shell am broadcast -n app.stash/app.stash.debug.TranscriptionCheckReceiver --es step save ; adb reboot` (s10, the longest, so the job is least likely to finish first). After the boot, unlock, **do not open Stash**, wait two minutes, and run the report.
   - `okAfterMs` present, with the boot between saving and finishing: written out after boot without opening the app.
   - No `okAfterMs`: open Stash, run the report again. If it is there now, it was written out only when Stash was opened (expected under J2 or J3).
   - It finished before the reboot (the job was quicker than the reboot): write that down and try once more.
5. **Remove the model, save, add it back.** Settings: Remove the speech model. Save a note (`step save`). The row says "Not written out yet: needs a speech model". Add the pack again (Settings' file picker, or `make_model_pack.py --push`). Without any other action the note gets its words. The report's line has `okAfterMs` after the import.
6. **Battery setting.** Settings, Apps, Stash, Battery: write down "Unrestricted", "Optimised" or "Restricted" as it was during C1. If time allows, change it and repeat one C1 run; write whether the answer changed.
7. **Clean up.** Delete each note the tool saved (Delete on its row). They are ordinary notes and go through the usual 30-day bin.


## Card 07, pass 1: readings, flags, the coded answers and a plain Readings tab (2026-11-12, JVM only so far)

Built and tested on the JVM only. Nothing was installed on the phone and no adb command was sent. 855 unit tests pass (50 new) with the sherpa AAR configured and with `-Pstash.sherpaAar=` (empty). No schema change, no new dependency, no network code. Pass 2 (Things tab, subject page, chart, rhythm, CSV, edit and remove) is not built.

| Check | Result | Status |
| --- | --- | --- |
| Outlier: history 7.4, 7.6, 7.2 flags 72 and not 7.8; under 3 earlier values and a zero median never flag; exactly 5 times and exactly one fifth do not flag | as stated | pass (JVM) |
| Pool sentence "Pool test: free chlorine 5, pH 7.2, alkalinity 70" stored as three reading rows (values as said, no unit invented, one subject) | as stated | pass (JVM) |
| "The Tide is on sale for $24.99 at Costco": a reading of "price" for Tide with its place; answered "Tide was $24.99 at Costco, three weeks ago." | as stated | pass (JVM) |
| Unit drift: the first unit heard becomes the metric's unit; another unit flags `UNIT_CHANGED`; the stored values are not changed | as stated | pass (JVM) |
| Answer wording: one value, two values, a word value, a session, a price with a place, a name under two subjects; no word of judgement | as stated | pass (JVM) |
| Question patterns with negatives ("what was my plan last time" matches only once a metric "plan" exists) | as stated | pass (JVM) |
| `findMetric`: "ph", "pH", "free chlorine", an alias, one name under two subjects | as stated | pass (JVM) |
| Private readings are returned only with `includeSensitive = true`; a public reading is never flagged against Private history; no app code passes true | as stated | pass (JVM) |
| Readings tab: metrics with latest values, a metric's value list with dates, place and flag, empty and Private cases | as stated | pass (JVM, Robolectric) |
| Spoken pool sentence three times, "pH 72" flagged, the Readings tab and the coded answer on the phone; what the recogniser does with decimals (R12) | not run | needs the owner |

## Card 08, pass 1: the router, read-only lookups, plain-search fallback, the answer trace, migration 4 to 5 (2026-11-12, JVM only so far)

Built and tested on the JVM only. Nothing was installed on the phone and no adb command was sent. No new dependency, no network code. Schema version 5 (`answer_trace`). Pass 2 (the tool loop, read aloud) is not built.

| Status | Date | Where | What | Result | Notes |
| --- | --- | --- | --- | --- | --- |
| ✅ | 2026-11-12 | Developer PC (JVM, Robolectric) | `.\gradlew.bat testDebugUnitTest` with the sherpa AAR configured (local.properties) | 902 tests, 0 failures, 1 skipped (47 new; 855 before) | The skipped one is `RealPackImportTest`, which needs `STASH_REAL_PACK_ZIP`, as before |
| ✅ | 2026-11-12 | Developer PC (JVM, Robolectric) | `.\gradlew.bat testDebugUnitTest -Pstash.sherpaAar=` (no engine) | 902 tests, 0 failures, 1 skipped | Same skip |
| ✅ | 2026-11-12 | Developer PC (JVM, Robolectric) | Migration 4 to 5 on a real version-4 file built from the committed `4.json`: seven notes (placement with subject, three pool readings, a Private pinned task with revision and nudge, a shared note with two `PHOTO` attachments, a voice note with its `AUDIO` recording and two `transcription` rows, a recording waiting for words, a note in the bin), a tombstone, an event, a miss, the word list, a correction; every encrypted file real | Version 5. All eighteen existing tables identical cell for cell, keys included. `answer_trace` empty with its index. Search, the pictures, the recording and the consistency check all clean; nothing queued. The migrated database then answered three questions and kept three traces | Room's own schema check on open passed. The two statements equal `5.json`, and `5.json` differs from `4.json` only by `answer_trace` (test) |
| ✅ | 2026-11-12 | Developer PC (JVM) | Router table: 42 questions route (the owner's three among them), 13 must not | as stated | `AskRouterTest` |
| ✅ | 2026-11-12 | Developer PC (JVM, Robolectric) | Each lookup on a seeded in-memory database; `includeSensitive = false` never returns a Private note or a note about a Private subject; `searchNotes(place = "Total Wine", hasPicture = true)` and by place with "two to three weeks ago" | as stated | `AskLookupsTest` |
| ✅ | 2026-11-12 | Developer PC (JVM, Robolectric) | One trace per question on every path (routed, loop seam with a fake, search, nothing, loop gave up, an exception); purge of a source blanks the words; "That's not it" logs `MARKED_WRONG`; nothing found logs `NO_MATCH`; newest 300 kept; no app code passes `includeSensitive = true` (except card 05's `TaskHook`) | as stated | `AskEngineTest`, `AskScreenTest` |
| ⏳ | | S25 Edge | Install over the existing app; the migration 4 to 5 on his real notes | Not run: needs the owner | Steps below |
| ⏳ | | S25 Edge | The owner's three questions by voice, answered by code with sources; the same in aeroplane mode; a question about something never noted gives "I don't have a note about that."; "Why this answer?" and "That's not it" | Not run: needs the owner | The loop and read-aloud checks wait for pass 2 |

**Needs the owner (in this order; never uninstall, which would destroy the notes):**

1. **Safety copy first.** This migration adds a table to the database that holds his notes.
   1. `python -I tools\peek.py notes > notes-before-08.txt`
   2. `mkdir C:\jeffsource\stash-safety-08`
   3. `adb exec-out run-as app.stash cat databases/stash.db > C:\jeffsource\stash-safety-08\stash.db`, and the same for `stash.db-wal` and `stash.db-shm` if they exist. All three commands only read the phone.
   4. Check that the copy is not empty: `stash.db` should be a few hundred KB.
2. `.\gradlew.bat installDebug` from this PC. It uses the same debug key, so it installs over the app. Then open Stash once.
3. After:
   1. `python -I tools\peek.py notes > notes-after-08.txt`, and compare it with the "before" file: the same notes and the same words.
   2. `python -I tools\peek.py dump 5`: the first line must say `database version 5`, and `answer_trace=0` in the row counts.
   3. `adb logcat -b crash -d`: nothing from `app.stash`.
4. On the Ask tab: ask by voice "What are the things I said I should handle?", "What was my pH last time?" and "Where did I put my glasses?". Each should be answered with no model mark and a "From these notes:" list. Tap **Why this answer?** on one. Turn on aeroplane mode and ask one again. Ask about something never noted: "I don't have a note about that."
5. `python -I tools\peek.py dump 5` again: `answer_trace` now has one row per question. `python -I tools\peek.py events 20`: `ASK ASKED` rows with numbers only.
6. Write the results here.

### Phone check after cards 26d to 08 (2026-11-12, S25 Edge, wireless adb)

Second whole-build install: everything from cards 26d (speech engine), 26e, 26f, 07 and 08 over the existing app, with a safety copy first (`C:\jeffsource\stash-safety-08\`).

| Status | What | Result | Notes |
| --- | --- | --- | --- |
| ✅ | In-place `installDebug`, app opened | 10 notes before, 10 after; text and ids identical (`diff` of `peek.py notes`) | Same signing key; no uninstall; no `app.stash` entry in the crash log |
| ✅ | Schema 4 to 5 migration on the real database | `peek.py dump`: `database version 5`; `answer_trace` present and empty; `transcription` present and empty | |
| ✅ | Attachment and recording store self-tests rerun on the new build | see the figures logged in the line that follows this table | |
| ⏳ | Everything else (Moonshine pack import and engine, transcription job C1, recorder C2, audio format C3, readings, Ask, pictures and camera) | Not run | Need the owner; the per-card steps are in each "Card NN" section |

## Guided phone check, 2026-11-13 (owner on the S25 Edge, one step at a time)
- Ask "what should I handle": 3 tasks, "Why this answer?" correct (card 08 pass). Ask "where are my glasses": honest "no note", trace says "where glasses is" (grammar polish).
- Typed pool notes -> Readings: chlorine/pH/alkalinity grouped as Pool, 2nd note gave "2 values", history newest first (card 07 pass).
- Typed-note "Saved" label stayed on screen like a button. FIXED in code (clears after 2.5 s), not yet installed.
- Add a picture (camera and gallery) works; 2 encrypted files, no crashes (16b/16c/16e pass).
- Picture text (16f) FINDING: photo of a candle jar; the jar's own label was NOT read, but "Non-Paged Pool / Paged Pool" from a monitor at the edge WAS. Owner's rule: the subject sits near the centre; text on or with the subject is first tier; background text at the edges matters less. TODO (new card 16m): rank/label picture text by distance from centre and size, show first-tier text first, keep edge text as lower tier; and look at why the jar text (probably stylised/low-contrast) was missed.
- Picture text, more findings (owner): Home Assistant screengrab read only the first lines (reader stopped early; display shows everything it returned, cap 4000 chars not hit). Water-analysis page mostly right, small words clipped ("spee", "qualt", "suitabity"). Candle jar curved/embossed text garbled ("HAN ND po URED", "BURN TIMI s6 HCUE RS APpnOX"). Typical ML Kit behaviour on small/stylised text. TODO 16m: log lines found vs kept; consider reading at the full sensor size and a second pass on the centre crop.
- Camera BUG found: preview is cropped to the tall screen but the saved JPEG was the whole sensor frame (wall and fingers included). FIXED in CameraXEngine.cropToPreview (uses ImageProxy.cropRect), not yet verified on the phone.
- Camera crop FIXED and verified by owner (2026-11-13): saved picture now cut to the preview's own shape (CameraXEngine.cropToPreview, view aspect, rotation-aware). Flash now cycles off/auto/on (owner asked); auto did not fire in a dim room (phone's judgement). "Saved" label on typed notes now clears after 2.5 s (installed, not yet watched).
- Guided check, later 2026-11-13: share-screenshot (16c) pass; FLAG_SECURE pass; picture removal pass (rows == files); typed + spoken notes work; Readings update after "Read it again". Gaps found and FIXED: correcting a word now re-reads the note; one-off fixes to everyday words or numbers no longer become permanent rules (the owner's "for -> 4" rule was removed by hand); typed "Saved" clears; debug screen titles "[Notes]" etc. OPEN: the Microsoft SwiftKey mic (hold comma) typed nothing into "Type a note" (works in Textra; not caused by FLAG_SECURE, debug flag was on). Retest with the Samsung keyboard. Debug settings screen has no way back (add a Back button).
- CHECK C2 PASSED (owner, 2026-11-13, S25 Edge): our own recorder (RecorderCheckActivity) recorded from the LOCKED phone: keyguard locked at start true, MicHealth OK, loudest 16503, heard speech, ended by STOP_PAUSE, verdict KEEP. So a locked quick note can keep its recording; D84's no-recording fallback is not needed for locked phones. Unlocked run: 2.92 s, ended by STOP_TAPPED, then auto-ended by pause as designed. FINDING: with keys being tapped nearby the pause rule took 20.9 s to end (noise counted as speech); consider a noise-floor adjustment before default-on.
- CHECK C3 PASSED (owner, 2026-11-13): Opus 12 kbps samples s07.ogg and s13.ogg "completely comprehensible". Audio format default stays Opus 12 kbps.
- CHECK C1 (owner, 2026-11-13, S25 Edge, wireless adb, not charging, Moonshine base installed, bucket 10 active): screen-on foreground-timing run: s07 (7.6 s) stored, job started 591 ms, written out in 2.7 s. SCREEN-OFF run: s10 (27.7 s) armed 60 s, screenOnAtSave=false, lockedAtSave=true, job started 583 ms after store, finished 3.5 s, killed 0, failed 0, by=job. READING: J1 (OUTCOME constant already J1). Only ONE screen-off run so far (card asks for three plus a 30 min idle run): repeat later. Words for s07: "...Italian section row five, pocket ten" vs said "row 5, bucket 10". Model pack: moonshine-base-en-2026-02-27 installed 2026-11-13 via tools/make_model_pack.py --push (INSTALLED, Ready).
- ALL THREE GATE CHECKS (C1, C2, C3) NOW PASSED: card 26g (capture screen switch-over) is unblocked. Two test notes saved by the debug tool (s07, s10 corpus) are in the Notes list: delete them.

## Card 26g: the capture screen records, the quick-note route, the one switch (2026-11-13, JVM only so far)

Nothing was installed on the phone and no adb command was sent. 953 unit tests pass (51 new; 902 before) in both build configurations. No schema change (still version 5), no new dependency. Route R1 is built in (`VoiceRoute.LOCKED_MIC_DEFAULT = OK`), from check C2 above. **Every phone row below is not run.**

| Status | Date | Where | What | Result | Notes |
| --- | --- | --- | --- | --- | --- |
| ✅ | 2026-11-13 | Developer PC (JVM, Robolectric) | `.\gradlew.bat :app:testDebugUnitTest` with `stash.sherpaAar` set (in `local.properties`) | 953 pass, 0 fail, 1 skipped (`RealPackImportTest`, which needs `STASH_REAL_PACK_ZIP`) | `RecordingSessionTest` 21, `VoiceRouteTest` 19, `RecordedNoteFlowTest` 6, 5 new in `RecorderLogicTest` |
| ✅ | 2026-11-13 | Developer PC | The same with `-Pstash.sherpaAar=` (no engine) | 953 pass, 0 fail, 1 skipped | Without the engine every voice note takes the quick route (`NoTranscriber` says "no model") |
| ✅ | 2026-11-13 | JVM | `VoiceRoute.choose`: all 64 combinations of switch, engine state (ready, no model, switched off, load failed), locked, locked microphone and the lock setting, and each row of the card's table by name | As item 3's table; first match wins; with the default setting no combination asks to unlock or refuses a note | |
| ✅ | 2026-11-13 | JVM, fake recorder and codecs, real `StreamingEncoder` | `RecordingSession`: Done; Cancel; the pause rule; the three-minute limit and its warning at 2:45; leaving the screen; Open Stash; a microphone that stops part-way | Done saves once and the save cue comes after the sink returned Saved, never before; a second Done or a late pause does nothing. Cancel saves nothing, releases the recorder, aborts the encoder. Each of the others saves | The order checked is: stop recorder, save, cue, state, ask for writing out |
| ✅ | 2026-11-13 | JVM | An empty recording (loudest sample 5); 25 s of faint sound; 25 s of ordinary room noise with no speech | Empty: nothing saved, no cue. Room noise: **saved** (26a's "when in doubt, keep it") | See the card's "What differs": Done without speaking will usually save a short note on the phone |
| ✅ | 2026-11-13 | JVM | An encoder whose finish hangs; an encoder that cannot be started | Both end in a WAV save holding every sample | |
| ✅ | 2026-11-13 | JVM | A failed save, then "Save again" twice; a save that throws; "Throw this recording away?" | The bytes are kept; "Save again" sends the same bytes (the same array); the failure cue plays; discard only from the failed state | |
| ✅ | 2026-11-13 | JVM | `MicHealth BLOCKED` after 1.5 s of zeros, locked and unlocked; a recorder that will not start, locked, unlocked, and for a missing permission | Switches to the quick note every time; the recorder is released; "locked microphone blocked" is remembered only when locked, and never for a missing permission | |
| ✅ | 2026-11-13 | JVM, Robolectric (real saver, sink, Room, encrypted files) | Private pressed while recording; a note whose words begin "private note"; a plain note; a note that gave up; an undone note; a full disk then "Save again"; Cancel | Private: the capture is inserted `sensitive`, and when its words arrive the screen's watch is handed "Private" with no text. Plain: "Saved" then "Saved: I'm putting my reading glasses in the nightstand drawer". Cancel: no row, no file | Before the cue nothing is in the database or on disk; after it the capture is `PENDING` with extract `DONE` and one `AUDIO` attachment |
| ✅ | 2026-11-13 | JVM | Switch off; no model (three ways); switch on with a model | Off or no model: the fake `SpeechEngine` is started once and the recorder is not. On with a model: the recorder, not the engine | Through `VoiceStarter`, which the screen uses for every note |
| ✅ | 2026-11-13 | JVM | Card 13's "every capability off" test; and the route with every capability off | Passes unchanged; the route is the quick note | |
| ✅ | 2026-11-13 | JVM | The Notes list line for a recorded note with no words | "Voice note, 0:12 · Writing it out…", "· Not written out yet", "· I couldn't write this one out", "· No words heard"; never blank for any state | |
| ✅ | 2026-11-13 | JVM, synthetic sound | The level rule with taps: a one- or two-chunk click every half second during the pause; taps alone for 30 s; a 60 ms sound; a word starting 40 ms before the pause limit | The note ends at exactly 2.5 s as if the taps were not there; taps alone are never "heard speech"; 60 ms counts and restarts the pause; the word is not cut off | `EndpointTuning.MIN_SPEECH_RUN_CHUNKS = 3` |
| ✅ (PC, numbers only) | 2026-11-13 | Developer PC, the 15 corpus recordings in `docs/spike25/audio` | The level rule with and without the 60 ms rule: the longest gap it sees inside speech | 1.58 s becomes 1.78 s at worst (s10); the others move by 0.12 s or less; all far from 2.5 s. The noise floor is at its minimum (0.0005) in all 15, so the absolute level decides. s15's last "speech" was one loud chunk after the words; it no longer counts | A throwaway script; no recording's content was printed |
| ⏳ | 2026-11-13 | S25 Edge | Unlocked: "I'm putting my reading glasses in the nightstand drawer". Time from the end of speech to the tone; the save part (bar: under 500 ms); the words appear on the screen; a record in Stash; `AUDIO/STORED` before `TRANSCRIBE/STARTED` | Not run: needs the owner | Step 2 |
| ⏳ | 2026-11-13 | S25 Edge | Locked, from the notification and from the tile: recorded (R1), and a note is saved every time | Not run: needs the owner | Step 3. Also shows whether the 0.8 s wait before a leave-the-screen save is needed |
| ⏳ | 2026-11-13 | S25 Edge | A two-minute ramble with pauses: saved whole, written out whole | Not run: needs the owner | Step 4 |
| ⏳ | 2026-11-13 | S25 Edge | Aeroplane mode: all of the above | Not run: needs the owner | Step 5 |
| ⏳ | 2026-11-13 | S25 Edge | "Private note the gate code is 4 4 7 1": the words are not drawn; Private in Stash. Once more with the Private button pressed while recording | Not run: needs the owner | Step 6 |
| ⏳ | 2026-11-13 | S25 Edge | Home in the middle of a sentence; the screen turned off in the middle of another: both saved up to that point | Not run: needs the owner | Step 7 |
| ⏳ | 2026-11-13 | S25 Edge | Cancel after five seconds of speech: no note, no new file in `files/att` | Not run: needs the owner | Step 8 |
| ⏳ | 2026-11-13 | S25 Edge | Switch off: the old way, no recording. Model removed with the switch on: the same, with "No recording: the speech model isn't added yet." | Not run: needs the owner | Step 9 |
| ⏳ | 2026-11-13 | S25 Edge | Undo within 5 seconds: the note leaves the list | Not run: needs the owner | Step 10 |
| ⏳ | 2026-11-13 | S25 Edge | Keys tapped nearby while a note is ending: how long does the pause take now (it was 20.9 s) | Not run: needs the owner | Step 11 |
| ⏳ | 2026-11-13 | S25 Edge | Done pressed without speaking, in an ordinary room: "No note made", or a short saved note? The loudest sample of a silent room (26a's step 3) sets `EMPTY_PEAK` | Not run: needs the owner | Step 12 |
| ⏳ | 2026-11-13 | S25 Edge | The app's idle memory with all libraries loaded (D83 asks for it before recording is the default) | Not run | Step 13 |
| ⏳ | after a day | Owner | Does not seeing the words while speaking bother him? His words, written down | Not asked yet | Step 14 |

### Phone steps for the owner (nothing here has been run)

Give these **one at a time**, and read the phone over adb after each before giving the next (the owner's rule). The builder was told the notes database had been backed up before this card was built (the handoff names `C:\jeffsource\stash-safety-26g\stash.db`; check the file is there before installing). Install over the top with `installDebug`, never uninstall.

1. **Before anything else.** Install. Open Stash, Settings: "Keep a recording of each voice note" is in "What Stash may use" and is on; "Speech model: Moonshine base" is shown; under "Voice notes" there is "End a voice note after a pause of" with 2.5 seconds chosen.
2. **Unlocked.** Tap Add a note. The screen says "Recording...", a level bar moves, a clock counts, and "Take your time." goes away when he speaks. No words appear. Say "I'm putting my reading glasses in the nightstand drawer" and stay quiet. About 2.5 s later: the tone, "Ready", "Saved" drifting, then "Saved: I'm putting my…" a moment later, and (the first time only) "Stash now keeps a recording of each voice note. You can turn that off in Settings." Then `python tools\peek.py events 20`: `CAPTURE STARTED` with n1 = 0, `AUDIO RECORDED`, `AUDIO ENCODED` (its ms is the encoder's finish), `AUDIO STORED`, `CAPTURE SAVED`, then `TRANSCRIBE STARTED` and `OK`. The save part is the time from `AUDIO RECORDED` to `CAPTURE SAVED` (bar: under 500 ms). In Stash the note has its words and a record under it.
3. **Locked.** Lock the phone. From the notification's action, without unlocking: the same as step 2, and "Phone is locked. Nothing else is shown here." at the bottom. Repeat from the Quick Settings tile. Both must save a note with a recording (`CAPTURE STARTED` n1 = 0, n2 = 1). If instead the screen says "Say that again, please." and listens the old way, the locked recorder was blocked this time: write it down; Settings will then show "Try recording on the lock screen again". **If a note ends by itself in its first second on the lock screen, or never starts, write exactly what the screen did**: that is the lifecycle question the 0.8 s wait is there for.
4. **A two-minute ramble with pauses** of a second or two. It must not stop early. The clock runs; Done ends it. In Stash the whole ramble is written out. (Run to the end once without Done: at 2:45 "Stops at 3:00", at 3:00 the tone and "Stopped at 3:00. Saved.")
5. **Aeroplane mode on.** Repeat steps 2 and 3. Nothing may differ.
6. **Private.** Say "Private note the gate code is 4 4 7 1". The capture screen shows "Saved" and never the words; in Stash the note is Private. Then once more: tap Private while recording, say anything: "Private. Its words are not shown." while recording, "Saved" after, Private in Stash.
7. **Leaving in the middle.** Start a note, say half a sentence, press Home. Open Stash: the half sentence is there. Again, pressing the power button instead of Home. Again with the back gesture. The tone may not sound when the screen is not visible; the note must be saved all the same.
8. **Cancel.** Before: `adb shell run-as app.stash ls files/att | find /c /v ""`. Start a note, speak for five seconds, tap Cancel. No note in Stash, and the same file count after.
9. **The switch and the model.** Settings: turn "Keep a recording of each voice note" off. Add a note: "Listening...", the words appear as he speaks, exactly as before this card; the note has no recording (`CAPTURE STARTED` n1 = 1). Turn it on again. Then Settings, Remove the speech model, and add a note: the old way, with the line "No recording: the speech model isn't added yet." (n1 = 2). Add the model again.
10. **Undo.** Add a note, tap Undo within five seconds: it is not in the Notes list.
11. **Keys tapped nearby.** Start a note, say a sentence, then type on a keyboard next to the phone as in check C2. Time from the last word to the tone. It was 20.9 s. Write the new figure. If it is still long, the taps ring for more than 40 ms on this microphone and the rule needs a real recording to tune against.
12. **Done without speaking.** In an ordinary room, tap Add a note and then Done at once. Write what happened: "No note made", or a saved "Voice note, 0:01 · No words heard". Then run 26a's step 3 (25 s of silence in `RecorderCheckActivity`) and write the loudest sample: `RecordingVerdict.EMPTY_PEAK` (40 now) is set from it.
13. **Idle memory** (D83): with Stash open on the Notes list and no note being written out, `adb shell dumpsys meminfo app.stash` and write the TOTAL PSS.
14. **After a day of real use:** ask whether not seeing the words while speaking bothers him, and write his words here. If it does, the switch gives him the old behaviour at once.
