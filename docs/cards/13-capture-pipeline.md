# 13 · Capture pipeline: save first, background extraction, private notes wired in, capability switches

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

Split from the old card 03 in revision 2 (the pure half is [card 03](03-extraction-core.md)).

## Goal

Saving a note never waits for anything. Understanding happens afterwards in the background, never sends a private note anywhere, and can be redone. Every optional ability of the app sits behind one named switch.

## Depends on

01, 03.

## Context

Today `Repository.saveNote` (in `data/Container.kt`) calls the hosted model **before** inserting the note, so a slow network delays the save by up to 15 seconds, and it sends every note because nothing ever sets `sensitive`. Both are fixed here. Card 03 supplies the types, `Sensitivity`, the checks, `Projector` and `RulesExtractor`. The hosted extractor arrives in card 04; until then use `FakeExtractor` in tests.

Two things in this card exist to protect later work (`docs/STORAGE.md`, "Decide now"):

- **One way in.** Everything that arrives while the phone may be locked goes through one `InboundSink`. Later, a sealed inbox can replace its implementation without touching capture code.
- **Background code never assumes the database can be opened.** It asks the store and reschedules on "not now".

## Scope

### A. One way in

1. `capture/InboundSink`: `suspend fun accept(item: Inbound): Result`. `Inbound.Capture` carries everything fixed at the moment of capture: id (UUID made here), `created_at`, zone, raw text, corrected text, source (`VOICE`, `TYPED`, later `SHARED`), locked flag, location, app version. The only implementation now is `DirectSink`, which runs `Sensitivity.check` (setting `sensitive` and its reason), strips the marker, and calls `CaptureRepository.insertCapture`.
2. `CaptureActivity` and typed entry call the sink and **then** play the save tone and show "Saved". Add a short vibration with each cue. Add **Undo** on the saved screen for 5 seconds (soft-deletes the capture). Add a text field on the main screen for typing a note.
3. A per-note toggle "Private" in the Notes list; turning it **on** is always allowed; turning it **off** asks for confirmation. Settings: an editable "Always private words" list; the prototype's "Use the hosted model" switch, kept.

### B. Background extraction (`pipeline/`)

