# 14 · Encryption at rest (SQLCipher) and the release-signed build

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

Split from the old card 02 in revision 2. The reasons for its place in the order are in `docs/STORAGE.md`.

## Goal

The database file is encrypted so that a copy of it is useless off the phone, the existing notes survive the conversion, and the app moves from a debug build to a release-signed build without losing anything.

## Depends on

01, 10 (a verified backup exists before the conversion), 15 (the spike has shown SQLCipher, Room and full-text search work together on this phone). Recommended after 02.

## Context

Until this card the database is a plain SQLite file. That is acceptable during development for a stated reason: a debug build already lets an authorised computer read the app's files, so encrypting the database of a debug build protects almost nothing. Encryption starts to matter on the day the app becomes a release build, so the two changes are made together here.

Because notes are saved while the phone is locked, the database key must be usable **without** user authentication. Encryption therefore does not by itself enforce "reading needs unlock" (the app gate does). That limit is accepted and documented; do not bind the key to biometrics. The later fix is the sealed inbox (`docs/STORAGE.md`).

Library facts, read on 2026-11-11 by a research helper; **confirm on the live pages** (`https://github.com/sqlcipher/sqlcipher-android`): `net.zetetic:sqlcipher-android`, latest 4.20.0; used with Room 2.x through `SupportOpenHelperFactory(keyBytes)` passed to `openHelperFactory`; from 4.18.0 it also offers a `SQLCipherDriver` for Room 3, which has removed the older API; needs `androidx.sqlite:sqlite` 2.7.0 and compileSdk 37; BSD-style licence that requires its notice in a place the user can reach. Avoid the 5.0 beta.

## Scope

### Encryption

1. `security/DatabaseKeyStore`: create-or-load a random 32-byte database key. It is stored only as ciphertext, wrapped with an AES-256-GCM key in the Android Keystore (prefer StrongBox, fall back to the TEE; **no** `setUserAuthenticationRequired`, **no** `setUnlockedDeviceRequired`). Follow the pattern in `security/SecretStore.kt`. The wrapped blob starts with a one-byte format version. If the wrapped key exists but cannot be unwrapped, raise a clear `DatabaseKeyLost` error; never create a new empty database over an existing file.
2. Give SQLCipher the key as a **raw key** (the 32 bytes, not a passphrase), so opening the database does not run the slow passphrase stretching. Keep it as a `ByteArray` and zero it after handing it to the factory.
3. **One-time conversion** of the existing plain database, run before Room opens it:
   - Refuse to start unless Settings shows a successful backup (card 10) newer than the newest capture, or the owner taps "Convert anyway".
   - Detect a plain file (its first 16 bytes are `SQLite format 3\0`).
   - Open it with SQLCipher using an empty key, `ATTACH` a new file `stash-enc.db` with the real key, `SELECT sqlcipher_export('encrypted')`, copy `user_version`, detach.
   - Open the new file with the key and compare row counts of every table with the plain file.
   - Only if they match: rename the plain file to `stash-plain-backup.db`, rename the new file to `stash.db`. On any failure: delete the new file, keep using the plain one, show a line in Settings ("Couldn't encrypt the notes yet") and try again at next start.
4. `Container` builds Room with the SQLCipher factory. The database class must also be constructible with the ordinary factory so JVM tests keep running without the native library.
5. **Clean-up, by the owner's hand:** a Settings row "Remove old unencrypted copies" that deletes `stash-plain-backup.db` and card 01's `stash-v1-backup.db`. It appears only while they exist. Do not delete them automatically.
6. Settings shows "Notes are encrypted on this phone" (or the failure line), and an "Open-source licences" row containing SQLCipher's notice.
7. `tools/peek.py notes` can no longer open the file: make it say so and point to `dump`.
8. Add `data-extraction-rules` that exclude the database, the wrapped keys and the picture files from both cloud backup and device-to-device transfer, so a transferred install starts clean instead of crashing on a key it cannot unwrap. (`allowBackup=false` stays.)

### Release build

9. A `release` build type signed with the **same** key the debug build has used since card 01, minified off for now, `debuggable=false`. Because the signing key is the same, it installs over the debug build and the data stays. Verify that before doing it on the owner's phone: install debug then release on an emulator.
10. The debug dump receiver and debug screens are absent from the release build (they live in `src/debug`). `peek.py` says "release build: use Export in Settings".

## Out of scope

The sealed inbox. Field-level encryption (rejected, `docs/STORAGE.md`). Key rotation UI. Play distribution.

## Acceptance checks

- **Unit (JVM):** key wrap and unwrap round-trip with a fake Keystore; a wrong key fails; a missing wrapped key with an existing database raises `DatabaseKeyLost`.
- **Unit:** conversion logic with file fakes: mismatch in counts → plain file kept, new file deleted; success → names swapped, backup present.
- **Real phone, S25 Edge:** note the capture count with `peek.py dump`. Back up (card 10). Install. Open: every note is there. `adb shell run-as app.stash cat databases/stash.db | head -c 16` is not `SQLite format 3`. `peek.py dump` still works and shows the same count. Search and a question feel as fast as before; note the time to open the app cold, before and after. Log which Keystore level was used (StrongBox or TEE).
- **Real phone:** lock the phone; add a note from the lock-screen notification: saved with no prompt (proves the key works while locked).
- **Real phone:** use "Remove old unencrypted copies"; `adb shell run-as app.stash ls databases files` shows neither backup.
- **Real phone:** install the release build over the debug build: notes intact; `adb shell run-as app.stash ls` is refused.
- Write all of it in `docs/EXPERIMENT_LOG.md`.

## Notes and risks

- `System.loadLibrary("sqlcipher")` once, before Room opens.
- If `sqlcipher_export` is not available, the fallback is: create the encrypted database with Room, then copy rows table by table inside one transaction. Say which you used.
- Until the owner removes the plain copies, the notes exist unencrypted on the phone. Say so in the hand-back.
- If Room has been moved to version 3 by the time this is built, use `SQLCipherDriver` with `setDriver`; the rest of the card is unchanged.

## Added 2026-11-12 (D80, from card 16d): release-build check for the debug screenshot toggle

After `assembleRelease`, the screenshot-toggle code must be physically absent from the release APK. Run, for each of `ScreenshotToggle`, `DebugSecureInit` and `DebugSettingsActivity`: `unzip -p app-release.apk 'classes*.dex' | grep -c <name>`. Each must print `0`. Also confirm `MainActivity` still sets `FLAG_SECURE` unconditionally. Record the results in `docs/EXPERIMENT_LOG.md`.
