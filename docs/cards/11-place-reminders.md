# 11 ◆ · Place-aware reminders (optional, after the basics)

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

"Buy ground beef next time I'm at Costco" shows up when he is at Costco: first without any background location permission, then, if he opts in, as a notification on arrival.

## Depends on

05, 06. Build only when the owner asks for it; cards 01–10 do not need it.

## Context

Tasks can carry a place (`task.at_place_text`, and `at_place_subject_id` once the subject is resolved). Today they are listed under "At a place" on the "To handle" screen and answered by "what do I need at Costco?". Nothing knows where Costco *is*.

The first design worked this out in detail; it still holds. Read these archived documents and cards before building, and follow them where this card is silent:

- `docs/archive/DESIGN-v1-2026-11-10.md`, section 8, "Place-aware reminders with minimum location access".
- `docs/cards/archive/05-spike-geofence.md` (a week-long reliability test), `14-capture-location-places.md`, `23-place-reminders.md`.

Their table and class names predate the new data model: where they say `place` and `place_location`, use `subject` (type `PLACE`) and `place_location(subject_id, …)`; where they say `reminder` with `trigger = PLACE`, use a `task` with `at_place_subject_id`.

Privacy rules: coordinates never leave the phone; no reverse geocoding (a place gets its name from what he said, and its location from where he was). Background location ("Allow all the time") is asked for only if he turns on arrival notifications, with one screen explaining why.

## Scope

### Stage 1: no new permission

1. A place gets a location in two ways: **"I'm here now"** on the place's subject page (stores the current fix as a `place_location`, radius 150 m); and **learned**: when a task at that place is marked done and the phone has a fresh fix, store it (`source = LEARNED`) after the second time it lands within 300 m of the first.
2. Whenever a note is captured or the app is opened with location permission, compare the one fix the app already takes with places that have open tasks. Within the radius → a card at the top of the main screen: "You're at Costco: ground beef, AA batteries", with Done per item. No notification.
3. "What do I need here?" in the router (card 08) uses the same comparison.

### Stage 2: arrival notifications (opt-in)

4. Run the archived geofence spike (card 05 in the archive) on the S25 Edge for a week first and log the result. If fewer than 9 arrivals in 10 are notified within 5 minutes with the recommended battery settings, stop at stage 1.
5. A setting "Tell me when I arrive" → explains, requests background location, registers Play services geofences **only** for places that currently have an open task, removes them when the last task there is done or expires; re-registers at boot, after app update and daily.
6. Arrival → a notification through card 06's `Notifier` on its own channel: public version "A reminder for where you are" (no place name, no items); private version lists the items with Done. At most once per place per 4 hours. It is an *event* notification under card 06's budget (at most 2 event notifications a day, 30 minutes apart, 4 of all kinds).
7. If background location is later revoked, fall back to stage 1 silently and show a line in Settings.

## Out of scope

Leaving-a-place reminders. Address search. A map. Other phones.

## Acceptance checks

- **Unit:** distance and radius matching; the learn-after-two rule; which places get geofences for a given set of tasks; the 4-hour spacing; the event budget.
- **Unit (Robolectric):** the arrival notification's public version contains no place name or item.
- **Real phone, S25 Edge, stage 1:** mark "I'm here now" at home for a test place with one task; reopen the app there: the card appears; elsewhere it does not.
- **Real phone, stage 2:** the spike's week, then a week of real use; log arrivals made versus notified and the delay.

## Notes and risks

- Cards 17 and 18 (trips, departure signals) share this card's place locations and its battery-management risk. If 18's stage 2 week has been run, read its log first: it says how this phone treats background signals.

- Samsung's battery management is the main risk; the archived spike card says how to measure it.
- Chains have many branches: allow several `place_location` rows per subject.
- Adding Play services Location is a new dependency; say so in the hand-back.
