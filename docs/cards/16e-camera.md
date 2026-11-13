# 16e · A camera inside Stash

Read [00-READ-FIRST.md](00-READ-FIRST.md) first. Part of the card 16 split ([index](16-share-and-pictures.md)).

**Status: built 2026-11-12, JVM only** (581 unit tests pass, 52 new; no schema change; one new dependency family, CameraX 1.6.2). Nothing was installed on the phone: the phone checks, including the lock-screen spike, wait for the owner. See "What was built, and what differs" at the end.

## Goal

He points the phone at a bottle, a label or a part, taps once, and the photo goes straight into Stash's private storage. It never touches the gallery, which a photo taken with the phone's own camera app always does. This card also answers one open question on the real phone: can the camera be used from the lock screen?

## Depends on

16a, 16b, 16c (the annotate screen). 16d if "Add a picture" on a note's row should offer the camera.

## Context

Read `capture/CaptureActivity.kt` first: how it shows over the lock screen, how it asks for the microphone, its `READY` phase, and `openStash()` (which asks the system to unlock).

Library: **CameraX** (`androidx.camera:camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`), newest stable, pinned in `gradle/libs.versions.toml`. Record it in `docs/LICENSES.md` before adding it (D78), and give the APK size before and after.

`CameraActivity` is an **adding** activity: it shows the live camera and the picture just taken, never anything stored.

## Scope

1. Manifest: `<uses-permission android:name="android.permission.CAMERA" />` and `<uses-feature android:name="android.hardware.camera.any" android:required="false" />`. `capture/CameraActivity`: `exported="false"`, `showWhenLocked="true"`, `turnScreenOn="true"`, `excludeFromRecents="true"`, `taskAffinity=""`. `FLAG_SECURE` in `onCreate`.
2. Capability `CAMERA` in `domain/Capability.kt`: permission `android.permission.CAMERA`, on by default, without it: "You can still share pictures in and pick them from your gallery."
3. Permission: if it is not granted and the phone is unlocked, ask for it when the camera is first opened. If the phone is locked and it is not granted, show "Open Stash once and allow the camera." (the same pattern as the microphone). If `CAMERA` is switched off, the camera buttons are not drawn at all.
4. The screen: a full-screen preview (`PreviewView` with a `LifecycleCameraController`: back camera, tap to focus, pinch to zoom), one large shutter button (at least 72 dp), a Close button, a flash button with Off and Auto. Nothing else.
5. Taking the picture: `takePicture` with the **in-memory** callback (`OnImageCapturedCallback`). Never use the form that writes to a file or to `MediaStore`. Take the JPEG bytes from the image, close it, and hand the bytes to `PicturePreparer.prepare(bytes, kindHint = PHOTO, source = CAMERA)`. Play a short shutter click through `Cues` only if that is one line; otherwise none.
6. **Rotation.** The image carries its rotation in two places (its `rotationDegrees` and, possibly, inside the JPEG). Apply it exactly once. The phone check below (portrait and landscape both upright) decides which one to trust; write down which.
7. What happens after the shutter depends on how the camera was opened, passed as an intent extra:
   - **New note** (no capture id): the picture joins the annotate screen from 16c, opened by this activity with `source = PHOTO` for the capture. The annotate screen's "Add another" gains **Camera**, which returns here; so one note can collect several photos.
   - **For an existing note** (extra `capture_id`): the picture is handed to `container.sink` as `Inbound.AddAttachments(captureId, …)`, the save cue plays, and the screen offers **Take another** and **Done**. The annotate screen is not shown: his words are already the note. The extra carries only the id; this activity never reads or shows the note.
8. Ways in:
   - a **camera button on the main screen** (new note);
   - a **"Take a picture" button on `CaptureActivity`'s `READY` phase** (new note), which is how the camera is reached from the lock screen;
   - **Camera** in "Add another" on the annotate screen;
   - **Camera** as a second choice on a note row's "Add a picture" (16d), for an existing note.
9. **Lock screen.** Build it to open directly over the lock screen, like `CaptureActivity`. Run the lock-screen check below. If the phone asks to unlock, or the preview is black, change the `READY` button's label while locked to "Unlock to take a picture" and make it ask the system to unlock first (`requestDismissKeyguard`, as `openStash()` does), then open the camera. Either result is acceptable; whichever the phone does becomes the documented behaviour.
10. While the phone is locked, the annotate screen opened from here shows only the pictures just taken; "Gallery" is hidden (16c already hides it).
11. Events (`Dbg.event`): camera opened (n1 = 1 if locked), picture taken (ms from shutter to prepared), camera failed (reason code).

## Out of scope

Scan mode for codes (card 21 adds it to this activity). Video. The front camera. Manual exposure, filters, HDR choices. Saving a copy to the gallery (never). The note-first offer after a spoken note (16g). The "latest screenshot" chip (16i).

## Acceptance checks

**Unit tests**

