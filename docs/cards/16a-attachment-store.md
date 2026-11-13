# 16a · Attachments: the table, the encrypted file store, delete and the sweep

Read [00-READ-FIRST.md](00-READ-FIRST.md) first. This is the first card of the card 16 split ([index](16-share-and-pictures.md), DECISIONS.md D79).

**Status: built 2026-11-12, JVM only** (423 unit tests pass, 29 new; schema version 3). Nothing was installed on the phone: the phone checks wait for the owner. See "What was built, and what differs" at the end.

## Goal

The app can keep any number of files with one note, each encrypted with its own key, and can never lose track of one: a row always has a file, a file always has a row, and deleting a note really deletes its files. No screen is built here. Pictures are the first user; a voice note's audio (D77) will be the second and must fit without a change to this store.

## Depends on

01, 13. Nothing else. It can be built before the hardening block (10, 02, 14); see "Notes and risks" for what that means.

## Context

Read these before writing: `data/Entities.kt`, `data/Enums.kt`, `data/Migrations.kt`, `data/StashDatabase.kt` (version 2 today), `data/Repositories.kt` (`CaptureRepository.insertCapture`, `softDelete`, `restore`, `purge`), `data/ConsistencyCheck.kt`, `capture/InboundSink.kt`, `app/src/test/java/app/stash/MigrationTest.kt`, and `docs/STORAGE.md` ("Pictures are files, not rows", rules 7 and 9).

Decisions kept from card 16 (D69, D72): files live only in the app's private storage; each file has its own random key, kept in its row, so a file is exactly as protected as the database, today and after card 14, with no change to the files; a file's privacy follows its capture's `sensitive` mark, there is no second flag; nothing decrypted is ever written to disk.

**Words used here.** An *attachment* is one file that belongs to exactly one capture. A capture has **zero, one or many** attachments. `CaptureSource.SHARED` and `PHOTO` and `capture.photo_hint` already exist in the code; this card does not change them.

**Crash-safe order, the one rule to remember:** when adding, write the **file first, then the row**; when deleting, delete the **row first, then the file**. A crash in the middle then always leaves a file with no row, never a row with no file, and the sweep (item 12) removes it.

## Scope

### A. The table (`data/`)

1. Add `AttachmentKind { PHOTO, SCREENSHOT, AUDIO }` and `AttachmentSource { SHARE, CAMERA, PICKER, RECENT }` to `Enums.kt`. (`SCAN` is added by card 21; `AUDIO` is declared now and used by nothing in this card, see section E.) Add `PICTURE_ADDED` and `PICTURE_REMOVED` to `RevisionKind`.
2. Entity `AttachmentEntity`, table `attachment`, index on `capture_id`:

   | Column | Type | Notes |
   | --- | --- | --- |
   | `id` | TEXT, primary key | `Ids.new()` |
   | `capture_id` | TEXT, not null | exactly one capture |
   | `kind` | TEXT, not null | `AttachmentKind` name |
   | `position` | INTEGER, not null | order within the capture, see item 6 |
   | `mime` | TEXT, not null | `image/webp` for pictures |
   | `file_name` | TEXT, not null | random (`Ids.new()`), no extension |
   | `file_key` | BLOB, not null | 32 random bytes from `SecureRandom` |
   | `format_version` | INTEGER, not null | `1` |
   | `byte_size` | INTEGER, not null | size of the plain bytes |
   | `sha256` | TEXT, not null | of the plain bytes, lower-case hex |
   | `width`, `height` | INTEGER, null | pixels; null for audio |
   | `duration_ms` | INTEGER, null | null for pictures; for audio later |
   | `ocr_text`, `ocr_engine` | TEXT, null | filled by card 16f; both null means "not read yet" |
   | `source` | TEXT, not null | `AttachmentSource` name |
   | `created_at`, `updated_at` | INTEGER, not null | implement `Stamped` like the other entities |

   There is **no** `deleted_at`: an attachment is hidden when its capture is soft-deleted and is really deleted by item 9 or 10.
