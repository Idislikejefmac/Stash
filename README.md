# Stash (working name): a capture-anything voice inbox for Android

"I have to remember to clean out the back of the car soon." … "Pool test: free chlorine 5, pH 7.2, alkalinity 70." … "I'm putting my reading glasses in the nightstand drawer." … "The Tide is on sale for $24.99." … later: "What should I handle?", "What was my pH last time?", "Where are my glasses?", "When did I last go out?", "What was that bottle at Total Wine a couple of weeks ago?"

One action from a locked phone starts a voice note. The words are saved on the phone at once and kept for good. Afterwards the app works out what was said (a placement, a loan, a task, a reading, an event, a fact, an idea), possibly several things from one note, and makes it findable; things to do come back as a gentle daily "You have 3 things you wanted to do". He never files or checks anything. Local first: no server, speech to text on the phone, an optional cheap hosted model that sees only note text and never anything marked Private.

**Scope (2026-11-11):** the owner's own phone only (Samsung Galaxy S25 Edge, Android 17), installed with `adb`, no Play developer account, no spend except an optional $5 of API credit (docs/QUESTIONS.md Q17). In development the owner grants every permission; every optional ability is nevertheless built behind its own switch so that lighter tiers are possible later. The first design's three-household plan is deferred, not dropped (`docs/archive/`, `docs/cards/archive/`).

**Status (2026-11-11): card 01 (data model, migration, foundations) is done** and installed on the owner's phone with his notes carried over (voice capture after the upgrade still to be confirmed by him); the signing key (Q38) and encryption (Q37) are deferred by the owner. **Card 03 (extraction core: record shape, private-note check, checks, due dates, projection, subject matching, offline rules v2, and a safe `ExtractionRunner`) is built and unit-tested (246 tests) but not yet called from the app; the phone was not connected, so no phone check was made.** **Card 13 (capture pipeline: one way in, save first, background extraction with WorkManager, private notes, capability switches, outbound register, event log) is built and unit-tested (284 tests in all) but not yet installed or checked on the phone.** **Card 05 (tasks: lifecycle, closing by speaking, duplicates, daily upkeep, the "To handle" tab, and the "what should I handle" question) is built and unit-tested (348 tests in all) but not yet installed or checked on the phone.** Next: the phone checks in the cards, then 04 (15 is the optional SQLCipher spike). Before that, a thin prototype existed in `app/` and has been used on the owner's phone. It captures by notification, tile and in-app button; transcribes with Android's on-device recogniser; applies a personal word list; structures a note with simple offline rules or an optional hosted model; and answers by keyword search. Nothing from the three revisions is built yet. **The database is unencrypted, there is no app lock, private-note handling is not built, and a reinstall from another PC would wipe the notes: read docs/PRIVACY.md ("The prototype today") before putting anything sensitive in it.** The owner test script at the end of this file has still not been written up in the experiment log.

Revisions 2 and 3 added designs for trips and a leaving list, pictures and scanned codes, watches, diagnostics, a storage decision, capability switches and tiers, a choice of model provider, and one Private mark. **Start with [docs/PLAN_REVIEW.md](docs/PLAN_REVIEW.md)**: it says what changed, why, and what to do this week.

## Documents

