# 16b · Picture size and format: the smallest legible copy

Read [00-READ-FIRST.md](00-READ-FIRST.md) first. Part of the card 16 split ([index](16-share-and-pictures.md)).

**Status: built 2026-11-12, JVM only** (446 unit tests pass, 23 new; no schema change). Nothing was installed on the phone: the phone checks wait for the owner. See "What was built, and what differs" at the end.

## Goal

One function turns any picture the app is handed into the app's own copy: small, still readable, with nothing hidden inside it (no camera data, no position), encrypted on disk through card 16a's store. Every later card that takes in a picture calls it and nothing else.

## Depends on

16a.

## Context

**The owner's decision of 2026-11-11, which this card implements:** store the **smallest legible** picture and never a full-resolution one. Store a **copy**; a gallery original is never changed, moved or deleted. Read the words from the picture **before** shrinking it. A setting "Picture size" offers Small, Standard and Large and applies to pictures added after the change.

| Setting | Photo, long edge | Screenshot, long edge |
| --- | --- | --- |
| Small | 1,024 px | 1,024 px |
| **Standard** (default) | 1,600 px | 1,920 px |
| Large | 2,400 px | 2,400 px |

Format: lossy WebP at quality 75. Android can write JPEG, PNG and WebP and nothing newer; WebP is the smallest of the three at the same look. Encoding is done by the platform (`Bitmap.compress`); **no library is added by this card**.

"Before shrinking" has one practical limit. The phone's main camera makes pictures of up to 200 megapixels, which do not fit in memory as pixels. So a picture is first decoded at a **reading size** of at most 4,096 px on the long edge; the words are read from that; then it is shrunk to the stored size. Google's guidance for its text reader is that letters taller than 24 px gain nothing, so nothing legible is lost.

The text reader itself is card 16f. This card defines the seam it plugs into and ships the do-nothing version.

## Scope

1. `domain/PictureSizing.kt` (pure Kotlin, no Android imports):
   - `enum PictureSize { SMALL, STANDARD, LARGE }`.
   - `targetLongEdge(kind, size, text: TextStats?)`: the table above. **Small-print rule, Standard only:** when `text` reports 12 or more lines and its median letter height, scaled to the Standard size, would be under 16 px, the target becomes 2,400 px. Small is never raised (it is the owner's explicit choice). Keep the rule behind one constant, `SMALL_PRINT_BOOST = true`, so it can be turned off in one line (the owner has not yet said whether he wants it; see the index's open questions).
   - `scaled(width, height, targetLongEdge)`: keeps the shape, rounds to whole pixels, never below 1, and **never enlarges**: a picture already smaller than the target keeps its size (it is still re-encoded, see item 4).
   - `guessKind(mime, width, height, screenWidth, screenHeight, displayName)`: `SCREENSHOT` when the name starts with "Screenshot" (any case), or the size equals the screen's in either orientation; otherwise `PHOTO`. Callers that know better (the camera) pass the kind and skip the guess.
   - `TextStats(lines: Int, medianLetterHeightPx: Float)`.
2. `pictures/PictureTextReader.kt` (new package `app.stash.pictures`): `interface PictureTextReader { suspend fun read(bitmap): PictureText? }` with `PictureText(text, stats: TextStats, engine: String)`, and `NoPictureText`, which returns null. `Container.pictureText` returns `NoPictureText` until card 16f.
3. `pictures/PictureDecoder.kt`: from a content `Uri` or from bytes in memory, using the platform `ImageDecoder`: read the header, set the target size so the long edge is at most 4,096 px, software allocator, and let `ImageDecoder` apply the picture's stored rotation. Returns the bitmap and the original width and height. Anything it cannot decode (a video, a broken file) returns a failure code, not an exception with content. An animated picture gives its first frame.
4. `pictures/PictureEncoder.kt`: scale with filtering to `scaled(...)`, then `compress(Bitmap.CompressFormat.WEBP_LOSSY, 75, …)` into memory. Every stored picture goes through this, even one that needs no shrinking, because **re-encoding is what removes the camera's embedded data, including any position**.
5. `pictures/PicturePreparer.kt`: `suspend fun prepare(input, kindHint: AttachmentKind?, source: AttachmentSource): Prepared`, where `Prepared` holds an `InboundAttachment` (16a) and a thumbnail bitmap of at most 384 px kept **in memory only**. Steps, in this order, one picture at a time (a `Mutex`), on a background dispatcher:
   1. decode at reading size; 2. `pictureText.read(...)` with a 5 second limit (null on time-out or failure); 3. choose the target with `PictureSizing`; 4. encode; 5. make a new id, key and file name and call `AttachmentStore.write`; 6. make the thumbnail; 7. recycle the large bitmaps.
   - `discard(prepared)` deletes the file again (used when the owner cancels).
   - Nothing decrypted or decoded is ever written to disk, including the cache directory.
