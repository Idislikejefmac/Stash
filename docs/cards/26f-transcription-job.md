# 26f · Writing recordings out: the transcription pass, its states, retries, and the screen-off check

Read [00-READ-FIRST.md](00-READ-FIRST.md) first. Part of the card 26 split ([index](26-voice-notes-with-audio.md), D77, D81, D82).

**Status: built 2026-11-12, JVM only** (805 unit tests pass in both build configurations, with and without the engine AAR, one skipped in each; 35 new; no schema change, no new dependency). Nothing was installed on the phone: **check C1 has not been run**, so `TranscriptionWorkShape.OUTCOME` is J1, as the card says to start. See "What was built, and what differs" at the end, and `docs/EXPERIMENT_LOG.md`, "Card 26f", for the owner's steps.

## Goal

Every saved recording gets written out without anyone asking: at once if the app is on screen, otherwise by a background job, and again later if that fails. A recording is never lost or blocked by a failure here. A request to write a note out again produces a new writing-out beside the old one. The card also answers open check C1: what does Samsung do to this job when the screen is off?

## Depends on

13 (the extraction pipeline and worker this mirrors), 26b (`AudioDecoder`, `Compactor`), 26c (states and `TranscriptionRepository`), 26d and 26e (`Container.transcriber`).

## Context

Read first: `pipeline/ExtractionPipeline.kt` and `pipeline/ExtractionWorker.kt` (copy their shape: a plain-Kotlin pass over the store, a thin WorkManager wrapper, `Container.store()` and "not now"), `data/RetryPolicy` (the back-off already used), `StashApp.kt`, `triggers/Triggers.kt` (the boot receiver), and "Background job and battery" in "Card 25: engine results".

Measured: a WorkManager job with the screen **on** started 59 ms after it was asked for and finished a 27.7-second note in about 3 seconds (load 1.1 s, work 2.0 s), about three times slower than in the foreground, at 418 MB. **With the screen off it has not been run** (C1). One hundred passes of the whole corpus moved the battery 3% and the temperature 6 °C, so battery is not a concern at a few notes a day.

**One pass, two ways to start it.** The same `TranscriptionPipeline.runPass()` is started (a) in the app's own process the moment a recording is saved and whenever the app comes on screen, and (b) by a WorkManager job. A lock makes sure only one runs. Which of the two does most of the work depends on C1; **no code changes with the answer, only two constants** (item 9).

## Scope

### A. The pass (`pipeline/TranscriptionPipeline.kt`, plain Kotlin, tested with fakes)

1. `runPass(where: FOREGROUND | BACKGROUND): PassResult`, guarded by one process-wide `Mutex` (a second caller returns at once with "already running"). It opens the database only through `Container.store()`; `NotAvailable` ends the pass and the worker returns `retry()`.
2. For each capture whose `transcribe_state` is `PENDING`, or `RETRY` and due, oldest first; then each note the owner asked to have written out again:
   - If `transcriber.availability()` is not `READY`: leave the note as it is, **count no attempt**, log `MODEL_MISSING` once per pass, and stop the pass. (It runs again when a model pack is imported: `ModelImporter` asks for a pass on success.)
   - Read the recording through `Repository.attachments.openBytes`, decode with `AudioDecoder`. An unreadable or undecodable file is `GAVE_UP` at once with a reason code (retrying cannot help), and is what `ConsistencyCheck` reports.
   - Write a **run marker** (capture id, start time, `where`) to a small preferences file, call `transcriber.transcribe(pcm)` with 2 threads in the foreground and 1 in the background (26e), clear the marker.
   - `Ok` on a note with no words yet: `transcriptions.applyFirst(...)` (26c: stores the writing-out, sets `raw_text` once, applies the correction rules and word list through `Corrector`, runs the Private check, sets the note to be read). Then ask the extraction scheduler for a pass (`scheduler.enqueueNow()`).
   - `Ok` on an owner's "again" request: `transcriptions.offer(...)`. Nothing else changes until he chooses (26h).
   - `Failed(retryable)`: `recordFailure` with attempts + 1 and the back-off already used for extraction (1 min, 10 min, 1 h, 6 h, 24 h), then `GAVE_UP`. `Failed(not retryable)`: `GAVE_UP`.