3. Hand-written migration from the current version to the next free one (2 to 3 if nothing else has been added), following `Migrations.V1_TO_V2`: one `CREATE TABLE` and one `CREATE INDEX`, copied from the exported schema JSON. Add it to `Migrations.ALL`, add the entity to `StashDatabase`, commit the new schema file. No existing table changes.
4. `AttachmentDao`: `insert`, `get(id)`, `forCapture(captureId)` ordered by `position, created_at, id`, `picturesForCapture(captureId)` (the same, `kind IN ('PHOTO','SCREENSHOT','SCAN')`), `maxPosition(captureId)`, `delete(ids)`, `deleteForCapture(captureId)`, `refs()` for the consistency check (id, capture id, file name), `fileNames()`, `updateOcr(...)`. Add one read model without the key, `AttachmentInfo` (id, capture id, kind, position, width, height, byte size, `ocr_text`), and a query that returns it. **Screens and view models only ever get `AttachmentInfo`; `file_key` is read only inside `AttachmentRepository`.**
5. The key never appears in a log, an event, `Repository.exportText`, or the debug dump (`debug/DumpReceiver`): the dump prints an attachment as id, kind, position, size and nothing else.

### B. Ordering and "many per note"

6. `position` starts at 0 and is the order the files were added. A file added later gets `maxPosition + 1`. Removing one leaves a gap; positions are never renumbered. There is no limit on how many a capture can have. The **cover** of a note is its picture with the lowest position. Reordering by the owner is not built; the column makes it possible later.
7. Adding a file whose `sha256` equals that of an attachment the same capture already has does nothing and returns the existing id (the same screenshot shared twice into one note).

### C. The file store (`security/AttachmentStore.kt`)

8. `AttachmentStore(dir: File)` where `dir` is `files/att/`. It knows nothing about the database or about pictures; it moves bytes.
   - `newKey(): ByteArray` (32 bytes, `SecureRandom`), `newFileName(): String`.
   - `write(fileName, key, attachmentId, plain: InputStream): Written(byteSize, sha256)`: writes to `<fileName>.tmp`, flushes and syncs, then renames to `<fileName>`. On any failure the `.tmp` is deleted and the exception is rethrown.
   - `open(fileName, key, attachmentId): InputStream`: a decrypting stream. Any failure (missing file, wrong key, changed byte, unknown version) throws `AttachmentUnreadable(reason code)`; the message carries no file content.
   - `delete(fileName): Boolean`, `list(): Set<String>`, `ageMs(fileName)`.
   - **File format, version 1:** 4 bytes ASCII `STSH`, 1 byte `0x01`, then the output of Tink's streaming AEAD: AES-256-GCM with HKDF-SHA-256, 4,096-byte ciphertext segments, the row's `file_key` as the key material. The associated data is the 5 header bytes followed by the attachment id as UTF-8, so a file renamed or copied onto another row does not open.
   - Library: `com.google.crypto.tink:tink-android`, newest stable (STORAGE.md saw 1.24.0 on 2026-11-11). It must be in `docs/LICENSES.md` before it is added (D78). Use `com.google.crypto.tink.subtle.AesGcmHkdfStreaming(ikm, "HmacSha256", 32, 4096, 0)` so the raw 32-byte key from the row is the key. **If that class or constructor does not exist in the pinned version, stop and say so in the hand-back. Do not write your own streaming format and do not switch to another library.**
   - Never create a temporary plain file. Callers pass and receive streams.

### D. The repository, the one way in, delete and the sweep

