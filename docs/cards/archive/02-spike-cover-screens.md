# 02 · Spike: capture and ask from a closed flip phone

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

Find out what can be done on the **Galaxy Z Flip 7 cover screen** and the **razr ultra 2025 external display** with the phone closed: can a note be started, and can a question be answered after a fingerprint?

## Depends on

01 (reuses TriggerLab).

## Context

Two of the three users have flip phones and will often have them closed. Reported behaviour, not verified: the razr's external display can run ordinary apps the user allows (Settings → External display); the Flip 7 cover screen ("FlexWindow") shows notifications and some third-party widgets, and runs full apps only if Samsung's Good Lock **MultiStar** module is installed and the app is allowed. Whether a third-party default assistant appears on either cover screen is unknown.

A widget tap and a notification action are both documented ways to start a microphone foreground service without showing an activity, so they are the likely cover-screen routes on the Flip.

## Scope

Add to TriggerLab:

1. A Glance widget in two sizes (2×2 and 4×2) with one large button "Add a note" that starts `LabCaptureService`. Declare it so it is eligible wherever the phone allows widgets (home screen `home_screen`; also `keyguard` in `widgetCategory`).
2. A second activity `LabAskActivity`: `showWhenLocked`; on start calls `KeyguardManager.requestDismissKeyguard`, and once dismissed shows "Unlocked: would answer here" and speaks it with `TextToSpeech`. This stands in for the real ask flow.
3. Make sure both activities declare they are resizeable and have no orientation lock, so a small display accepts them.

Run and log under "Cover screens" in `docs/EXPERIMENT_LOG.md`:

| # | Phone closed | Try |
| --- | --- | --- |
| 1 | Flip 7 | Add the TriggerLab widget to the cover screen (Settings → Cover screen → Widgets). Is it offered? Does a tap record real audio with both cues? |
| 2 | Flip 7 | Notification action "Record (service)" from the cover screen notification list |
| 3 | Flip 7 | Side key long-press with TriggerLab as default assistant |
| 4 | Flip 7 | With Good Lock MultiStar: allow TriggerLab on the cover screen; launch the capture activity; launch `LabAskActivity` and unlock with the side fingerprint sensor |
| 5 | Flip 7 | Without MultiStar: does anything launch `LabAskActivity` on the cover screen? |
| 6 | razr ultra | Allow TriggerLab on the external display; launcher icon → capture; `LabAskActivity` with fingerprint |
| 7 | razr ultra | Widget or panel on the external display, if third-party widgets are offered |
| 8 | razr ultra | Notification action from the external display |
| 9 | razr ultra | Power long-press (and AI key) with TriggerLab as default assistant, phone closed |
| 10 | Both | Microphone quality closed: is the played-back recording as clear as when open? |
| 11 | Both | Text size: is a 24 sp label and a 72 dp button usable on the cover screen? |

## Out of scope

Anything on the main screen (card 01), real speech to text, real answers.

## Acceptance checks

- **Real phone, Z Flip 7 and razr ultra 2025:** each row logged with a status mark.
- **Pass:** on each flip phone, at least one way to start a real recording closed, and at least one way to reach an unlocked "answer" screen closed.
- **Partial:** capture passes, ask fails → the design stands; answers on that phone need it opened. Record it so onboarding says so.
- **Fail:** no closed capture on a phone → that person opens the phone to add a note; tell the owner.
- No unit tests.

## Notes and risks

- MultiStar is an extra install from the Galaxy Store and Samsung changes it between One UI versions. Prefer a route that does not need it for the parents.
- If the Flip does not offer the widget for the cover screen, try a simpler widget layout (a single `RemoteViews` button, no Glance) before recording a fail; Samsung is reported to accept only some widget shapes.
