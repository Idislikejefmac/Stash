# 26h · On a note: listen, fix the words, write it out again, remove the recording

Read [00-READ-FIRST.md](00-READ-FIRST.md) first. Part of the card 26 split ([index](26-voice-notes-with-audio.md), D77, D80, D82).

## Goal

A note that has a recording can be heard. While hearing it he can put the words right, ask for it to be written out again, see what was written before, and remove the recording while keeping the words. This is the owner's reason for the whole feature: a wrong transcription no longer has to become "the fact".

## Depends on

26b (the stored formats; its `AudioDecoder` only as the playback fallback in "Notes and risks"), 26c (`RecordingInfo`, `transcriptions`, `recordings.remove`), 26f (`askAgain`, offers), 26g (notes with recordings exist; its row status lines). 16d for the pattern it copies (one composable draws stored media; `FLAG_SECURE`; the Private stopgap). If 16d is not built yet, this card sets `FLAG_SECURE` on `MainActivity` itself, exactly as 16d item 1 and D80 say, including the debug-only toggle.

## Context

Read first: `ui/MainActivity.kt` (the Notes list: each note is a row with its text, "Read it again", "Correct a word", the Private switch and delete; there is **no page for one note**, do not build one), `ui/MainViewModel.kt`, `ui/PrivateLabel.kt`, `domain/Corrector.kt` (`CorrectionLearner.learn` and `replaceWord`), `Repository.correctWord`.

**One honest fact shapes this screen.** The engine is deterministic: the same model on the same recording writes the same words. "Write it out again" therefore helps only when something has changed: a different model pack is installed, the first attempt failed, or the note was written out by an older engine. Otherwise the useful thing is to **listen and fix the words by hand**, and the screen must say so instead of offering a button that does nothing.

Rules kept from the decisions:

