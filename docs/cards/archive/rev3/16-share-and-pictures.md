# 16 · Pictures: Share to Stash, a camera in the app, and notes that ask for a picture

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

Rewritten in revision 3 (both directions, one attachment model, private by default, a camera inside the app).

## Goal

A picture and a note belong together whichever came first. **Picture first:** he shares a screenshot or photographs a bottle; the app reads the printed words on the phone to make a draft and asks "Anything to add about this?". **Note first:** he says "It's a Valpolicella by Allegrini, in the Italian section, row 5, bucket 10" and the app quietly offers "Add a picture?". The app keeps its own small, private copy; the picture is findable later by place, by date and by the words printed on it, with no image recognition and nothing sent anywhere.

## Depends on

01, 13. Works with no model (Tier 0); better with 04. Its search checks need 08. Build after the hardening block (10, 02, 14) unless the owner asks for it sooner.

## Context

### Every picture is private by default

These hold for every picture, marked Private or not, and are the design's answer to sensitive pictures of any sort. The app does **not** look at what a picture shows and contains no "explicit image" detector (`docs/DESIGN.md` §21 says why).

- Stored only in the app's private storage, so it is not in the phone's gallery and is not picked up by a gallery's cloud backup. Excluded from Android's own backup and device transfer.
- Encrypted with its own key from the first picture (below); covered by database encryption from card 14.
- Never in a notification, a widget, a digest, the recent-apps view, a diagnostic report or a log.
- Never sent to a hosted model. Sending one is not built in this card.
- Shown only on reading screens (behind the app gate from card 02), which block screenshots.
- A picture belonging to a note marked Private gets the second unlock and every other exclusion of the mark (card 02).

### How a picture arrives

| Route | Permission | Verdict |
| --- | --- | --- |
| **Share to Stash** from Samsung's screenshot toolbar, the gallery, or any app | none | **Build.** The main route for screenshots |
| **A camera inside Stash** | Camera | **Build.** The main route for things in front of him. The photo goes straight into private storage and never touches the gallery, which a photo taken with the phone's camera app always does |
| **Photo picker** from a note | none | **Build** |
| **Offer my latest screenshot or photo** when a note is started within a few minutes of one | all photos (`READ_MEDIA_IMAGES`) | **Behind a switch, off by default**, to learn whether the offer is worth the permission |
| Watching the photo library in the background and notifying | all photos plus a background trigger | **Not built**: broad access and one more notification. Google's store policy also allows broad photo access only when it is an app's core purpose |

Android cannot tell an app "the user just took a screenshot of another app": the Android 14 callback reports only screenshots of the app's own screens, and with "selected photos only" access a new screenshot is invisible. (Google's pages, read 2026-11-11 by a research helper.)

### Reading the words

**ML Kit Text Recognition v2**, Latin model **bundled** (about 4 MB; the other packaging is 260 KB but fetches the model through Google Play services). Google's terms say the picture is processed on the device and not sent to Google, and also that the library **reports usage and performance figures to Google** (high: ML Kit terms page). That is not content, but it is something leaving the phone that the app does not control, so it goes on the "What leaves my phone" screen as a fixed line and in `docs/PRIVACY.md`. Google's guidance: letters need to be about 16 pixels tall to read well, and more than 24 gains nothing.

## Scope

### A. One attachment model (`data/`, `security/`)

1. Table `attachment` (a migration and migration test): `id`, `capture_id`, `kind` (`PHOTO`, `SCREENSHOT`; card 21 adds `SCAN`), `mime`, `file_name` (random), `file_key` (BLOB, 32 random bytes), `format_version`, `byte_size`, `width`, `height`, `sha256`, `ocr_text`?, `ocr_engine`?, `source` (`SHARE`, `CAMERA`, `PICKER`, `RECENT`), `created_at`, `updated_at`, `deleted_at`?. `capture.source` gains `SHARED` and `PHOTO`. A picture's privacy follows its capture's `sensitive` mark; there is no separate flag to get out of step.
2. An attachment always belongs to exactly one capture. Picture first: the capture is created with the picture and may have empty text. Note first: the picture is added to the existing capture. Either order ends in the same rows.
3. `AttachmentStore`: files in `files/att/`, each encrypted with AES-256-GCM under its own `file_key` (Tink streaming AEAD), with a small plain header (magic, format version). The key is in the row, so the file is exactly as protected as the database, today and after card 14, with no change to the files.
4. **Size and format.** Read the words from the full-size image first, then store: long edge at most 2,000 px for a screenshot and 1,600 px for a photo, lossy WebP at quality 75. Why: Android can write JPEG, PNG and WebP and nothing newer; WebP is roughly a third smaller than JPEG at the same look (an estimate); at 1,600 px a label's small print is still above the 16-pixel line in ordinary framing. If the recognised text has many lines and the average letter height at the stored size would fall under 16 px, keep 2,400 px instead. Re-encoding drops the camera's embedded data, including any position. Expect about 150–350 KB a picture (estimate; measured below).
5. Never write a decrypted picture to disk. Thumbnails are made in memory.
6. **Deleting:** card 01's purge removes the rows and files with the capture. "Remove picture" removes one. `ConsistencyCheck` gains: every row has a file; a file with no row is deleted.
7. `capture.photo_hint` (card 01): `PENDING` means "a picture was suggested and not yet added". It changes nothing else: the note is complete without one. It becomes `DISMISSED` when he says no, and by itself after 24 hours.

