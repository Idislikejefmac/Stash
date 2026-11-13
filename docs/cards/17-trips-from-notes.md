# 17 · Trips built from notes, and "when did I last go out?"

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

The app can say when he last went out, where he stopped, and what he noted there, using only the notes he made while out. No background location, no new permission, no prompt.

Example: at Costco, "I just picked up a bag of nuts, some Alaskan Amber and some Tide; the Tide is on sale for $24.99". Later, "Just bought some jeans at Ross". Later, at Total Wine, a note with a picture. Then home. That is one trip with three stops, a price he can ask about next time, and an answer to "when was the last time I went out?".

## Depends on

01, 03, 13, 08. Better with 04, 07 (prices as readings) and 16 (pictures). Build after the hardening block.

## Context

Each capture already carries its time and, when location is allowed, where the phone was. A note made at Costco therefore already says when and where. A trip is a grouping of such notes. Because it is worked out from captures, a trip is **derived data**: it can be rebuilt, and deleting it loses nothing. It becomes the owner's own (pinned) only when he starts, ends, names or edits it.

Honest limit, to be shown in the answer wording: this knows only about outings on which he made a note. Trips with no notes need a departure signal (card 18).

Privacy: a trip is a location history. It stays on the phone. Times and coordinates are never sent to a model; a trip may appear in a tool result only as place names he said himself and ages in words.

## Scope

### A. Home

1. Settings → "This is home": stores the current position as a `place_location` for a subject named "home" (type `PLACE`), radius 200 m. Without a home, trips are not built and the Trips screen says why.

### B. Tables (a migration and migration test)

2. `trip`: `id`, `started_at`, `start_estimated` (the true departure is unknown; this is the first note's time), `ended_at`?, `end_estimated`, `state` (`ACTIVE`, `ENDED`, `DISCARDED`), `start_source` (`NOTES`, `MANUAL`, later `BLUETOOTH`, `WIFI`, `GEOFENCE`, `ACTIVITY`), `end_source`?, `vehicle_subject_id`?, `driver_text`?, `pinned`, `created_at`, `updated_at`.
3. `trip_stop`: `id`, `trip_id`, `seq`, `place_subject_id`?, `place_text`?, `arrived_at`, `left_at`?, `lat`?, `lon`?, `name_source` (`SAVED_PLACE`, `SAID`, `NONE`).
4. `capture.trip_id`? and `capture.trip_stop_id`? (nullable, indexed).

### C. The builder (`domain/TripBuilder.kt`, pure; run as an `AfterProjection` hook and by "Rebuild trips")

5. Input: captures in time order with their positions, the home location, saved place locations, each capture's records (for `place` names), and settings. Output: trips and stops.
6. Rules:
   - A capture more than the home radius from home, with no active trip → start a trip at that capture's time (`start_estimated`).
   - A later away capture within **3 hours** of the previous one joins the trip. A longer gap ends the trip at the last capture's time (`end_estimated`) and starts a new one.
   - A capture at home ends the active trip at its time.
   - Stops: consecutive captures within 150 m of each other are one stop. Its name, in order: a saved `place_location` within its radius; else a `place` or `at_place` named in a record of one of its captures ("at Ross"); else none ("a stop", shown with its time).
   - A capture with no position while a trip is active joins it if it is within 30 minutes of the previous capture.
   - With `LOCATION_AT_CAPTURE` off: a trip is built only from captures whose records name a place, grouped by the 3-hour rule. Weaker, and said to be so.
7. Rebuilding replaces unpinned trips only. A pinned trip keeps its stops and attributes; new captures inside its time span are attached.
8. **No reverse geocoding.** A stop gets a name from a saved place or from his words, never from a lookup service.
9. Naming teaches: when a stop is named from his words twice within 300 m, save a `place_location` for that subject (`source = LEARNED`), as card 11 does.

### D. Car and driver

10. Extraction schema version 2 adds one optional top-level object, `outing`: `{ "vehicle": string | null, "driver": string | null, "leaving": boolean | null, "home": boolean | null }`, with one line in the instruction: "If he says which car he is in, who is driving, that he is heading out, or that he is home, fill `outing`." Rules extractor: "(taking|in|driving) the <vehicle>", "<person> is driving / drove", "heading out", "leaving now", "start a trip", "I'm home", "back home".
11. `vehicle` resolves to a subject (type `THING`, tag `vehicle`); `driver` is kept as text ("me" when he says he drove). They are set on the active trip, or on the trip the capture belongs to. `leaving` starts a trip (`start_source = MANUAL`, not estimated, pinned); `home` ends one.
12. Record new responses for the schema change (card 04's recorder) and add six cases to the seed set.

### E. Screens (behind the app gate)

13. **Trips** list: date, duration ("about 2½ hours" when estimated), stops by name, car, and the count of notes and pictures. Tap → the trip: stops in order, each with its notes.
14. Actions: rename a stop (creates or links a place subject), set car and driver, **Not a trip** (discards and pins the decision), **Join with previous**, **End now** on an active trip. On the main screen while a trip is active: one quiet line, "Out since about 2:10 pm · Costco, Ross".
15. A **Start a trip** button on the main screen and in the capture notification's expanded view (an adding action: it records a start time and nothing else).

### F. Questions (router, card 08)

16. Routes, answered by code: "when did I last go out / leave the house", "when was my last trip" → the newest ended or active trip: "The last outing I know about was yesterday, about 2:10 to 4:40 pm: Costco, Ross, Total Wine. You took the Tesla." · "when was I last at P", "when did I last go to P" → the newest stop at that place · "what did I buy / note at P last time" → that stop's records · "what was the price of X last time" is already a reading question (card 07).
17. The wording always says "that I know about" when trips come only from notes.
18. Tool for the loop: `last_trips(place?, last_n)` returning place names he said, ages in words, and note titles; no clock times, no coordinates.

### G. Capability

19. `TRIPS` (needs nothing; better with `LOCATION_AT_CAPTURE`). Off → no trips are built and the screens are hidden.

## Out of scope

Detecting departure or arrival without a note, the leaving checklist, any notification (card 18). Maps. Mileage or routes. Sharing a trip. A model-written summary (the summary line is written by code).

## Acceptance checks

- **Unit:** `TripBuilder` table tests with fixed positions and times: the Costco, Ross, Total Wine afternoon → one trip, three named stops; a note at home ends it; a 4-hour gap splits; captures without position; no home set → nothing; location off → place-name grouping; pinned trip survives a rebuild; "Not a trip" stays discarded.
- **Unit:** `outing` parsing, rules patterns, and projection onto the active trip.
- **Unit:** router patterns with negatives; answer wording for estimated and exact times.
- **Unit:** the `last_trips` tool result contains no digits that are a time or coordinate from the seeded data.
- **Unit:** migration test.
- **Real phone, S25 Edge:** set home. On a real outing make a note at two shops and one at home afterwards. The Trips list shows one trip with two stops; note how each stop was named. Ask aloud "When was the last time I went out?". Say "I'm taking the Tesla" at the start of the next one. Log what was right and wrong, including how far the phone's last known position was from where you stood.

## Notes and risks

- The prototype stores the phone's *last known* position, which can be stale by many minutes. If stops land in the wrong place on the phone check, the cheap fix is to ask for a fresh position in the background after saving (never delaying the save) and update the capture's coordinates once.
- Keep the builder's numbers (200 m, 150 m, 3 hours) as settings in one place.
- If he rarely makes notes while out, this card will show nothing. That is a finding: write it in the log before building card 18.
