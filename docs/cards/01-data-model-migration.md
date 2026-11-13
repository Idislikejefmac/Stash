# 01 · New data model and migration from the prototype

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

**Status: done 2026-11-11** (built and tested; migration verified on the phone with the owner's 4 notes; a voice capture after the upgrade is still to be confirmed by the owner). Results are in `docs/EXPERIMENT_LOG.md`.

## Goal

Replace the prototype's single `note` table with the two-layer model (captures, extractions, records and their side tables), **without losing any of the owner's existing notes**, and keep the app working exactly as it does today on top of the new tables.

## Depends on

None. This is the first card.

## Context

The prototype (`app/src/main/java/app/stash/data/Entities.kt`) has Room database `StashDatabase` version 1 with `exportSchema = false` and three tables: `note`, `vocab_word`, `correction`. The owner's phone holds real notes in it. `data/Container.kt` has `Repository.saveNote`, which corrects the text, structures it (hosted model if a key is set, else rules), finds an older note about the same item to supersede, and inserts one `note` row. `domain/Search.kt` searches a list of `NoteView` in memory. `ui/` shows notes and answers. `tools/peek.py` reads the database file over USB with `adb run-as`.

The new model is in 00-READ-FIRST ("The data model in one table"). The point of the two layers: the capture is what he said and is never rewritten; records are what the app made of it and can be rebuilt.

Revision 2 added the **foundations** in scope items 0 and 8–11. They are the things `docs/STORAGE.md` lists as cheap now and expensive later; they add no feature.

This card changes storage only. It does **not** change how notes are understood: after this card the prototype's structurers still run in the save path and still produce one record per capture. Cards 03 and 04 change that.

### Mapping from the old table

| Old `note` | New |
| --- | --- |
| each row | one `capture`: same `id`, `created_at`, `raw_text`, `text`, `lat`, `lon`, `sensitive`; `zone` NULL; `source = 'VOICE'`; `captured_locked` NULL; `extract_state = 'PENDING'`; `extract_attempts = 0`. The old column `localOnly` becomes `sensitive` (revision 3 unified the two ideas; nothing ever set the old flag, so every migrated row is 0) |
| the structured fields | one `extraction`: `id = <note id>-x0`, `extractor = 'prototype-rules'` or `'prototype-model'` (from `structureSource`; `NONE` → `prototype-rules`), `schema_version = 0`, `text_hash = ''`, `output_json` = a JSON object built in SQL from `kind`, `item`, `place`, `person`, `eventDate`, `status = 'CURRENT'` |
| | one `record`: `id = <note id>-r0`, `seq = 0`, `title` = the note text cut to 80 characters, `quote` = the note text, `confidence = 'MEDIUM'`, `pinned = 0`, `aliases = '[]'`, `tags = '[]'` |
| `kind = STORED_ITEM` | `record.kind = 'PLACED'` + `whereabouts` (`item_text` = `item`, `where_text` = `place`, `direction = 'PLACED'`) |
| `kind = LOAN` | `record.kind = 'LOAN'` + `whereabouts` (`item_text`, `person_text` = `person`, `said_date_text` = `eventDate`, `direction = 'LENT'`) |
| `kind = REMINDER` | `record.kind = 'TASK'` + `task` (`state = 'OPEN'`, `due_kind = 'NONE'`, `at_place_text` = `place`, `perishable = 0`, `nudge_count = 0`, `ignored_in_a_row = 0`, `quiet = 0`, `last_active_at` = `created_at`) |
| `kind = OTHER` or anything else | `record.kind = 'OTHER'` |
| `supersedesId = X` | `record.supersedes_record_id = 'X-r0'` |
| `vocab_word`, `correction` | untouched |

`whereabouts.item_text` must not be NULL: use `COALESCE(item, text)`.

## Scope

0. **A signing key that does not change.** Today the app is signed with whatever debug key Android Studio made on this PC (`app/build.gradle.kts` has no signing configuration). A build from another PC, or after that key is lost, cannot be installed over the app; it must be uninstalled first, and an uninstall destroys the notes. Before anything else: create one keystore for this project, kept outside the repository (the `.gitignore` already excludes `*.jks`; put its path and passwords in `keystore.properties`, also ignored), and sign the `debug` build type with it. **The first install signed with the new key needs an uninstall**, so first save the notes: `python tools/peek.py notes 1000 > notes-before.txt` and copy the database file off the phone with `adb exec-out run-as app.stash cat databases/stash.db > stash-v1.db`; after reinstalling, push the file back with `run-as` before first launch, or accept re-entering the few test notes (ask the owner which). Tell the owner to copy the keystore and its passwords somewhere safe.
1. **Freeze version 1.** Set `exportSchema = true`, configure the Room schema directory (`app/schemas/`), build, and keep the generated `1.json`. Do this before changing any entity.
2. **Entities and DAOs** for every table in 00-READ-FIRST, database version 2. **Version 2 holds the core tables only**: `capture`, `extraction`, `record`, `task`, `reading`, `metric`, `whereabouts`, `subject`, `place_location`, `nudge_log`, `revision`, `miss_log`, `tombstone`, `app_event`, `vocab_word`, `correction`, `search_fts`. Feature tables (`answer_trace`, `attachment`, `trip`, `checklist_item`, `watch`) are added by their own cards, each with a migration and a migration test. Indexes: `capture(created_at)`, `capture(extract_state)`, `record(capture_id)`, `record(kind)`, `record(subject_id)`, `task(state)`, `reading(metric_id, measured_at)`, `whereabouts(item_subject_id)`, `subject(norm_name)`. `search_fts` is a Room `@Fts4` entity (tokenizer porter) with columns `doc_id`, `doc_type` (`RECORD` or `CAPTURE`) and `body`; it is maintained by the repository, not by triggers.
3. **`Migration(1, 2)`**, hand-written SQL, following the mapping above, then `DROP TABLE note`. All inside the migration's transaction.
4. **Safety copy.** Before Room opens the database, if the file is at version 1 (`PRAGMA user_version`) and `stash-v1-backup.db` does not exist in the app's files directory, checkpoint and copy `stash.db` there. Never overwrite an existing copy. (Card 14 offers its removal.)
5. **Repositories** in `data/`:
   - `CaptureRepository`: `insertCapture(...)`, `updateText(captureId, newText)` (writes a `revision`, sets `extract_state = PENDING`), `softDelete`, `restore`, `purgeDeletedOlderThan(days)`, observers as `Flow`.
   - `RecordRepository`: `replaceRecordsFor(captureId, extraction, records)` in **one transaction**: marks the previous `CURRENT` extraction `REPLACED`, deletes the capture's **unpinned** records and their side rows, inserts the new ones, updates `search_fts`. If any record of the capture is pinned, unpinned ones are still replaced and pinned ones are left untouched.
   - `SearchIndex`: `rebuild()` and `search(query): List<Hit>`; document body for a record = title, quote, aliases, tags, subject name, metric labels; for a capture = its text.
   - `ConsistencyCheck.run(): List<Violation>` (pure logic over DAO results): every record has a live capture and extraction; every `TASK` record has a `task` row; every `PLACED`/`LOAN` record has a `whereabouts` row; every `reading` has a record; every live record and capture has an FTS document. `repair()` rebuilds the index and reports what it cannot fix.
6. **Keep the app working.** Rewrite `Repository.saveNote` to: insert the capture; run the existing structurer exactly as today; write one extraction and one record (plus `whereabouts` or `task`) through `replaceRecordsFor`; keep the supersession rule (same item key → `supersedes_record_id`). Mark the capture `DONE`. Map the prototype's `NoteKind` to the new kinds (`STORED_ITEM`→`PLACED`, `REMINDER`→`TASK`) and add the four new enum values. Adapt `NoteView`, `Searcher`, `AnswerComposer`, the Notes list, delete, "correct a word" and export to read from the new tables. The Notes list shows captures, each with its records beneath.
7. **Debug dump for the developer.** In the `debug` source set only (`app/src/debug/`): a broadcast receiver `app.stash.debug.DumpReceiver` that writes `files/debug/dump.json` (every table except `search_fts`, newest first, optional `limit` extra). Extend `tools/peek.py` with `dump [N]`: sends the broadcast with `adb shell am broadcast`, then reads the file with `run-as` and prints captures with their records. Keep the existing `notes` command working by pointing it at the `capture` table.

8. **Timestamps for merging.** Every table whose rows can change after insert (`capture`, `record`, `task`, `reading`, `metric`, `whereabouts`, `subject`, `place_location`) has `created_at` and `updated_at`, set by the repositories on every write (one helper; tested). Nothing reads `updated_at` yet; backup merge (card 10) and any later sync depend on it.
9. **Deleting, defined once** (`CaptureRepository.purge(captureId)` and `purgeDeletedOlderThan(30)`, run by a daily worker; until card 05 adds that worker, run it at app start): a real delete of a capture removes, in one transaction, its `extraction` rows (they hold the model's copy of the words), its records and their side rows, its `revision` rows, its search documents, and (from card 16) its picture files; then writes one `tombstone` row (`id`, `table_name`, `row_id`, `deleted_at`). A tombstone holds no content. Import and restore (card 10) apply tombstones, so a deleted note does not come back from an older backup. Subjects left with no records are removed.
10. **The owner's actions are recorded.** `revision` is `id`, `at`, `capture_id`?, `record_id`?, `subject_id`?, `what` (an enum name: `TEXT_CORRECTED`, `KIND_CHANGED`, `TASK_DONE`, `TASK_REOPENED`, `SNOOZED`, `DROPPED`, `MERGED`, `PINNED`, `EDITED`, `MADE_PRIVATE`, …), `old_value`, `new_value`. Every repository method that carries out something the owner did writes one in the same transaction. Captures plus revisions are everything that cannot be rebuilt.
11. **The event log table** `app_event`: `id`, `at`, `component`, `code`, `n1`?, `n2`?, `ref_id`?, `ms`?. No text column. (Card 13 adds the `Dbg.event` function that writes it and the test that forbids other logging.) Also `capture.app_version` (the build that made it) and `record.place_subject_id`?, `record.place_text`? (where something happened; filled from card 03's `place`), `record.spot_text`? (where within the place: "row 5, bucket 10"), and `capture.photo_hint` (`NONE`, `PENDING`, `DISMISSED`; default `NONE`; used by card 16).

## Out of scope

Encryption (14). Changing extraction, splitting, the sensitive check, or moving structuring out of the save path (03, 13). Subjects being created or resolved (03): `subject_id` columns stay NULL. Tasks UI (05). Readings (07). Do not add columns that are not in 00-READ-FIRST.

## Acceptance checks

- **Unit (Robolectric with Room's `MigrationTestHelper`, or instrumented if it will not run under Robolectric; say which):** create a version-1 database containing: a `STORED_ITEM` note; a second `STORED_ITEM` note that supersedes the first; a `LOAN` with person and date; a `REMINDER` with a place; an `OTHER` note whose text is "I am updating the app for stash right now"; a note with location; a `localOnly` note; two words and one correction. Migrate. Assert: capture count equals old note count; every `raw_text`, `text`, `created_at`, `lat`, `lon`, `sensitive` is identical; each capture has exactly one record of the mapped kind; the supersession link points at the right record; `whereabouts` and `task` rows carry the old fields; words and corrections are unchanged; all captures are `PENDING`.
- **Unit:** `replaceRecordsFor` leaves a pinned record in place and replaces unpinned ones; a simulated failure halfway (a DAO fake that throws) leaves the old records intact.
- **Unit:** `ConsistencyCheck` finds each kind of violation in a hand-built broken state, and `repair()` restores the index.
- **Unit:** search finds a capture by a word that is only in its text and a record by a word that is only in its aliases; a deleted capture is not found.
- **Unit:** a write through each repository sets `updated_at`; an owner action writes a `revision` with old and new values.
- **Unit:** purge of a capture leaves no row in any table containing its text (search every text column for a marker word) and leaves one tombstone; subjects with no remaining records are gone.
- **Unit:** the prototype's existing tests (corrections, structuring, search and answers) pass, adapted to the new types.
- **Developer PC:** the debug APK is signed by the project keystore (`apksigner verify --print-certs`), and a second build on a clean checkout with the same keystore installs over the first without uninstalling.
- **Real phone, S25 Edge:** before installing, run `python tools/peek.py notes 100` and save the output. Install over the existing app (do **not** uninstall). Open it: every old note is in the Notes list with the same text and date. `python tools/peek.py dump 100` shows the same count. `stash-v1-backup.db` exists (`adb shell run-as app.stash ls files`). Add a new note by voice; ask "where did I put …" about an old one: both work as before. Write the counts in `docs/EXPERIMENT_LOG.md`.

## Notes and risks

- The owner's notes are irreplaceable test data. If the migration test and the phone disagree, stop and restore `stash-v1-backup.db`; do not "fix forward" on his data.
- Building JSON in SQLite: `json_object(...)` is available in the SQLite versions Android 15+ ships; if the migration test shows otherwise, build `output_json` by string concatenation with `replace()` for quotes.
- Room's `@Fts4` entity needs an integer `rowid`; keep `doc_id` as a normal column.
- `fallbackToDestructiveMigration` must not be anywhere in the code.
- IDs are opaque strings. New rows get UUIDs; migrated rows keep ids like `<note id>-r0`. No code may parse an id or assume its form.
- The old `note` table also has `placeName` and `expiresAt`. The prototype never writes either (code read 2026-11-11), so the migration drops them; the migration test asserts they are null in the seeded version-1 database to document the assumption.
- The debug receiver must not exist in a release build; assert it with a unit test that looks for the class under `src/main`.

## What was built, and what differs from the card (2026-11-11)

Built: version-2 schema (all core tables, indices, `search_fts` as FTS4 with the porter tokenizer), schema export on with `app/schemas/app.stash.data.StashDatabase/1.json` (generated from the unchanged prototype; its hash matches the phone's) and `2.json`; hand-written `Migrations.MIGRATION_1_2` (DDL copied from `2.json`); `SafetyCopy`; `CaptureRepository` (insert, `updateText`, `setSensitive`, soft delete, restore, `purge`, `purgeDeletedOlderThan`, observers), `RecordRepository` (`replaceRecordsFor` in one transaction, `pin`, `rewriteTexts`), `SearchIndex`, `ConsistencyCheck` (pure) with `Consistency.repair()`, `Clock`/`Ids`/`Stamped` foundations (`updated_at` on every write, UUIDs for new rows), `RevisionWriter`, `app_event` table, `NoteViews` (flat views for the unchanged `Searcher` and answers), the debug-only `DumpReceiver`, `tools/peek.py` `notes`/`dump`/`rules`. The Notes list shows captures with their records beneath.

Differs from the card:
- **Scope item 0 (signing key) skipped, by owner decision (Q38).** The owner deferred it because he is on a work PC. The app is still signed with this PC's automatic debug keystore (copy in `C:\jeffsource\StashBackup`), so the card's risk stays open: a build from another PC cannot be installed over this one without destroying the notes. Do it before moving development to another machine. The acceptance check "debug APK signed by the project keystore" is not met for the same reason.
- **Encryption not added (Q37):** plain Room, as the card says.
- Tests use **Robolectric 4.16.1** (test-only, new dependency) with real Room and SQLite, not `MigrationTestHelper`: that needs instrumentation, and running instrumented tests installs a test build and uninstalls the app, which would wipe the notes. The version-1 database is built from the committed `1.json`.
- "A DAO fake that throws" is replaced by a real duplicate-id insert halfway through `replaceRecordsFor`, which rolls the transaction back (the old records and index stay intact).
- The migration carries `place`, `person` and `eventDate` into `whereabouts` for both stored items and loans (the card names a subset); nothing is lost. A reminder's item has no column in `task`, so views read it from the extraction's `output_json` (schema_version 0 only). Correcting a word in a reminder therefore does not fix its item in that JSON (small known gap; card 03 replaces this).
- `saveNote` now inserts the capture before structuring (the card says leave structuring as is; the order change costs nothing). The "Saved" cue still waits for structuring, as before: card 03/13.
- The Notes screen Delete is now a soft delete (hidden at once, really removed after 30 days by `purgeDeleted()` at app start). There is no screen to restore yet. The UI search and answers still use the in-memory `Searcher`; `SearchIndex` is maintained but not yet used by screens.
- `NoteKind` is kept; a new `RecordKind` (all eight kinds) maps to and from it.
- Added `MADE_PUBLIC`, `DELETED` and `RESTORED` to the revision enum. `captured_locked` is filled from the keyguard state for new voice captures.
- Not enforced: SQLite free pages may still hold deleted text on disk until reused (no `secure_delete`); the index is merged (`optimize`) after a purge. Card 14's encryption covers the file.
- Room's `fallbackToDestructiveMigration` is absent (a test checks).
- Tests do not run the real `Container`; `Container.store()` does not yet return "not available" (card 13).