- Pure: `CameraNext.after(shot, captureId)` returns "annotate" for a new note and "attach" for an existing one.
- Robolectric with a fake camera and fake preparer: a shot for an existing capture produces one `AddAttachments` with that id and never opens the annotate screen; a shot for a new note opens the annotate screen with one picture; with `CAMERA` off the main screen has no camera button.
- The source-scan test from card 13 still passes (CameraX opens no network connection of ours).
- A scan of this card's code finds no call to `MediaStore`, `ImageCapture.OutputFileOptions` or `getExternalFilesDir`.

**Real phone (S25 Edge); write each result in the experiment log**

- Take a photo in Stash, say "test bottle", Save. Open **Samsung Gallery** and **Google Photos**: the photo is not in either. `adb shell run-as app.stash ls -l files/att` shows one file; write its size.
- Hold the phone upright, take one; turn it sideways, take one. Both are upright in the strip.
- Take three photos into one note with "Add another → Camera". One note, three pictures, in the order taken.
- From a note's row, Add a picture → Camera → take two → Done: they are on that note, last.
- **Lock screen (RISKS.md R33; a spike, whatever happens is the answer).** Lock the phone. Start a note from the lock-screen notification, let it save, then tap "Take a picture". Write down exactly which of these happened: the camera opened and took a picture / the phone asked to unlock / the preview was black / something else. Do it a second time after restarting the phone, unlocking it once and locking it again. Then apply item 9.
- If it worked while locked: after Save, does the phone return to the lock screen, and is the picture absent from everything visible while locked?
- Time from tapping the shutter to the annotate screen showing the picture: write the seconds.
- `zipalign -c -P 16 -v 4 app-debug.apk` passes (CameraX ships a small native library; Android 17 needs 16 KB alignment).

## Notes and risks

- Confidence that the camera opens over the lock screen on One UI: **low either way**. Android's stated rule is only that background apps cannot use the camera; one user report says Samsung allows only its own camera there. That is why this is a check and not a promise.
- Confidence that the in-memory capture path writes nothing to disk or the gallery: high (it is what the callback form is for). The gallery check proves it.
- Confidence about which rotation to trust: medium; hence item 6's phone check.
- The camera's own JPEG is discarded after `prepare`; only the small WebP copy is kept. There is no "original" anywhere.

**Suggested builder: Sonnet.** It is CameraX and screen wiring; the storage and privacy-critical parts are already behind `PicturePreparer` and the sink.


## What was built, and what differs from the card (2026-11-12)