| File | What it is |
| --- | --- |
| [docs/PLAN_REVIEW.md](docs/PLAN_REVIEW.md) | **Read first.** An independent review of the whole plan: what changed and why, contradictions fixed, risks re-ranked, the first week |
| [REVISION_2_BRIEF.md](REVISION_2_BRIEF.md), [REVISION_3_ADDENDUM.md](REVISION_3_ADDENDUM.md) | The briefs for the latest revisions. [REVISION_1_BRIEF.md](REVISION_1_BRIEF.md) and [BRIEF_FOR_FABLE.md](BRIEF_FOR_FABLE.md) are the earlier ones |
| [docs/DESIGN.md](docs/DESIGN.md) | Scope, threat model, architecture, data model, the model design, search, reminders; and (§16–23) capabilities and tiers, trips, pictures and codes, diagnostics, watches, sensitive content, providers, storage |
| [docs/STORAGE.md](docs/STORAGE.md) | The storage decision record: what to decide now, what can wait, when to encrypt |
| [docs/PROVIDERS.md](docs/PROVIDERS.md) | The three tiers, every feature by tier and permission, model providers compared, the conformance set |
| [docs/EXPLANATION_CARDS.md](docs/EXPLANATION_CARDS.md) | The wording shown beside every switch, and the "What leaves my phone" screen |
| [docs/PRIVACY.md](docs/PRIVACY.md) | Plain-language account of where notes live, what leaves the phone and under what terms, and what cannot be promised |
| [docs/FEATURES.md](docs/FEATURES.md) | Every feature considered, ranked, with its tier, what it needs, and whether the prototype has it |
| [docs/RISKS.md](docs/RISKS.md) | Assumptions that could sink the design, each with a cheap test; the top five |
| [docs/DECISIONS.md](docs/DECISIONS.md) | Choices made and alternatives rejected, with superseded ones marked. **Read before reopening one.** |
| [docs/QUESTIONS.md](docs/QUESTIONS.md) | What only the owner can decide, each with a recommendation. Three are for this week |
| [docs/EXPERIMENT_LOG.md](docs/EXPERIMENT_LOG.md) | What the real phone did. **Cards read it before building; it overrides the design.** |
| [docs/cards/](docs/cards/) | Implementation cards. Start with [00-READ-FIRST.md](docs/cards/00-READ-FIRST.md). Retired cards: [ARCHIVE.md](docs/cards/ARCHIVE.md) |
| [docs/archive/](docs/archive/) | The first design's documents and revision 1's DESIGN, unchanged |

## Build order

**Card numbers are names, not the order.** Build in the order of this table. A card may be started only after every card it depends on is done. ◆ marks optional cards.

