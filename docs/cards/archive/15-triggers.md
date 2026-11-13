# 15 · Triggers: notification action, tile, widgets, shortcuts

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

The everyday ways to start a note without opening the app, built from whatever spikes 01 and 02 proved on each phone.

## Depends on

01, 02, 12. **Read the "Triggers from a locked phone" and "Cover screens" sections of `docs/EXPERIMENT_LOG.md` before starting**; build only what passed on at least one phone, and skip (with a line in the hand-back) what failed everywhere.

## Context

`CaptureActivity` (shown over the lock screen) and `CaptureService` (foreground service, microphone type) exist from card 12. A trigger is just a way to start one of them. Android permits starting the microphone service directly from a **widget tap** or a **notification action**; everything else should launch the activity.

Expected results going in (the log overrides this): the notification action works from the lock screen on all three phones; the widget works on the home screen everywhere and on the Flip 7 cover screen and razr external display; the quick-settings tile works from the locked shade; Samsung's side-key double-press and lock-screen shortcuts can open an app only after unlocking.

## Scope

1. **Ongoing notification** ("Stash is ready · Add a note"): low-importance channel, no sound, `VISIBILITY_PUBLIC`, one action button "Add a note" → `PendingIntent.getForegroundService` to `CaptureService` (or to the activity, if the log says that variant behaved better on a phone). Posted from `Application.onCreate`, after boot (`BOOT_COMPLETED` receiver), after app update, and re-posted if the user swipes it away while the setting is on. Setting "Show the add-a-note notification" (default on). Needs `POST_NOTIFICATIONS`; ask in context.
2. **Quick-settings tile** "Add a note" → launches `CaptureActivity` with `startActivityAndCollapse(PendingIntent)`. No `unlockAndRun`.
3. **Widgets** (Glance): a 1×1/2×2 "Add a note" button widget that starts `CaptureService`; a 4×2 widget with two large buttons, "Add a note" and "Find something" (the second opens the ask screen, which will require unlock; until card 20 exists it opens the app). Declare the widget so cover screens and lock screens may host it where the phone allows. No note content is ever shown on a widget.
4. **Launcher shortcuts** (static): "Add a note", "Find something".
5. **A separately named launcher alias** "Add a note" (`activity-alias` targeting `CaptureActivity`, own icon) so Samsung's side-key "Open app", lock-screen shortcut picker, Good Lock RegiStar and Moto Quick Launch can be pointed at capture rather than at the app's home. Keep it only if the log shows at least one of those worked; a second icon in the app drawer is otherwise clutter.
6. A "Ways to add a note" settings page listing each trigger with a one-line how-to for *this* phone's manufacturer (`Build.MANUFACTURER`), using the exact menu paths recorded in the log.
7. Every entry point passes a `trigger` string; store it on the note (`note.trigger`, nullable text column: add `Migration` and test) so the owner can later see which triggers people really use.

## Out of scope

The assistant role (16), NFC, shake, headset media keys, wake word (all ruled out or later; see FEATURES.md). Do not add any always-running service.

## Acceptance checks

- **Unit (JVM/Robolectric):** the boot receiver re-posts the notification only when the setting is on; each entry point builds the expected intent with its trigger tag; migration test.
- **Real phone, S25 Edge, locked:** notification action → tones → note saved, phone still locked. Tile from the locked shade → same. Results logged.
- **Real phone, Z Flip 7, closed:** cover-screen widget tap → tones → note saved (or whichever route spike 02 found). Logged.
- **Real phone, razr ultra, closed:** external-display route from spike 02 → note saved. Logged.
- **Real phone, each:** after a reboot, without opening the app, the notification is back and works.
- **All phones:** no widget, tile or notification ever displays note text.

## Notes and risks

- Starting with Android 14 users can swipe away "ongoing" notifications; re-post politely (not within the same minute) and never when the setting is off.
- Some cover screens show only notifications from apps the user has allowed there; put that step in the how-to page if spike 02 needed it.
- If the notification-to-service path gives silent audio on some phone but notification-to-activity works, use the activity there and write it in the log.