Built (under `app/src/main/java/app/stash/` unless noted):
- `domain/CameraRules.kt` (pure): `CameraShot`, `CameraFlash { OFF, AUTO }`, `CameraFailure` (codes 1 to 6) and `CameraException`, `CameraNext.after(shot, captureId)` (returns `Step.ANNOTATE` or `Step.ATTACH`), `CameraGate` and `CameraRules.gate(...)` (switch, pictures, a camera on the phone, permission, locked), all the words, `buttonVisible`, `takeLabel`, and the constant `UNLOCK_FIRST_WHEN_LOCKED = true`. `domain/JpegOrientation.kt` (pure): see rotation below.
- `domain/Capability.kt`: row `CAMERA` ("Take pictures inside Stash", permission `android.permission.CAMERA`, on by default, "You can still share pictures in and pick them from your gallery."). `domain/Dbg.kt`: component `CAMERA`, codes `OPENED` (n1 = 1 if locked), `TAKEN` (ms from shutter to prepared, n1 bytes kept) and `FAILED` (n1 = reason code).
- `capture/CameraEngine.kt` (the interface: `Preview`, `capture()`, `setFlash`), `capture/CameraXEngine.kt` (a `LifecycleCameraController` on the back camera, tap to focus and pinch to zoom on, `takePicture` with `OnImageCapturedCallback` only, JPEG bytes read, image closed), `capture/CameraDeps.kt` (`CameraPhotos` over `PicturePreparer.prepare(bytes, PHOTO, CAMERA)`, `CameraDeps`, `CameraDepsProvider` with a test factory), `capture/CameraViewModel.kt` (shoot, the three screens, the existing-note save through `Inbound.AddAttachments`, once the shutter has fired the rest finishes even if the screen goes away), `capture/CameraActivity.kt`.
- `CameraActivity`: `FLAG_SECURE` through `ScreenSecurity.secure` in `onCreate`; `showWhenLocked`, `turnScreenOn`, `excludeFromRecents`, `taskAffinity=""`, `exported="false"` in the manifest. Full-screen preview, a shutter button of 80 dp, Close, and one flash button (Off, Auto). No extra: a new note, each picture joins the annotate screen of 16c (the same `ShareViewModel` and `AnnotateScreen`, saved as source `PHOTO`), whose new **Camera** button beside Gallery comes back to the camera. Extra `capture_id`: the picture goes to `container.sink` as `Inbound.AddAttachments`, the save cue plays, and "Take another" and "Done" are shown; the annotate screen is never shown and the note is never read (the intent carries the id and nothing else, and a test pins that).
- Manifest: `CAMERA` permission and `<uses-feature android:name="android.hardware.camera.any" android:required="false" />`.
- Permission: not granted and unlocked: asked once when the camera first opens; denied or later: "Stash needs the camera to take pictures. Allow it, or choose pictures from your gallery." with Allow the camera, Choose from your gallery and Close. Locked and not granted: "Open Stash once and allow the camera." and Close only (no picker while locked). Camera switch off, pictures off, or no camera: one line and Close (the picker is offered unless locked or pictures are off).
- Ways in: **Take a picture** beside **Add a note** on the Ask and Notes screens (`ui/CameraEntry.kt`); **Take a picture** on `CaptureActivity`'s READY phase (own task, so the capture screen then closes); **Camera** in the annotate screen's row; and on a note row, **Add a picture** now asks "Take a picture" or "Choose from gallery" when the camera is on (straight to the gallery picker when it is off, as before). The buttons are drawn only when the CAMERA switch and PICTURES are on and the phone has a camera (the permission is not part of it: the first tap asks).
- Changes to earlier cards, wiring only: `ShareViewModel.addPrepared` (a picture made ready elsewhere; it never shows the "still in your gallery" line), `annotateUiFor` (the one place the annotate screen's state is built, now used by both `ShareActivity` and the camera), `AnnotateUi.cameraVisible` and `AnnotateActions.onCamera` (defaults keep the share screen unchanged), `ShareDepsProvider.get(context, source)` and `shareSave(entry, source)` (SHARED stays the default). `PictureAdder`, `PicturePreparer`, `AttachmentRepository` and the sink are untouched.
- **Rotation (item 6): the camera's `rotationDegrees` is trusted and written into the JPEG's own orientation tag; the picture decoder of card 16b then rotates once.** `JpegOrientation.withOrientation` drops every Exif segment the camera put there and writes one holding only the orientation for that rotation, so whichever of the two places the camera fills, the picture is turned exactly once. A test with the platform decoder proves a 90 turns once, and a picture already tagged 90 that the camera calls upright is not turned. Which value the S25 Edge reports is the phone check.
- Library: CameraX 1.6.2, see the experiment log for the coordinates, licences and the other libraries it pulls in. APK (debug) 74,918,507 to 83,848,843 bytes (+8.93 MB). `zipalign -c -P 16` passes.
- Tests (52 new, 581 in all): `CameraRulesTest` 19 (plain JVM: the gates, the lock fallback, flash, the JPEG tag rewrite on well-formed, tagged, big and little endian, damaged and non-JPEG input), `CameraActivityTest` 21 (Robolectric: the real activity with a fake camera and a fake preparer over a real store and database), `CameraEntryTest` 5, `CameraSourceScanTest` 4, `CameraRotationTest` 3.

Differs from the card, and why:
- **The lock-screen fallback is the shipped default, behind one constant.** The card says to build it to open directly, run the check, then fall back if needed. The real behaviour is unknown and cannot be learned without the phone, so both paths exist and `CameraRules.UNLOCK_FIRST_WHEN_LOCKED` (true) chooses: true gives the label "Unlock to take a picture" and asks the system to unlock first; false opens the camera over the lock screen so the spike can be run. Whichever the phone does is then written in the log and the constant set to match.
- **The new-note flow stays inside `CameraActivity`.** The card says the annotate screen is "opened by this activity with `source = PHOTO`". Here the same activity switches from the camera to the shared annotate screen (one `ShareViewModel`, which survives rotation), so pictures are never handed over a process boundary and "Add another, Camera" is a screen change. The note is saved with `CaptureSource.PHOTO`; the attachments carry `CAMERA`.
- **No shutter click.** `Cues` has no shutter sound and adding one is more than one line; none is played. The existing-note path plays the save cue, as the card says.
- **"Done" and Close use `finishAndRemoveTask`**, like `CaptureActivity`; from the capture screen the camera is started with `FLAG_ACTIVITY_NEW_TASK` so closing the capture screen does not close the camera.
- **The main-screen buttons and the note-row choice have no test that launches `MainActivity`** (same reason as card 16d: `StashApp` needs WorkManager). Their decision (`cameraVisible`) and the button composable are tested; the placement in the two screens is not. A scan test pins that no code uses the gallery, the file form of `takePicture`, or an external folder.
- **`camera-view` brings `camera-video` and other libraries with it** (list in the log); the card names four artifacts and these come transitively. No other dependency was added.
- **Pictures taken while locked**: with the fallback on, nothing is taken while locked. The annotate screen's Gallery is hidden while locked (16c's rule, kept); Camera is shown.
- **Auto-listen:** the 16c rule (D80, listening starts when the first picture is ready) applies to the camera too, because it is the same screen and the same view model. Say so if it is unwanted after a camera shot.

Unverified / still to do (the owner; nothing was installed): every real-phone check in the card's list, with exact steps in `docs/EXPERIMENT_LOG.md`, "Card 16e". Unproven until then: the preview and shutter on the S25 Edge, which rotation value the camera reports, the lock-screen behaviour (RISKS R33), the shutter-to-annotate time, and that the Samsung gallery shows nothing. `docs/LICENSES.md` was not edited (this build may not); its CameraX row should now read "1.6.2, Apache-2.0 per POMs" with the transitive list above.
