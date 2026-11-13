# 26j · Recordings in Settings: space used, what is waiting, deleting many, and what backup needs

Read [00-READ-FIRST.md](00-READ-FIRST.md) first. Part of the card 26 split ([index](26-voice-notes-with-audio.md), D77, D82).

## Goal

The owner can see how much space his recordings take and whether any are still waiting to be written out, and can delete recordings in bulk while keeping every word. The rule he chose stays the rule: **a recording is kept until he deletes it or its note.** Nothing here deletes by itself.

## Depends on

26c (`audioTotals`, `recordings.remove`), 26f (`TranscriptionStatusSummary`). Best after 26h, so that removing one recording from a note already exists. Card 10 (backup) is **not** needed; section C says what that card must do when it is built.

## Context

Read `ui/MainActivity.kt` (`SettingsScreen`), card 16d item 13 (the "Pictures: 42, using 11 MB" line this sits beside) and card 16a (purge, sweep).

How a recording can leave the phone's storage, all in one place:

| Way | What goes | Can it be undone |
| --- | --- | --- |
| Delete the note | The note, to the 30-day bin, with its recording. After 30 days the purge removes the row, the key and the file (16a) | Yes, for 30 days |
| Undo on the capture screen (5 seconds) | The same | Yes, for 30 days |
| Remove the recording from the note (26h) | The file, its key and its row, at once. The words stay | No |
| The bulk actions below | As "remove the recording", for many notes | No |
| Delete everything (card 10) | Everything | No |

Size, measured: Opus at the default setting is about 170 KB a minute. Ten ten-second notes a day is about 100 MB a year (an estimate from that figure).

## Scope

### A. What Settings shows (a "Voice notes" section)

1. **"Recordings: 214, 3 h 12 min, using 34 MB"** from `audioTotals()`. Beside it 16d's pictures line, if built.
2. **"Speech model: Moonshine base, 141 MB"** with Remove, or "No speech model added. Voice notes are written down by the phone as you speak, and no recording is kept." with **Add the speech model** (26d's importer).
3. **Waiting:** "2 recordings are waiting to be written out" and, if any, "1 could not be written out", each opening the Notes list filtered to those notes. Under 26f's outcomes J2 or J3 also the sentence that outcome names ("Recordings are written out when Stash is open or the phone is charging").
4. The switch **"Keep a recording of each voice note"** (26g's capability) with its without-it sentence, the "End a voice note after a pause of" choice (26a), "Recording quality" (26b), and, only if check C2 was blocked, "When the phone is locked" (26g).
5. A line that is always there and always true: "Recordings never leave this phone. They are not sent to any model."

### B. Deleting many (`data/`, then the screen)

6. `recordings.removable(olderThan: Long?): List<RecordingInfo>`: the recordings of live notes that **have words** (`text` not blank) and are not waiting to be written out. A recording that is the only thing a note holds is never in this list.
7. **"Delete recordings, keep the words"** opens a choice: older than 3 months, older than 6 months, older than a year, or all. It then says exactly what will happen: "Delete 180 recordings (29 MB)? The words of those notes stay. The recordings can't be brought back. 3 recordings are kept because they haven't been written out." and needs one confirming tap on a button that repeats the number ("Delete 180 recordings"). When card 02 exists this sits behind the app gate like the rest of Settings.
8. It calls `recordings.remove` for each (26c: row first, then file, a tombstone and an `AUDIO_REMOVED` revision each), in batches of 50 with a progress line, and can be interrupted safely: whatever was done is done, the rest is untouched. Then runs 16a's sweep.
9. There is **no** automatic age limit, no "keep only the newest N", and no deletion when storage is low. If the phone is nearly full, saving a new recording fails visibly on the capture screen (26g's "I couldn't save that note. Save again"), and this screen is where he makes room.

### C. What card 10 (backup, export, delete everything) must do; not built here

Card 10 is later in the order. When it is built, these are needed, and until then recordings, like pictures and notes, have no backup.

10. **The registry (card 10 item 1):** `transcription` is on the "backed up" list (it is memory once a recording is removed). `capture.transcribe_state` and `transcribe_attempts` travel with the capture.
11. **Recordings in the backup (item 4):** the zip gets one entry per attachment, `att/<attachment id>`, for **recordings as well as pictures**, holding the decrypted bytes inside the encrypted container; the row is exported without `file_key` and a restored file gets a new key (16a). This is the explicit statement rule 4 asks for: **a recording may be in the app's own encrypted backup, and nowhere else.**
12. **A choice, default on:** "Include recordings in backups (adds about 34 MB to each backup)". With it off, `AUDIO` attachment rows and files are left out, **except** for notes not yet written out (their recording is the only content they have), and `transcription` rows are kept. After a restore from such a backup the notes have their words and no Listen control, and the consistency check must not report them. Card 10 keeps the newest five backups, so the size is paid five times; the screen says so.
13. **Restore:** a restored note still `PENDING` or `RETRY` is written out on the new phone once a model pack is there (`TranscriptionScheduler.requestNow()` after import).
14. **Exports (items 9 to 11):** the JSON and Markdown exports hold words only; a recorded note's Markdown entry adds "(recording kept, 0:12)". No export contains sound. Saving a copy of one recording out of the app is **not designed**; the index lists it as a question.
15. **Delete everything (items 13, 14):** the explanation names recordings; `files/att/` already covers the files. The speech model pack is deleted too (the app restarts as a fresh install) and the screen says it will need adding again.
16. **Device transfer:** already excluded for `files/att/` (16a) and `files/models/` (26d).

## Out of scope

Building any of section C. Compressing old recordings further. Moving recordings to an SD card or a cloud folder. Showing storage by month. Per-note deletion (26h).

## Acceptance checks

**Unit tests (Robolectric with a seeded database)**

- Totals: three recordings of known size and length give the right count, duration and bytes; a soft-deleted note's recording is not counted as removable but is counted in bytes until it is purged (say so on the line: "includes 2 in the bin").
- `removable`: excludes a wordless recorded note, a `PENDING` one and a `GAVE_UP` one; the age filter uses the note's `created_at`.
- Bulk delete of 120: no rows, no files, 120 tombstones and revisions, every note's `text` unchanged, the three exclusions still have their recordings; interrupted after the first batch, a second run finishes the rest.
- The waiting counts match seeded states.
- With no model pack the section shows the "no speech model" sentence and the Add button.

**Real phone (S25 Edge); experiment log, "Card 26"**

- After a week of use: the totals line, and the real average size of a note. Compare with the 170 KB a minute estimate and correct the wording if it is off by more than a fifth.
- Delete recordings older than one day (a debug-only extra choice): the count and `adb shell run-as app.stash ls files/att | wc -l` fall together; the notes still have their words; a deleted recording's note shows no Listen control.
- Every line at 200% font size.

## Notes and risks

- The bulk delete is the one place in the feature where a single tap destroys many recordings. The count on the button, the exclusion of wordless notes and the lack of any automatic variant are the protections. Do not add a shortcut.
- "Kept until deleted" means storage grows for ever. At about 100 MB a year this is small beside the model (141 MB) and beside a year of pictures, and the totals line makes it visible. If a real year turns out much larger, the owner already has the tool.

**Suggested builder: Opus.** The screen is simple, but the bulk delete cannot be undone and section C fixes what the backup card will be told to do with the most private data the app holds.
