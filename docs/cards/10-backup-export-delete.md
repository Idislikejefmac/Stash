# 10 · Encrypted backup and restore, export in open formats, delete everything

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

A lost or replaced phone does not mean lost notes; the owner can take his data out in formats any tool can read; and "delete everything" really does.

## Depends on

01. Best done after 07 so that every table has real content. **It comes before encryption (card 14) and before the switch to a release build**: both are moments at which a mistake loses the notes, and this card is the safety net.

## Context

Everything lives in one database on the phone (encrypted from card 14 on, with a key that cannot leave the phone). Android's own backup is switched off (`allowBackup="false"`) and must stay off: it would copy a database the new phone could not open, into Google's cloud. So the app makes its own backup.

Design, kept from the first version of the project:

- The backup is a **JSON export inside an encrypted container**, not a copy of the database file. Restoring is an import, so a backup from an older version restores into a newer app and onto a phone with a different database key.
- The container is encrypted **in the app** with a random 256-bit backup key. The key is shown once as a **recovery code** to print or put in a password manager. Whoever has the file and the code can read everything; without the code nobody can, including the owner.
- The file is written through Android's Storage Access Framework to a folder he picks (a cloud-drive folder, or a local folder that a sync app copies elsewhere). The app needs no network code for this.

Library: Tink (`com.google.crypto.tink:tink-android`) for streaming authenticated encryption (AES-256-GCM-HKDF, 1 MB segments). Do not hand-roll cryptography.

`docs/PRIVACY.md` describes this in plain words; keep the in-app wording consistent with it.

**Build it in two passes.** Pass 1: the snapshot, the encrypted backup with a recovery code, "Back up now", restore, JSON export, delete everything. Pass 2: the scheduled backup, QR code, test restore, Markdown and CSV, the in-app privacy page.

## Scope

### A. Export model (`backup/ExportModel.kt`, pure)

1. `Snapshot`: schema version, app version, created-at, and every table **except** `search_fts`, `nudge_log` and `miss_log`: `capture`, `extraction`, `record`, `task`, `reading`, `metric`, `whereabouts`, `subject`, `place_location`, `revision`, `tombstone`, `vocab_word`, `correction`, and whichever feature tables exist when this is built or are added later (`attachment`, `trip`, `trip_stop`, `checklist_item`, `watch`, `watch_seen`; not `answer_trace`, `app_event` or `watch_run`, which are history, not memory). The list is driven by one registry so a new table cannot be forgotten: a unit test fails if a Room entity is in neither the "backed up" nor the "deliberately not" list. Plus settings (not the API key, not the database key, not the backup key).
2. `toJson` / `fromJson` with a `format` number. `import(snapshot)`: upsert by id inside one transaction, then rebuild the search index and run the consistency check. Importing into a non-empty database **merges**: a row with the same id is replaced only if the snapshot's `updated_at` is newer; tombstones in either side win over rows, so a note deleted on the phone does not return from an older backup, and a note deleted before the backup stays deleted. Older `format` numbers are upgraded in code before import, with a test per step.

### B. Backup (`backup/`)

3. Set-up flow (behind the app gate): explain in three sentences; generate the backup key; show the recovery code as grouped characters (base32, groups of four) and as a QR code; require typing back the last group; pick a folder (`ACTION_OPEN_DOCUMENT_TREE`, persist the permission). The key is stored wrapped by a Keystore key like the database key.
4. `BackupWriter`: zip (entry `stash.json`, plus from card 16 one entry per picture, `att/<attachment id>`, holding the decrypted picture bytes; the container encrypts them again) → Tink streaming AEAD → file `stash-YYYYMMDD-HHMM.stashbak` with a small plain header (magic, format version). Keep the newest 5; delete older ones in that folder that match the name pattern.
5. `BackupWorker` (WorkManager): weekly, and after every 25 new captures; also "Back up now". Records the last success and last failure for Settings. A failure three times running shows a line on the main screen ("Backups aren't working: …").
6. `Restore`: from Settings or the first-run screen of a fresh install: pick a file, enter or scan the recovery code, decrypt, show what it contains (dates, counts), import. A wrong code fails cleanly with a plain message. After a restore on a new phone, the entered code becomes this phone's backup key.
7. "Show my recovery code" (behind the gate, with a fresh biometric prompt regardless of grace period). "Make a new code" re-keys future backups and says old files still need the old code.
8. **Test restore:** a button that decrypts the newest backup with the stored key and reports the counts without importing, so he can know backups work.

