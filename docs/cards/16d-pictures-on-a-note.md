# 16d · Pictures on a note: the thumbnail strip, adding later, removing one

Read [00-READ-FIRST.md](00-READ-FIRST.md) first. Part of the card 16 split ([index](16-share-and-pictures.md)).

**Status: built 2026-11-12, JVM only** (529 unit tests pass, 40 new; no schema change, no new dependency). Nothing was installed on the phone: the phone checks wait for the owner. See "What was built, and what differs" at the end.

## Goal

A note that has pictures shows them. He can look at one, add more to a note at any time, and remove one. A note can have any number.

## Depends on

16a, 16b, 16c (for the photo-picker helper and the thumbnails' look).

## Context

Read `ui/MainActivity.kt` (the Notes list; each note is a row with its text, "Correct a word", the Private switch and delete), `ui/MainViewModel.kt` and `ui/PrivateLabel.kt` first. There is **no separate page for one note** today. Do not build one: the pictures go on the note's row, and a full-screen viewer opens over the list.

Card 16's rules that this card must keep true:

- A picture is shown only inside `MainActivity`, the one reading activity. Never in a notification, a widget, a digest, or the recent-apps view.
- A picture on a note marked Private gets the second unlock when card 02 exists.
- Nothing decrypted is written to disk; thumbnails are made in memory.

## Scope

### A. Showing

1. `MainActivity.onCreate` sets `FLAG_SECURE` on its window, always, in every build. This is what keeps pictures out of the recent-apps view and out of screenshots. Card 02 later owns this line. (Consequence: `tools/peek.py screen` shows a black picture for this activity from now on; read the screen's text with `uiautomator dump` instead, as the experiment log already advises.)
2. `ui/pictures/PictureLoader`: given an `AttachmentInfo`, opens it with `Repository.attachments.openBytes`, decodes it to at most the size asked for, on a background dispatcher, and keeps the result in an in-memory cache of at most 8 MB keyed by attachment id and size. The cache is cleared when the activity stops and whenever the app locks. A file that cannot be opened gives a grey tile that says "Picture missing".
3. `ui/pictures/NotePictures(captureId)`, **the only composable that draws stored pictures**: a strip under the note's text, scrolling sideways, one square thumbnail per picture in `position` order, each at least 72 dp. It loads lazily, so a note with 60 pictures costs no more than the few on screen. When the note has more than fits, the strip simply scrolls; a small "5 pictures" label sits at its start. A note with no pictures shows no strip.
4. **Private notes.** Until card 02 is built there is no second unlock. For a `sensitive` capture the strip is replaced by one row, "3 private pictures · Show"; tapping Show draws them until the app leaves the foreground. When card 02 exists, wrap the strip in its `PrivateContent { }` and delete this interim row. Either way nothing else decides whether a private picture is drawn.
5. **The viewer:** tapping a thumbnail opens that picture full-screen inside `MainActivity` (a composable over the list, not a new activity). Pinch to zoom, drag to pan, swipe sideways for the note's other pictures in order, "2 of 5" at the top, a Close button. When card 16f has stored words for the picture, "Read from the picture" and the words are shown beneath, collapsed to three lines.
6. Soft-deleted notes do not show their pictures anywhere; a restored note shows them again (nothing to build: the list already hides them; check it).

### B. Adding later

7. Each note's row gains **Add a picture**. It opens the system photo picker (the helper from 16c; several can be picked). Each picked picture is prepared (`source = PICKER`) and the list is handed over as one `Inbound.AddAttachments(captureId, …)` through `container.sink`. Show "Adding 2 of 5" on the row while it runs. New pictures appear at the end of the strip. (Card 16e adds "Camera" as a second choice on this button.)
8. If the sink returns `Failed`, discard the prepared files and say "I couldn't add that picture."
9. Adding a picture never changes the note's words, its records or its Private mark.

### C. Removing one

10. The viewer has **Remove picture**. It asks first: "Remove this picture? It can't be brought back." with Remove and Keep. Remove calls `Repository.attachments.remove(id)` (16a: the row, the key and the file go at once) and the viewer moves to the next picture, or closes if none is left.
11. If the picture is the **only** one on a note that has **no words**, the question is instead: "This note is only this picture. Remove the note?" and Remove calls the existing `Repository.deleteCapture(id)` (the note goes to the 30-day bin with its picture, like any deleted note). A note with neither words nor pictures must never exist.
12. Removing does not renumber the others; the order of the rest is unchanged.

### D. Housekeeping the owner can see

13. Settings, under storage: "Pictures: 42, using 11 MB" from a count and a sum of `byte_size`.

## Out of scope

Reordering pictures by hand. Choosing a different cover. Cropping or editing. Sharing a picture out of the app. The camera (16e). Reading words (16f). The note-first offer (16g). A grid of pictures across notes (16h). A page for one note.

## Acceptance checks

**Unit tests (Robolectric with a seeded database and a fake `PictureLoader`)**

- A note with three attachments draws three thumbnails in position order; a note with none draws no strip; a seeded `AUDIO` attachment is not drawn.
- A `sensitive` note draws the "private pictures · Show" row and no thumbnail; after Show it draws them; after the activity stops and starts it is hidden again.
- Add: two picked pictures on a note with three gives five, the new two last; the note's text and records are unchanged.
- Remove the middle of three: two left, in their old order; the confirmation was required.
- Remove the only picture of a wordless note: the capture is soft-deleted, not left empty.
- A soft-deleted note's pictures are not in the list; after `restore` they are.
- A loader that fails gives the "Picture missing" tile and no crash.
- The window has `FLAG_SECURE` after `onCreate`.

**Real phone (S25 Edge); write each result in the experiment log**

- Share five pictures into one note (16c); open the app: five thumbnails, in the order shared. Open the third, swipe to the fifth, zoom in on small print.
- Add two more from the gallery to that note; they appear last. Remove the first; the rest keep their order. `adb shell run-as app.stash ls files/att | wc -l` goes 5, 7, 6.
- Delete the whole note; its pictures vanish from the app. (They leave the disk when the 30-day purge runs; card 16a's tests cover that.)
- Mark a note with pictures Private: only the "private pictures" row shows until Show is tapped.
- With the Notes list showing pictures, open recent apps: the Stash card shows no picture. Take a screenshot: refused.
- Share thirty pictures into one note and scroll the list and the strip: note any stutter, and the app's memory in `adb shell dumpsys meminfo app.stash` before and after.
- At 200% font size and with TalkBack: each thumbnail is announced as "Picture 2 of 5", and Add and Remove can be reached.

## Notes and risks

- Before card 02 anyone holding the unlocked phone can open Stash and see every picture that is not Private, exactly as they can read every note today. That is the stated condition until the hardening block.
- Do not pass a decrypted `Bitmap` or bytes through an `Intent` or saved state; hand around attachment ids only.
- `FLAG_SECURE` is a change the owner will notice (no screenshots of Stash). It is required by card 16's "never in recents"; say so in the hand-back.

**Suggested builder: Sonnet.** It is Compose wiring over repository calls that card 16a already tested; the one destructive action is a single, confirmed call.

## Owner decision, 2026-11-12 (D80)

1. **Remove one picture is immediate and final** after one confirmation: no bin, but a revision row and a tombstone as the card says. 2. **`FLAG_SECURE` on every reading screen, including the main screen, accepted now.** Add a **debug-only toggle** (a switch on the debug settings screen, code under `app/src/debug/`) that clears the flag so `peek.py screen` and screenshots work during development. The release source set must not contain the toggle: it sets the flag unconditionally. Add an acceptance check that searches the release source set and the release APK for the toggle's name and finds nothing, and a note in card 14's release checklist.


## What was built, and what differs from the card (2026-11-12)

Built (under `app/src/main/java/app/stash/` unless noted):
- `domain/PictureStrip.kt` (pure): `PictureWords` (every line of this card, and the Settings storage line), `PictureRemoval` (which question, and where the viewer goes after a removal), `PicturePrivacy`, `PictureViewing.sampleSize`, `SizedLruCache` (byte-budgeted, thread-safe) and `ZoomState` (pinch and pan as numbers, clamped).
- `ui/pictures/PictureLoader.kt`: `PictureLoader` (interface) and `StoredPictureLoader`: opens through `Repository.attachments.openBytes`, decodes in memory on `Dispatchers.IO` with `inSampleSize`, 8 MB cache keyed by attachment id and size, `clear()`; any failure is `Missing` (never thrown). `PictureSession`: the Private notes the owner tapped "Show" on, and the viewer request (ids only); `onStop()` and `onLock()` hide everything, close the viewer and clear the cache.
- `ui/pictures/NotePictures.kt`: the only composable that draws stored pictures: label "5 pictures", then 88 dp thumbnails in a `LazyRow`, "Picture 2 of 5" for TalkBack, a grey "Picture missing" tile; for a Private note one row "3 private pictures · Show" and nothing is opened or decoded until the tap. `ui/pictures/PictureViewer.kt`: a composable overlay in `MainActivity` (not a new activity): `HorizontalPager`, pinch to zoom and drag to pan (one finger on an unzoomed picture is left to the pager), "2 of 5", Close, "Read from the picture" collapsed to three lines when `ocr_text` exists, Remove picture with one question. `ui/pictures/PictureHost.kt` (the interface screens use, and `RepositoryPictureHost`, which decides the question again at the moment of removal) and `ui/pictures/PictureAdder.kt` (prepare each picked picture with source `PICKER`, one `Inbound.AddAttachments`, discard on `Failed`).
- `MainActivity`: `ScreenSecurity.secure(window)` first thing in `onCreate`, unconditional; `onStop` calls `PictureSession.onStop()`; the strip under each note's words, **Add a picture** on each row (system photo picker, several at once; "Adding 2 of 5" on the row; "I couldn't add that picture." on failure), the viewer overlay, and in Settings "Pictures: 42, using 11 MB". `MainViewModel` holds the loader, session, host, adder, progress and the stats. `ui/ScreenSecurity.kt` is the one place the flag is set.
- `AttachmentDao.pictureStats()` and `AttachmentRepository.pictureStats()` (a count and a sum of `byte_size` for pictures): read-only additions to card 16a's files, no behaviour changed, no schema change.
- **Debug only** (`app/src/debug/`): `debug/ScreenshotToggle.kt` (the switch's state, and `DebugSecureInit`, a content provider declared only in the debug manifest that registers a lifecycle callback; it clears the flag on `MainActivity` when the switch is on and sets it again when off) and `debug/DebugSettingsActivity.kt` (the screen with the switch, off by default; open with `adb shell am start -n app.stash/app.stash.debug.DebugSettingsActivity`). Nothing in `app/src/main` names any of it.
- Tests (40 new, 529 in all): `PictureStripTest` 9 (plain JVM), `ScreenshotToggleAbsentFromReleaseTest` 3 (plain JVM: scans `src/main` and `src/release` for every name in the debug files, two guards so it cannot pass by scanning nothing, and a pin that `onCreate` calls `ScreenSecurity.secure(window)` as a statement of its own), `PicturesOnNoteTest` 21 (Robolectric, seeded database, fake loader), `StoredPictureLoaderTest` 4 (Robolectric native graphics, real store), `ScreenFlagTest` 3 (the flag on a real window; the debug switch clears and restores it).

Differs from the card, and why:
- **No test launches the real `MainActivity`.** `MainViewModel` casts the application to `StashApp`, whose `onCreate` needs WorkManager initialised (it is disabled in the manifest); a `@Config(application = StashApp::class)` run fails with "WorkManager is not initialized properly". The card's check "the window has `FLAG_SECURE` after `onCreate`" is covered instead by `ScreenSecurity.secure` on a real window, a test that `onCreate` contains the call as its own statement before `setContent`, and the phone check. The Notes row wiring inside `MainActivity` (picker, progress line, strip placement) has no UI test for the same reason; its pieces do.
- **The debug switch is applied by a lifecycle callback, not by a line in `MainActivity`**, because the release source set may not reference it at all. `MainActivity` always sets the flag; in a debug build the callback may clear it afterwards. It applies to `MainActivity` only. `ShareActivity` still sets `FLAG_SECURE` unconditionally (the card does not ask for the switch there), so the share screen cannot be screenshotted even in a debug build.
- **The release-APK half of the acceptance check was not run** (no release build here). The source-set half is a test. After `assembleRelease`, `unzip -p app-release.apk 'classes*.dex' | grep -c ScreenshotToggle` must print 0 (likewise `DebugSecureInit` and `DebugSettingsActivity`). **Card 14's release checklist was not edited** (this build may not edit other cards); D80 asks for that line to be added there.
- **The viewer closes when the app leaves the foreground** (for every note, not only Private ones), and the photo picker is a separate activity, so adding a picture to a Private note hides its pictures again when you return. Simple and safe; say so if it is annoying.
- **A note with no words shows no text line** (a picture-only note would otherwise show an empty heading).
- **Add a picture with Pictures switched off** says "Pictures are switched off in Stash. Turn them on in Settings." (the line card 16c already uses); the card is silent.
- **The question is decided again at the moment of removal.** If the note changed between the question and the tap so that a different question applies, nothing is removed.
- The viewer is drawn at the stored size (up to 4,096 on the long edge), thumbnails at 384; the viewer decodes only the picture on screen. A picture too big for the 8 MB cache is shown but not kept.
- The strip's "N pictures" label is the first item of the sideways list, so it scrolls away with the strip.
- Camera: not built (card 16e). The place for it is marked by a comment on the Add a picture button.
- Dialogs use `SecureFlagPolicy.SecureOn`, because a dialog is its own window and `FLAG_SECURE` on the activity does not cover it.

Unverified / still to do (the owner; nothing was installed): every real-phone check in the card's list, written up in `docs/EXPERIMENT_LOG.md`, "Card 16d". Pinch, pan, swipe, the real decode time and memory are unproven until then. `FLAG_SECURE` is a change the owner will notice: no screenshots of Stash, and `tools/peek.py screen` shows black unless the debug switch is on.
