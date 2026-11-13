# 05 · Spike: do place reminders survive real life on these phones

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

Find out whether a geofence arrival reliably produces a notification on the Samsung and Motorola phones after days of the app not being opened, and what settings that requires.

## Depends on

None (can run in the background of other work; it needs a week of ordinary life).

## Context

"Next time I'm at Costco" depends on Google Play services geofencing (`GeofencingClient`), which needs the "Allow all the time" location permission and delivers a broadcast to the app when the phone enters a circle. Known behaviour: geofences are removed at reboot, when Play services data is cleared and sometimes at app update, and must be registered again; background delivery can lag by a couple of minutes; small circles are unreliable (use 150 m or more). Samsung's battery manager can put an unused app to sleep, which may stop the broadcast arriving. None of this has been checked on One UI 8/9 or on the razr.

Throwaway code in `spikes/geolab/` (applicationId `app.stash.geolab`).

## Scope

1. An app that: asks for fine location, then background location, then notification permission; has a button "Add a place here" (name + current fix + radius 150 m); lists places; registers one geofence per place with `GEOFENCE_TRANSITION_ENTER` and `DWELL` (loitering delay 3 minutes) through a `PendingIntent` to a `BroadcastReceiver`.
2. The receiver posts a notification "Arrived: <place> (enter|dwell)" and appends a line to a log file: time, place, transition, the triggering location's accuracy.
3. Re-registers all geofences on `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`, and from a daily `WorkManager` job. Logs each re-registration.
4. A log screen with "copy all".
5. Protocol, one week per phone, at least three places visited at least twice each (home, a shop, one other):
   - Days 1–3: default battery settings; do not open the app.
   - Day 4: reboot the phone; do not open the app.
   - Days 5–7: Samsung only, set the app to "Never sleeping" (Settings → Battery → Background usage limits) and battery "Unrestricted"; Motorola, set battery to "Unrestricted".
   - The user notes each real arrival time (a text message to themselves is enough).
6. Log in `docs/EXPERIMENT_LOG.md` under "Place reminders and battery management": arrivals notified out of arrivals made, typical delay, for each setup; whether the app appeared in Samsung's sleeping lists by itself; battery use reported by the system for the week.

## Out of scope

Reminders, notes, the opportunistic "you're here" card, any UI polish.

## Acceptance checks

- **Real phone, S25 Edge and one of the other two (ideally both):** a week of entries logged.
- **Pass:** with the recommended settings, at least 9 of 10 arrivals notified within 5 minutes, including after the reboot. The log records which settings were needed, because onboarding (card 27) must ask for exactly those.
- **Partial:** works only with "Never sleeping" → acceptable; onboarding must set it.
- **Fail:** misses more than 2 in 10 even with the best settings → place reminders ship as "opportunistic" only (shown when the app is opened or a note is captured at the place) on that phone, and the owner is told.
- No unit tests.

## Notes and risks

- Android shows a system reminder every so often that the app is using location in the background; note when it appears, since the parents will see it.
- Do not use a foreground service to "help" delivery; the design has no permanent service.
- If "dwell" never fires on a phone but "enter" does, record it; card 23 will use enter with its own 3-minute confirmation fix.