### C. Export (behind the gate, through the system share sheet or "save to")

9. **Everything, as JSON:** the snapshot, unencrypted, with a warning that the file is readable by anyone who gets it.
10. **Notes, as Markdown:** one file, newest first: date and time, the words as corrected, then each record as a bullet (kind, title, key fields), and for readings a small table. Sensitive notes are included and marked.
11. **Readings, as CSV** (card 07's export, for all subjects). **Tasks, as CSV** (title, state, due words, due dates, created, closed).
12. Replace the prototype's "Export notes as text" with these.

### D. Delete everything

13. Settings → "Delete everything": explains what goes (all notes, the word list, settings, the API key, the backup key) and what does not (backup files in his folder, which he must delete himself; text already sent to the hosted model, which the provider deletes on its own schedule: point to the privacy page). Requires a fresh biometric prompt and typing DELETE.
14. Closes the database, deletes the database files and any leftover plain copies, the wrapped keys, the Keystore entries, preferences, picture files, saved diagnostic token tables, the debug dump folder, and cancels scheduled work and alarms. The app restarts as a fresh install.

### E. In-app privacy page

15. Settings → "What is stored and what is sent": a short screen in the words of `docs/PRIVACY.md` "The short version", plus live facts: hosted model on or off; number of notes marked private; this month's requests and estimated cost; last backup; whether old unencrypted copies still exist.

## Out of scope

Live sync between devices. Uploading to a server or to Home Assistant directly. Sharing with other people. The sealed inbox.

## Acceptance checks

- **Unit:** snapshot round-trip for a database with every table populated: export, wipe, import → identical rows (compare table by table), search works, consistency check is clean.
- **Unit:** merge import: the same id is not duplicated; the newer `updated_at` wins; a tombstoned row is not restored; a backup made before a table existed imports cleanly.
- **Unit:** the registry test (every entity is listed one way or the other).
- **Unit:** container round-trip with a known key; one flipped byte fails authentication; a wrong key fails; the header is recognised and an unrelated file is rejected.
- **Unit:** recovery-code encode and decode, including with spaces, lower case and a look-alike substitution (0/O, 1/I) handled or rejected plainly.
- **Unit:** retention keeps exactly the newest 5.
- **Unit:** Markdown and CSV exports for a seeded database equal stored expected files.
- **Unit:** delete-everything leaves no file in a temp app directory and no key in a fake Keystore.
- **Real phone, S25 Edge:** set up backup to a cloud-drive folder; "Back up now"; the file appears and is not readable as text. "Test restore" reports the right counts. Note the capture count; uninstall the app; reinstall; restore from the file with the code: every note, task, reading and the word list is back, and a question about an old note works. Log it.
- **Real phone:** leave the phone unopened for a week: a scheduled backup was written (check the folder's dates). If not, record why (battery management, folder permission lost).
- **Real phone:** "Delete everything", then reopen: the app is empty and asks for set-up; `adb shell run-as app.stash ls -R` shows no database.

## Notes and risks

- Some storage providers revoke persisted folder permission or refuse background writes; a week of scheduled backups is the only real test.
- The recovery code screen must not be capturable (`FLAG_SECURE`) and must never be logged.
- Until this card is done, an uninstall destroys the notes. Say so in the hand-backs of earlier cards whenever an uninstall is suggested.
