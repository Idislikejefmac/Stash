# 08 · Encrypted database

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

A Room database encrypted with SQLCipher, its key protected by the Android Keystore, with the version-1 schema, a full-text index, and migration tests in place from the start.

## Depends on

07.

## Context

All notes live in one SQLite database in app-private storage, encrypted with SQLCipher (AES-256). The database key is 32 random bytes generated on first run. It is never stored in the clear: it is encrypted with an AES-256-GCM key that lives in the Android Keystore (hardware-backed, non-exportable; StrongBox when the phone has it) and the ciphertext is kept in a small file. The Keystore key must **not** require user authentication or an unlocked device, because notes are saved while the phone is locked. (A later card, 30, adds a locked-phone "inbox" so that this can be tightened.)

Library: `net.zetetic:sqlcipher-android` with `androidx.sqlite`; Room uses it through `SupportOpenHelperFactory`. SQLCipher's build includes FTS5.

### Schema v1

IDs are UUID strings. Times are UTC epoch milliseconds. Enums are stored as strings.

- `note`: `id` PK, `created_at`, `zone`, `raw_text`, `text`, `kind` (`PLACED`,`LOAN`,`ERRAND`,`REMINDER`,`FACT`,`UNSORTED`), `status` (`ACTIVE`,`SUPERSEDED`,`DONE`,`EXPIRED`), `local_only` bool, `captured_locked` bool, `needs_review` bool, `lat` real?, `lon` real?, `accuracy_m` real?, `fix_age_s` int?, `captured_place_id`?, `structure_source` (`NONE`,`RULES`,`MODEL`,`USER`), `structure_json`?, `structure_confidence` real?, `stt_engine`?, `expires_at`?, `done_at`?, `superseded_by`?, `deleted_at`?
- `item`: `id`, `name`, `aliases` (JSON array text), `volatility` (`DAILY`,`NORMAL`,`STORED`)
- `placement`: `id`, `item_id` FK, `note_id` FK, `where_text`, `place_id`?, `copy_label`?, `at`
- `person`: `id`, `name`, `aliases`
- `loan`: `id`, `note_id` FK, `item_id` FK, `person_id` FK, `direction` (`LENT`,`BORROWED`), `on_date`?, `due_date`?, `returned_at`?, `last_nudge_at`?
- `place`: `id`, `name`, `aliases`
- `place_location`: `id`, `place_id` FK, `lat`, `lon`, `radius_m`, `source` (`HERE`,`SEARCH`,`LEARNED`)
- `reminder`: `id`, `note_id` FK, `text`, `trigger` (`PLACE`,`TIME`,`NONE`), `place_id`?, `due_at`?, `expires_at`?, `state` (`ACTIVE`,`SNOOZED`,`DONE`,`EXPIRED`), `last_notified_at`?, `notify_count`
- `note_revision`: `id`, `note_id` FK, `at`, `old_text`, `old_structure_json`?, `reason`
- `pending_audio`: `id`, `note_id` FK, `file`, `created_at`, `attempts`
- `note_fts`: FTS5 virtual table with columns `note_id` (unindexed), `body`; tokenizer `porter unicode61`. `body` is the note text plus item name and aliases, person name, place name and where-text, joined by spaces. It is maintained by the repository in the same transaction as the tables it mirrors (not by triggers, because it draws from several tables).

Indexes on `note(status, kind)`, `note(created_at)`, `placement(item_id, at)`, `loan(person_id)`, `reminder(state, trigger)`.

## Scope

1. `security/DatabaseKeyStore`: create-or-load the wrapped database key; prefers StrongBox, falls back to the TEE; logs (through `Dbg`) which was used. If unwrapping fails because the Keystore key is gone, surface a clear `DatabaseKeyLost` error; do not silently create a new database over the old file.
2. Room database `StashDatabase` version 1 with the entities above, DAOs with the obvious queries, `exportSchema = true` with schemas checked into `app/schemas/`.
3. `data/NoteRepository` with: `insertCapturedNote(...)` (creates an `UNSORTED`, `ACTIVE` note and its FTS row), `updateText(noteId, newText, reason)` (writes a `note_revision`), `softDelete`, `restore`, `purgeDeletedOlderThan(days)`, `rebuildFts()`, observers as `Flow`.
4. Migration harness: `MigrationTestHelper` test that creates v1 and opens it; a documented pattern (`docs` comment in the test) for adding `Migration(n, n+1)` plus a test for each future change.
5. A debug-only screen or `adb`-callable broadcast that inserts five sample notes, so later cards have data.

## Out of scope

Structuring, supersession and expiry logic (cards 09–11), backup (25), the app lock (17). Do not add columns "for later".

## Acceptance checks

- **Unit (JVM, Robolectric with an in-memory or temp-file SQLCipher database, or instrumented if SQLCipher's native library cannot load under Robolectric; say which):** insert → query round-trip for every entity; `updateText` writes a revision; soft delete hides from default queries; `rebuildFts` reproduces the same search results; FTS query `glasses` finds a note whose text says "reading glasses" and one whose item alias is "glasses".
- **Unit:** key wrapping round-trip with a fake Keystore; wrong key fails to open.
- **Real phone, S25 Edge:** after inserting sample notes, pull the database file with `adb` (debug build, `run-as`) and confirm `sqlite3` cannot read it ("file is not a database"). Log the Keystore security level reported (StrongBox or TEE) in `docs/EXPERIMENT_LOG.md`.
- **Real phone:** lock the phone, trigger the debug insert by `adb shell am broadcast`, unlock: the note is there (proves the key works while locked).

## Notes and risks

- SQLCipher loads a native library; make sure `System.loadLibrary("sqlcipher")` happens once before Room opens.
- Keep the passphrase as a `ByteArray` and zero it after handing it to the factory.
- Room's auto-generated FTS support targets FTS4; declare the FTS5 table with a raw `CREATE VIRTUAL TABLE` in a database callback and access it with `@RawQuery`/`SupportSQLiteQuery`. Include it in the migration tests by hand.