### B. Picture first

8. **`share/ShareActivity`**: exported, intent filters for `ACTION_SEND` with `image/*` and `text/plain`; a sharing shortcut so Stash is in the direct-share row. An adding activity (card 02). Read the shared content at once in `onCreate`.
9. **`capture/CameraActivity`** (capability `CAMERA`): a plain CameraX preview with one shutter button, started from a camera button on the main screen, on the capture screen's "Saved" state, and on a note's page. Shows over the lock screen like `CaptureActivity` and shows nothing stored.
10. Both end on the same **annotate screen**: the picture; the words read from it appearing beneath as a draft ("Read from the picture: Valpolicella Classico · Allegrini · 2022 · 750 ml"); a microphone button that starts listening at once ("Anything to add about this?"); a text field; **Save**; **Cancel**. Saving with no words is allowed.
11. Save → one capture through the `InboundSink` (`source = SHARED` or `PHOTO`; text = his words, possibly empty), the attachment linked, save cue, close. Shared text or a link becomes the capture's text.
12. **Merging the two sources.** His words are the capture's `text` and are what the extractor reads. The words read from the picture stay in `attachment.ocr_text`, are added to the capture's search document, and are shown under the note as "Read from the picture". If they disagree, records come from his words and the picture's text is still kept and searchable. If he says nothing: Tier 0 makes one `REFERENCE` record whose title is the first two lines of the picture's text (or "Picture" if none was read). If nothing was read and nothing said, the picture is saved with its time and place and can be found by those.
13. Capability `PICTURE_TEXT_TO_MODEL` (default **off**): when on, and the capture is not sensitive, the picture's text is appended to the extraction request under a heading "Text read from a picture of the thing:" so the model can fill in name, maker and type. The picture itself is never sent.

### C. Note first

14. When a capture's extraction has `photo_hint` true on any record (the model, or card 03's rule), and the capture has no picture, set `capture.photo_hint = PENDING`.
15. The offer is **a button, not a notification**: on the "Saved" screen ("Add a picture" beside Undo, for as long as that screen shows, extended to 8 seconds when the hint is known in time), and on the note's row in the Notes list while `PENDING` ("Add a picture · No thanks"). With rules-only filing the hint is known at once; with the model it usually arrives after the Saved screen has closed, and then only the row shows it. No reminder, ever.
16. Tapping opens `CameraActivity` for that capture. The picture's text is read and stored as in item 12; his original words stay the note.
17. The attention list (card 09) does **not** get an item for this. A note that suggested a picture and never got one is simply a note.

### D. Finding them again (with card 08)

18. Router routes, answered by code:
    - "what was that <thing> at <place> (a couple of weeks ago | last month | in October)?" → `searchNotes(query = thing, place, on = range)`, pictures first. "A couple of weeks ago" resolves to 10–21 days back, "a few weeks" to 14–35, on the phone.
    - "show me the <things> I (tagged | noted | photographed) at <place>" → `searchNotes(query = thing, place, hasPicture = true)` shown as a grid of thumbnails with titles.
    - A name ("Allegrini") needs no route: full-text search finds it in his words or in the picture's text.
19. A capture's place, for these, is the first that exists of: a `place` on one of its records; its trip stop's name (card 17); a saved place within radius of its position.
20. Tool results for the loop may say a note "has a picture" and may include his words; they never include the picture or, unless item 13's switch is on, its text.

### E. Recent picture offer (capability `RECENT_MEDIA`, default off)

21. `RecentMediaSource` with a do-nothing version and a `MediaStore` version. When on and the phone is unlocked, the annotate and Saved screens show one chip, "Include the screenshot from 2 minutes ago?", for an image made in the last 5 minutes. No background observer, no notification.

### F. Capabilities and register rows

