# 22 · Reminders, expiry and the notification budget

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

Time reminders that notify once and can be finished or put off; automatic expiry of old errands and reminders; and one rule-keeper that stops the app from becoming annoying.

## Depends on

11, 17, 19.

## Context

The structuring pipeline (card 11) creates `reminder` rows: `trigger = TIME` with `due_at` ("remind me to call the vet in two weeks"), `PLACE` with `place_id` ("buy ground beef at Costco"; arrival notifications are card 23), or `NONE`. Notes have `expires_at` set from per-kind defaults (card 09): errands 45 days (14 if perishable), dated reminders 7 days after due, undated 90 days. Expired things stop reminding and drop out of "what do I need" answers but remain as history.

Reminder text is private. On the lock screen a notification must not show it unless the user chose that.

## Scope

1. `reminders/ReminderScheduler`: for each `ACTIVE` `TIME` reminder, schedule an alarm at `due_at` with `AlarmManager.setAndAllowWhileIdle` (inexact is fine; do not request the exact-alarm permission). Rescheduled on boot, app update, and whenever reminders change. Past-due reminders discovered at startup notify immediately (subject to the budget).
2. `reminders/Notifier`: builds notifications on a "Reminders" channel. Public (lock-screen) version: "A reminder from Stash" (for place reminders: "A reminder for Costco"). Private version: the reminder text. Actions: **Done**, **Later** (snooze: 1 hour for time reminders; "next visit" for place reminders). Setting "Show reminder text on the lock screen" (default off). Tapping opens the note behind the app lock.
3. `reminders/NotificationBudget` (pure Kotlin, clock injected): decides allow/hold for each proposed notification.
   - Quiet hours 21:00–08:00 local (setting): hold until 08:00.
   - At most 3 proactive notifications per day in total, not counting place arrivals; extra ones are held for the next day or folded into the digest (card 28).
   - A given reminder notifies at most 3 times in its life (first, and after two snoozes) unless the user snoozes again by hand.
   - Place arrivals (card 23 will call this): at most once per place per 4 hours.
4. `reminders/ExpiryWorker` (daily, `WorkManager`): marks notes and reminders past `expires_at` as `EXPIRED`; cancels their alarms; never deletes. Also marks `REMINDER` notes `DONE` when their reminder is done.
5. **Reminders screen** (behind the app lock): active reminders and errands grouped by place and by date, each with Done / Later / Edit expiry ("Keep for another month", "Never expire"); a collapsed "Expired recently" section with "Bring back".
6. Settings: per-kind default lifetimes (errand, perishable errand, dated reminder, undated reminder) as simple pickers; quiet hours; daily limit.
7. `POST_NOTIFICATIONS` is requested the first time a reminder is created, with one sentence of explanation.

## Out of scope

Geofences and arrival detection (23), loan nudges (24), the weekly digest (28), recurring reminders (later; see FEATURES.md).

## Acceptance checks

- **Unit (JVM):** budget: quiet hours hold and release; fourth notification in a day is held; arrival spacing; per-reminder cap. Expiry: boundaries for each kind with a fixed clock; expired errand no longer returned by `AskEngine` `WHAT_AT` (re-run that test from card 19). Scheduler: reschedules exactly the active time reminders after "boot".
- **Unit (Robolectric):** the public notification text never contains the reminder text when the setting is off.
- **Real phone, S25 Edge:** "remind me to check the oven in two minutes" (use a debug shortcut for short delays if the rules only parse days) → notification arrives within a few minutes with the phone idle and locked; lock screen shows the generic text; Done removes it and marks the note done; Later brings it back in an hour (shorten in debug).
- **Real phone:** reboot with a pending reminder; it still fires without opening the app.
- **Real phone:** set an errand's expiry to yesterday in the debug screen; next day's worker (or a debug "run now") moves it to Expired, and "what did I need at Costco" no longer mentions it.

## Notes and risks

- Samsung may delay inexact alarms for sleeping apps; minutes of delay are acceptable for this product. If delays exceed half an hour in testing, record it and consider asking for "never sleeping" when reminders are used (card 27 owns that request).
- Keep notification wording calm and short: "Call the vet" not "⏰ REMINDER!!".
