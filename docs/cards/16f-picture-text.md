# 16f · Reading the words in a picture

Read [00-READ-FIRST.md](00-READ-FIRST.md) first. Part of the card 16 split ([index](16-share-and-pictures.md)).

**Status: built 2026-11-12, JVM only** (610 unit tests pass, 29 new; no schema change; one new dependency family, ML Kit Text Recognition 16.0.1, bundled Latin model). Nothing was installed on the phone: the phone checks wait for the owner. See "What was built, and what differs" at the end.

## Goal

The phone reads the printed words in a picture (a label, a poster, a screenshot), shows them as a draft while he adds his own words, keeps them with the picture, and makes the note findable by them. Nothing is sent anywhere. What he says always wins over what the picture says.

## Depends on

16a, 16b, 16c. Uses the pipeline from 03 and 13. Works with no model.

## Context

Read `pictures/PictureTextReader.kt` and `PicturePreparer.kt` (16b: the seam and where it is called, before shrinking), `domain/SearchDocs.kt`, `data/SearchIndex.kt`, `pipeline/ExtractionPipeline.kt`, `domain/ExtractionChecks.kt` and `domain/Projector.kt` first.

Library: **ML Kit Text Recognition v2, Latin model bundled** (`com.google.mlkit:text-recognition`; about 4 MB; the other packaging fetches its model through Google Play services and is not used: D76). Google's terms say the picture is processed on the phone and not sent to Google, and also that the library **reports usage and performance figures to Google**. That is not content, but it leaves the phone outside the app's control, so it is disclosed (item 13). The library's terms are Google's own, not an open licence: it must be recorded in `docs/LICENSES.md` and the owner must have accepted it there before it is added (D78 puts custom licences under "ask first").

The app never judges what a picture shows (D69). This card reads printed text and nothing else: no labels, no faces, no objects.

## Scope

### A. The reader

1. Capability `PICTURE_TEXT` in `domain/Capability.kt`: no permission, on by default, without it: "Pictures are found by what you said, the place and the date, not by the words printed on them."
2. `pictures/MlKitPictureText : PictureTextReader`. `read(bitmap)` runs the recogniser on the reading-size bitmap and returns `PictureText`: `text` = the recognised lines joined with new lines, in the library's reading order, cut at 4,000 characters; `stats` = number of lines and their median height in pixels; `engine` = `"mlkit-text-latin/" + library version`. No words found gives an empty `text` (not null). Any failure gives null.
3. `Container.pictureText` returns `MlKitPictureText` when `capabilities.isOn(PICTURE_TEXT)` and `NoPictureText` otherwise. With the switch off the ML Kit classes are never touched.
4. `PicturePreparer` already calls the reader before shrinking and copies `text` and `engine` into the `InboundAttachment` (`ocrText`, `ocrEngine`); 16a stores them. Check that this is so and that `stats` reaches `PictureSizing`'s small-print rule.

### B. The draft and the display