22. `PICTURES` (none needed), `CAMERA` (Camera; without it: share and picker only), `PICTURE_TEXT` (none; without it: found only by what he said, place and date), `PICTURE_TEXT_TO_MODEL` (the hosted model; off by default), `RECENT_MEDIA` (all photos). Explanation-card text for each is in `docs/EXPLANATION_CARDS.md`.
23. A manifest `data-extraction-rules` entry excluding `files/att/` from cloud backup and device transfer.

## Out of scope

Sending a picture to a hosted model ("hosted vision"): later, per picture, with the consent wording in `docs/EXPLANATION_CARDS.md`, never for a private one; `docs/PROVIDERS.md` says what it would add. Any judgement of what a picture shows. Sharing a picture **out** of the app, other than as part of an export he asks for. Video. Editing or cropping. Deleting the original from the gallery. Accepting the picture offer by saying "yes" (see notes). Barcodes (card 21).

## Acceptance checks

- **Unit:** file round-trip with a known key; a flipped byte fails; a wrong key fails.
- **Unit:** scaling rules including the small-print exception; output is WebP; no embedded metadata survives.
- **Unit:** purge removes rows and files; orphan sweep; `photo_hint` goes `PENDING` → `DISMISSED` after 24 hours and on "No thanks".
- **Unit:** picture-first and note-first for the same bottle end in the same rows apart from `source`.
- **Unit:** his words win: a capture saying "Allegrini" with picture text "ALLEGRINl" yields records from his words; both strings are searchable.
- **Unit:** the hosted request contains no picture text with the switch off, contains it with the switch on, and never for a sensitive capture.
- **Unit:** the three searches in item 18 against a seeded database with two Total Wine visits three weeks apart: the dated question returns the right visit's bottle; the "show me" question returns only notes with pictures; "Allegrini" is found from picture text alone.
- **Unit (Robolectric):** `ShareActivity` with an image creates one capture and one attachment and never queries stored notes; no notification is ever posted by this card's code.
- **Real phone, S25 Edge, screenshots:** take a screenshot in another app; Share from the toolbar; pick Stash; say a sentence; Save. Count the taps. Search for a word that is only in the picture. `adb shell run-as app.stash ls -l files/att` shows one unreadable file; note its size against the original.
- **Real phone, labels (RISKS.md R31):** photograph ten wine or product labels in shop lighting with the in-app camera. For each, write down whether the name, the maker and the price on the shelf tag were read correctly. **Pass:** the name is findable by search for at least 8 of 10.
- **Real phone, privacy:** after taking a picture in Stash, open the Samsung Gallery and Google Photos: it is not there. Mark the note Private: no thumbnail anywhere until the second unlock.
- **Real phone, lock screen (R33):** with the phone locked, start a note from the notification, then tap the camera button. Write down exactly what happens: camera opens / asks to unlock / black preview. Whatever it is becomes the documented behaviour.
- **Real phone, note first:** say the Valpolicella sentence with the model off: the Saved screen offers the picture. With the model on: the offer appears on the note's row a little later. Ignore it: nothing else ever mentions it.

## Notes and risks

- **Camera from the lock screen is unverified.** Android's stated rule is only that background apps cannot use the camera; a visible screen over the lock screen with permission already granted should work, but one user report says Samsung allows only its own camera there (low confidence either way). If it asks for unlock, that is acceptable: say so on the button ("Unlock to take a picture").
- **Saying "yes" to the offer** is left out on purpose. It means reopening the microphone for a few seconds after every such note and guessing whether a sound was "yes"; a wrong guess opens a camera he did not ask for. Revisit if he asks for it after using the button.
- Samsung's screenshot toolbar is believed to use the ordinary share sheet; not verified. If Stash does not appear there, the gallery's Share will.
- A photo shared in from the gallery is still in the gallery and in whatever backs the gallery up. The annotate screen says so once.
- Tink, ML Kit text recognition and CameraX are new dependencies; give the APK size before and after.

## Owner decision, 2026-11-11 (overrides scope item 4's numbers where they differ)

The owner wants the **smallest legible** picture and never a full-resolution (4K-class) one. Store a **copy**; a gallery original is never changed or moved. Default: long edge at most **1600 px** for photos and **1920 px** for screenshots, lossy WebP quality 75 (a 4:3 photo is about 1600 x 1200). Keep the "many small lines" exception at 2,400 px only if the owner does not object; otherwise cap at 1920. Add a setting "Picture size" with Small (1024 px), Standard (1600/1920, default) and Large (2400), applied to pictures added after the change. Read the words from the full-size image **before** shrinking it. Pictures are stored in the app's private storage (not a visible folder), per the card.
