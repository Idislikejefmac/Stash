# 16c · Share to Stash and the "Anything to add?" screen

Read [00-READ-FIRST.md](00-READ-FIRST.md) first. Part of the card 16 split ([index](16-share-and-pictures.md)).

**Status: built 2026-11-12, JVM only** (new tests listed below; no schema change, no new dependency). Nothing was installed on the phone: the phone checks wait for the owner. See "What was built, and what differs" at the end.

## Goal

He takes a screenshot (or picks pictures in the gallery, or selects text in any app), taps Share, picks Stash, says or types a sentence or nothing, and taps Save. One note is made with all the shared pictures attached. This is the main route for screenshots and needs no permission.

## Depends on

16a, 16b.

## Context

Read `capture/CaptureActivity.kt`, `capture/InboundSink.kt` (`CaptureEntry.save`), `capture/Cues.kt`, `speech/SpeechEngine.kt` and the manifest first.

`ShareActivity` is an **adding** activity (00-READ-FIRST rule 5; card 02): it shows only the thing being added now, never a stored note, and links to nothing stored. It keeps working when card 02's gate arrives.

Every picture rule from card 16 holds on this screen: it blocks screenshots and stays out of the recent-apps view; it posts no notification; nothing is sent anywhere.

Android's own screenshot toolbar (the strip that appears after a screenshot on Samsung phones) has a Share button. It is believed to open the ordinary share sheet with one image; this is not verified and is a phone check below.

## Scope

### A. Receiving

1. `share/ShareActivity`, in the manifest: `exported="true"`, `excludeFromRecents="true"`, `taskAffinity=""`, not `showWhenLocked`. (Do not set `noHistory`: the photo picker in item 6 must be able to return a result.) Intent filters: `ACTION_SEND` with `image/*`; `ACTION_SEND_MULTIPLE` with `image/*`; `ACTION_SEND` with `text/plain`. In `onCreate` set `FLAG_SECURE`.
2. Read the shared content **at once in `onCreate`**: permission to read a shared picture lasts only as long as this activity. Take the `Uri`s from `EXTRA_STREAM` (one or a list), and any `EXTRA_TEXT`.
3. Capability `PICTURES` (add the row to `domain/Capability.kt`: no permission, on by default, without it: "Notes are words only."). When it is off, a shared picture shows "Pictures are switched off in Stash. Turn them on in Settings." and one Close button; shared text still works.
4. Each picture goes through `PicturePreparer.prepare(uri, kindHint = null, source = SHARE)`, one after another, in the order they were shared. While that runs the strip shows "Getting 3 of 12 ready". A picture that cannot be prepared is left out and one line says "1 picture could not be added". There is no limit on the number.
5. Shared text (a sentence, a link) becomes the starting content of the text field, which he can edit. If both text and pictures were shared, both are used.

### B. The annotate screen (`share/AnnotateScreen.kt`, a composable; card 16e reuses it)

6. From top to bottom:
   - **The strip:** one thumbnail per picture being added, in order, scrolling sideways, each at least 56 dp with a small "remove" mark (removing calls `PicturePreparer.discard`). Tapping a thumbnail shows that picture larger on this same screen.
   - **A draft line**, only when card 16f is built and found words: "Read from the picture: …". Until then this slot is empty; leave a parameter for it.
   - **"Anything to add about this?"** with the words heard or typed so far.
   - **Microphone:** if `VOICE_CAPTURE` is on and the permission is granted, play the start cue and start listening as soon as the first picture is ready, exactly as `CaptureActivity` does (same `SpeechEngine`, same word list, same cues, same silence handling). A microphone button stops and starts it. Tapping the text field stops listening. Use only the `SpeechEngine` interface so that a later change of engine (cards 25, 26) touches nothing here.
   - **A text field** for typing instead.
   - **Add another:** "Gallery" opens the system photo picker (`ActivityResultContracts.PickMultipleVisualMedia`, images only; no permission); picked pictures are prepared with `source = PICKER` and join the strip. Hide this button when the phone is locked. (Card 16e adds "Camera" beside it.)
   - **Private** toggle (the existing `PrivateLabel`): the note is saved Private.
   - **Save** and **Cancel**, each at least 56 dp.
