# 26 · Export for humans, and delete everything

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

The user can take their data out in a form they can read without this app, and can wipe the app completely.

## Depends on

17, 18, 25.

## Context

`backup/Exporter` (card 25) already produces a complete `export.json`. That is for machines. People want a list they can read or open in a spreadsheet. And a memory app must have an honest "forget everything" button: database, keys, pending audio, backups it can reach, the API key, geofences, alarms, notifications.

Both actions sit behind the app lock (card 17) and both ask for authentication again at the moment of use, whatever the grace period.

## Scope

1. **Export** (settings → "Export my notes"): choose **Readable list (Markdown)**, **Spreadsheet (CSV)**, or **Complete data (JSON)**. Options: include replaced/expired/done notes (default yes); include private notes (default no, with a warning when turned on); include locations (default no).
   - Markdown: grouped by kind, each line "- Reading glasses → nightstand drawer (12 Mar 2026)"; loans by person; errands by place; a final "Everything, newest first" section with the raw text.
   - CSV: one row per note: date, kind, status, text, item, where, person, place, due, expires, private, (lat, lon if chosen).
   - JSON: the card-25 export as is (unencrypted; say so).
   - Written through the system "save file" picker (`ACTION_CREATE_DOCUMENT`) or handed to the share sheet. A plain warning before writing: "This file is not protected. Anyone who has it can read it."
2. **Delete everything** (settings → bottom, red): a screen that lists what will go; requires authentication and typing the word DELETE (or holding a button for 3 seconds in simple mode); then:
   - cancels all alarms, removes all geofences, cancels notifications and scheduled work;
   - deletes the database files, pending audio, attached files, DataStore settings, the wrapped keys, and the Keystore keys themselves;
   - optionally (checkbox, default on) deletes the backup files the app created in the chosen folder;
   - restarts the app at first run.
   The API key is deleted locally; the screen reminds the user that revoking it at the provider is separate.
3. **Delete one person's or one place's notes** is not needed; single-note delete exists (card 18).
4. A short "What is stored and where" page in settings, in plain words, generated from the same facts: on this phone, encrypted; backups in <folder>; what is sent to the model if enabled.

## Out of scope

Importing Markdown/CSV, exporting to other apps' formats, scheduled plain exports (unprotected files should be deliberate).

## Acceptance checks

- **Unit (JVM):** Markdown and CSV writers against a seeded database (snapshot tests); CSV escaping of commas, quotes and newlines; private notes excluded by default and included when asked; locations excluded by default.
- **Unit (Robolectric):** after "delete everything", no file remains under the app's files, databases, cache and no-backup directories; Keystore aliases are gone; scheduled work is empty.
- **Real phone, S25 Edge:** export Markdown to Downloads and open it in another app: readable and correct. Delete everything: the app returns to first run; `adb shell run-as` (debug) shows empty directories; no geofences remain (debug screen before, system "location" recent-apps after); backup files are gone from the folder when the box was ticked.
- **Real phone:** restore from an older backup after a delete works (the recovery code still opens older files).

## Notes and risks

- Deleting files in a storage-access folder can fail (provider offline); report which files could not be removed rather than claiming success.
- Do not offer "export" from the lock screen or via any intent another app could send.