9. `AttachmentRepository(db, store, clock)` in `data/`, reached as `Repository.attachments`:
   - `prepare(...)` is **not** here (card 16b makes the bytes). This card defines the hand-over type in `capture/InboundSink.kt`: `InboundAttachment(id, kind, source, mime, fileName, key, byteSize, sha256, width?, height?, durationMs?, ocrText?, ocrEngine?)`. It describes a file that `AttachmentStore.write` has **already written**.
   - `Inbound.Capture` gains `attachments: List<InboundAttachment> = emptyList()`. `CaptureRepository.insertCapture` gains the same parameter and inserts the capture and its attachment rows (positions 0, 1, 2 … in list order) **in the one transaction it already opens**. A capture with empty `text` is allowed only when it has at least one attachment.
   - New `Inbound.AddAttachments(captureId, attachments)` for a picture added to an existing note. `DirectSink` handles it by calling `attachments.addTo(captureId, list)`: one transaction, rows appended, one `PICTURE_ADDED` revision per file (`old` empty, `new` = attachment id), the capture's `updated_at` stamped. If the capture does not exist or is soft-deleted, nothing is inserted and the result is `Failed`.
   - If a transaction fails, the sink returns `Failed` and **leaves the files alone**; the caller may try again with the same files, and the sweep removes them if it never does.
   - `remove(attachmentId)`: in one transaction delete the row, write a `tombstone` (`table_name = "attachment"`) and a `PICTURE_REMOVED` revision (`old` = attachment id); after the transaction commits, delete the file. This is a real delete, at once; there is no bin for a single picture (the screen in card 16d asks first).
   - `openBytes(attachmentId): InputStream` for screens and, later, the backup: reads the row, opens the file. Refuses with `AttachmentUnreadable` when the row is gone.
10. **Deleting a note.** `softDelete` and `restore` do not touch attachments (rows and files stay; they are hidden because every listing goes through live captures). `CaptureRepository.purge` gains, inside its transaction: read the capture's attachment file names, delete the rows, write one `attachment` tombstone per row. After the transaction commits, delete each file; a file that cannot be deleted is left for the sweep. Give `CaptureRepository` an optional `attachments: AttachmentStore? = null` constructor argument for this (the pipeline constructs a `CaptureRepository` that never purges and passes nothing). Update the comment on `purge` that says pictures "join this list when they exist".
11. `ConsistencyCheck`: `ConsistencySnapshot` gains `attachments` (id, capture id, file name) and `files: Set<String>`. New violations: `ATTACHMENT_WITHOUT_CAPTURE`, `ATTACHMENT_WITHOUT_FILE`, `FILE_WITHOUT_ATTACHMENT`. The check stays pure.
12. `AttachmentRepository.sweep(): Int`, called wherever `Repository.purgeDeleted` is called today: delete every file in `files/att/` that has no row **and** is older than 24 hours (a file younger than that may belong to a note still being made), and every `.tmp` older than 24 hours. A row with no file is **reported, never deleted**: its row still holds the words read from the picture.
13. Manifest: add `android:dataExtractionRules` pointing at an XML that excludes the `att/` folder of the `file` domain from both cloud backup and device transfer (`allowBackup="false"` stays). A transferred file would arrive without its key.
14. Events (`Dbg.event`, numbers only): add a `PICTURE` component with codes `STORED` (n1 bytes), `REMOVED`, `UNREADABLE` (n1 reason), `SWEPT` (n1 files).

### E. How audio will reuse this store (card 26; **not built here**)

