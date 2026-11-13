# 16g · Note first: the quiet "Add a picture?" offer

Read [00-READ-FIRST.md](00-READ-FIRST.md) first. Part of the card 16 split ([index](16-share-and-pictures.md)).

**Status: built 2026-11-12, JVM only** (632 unit tests pass, 22 new; no schema change, no new dependency). Nothing was installed on the phone: the phone checks wait for the owner. See "What was built, and what differs" at the end.

## Goal

He says "It's a Valpolicella by Allegrini, in the Italian section, row 5, bucket 10". The note is saved and complete. Because it describes a thing a picture would help find again, the app offers "Add a picture" as a button, once, and never mentions it again if he ignores it.

## Depends on

16a, 16d (the row's "Add a picture"), 16e (the camera). The hint itself comes from card 03's rule and, when card 04 is built, from the model.

## Context

Read `capture/CaptureActivity.kt` (the `READY` phase, `previous`, Undo), `capture/SavedNote.kt` (the Undo and idle timings), and `data/ExtractionRunner.kt` first. The runner **already** sets `capture.photo_hint` from `NONE` to `PENDING` when an extraction says a picture would help; nothing reads that value yet.

Decisions kept (D73): the offer is **a button, never a notification**; it is not accepted by saying "yes"; the attention list gets **no** item for it. (Card 09 mentions an `ADD_PICTURE` reason from an earlier revision; do not create it.) A note that suggested a picture and never got one is simply a note.

## Scope

1. `domain/PictureOffer.kt` (pure): `show(photoHint, pictureCount, sensitive, ageMs)` is true only when the hint is `PENDING`, the note has no picture, it is not Private, and it is less than 24 hours old. Private notes are left out of suggestions by rule; he can still add a picture to one with the ordinary button.
2. Closing the offer. `photo_hint` becomes `DISMISSED`:
   - when he taps **No thanks**;
   - when a picture is added to the note by any route (so the offer does not come back if that picture is later removed);
   - by the daily upkeep (where `Repository.purgeDeleted` runs) for every `PENDING` capture older than 24 hours.
   Add `CaptureRepository.setPhotoHint(captureId, value)` for this. No revision is written: it is not something he needs to be able to undo.
3. `ExtractionRunner`: keep the existing `NONE → PENDING` step, but skip it when the capture already has a picture. It must never move `DISMISSED` back to `PENDING` (it does not today; add the test).
4. **On the Saved screen** (`CaptureActivity`, phase `READY`, while `previous` is the note just saved): check the saved capture's `photo_hint` every half second for as long as its Undo window is open. If `PictureOffer.show` becomes true, draw **Add a picture** beside Undo and extend that note's Undo and idle windows to 8 seconds from the moment the button appears (read `SavedNote` and add the extension there, with a test). With rules-only filing the hint is known within a moment; with a model it usually arrives after this screen has closed, and then only the row (item 6) shows it.
5. Tapping it opens `CameraActivity` **for that capture** (16e, extra `capture_id`). The picture is attached to the note he just spoke; his words stay the note; card 16f reads the picture's words as for any picture. The existing "Take a picture" button on this screen (16e, a new note) stays; the two buttons have different labels.
6. **On the note's row** in the Notes list, while `PictureOffer.show` is true: one line, "Add a picture · No thanks". Add opens the same choice as the row's ordinary "Add a picture" (16d). No badge, no count, no sound.
7. Nothing else ever refers to the offer: no notification, no digest line, no attention item, no reminder.
8. Events (`Dbg.event`): offered on the Saved screen, offered on the row, accepted, dismissed by tap, dismissed by age. Counts only; they tell the owner later whether the offer earns its place.

## Out of scope

Accepting by voice (D73, QUESTIONS.md Q43). A notification. Offering for Private notes. Offering more than once. Deciding **which** notes deserve the hint: that is card 03's rule and card 04's model.

## Acceptance checks

**Unit tests**

- `PictureOffer.show`: true for `PENDING`, no picture, not Private, 1 hour old; false for each of: `NONE`, `DISMISSED`, one picture, Private, 25 hours old.
- Upkeep moves a 25-hour-old `PENDING` to `DISMISSED` and leaves a 1-hour-old one.
- Adding a picture to a `PENDING` note makes it `DISMISSED`; removing that picture does not bring the offer back.
- Re-reading a `DISMISSED` capture whose extraction still carries the hint leaves it `DISMISSED`. A capture that already has a picture stays `NONE`.
- `SavedNote`: when the offer appears 2 seconds after saving, Undo and the idle close both run until 10 seconds after saving; with no offer the timings are unchanged.
- Robolectric: the Valpolicella sentence through the rules pipeline makes the offer appear on the Saved screen; tapping it starts `CameraActivity` with that capture's id.
- No code in this card posts a notification or sets `record.attention` (assert with doubles that fail if called).

**Real phone (S25 Edge); write each result in the experiment log**

- Model off. Say the Valpolicella sentence. Does "Add a picture" appear on the Saved screen, and how many seconds after the save tone? Tap it, take the picture, Done: the note in the list has his words and the picture.
- Say it again and ignore the button: the screen closes by itself; the note's row shows "Add a picture · No thanks"; tap No thanks; it is gone for good.
- Say it a third time and do nothing at all for a day: the next day the row no longer offers. Nothing reminded him in between.
- When card 04 is built, repeat with the model on: write whether the offer made it to the Saved screen or only to the row.
- From the lock screen: say the sentence, tap "Add a picture". The result follows whatever card 16e recorded for the lock screen.

## Notes and risks

- The half-second check reads one row by id; it is cheap. Stop it when the screen leaves `READY`.
- With the model, the hint depends on a network reply and will often miss the Saved screen. That is accepted (card 16): the row is the fallback, and nothing else is.

**Suggested builder: Sonnet.** A small pure rule, a timing change with tests, and two buttons.


## What was built, and what differs from the card (2026-11-12)

Built (under `app/src/main/java/app/stash/` unless noted):
- `domain/PictureOffer.kt` (pure): `show(photoHint, pictureCount, sensitive, ageMs)`, `EXPIRY_MS` (24 h; the offer is off from exactly 24 h), `SAVED_EXTENSION_MS` (8 s), `POLL_MS` (500 ms), `staleBefore(now)`, and the two labels.
- `data/Daos.kt`: `CaptureDao.dismissStaleOffers(cutoff, now)` (one UPDATE; no schema change). `data/Repositories.kt` `CaptureRepository`: `setPhotoHint(id, value)` (no revision; true only if the value changed), `pictureOfferShown(id)` (one row and one picture count, by the repository clock), `dismissStaleOffers()`.
- Closing the offer: **No thanks** (`MainViewModel.dismissOffer`); a picture added by any route (`AttachmentRepository.addTo`, in its own transaction, so camera, picker and share all close it); the daily upkeep (`TaskUpkeep.run` and `Repository.purgeDeleted`, 24 h by the repository clock). `UpkeepResult.offersClosed` is new (default 0).
- `ExtractionRunner`: still `NONE -> PENDING` only, now skipped when the capture has a picture. `DISMISSED` is never moved back (tested).
- `capture/SavedNote.kt`: `offered` flag and `offerAppeared(now)`: Undo and idle run until 8 s after the button appears (2 s after saving gives 10 s), never shorter than before; only the first appearance extends.
- `capture/CaptureActivity.kt`: while the note's Undo window is open the ready ticker reads the hint every 500 ms; **Add a picture** appears beside Undo and opens `CameraActivity.intent(this, captureId)` (through the same unlock-first rule as "Take a picture"); Private pressed hides it. The existing "Take a picture" button is unchanged.
- `ui/MainActivity.kt` and `ui/MainViewModel.kt`: while the offer shows, the note's row has one line "Add a picture · No thanks" (tag `pictureOffer`, 56 dp buttons, no badge, no sound). Add opens the same choice as the row's ordinary button (the two now share one lambda, `addPictureTo`).
- Events (`DbgComponent.OFFER`; `DbgCode.OFFERED`, `ACCEPTED`, `DISMISSED`): offered n1 1 = Saved screen, 2 = row (the row once per note per app run); accepted n1 1 or 2; dismissed n1 1 = No thanks, 2 = by age (n2 = how many), 3 = a picture was added. Counts and a row id only.
- The Tier 0 rule needed no change: card 03's `RulesExtractor` already sets `photo_hint` for a spot ("row 5, bucket 10") or a container word, and `Projector` already carries it to the runner; both were tested before this card. The Valpolicella sentence is now tested end to end.

Tests (22 new, `app/src/test/java/app/stash/PictureOfferTest.kt`): the `show` table (true case, each false case, the 24 h edge); Valpolicella through the rules makes `PENDING` and the offer show, and the camera intent carries only that capture's id; a note with a picture stays `NONE`; re-reading a `DISMISSED` note leaves it; a non-physical note gets no hint; No thanks writes no revision; add then remove leaves it `DISMISSED`, also after a re-read; removing the picture of a note that never had the offer does not start one; the upkeep closes a 25 h offer and leaves a 1 h one; expiry by the fake clock; `purgeDeleted` closes old offers; Private: not offered, but a picture can still be added, and Private pressed withdraws the offer; a bin note is not offered; `SavedNote` timings (offer at 2 s gives 10 s, unchanged without an offer, never shortens, only once); no notification posted (the shadow notification manager stays empty across the whole flow) and no record `attention`; `PictureOffer.kt` names no notification, alarm, reminder or attention API; events are counts only.

Differs from the card:
- **No Robolectric test drives `CaptureActivity`.** It needs the speech engine and the tone player, and no earlier card tests it either. The Saved-screen part is covered by the pieces it uses (`pictureOfferShown`, `SavedNote.offerAppeared`, `CameraActivity.intent`); the screen itself waits for the phone check. The card's "Robolectric: the Valpolicella sentence makes the offer appear on the Saved screen; tapping starts `CameraActivity`" is therefore tested at repository and intent level, not through the activity.
- **The Saved-screen button is drawn only when the camera is offered** (switch on, pictures on, phone has a camera). The picker cannot be opened from that screen, so with the camera off the offer reaches only the note's row, where Add opens the gallery. On the Saved screen it would otherwise have been a dead button.
- **Removing a picture from a note whose hint is `NONE` sets it to `DISMISSED`** (`AttachmentRepository.remove`). Without it, a note that came with a picture and then lost it would be offered one on the next re-read. This is a small change to 16d's remove, as the card's "removing that picture does not bring the offer back" asks.
- `dismissStaleOffers` also runs in `Repository.purgeDeleted` (the card names that place); the scheduled worker calls `TaskUpkeep.run`, which does the same on its own. Both are idempotent.
- A hint set on a Private note is still stored as `PENDING` (the runner is unchanged); `show` hides it, so if the note is later made not Private the offer shows only while the note is under 24 hours old.

Needs the owner (nothing installed here):
1. `.\gradlew.bat installDebug` over the existing app (never uninstall: it destroys the notes). Model off, pictures and camera on.
2. Say the Valpolicella sentence. Does "Add a picture" appear beside Undo on the Saved screen, and how many seconds after the save tone? Tap it, take the picture, Done: the note in the list has his words and the picture, and no offer line.
3. Say it again and ignore the button: the screen closes by itself (at most 8 s after the button appeared); in Notes the row shows "Add a picture · No thanks"; tap No thanks; it is gone for good (also after "Read it again").
4. Say it a third time and do nothing for a day: next day the row no longer offers it. Nothing reminded him in between (no notification, nothing in the attention list).
5. Say a task sentence ("remember to call the dentist"): no offer anywhere.
6. Say the sentence and press Private on the Saved screen: the button goes. Say it as a Private note: no offer on the row.
7. Switch the camera off in Settings: the Saved screen shows no "Add a picture"; the row's Add opens the gallery.
8. `python tools/peek.py events`: `OFFER OFFERED` (n1 1 and 2), `ACCEPTED`, `DISMISSED` (n1 1, 2, 3) appear with counts only.
9. When card 04 is built, repeat with the model on and write whether the offer made it to the Saved screen or only to the row.
10. From the lock screen: say the sentence, tap "Add a picture". The result follows what card 16e recorded for the lock screen (as built, the phone asks to unlock first).