5. Annotate screen (16c's draft slot): when the first picture's text is not empty, show "Read from the picture: " followed by its first four non-empty lines joined with " · " (for example "Valpolicella Classico · Allegrini · 2022 · 750 ml"). It is a display only: it is **not** put into the text field and is not his words.
6. Under a note in the Notes list and in the viewer (16d): "Read from the picture" and the stored words, collapsed. For a Private note this sits inside the same private wrapper as the pictures.

### C. Finding by the words

7. `SearchDocs.captureBody(text, pictureTexts: List<String>)`: his words, then each picture's words. `SearchIndex.indexCapture` loads the capture's attachments' `ocr_text` and passes them; `rebuild` therefore includes them too.
8. Re-index the capture whenever its attachments change: when pictures are added (`AttachmentRepository.addTo`), when one is removed, and when `updateOcr` stores words later (item 12). A soft-deleted capture stays out of the index as today.

### D. His words win

9. The extractor is given `capture.text` only, as today. Picture text never enters an extraction in this card. If he says "Allegrini" and the picture says "ALLEGRINl", the records come from his word; both are searchable.
10. **A picture with no words from him.** When a capture's `text` is blank and it has at least one picture, the pipeline does not run the rules or a model on it. Instead a small local extractor, name `picture-1`, produces exactly one record: kind `REFERENCE`, confidence `LOW`, title = the first two non-empty lines of the first picture's text joined with " · " and cut at 80 characters, or "Picture" when nothing was read. Read `ExtractionChecks` and `Projector` before writing it: if a check requires the record's quote to be found in the capture's text, exempt extractor `picture-1` from that one check and add a test that says why. If nothing was read and nothing was said, the note is still saved and found by its time and place.
11. **A record made by `picture-1` is picture text, not his words.** Mark this in one place (`RecordsFromPictures.isPictureText(extractorName)`), because cards 16h and 16j must not let such a title reach a hosted model unless the owner turned on that separate switch.

### E. Pictures not read yet

12. Catch-up: after the daily upkeep (where `Repository.purgeDeleted` runs) and when `PICTURE_TEXT` is switched on, read up to 20 picture attachments whose `ocr_engine` is null: open the **stored** copy, read it, `updateOcr`, re-index. These were saved while the switch was off, or the reader timed out. Reading the stored copy is less sharp than reading before shrinking; that is accepted and the `engine` string gets the suffix `"/stored"`.
13. Disclosure, as plain text in Settings beside the `PICTURE_TEXT` switch until card 23 builds the explanation card: "The phone reads the words itself. The reader is made by Google and sends Google figures about how it is used and how it performs. It does not send your pictures or their words."
14. Events (`Dbg.event`): read ok (n1 lines, n2 characters, ms), read failed, read timed out, catch-up ran (n1 pictures). **Never the words.**

## Out of scope

Sending picture text to a model (16j). Sending a picture to a model (16l). Other scripts than Latin. Handwriting (the library reads little of it; no promise). Translating. Barcodes (card 21). Any judgement of what a picture shows. Correcting the read words by hand.

## Acceptance checks

**Unit tests (fake reader; Robolectric where Room is needed)**

- The reader is called before the encoder and its words and engine name land in the attachment row.
- Draft wording: four lines joined; two lines; none (no draft shown); a first line that is blank is skipped.
- Search: a capture saying "wine for Saturday" with picture text "ALLEGRINl Valpolicella" is found by "Saturday", by "Valpolicella" and by "ALLEGRINl"; after the picture is removed it is found by "Saturday" only; after `rebuild` the results are the same as before it.
- His words win: text "Allegrini, row 5" with picture text "ALLEGRINl" yields records whose titles and quotes come from his text only.
- Picture only: blank text with picture text of three lines gives one `REFERENCE` record titled with the first two; with no picture text the title is "Picture"; no hosted extractor is called (use one whose transport throws).
- A capture with blank text and pictures that later gets words typed (text edited) is read again by the normal extractors and the `picture-1` record is replaced.
- Catch-up: two attachments with null engine are read and indexed; one with an engine is left alone; with the capability off nothing is read.
- With `PICTURE_TEXT` off, `Container.pictureText` is `NoPictureText`.
- A soft-deleted capture's picture text is not searchable.

**Real phone (S25 Edge); write each result in the experiment log**

- **Labels (RISKS.md R31).** With the in-app camera (16e), or shared photos if 16e is not built, take ten wine or product labels in shop lighting. For each, write whether the name, the maker, and the price on the shelf tag were read correctly. **Pass:** the name is findable by search for at least 8 of 10. If it fails, the feature still works by his spoken words, place and date: say so in the log and in the hand-back.
- Share a screenshot of an article. Search for a word that appears only in the picture: the note is found.
- Turn on aeroplane mode and add a picture: its words are still read (the model is in the app).
- Milliseconds for one read, from the event log, for a label and for a full screenshot.
- APK size before and after ML Kit. `zipalign -c -P 16 -v 4` passes on the APK, and the first read does not crash (the library has native code; Android 17 needs 16 KB alignment).

## Notes and risks

- Confidence that the bundled library sends no picture and no text: high for what Google's terms say (read 2026-11-11 by a research helper), unverifiable from here. `docs/PRIVACY.md` already lists it under what cannot be promised.
- Whether the library starts any reporting merely by being in the APK, before the first read, is **not known**. The only certain way to send Google nothing is a build without the library. Say this plainly if the owner asks; do not claim the switch stops it.
- The library brings Google Play services support libraries with it. It does not need Play services to be signed in to work (medium confidence; the aeroplane-mode check covers the offline part).
- Screenshots contain other people's names and words. They stay on the phone here; this is exactly why sending them is a separate switch in a separate card.

**Suggested builder: Sonnet.** It wires a reader behind an existing seam and extends the search document; the logic that matters is small and fully covered by unit tests with a fake reader.

## Owner decision, 2026-11-12 (D80)

ML Kit text reading is **approved by the owner** (Google's proprietary terms; it reports usage figures to Google; Q46 accepted for development). Keep the fixed disclosure line on the "What leaves my phone" screen. Reconsider for a product (Q39, Q46).

## What was built, and what differs from the card (2026-11-12)

Built (under `app/src/main/java/app/stash/` unless noted):
- `domain/PictureText.kt` (pure): `PictureTextRules` (4,000-character cut, `lines`, `median`/`stats`, `draftLine`, `title`, `engine`, `asStored`, the batch of 20, the fixed disclosure text) and `RecordsFromPictures` (`EXTRACTOR = "picture-1"`, `isPictureText(name)`: the one place that marks such a record, for cards 16h and 16j). `domain/Capability.kt`: `PICTURE_TEXT` (no permission, on by default, the card's "without it" text). `domain/SearchDocs.kt`: `captureBody(text, pictureTexts = emptyList())`. `domain/Dbg.kt`: codes `READ`, `TIMED_OUT`, `CAUGHT_UP` (component PICTURE).
- `pictures/MlKitPictureText.kt`: the only code that touches ML Kit. `TextRecognizerOptions.DEFAULT_OPTIONS` (Latin, bundled), one recogniser per process, lines in the library's block order joined with new lines, cut at 4,000, `stats` from the lines' bounding-box heights, engine `mlkit-text-latin/16.0.1` (a test checks the version string against `gradle/libs.versions.toml`). No words found gives empty text; any failure gives null. Events carry counts and milliseconds only. `PictureTextChoice.reader(on, factory)` picks it; `Container.pictureText` is now a getter (was a `var`) that asks the switch each time, so with the switch off no ML Kit class is touched.
- `model/PictureOnlyExtractor.kt` (`picture-1`) and its wiring in `pipeline/ExtractionPipeline.kt`: blank text plus at least one picture gives exactly one `REFERENCE` record, confidence `LOW`, title from the first picture's first two non-empty lines (80 characters) or "Picture"; no rules and no model run. `data/ExtractionRunner.kt` skips `ExtractionChecks` for extractor `picture-1` only (its quote is the picture's words and there is no text of his, so "quote is in his text" would always fail it and mark it BAD_QUOTE; a test shows this). Every other extractor is checked as before. Typing words later sets PENDING (existing `updateText`), the normal extractors run and the `picture-1` record is replaced (existing replace rule).
- Search: `SearchIndex.indexCapture` loads `AttachmentDao.ocrTexts` (new query, no schema change); `AttachmentRepository.addTo`, `remove` and `setOcr` re-index the capture inside their transactions (a deleted capture stays out). `restore` and `rebuild` already call `indexCapture`, so they include picture text.
- `pictures/PictureCatchUp.kt`: up to 20 pictures with null `ocr_engine` of live notes (new `AttachmentDao.unreadPictures`), opened from the stored copy, read (5 s limit), `setOcr` with engine suffix `/stored`, then `afterRead` (a picture-only note is asked to be read again so its title appears). A picture the reader could not read is touched (`touchUnread`, which only moves `updated_at`) so it goes to the back of the line and cannot starve the others; it is tried again at the next run. Run from `TaskUpkeepWorker` after the daily upkeep and from `MainViewModel.setCapability` when the switch is turned on. With the switch off it reads nothing.
- UI: `AnnotateUi.draftLine` is filled by `annotateUiFor` (first picture's first four non-empty lines; display only, never in the text field). `NotePictures` shows a collapsed "Read from the picture (show/hide)" under the strip, inside the same private wrapper as the pictures. The viewer's words block (16d) was already there. Settings shows the fixed disclosure under the `PICTURE_TEXT` row (test tag `picture-text-disclosure`).
- Build: `gradle/libs.versions.toml` (`mlkitTextRecognition = "16.0.1"`) and a plain `implementation(libs.mlkit.text.recognition)` in `app/build.gradle.kts`. No flag was needed: 16.0.1 compiles cleanly with Kotlin 2.2.10.

Differs from the card, or worth knowing:
- The card calls the repository call `updateOcr`; 16a built it as `AttachmentRepository.setOcr` (the DAO call is `updateOcr`). The new code uses `setOcr`.
- Item 4 needed no change: `PicturePreparer` already reads before shrinking, copies `text` and `engine` into the `InboundAttachment`, and passes `stats` to `PictureSizing` (16b's tests cover all three).
- The "Container.pictureText is NoPictureText with the switch off" test builds the real `Container` under Robolectric and flips the switch.
- A picture whose first picture has no words but a later one has: the title uses the first picture's words only, as the card says.
- Picture text of a Private note is searchable by the same rules as his words (the index follows the capture); the stored words are drawn only after "Show".
- Nothing about the library's own reporting was tested here: see the phone checks and the card's notes. It is not known whether the library reports anything before the first read; only a build without it sends Google nothing.

Tests (29 new; 610 in all pass): `PictureTextRulesTest` (draft wording: four lines, two lines, none, blank first line; title rule; cut and stats; engine strings and the version-catalogue check; search document; the capability and its text; the disclosure text; `PictureTextChoice` does not build the library's reader when off; the picture-only extractor; why the quote check is exempted) and `PictureTextTest` (Robolectric, real Room, fake reader: search by his words and by the picture's, after removal, after `rebuild`, after `addTo` and `setOcr`, a deleted note; his words win, and a hosted model is shown his text only; picture-only note with and without words read, no model called; typed words replace the picture record; catch-up of two unread, one already read left alone, switch off reads nothing, a failing read does not block, the batch of 20, deleted notes skipped, the picture-only note filed again; the container's reader with the switch off and on).

Phone checks that need the owner are listed in `docs/EXPERIMENT_LOG.md` under "Card 16f".