7. **Save:** allowed as soon as at least one picture is ready or there is some text. Saving with no words is allowed when there is a picture. Add `CaptureEntry.save(raw, source, capturedLocked, markPrivate, attachments)` (a new last parameter passed into `Inbound.Capture`); call it with `source = SHARED`. On `Saved`: save cue, show "Saved" briefly, finish. On `Failed`: "I couldn't save that. Try again." and the prepared files are kept so that Save can be pressed again.
8. If he taps Save while pictures are still being prepared, show "Saving…" and save when they are ready. He does not have to wait and tap again.
9. **Cancel** (and the back gesture): stop listening, `discard` every prepared file, finish. Nothing is saved.
10. The first time a picture is shared in, show once, under the strip: "This picture is still in your gallery too. Stash keeps its own copy." Remember that it was shown.
11. Events only (`Dbg.event`): shared (n1 pictures, n2 1 if text), saved, cancelled. No file names, no words.

### C. Appearing in the share sheet's top row

12. Publish one sharing shortcut ("Stash") with `ShortcutManagerCompat` and a share-target entry in `res/xml/shortcuts.xml`, so Stash can appear in the direct-share row. Do this last; if it takes more than an hour, leave it out and say so. Stash is in the ordinary list either way.

## Out of scope

The camera (16e). Showing pictures on stored notes, adding to an existing note, removing one from a stored note (16d). Reading words (16f). Sharing anything **out** of Stash. Video and other file types: the filters do not accept them. Editing or cropping.

## Acceptance checks

**Unit tests (Robolectric, with a fake `PicturePreparer` and a fake `SpeechEngine`)**

- `ACTION_SEND` with one image, then Save: one capture with `source = SHARED`, one attachment at position 0, text as typed.
- `ACTION_SEND_MULTIPLE` with four images, the second of which fails to prepare: one capture, three attachments in the shared order, the "could not be added" line was shown.
- Save with pictures and no words makes a capture with empty text. Save with neither is not possible (the button is disabled).
- Shared text only: one capture, no attachment, the text as shared.
- Cancel after three pictures were prepared: `discard` was called three times and no capture exists.
- Private toggle on: the capture is `sensitive`.
- With `PICTURES` off an image share saves nothing.
- The activity never calls a repository read of stored captures (give it a repository double that fails on any read) and never posts a notification.
- Pure: the "Getting N of M ready" and "N pictures could not be added" wording.

**Real phone (S25 Edge); write each result in the experiment log**

- Take a screenshot in another app. Tap Share on the screenshot toolbar. Is Stash offered? Pick it, say a sentence, Save. **Count the taps** from the screenshot to "Saved" and write the number. If Stash is not offered from the toolbar, say so, and repeat from the gallery's Share.
- In the gallery select five pictures, Share, Stash, Save without words. `adb shell run-as app.stash ls -l files/att` shows five files; write their sizes next to the originals' sizes.
- Share a web link from the browser: the address is in the text field.
- While the annotate screen is open, press the recent-apps button: the Stash card is blank or absent. Try to take a screenshot of it: refused.
- Share a picture, then Cancel: `files/att` has no new file.
- Rotate the phone on the annotate screen with three pictures ready: nothing is lost and nothing is prepared twice.

## Notes and risks

- Confidence: reading shared content needs no permission and must be done while the activity is alive, high. The photo picker needs no permission, high. Samsung's screenshot toolbar using the ordinary share sheet, medium (unverified; the first phone check settles it).
- Starting to listen without a tap is card 16's decision ("a microphone button that starts listening at once"). If the owner finds it startling after sharing, the fix is one boolean; ask him after a week.
- Keep prepared pictures in a holder that survives rotation (a view model), not in the composable.

**Suggested builder: Sonnet.** It is screen wiring over the save path and the preparer that cards 16a and 16b already built and tested.

## Owner decision, 2026-11-12 (D80)

Listening starts automatically on the annotate screen. The owner accepted this and will **review it after about two weeks of use**; it stays one boolean.

## What was built, and what differs from the card (2026-11-12)

