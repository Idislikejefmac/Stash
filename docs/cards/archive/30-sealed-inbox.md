# 30 · Hardening: notes captured while locked go into a sealed inbox

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

Make "reading requires unlocking" true cryptographically, not only in app code: while the phone is locked, the app can add notes but cannot decrypt any stored note, because the key that opens the database is unavailable until the phone is unlocked.

## Depends on

12, 13, 15, 17, 22, 23, 24, 25, 28 (it audits every background path those cards added). Build only when the owner asks for it (see RISKS.md, "the reading gate is enforced by code"). Everything before this card works without it.

## Context

In version 1 the database key is wrapped by an Android Keystore key that works at any time, because capture writes to the database while the phone is locked. An attacker able to run code as the app on a locked phone (not a person holding it; that is already covered) could therefore read notes.

The fix is a one-way door:

- The **database key's wrapping key** gets `setUnlockedDeviceRequired(true)`: the Keystore refuses to use it while the device is locked.
- An **inbox key pair** (EC P-256 in the Keystore) is created; the private key also has `setUnlockedDeviceRequired(true)`; the public key is exported and stored in plain form.
- While locked, capture encrypts each new note (text, time, location, trigger) to the public key using hybrid encryption (ephemeral ECDH + HKDF + AES-GCM; use Tink's hybrid encryption primitives rather than assembling it by hand) and appends the ciphertext to `inbox/` as one file per note. The app cannot read these back until unlock.
- On unlock (first chance: app open, a capture started while unlocked, or a periodic worker that finds the device unlocked), the inbox is **drained**: each file is decrypted, inserted through the normal repository and structuring pipeline, and deleted.

Consequences that must be handled:

- Structuring, supersession and reminder creation for a locked capture are delayed until the next drain. A place reminder said while locked is not armed until then.
- A geofence arrival while the phone is locked cannot read the reminder text. Keep a tiny non-secret side file `arrivals.json`: geofence ID → place name and a count, rewritten at each reconcile, so the receiver can post "A reminder for Costco (2)" without the database. The private text is filled in after unlock.
- Time-reminder alarms firing while locked: same approach (generic text from the alarm's extras, which hold only the reminder ID).
- Pending audio (failed transcription) is already encrypted with its own key; switch that key to the same inbox public-key scheme.
- The transcript shown on the capture screen for Undo is held in memory only; Undo while locked deletes the inbox file.
- Backups run only when unlocked (the worker simply retries).

## Scope

1. `security/KeyPolicy` migration: on first start after this version (device unlocked, inside the app gate), create the new keys, re-wrap the database key under the unlocked-required wrapping key, delete the old wrapping key. Reversible only by restore; do it once, log the outcome through `Dbg`, and keep the old wrapped blob until the new one has been verified by opening the database.
2. `capture/CaptureInbox`: `append(sealedNote)` (works locked), `drain()` (requires unlocked), with file-per-note atomic writes.
3. `CaptureSession` writes through a `CaptureSink` interface: when the device is locked → inbox; otherwise → repository directly as today.
4. `InboxDrainWorker` (periodic 15 minutes while the inbox is non-empty; plus drain calls at app start, `ProtectedContent` unlock, and capture start when unlocked). Drain is idempotent (note IDs are assigned at capture).
5. Database access wrapper: any attempt to open the database while locked fails fast with a typed `DeviceLocked` result; receivers and workers handle it by doing the generic thing or rescheduling. Audit every receiver/worker from cards 13, 15, 22, 23, 24, 25, 28 for this path.
6. `arrivals.json` side file and receiver changes as described.
7. Home shows "2 notes added while locked" after a drain, linking to the review list.

## Out of scope

Requiring a fresh biometric for every database open (`setUserAuthenticationRequired`): it would break background work while unlocked and add prompts; the unlocked-device requirement is the right strength for this threat model. Protecting against a compromised operating system.

## Acceptance checks

- **Unit (JVM) with fake key policy and lock state:** capture while "locked" produces an inbox file that the app's own code cannot decrypt while locked (decrypt call throws); after "unlock", drain inserts the note with the original capture time and location, runs structuring, and removes the file; draining twice inserts once; Undo while locked removes the file.
- **Unit:** each audited worker/receiver returns/reschedules on `DeviceLocked` without crashing; the arrival receiver posts the generic text from the side file with no database access.
- **Real phone, S25 Edge:** lock the phone for 5 minutes; capture three notes; with the phone still locked, `adb shell run-as` (debug build) shows three inbox files and the app's debug "try to read" broadcast reports `DeviceLocked`; unlock → within moments the notes appear, structured, in order.
- **Real phone:** a place reminder captured while locked is armed after the next unlock; an arrival while locked shows the generic notification; after unlock the notification's expanded text shows the items.
- **Real phone:** key migration on an install with existing data keeps every note (compare counts and a backup round-trip before and after).
- **Real phone, Flip 7 and razr:** confirm `setUnlockedDeviceRequired` keys behave the same (some devices have had bugs where such keys stay usable briefly after locking, or become unusable after a fingerprint change); log what is seen.

## Notes and risks

- This is the most intricate card in the project and touches many others. Take a verified backup first. Ship it to the owner's phone for two weeks before the family's.
- `setUnlockedDeviceRequired` has had manufacturer-specific bugs in past Android versions (medium confidence). If it misbehaves on one of the phones, keep that phone on the version-1 policy (a build-time or settings flag) and record it.
- If enrolling a new fingerprint invalidates keys on some device, the database key becomes unrecoverable there: that is what the encrypted backup is for; say so in the migration screen.