4. `ExtractionWorker` (WorkManager, unique work, runs immediately when enqueued; no network constraint): for each capture in `PENDING` or `RETRY`, oldest first:
   - Build the context from non-sensitive data.
   - Always run `RulesExtractor`. If `Sensitivity.sendable(capture)` is not null, the hosted capability is on (section C) and a hosted `Extractor` is registered, run it too.
   - Hosted `Ok` → its records are used. Hosted `Failed(retryable)` → rules records are used, state `RETRY`, attempts + 1, back-off 1 min, 10 min, 1 h, 6 h, 24 h, then `GAVE_UP`. Hosted `Failed(not retryable)` → rules records, `DONE`.
   - No hosted extractor applicable → rules records; `DONE` if sensitive or no key; `RETRY` without counting an attempt if a key exists but there is no network (re-enqueue with a network constraint).
   - Store the extraction row (`text_hash` = SHA-256 of the capture text), run checks, project, write through `RecordRepository.replaceRecordsFor` in one transaction. A capture with any pinned record is skipped by automatic re-reads.
   - After the write, call the hooks later cards register (`AfterProjection`: card 05's task closer, card 07's outlier check, card 17's trip builder). A hook that throws is logged as an event and does not undo the write.
5. Enqueued after every insert, at app start, and at boot. A capture whose `text` was edited goes back to `PENDING`. Migrated captures from card 01 are all `PENDING` and are re-read.
6. "Read it again" on a capture in the Notes list: sets `PENDING` and enqueues, even if some records are pinned (pinned ones stay; new ones are added; a new record whose kind and normalised title equal a pinned one's is discarded).
7. The worker opens the database only through `Container.store()`, which may return `NotAvailable`; the worker then returns `Result.retry()`. Today it is always available. Do not bypass it.

### C. Capability switches (`domain/Capability.kt`, `data/Capabilities`)

8. An enum of every optional ability, each with: a plain name, the Android permission it needs (if any), what works without it, and a default. Start with:

   | Capability | Needs | Without it |
   | --- | --- | --- |
   | `VOICE_CAPTURE` | microphone | typed notes only |
   | `CAPTURE_NOTIFICATION` | notifications | tile and in-app button only |
   | `LOCATION_AT_CAPTURE` | location while in use | notes have no place; trips (card 17) fall back to spoken place names |
   | `HOSTED_MODEL` | internet, a key for the chosen provider | rules extractor; search results as a list (Tier 0, `docs/PROVIDERS.md`) |
   | `REMINDERS` | notifications | the "To handle" screen only |

   Later cards add their own rows (`READ_ALOUD`, `PICTURES`, `RECENT_MEDIA`, `PICTURE_TEXT`, `TRIPS`, `DEPARTURE_*`, `PLACE_ARRIVAL`, `WATCHES`, `BACKUP`).
9. `Capabilities.isOn(cap)` = the owner's switch **and** the permission is granted **and** the build allows it (a constant set per build, all on today; this is where a lighter product tier would turn things off). All feature code asks this one function; nothing checks a permission or a setting directly.
10. Each capability's implementation sits behind a small interface with a do-nothing version (`LocationSource`, `HostedExtractorProvider`, …), chosen in `Container`. `Repository.lastKnownLocation()` becomes `LocationSource`.
11. Settings → "What Stash may use": the list, each with its switch, the permission state, and the "without it" sentence.

### C2. Tier 0 must look intended, not broken

11a. Every record shows how it was filed: nothing for the model; a small "filed by simple rules" mark for `rules-2`. A capture the rules could not place (`OTHER`) says "Saved. Not sorted: needs a model" in the Notes list, never nothing.
11b. The typed-question box, with no model available and no router match, says "Without a model I can only match words. Notes that match:" above the list. (Card 08 replaces the wording; the rule stays: no silent fall-back.)
11c. Captures filed only by rules stay eligible for a model later: they keep `extract_state = DONE` with a `rules-2` extraction and are found by "current extraction is not from a model, and not sensitive". Card 04 offers to file them when a model is switched on.

### C3. One register of everything that can leave the phone

11d. `domain/Outbound.kt`: an enum of every kind of outbound request the app can make (`EXTRACT_NOTE`, `ANSWER_QUESTION` today; later `WATCH_EVENTS`, `WATCH_WEB`, `PRODUCT_LOOKUP`, `DESCRIBE_PICTURE`), each with: destination (from the chosen provider), what is sent in plain words, and the capability that gates it. `HttpPoster.post` gains a required `kind: Outbound` argument and refuses when that kind's capability is off; it counts each call (`outbound_count` in preferences: per kind, this month, last time). No request can be made without naming its kind. Card 23 draws the "What leaves my phone" screen from this register.

### D. Event log, not text log (`domain/Dbg.kt`, `app_event` table from card 01)

12. `Dbg.event(component, code, n1 = null, n2 = null, refId = null, ms = null)`: `component` and `code` are enums; the numbers are counts or sizes; `refId` is a row id. **There is no parameter that can carry a string of note content.** Writes a row to `app_event`; keeps the newest 5,000.
13. Log from: capture saved, extraction started/ok/failed (reason code, tokens, ms), checks failed (reason code), projection written (record count), hook failed, worker rescheduled.
14. `peek.py events [N]` prints them from the dump.

### E. Enforcement tests

15. A unit test that scans `app/src/main` and fails if any file outside the allowed network packages (today only `app/stash/model/hosted/`) contains `java.net.`, `HttpURLConnection`, `okhttp`, `Socket(` or `WebView`.
16. A unit test that runs the pipeline over a sensitive capture with a hosted extractor whose transport throws if called.
17. A unit test that scans `app/src/main` and fails on `android.util.Log`, `println(` or `printStackTrace(` anywhere except `domain/Dbg.kt`.
18. A unit test that runs capture → extraction → search → open tasks with **every optional capability off** and passes.

## Out of scope

The real hosted call (04). Acting on `closes` and task expiry (05). Nudges (06). The attention list UI (09). The sealed inbox itself.

## Acceptance checks

- **Unit:** worker logic with `FakeExtractor`: success; retryable failure then success; give up after five; sensitive never reaches the hosted fake; pinned record survives a re-read; a throwing hook leaves the records written; store `NotAvailable` → retry.
- **Unit:** tests 15–18.
- **Unit:** `Capabilities.isOn` truth table (switch, permission, build).
- **Unit:** `HttpPoster` with a kind whose capability is off throws before any connection; counts rise per kind.
- **Real phone, S25 Edge:** in aeroplane mode, add a note by voice: the save tone comes at once (under a second after you stop). `peek.py dump 5` shows the capture, a `rules-2` extraction and a record. Say "private note the gate code is 4 4 7 1": `sensitive` is 1, `text` has no "private note", `raw_text` does. After install over the card-01 build, the migrated captures have been re-read (`extractor = rules-2`), and "I am updating the app for stash right now" is an `EVENT`. `peek.py events 20` shows codes and numbers and no words from any note. Log it.
- **Real phone:** Undo within 5 seconds removes the capture from the list. Turn `LOCATION_AT_CAPTURE` off: a new capture has no coordinates and everything else works.

## Notes and risks

- WorkManager is the one new dependency (`androidx.work:work-runtime-ktx`).
- Samsung may delay background work for a "sleeping" app. The capture is safe regardless; note in the log how long extraction took after a locked capture.
- Keep the capability list honest: a row is added when a feature is built, not before.