- A recording is played **only inside `MainActivity`**, the one reading activity, never from a notification, a widget, the capture screen, or a spoken answer. There is no media notification, no lock-screen control and nothing sent to a car or watch display.
- A recording on a Private note is behind the second unlock (card 02's `PrivateContent { }`; until card 02 exists, the same tap-to-show stopgap 16d uses).
- Nothing decrypted is written to disk. A recording is small (a three-minute note is about half a megabyte), so it is decrypted into memory to play.

## Scope

### A. Playing (`ui/audio/`, `audio/NotePlayback.kt`)

1. `NotePlayback`: given an attachment id, reads the bytes through `Repository.attachments.openBytes` on a background dispatcher, wraps them in a `MediaDataSource`, and plays with `MediaPlayer` (speech content type, media usage, audio focus requested and honoured). `play`, `pause`, `seekBy(-5 s)`, `position`, `duration`, `stop`. One recording plays at a time. The bytes are dropped on `stop`.
2. It stops at once when: `MainActivity` stops; the app locks (`AppLock.lockNow()`, when card 02 exists); the screen turns off; audio focus is lost; the note is deleted or its recording removed. It never creates a `MediaSession`.
3. `NoteRecording(captureId)`, **the only composable that plays stored sound**, under the note's text: **Listen · 0:12** (Play/Pause, at least 56 dp), a position bar, **Back 5 s**. A recording that cannot be opened shows "Recording missing" and no crash. A soft-deleted note shows nothing.
4. Private notes: until card 02, the control is replaced by "Private recording · Show" and appears after the tap, until the app leaves the foreground; with card 02, wrap it in `PrivateContent { }` and delete the stopgap. Nothing else decides whether a private recording can be played.

### B. Looks wrong (the fix panel)

5. Each recorded note's row gains **Looks wrong**. It opens a panel over the list (a composable, not a new activity) with:
   - the player from item 3;
   - the note's words in an editable field, and **Save**: this is the existing text-correction path (`captures.updateText`, a `TEXT_CORRECTED` revision, the note is read again). Before saving, show the single-word changes found by `CorrectionLearner.learn(before, after)` as ticked lines, "Remember: Cabot → Cavit", so that each becomes a correction rule and a word in the word list as "Correct a word" does today. He can untick one;
   - a line saying how it was written out: "Written out by Stash (Moonshine base) on 12 Nov";
   - **Earlier versions**: `transcriptions.history`, collapsed: the first writing-out (`raw_text`) and any later ones, each with its date and model. Read-only.
6. **Write it out again** is shown in the panel only when it can change something:
   - the note is in `GAVE_UP` (label: **Try again**);
   - or the installed model's id differs from the one that wrote the current words (a different or newer pack);
   - or two packs are installed (label: **Try the other model**).
   Otherwise the panel shows the sentence: "The same model would write the same words. Listen and fix them here."
7. It calls `transcriptions.askAgain` and shows "Writing it out…". When the offer arrives (26c `OFFERED`): "New: … / Now: …" with **Use the new words** (`transcriptions.use`: the words change, the note is read again, `raw_text` and every earlier version stay) and **Keep mine** (`keepMine`). If the same words came out: "The same words came out."
8. **Remove the recording**, at the bottom of the panel. It asks first: "Remove this recording? The words stay. It can't be brought back." with Remove and Keep. Remove calls `recordings.remove` (the row, the key and the file go at once). If the note has **no words**, the question is "This note is only this recording. Remove the note?" and Remove calls the existing `Repository.deleteCapture` (the 30-day bin, like any note).
9. Opening the panel marks nothing and sends nothing. Closing it without a change leaves the note exactly as it was.

### C. States on the row

10. Replace 26g's minimal status lines with the full set: "Writing it out…" (a pass is running), "Not written out yet · Listen", "I couldn't write this one out · Listen · Try again", "No words heard · Listen · Remove". A note whose recording was removed looks like any other note, with one small line in the panel's history: "Recording removed on 3 Dec".

## Out of scope

A page for one note; card 09's capture page will later show the same panel (see the index's list of lines to change). Playing recordings from search results or answers (they open the note's row). Sharing or saving a recording out of the app (the app contains nothing that exports sound; the index lists this as a question for the owner). Trimming or editing sound. Playback speed. The doubtful-words mark (26i). Storage totals and deleting many recordings (26j).

## Acceptance checks

**Unit tests (Robolectric with a seeded database, a fake `NotePlayback`, a fake transcriber)**

- A note with a recording draws the Listen control; one without draws none; a picture attachment is not offered as a recording.
- A `sensitive` note draws "Private recording · Show" and no control; after Show it draws it; after the activity stops and starts it is hidden again.
- Playback is stopped by activity stop and by removal; `MainActivity`'s window has `FLAG_SECURE`.
- Fix and Save: `text` changes, `raw_text` does not, one `TEXT_CORRECTED` revision, a ticked single-word change becomes a correction rule and an unticked one does not, the note goes back to be read.
- "Write it out again" is absent when the current writing-out came from the installed model and only one pack exists, and the explanatory sentence is shown; it is present for `GAVE_UP`, for a different model id, and for two packs.
- Offer, then Use: words replaced, history has both. Offer, then Keep mine: words unchanged, history has both.
- Remove on a note with words: recording gone, words and history stay, the confirmation was required. Remove on a wordless note: the capture is soft-deleted, never left empty.
- A fake playback that fails to open gives "Recording missing".

**Real phone (S25 Edge); experiment log, "Card 26"**

- Record "Total Wine has the Cavit Pinot Grigio for eleven ninety nine". In Stash, listen to it. Fix "Cabot" to "Cavit" in the panel with the rule ticked. Record the sentence again: the new note says Cavit.
- Listen on the speaker, then with the phone to the ear or on headphones: note which output Android chose and whether that is what he expected.
- While it plays: press Home (it stops); turn the screen off (it stops); open recent apps (the Stash card shows nothing); try a screenshot (refused).
- Mark the note Private: the control is hidden until Show (or the second unlock).
- Import the tiny pack beside base: **Try the other model** appears; use it; both versions are in the history.
- Remove the recording: the words stay; `adb shell run-as app.stash ls files/att | wc -l` drops by one.
- At 200% font size and with TalkBack: Listen, Back 5 s, Save and Remove are reachable and named.

## Notes and risks

- Whoever is nearby hears a recording played on the speaker. There is no warning for ordinary notes (it is his own voice saying what the screen already shows); a Private note has already asked for the second unlock.
- `MediaPlayer` with a `MediaDataSource` and Ogg Opus: medium to high confidence. If it refuses the stored format, decode with 26b's `AudioDecoder` and play the samples through `AudioTrack`; say which was used.
- Before card 02, anyone holding the unlocked phone can open Stash and listen to every recording that is not Private, just as they can read every note today. That is the stated condition until the hardening block (D77 accepted it).
- Do not pass sound or decrypted bytes through an `Intent` or saved state; hand around ids only.

**Suggested builder: Sonnet.** Compose wiring over repository calls that 26c and 26f already tested; the two destructive actions are single, confirmed calls. Give it to Opus instead if 16d has not been built, because then this card also sets the window flag and the debug toggle.
