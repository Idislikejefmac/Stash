# 14 · Location at capture, and saved places

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

Stamp each note with where the phone was, attach a saved place name when the phone is inside one, and let the user create places with "I'm here now". No coordinates ever leave the phone.

## Depends on

08, 12.

## Context

Each note has optional `lat`, `lon`, `accuracy_m`, `fix_age_s` and `captured_place_id`. Places (`place`, `place_location`) are things the user names: "home", "Costco", "the cabin". A place can have several locations (a chain has many branches). The app does **not** reverse-geocode (that would send coordinates to Google); a place name is found by comparing coordinates with saved places on the phone.

Capture happens in a visible activity or a user-started foreground service, so the ordinary "while using the app" location permission is enough. Do not request background location in this card (card 23 does, and only if the user turns on arrival reminders).

Use Google Play services `FusedLocationProviderClient` (already on all three phones): `getCurrentLocation` with balanced-power-or-better priority and a short timeout, falling back to `lastLocation`.

## Scope

1. `places/LocationSnapshotter`: `suspend fun snapshot(): LocationSnapshot?` → tries a current fix for up to 5 s; else last known location if younger than 30 minutes; else null. Returns lat, lon, accuracy, age. Never throws; no permission → null.
2. Capture integration: start the snapshot when listening starts (in parallel with recording), attach whatever has arrived by the time the note is saved; if the fix arrives after saving, update the note within 10 s, then give up. Capture is never delayed by location.
3. `CaptureService` adds foreground-service type `location` alongside `microphone` only if the permission is granted.
4. `places/PlaceMatcher` (pure Kotlin): given a point and all `place_location` rows, return the place whose circle contains the point (distance ≤ radius + min(accuracy, 100 m)); nearest centre wins on overlap. Haversine distance.
5. After structuring: if the note names a place (`placeName`) that has no location yet and the note was captured with a good fix (accuracy ≤ 100 m), do **not** assume the user is there. Only "I'm here now" or a later learning step sets a location.
6. Places screen (behind the app lock placeholder; card 17 adds the real gate): list places with their number of locations and active errands; "Add a place here" (name + current fix, default radius 150 m, slider 100–500 m); rename; delete a location; merge two places.
7. "Find by address" on the same screen, clearly labelled "This sends what you type to Google's address search": uses `android.location.Geocoder`. Because this is a network use outside `model.hosted`, wrap it in `places/AddressSearch` and add that one class to the allow-list of `NetworkBoundaryTest` with a comment. It sends only the typed text.
8. Permission request: asked the first time the user opens the Places screen or in onboarding (card 27), with one sentence: "So I can remember where you were when you told me something." Declining is fine everywhere.
9. A setting "Remember where I was for each note" (default on). Off → no location is ever read.

## Out of scope

Geofences and arrival notifications (23), maps (no map SDK in this app), learning a place's location from behaviour (23), showing location in answers (19).

## Acceptance checks

- **Unit (JVM):** `PlaceMatcher`: inside, outside, on the edge with poor accuracy, overlapping circles, a place with two locations. Snapshot policy with a fake provider: fresh fix; timeout then last-known; too old → null; no permission → null.
- **Unit:** capture with a fake snapshotter that responds after the save still updates the note; one that never responds leaves it null without delaying the save cue.
- **Real phone, S25 Edge:** capture outdoors → note has coordinates with plausible accuracy. Capture in a basement or with location off → note saved with no coordinates, no delay, no error. Create "home" with "I'm here now"; a new note captured at home shows the place name in the debug dump.
- **Real phone:** with "Remember where I was" off, Android's location indicator does not appear during capture.

## Notes and risks

- Android 17 shows a location indicator when an app reads location; that is expected during capture.
- On the lock screen, the first-ever permission prompt cannot be answered; permissions must be granted once from inside the app. Capture must work without them.
- Precise vs approximate: if the user grants only approximate location, store it with the reported (large) accuracy; `PlaceMatcher`'s accuracy allowance is capped so approximate fixes rarely match a place, which is the right behaviour.
