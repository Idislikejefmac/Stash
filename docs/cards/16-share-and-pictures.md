# 16 · Pictures: index of cards 16a to 16l

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

**This page is an index, not a card to build.** On 2026-11-12 card 16 was split into twelve small cards (DECISIONS.md D79). The card as it stood before the split is kept unchanged at [archive/rev3/16-share-and-pictures.md](archive/rev3/16-share-and-pictures.md); do not build from it. The number 16 stays here so that every other document's "card 16" still leads somewhere.

## What pictures are, in one paragraph

A picture and a note belong together whichever came first. **Picture first:** he shares a screenshot or photographs a bottle; the phone reads the printed words to make a draft and asks "Anything to add about this?". **Note first:** he describes a thing and the app quietly offers "Add a picture?". A note can have **any number** of pictures. The app keeps its own small, private, encrypted copy of each; a picture is found later by place, by date and by the words printed on it, with no image recognition and nothing sent anywhere.

## Decisions every 16x card keeps (D69, D72, D73, D76, D77, D78)

- **Private by default, by construction.** App-private storage only (not the gallery, not a gallery's cloud backup, not Android's backup or device transfer); each file encrypted with its own key; never in a notification, a widget, a digest, the recent-apps view, a diagnostic report or a log; shown only inside the reading activity; a picture on a Private note follows that mark in every way.
- **No detector.** The app does not look at what a picture shows and contains no "explicit image" check.
- **Never sent.** No picture leaves the phone. The **words read** from a picture leave only behind their own switch (16j), off by default, and never for a Private note. Sending a picture to a model is a separate, later, unscheduled card (16l) that needs a new decision first.
- **Size (owner, 2026-11-11).** The smallest legible copy, never full resolution. A copy: a gallery original is never changed or moved. Long edge at most 1,600 px for photos and 1,920 px for screenshots, lossy WebP at quality 75. A setting "Picture size": Small (1,024), Standard (1,600 / 1,920, the default), Large (2,400), applied to pictures added after the change. The words are read from the picture **before** it is shrunk. (16b)
- **The offer is a button**, never a notification, never accepted by voice, never an attention-list item. (16g)
- **Libraries are recorded in `docs/LICENSES.md` before they are added.**

## New on 2026-11-12 (owner), and where each lives

| Requirement | Card |
| --- | --- |
| Any number of pictures on one note: order, adding later, removing one, the thumbnail strip, delete and purge of many files | 16a (data and files), 16d (what he sees) |
| Three ways in: Share to Stash (including the screenshot toolbar), the gallery photo picker, the in-app camera; the lock-screen camera question kept as a check | 16c (share, picker), 16e (camera, lock screen) |
| A future option to send one picture to the user's own model provider: seam, consent and safety rules only | 16l |
| Voice notes keep their audio (D77): pictures and audio share one encrypted file store | 16a section E (design only; cards 24 and 25 are the spikes, card 26 would build it) |
| Every library sellable and on the licence register (D78) | each card names its libraries; list below |

### How "many pictures per note" works, in one place

- **Order:** the order they were added (`attachment.position`, 0, 1, 2 …). The first is the note's cover. He cannot reorder by hand (not built; the column allows it later).
- **Adding later:** from the note's row, by gallery picker or camera; new pictures go last. Adding never changes the note's words or records.
- **Removing one:** from the viewer, after a question; the row, its key and its file are deleted at once. The others keep their order. Removing the only picture of a note with no words removes the note (to the 30-day bin).
- **The strip:** thumbnails under the note's text, scrolling sideways, loaded only as they come on screen, made in memory and never written to disk. Tap for a full-screen viewer with swipe between that note's pictures.
- **Deleting the note:** it and its pictures disappear at once (soft delete); after 30 days the purge removes every row, then every file, and leaves tombstones. A daily sweep deletes any file with no row; a row with no file is reported, never silently dropped.

## The cards

