# 29 · Release builds and updates for three households

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

A repeatable way to produce a signed release and get it, and every later update, onto the three phones with as little effort for the family as the platform allows.

## Depends on

06 (read its decision in `docs/EXPERIMENT_LOG.md`, "Installing and updating", and the owner's answer to QUESTIONS.md Q4), 07, 15, 22, 23 (their after-update behaviour is verified here).

## Context

The owner installs on his own phone from Android Studio. The other two phones need another route. Spike 06 tried the candidates and recorded which works on each phone:

- **Route A: Google Play internal testing.** Upload an app bundle to the internal track; invited Google accounts install from a link and receive updates automatically. No sideloading obstacles (Samsung Auto Blocker, "unknown sources", Google's developer verification).
- **Route B: sideload with an in-app update check.** A signed APK and a small `version.json` are published somewhere the owner controls (for example GitHub Releases). The app checks occasionally, tells the user, downloads the APK and hands it to the system installer. On Samsung the user must switch Auto Blocker off first; from 2027 the developer must also be registered with Google for sideloaded installs to work on certified phones.

Build the route the log chose. Both need items 1–4.

## Scope

1. **Signing:** release signing from `keystore.properties` (ignored by git) pointing at a keystore kept outside the repository. A `docs/RELEASING.md` (this is an explicitly requested document) with: where the key lives (described, not the secrets), how to back it up, and the steps below. State plainly that losing the key means uninstalling on every phone and restoring from backups.
2. **Versioning:** `versionCode` = commit count or a manual integer bumped per release; `versionName` = `YYYY.MM.DD`. Shown in settings → About, with the speech model name and (if any) the model provider.
3. **Release checklist** in `RELEASING.md`: unit tests pass; migration tests pass from every earlier schema; install over the previous release on the owner's phone and confirm notes, triggers, assistant role, geofences and backup survive; backup then restore on a spare install; only then publish.
4. **"What's new"** string shown once after an update, in two or three plain sentences, because the family will not read release notes.
5. **Route A (if chosen):** Gradle task/doc for building the `.aab`; Play Console steps (internal track, tester list, the short privacy statement, data-safety answers: data stays on device; optional transmission of note text to the model provider; no data collected by the developer); how to add a family member's Google account. No in-app update code.
6. **Route B (if chosen):** `update/UpdateChecker` in package `app.stash.update` (the second package allowed to use the network): at most once a day and only on unmetered networks, fetch `version.json` (`versionCode`, `apkUrl`, `sha256`, `notes`) over HTTPS from one fixed host; if newer, show a row on Home ("An update is ready") and, on tap, download to cache, verify SHA-256, start a `PackageInstaller` session. Per-manufacturer instructions when the installer is blocked (Auto Blocker steps on Samsung, from the log). `REQUEST_INSTALL_PACKAGES` permission. The checker sends nothing but the HTTP request (no device ID, no version in the query).
7. **Either route:** an `adb` fallback paragraph for when the owner is physically with the phone.
8. Verify after an update: scheduled work, alarms, geofences and the ongoing notification are re-established by the `MY_PACKAGE_REPLACED` receiver (cards 15, 22, 23).

## Out of scope

Public Play listing, F-Droid, crash reporting, staged rollouts, automatic silent self-update (not available to sideloaded apps without user action on first install).

## Acceptance checks

- **Unit (JVM):** (Route B) version comparison; SHA-256 mismatch aborts; malformed `version.json` is ignored; the request carries no query parameters or custom headers beyond a generic user agent. `NetworkBoundaryTest` allows `app.stash.update` and nothing new.
- **Build:** `./gradlew assembleRelease` (and `bundleRelease` for Route A) produce signed output; the APK contains only `arm64-v8a` native libraries; record its size.
- **Real phone, S25 Edge:** release build installed over the debug-signed build is refused (expected: different key); installed fresh, it runs; then version N+1 over N keeps all data and the assistant role.
- **Real phone, Z Flip 7 and razr ultra:** version N installed by the chosen route; version N+1 arrives by the chosen route with the number of user steps recorded in `docs/EXPERIMENT_LOG.md`. Target: zero steps on Route A; at most three taps on Route B.
- **Real phone:** after the update, without opening the app, a place reminder and the lock-screen notification action still work.

## Notes and risks

- R8 can strip classes needed by the speech engine's JNI layer, the model SDK and serialization; the release build must pass the four-sentence capture test and a model call before any family phone gets it.
- Keep debug and release as different application IDs (`app.stash.debug`) so the owner can have both installed and never tests on his real data by accident.
- Check Google's developer-verification page at each release until its rules are settled; record changes in the log.
