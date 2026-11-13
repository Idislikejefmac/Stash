# 17 · App lock: reading needs the user's fingerprint, face or PIN

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

One gate that every screen showing stored notes must pass, enforced in one place and impossible to forget when adding a screen.

## Depends on

08.

## Context

Policy: adding a note is allowed on a locked phone; **reading, searching and answering require the user's biometric or device credential**. This is the main protection against someone else holding the phone, including an unlocked phone handed over for a moment.

Two layers:

1. The device must be unlocked (`KeyguardManager.isDeviceLocked == false`). A reading screen launched over the lock screen must first ask the system to dismiss the keyguard (`KeyguardManager.requestDismissKeyguard`), which shows the phone's own fingerprint/PIN prompt.
2. The app's own gate: `androidx.biometric.BiometricPrompt` with `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`. After success, reading is allowed for a grace period.

In version 1 the gate is enforced by app code; the database key itself is not bound to authentication (it must work while locked so capture can save). That limit is documented and accepted; card 30 tightens it.

## Scope

1. `security/AppLock`: holds `unlockedUntil` in memory only (never persisted; process death = locked). `isUnlocked(now)`, `unlock(activity): Result` (runs the prompt), `lockNow()`. Grace period from settings: `EVERY_TIME` (0), `TWO_MINUTES` (default), `TRUST_DEVICE_UNLOCK` (the app gate is skipped, the device-unlocked check still applies). Locks immediately when the screen turns off (`ACTION_SCREEN_OFF` receiver registered at runtime) and when the app has been in the background longer than the grace period.
2. `ui/ProtectedActivity` (base class) or a single `ProtectedContent { }` composable wrapper that: sets `FLAG_SECURE` (no screenshots, blank in recents); if the device is locked, requests keyguard dismissal; if `AppLock` is locked, shows a neutral screen ("Unlock to see your notes" + a large button) and launches the prompt; only then composes its content. Reading screens get their data **through** this wrapper (a `ProtectedScope` parameter the repositories' read functions require in the UI layer), so a screen that skips the gate does not compile.
3. `CaptureActivity` stays outside the gate and must not link to gated content.
4. No biometrics or PIN enrolled on the phone: explain that a screen lock is required to read notes, with a button to system security settings; capture still works.
5. Settings entry for the grace period, itself behind the gate. Changing to `TRUST_DEVICE_UNLOCK` shows a one-sentence warning.
6. Home screen (placeholder until card 18) is wrapped.

## Out of scope

Binding encryption keys to authentication (30), voice recognition of the speaker (not planned), per-note locks.

## Acceptance checks

- **Unit (JVM):** `AppLock` timing with a fake clock for each grace setting; screen-off locks; process restart is locked.
- **Unit (Robolectric):** protected content is not composed before unlock (assert a test tag is absent), and is composed after a simulated success.
- **Real phone, S25 Edge:** open the app → prompt → notes. Background for 3 minutes → prompt again. Screen off and on → prompt again. Recents shows a blank card. Screenshot is refused.
- **Real phone:** with the phone locked, `adb shell am start` the home activity → the system unlock appears first; cancelling it shows nothing of the app's data.
- **Real phone:** capture from the lock screen still works without any prompt.
- **Real phone, Flip 7 and razr (when available):** the prompt is usable on the cover screen / external display, or the log records that reading needs the phone opened.

## Notes and risks

- `BiometricPrompt` needs a `FragmentActivity`; make the app's activities extend it.
- Face unlock on Samsung is class 2 ("weak") and will not satisfy `BIOMETRIC_STRONG`; the device-credential fallback covers it. Mention in the hand-back what the parents' phone offered.
- Do not cache note content in view models that outlive the lock; on `lockNow()` clear them.