Built (under `app/src/main/java/app/stash/` unless noted):
- `domain/ShareRules.kt` (pure): the wording ("Getting N of M ready", "N pictures could not be added", the fixed lines), `canSave`, `hasContent`, `join` (words heard after words already there), the constants (`AUTO_LISTEN = true`, the owner's D80 one boolean; 25 s silence and 60 s hard limits, the same values as `CaptureActivity`).
- `domain/Capability.kt`: row `PICTURES` ("Pictures on notes", no permission, on by default, "Notes are words only."). `domain/Dbg.kt`: component `SHARE`, codes `RECEIVED` (n1 pictures, n2 1 if text) and `CANCELLED`; saved is `SHARE/SAVED` with the same numbers.
- `capture/InboundSink.kt`: `CaptureEntry.save(..., markPrivate, attachments)` (new last parameter, passed into `Inbound.Capture`).
- `share/ShareIntentReader.kt` (reads `EXTRA_STREAM` one or many, falling back to `ClipData`, and `EXTRA_TEXT`; pictures only when the type is an image type), `share/ShareDeps.kt` (the one seam: `PictureIntake`, `ShareCues`, `ShareDeps`, `ShareDepsProvider`, and `PreparerIntake` over card 16b's `PicturePreparer` with `kindHint = null`), `share/ShareViewModel.kt` (everything made so far, survives rotation; pictures prepared one after another in the shared order; Save waits for pictures and for dictation), `share/AnnotateScreen.kt` (stateless composable with `AnnotateUi` and `AnnotateActions`, a `draftLine` slot for 16f, reusable by 16e; also `PicturesOffScreen`), `share/ShareActivity.kt`, `share/ShareShortcut.kt`.
- Manifest: `ShareActivity` exported, `excludeFromRecents`, `taskAffinity=""`, not `showWhenLocked`, no `noHistory`; filters SEND image, SEND_MULTIPLE image, SEND text/plain; `FLAG_SECURE` set in `onCreate`, unconditionally. `res/xml/shortcuts.xml` (a `share-target`) referenced from `MainActivity`'s entry; `StashApp.onCreate` publishes one dynamic shortcut "Stash".
- Tests (43 new, 489 in all): `ShareRulesTest` 7 (plain JVM), `ShareViewModelTest` 3, `ShareEntryTest` 7, `ShareActivityTest` 26 (Robolectric, the real activity with a fake intake, a fake speech engine and a real save path over an in-memory Room database that records every SQL statement). Every acceptance check in the card's list has a test, including: no `SELECT` on notes, records, the search index, extractions or tasks while saving; no notification posted; `FLAG_SECURE` set; `discard` called three times on Cancel.

Differs from the card, and why:
- **"No stored-note read" is proven by SQL, not by a repository double.** `CaptureRepository` is a class, not an interface, so a double that fails on any read was not possible without changing it. `ShareDeps` has no repository member at all (only a `save` function and the word list), and the test records all SQL of a real save and fails on any `SELECT` from the note tables. Saving does read the new capture's own `attachment` rows (card 16a numbers positions); that is the save path.
- **A fake `PictureIntake` instead of a fake `PicturePreparer`** (the preparer is a final class and card 16b may not change); the real adapter is a thin wrapper.
- **Source is always `SHARED`** for the capture, as the card says; the attachments carry `SHARE` or `PICKER`.
- **Thumbnail "larger"** decodes the stored copy in memory through the store (never written anywhere); until it is ready the thumbnail is shown.
- **Pictures off with text shared too:** the text is kept, the picture is dropped, and the message line is shown above the screen (the card describes only the picture-only case: the message and one Close button).
- **A text-only share does not start listening by itself** (the card says "as soon as the first picture is ready"); the microphone button is there.
- **Voice off or permission missing:** the microphone button is hidden and one line says "Voice is off. Type your note instead." The screen never asks for the permission.
- **Back or Cancel while the save is running is ignored**; a failed save keeps the files and Save can be pressed again.
- **Leaving the activity without saving** (the view model is cleared) also deletes the prepared files.
- **Silence handling** repeats `CaptureActivity`'s constants in `ShareRules` rather than editing that private companion; on silence it stops quietly and says "I didn't catch that. Tap the microphone to try again." instead of showing a failure screen.
- **The share-target meta-data is on `MainActivity`** (the launcher entry), where static shortcut files are read. Whether the top row then offers Stash is unverified (phone check).
- `finish()`, not `finishAndRemoveTask()`: the activity may run in the sharing app's task.
- `FLAG_SECURE` is unconditional here; the debug-only toggle of D80 belongs to card 16d, so a debug build cannot be screenshotted on this screen either.
- No schema change, no new dependency. `docs/LICENSES.md` not touched.

Unverified / still to do (the owner; see "Card 16c" in `docs/EXPERIMENT_LOG.md`): every real-phone check in the card.