| Card | What it builds | Suggested builder |
| --- | --- | --- |
| [16a](16a-attachment-store.md) | The `attachment` table and migration, the encrypted file store (Tink), many per note, the one way in, remove, purge, sweep, consistency check; how audio will reuse it | Opus |
| [16b](16b-picture-size-and-format.md) | Decode, read-before-shrink seam, shrink, WebP, no embedded data; the Picture size setting | Sonnet |
| [16c](16c-share-and-annotate.md) | Share to Stash (one or many pictures, text, links), the "Anything to add?" screen, the gallery picker | Sonnet |
| [16d](16d-pictures-on-a-note.md) | The thumbnail strip, the viewer, add later, remove one, Private handling, no recents or screenshots | Sonnet |
| [16e](16e-camera.md) | The camera inside Stash (CameraX), straight to private storage; the lock-screen check | Sonnet |
| [16f](16f-picture-text.md) | Reading the words on the phone (ML Kit), the draft, search by picture words, "his words win", a picture with no words | Sonnet |
| [16g](16g-picture-offer.md) | The quiet "Add a picture?" after a note about a thing | Sonnet |
| [16h](16h-finding-pictures.md) | "What was that bottle at Total Wine…", "show me the bottles…" (needs card 08) | Sonnet |
| [16i](16i-recent-picture-offer.md) ◆ | "Include the screenshot from 2 minutes ago?" (all-photos permission; off by default) | Sonnet |
| [16j](16j-picture-text-to-model.md) | The switch that sends a picture's **words** to the model (needs card 04; off by default) | Opus |
| [16k](16k-picture-explanations.md) | Explanation cards and "What leaves my phone" lines for pictures (needs card 23) | Sonnet |
| [16l](16l-hosted-vision.md) ◆ | **Later, unscheduled:** sending one picture to the user's own provider, per picture, by consent | Opus |

## Order and dependencies