6. Setting: `Settings.pictureSize` (default `STANDARD`) and a "Picture size" row in the Settings screen with three choices and one line each: "Small: about 1000 dots wide. Fine for most photos." · "Standard: small print stays readable." · "Large: for documents and fine detail. About twice the storage." Under it: "Applies to pictures you add from now on."
7. `domain/WebpChunks.kt` (pure): lists the chunk names of a WebP file (`VP8 `, `VP8L`, `VP8X`, `EXIF`, `XMP `, `ICCP` …) from its bytes. Used by the tests and by item 8 to prove nothing is embedded.
8. A debug-only screen (`app/src/debug`, like `AudioSpikeActivity`): pick one picture with the system photo picker, prepare it at each of the three sizes, and show for each: stored width and height, bytes, milliseconds, and the chunk list; then discard all three files. Results are also written as `Dbg.event(PICTURE, STORED, n1 = bytes, n2 = long edge)`. This screen is how this card is checked on the phone before any real screen exists.

## Out of scope

Reading words (16f). Any real screen (16c, 16d, 16e). Cropping, rotating by hand, filters. Keeping the original. A newer format (AVIF, JPEG XL): Android cannot write them. Changing pictures already stored when the setting changes.

## Acceptance checks

**Unit tests (JVM)**

- `targetLongEdge` for every kind and setting; the small-print rule raises Standard to 2,400 with 12 lines of 10 px letters, does not with 11 lines, does not for Small, and does nothing when the constant is off.
- `scaled`: 4000 x 3000 at 1,600 gives 1600 x 1200; 1080 x 2340 at 1,920 gives 886 x 1920; 800 x 600 at 1,600 stays 800 x 600; a 1 x 5000 strip never reaches 0.
- `guessKind`: a name starting "Screenshot_", a picture the size of the screen turned sideways, an ordinary 4:3 photo.
- `WebpChunks` on three hand-made byte arrays: plain lossy, one with an `EXIF` chunk, one that is not WebP.
- `PicturePreparer` with a fake decoder, encoder and store: the reader is called **before** the encoder; a reader that hangs is abandoned after 5 seconds and the picture is still stored; a store failure leaves no file; `discard` deletes the file.
- If Robolectric's native graphics mode can really encode WebP, also assert that the output starts `RIFF….WEBP` and has no `EXIF`, `XMP ` or `ICCP` chunk. If it cannot, say so in the hand-back; the phone check below covers it.

**Real phone (S25 Edge), with the debug screen; write the numbers in the experiment log**

