# 26c · Keeping a recording: the AUDIO attachment, the `transcription` table, and the save path

Read [00-READ-FIRST.md](00-READ-FIRST.md) first. Part of the card 26 split ([index](26-voice-notes-with-audio.md), D77, D82). Where 00-READ-FIRST says audio is never stored, this card wins.

**Status: built 2026-11-12, JVM only** (715 unit tests pass, 29 new; schema version 4; no new dependency). Nothing was installed on the phone: the migration of his real notes and the self-test wait for the owner. See "What was built, and what differs" at the end.

## Goal

A voice note can be saved as **a recording with no words yet**: the encrypted file and the capture row are written together, at once, and nothing about understanding it is needed for that. Every later writing-out of the recording is kept as its own row. The recording can be removed without removing the note. No screen, no recorder and no engine is built here.

## Depends on

01, 13, **16a** (the attachment table and the encrypted file store; this card adds no second store), 26b (the `EncodedAudio` type only).

## Context

Read first: `data/Entities.kt`, `data/Enums.kt`, `data/Migrations.kt`, `data/StashDatabase.kt`, `data/Repositories.kt` (`insertCapture`, `updateText`, `requestReread`, `setSensitive`, `purge`), `data/ConsistencyCheck.kt`, `capture/InboundSink.kt`, 16a's `AttachmentRepository` and `AttachmentStore` as built, `domain/Sensitivity.kt`, `domain/Corrector.kt`, and card 16a section E.

**What is truth now.** For a recorded note the **recording** is what he said. The words are the app's reading of it and can be redone. So:

- `capture.raw_text` is the **first** writing-out, written **once** and never changed afterwards (D36 stays literally true: the raw words are never rewritten).
- Every writing-out, the first and any later one, is a row in `transcription` with the engine's exact words. Rows are never edited and are deleted only when the note is purged.
- `capture.text` is the words the app uses now. It changes only through a named action that leaves a `revision`.

**Two states, not one.** `extract_state` keeps meaning "have the words been read into records". A new `transcribe_state` means "has the recording been written out". The extraction pipeline never sees a note that has no words yet, with no change to its query: a recording is inserted with `extract_state = DONE` (there is nothing to read) and goes to `PENDING` when its words arrive, exactly as an edited note does today.

**Crash-safe order (16a):** adding is file first, then row; deleting is row first, then file.

## Scope

### A. Schema (`data/`)