15. Nothing below is built. It is written down so that this card does not close the door.
    - A voice note's recording becomes one attachment with `kind = AUDIO`, its `mime` whatever card 25 chooses, `duration_ms` filled, `width`/`height` null, and a new `AttachmentSource` value added by that card.
    - It uses the same `AttachmentStore.write`/`open`, the same key-per-file rule, the same `InboundAttachment` hand-over through the `InboundSink` (so a recording made on the lock screen arrives with its capture in one transaction), and therefore the same purge, tombstones, sweep, consistency check, backup exclusion and Private behaviour, with no new code in any of them.
    - The 4 KB segments are there for audio: Tink can open the same file as a seekable channel, so playback need not decrypt a long recording into memory. (Medium confidence that `newSeekableDecryptingChannel` suits Android's `MediaDataSource`; card 26 checks.)
    - Everything that lists **pictures** uses `picturesForCapture`, which leaves `AUDIO` out. The one unit test in this card that mentions audio proves that.

## Out of scope

Making picture bytes (16b). Any screen (16c, 16d, 16e). Reading words from pictures (16f). Recording, storing or playing audio (card 26). Backup and export of files (card 10, which will add one entry per attachment; it should export the row **without** `file_key` and give each restored file a new key). Wrapping the per-file keys with a Keystore key (they are in the database on purpose; card 14 covers the database).

## Acceptance checks

**Unit tests (JVM; Robolectric where Room is needed)**

- Store: bytes written with a known key read back identical for 0 bytes, 1 byte, 4,096 bytes, 300 KB and 3 MB; a flipped byte fails; a wrong key fails; the right key with another attachment id fails; a truncated file fails; an unknown version byte fails; every failure is `AttachmentUnreadable`.
- Store: a `write` whose input stream throws half-way leaves neither the file nor the `.tmp`.
- Migration: a database built from the committed previous schema JSON, seeded with three captures and their records, opens through Room at the new version with every capture and record unchanged and an empty `attachment` table (follow `MigrationTest`).
- Many per note: a capture inserted with three attachments has positions 0, 1, 2; `AddAttachments` with two more gives 3 and 4; removing position 1 leaves 0, 2, 3, 4 in order; the same `sha256` added twice gives one row.
- A capture with empty text and no attachment is refused; with one attachment it is saved.
- `remove`: the row is gone, the file is gone, one tombstone and one `PICTURE_REMOVED` revision exist, the other attachments of the capture are untouched.
- `purge` of a capture with five attachments: no rows, no files, five attachment tombstones and the capture tombstone. With a store whose `delete` fails once, the row is still gone and the next `sweep` (with the clock moved 25 hours) removes the file.
- `softDelete` then `restore`: rows and files unchanged throughout.
- Sweep: an orphan file 25 hours old is deleted; one 1 hour old is kept; an old `.tmp` is deleted; a row with no file is reported as `ATTACHMENT_WITHOUT_FILE` and still exists afterwards.
- A failed transaction in the sink returns `Failed` and the files are still on disk.
- A seeded `AUDIO` attachment is absent from `picturesForCapture`, present in `forCapture`, and removed by `purge`.
- The debug dump of a database with one attachment contains neither the key's hex nor its Base64 form.

**Real phone (S25 Edge); write each result in `docs/EXPERIMENT_LOG.md`, "Pictures and codes"**

- Before installing: `python -I tools/peek.py notes`. `./gradlew installDebug` over the existing app. After: the same notes, the database at the new version, nothing in the crash log.
- A debug-only self-test (a receiver in `app/src/debug`, like `DumpReceiver`) writes 1 MB of random bytes through `AttachmentRepository`, reads them back, compares, removes them, and reports pass or fail and the milliseconds for write and read. This is the proof that Tink's streaming AEAD runs on this phone. Then `adb shell run-as app.stash ls -l files/att` is empty.
- Give the APK size before and after adding Tink.

## Notes and risks

- **Built before card 14, the keys sit in an unencrypted database on a debug build**, so anyone who can read the app's files through USB debugging can read the pictures. That is the same exposure the notes have today (`docs/PRIVACY.md`, "The prototype today") and it disappears with card 14 without touching a file. Built before card 10, pictures have no backup; a lost phone loses them, as it loses the notes.
- Confidence: Tink streaming AEAD on Android, high (long-standing); the exact `subtle` constructor taking raw key bytes, medium to high (from memory, not re-read): the JVM round-trip test settles it before anything reaches the phone. That `allowBackup="false"` alone does not stop device-to-device transfer on current Android, medium: the exclusion file costs nothing either way.
- `AttachmentEntity` holds a `ByteArray`; a Kotlin data class compares arrays by reference. Do not rely on `==` between two entities in tests; compare fields.
- Do not add a foreign key with `ON DELETE CASCADE`: the delete order in item 10 is deliberate, and the other tables use none.

**Suggested builder: Opus.** It adds a migration, an encrypted file format and the delete path, where a mistake loses or exposes the owner's pictures and cannot be fixed afterwards.

## What was built, and what differs from the card (2026-11-12)

Built (under `app/src/main/java/app/stash/` unless noted):
- `data/Enums.kt`: `AttachmentKind { PHOTO, SCREENSHOT, AUDIO }` (`AUDIO` declared, used by nothing), `AttachmentSource { SHARE, CAMERA, PICKER, RECENT }`, `RevisionKind.PICTURE_ADDED` and `PICTURE_REMOVED`.
- `data/Entities.kt`: `AttachmentEntity` (table `attachment`, index on `capture_id`, every column in item 2 including nullable `duration_ms`; `Stamped`). `data/Daos.kt`: `AttachmentDao` (all of item 4) and the read models `AttachmentInfo` (no key, no file name) and `AttachmentRef` (for the check).
- `data/Migrations.kt`: `V2_TO_V3` / `MIGRATION_2_3`, one `CREATE TABLE` and one `CREATE INDEX` copied from the exported `app/schemas/app.stash.data.StashDatabase/3.json` (a test checks they are identical); `ALL` is now 1→2, 2→3. `StashDatabase` is at version 3. `2.json` is unchanged and every other table in `3.json` is identical to it.
- `security/AttachmentStore.kt`: `newKey`, `newFileName`, `write` (`.tmp`, flush, `fd.sync()`, rename; `.tmp` deleted on any failure), `open` (decrypting stream), `delete`, `list`, `ageMs`; `AttachmentUnreadable(reason)`. Format version 1 exactly as item 8: `STSH`, `0x01`, then `AesGcmHkdfStreaming(key, "HmacSha256", 32, 4096, 0)`, associated data = the 5 header bytes + the attachment id.
- `data/AttachmentRepository.kt` (`Repository.attachments`): `forCapture`, `pictures`, `cover` (all `AttachmentInfo`), `addTo`, `remove`, `openBytes`, `setOcr`, `sweep`. `capture/InboundSink.kt`: `InboundAttachment`, `Inbound.Capture.attachments`, `Inbound.AddAttachments`, handled by `DirectSink`. `CaptureRepository.insertCapture(..., attachments)` writes the rows in its one transaction; `purge` deletes rows and writes one tombstone each inside its transaction, then deletes the files.
- `data/ConsistencyCheck.kt`: `ATTACHMENT_WITHOUT_CAPTURE`, `ATTACHMENT_WITHOUT_FILE`, `FILE_WITHOUT_ATTACHMENT`; the check stays pure.
- `domain/Dbg.kt`: component `PICTURE`, codes `STORED` (n1 bytes), `REMOVED`, `UNREADABLE` (n1 reason code), `SWEPT` (n1 files). The row id goes in `refId`; nothing else.
- `res/xml/data_extraction_rules.xml` and `android:dataExtractionRules` in the manifest: `file` domain `att/` excluded from cloud backup and device transfer. `allowBackup="false"` stays.
- Debug only (`app/src/debug/`): `DumpReceiver` prints an `attachment` row as `id, kind, position, byte_size` and nothing else (its query moved into `DumpReceiver.dump()` so a test can run it). New `AttachmentSelfTestReceiver` for the phone check (below).
- Library: `com.google.crypto.tink:tink-android:1.24.0` (`gradle/libs.versions.toml`), the newest on Maven Central on 2026-11-12 (metadata last updated 2026-10-16) and the version STORAGE.md named. **The card's API recollection is right:** `javap` on the jar from Maven Central (sha1 `c2ca30c9d3654a37a3966dff064c7bf063e81f9a`) shows `public AesGcmHkdfStreaming(byte[], String, int, int, int)`, and the JVM round trip passes. Its POM names "Apache License, Version 2.0". Protobuf is shaded inside the jar (no separate protobuf-javalite); it pulls in `androidx.annotation:annotation-jvm` 1.8.2, `com.google.code.findbugs:jsr305` 3.0.2, `com.google.code.gson:gson` 2.13.2 and `com.google.errorprone:error_prone_annotations` 2.41.0, whose POMs all name Apache 2.0. These are POM statements, not the licence texts: `docs/LICENSES.md` keeps Tink as an unverified lead.
- Tests: `AttachmentStoreTest` (11, plain JVM), `AttachmentTest` (15, Robolectric, real Room and real files), two new tests in `MigrationTest` (a real version-2 file built from `2.json` with three captures and their records opens at version 3 with both tables byte-for-byte unchanged and `attachment` empty; the migration SQL equals `3.json`), one in `ConsistencyCheckTest`. Every acceptance check in the card's unit-test list has a test.

Differs from the card, and why:
- **The store has two helpers the card does not name.** `list()` returns finished files only; `temps()` and `deleteTemp()` handle `.tmp` leftovers, so the check never reports a write in progress as a stray file. `ageMs(fileName, now)` takes the time from the repository's clock, so the 25-hour sweep test needs no waiting. The store also refuses to replace an existing file and refuses file names that are not plain `[A-Za-z0-9-]` (no path can escape `files/att/`).
- **`open` reads the first byte at once.** Tink's stream finds a wrong key only when it decrypts; reading one byte (and pushing it back) makes a wrong key, a damaged first segment or a header-only file fail inside `open`. A failure further in is thrown from `read`, also as `AttachmentUnreadable`. Reasons: `MISSING` 1, `NOT_A_STASH_FILE` 2, `UNKNOWN_VERSION` 3, `CORRUPT` 4 (wrong key, changed byte, other attachment id, truncated), `NO_ROW` 5, `BAD_KEY` 6 (not 32 bytes).
- **`remove` also stamps the capture's `updated_at`**, because the note changed (STORAGE.md rule 6). `addTo` does the same, as the card says.
- **A duplicate's file is left for the sweep**, not deleted at once: if a retry hands over the very file a row already uses, deleting "the duplicate" would delete the row's file. The sweep removes a true duplicate after 24 hours.
- **"Empty text" means the empty string.** `insertCapture` refuses `text == ""` with no attachment (`IllegalArgumentException`; the sink turns it into `Failed`). Both callers already trim and skip blank text.
- **`DirectSink` gains an optional `attachments: AttachmentRepository`** for `AddAttachments`; without it that item is `Failed`. `AddAttachments` answers `Saved(capture)` with the stamped capture.
- **The sweep runs where purging really happens.** Nothing in the app calls `Repository.purgeDeleted` today; the daily purge is `TaskUpkeep.run` (card 05's worker). Both now sweep: `TaskUpkeep.run` takes an optional store (the worker passes `Container.attachmentStore`) and gives it to its `CaptureRepository` so purge deletes files; `Repository.purgeDeleted` also sweeps. The extraction pipeline's `CaptureRepository` gets no store, as the card says.
- **`Repository` and `Consistency` take the store** (`Repository(..., attachmentStore = ...)`, defaulting to `filesDir/att` when there is a context). `ConsistencySnapshot`'s two new fields default to empty, so older callers compile unchanged.
- **The phone self-test does not touch the owner's notes.** It writes 1 MB through the real store into the real `files/att/` and through `AttachmentRepository`, but the rows go into a throwaway in-memory database, so no capture is ever added to his notes. It writes `files/debug/attachment-selftest.json` (`pass`, `writeMs`, `readMs`). The same function runs in a JVM test.
- **APK size (debug, unminified, measured on the PC):** 72,116,326 bytes for the APK built at 18:14 before this work, 74,507,889 after: **+2.39 MB**, almost all dex (Tink, Gson and this card's own code; the card 25 spike libraries are in both). A release build with R8 will be smaller; not measured.

Unverified / still to do (the owner; nothing was installed):
- The migration on the real phone (version 2 to 3), the self-test (proof that Tink runs on the S25 Edge) and `files/att` empty afterwards. Steps are in `docs/EXPERIMENT_LOG.md`, "Card 16a".
- That `dataExtractionRules` (with `allowBackup="false"`) really keeps `files/att/` out of a Samsung Smart Switch transfer: not testable here.