3. **A killed run.** If a pass starts and finds a run marker it did not write, the process died in the middle of that note (most likely killed for memory). Record a `FAILED` row with code `KILLED_BG` or `KILLED_FG`, count an attempt, log `Dbg.event(TRANSCRIBE, KILLED, n1 = audio ms)`. A note with **two** `KILLED_BG` rows is skipped by every later background pass and waits for a foreground one. A note with two `KILLED_FG` rows is `GAVE_UP` with the reason "too long for this phone".
4. At the end of every pass that loaded the engine: `transcriber.release()`. Then, if any recording is stored as `audio/wav` (26b's fallback), compact one per pass with `Compactor` and `recordings.replaceFile`.
5. `GAVE_UP` never deletes anything. The recording stays, the note says it could not be written out (26g, 26h), and "Try again" (`askAgain`) starts from zero.

### B. Starting it (`pipeline/TranscriptionScheduler.kt`, `StashApp`, `Container`)

6. `TranscriptionScheduler.requestNow()`: launches `runPass(FOREGROUND)` in the application's coroutine scope **if an activity of the app is started** (count them with `ActivityLifecycleCallbacks` in `StashApp`; no new library), and always enqueues unique work `stash-transcribe` (`ExistingWorkPolicy.APPEND_OR_REPLACE`, like extraction).
7. `TranscriptionWorker` (CoroutineWorker): `runPass(BACKGROUND)`; reschedules itself for the nearest back-off with unique work `stash-transcribe-later`; `retry()` on "store not available".
8. `requestNow()` is called: by 26c's `afterRecordingSave` (wire it in `Container`); when `MainActivity` or `CaptureActivity` starts; at boot (beside the extraction enqueue); after a model pack is imported; after "write it out again" or "Try again".
9. Two constants, set from C1's answer (start with J1's values):

   | Outcome of C1 | `WORK_EXPEDITED` | `WORK_NEEDS_CHARGING` | What does the work in practice |
   | --- | --- | --- | --- |
   | **J1** the job starts within about a minute and finishes | true | false | The job, or the foreground pass if the capture screen is still up. Nothing to tell the owner |
   | **J2** the job finishes but only after minutes or hours | true | false | The foreground pass while the capture screen is still showing (it stays up to 10 seconds after a save) and whenever Stash is opened; the job catches the rest late. The note shows "Not written out yet" meanwhile |
   | **J3** the job is killed, or never runs with the screen off | false | true | The foreground pass as in J2; the job runs only while the phone is charging. Card 26e's outcome is revisited (tiny for background work). Settings says "Recordings are written out when Stash is open or the phone is charging" |

   `WORK_EXPEDITED` uses `setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)`; confirm on the live WorkManager page that a `CoroutineWorker` on Android 12 and later needs no notification for it, and say what you found.