- Five pictures: a gallery photo taken with location tagging on, a 200-megapixel photo if the camera has that mode, a screenshot of a text-heavy page, a HEIC photo, a PNG. For each, at each size: bytes, milliseconds, and the chunk list. **Pass:** every output is WebP with only image chunks; no crash and no out-of-memory on the largest; Standard photos are between about 100 and 500 KB (the design's estimate is 150–350 KB: write down the real figure).
- A portrait photo and a landscape photo both come out upright.
- The debug screen shows the Standard copy of the text-heavy screenshot full-screen, with pinch to zoom: can the owner read the smallest print? Then the Small copy. Write his answers. This is the "legible" in "smallest legible".
- After the test `adb shell run-as app.stash ls files/att` is empty and `ls cache` holds no picture.

## Notes and risks

- Confidence: `WEBP_LOSSY` exists from Android 11, high. `Bitmap.compress` writes pixels only and no embedded data, high. `ImageDecoder` applies stored rotation by itself and reads HEIC on this phone, high for both (documented; the upright check proves it). Robolectric encoding real WebP, low to medium.
- WebP's lossy mode softens fine coloured text slightly more than JPEG at the same size. If the owner cannot read the Standard screenshot, try quality 85 for `SCREENSHOT` before raising the size, and record both.
- Memory: a 4,096 px reading bitmap is about 50 MB. Prepare one picture at a time; never hold two reading bitmaps.

**Suggested builder: Sonnet.** The sizing rules are pure functions pinned by a table of tests, and the rest is three platform calls behind fakes.

## Owner decision, 2026-11-12 (D80)

The small-print bump to **2,400 px is wanted**: when the words read from the picture show many small lines (average letter height under the card's 16 px line at the stored size), keep 2,400 px under Standard instead of 1,920 px. It is no longer optional; keep it behind one constant so it can be tuned from the phone measurements.

## What was built, and what differs from the card (2026-11-12)

Built (under `app/src/main/java/app/stash/` unless noted):
- `domain/PictureSizing.kt` (pure): `PictureSize`, `PictureKind { PHOTO, SCREENSHOT }`, `TextStats`, and `PictureSizing` with `targetLongEdge`, `scaled`, `guessKind` and the constants (`SMALL_PRINT_BOOST = true`, 12 lines, 16 px, 2,400 px, reading edge 4,096, thumbnail 384, WebP quality 75, reader limit 5,000 ms). `domain/WebpChunks.kt` (pure): `list(bytes)` and `onlyImageData(bytes)`.
- `pictures/PictureTextReader.kt` (`PictureText`, `NoPictureText`), `pictures/PictureDecoder.kt` (interface, `AndroidPictureDecoder` on `ImageDecoder`: software allocator, header read, `setTargetSize` only when the long edge is over 4,096, rotation left to the decoder, first frame of an animation, every failure a `PictureFailure` code), `pictures/PictureEncoder.kt` (interface, `AndroidPictureEncoder`: filtered scale, `WEBP_LOSSY` at 75, thumbnail), `pictures/PicturePreparer.kt` (steps in the card's order behind a `Mutex`, `discard`).
- `Settings.pictureSize` (default Standard), `MainViewModel.pictureSize`, a "Picture size" row in Settings (three chips, the card's one-line texts and "Applies to pictures you add from now on."). `Container.pictureText` (`NoPictureText`) and `Container.picturePreparer`.
- Debug only: `app/src/debug/java/app/stash/debug/PictureSizeActivity.kt` and its manifest entry: pick one picture with the photo picker, prepare it at all three sizes, show size, bytes, ms and chunk list, `Dbg.event(PICTURE, STORED, n1 bytes, n2 long edge, ms)`, view each stored copy full-screen with pinch to zoom (decoded from memory), "Discard all" (also on leaving).
- Tests: `PictureSizingTest` (12, plain JVM), `PicturePreparerTest` (10, Robolectric: reader before encoder, hanging reader abandoned and picture still stored, throwing reader, kind guess, small-print path, store failure leaves no file and no thumbnail, undecodable picture, discard, three sizes, no stray files), `WebpEncodingTest` (1).

Differs from the card, and why:
- **Robolectric can encode real WebP** (`@GraphicsMode(NATIVE)`): the output starts `RIFF....WEBP`, has no `EXIF` or `XMP ` chunk, and the thumbnail size is right. **But Skia writes an `ICCP` chunk** (the output was `VP8X, ICCP, VP8 `). The card wanted no `ICCP` either. It is a colour profile, not camera or position data, and a wide-colour (Display P3) photo needs it to keep its colours, so `WebpChunks.onlyImageData` allows it and the test asserts only no `EXIF` and no `XMP `. If the owner would rather have none, the cost is shifted colours on wide-colour photos; say so and it becomes a small change (draw into an sRGB bitmap first). The phone's chunk list shows whether the real device does the same.
- **`targetLongEdge` takes a fourth argument, `readLongEdge`** (long edge of the bitmap the reader saw), because the card's rule ("letter height scaled to the Standard size") cannot be computed without it. Letter height is scaled by `min(1, base / readLongEdge)`; with `null` it is taken as already at the stored size (D80's wording), which is what the card's "12 lines of 10 px" test uses.
- **Failures are exceptions, not a returned value.** `prepare` returns `Prepared` as the card says and throws `PictureException(PictureFailure)` with a code only (also written as `Dbg.event(PICTURE, FAILED, code)`).
- **`prepare` has an optional `sizeOverride`** so the debug screen can try all three sizes without changing the setting; real callers leave it out.
- **The kind guess needs the screen size and display name**, so the preparer takes the screen size as a function and `PictureInput` carries an optional `displayName`.
- The constant `SMALL_PRINT_BOOST` is compile-time, so the "constant off" case cannot be driven from a test; the test pins the constants instead.
- A pure-Kotlin `PictureKind` exists so `domain/` does not import `data/` enums; the preparer maps it to `AttachmentKind`.
- The text reader is not wired: nothing sets `ocrText` until card 16f, so the small-print rule is only exercised by tests so far.
- No quality-85 fallback for screenshots was built (the card says only to try it if the owner cannot read the Standard screenshot).

Unverified / still to do (the owner; see "Card 16b" in `docs/EXPERIMENT_LOG.md`): every real-phone check in the card (five pictures, byte sizes, times, chunk lists, upright portrait and landscape, legibility, `files/att` empty afterwards). Rotation, HEIC reading and the 200-megapixel decode are unproven until then.