| Step | Card | Depends on | Needs the phone | Needs an API key |
| --- | --- | --- | --- | --- |
| 0 | *No code:* owner tests, desk spike of extraction ([card 04](docs/cards/04-hosted-extraction-and-scoring.md) §0) | — | Yes (tests) | **Yes** (spike; Q17) |
| 1 | [01 Data model, migration, foundations](docs/cards/01-data-model-migration.md) | — | Yes (his notes must survive) | No |
| 2 | [15 ◆ Spike: SQLCipher with this Room and phone](docs/cards/15-spike-sqlcipher.md) | 01 | Yes | No |
| 3 | [03 Extraction core](docs/cards/03-extraction-core.md) (pure Kotlin) — **done 2026-11-11** | 01 | No | No |
| 4 | [13 Capture pipeline, private notes, capability switches](docs/cards/13-capture-pipeline.md) | 01, 03 | Yes | No |
| 5 | [04 Hosted extraction, provider seam, scoring](docs/cards/04-hosted-extraction-and-scoring.md) | 03, 13 | Yes | **Yes** |
| 6 | [05 Tasks and "What should I handle?"](docs/cards/05-tasks.md) | 03, 13 | Yes | No (better with) |
| 7 | [06 Reminders](docs/cards/06-reminders.md) (pass 1) | 05 | Yes, over a week | No |
| 8 | [07 Readings](docs/cards/07-readings-subjects.md) (pass 1) | 03, 13 | Yes | No (better with) |
| 9 | [08 Asking, with the answer trace](docs/cards/08-ask.md) (pass 1) | 03, 13, 05, 07 | Yes | For the loop |
| — | **STOP. Two weeks of use. Write the answer to RISKS.md R19 in the experiment log.** | | | |
| 10 | [09 Attention list and repair](docs/cards/09-attention-and-repair.md); second passes of 06, 07, 08 | 03, 13, 05, 07 | Yes | No |
| 11 | [10 Backup, export, delete everything](docs/cards/10-backup-export-delete.md) | 01 | Yes | No |
| 12 | [02 App gate and the second unlock for Private](docs/cards/02-app-gate.md) | 01 | Yes | No |
| 13 | [14 Encryption at rest and the release-signed build](docs/cards/14-encryption-at-rest.md) | 01, 10, 15 | Yes | No |
| 14 | [23 Explanation cards and "What leaves my phone"](docs/cards/23-explanation-cards.md) | 13 | Yes | No |
| 15 | **Pictures**, split on 2026-11-12 into cards 16a to 16l ([index](docs/cards/16-share-and-pictures.md)); build in the order of the rows below | | | |
| 15.1 | [16a Attachments: table, encrypted file store, delete, sweep](docs/cards/16a-attachment-store.md) | 01, 13 | Yes (his notes must survive the migration) | No |
| 15.2 | [16b Picture size and format](docs/cards/16b-picture-size-and-format.md) | 16a | Yes | No |
| 15.3 | [16c Share to Stash and the "Anything to add?" screen](docs/cards/16c-share-and-annotate.md) | 16a, 16b | Yes | No |
| 15.4 | [16d Pictures on a note: strip, add later, remove one](docs/cards/16d-pictures-on-a-note.md) | 16a, 16b, 16c | Yes | No |
| 15.5 | [16e A camera inside Stash](docs/cards/16e-camera.md) | 16a, 16b, 16c | Yes (lock-screen check) | No |
| 15.6 | [16f Reading the words in a picture](docs/cards/16f-picture-text.md) | 16a, 16b, 16c | Yes (ten labels) | No |
| 15.7 | [16g Note first: the "Add a picture?" offer](docs/cards/16g-picture-offer.md) | 16a, 16d, 16e | Yes | No (better with) |
| any time after 16c | [16i ◆ Offer my latest screenshot](docs/cards/16i-recent-picture-offer.md) | 16a, 16b, 16c | Yes, over a fortnight | No |
| after 08 | [16h Finding pictures again](docs/cards/16h-finding-pictures.md) | 08, 16a, 16d, 16f | Yes | No |
| after 04 | [16j Sending a picture's words to the model](docs/cards/16j-picture-text-to-model.md) | 04, 13, 16f | Yes | **Yes** |
| after 23 | [16k Pictures: explanation cards, "What leaves my phone"](docs/cards/16k-picture-explanations.md) | 23, the 16x cards built | Yes | No |
| not scheduled | [16l ◆ Later: asking a hosted model about one picture](docs/cards/16l-hosted-vision.md) | 04, 13, 23, 02, 14, 16a, 16d, 16k, and a new decision | Yes | Yes |
| 15b | **Voice notes with audio**, written on 2026-11-12 as cards 26a to 26l ([index](docs/cards/26-voice-notes-with-audio.md), D82); build in the order of the rows below. Needs only 16a from the pictures block (and 16d's pattern for 26h), so the owner chooses whether it goes before or after 16b to 16g | | | |
| 15b.1 | [26a The recorder: our own microphone capture, when to stop](docs/cards/26a-recorder.md) | 13 | Yes (answers check C2: recording from the locked phone) | No |
| 15b.2 | [26b The stored format: Opus while recording, fallbacks, reading it back](docs/cards/26b-audio-format.md) | 26a | Yes (answers check C3: 12 kbps Opus by ear) | No |
| 15b.3 | [26c Keeping a recording: AUDIO attachment, `transcription` table, save path](docs/cards/26c-recording-store.md) | 01, 13, 16a, 26b | Yes (his notes must survive the migration) | No |
| 15b.4 | [26d The speech engine: `Transcriber` seam, Moonshine adapter, model packs](docs/cards/26d-speech-engine.md) | 13, 26a | Yes | No |
| 15b.5 | [26e Long notes inside the memory guide](docs/cards/26e-long-notes-and-memory.md) | 26d | Yes (decides Moonshine base or tiny) | No |
| 15b.6 | [26f Writing recordings out: the pass, states, retries](docs/cards/26f-transcription-job.md) | 13, 26b, 26c, 26d, 26e | Yes (answers check C1: the job with the screen off) | No |
| 15b.7 | [26g The capture screen records; the quick-note path; the one switch](docs/cards/26g-capture-with-recording.md) | 26a to 26f, **and checks C1, C2, C3 written in the experiment log** | Yes | No |
| 15b.8 | [26h On a note: listen, fix the words, write it out again, remove the recording](docs/cards/26h-listen-and-fix.md) | 26c, 26f, 26g (16d for the pattern) | Yes | No |
| 15b.9 | [26j Recordings in Settings: space used, deleting many, what backup needs](docs/cards/26j-recordings-storage-and-delete.md) | 26c, 26f | Yes, over a week | No |
| any time after 26f | [26i ◆ A mark on notes whose words may be wrong](docs/cards/26i-doubtful-words.md) | 26c, 26f (26h for the mark) | Yes, over a fortnight | No |
| after 23 | [26k Recordings: explanation card, "What leaves my phone"](docs/cards/26k-recording-explanations.md) | 23, 26g | Yes | No |
| not scheduled | [26l ◆ Later: getting the speech model onto other people's phones](docs/cards/26l-model-delivery.md) | 26d, a new decision (13 and 23 for a download) | Yes | No |
| 16 | [21 Barcodes and QR codes](docs/cards/21-codes.md) | 16a, 16b, 16c, 16e | Yes | No |
| 17 | [17 Trips built from notes](docs/cards/17-trips-from-notes.md) | 01, 03, 13, 08 | Yes, on outings | No (better with) |
| 18 | [18 Leaving list and departure signals](docs/cards/18-leaving-checklist-and-departure.md) | 06, 17 | Yes, over a week | No |
| 19 | [19 Watches](docs/cards/19-watches.md) | 04, 06, 09, 13 | Yes, over weeks | Yes |
| any time after 04 | [12 ◆ Spike: the phone's own model](docs/cards/12-spike-on-device-model.md) | 04 | Yes | No |
| any time after 04 | [22 ◆ A second model provider](docs/cards/22-second-provider.md) | 04 | Yes | Yes, at that provider |
| any time after 13, 04 | [20 ◆ Diagnostics](docs/cards/20-diagnostics.md) (stage 1 now; the rest at product stage) | 13, 04 | Yes | No |
| after 05, 06 | [11 ◆ Place-aware reminders](docs/cards/11-place-reminders.md) | 05, 06 | Yes, over weeks | No |

Why this order, in three sentences: what is least known is whether a model files his real speech well and whether he keeps using the app, so those come first and everything else waits behind a written check. Hardening (backup, gate, encryption, release build) is one block, done when the build stops being a debug build and before pictures, trips and watches raise what is at stake. Watches are last because they are the furthest from the core and send a different kind of thing. The full reasoning is in [docs/PLAN_REVIEW.md](docs/PLAN_REVIEW.md) and DECISIONS.md D56.

**Added 2026-11-12 (D77, D78):** voice notes will keep their audio. [Card 24](docs/cards/24-spike-keep-audio.md) (spike, done: verdict C, the phone's recogniser cannot take recorded audio and cannot share the microphone) leads to [card 25](docs/cards/25-spike-speech-engine.md) (spike: which engine of our own, if any, on the owner's voice; also the smallest legible audio size and the licence check). A later card 26 builds voice notes with audio if 25 says yes. Every dependency and model is recorded in [docs/LICENSES.md](docs/LICENSES.md) before use. Order chosen by the owner: 25, then 16.

**Card 16 was split on 2026-11-12 (D79)** into twelve small cards, 16a to 16l; `16-share-and-pictures.md` is now their index. The owner's order stands: **card 25's engine spike first, then pictures**, which means 16a, 16b, 16c, 16d, 16e, 16f, 16g in that order (16i is optional at any time after 16c). Those seven have no technical need of the hardening block (10, 02, 14) and may be built before it; the index says what that costs until the block is done. Four wait for another card whatever the order: 16h for 08, 16j for 04, 16k for 23, and 16l (sending a picture to a model: designed, not scheduled) for the hardening block, card 23 and a new decision by the owner. The attachment store in 16a is written so that a voice note's audio (D77, a later card 26) uses the same encrypted files with no change.

**Card 26 was written on 2026-11-12 (D82)** as twelve small cards, 26a to 26l; `26-voice-notes-with-audio.md` is their index. Card 25's spike proposed verdict A with Moonshine base (as accurate as the phone's recogniser on the owner's voice, not more; it writes out a whole note, works from a file and can be run again). The cards record with the app's own recorder, keep the recording as an `AUDIO` attachment in 16a's encrypted store (Opus, about 170 KB a minute), save before anything is written out, and write out in the foreground or in a background job. One switch turns recording off and the phone's recogniser is then used as today. **Three checks still need the owner on the phone** and each decides between named alternatives in the index: the background job with the screen off (C1, run in 26f), our recorder from the locked phone (C2, in 26a), and whether 12 kbps Opus can be followed by ear (C3, in 26b). 26a to 26f can be built before those answers; **26g, which changes how real notes are captured, waits for them**. The block needs 16a and none of the hardening block; where it goes relative to 16b to 16g is the owner's call.

### This week, before any card

1. Copy `C:\Users\<you>\.android\debug.keystore` somewhere safe (two minutes; see RISKS.md R21).
2. The owner test script below (ten minutes), written into the experiment log.
3. Ten dictations of the pool sentence and five of a price sentence (RISKS.md R12; twenty minutes).
4. Buy $5 of API credit (Q17) so the desk spike can run.
5. Ten labels through the phone's own text selection (RISKS.md R31; twenty minutes).

### Milestones

1. **Measured (step 0):** the three phone facts are recorded and extraction quality is a number. *Stop and talk if the scores in RISKS.md R11 are badly missed after two prompt changes.*
2. **It understands me (steps 1–5):** his notes are in the new model and safe from a reinstall; say anything; several things per note; private notes stay home; tier 0 says what it could not do.
3. **Useful daily (steps 6–9):** tasks, the daily digest, readings, proper answers with "Why this answer?". **Then the stop:** two weeks of use and the R19 check.
4. **Safe to rely on (steps 10–14):** doubts have somewhere to go; a lost phone is not lost notes; a fingerprint to read; the database encrypted; a release build; every switch explains itself.
5. **Out and about (steps 15–18):** pictures and codes; trips; the leaving list.
6. **Last (step 19):** watches.
7. **If it becomes a product:** card 20's report, card 22's second provider, the sealed inbox (STORAGE.md), a lawyer's view (Q48), and the deferred first-design work.

## Building

Prototype app, package and application id `app.stash`, `minSdk 35`, target/compile SDK 37, Kotlin + Compose + Room (KSP). Versions are pinned in `gradle/libs.versions.toml` (Room 2.8.4, KSP 2.3.6; the rest copied from the HomeLights project).

```sh
# Windows (PowerShell): $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
./gradlew testDebugUnitTest   # JVM unit tests (246 passing after card 03: corrections, structuring, search and answers, Robolectric tests for the migration, repositories, delete and consistency (card 01), and the extraction core: shape, sensitivity, checks, dates, projection, rules, `ExtractionRunner` (card 03); the first run downloads Robolectric's Android jar)
./gradlew assembleDebug       # build app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug        # build and install on a phone connected with USB debugging (adb in Android SDK platform-tools)
```

### What is in the prototype

- Capture: `capture/CaptureActivity` (shows over the lock screen, beeps, listens, saves, shows "Saved: ...", closes), started by the ongoing notification action or the Quick Settings tile (`triggers/`).
- Speech: `speech/SpeechEngine` interface; `AndroidSpeechEngine` prefers the on-device recognizer and passes the word list as biasing strings. Nothing heard through it is saved as sound: it is the quick-note route. From card 26g a voice note is normally **recorded** instead (`capture/RecordingSession`, `app.stash.audio`): the recording is kept as the note's encrypted attachment and written out by the speech model inside Stash (`speech/Transcriber`); the switch "Keep a recording of each voice note" turns that off.
- Corrections: `domain/Corrector` (heard to meant rules, then conservative fuzzy match against the word list) and `CorrectionLearner` (a word fixed in the Notes screen becomes a rule). Seed: words Syd, Costco, frunk, Frank, Tesla; rule Sid to Syd.
- Structuring: `model/rules/RulesStructurer` (offline) and `model/hosted/HostedStructurer` (Anthropic Messages API, model `claude-haiku-5-5`, any failure or no key falls back to the rules).
- Ask: keyword search with latest-wins supersession (`domain/Search.kt`); answers say how old the note is. With a key the model may phrase the answer.
- Screens (`ui/`): Ask, Notes (search, correct a word, delete), Settings (word list, corrections, API key, hosted switch, notification switch, export as text).

### Privacy and storage, as built

- **The database is plain, unencrypted SQLite (Room).** The Settings screen says so. Backups are off (`allowBackup=false`). The app lock is card 02 and encryption at rest card 14; docs/STORAGE.md says why they come when they do.
- **The app is signed with this PC's automatic debug key.** A build from another PC cannot be installed over it without an uninstall, which deletes the notes. Card 01 fixes this first.
- **Private notes are not implemented.** With a key set and the hosted model on, the text of every note is sent. Cards 03 and 13 add the check.
- The API key is encrypted with an AES-GCM key held in the Android Keystore and stored in private preferences.
- The only code that opens a network connection is `app/stash/model/hosted/Anthropic.kt`. It sends only a note's text (or a question plus the text of up to 3 matching notes) and the word list. Never audio, location or dates; the "how old" wording is added on the phone. No analytics, no crash reporting, and no log calls at all (checked 2026-11-11).
- Location: last known coarse/fine fix is stored with a note if permission was granted; no background location; saving never waits for it.
- The main app is not marked show-when-locked, so asking and reading notes needs the phone unlocked. The capture screen shows only the words being captured now.

### Not built in the prototype

Everything in cards 01–23 (card 16 is now the twelve cards 16a to 16l) and in cards 26a to 26l (voice notes that keep their audio: the prototype stores no sound): the two-layer data model, encryption, app lock, private notes, several records per note, tasks and reminders, readings, the tool loop and answer trace, the attention list, backup and restore, capability switches and explanation cards, pictures, codes, trips, the leaving list, watches, a second provider. Also deferred: assistant role, other triggers, onboarding.

## Ground rules (full list in docs/cards/00-READ-FIRST.md)

- The words he said are saved first and kept for good; everything the app works out from them can be redone.
- Audio, location and time never leave the phone. A voice note's recording is kept only as the app's own encrypted file, until he deletes it (D77; cards 26a to 26l). Only note text may go to a hosted model, and never for anything marked Private. Pictures stay on the phone. A watch sends only words the owner approved.
- Every optional ability has its own switch and says what it sends and what is lost without it.
- The app is fully usable with no API key.
- Adding a note is allowed while locked. Reading always needs the user's fingerprint, face or PIN.
- No telemetry, analytics or crash reporting. Logs hold events and codes, never words.
- What a phone actually did goes in the experiment log, so nothing is tried twice.

## Owner test script (results not yet recorded in docs/EXPERIMENT_LOG.md)

Install: connect the S25 Edge with USB debugging, `./gradlew installDebug` (adb must be on PATH or use the SDK's `platform-tools\adb.exe`). Then write what you see in `docs/EXPERIMENT_LOG.md` (the "Prototype build" table). Note down: what happened, any message, and whether it needed your PIN or fingerprint.

**Set up (unlocked)**
1. Open Stash. Allow Microphone, Notifications, and Location (approximate is enough).
2. Settings: check the words Syd, Costco, frunk, Frank, Tesla and the correction `sid -> Syd`.
3. Pull down the Quick Settings panel, edit it, and drag the "Add a note" tile in.
4. Confirm the "Stash / Add a note" notification is visible.

**Unlocked**
5. Tap the notification's Add a note. Do you hear a short beep? Say "I'm putting my reading glasses in the nightstand drawer", then stop talking. Note: second tone, "Saved: ..." text, whether the screen closes by itself, how many seconds from the end of speech to "Saved".
6. Same with the tile. Same with "I loaned my guitar to Frank on Tuesday" and "I need to remember to buy milk at Costco".
7. Say "Syd has the spare key in the frunk". Does it print Syd (not Sid)? Is "frunk" right? Note anything wrong.
8. In Notes, tap "Correct a word" on a note with a wrong word, fix it, then dictate that word again: is it right now?
9. Ask tab: type "Where did I put my glasses?" and tap Ask. Then tap Speak and ask it aloud. Is the answer right and does it say how long ago?
10. Put the reading glasses somewhere else with a new note; ask again. Latest should win, and the old place appears after "Before that".
11. Ask about something you never noted. It should say it has no note, not guess.

**Locked (the key test)**
12. Lock the phone (screen off, then wake it; stay on the lock screen). Is the "Add a note" notification visible and does it show a button? Tap it. Does the capture screen appear **without** a PIN? Does it beep? Say a note. Does it save and close? Write down exactly what happened (asked for PIN / opened / nothing).
13. Same from the lock screen's Quick Settings tile (pull down the shade while locked). Does the tile appear, and does tapping it open the capture screen or ask to unlock?
14. After capture while locked, unlock and check the note is in Notes and the text is correct. Does the phone go back to the lock screen afterwards?
15. Try step 12 with the phone on silent or vibrate: are the beeps still heard? And once with Wi-Fi and mobile data off (airplane mode): does speech to text still work (tells you whether the recognizer is on-device)?
16. Swipe the notification away; does it come back after a reboot or when you open Stash?
17. On the lock screen, confirm the Stash app never shows any stored note text (only the note being spoken).

**Also useful to note**: battery use after a day, any time the notification vanishes, notes that were structured wrongly (item/place shown on each note card), and whether you reached for it naturally during a day.

**Hosted model (only once you have a key)**: Settings, paste the key, Save. Add a note, check it still works; switch the model off and check it still works. With airplane mode the note must still save (it falls back to offline rules).
