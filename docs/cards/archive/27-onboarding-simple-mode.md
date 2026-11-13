# 27 · First run, simple mode and the accessibility pass

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

Someone who has never seen the app can set it up in a couple of minutes, with each permission asked at a sensible moment in plain words; the owner's parents get a pared-down mode; and every screen passes a large-text and TalkBack check.

## Depends on

15, 18, 20, 22 (and 16, 21, 23, 25 if they are built: their set-up steps are folded in here).

## Context

By now the app can capture from several triggers, list and fix notes, answer questions, and remind. Each feature brought its own permission or setting. Three people will use it; two of them will have it set up for them by the owner, and the parents (older, Galaxy Z Flip 7) should then never need the settings screen. "It just works" is a requirement. The exact per-phone steps for triggers and battery settings were recorded in `docs/EXPERIMENT_LOG.md` by the spikes; use those words.

## Scope

1. **First run**, one idea per screen, big Next button, skippable, re-runnable from settings:
   1. "This remembers what you tell it." Two example sentences. Or **Restore from a backup** (if card 25 exists).
   2. Microphone: explain → system prompt. If denied, the app still opens and says how to allow it later.
   3. Try it: an in-app capture ("Say: I'm putting my keys on the hook by the door") → shows the result → then asks the matching question and plays the answer. This doubles as a working check of speech on this phone.
   4. Screen lock check: if the phone has no secure lock, explain that one is needed to read notes.
   5. Notifications: explain (reminders, and the add-a-note button on the lock screen) → prompt.
   6. Location (optional): "So I can remember where you were" → while-in-use prompt. No background location here.
   7. "Ways to add a note without opening the app": the trigger that the log says is best for this phone model first, with pictures or exact menu words; a button to add the tile (`StatusBarManager.requestAddTileService`) and to pin the widget (`AppWidgetManager.requestPinAppWidget`). The assistant-role option (card 16) only if built and only as an explicit choice with its trade-off.
   8. Backup (if card 25 exists): set it up now or "Remind me later" (a Home row keeps reminding).
   9. Done: "Press <their trigger> and say something."
2. **Set-up for someone else** (settings → "Set up for a family member", or chosen on the first screen): the same steps in an order suited to the owner doing it on their phone, plus: turn on simple mode; choose the app-lock grace (owner's decision, QUESTIONS.md Q5); scan the API key QR (if card 21 exists); set up backup and show the recovery code to the *owner* to keep; and a final checklist screen showing a green tick or a fix button for each of: microphone, notifications, screen lock, trigger tested, backup tested, battery setting (only if place reminders are on).
3. **Simple mode** (a setting; default off; on for the parents):
   - Home is two very large buttons (**Remember something**, **Find something**) and, below, "Things to check" only when non-empty, and at most three recent notes.
   - Capture reads back a short confirmation after saving ("Saved: reading glasses, nightstand drawer") using on-device text to speech, when the phone is not on silent.
   - Settings collapse to five rows: text size helper (opens system display settings), speak answers, ways to add a note, backup status, "More settings" (the full list).
   - Destructive actions use press-and-hold instead of typing.
   - No filter chips, no kind pickers in the editor (text edit, "This is wrong, delete it", and "Looks right" only).
4. **Read-back option** outside simple mode too (default off).
5. **Health check** row on Home (both modes) that appears only when something needs attention: microphone permission lost, notifications off, assistant role lost, backup overdue, geofence permission revoked, speech engine failed to load. One tap to fix each.
6. **Accessibility pass over every screen built so far:** font scale 200% and display size maximum with no clipping or overlap; every control ≥ 56 dp with a content description; headings marked for TalkBack; no colour-only meaning; contrast ≥ 4.5:1 in both themes; no timed UI that disappears faster than 8 seconds (the capture Undo already is 8 s; make it stay until dismissed when TalkBack is on); animations respect "remove animations".
7. A one-page printable "How to use it" (a string resource rendered to the print framework) with the person's own trigger, for the parents' fridge.

## Out of scope

Localisation into other languages (strings are already in resources; translating is later), tutorials beyond the try-it step, any account or sign-in.

## Acceptance checks

- **Unit (Robolectric/Compose):** onboarding can be completed with every permission denied and the app remains usable for what is left; simple mode hides the listed controls; health-check rows appear for each simulated fault and disappear when fixed.
- **Compose screenshot or semantics tests:** each screen at font scale 2.0 has no node clipped (use `assertIsDisplayed` on key nodes at a small screen size) and every clickable node has a label and a 56 dp minimum size.
- **Real phone, S25 Edge:** fresh install → first run → first real note from the lock screen in under 3 minutes (time it; log it).
- **Real phone, Z Flip 7, with one of the parents if possible:** the owner runs "set up for a family member"; then the parent, unaided, adds a note with the phone closed and later finds it. Write down every hesitation in `docs/EXPERIMENT_LOG.md`; each is a bug.
- **Real phone, razr ultra:** same with the owner's wife.
- **Real phone, TalkBack on:** complete capture and ask without sight.

## Notes and risks

- Android will not show a permission prompt twice after two denials; the "how to allow it later" text must point to the app's system settings page with a button.
- Do not front-load permissions. Anything not needed for the first note waits until its feature is first used.
- The usability session with a parent is the single most valuable check in the project; do not skip it because the unit tests pass.