1. Enums: `TranscribeState { PENDING, DONE, RETRY, GAVE_UP }`; `TranscriptionStatus { CURRENT, OFFERED, REPLACED, FAILED }`; add `RECORDER` to `AttachmentSource`; add `AUDIO_REMOVED` and `TEXT_REWRITTEN` to `RevisionKind`.
2. `capture` gains `transcribe_state` (TEXT, **null** for every note that has no recording: typed, shared, dictated through the phone's recogniser, and every existing row) and `transcribe_attempts` (INTEGER, not null, default 0).
3. New table `transcription`, index on `capture_id`:

   | Column | Type | Notes |
   | --- | --- | --- |
   | `id` | TEXT, primary key | `Ids.new()` |
   | `capture_id` | TEXT, not null | |
   | `attachment_id` | TEXT, null | the recording it was made from; kept as a plain value after the recording is removed |
   | `at` | INTEGER, not null | |
   | `engine` | TEXT, not null | the transcriber's id, e.g. `moonshine-base-en-2026-02-27/sherpa-2.0.1` |
   | `text` | TEXT, not null | the engine's words, untouched. Empty when it heard none |
   | `status` | TEXT, not null | `TranscriptionStatus` |
   | `error` | TEXT, null | a reason **code**, never words |
   | `audio_ms`, `proc_ms`, `chunks` | INTEGER, null | for the event log and Settings |
   | `doubt` | INTEGER, not null, default 0 | bit flags, filled by card 26i; 0 until then |
   | `asked_by_owner` | INTEGER, not null, default 0 | 1 for "write it out again" |

4. Hand-written migration to the next free version (3 to 4 if nothing else was added), two `ALTER TABLE capture ADD COLUMN`, one `CREATE TABLE`, one `CREATE INDEX`, copied from the exported schema JSON; added to `Migrations.ALL`; the schema file committed. No existing row changes.
5. `TranscriptionDao` and, in `AttachmentDao`, `audioForCapture(captureId)` and `audioTotals()` (count, sum of `byte_size`, sum of `duration_ms`). A read model without the key: `RecordingInfo(attachmentId, captureId, durationMs, byteSize, mime)`. Screens only ever get `RecordingInfo`.

### B. Saving (`capture/`, `data/`)

6. `RecordingSaver.save(encoded: EncodedAudio, capturedLocked, markPrivate): InboundSink.Result`, the one function the capture screen will call:
   - makes a key and file name, calls `AttachmentStore.write` (file first), builds an `InboundAttachment(kind = AUDIO, source = RECORDER, mime, durationMs, width = null, height = null)`;
   - hands `Inbound.Capture` (source `VOICE`, `rawText = ""`, `text = ""`, that one attachment, location and zone as today) to `container.sink`;
   - on `Failed`, returns it and **keeps the file** (16a's rule); the caller still holds the bytes and may call again.
7. `DirectSink`: a capture that arrives with an `AUDIO` attachment and blank text is inserted with `transcribe_state = PENDING`, `transcribe_attempts = 0`, `extract_state = DONE`, `sensitive` set only from `markPrivate` (there are no words to check yet). After it is saved the sink calls a new `afterRecordingSave` callback instead of the extraction one (card 26f supplies it; a no-op here). A capture may have **at most one** `AUDIO` attachment; `AddAttachments` with a second is refused.
8. Event: `Dbg.event(CAPTURE, SAVED, n1 = 0, n2 = sensitive, refId)` as today, plus `Dbg.event(AUDIO, STORED, n1 = bytes, n2 = duration ms, refId = attachment id)`.

### C. The words arrive (`data/TranscriptionRepository`, reached as `Repository.transcriptions`)

Each of these is one database transaction. `Corrector` and `Sensitivity` are passed in, as `CaptureEntry` and `DirectSink` take them today.

9. `applyFirst(captureId, engine, text, timings)`: allowed only while `transcribe_state` is `PENDING` or `RETRY`. Inserts the `transcription` row as `CURRENT`. If `text` is not blank: sets `raw_text` to it (only if `raw_text` is blank), sets `capture.text` to the corrected words, runs `Sensitivity.check` on both and **may set** `sensitive` (it never clears it) and strips the "private note" marker from `text` as `DirectSink` does, sets `extract_state = PENDING`, `extract_attempts = 0`, and updates the search index. Always sets `transcribe_state = DONE`. Returns whether there were words.
10. `recordFailure(captureId, engine, code, retryable, countAttempt)`: inserts a `FAILED` row (no text); sets `RETRY` with attempts + 1 when `countAttempt`, or `GAVE_UP` when the policy in 26f says so. The recording is untouched.
11. `offer(captureId, engine, text, timings)`: for "write it out again" on a note that already has words. Inserts the row as `OFFERED` with `asked_by_owner = 1`, or as `REPLACED` straight away when the corrected new words equal `capture.text`. Does not touch `capture.text`.
12. `use(transcriptionId)`: the old `CURRENT` row becomes `REPLACED`, this one `CURRENT`; `capture.text` becomes the corrected new words (Private check as in item 9, never clearing); a `TEXT_REWRITTEN` revision holds the old and new text; `extract_state = PENDING`. `raw_text` is **not** changed. `keepMine(transcriptionId)`: the row becomes `REPLACED`; nothing else changes.
13. `askAgain(captureId)`: sets `transcribe_state = PENDING`, attempts 0, for a note in `GAVE_UP`, or marks a `DONE` note as wanting a new offer (a small persisted set, like `Container.forceList`).
14. `history(captureId)`: every row, newest first, for the note's page.

### D. Removing, replacing, purging, checking

15. `Repository.recordings.remove(captureId)`: calls 16a's `attachments.remove` for the note's `AUDIO` attachment (row, key and file go at once, with a tombstone) and writes an `AUDIO_REMOVED` revision instead of 16a's `PICTURE_REMOVED` (make 16a's `remove` choose the revision kind from the attachment kind). `transcription` rows and the note's words stay. If the note has **no words** (`text` blank) the caller must delete the note instead; this function refuses and says so, so that a note with neither words nor a recording can never exist.
16. `replaceFile(attachmentId, encoded)`, for 26b's compaction of a `WAV` fallback: write the new file under a new name and key; in one transaction point the row at it (`file_name`, `file_key`, `mime`, `byte_size`, `sha256`, `updated_at`); then delete the old file. A crash leaves an orphan file for 16a's sweep, never a row without a file.
17. `CaptureRepository.purge`: also deletes the capture's `transcription` rows inside its transaction. Soft delete and restore touch nothing new.
18. `ConsistencyCheck`: new violations `RECORDING_STATE_WITHOUT_AUDIO` (a capture in `PENDING`, `RETRY` or `GAVE_UP` with no `AUDIO` attachment), `EMPTY_NOTE` (blank `text`, no attachment), `TWO_RECORDINGS` and `TWO_CURRENT_TRANSCRIPTIONS`. The check stays pure. Reported, never auto-repaired.
19. The debug dump prints `transcription` rows and the two new capture columns. A source-scan unit test: `openBytes` is called for an `AUDIO` attachment only from `app.stash.audio`, `app.stash.pipeline` and (later) `app.stash.backup`; and no file under `model/hosted`, `watch/net` or `lookup/net` mentions `AttachmentRepository`, `Pcm` or `EncodedAudio`. This is the code form of "audio never leaves the phone and is never sent to a model".

## Out of scope

Recording (26a), encoding (26b), the engine (26d), deciding when to transcribe and retry (26f), every screen (26g, 26h, 26j). Backup of recordings (card 10; the index says what it needs).

## Acceptance checks

**Unit tests (JVM; Robolectric where Room is needed)**

- Migration: a database built from the committed previous schema JSON with three captures, their records and one picture attachment opens at the new version with everything unchanged, `transcribe_state` null on all three and an empty `transcription` table.
- Save: a recording goes in as one capture (`PENDING`, extract `DONE`, blank text) and one `AUDIO` attachment whose bytes read back identical; `captures.toRead()` does not return it; a store whose `write` throws leaves no row; a failed transaction leaves the file and returns `Failed`.
- `applyFirst`: sets `raw_text`, corrected `text` ("Sid" becomes "Syd" with the seed rule), `DONE`, extract `PENDING`; "private note the gate code is 4471" ends `sensitive` with the marker stripped from `text` and kept in `raw_text`; blank text ends `DONE` with extract still `DONE`; a second `applyFirst` is refused.
- `offer` then `use`: `raw_text` unchanged, `text` new, one `CURRENT` row, one `REPLACED`, one `TEXT_REWRITTEN` revision. `offer` then `keepMine`: `text` unchanged. `offer` of the same words: no `OFFERED` row left.
- A note the owner made Private stays Private after `applyFirst` and after `use`, whatever the words say.
- `remove`: file gone, row gone, tombstone, `AUDIO_REMOVED` revision, words and `transcription` rows still there; refused on a wordless note.
- `replaceFile`: new bytes read back, old file gone; with a failing delete the old file is swept later.
- `purge` of a recorded note: no capture, attachment or `transcription` rows; no file.
- A second `AUDIO` attachment on one capture is refused. Each new consistency violation is reported for a seeded bad row.
- The source-scan test in item 19.

**Real phone (S25 Edge); experiment log, "Card 26"**

- `python -I tools/peek.py notes` before; `./gradlew installDebug` over the existing app; the same notes after, database at the new version, nothing in the crash log.
- A debug-only self-test (like 16a's): save 10 seconds of generated tone through `RecordingSaver`, read it back, `applyFirst` a fixed sentence, `remove`, purge. Reports pass or fail. Afterwards `adb shell run-as app.stash ls files/att` shows nothing left from it.

## Notes and risks

- Between the save and the first writing-out, a note that begins "private note …" is not yet marked Private (nobody knows what it says). Nothing can be sent in that window, because extraction has not started; what is exposed is that the note's Listen button is not behind the second unlock for those seconds. The Private button on the capture screen marks it from the start. Stated in `docs/PRIVACY.md`.
- Built before card 14, the recording's key sits in an unencrypted database on a debug build: the same exposure as the notes and pictures today, ended by card 14 without touching a file (D77 accepted this).
- `attachment_id` in `transcription` is a soft reference by design; do not add a foreign key (16a uses none, and the delete order is deliberate).

**Suggested builder: Opus.** A migration on the owner's real notes, the save path for a recording, and the delete path: each can lose a note or leave a recording behind.

## What was built, and what differs from the card (2026-11-12)

Built (under `app/src/main/java/app/stash/` unless noted):
- `data/Enums.kt`: `TranscribeState`, `TranscriptionStatus`, `AttachmentSource.RECORDER`, `RevisionKind.AUDIO_REMOVED` and `TEXT_REWRITTEN`.
- `data/Entities.kt`: `CaptureEntity.transcribeState` (nullable) and `transcribeAttempts` (default 0); `TranscriptionEntity` (table `transcription`, every column in item 3, index on `capture_id`, no foreign key). `StashDatabase` is at **version 4**.
- `data/Migrations.kt`: `V3_TO_V4` / `MIGRATION_3_4`, added to `ALL` (now 1→2, 2→3, 3→4). It has exactly four statements, copied from the exported `app/schemas/app.stash.data.StashDatabase/4.json`:
  - `ALTER TABLE capture ADD COLUMN transcribe_state TEXT`;
  - `ALTER TABLE capture ADD COLUMN transcribe_attempts INTEGER NOT NULL DEFAULT 0`;
  - `CREATE TABLE transcription`;
  - its `CREATE INDEX`.

  It changes no existing row: every note gets `transcribe_state` null and `transcribe_attempts` 0. In `4.json` every other table is identical to `3.json`. A test checks all of this.
- `data/Daos.kt`:
  - `TranscriptionDao`;
  - in `AttachmentDao`: `audioForCapture`, `recordingInfo`, `audioTotals`, `replaceFile`;
  - read models with no key: `RecordingInfo(attachmentId, captureId, durationMs, byteSize, mime)` and `AudioTotals(count, bytes, durationMs)`;
  - `CaptureRef` gains `transcribe_state` and `blank`; `AttachmentRef` gains `kind`; new `TranscriptionRef`.
- `capture/InboundSink.kt`:
  - `RecordingSaver.save(encoded, capturedLocked, markPrivate)`. It writes the file first, then hands the sink an `Inbound.Capture`: source `VOICE`, blank words, one `AUDIO` attachment from `RECORDER` with `duration_ms`. On `Failed` the file is kept.
  - `DirectSink` saves a blank-text capture that has an `AUDIO` attachment with `transcribe_state = PENDING` and `extract_state = DONE`. It is Private only if the Private button was pressed. Then the sink calls the new `afterRecordingSave(captureId)` instead of `afterSave`.
- `data/TranscriptionRepository.kt`:
  - `TranscriptionRepository` (`Repository.transcriptions`): `applyFirst`, `recordFailure`, `offer`, `use`, `keepMine`, `askAgain`, `wantsNewOffer`, `history`.
  - `RecordingRepository` (`Repository.recordings`): `info`, `totals`, `remove(captureId)`, `replaceFile(attachmentId, encoded)`.
  - Each change is one transaction. Corrections and the "always private" words are passed in as functions, the way `CaptureEntry` and `DirectSink` take them.
- `data/AttachmentRepository.kt`:
  - `remove` writes `AUDIO_REMOVED` and the `AUDIO` event for a recording, and `PICTURE_REMOVED` otherwise. It leaves the picture offer alone for a recording.
  - `removeIf` asks a question inside the delete's transaction.
  - `openRecording`.
  - `replaceFile`: a new file under a new name and key, one transaction, then the old file is deleted.
  - `AttachmentRows.append` refuses a second recording on one note (`SecondRecording`). It is thrown inside the transaction, so nothing is kept.
  - `announce` writes `Dbg.event(AUDIO, STORED, n1 = bytes, n2 = duration ms, refId = attachment id)` for a recording.
- `data/Repositories.kt`: `insertCapture` takes `extractState` and `transcribeState`. `purge` deletes the capture's `transcription` rows inside its transaction; the recording's row, tombstone and file go the way 16a's do.
- `data/ConsistencyCheck.kt`: `RECORDING_STATE_WITHOUT_AUDIO`, `EMPTY_NOTE`, `TWO_RECORDINGS`, `TWO_CURRENT_TRANSCRIPTIONS`. The check stays pure. They are reported, never repaired.
- `data/Container.kt`:
  - `Container.recordingSaver`;
  - `Container.afterRecordingSave`, a no-op until 26f;
  - `PrefsCaptureIdSet` (preferences file `write_again`), which holds item 13's "write it out again" list.
- Debug only: `app/src/debug/.../RecordingSelfTestReceiver.kt`, and its one `<receiver>` entry in `app/src/debug/AndroidManifest.xml`. The dump (`DumpReceiver`) needed no change: it already prints every table, so it shows `transcription` rows and the two new capture columns. A test checks that it still holds no key.
- Tests:
  - `RecordingStoreTest` (22, Robolectric, real Room and real encrypted files);
  - `MigrationV4Test` (3);
  - `RecordingSourceScanTest` (3);
  - one new test in `ConsistencyCheckTest`.

  `MigrationTest` and `AttachmentTest` were updated for version 4 and for `openRecording`. The version-2 file now opens at version 4, and its capture rows are compared column by column with the two new columns appended as null and 0. Every acceptance check in the card's unit-test list has a test.

**The migration test** builds a real version-3 file from `3.json`, seeded with six notes:
- a placement with its subject;
- a reading with its metric;
- a Private pinned task with a revision;
- a shared note with two `PHOTO` attachments, one with words read from it;
- a picture-only note;
- a note in the bin.

It also holds a tombstone, an event, a nudge, the word list and a correction, and the attachments' encrypted files really exist. Opened through Room at version 4:
- every table except `capture` is identical cell for cell, keys included;
- `capture` is identical, with `null, 0` appended to each row;
- `transcription` is empty and has its index;
- search finds the words and the picture words, and the pictures read back;
- nothing waits to be read or written out, and the consistency check is clean;
- a recording can then be saved into the migrated database.

What differs from the card, and why:
- **`openBytes` refuses a recording** (`AttachmentUnreadable.Reason.WRONG_KIND`, code 7), and a new `openRecording` opens only recordings. The card's source scan ("`openBytes` for an `AUDIO` attachment only from audio, pipeline, backup") cannot tell from the text which kind a call opens. With two doors it can: the scan allows `openRecording(` only in `audio/`, `pipeline/`, `backup/` and its own definition. 16a's audio test now uses `openRecording`.
- **`applyFirst` returns `First.WORDS`, `NO_WORDS` or `REFUSED`** instead of a Boolean, so a refused second call is not mistaken for "no words". `askAgain` likewise returns `WAITING_AGAIN`, `OFFER_NEXT` or `NOTHING`.
- **`recordFailure` takes the give-up rule as a function** (`giveUp(attempts)`), because card 26f owns that policy.
  - Until 26f, a failure that cannot be retried gives up, and every other failure goes to RETRY.
  - A FAILED row is written in every state. The state changes only while the note is PENDING or RETRY.
  - The reason code is cut to ASCII letters, digits and `_` (at most 40 characters), so no words can get in.
- **`transcription` has no `updated_at`.** The card's column list has none. Rows change only their `status`, as `extraction` rows do. Every action that changes the note stamps the capture's `updated_at`.
- **When words make a note Private, no revision is written.** The app sets the mark, not the owner, just as `DirectSink` does at save time. `MADE_PRIVATE` stays an owner action.
- **The marker is stripped whenever the note is or becomes Private.** This matches `DirectSink`, which strips it when the Private button was pressed.
- **Offers:**
  - an offer with no words goes in as REPLACED, because there is nothing to offer;
  - a new offer closes an older one that is still open;
  - `use` also accepts an earlier REPLACED version that has words (for 26h's "earlier versions"), and never a CURRENT or FAILED row.
- **"At most one recording" is enforced where rows are written** (`AttachmentRows.append`), so it holds for a new capture and for `AddAttachments` alike.
- **`replaceFile` does not change `duration_ms`.** The card's list of columns leaves it out, and compaction does not change the length.
- **Events.** `AUDIO STORED` comes from the same place as `PICTURE STORED`. Removing a recording logs `AUDIO REMOVED`. A file that could not be written logs `AUDIO FAILED` with n1 = 300 (26b uses the 100s and 200s).
- **Backup and export needed no change.** 16a already excludes `files/att/` from cloud backup and device transfer, and `exportText` writes words only. A test checks that the export of a recorded note holds no file name, id, hash or mime.
- **The phone self-test does not touch his notes**, as 16a's does not: the rows go into a throwaway in-memory database, and the file goes into the real `files/att/`.
  - It saves 10 s of a 440 Hz tone as WAV (no codec involved) through `RecordingSaver`.
  - It reads it back through `openRecording`, applies a fixed sentence, removes the recording, purges the note, and checks that no file is left.
  - It writes `files/debug/recording-selftest.json` (numbers and names only).
  - The same function runs in a JVM test.

Unverified, still to do (needs the owner; nothing was installed and no adb command was sent):
- The migration from version 3 to 4 on his real notes, and the self-test on the S25 Edge. The steps are in `docs/EXPERIMENT_LOG.md`, "Card 26c".