10. No foreground service and no notification, in any outcome. A recording waiting to be written out is not worth a notification (D60's budget), and a service would need a new permission.

### C. Events and what Settings can show

11. Events (numbers only): `TRANSCRIBE` with `STARTED` (n1 audio ms, n2 0 foreground or 1 background), `OK` (n1 chunks, n2 characters, ms), `FAILED` (n1 code), `KILLED`, `GAVE_UP`, `RESCHEDULED`, `MODEL_MISSING`. The time between `AUDIO/STORED` and `TRANSCRIBE/OK` for the same note is the delay the owner experiences; `peek.py events` shows it.
12. `TranscriptionStatusSummary` for Settings (drawn by 26j): how many recordings are waiting, how many gave up, and the last delay.

## Out of scope

The capture screen (26g) and any screen that shows states or offers (26h). Doubt flags (26i). Running both models on one note. Writing out old notes that have no recording (there is nothing to write out). Changing the extraction pipeline, other than asking it for a pass.

## Acceptance checks

**Unit tests (JVM; Robolectric with an in-memory Room database; a fake `Transcriber` and a fake decoder)**

- A saved recording: one pass gives `DONE`, `raw_text` and corrected `text`, extract `PENDING`, and the extraction scheduler was asked once.
- Retryable failure then success: `RETRY` with attempts 1, not retried before the back-off, then `DONE`. Five retryable failures: `GAVE_UP`, recording still present.
- No model: state stays `PENDING`, attempts stay 0, nothing is retried in a loop; after the fake becomes `READY` and `requestNow()` is called, it is written out.
- An unreadable file: `GAVE_UP` at once, the others in the pass still done.
- Run marker left behind by a "dead" background run: one `KILLED_BG` row and an attempt; after two, a background pass skips the note and a foreground pass does it. Two `KILLED_FG`: `GAVE_UP`.
- "Again" on a `DONE` note: an `OFFERED` row, `capture.text` unchanged; "again" on `GAVE_UP`: back to `PENDING` and done.
- Two callers at once: the transcriber is entered once.
- Store `NotAvailable`: the worker logic returns retry and nothing is changed.
- A Private recording: written out like any other (it never leaves the phone), the result stored, and a spy on the outbound gate records no call.
- `release()` is called after a pass that transcribed, and not after one that did nothing.
- A `WAV` recording is compacted after the pass and its bytes replaced.

**Real phone (S25 Edge); experiment log, "Card 26"**

- With the app open: a debug action saves the 8-second corpus recording as a note; time from `AUDIO/STORED` to `TRANSCRIBE/OK` (expect about a second), and the text.
- **Open check C1.** Phone unlocked and idle, Stash not on screen (swipe it from recents is **not** required; press Home). A debug action saves the 27.7-second recording as a note after a 60-second delay; press the power button within that minute; wait two minutes; unlock; read the events. Record: the delay from stored to started, whether it finished, the peak memory if the job logs it, any `KILLED`. Repeat three times, and once more after the phone has been left alone for 30 minutes (Samsung's "sleeping apps"). **Write the answer as J1, J2 (with the delays seen) or J3 (with what killed it), and set the two constants.** If the answers differ between runs, take the worst.
- Reboot the phone with one recording still `PENDING`, in aeroplane mode (no network is needed for any of this): it is written out after boot without opening the app, or, under J2/J3, when Stash is next opened. Record which.
- Remove the model pack, save a recording, add the pack again: the note is written out without any other action.
- Stash's battery setting: note whether it is "Unrestricted", "Optimised" or "Restricted" during C1, and whether changing it changes the answer.

## Notes and risks

- The foreground pass runs in the app's process without a service, so Android may freeze it shortly after the last activity closes. That is expected: the job (or the next opening of the app) finishes the note. Nothing is lost, because a pass that dies leaves a marker and the state is still `PENDING` or `RETRY`.
- Under J2 or J3 a note recorded on the lock screen and never looked at may stay unwritten for hours. It is still saved and can be heard; it cannot be found by its words until then. The Notes list must say so on the row (26g), and Settings shows the count (26j). Nothing fails silently.
- Do not transcribe on the capture screen's own coroutine scope: it dies with the activity. Use the application scope.

**Suggested builder: Opus.** A background job with retry, kill detection and two entry points, where a mistake leaves recordings unwritten for ever or writes one twice.

## What was built, and what differs from the card (2026-11-12)

Built (under `app/src/main/java/app/stash/` unless noted):
- `pipeline/TranscriptionPolicy.kt` (pure, no Android import), every rule as a named constant:
  - `PassWhere` (`FOREGROUND` 2 threads, `BACKGROUND` 1, through 26e's `RunPlace`).
  - `TranscriptionPolicy`: `BACK_OFF_MS` (extraction's `RetryPolicy`: 1 min, 10 min, 1 h, 6 h, 24 h), `MAX_ATTEMPTS` 5, `BACKGROUND_KILLS_BEFORE_FOREGROUND_ONLY` 2, `FOREGROUND_KILLS_BEFORE_GIVING_UP` 2, the reason codes (`KILLED_BG`, `KILLED_FG`, `NO_RECORDING`, `UNREADABLE`, `UNDECODABLE`), `waitMs` (is a note due), `giveUp`, and which failures still count (`countingFailures`, `skipInBackground`, `abandonAgainRequest`).
  - `RunMarker`, `RunMarkerStore`, `MemoryRunMarkerStore`, and `KillCheck.judge` (none, our own leftover, or killed).
  - `C1Outcome` (J1, J2, J3, each with `expedited`, `needsCharging` and the J3 Settings sentence) and `TranscriptionWorkShape`. **`OUTCOME = C1Outcome.J1` is the one constant check C1 sets**; `WORK_EXPEDITED` and `WORK_NEEDS_CHARGING` follow from it. The unique work names and `ALREADY_RUNNING_RECHECK_MS` (2 min) are here too.
  - `StartRule` (the job always; the foreground pass while a screen is started), `ForegroundTracker`, `TranscribeNotice` (the row's words), `TranscriptionStatusSummary`.
- `pipeline/TranscriptionPipeline.kt` (plain Kotlin): `runPass(where)` as items 1 to 5.
  - One process-wide `Mutex` (`tryLock`: a second caller gets `alreadyRunning` at once). The store only through `Container.store()`.
  - Notes PENDING, or RETRY and due, oldest first; then notes with an owner's "again" request. After each round it looks again, so a recording saved during a pass is written out in that pass.
  - No model ready: the pass stops, no attempt is counted, one `MODEL_MISSING` event.
  - The recording is read with `openRecording` (see below) and decoded through the `Decoder` seam. A missing, unreadable or undecodable recording is GAVE_UP at once.
  - A run marker is written before each call into the engine and cleared after it (`finally`).
  - `Ok` goes to `applyFirst` (then the extraction scheduler's `enqueueNow`, only when there were words) or to `offer`. `Failed` goes to `recordFailure` with the policy's `giveUp`.
  - `release()` after a pass that called the engine; then one WAV recording per pass is compacted (`Compactor`, `recordings.replaceFile`).
- `pipeline/TranscriptionWorker.kt` (Android, thin):
  - `TranscriptionWork` (the seam) and `TranscriptionScheduler.requestNow()`.
  - `TranscriptionJobLogic` (pure: what the job does with a pass's result).
  - `WorkManagerTranscriptionWork`: `stash-transcribe` with `APPEND_OR_REPLACE`, expedited with `RUN_AS_NON_EXPEDITED_WORK_REQUEST` under J1 and J2, `requiresCharging` under J3; `stash-transcribe-later` with `REPLACE` for the nearest back-off.
  - `TranscriptionWorker` (`retry()` when the store is not available) and `PrefsRunMarkerStore` (preferences file `transcribe_run`, written with `commit()`).
- `data/Daos.kt`, `TranscriptionDao`: five new queries (waiting notes, written-out note ids, WAV recordings, a count by state, the last delay from save to first writing-out). Queries only; the schema is unchanged.
- `data/Container.kt`: `foreground`, an application-lifetime scope for the foreground pass, `transcriptionWork`, `transcriptionPipeline`, `transcriptionScheduler`, `writeOutAgain(captureId)` (`askAgain` then `requestNow`, for 26h's buttons) and `transcriptionSummary()` (item 12, for 26j). **26c's `afterRecordingSave` now calls `requestNow()`**, and `modelImporter` is built with `onInstalled = { requestNow() }`.
- `speech/ModelImporter.kt`: an optional `onInstalled` callback, called after a pack is installed (both routes: Settings and the debug receiver). No other change.
- `StashApp.kt`: `ActivityLifecycleCallbacks` count the started activities; when the first one starts (Stash comes on screen: `MainActivity`, `CaptureActivity` or any other) it calls `requestNow()`. `triggers/Triggers.kt`: the boot receiver calls `requestNow()` beside the extraction enqueue.
- `domain/Dbg.kt`: codes `KILLED` and `MODEL_MISSING`. The pass's TRANSCRIBE events carry the recording's attachment id as `refId`, the same id `AUDIO STORED` carries, so the delay is read from the events.
- `ui/MainActivity.kt` and `MainViewModel.kt`: on a note's row, in place of the filing line, one line while it waits: "Saved with its recording. Not written out yet." or, with no model ready, "Saved with its recording. Not written out yet: needs a speech model (Settings, Voice notes)."; "It could not be written out." for GAVE_UP; "No words were heard." for an empty writing-out. No notification.
- Debug only: `app/src/debug/java/app/stash/debug/TranscriptionCheckReceiver.kt` and one `<receiver>` line in the debug manifest. `step=save` saves a corpus WAV as a real recorded note through `Container.recordingSaver`; `step=arm` does it from an alarm after a delay; `step=report` turns the events into one line per saved note and C1's reading (`C1Report`, pure, tested).
- Tests: `TranscriptionJobTest` (25, Robolectric, real Room and real encrypted files, a fake transcriber and decoder) and `TranscriptionPolicyTest` (10, plain JVM, injected clock). Every unit check in the card has a test, and so do these: a note with no model stays PENDING with the plain "needs a speech model" line and is never lost; no recording is deleted (GAVE_UP, unreadable, undecodable, killed twice); two simultaneous starts enter the transcriber once.

**The live WorkManager page** ("Define work requests", read 2026-11-12) says "WorkManager might run a foreground service on platform versions older than Android 12", and that `getForegroundInfo()` lets WorkManager "display a notification when you call setExpedited() prior to Android 12". This app's minSdk is 35, so an expedited job needs **no notification and no `getForegroundInfo()`**, and none is implemented (item 10). Expedited jobs accept no charging constraint, so `TranscriptionWorkShape.expedite()` never expedites a charging job (J3). An expedited request cannot have an initial delay either, so the "later" job is never expedited.

What differs from the card, and why:
- **"Five retryable failures: GAVE_UP"** is taken literally (`MAX_ATTEMPTS = 5`): the fifth failure gives up, so the 24-hour back-off is never reached. Extraction gives up at the sixth (a first try and five retries). It is one constant if the owner prefers extraction's count. A killed run counts as an attempt (item 3), so kills and failures share the five.
- **`openRecording`, not `openBytes`.** 26c made `openBytes` refuse a recording; 26c's source scan allows `pipeline/` to call `openRecording`.
- **Which kills still count.** Rows are never deleted, so "two `KILLED_BG` rows" is read as two among the failures since the last reset: the newest `transcribe_attempts` FAILED rows. Every counted attempt writes one, and "Try again" sets attempts to 0, so it really starts from zero. For an owner's "again" request: the FAILED rows newer than the newest row with words.
- **An "again" request has no state of its own**, so it cannot be GAVE_UP. It is not tried again after two foreground kills, a failure retrying cannot help, or five failures since the last words. Its words and recording stay; showing that is 26h's.
- **A kill puts the note in RETRY with the normal back-off** (1 minute after the first) rather than trying it again at once, so a note that kills the process cannot do it in a tight loop.
- **When the job finds a foreground pass already running** it returns success and enqueues `stash-transcribe-later` in 2 minutes. Without this, a foreground pass frozen or killed after the last screen closed would leave nothing scheduled until Stash was next opened.
- **The engine says its model is gone mid-pass** (`MODEL_MISSING` or `NO_ENGINE` from `transcribe`): treated like "no model" (no attempt, no row), not as a failure of the note.
- **Out of memory while decoding** is a retryable failure (`OUT_OF_MEMORY`), not "undecodable": it is not the recording's fault.
- **"When MainActivity or CaptureActivity starts"** is done in `StashApp`'s lifecycle callbacks (when the first activity of the app starts), so `CaptureActivity` itself is unchanged (26g owns it).
- **The waiting line** is drawn in the Notes list now. Without it a wordless recorded note would show "Saved. Not sorted: needs a model", which is wrong. 26g and 26h may restyle it; the words are `TranscribeNotice`.
- **J3's "tiny for background work"** is not built: it changes 26e's pack policy and is to be made only if C1 answers J3.
- **26d's engine still logs its own `STARTED` and `OK`** (no `refId`, other numbers). The pass's events are the ones carrying the recording's id.

Unverified, needs the owner (nothing was installed and no adb command was sent): every "Real phone" check of the card, including **check C1**. The steps are in `docs/EXPERIMENT_LOG.md`, "Card 26f".
