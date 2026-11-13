# 23 · Place reminders: "next time I'm at Costco"

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

Tell the user what they wanted at a place when they get there: always when they open the app or add a note there, and, if they opt in to background location, by a notification on arrival.

## Depends on

05 (read its results in `docs/EXPERIMENT_LOG.md`, "Place reminders and battery management"), 14, 22.

## Context

Errands with a place create `reminder` rows with `trigger = PLACE` and a `place_id` (card 11). Places have zero or more `place_location` rows (lat, lon, radius; card 14). `NotificationBudget` and `Notifier` exist (card 22).

Two modes:

- **Opportunistic (no extra permission):** whenever the app takes a location fix anyway (app opened, note captured, question asked), compare it with places that have active errands. Works with "while using the app" location.
- **Arrival (opt-in):** Google Play services geofences, which need "Allow all the time" location. Android makes this a second, separate request that sends the user to Settings, and periodically reminds users the app has it. Geofences are lost on reboot and sometimes on app update or when Play services is reset; they must be re-registered. Spike 05 recorded how reliable delivery was on each phone and which battery settings it needed. If spike 05 failed on a phone, build only the opportunistic mode for it (keep the code, hide the opt-in on that manufacturer, and say why in settings).

## Scope

1. **A place with no location yet.** When an errand names a place that has no `place_location`: the reminder is created anyway; the Reminders screen and the note show "I don't know where Costco is yet" with the options **I'm there now** and **Find by address** (card 14's screens). Also: when the user marks such an errand **Done** and a good fix (≤ 100 m) is available at that moment, offer "Is this Costco? Remember this spot" (one tap; `source = LEARNED`).
2. **Opportunistic check** `places/HereCheck`: given a snapshot, find matching places (`PlaceMatcher`) with active, unsnoozed place reminders. In the app: a card at the top of Home ("You're at Costco: ground beef, AA batteries" with Done buttons). After a capture from a locked phone: a notification through `Notifier`/`NotificationBudget` instead (public text "A reminder for Costco").
3. **Arrival mode opt-in flow** (from the first place reminder that has a location, and from settings): one plain screen: what it does, that Android will show "Allow all the time", that it is used only to notice arrival at places with something on the list, and that it can be turned off any time. Then the fine-location request if needed, then the background-location request. On Samsung, a second step (only if spike 05 showed it was needed): set the app to "Never sleeping" / battery "Unrestricted", with a button that opens the right settings page and the exact wording from the log.
4. `places/GeofenceRegistrar`: desired set = every `place_location` of every place that has at least one `ACTIVE`, unsnoozed `PLACE` reminder (cap 80; Play services allows 100 per app). Register `ENTER` and `DWELL` (3-minute loitering) or whatever spike 05 found works; radius = stored radius, minimum 150 m. Reconcile (add/remove the difference) whenever reminders or places change, at boot, after app update, and in a daily worker. Remove all when the user turns the mode off or revokes the permission (detect on app start).
5. `places/GeofenceReceiver`: on a transition, look up the place and its active reminders, pass through `NotificationBudget` (once per place per 4 hours), notify: public "A reminder for Costco", private "At Costco: ground beef, AA batteries". Actions: **Done** per item when there is one item, otherwise **Open list**; **Not now** (snooze until the next arrival after leaving).
6. When the last active reminder for a place is done or expires, its geofences are removed at the next reconcile, so background location is used only while something is pending. If no place has pending reminders, no geofence exists at all.
7. Settings: "Tell me when I arrive" switch with its current state in words ("On: watching 2 places" / "Off" / "Needs 'Allow all the time'"); per-place switch.

## Out of scope

Leaving-a-place reminders, time-at-place rules, maps, tracking or location history (the app stores only the fix at capture and the places the user named).

## Acceptance checks

- **Unit (JVM):** desired-set computation (places without locations excluded; done/expired/snoozed excluded; cap); reconcile produces the right adds and removes; `HereCheck` matches and respects snooze; receiver logic with the budget (second arrival within 4 hours is silent); "Not now" suppresses until an exit then enter.
- **Unit (Robolectric):** permission states map to the right settings wording; revoking background location removes geofences and flips the switch off.
- **Real phone, S25 Edge:** say "next time I'm at <a nearby shop> buy batteries"; set the place with "I'm there now" on a first visit; leave; return another day without opening the app → notification within about 5 minutes of arriving; lock screen shows the generic text. Mark Done → the debug screen shows zero geofences registered.
- **Real phone:** opportunistic mode with background location denied: opening the app at the place shows the card.
- **Real phone:** reboot, do not open the app, arrive → still notified.
- **Real phone, razr ultra and Flip 7 (when available):** one arrival each, logged with the delay.
- All results in `docs/EXPERIMENT_LOG.md`.

## Notes and risks

- A geofence broadcast received while the phone is locked can read the database in version 1 (the key works while locked); the notification's lock-screen text stays generic regardless.
- Chains: "Costco" with two saved branches gets two geofences and one list.
- Driving past a large radius can trigger "enter"; dwell avoids most of it. If spike 05 found dwell unreliable, on "enter" schedule a re-check with a fresh fix after 3 minutes (a short `WorkManager` job) and notify only if still inside.
- Do not ask for background location during first-run onboarding; ask when it first becomes useful.
