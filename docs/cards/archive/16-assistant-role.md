# 16 · Optional: be the phone's digital assistant (hardware long-press trigger)

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

Let a person choose Stash as their phone's default digital assistant, so a long-press of the side or power key (and a headset's assistant gesture) starts a note from a locked phone in one action.

## Depends on

01, 12. **Do not start unless** the experiment log shows the assistant route passed on at least one phone **and** the owner answered Q1 in `docs/QUESTIONS.md` with "yes" for at least one person.

## Context

Android lets the user pick one "digital assistant app". The system starts that app's `VoiceInteractionService` on the assistant gesture: long-press of the side key on Samsung (when the side key's long-press is set to "Digital assistant"), long-press of the power key on Motorola (when enabled under Gestures), a swipe from a bottom corner, and the long-press on many Bluetooth headsets. It can be invoked on the lock screen (`onLaunchVoiceAssistFromKeyguard`). Google documents that a service started by the app providing the `VoiceInteractionService` may use the microphone even though the app was in the background.

The cost, which the user must be told plainly: this **replaces Gemini** (or Bixby) as what that gesture opens, and "Hey Google" may stop working. Gemini stays installed as an ordinary app. One report says a third-party assistant can lose the role after an update; the app must notice and say so.

Spike 01's TriggerLab has a working minimal implementation; reuse its manifest declarations and the menu paths it recorded.

## Scope

1. `triggers/StashVoiceInteractionService`, `StashSessionService` and a stub `RecognitionService`, with the XML metadata the role requires. On invocation (locked or not) start capture: prefer launching `CaptureActivity`; if the log says the session window or the service route worked better on a phone, do that there.
2. From the lock screen, never show stored notes. The assistant gesture always means "add a note".
3. When the phone is **unlocked**, the session shows a small sheet with two large buttons, "Add a note" (default, auto-selected after 1.5 s of no touch, with the start tone) and "Find something" (goes to the ask flow). A setting chooses whether to skip the sheet and always capture.
4. Settings page "Use the side button": explains the trade-off in three short sentences, a button that opens the system's assistant picker (the intent the spike found to work), and per-manufacturer steps for the hardware key (from the log). Shows current state: "Stash is your assistant" / "Not set".
5. On each app start and each capture: check whether the role is still held if the user had turned this on (`RoleManager.isRoleHeld(ROLE_ASSISTANT)`); if lost, show a quiet in-app banner with a one-tap fix. Never nag by notification.
6. Tag notes with `trigger = "assistant"`.

## Out of scope

Hotword ("Hey Stash"): not available to third-party apps. Answering general questions like a real assistant. Handing unknown requests on to Gemini.

## Acceptance checks

- **Unit (Robolectric):** role-lost banner logic; the unlocked sheet's auto-select timer; locked invocation never routes to the ask flow.
- **Real phone, S25 Edge:** set as assistant; lock; long-press the side key → start tone within a second, speak, two tones, phone still locked. Repeat 10 times; log successes and the delay.
- **Real phone, razr ultra / Flip 7:** same with their keys, open and closed, if the log said the route works there.
- **Real phone, Bluetooth earbuds:** the earbud assistant gesture starts a note with tones in the earbuds, if the log said so.
- **Real phone:** install an update over the top (`adb install -r`) and confirm the role is still held; reboot and confirm again. Log both.
- **Real phone:** switch the assistant back to Gemini in system settings; Gemini works as before, and Stash shows "Not set" without complaint.

## Notes and risks

- The role is per phone and entirely the user's choice. The parents may well prefer to keep Gemini; the app must be complete without this card.
- If Samsung shows Bixby or an "assistant" chooser on long-press even after the role is set, record the menu path that fixes it.
- Keep this code isolated in `triggers/`; nothing else may depend on the app being the assistant.