Build in this order. "Before hardening" means the card can be built before cards 10 (backup), 02 (gate) and 14 (encryption), which is the order the owner chose on 2026-11-12 (card 25's spike first, then pictures).

| Order | Card | Depends on | Before hardening? | Why here |
| --- | --- | --- | --- | --- |
| 1 | 16a | 01, 13 | **Yes** | Everything else writes through it; it carries the only migration and the delete path, so it is built and tested alone |
| 2 | 16b | 16a | **Yes** | No picture may ever be stored at full size or with camera data inside, so the one function that prevents it exists before any screen |
| 3 | 16c | 16a, 16b | **Yes** | The route with no permission and no new library; it brings the annotate screen the camera reuses |
| 4 | 16d | 16a, 16b, 16c | **Yes**, with a stopgap for Private pictures until card 02 | Without it saved pictures cannot be seen; it also sets the no-recents, no-screenshots flag |
| 5 | 16e | 16a, 16b, 16c (16d for "add to a note") | **Yes** | Needs a permission, a library and a phone-only check (lock screen), so it follows the routes that need none |
| 6 | 16f | 16a, 16b, 16c | **Yes** | Adds Google's reader; pictures are already useful by words, place and date without it |
| 7 | 16g | 16a, 16d, 16e | **Yes** | Needs the camera and the row's add button to have somewhere to go |
| any time after 3 | 16i ◆ | 16a, 16b, 16c | Yes, optional | A fortnight's experiment to answer Q34 |
| when 08 is done | 16h | **08**, 16a, 16d, 16f | Waits for card 08, not for hardening | It extends card 08's router and lookups |
| when 04 is done | 16j | **04**, 13, 16f | Waits for card 04; ask the owner first (Q35) | It changes what leaves the phone |
| when 23 is done | 16k | **23**, and the 16x cards built so far | **Waits** (23 is in the hardening block) | Until then each switch shows its one-line "without it" text |
| not scheduled | 16l ◆ | 04, 13, 23, **02, 14**, 16a, 16d, 16k (22 for other providers), and a new decision | **Waits**, after hardening | The first thing that would send a picture; not for a build with no lock and no encryption |

What building pictures **before** the hardening block costs, said once: the per-file keys sit in an unencrypted database on a debug build (the same exposure as the notes today, ended by card 14 with no change to the files); there is no backup of pictures until card 10; and until card 02 anyone holding the unlocked phone can open Stash and see pictures, with only a tap-to-show row in front of Private ones.

**Other cards that say "depends on 16".** Card 21 (codes) needs 16a, 16b, 16c, 16e and, for codes inside saved pictures, hooks into 16b's preparer. Card 10 (backup) picks up the `attachment` table and files from 16a. Card 02's Private test with a picture needs 16d. Cards 24 and 25 mean 16a's store when they say "card 16's store design".

## Platform facts the cards rely on, and how each is checked

| Fact | Confidence | Checked by |
| --- | --- | --- |
| Tink's streaming AEAD works on Android and can take a raw 32-byte key | high / medium to high | 16a's JVM round-trip test, then its on-phone self-test |
| `Bitmap.compress` writes lossy WebP (Android 11+) with no embedded camera data | high | 16b's chunk list on the phone |
| `ImageDecoder` applies a picture's stored rotation and reads HEIC | high | 16b: portrait and landscape both upright |
| Shared content needs no permission and must be read while the receiving activity lives; the photo picker needs none | high | 16c on the phone |
| Samsung's screenshot toolbar shares through the ordinary share sheet | medium, unverified | 16c: first phone check |
| CameraX's in-memory capture never writes to the gallery | high | 16e: look in Samsung Gallery and Google Photos |
| The camera opens over the lock screen on One UI | **low either way** | 16e: the lock-screen check (RISKS.md R33); either answer is accepted |
| ML Kit's bundled reader works offline and sends no picture or text | high for Google's terms, unverifiable here | 16f: aeroplane mode; disclosed regardless |
| Native libraries in CameraX and ML Kit are 16 KB aligned for Android 17 | medium | `zipalign -c -P 16` and a first run, in 16e and 16f |
| With "selected photos only", a new screenshot is invisible and `READ_MEDIA_IMAGES` reads as not granted | medium to high | 16i on the phone |

## Libraries these cards add (for `docs/LICENSES.md`; all unverified here)

| Library | Card | Licence, as far as known |
| --- | --- | --- |
| Tink (`com.google.crypto.tink:tink-android`) | 16a | Apache-2.0. Brings protobuf-javalite (BSD-3-Clause) and possibly Gson and annotation libraries (Apache-2.0) |
| CameraX (`androidx.camera:*`) | 16e | Apache-2.0 |
| ML Kit Text Recognition v2, bundled Latin model | 16f | Google's own ML Kit terms: free, not open source, reports usage figures. A custom licence, so "ask first" under D78. The model file is believed to be covered by the same terms. Brings Google Play services support libraries (Android SDK licence) and Google's data-transport libraries (believed Apache-2.0) |
| WebP encoding, picture decoding, the photo picker | 16b, 16c | No library: the Android platform does these |

## Open questions for the owner

1. **The small-print rule.** Under Standard, a page of many small lines is kept at 2,400 px instead of 1,920 so it stays readable. Your note of 2026-11-11 said to keep this "only if the owner does not object". Keep it, or cap at 1,920? (16b builds it behind one constant either way.)
2. **Removing one picture is immediate and final** (after one question), while deleting a whole note has a 30-day bin. Is that the behaviour you want, or should a single removed picture also wait 30 days?
3. **No screenshots of Stash from 16d on.** Keeping pictures out of the recent-apps view means the main screen blocks screenshots in every build, including yours. Acceptable now, or only once card 02 is built?
4. **Q35** (send a picture's words to the model) and **Q46** (Google's reader reporting usage figures) are still open in QUESTIONS.md; 16j waits on the first, and 16f assumes "accept for development" for the second.
5. **16l** needs your explicit go-ahead and a decision that amends "a picture never leaves the phone" (D69, D72), and before any product a lawyer's view (Q48).
