# 02 · App gate and encryption at rest

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

Reading stored notes requires the owner's fingerprint or PIN, enforced in one place; and the database file is encrypted so that a copy of it is useless off the phone. The existing notes survive the conversion.

## Depends on

01.

## Context

Today the database is a plain SQLite file and the app opens straight onto the notes. Two protections are added here. They are different in value and both documents say so (`docs/PRIVACY.md`):

- **The app gate** is what protects against the everyday case: someone holding the unlocked phone.
- **Database encryption** protects a copy of the file that leaves the phone. Because notes are saved while the phone is locked, the database key must be usable **without** user authentication, so encryption does not by itself enforce "reading needs unlock". That limit is accepted and documented; do not try to bind the key to biometrics.

Capture (`capture/CaptureActivity`) must keep working from the locked screen with no prompt.

Library: `net.zetetic:sqlcipher-android` (with `androidx.sqlite`), used through its `SupportOpenHelperFactory` in the Room builder. `androidx.biometric:biometric` for the gate.

## Scope

### Encryption

1. `security/DatabaseKeyStore`: create-or-load a random 32-byte database key. It is stored only as ciphertext, wrapped with an AES-256-GCM key in the Android Keystore (prefer StrongBox, fall back to the TEE; **no** `setUserAuthenticationRequired`, **no** `setUnlockedDeviceRequired`). Follow the pattern already in `security/SecretStore.kt`. If the wrapped key exists but cannot be unwrapped, raise a clear `DatabaseKeyLost` error; never create a new empty database over an existing file.
2. **One-time conversion** of the existing plain database, run before Room opens it:
   - Detect a plain file (its first 16 bytes are `SQLite format 3\0`).
   - Open it with SQLCipher using an empty key, `ATTACH` a new file `stash-enc.db` with the real key, `SELECT sqlcipher_export('encrypted')`, copy `user_version`, detach.
   - Open the new file with the key and compare row counts of `capture`, `record`, `vocab_word` and `correction` with the plain file.
   - Only if they match: rename the plain file to `stash-plain-backup.db`, rename the new file to `stash.db`. On any failure: delete the new file, keep using the plain one, show a line in Settings ("Couldn't encrypt the notes yet") and try again at next start.
3. `Container` builds Room with the SQLCipher factory. The database class must also be constructible with the ordinary factory so JVM tests (card 01's migration test included) keep running without the native library.
4. **Clean-up, by the owner's hand:** a Settings row "Remove old unencrypted copies" that deletes `stash-plain-backup.db` and card 01's `stash-v1-backup.db`. It appears only while they exist, and says what it does. Do not delete them automatically.
5. Settings shows "Notes are encrypted on this phone" (or the failure line). Remove the prototype's "database is not encrypted" warning.

### App gate

6. `security/AppLock`: `unlockedUntil` held in memory only (process death = locked). `isUnlocked(now)`, `unlock(activity)`, `lockNow()`. Grace period setting: every time / 2 minutes (default) / 10 minutes. Locks at once when the screen turns off, and when the app has been in the background longer than the grace period.
7. A single `ProtectedContent { }` composable wrapper used by `MainActivity` for everything it shows: sets `FLAG_SECURE`; if the device is locked, asks the system to dismiss the keyguard first; if `AppLock` is locked, shows a neutral screen ("Unlock to see your notes" and one large button) and runs `BiometricPrompt` with `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`; only then composes its content. View models that hold note content clear it on `lockNow()`.
8. `CaptureActivity` stays outside the gate and must not link into gated content. Its "Saved: …" text is the only note text it may show.
9. No screen lock set on the phone: explain that one is needed to read notes, with a button to the system security settings. Capture still works.
10. The in-app "Add a note" button and typed-note entry (if present) work without passing the gate; anything that lists or searches does not.

### Developer access

11. `tools/peek.py notes` can no longer open the file. Make it say so and point to `dump` (card 01). The debug dump keeps working because the app itself decrypts.

## Out of scope

Binding keys to authentication or the "sealed inbox" (archived card 30). Backup (10). Per-note locks. Changing what the capture screen shows.

## Acceptance checks

- **Unit (JVM):** key wrap and unwrap round-trip with a fake Keystore; a wrong key fails; a missing wrapped key with an existing database raises `DatabaseKeyLost`.
- **Unit (JVM):** `AppLock` timing with a fake clock for each grace setting; screen-off locks; a new process is locked.
- **Unit (Robolectric):** protected content is not composed before unlock (a test tag is absent) and is composed after a simulated success.
- **Unit:** conversion logic with file fakes: mismatch in counts → plain file kept, new file deleted; success → names swapped, backup present.
- **Real phone, S25 Edge:** note the capture count with `peek.py dump`. Install. Open: the prompt appears, then every note is there. `adb shell run-as app.stash cat databases/stash.db | head -c 16` is not `SQLite format 3`. `peek.py dump` still works and shows the same count. Log which Keystore level was used (StrongBox or TEE) in `docs/EXPERIMENT_LOG.md`.
- **Real phone:** lock the phone; add a note from the lock-screen notification: no prompt, note saved (proves the key works while locked). Unlock, open the app: prompt, then the note is there.
- **Real phone:** background the app for 3 minutes → prompt again. Screen off and on → prompt again. The recent-apps card is blank. A screenshot is refused.
- **Real phone:** with the phone locked, `adb shell am start -n app.stash/.ui.MainActivity` → the system unlock comes first; cancelling shows nothing of the notes.
- **Real phone:** use "Remove old unencrypted copies"; `adb shell run-as app.stash ls databases files` shows neither backup.

## Notes and risks

- `System.loadLibrary("sqlcipher")` once, before Room opens.
- `BiometricPrompt` needs a `FragmentActivity`; change `MainActivity`'s base class.
- Samsung face unlock is a weak biometric and will not satisfy `BIOMETRIC_STRONG`; the device-credential fallback covers it.
- Keep the passphrase as a `ByteArray` and zero it after handing it to the factory.
- If `sqlcipher_export` is not available in the library's build, the fallback is: create the encrypted database with Room, then copy rows table by table inside one transaction. Say which you used.
- Until the owner removes the plain copies, the notes exist unencrypted on the phone. Say so in the hand-back so he does it.
