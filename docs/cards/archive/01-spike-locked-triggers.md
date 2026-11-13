# 01 · Spike: which triggers can start a voice note from a locked phone

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

Find out, on each of the three phones, which one-action triggers can start a microphone recording **without unlocking**, with an audible cue at start and at end. This decides the app's main entry point. It is the riskiest assumption in the whole design, so it comes first.

## Depends on

None.

## Context

Android only lets an app use the microphone when the user can tell it is happening. The documented ways a recording may start when the app was not already on screen are: (a) the app shows an activity (an activity with `android:showWhenLocked="true"` and `android:turnScreenOn="true"` can appear over the lock screen); (b) the user taps one of the app's **widgets**; (c) the user taps a **notification action**; (d) the app is the phone's **default digital assistant** (a `VoiceInteractionService`). A service started any other way from the background gets silence or a `SecurityException`. Android 17 also silently mutes sound played from the background, so a cue that "plays" may not be heard.

What is not known is which hardware and system gestures on each phone can reach (a)–(d) while the phone is locked. Samsung's help pages suggest side-key double-press "Open app" asks for an unlock first; nobody documents the rest.

This is a throwaway app. Do not build it to be reused. It lives in `spikes/triggerlab/` as its own small Gradle project (applicationId `app.stash.triggerlab`), so it can be deleted later.

## Scope

Build **TriggerLab**, an app with:

1. `LabCaptureActivity`: `showWhenLocked`, `turnScreenOn`, no keyguard dismissal. On launch: plays a rising tone (`ToneGenerator` or a short bundled WAV through `AudioTrack`/`SoundPool`, usage `ASSISTANCE_SONIFICATION`), records 5 seconds with `AudioRecord` at 16 kHz mono, plays two tones, then plays the recording back, then finishes. Shows a large label with the trigger name (passed as an intent extra) and the peak amplitude measured. Appends one line to an in-app log: time, trigger name, `KeyguardManager.isDeviceLocked`, peak amplitude, whether any exception occurred.
2. `LabCaptureService`: a foreground service of type `microphone` doing the same 5-second record and cues without any activity, for the widget and notification paths.
3. Entry points, each tagging its trigger name:
   - launcher icon and a static launcher shortcut;
   - a quick-settings tile (`TileService`) that launches the activity (`startActivityAndCollapse` with a `PendingIntent`; do not call `unlockAndRun`);
   - a home-screen widget whose tap starts `LabCaptureService` directly, and a second widget whose tap launches the activity;
   - an ongoing notification with two actions: "Record (service)" → `LabCaptureService`, "Record (screen)" → the activity. Notification visibility `PUBLIC`;
   - a `VoiceInteractionService` + `VoiceInteractionSessionService` + `RecognitionService` stub so the app can be chosen as the default digital assistant; `onLaunchVoiceAssistFromKeyguard()` and the session's `onShow` both launch the capture path;
   - an activity alias with a clear name ("TriggerLab record") so Samsung's side-key "Open app", lock-screen shortcut pickers, Good Lock RegiStar and Moto Quick Launch can target it.
4. A log screen (unlocked) listing the lines, with a "copy all" button.

Then run the checklist below on each phone and write every row into `docs/EXPERIMENT_LOG.md` under "Triggers from a locked phone".

### Checklist (each on a phone that has been locked for at least a minute, screen off)

| # | Trigger | How to set it up |
| --- | --- | --- |
| 1 | Notification action "Record (service)" | Wake the screen, expand the notification on the lock screen, tap |
| 2 | Notification action "Record (screen)" | Same |
| 3 | Quick-settings tile | Pull down the shade on the lock screen, tap the tile |
| 4 | Default assistant, hardware long-press | Samsung: Settings → Advanced features → Side button → Long press → Digital assistant, and Settings → Apps → Choose default apps → Digital assistant app → TriggerLab. Motorola: Settings → Apps → Default apps → Digital assistant app; and the power-button long-press setting under Gestures |
| 5 | Default assistant, corner swipe (if gesture navigation is on) | From the lock screen |
| 6 | Default assistant, long-press on a Bluetooth headset or earbud button | Any earbuds that have an "assistant" gesture |
| 7 | Samsung side key **double-press** → Open app → TriggerLab record | Settings → Advanced features → Side button → Double press |
| 8 | Samsung lock-screen corner shortcut → TriggerLab record | Lock screen editor → shortcuts |
| 9 | Samsung Good Lock RegiStar back-tap → open app | Install Good Lock and RegiStar |
| 10 | Motorola Quick Launch (double-tap the back) → app, if it offers "open app" | Settings → Gestures |
| 11 | Motorola AI key, any remap the phone itself offers | Settings → search "AI key" |
| 12 | Home-screen widget (service) and widget (screen) | Unlocked baseline only |
| 13 | Lock-screen widget, if the phone lets a third-party widget be added to the lock screen | Samsung lock screen editor |

For each row record: did the capture start without unlocking; was the start tone heard; was the played-back recording real speech or silence; was the end tone heard; anything the phone asked for. Also note, for row 4, what happened to "Hey Google" and to Gemini after TriggerLab became the assistant, and whether the role was still held after reinstalling the app.

## Out of scope

Speech to text, saving anything, location, a pleasant UI, cover screens (card 02), shake or chop detection (ruled out by platform rules; do not build it).

## Acceptance checks

- **Real phone, owner's S25 Edge (Android 17):** every checklist row that applies has a line in the experiment log with a status mark.
- **Real phone, razr ultra 2025 and Z Flip 7:** same. If the other phones are not available yet, log the S25 Edge and mark the others ⏳; cards 12 onward may proceed on the owner's phone.
- **Pass for the design:** on every phone at least one trigger starts a real (non-silent) recording from the locked state with both cues audible. Expected: rows 1 and 4.
- **Fail:** no trigger passes on some phone. Stop and tell the owner; the fallback is "wake, unlock with fingerprint, tap widget", which changes must-have 1 for that phone.
- No unit tests in this card.

## Notes and risks

- Use `adb shell cmd audio set-enable-hardening throw` on the Android 17 phone to turn silent cue failures into exceptions while testing; set it back to `enable` afterwards.
- If the tile launches the activity only after an unlock prompt, record that as a fail for "without unlocking", not as a bug to fix.
- Becoming the default assistant is a user choice in Settings; the app can only open the settings screen (`Settings.ACTION_VOICE_INPUT_SETTINGS` or the role request via `RoleManager.ROLE_ASSISTANT`). Record which one the phone honours.
- Samsung may route the long-press to Bixby unless "Digital assistant" is selected for the side key; record the exact menu path that worked, because onboarding will have to describe it.
