# 02 · App gate

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

Revision 2 split the old card 02 in two: this card is the fingerprint or PIN gate; database encryption is now [card 14](14-encryption-at-rest.md). The old combined card is at `archive/rev1/02-app-gate-encryption.md`.

## Goal

Reading stored notes requires the owner's fingerprint or PIN, enforced in one place. Adding a note (by voice, by typing, by sharing a picture) never asks.

## Depends on

01. Build it at the start of the hardening block (README build order), after the two-week usefulness check and before pictures, trips or watches are added.

## Context

Today the app opens straight onto the notes. The gate is what protects against the everyday case: someone holding the unlocked phone. It does more for that case than database encryption does (`docs/STORAGE.md`).

`capture/CaptureActivity` must keep working from the locked screen with no prompt. So must every other way of *adding*: the typed-note field, and from card 16 the Share to Stash screen.

Library: `androidx.biometric:biometric`.

## Scope

1. `security/AppLock`: `unlockedUntil` held in memory only (process death = locked). `isUnlocked(now)`, `unlock(activity)`, `lockNow()`. Grace period setting: every time / 2 minutes (default) / 10 minutes. Locks at once when the screen turns off, and when the app has been in the background longer than the grace period.
2. A single `ProtectedContent { }` composable wrapper used by `MainActivity` for **everything** it shows: sets `FLAG_SECURE`; if the device is locked, asks the system to dismiss the keyguard first; if `AppLock` is locked, shows a neutral screen ("Unlock to see your notes" and one large button) and runs `BiometricPrompt` with `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`; only then composes its content. View models that hold note content clear it on `lockNow()`.
3. **One rule for entry points:** an activity either *adds* (shows only the thing being added now, sits outside the gate, never links into stored content) or *reads* (is `MainActivity` and therefore inside `ProtectedContent`). There is no third kind. `CaptureActivity` is an adding activity. Notification taps that open a list go to `MainActivity`.
3a. **Private items need a second unlock.** A capture, record, picture or scan marked sensitive ("Private" in the UI) is never composed until `AppLock.privateUnlockedUntil` is set, which takes a **fresh** `BiometricPrompt` regardless of the grace period and lasts until the app leaves the foreground or 2 minutes pass. Until then: lists show a single row "3 private notes · Unlock" where they would sort; search and answers say "1 private note also matches · Unlock to see" and show nothing of it, not a title, not a thumbnail, not while typing; the "To handle" screen shows private tasks only after the second unlock. One composable, `PrivateContent { }`, does this; nothing else reads the mark to decide what to draw.
3b. Marking something private is one tap and never asks. Un-marking asks for the second unlock and a confirmation ("This note may then be sent to your model provider when it is re-read").
4. No screen lock set on the phone: explain that one is needed to read notes, with a button to the system security settings. Capture still works.
5. The in-app "Add a note" button and typed-note entry work without passing the gate; anything that lists or searches does not.
6. A unit test that scans the manifest: every exported activity other than `MainActivity` is on a short allow-list of adding activities.

## Out of scope

Database encryption (card 14). Binding keys to authentication, the sealed inbox (`docs/STORAGE.md`, archived card 30). Backup (10). Per-note locks.

## Acceptance checks

- **Unit (JVM):** `AppLock` timing with a fake clock for each grace setting; screen-off locks; a new process is locked.
- **Unit (Robolectric):** protected content is not composed before unlock (a test tag is absent) and is composed after a simulated success.
- **Unit:** the manifest test in item 6.
- **Unit (Robolectric):** with a seeded database holding one private note with a picture: the Notes list, search-as-you-type, an answer and the "To handle" screen contain none of its text and no thumbnail before the second unlock, and show it after; leaving the foreground hides it again.
- **Real phone, S25 Edge:** open the app: the prompt appears, then every note is there. Background the app for 3 minutes → prompt again. Screen off and on → prompt again. The recent-apps card is blank. A screenshot is refused.
- **Real phone:** lock the phone; add a note from the lock-screen notification: no prompt, note saved. Unlock, open the app: prompt, then the note is there.
- **Real phone:** mark a note private; inside the grace period reopen the app: the note is still hidden until a new fingerprint. Search for a word in it: only the count line appears.
- **Real phone:** with the phone locked, `adb shell am start -n app.stash/.ui.MainActivity` → the system unlock comes first; cancelling shows nothing of the notes.
- Write the results in `docs/EXPERIMENT_LOG.md`.

## Notes and risks

- `BiometricPrompt` needs a `FragmentActivity`; change `MainActivity`'s base class.
- Samsung face unlock is a weak biometric and will not satisfy `BIOMETRIC_STRONG`; the device-credential fallback covers it.
- The gate is app logic, not cryptography. `docs/PRIVACY.md` says so; do not claim more in the UI.
