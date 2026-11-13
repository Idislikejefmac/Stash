# 25 · Encrypted backup and restore

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

A backup file that only the user can open, written automatically to a folder they chose, and a restore that brings everything back on a new phone.

## Depends on

08, 17.

## Context

The database is encrypted with a key that never leaves the phone's secure hardware, so the database file itself is useless on another phone, and Android's own cloud backup is switched off for this app. Losing the phone would lose everything unless the app makes its own backup.

Design:

- **Backup key:** 32 random bytes generated on the phone. Shown to the user once as a **recovery code** (a QR code plus the same value as groups of letters and digits, Crockford base32 with a checksum group) to print or store in a password manager. Stored on the phone encrypted under a Keystore key so backups can run unattended.
- **File:** `stash-YYYYMMDD-HHmm.stashbak` = a short clear header (magic bytes, format version, key ID = first 8 bytes of SHA-256 of the key, creation time) followed by the payload encrypted with Tink's streaming authenticated encryption (AES-256-GCM-HKDF, 1 MB segments), header bytes as associated data. Payload = a zip containing `export.json` (every table except `note_fts` and `pending_audio`, plus settings; **not** the API key, not any key) with `schemaVersion`, and attached files if any exist.
- **Where:** a folder chosen with Android's folder picker (Storage Access Framework, `ACTION_OPEN_DOCUMENT_TREE`, persisted permission). The app needs no network code for this: the folder can be local storage that Syncthing-Fork copies to a home server, a cloud drive's folder, a USB stick. The storage provider sees only the encrypted file, its size and its dates.
- **Restore** is an *import* of `export.json` through code that understands older `schemaVersion`s, so an old backup restores into a newer app.

Dependency: Tink (`com.google.crypto.tink:tink-android`), used only for the streaming cipher. No hand-rolled cryptography.

## Scope

1. `backup/BackupKeyStore` (create, wrap, load, replace), `backup/RecoveryCode` (encode, decode, checksum; pure Kotlin).
2. `backup/Exporter` → `export.json` (kotlinx.serialization data classes mirroring the tables; stable field names; `schemaVersion`), `backup/Importer` (version-aware; validates references; runs in one transaction; rebuilds the FTS index; re-runs geofence/alarm scheduling hooks if those cards exist).
3. `backup/BackupWriter` / `BackupReader` (header + Tink streaming + zip), writing to a temporary name then renaming, verifying by reading the header and first segment back.
4. `backup/BackupWorker`: weekly, and after every 25 new notes, when the folder is reachable; keeps the newest 5 files and deletes older ones it created. Records last success time and last error in DataStore.
5. **Set-up screen** (behind the app lock): (a) explain in three sentences; (b) show the recovery code with "Save as PDF/print" (system print) and "I've stored it" (requires re-entering 4 random characters of it); (c) pick the folder; (d) run a backup now and confirm it can be read back. Status line in settings: "Last backup: 3 days ago to 'StashBackups'". A warning row on Home if there has been no successful backup for 21 days or none was ever set up after 20 notes exist.
6. **Restore** (available from the first-run screen and from settings): scan or type the recovery code → pick a backup file → show its date and note count → choose **Replace everything on this phone** or **Merge** (by ID; newer `created_at`/revision wins; soft-deleted stays deleted) → done. Wrong code → "That code doesn't open this backup" (detected by key ID before decrypting).
7. **New recovery code:** generates a new key; future backups use it; old files still need the old code. Say so on the screen.
8. Declare in the manifest that Android auto-backup stays off (already set in card 07; add a test that fails if `allowBackup` becomes true).

## Out of scope

Uploading to anything over the network from the app itself (Home Assistant, WebDAV, cloud APIs): the folder picker covers these through other apps; a direct target would be a later card. Sync between two phones. Plain export for reading by humans (26). Backing up the speech model or settings that are device-specific (permissions, assistant role).

## Acceptance checks

- **Unit (JVM):** recovery-code round-trip and checksum error detection (one wrong character is caught); export → import round-trip reproduces every table exactly (compare row sets) and search results; import of a hand-written `schemaVersion` 1 fixture after the schema has moved on; merge rules; tampering with one byte of the payload fails authentication; wrong key detected by key ID; the export contains neither the API key nor the database key (assert by scanning for the test values).
- **Unit:** retention keeps 5.
- **Real phone, S25 Edge:** set up with a local folder; backup file appears; `adb pull` it and confirm it is not a zip and contains no readable note text (`strings` finds none of a known phrase).
- **Real phone:** uninstall the app (or use a second phone), install, restore with the recovery code: notes, places, loans, reminders are back; "where did I put my guitar" answers as before; place reminders and alarms are re-registered.
- **Real phone:** choose a cloud drive folder (whichever the owner uses) and a Syncthing-Fork folder; record in `docs/EXPERIMENT_LOG.md` whether unattended weekly writes succeed to each (some cloud providers refuse background writes through the folder picker).

## Notes and risks

- Without the recovery code a backup cannot be opened by anyone, including the owner on behalf of his parents. The set-up screen must say this in plain words, and the owner may want to keep his parents' code himself (QUESTIONS.md Q6).
- If a provider rejects background writes, fall back to a local folder and tell the user; do not silently skip backups.
- The backup includes private (local-only) notes; they are encrypted like the rest.
