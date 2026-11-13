# 18 · The leaving checklist and departure signals

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

When he is about to leave, one short reminder of what to take ("Wallet, keys, reading glasses"), fitted to where he is going or which car he is taking; and trips that are noticed even when he makes no note. Never more than one notification per departure, and it stops by itself if he ignores it.

## Depends on

06 (the notifier and the budget), 17 (trips). Stage 2 needs a week of measurement on the phone.

## Context

This is the part of the trips idea most likely to become a nag (`docs/RISKS.md` R14), so it is built in stages and each automatic signal must earn its place by measurement.

What each way of noticing a departure needs and costs. Android facts are from Google's pages as read by a research helper on 2026-11-11; **none has been tried on this phone**.

| Signal | Permission | Battery | How early | Reliability and false triggers |
| --- | --- | --- | --- | --- |
| **He says so or taps "Start a trip"** | none | none | at the door | Exact. He must remember to do it. |
| **The car's Bluetooth connects** (also says which car) | Nearby devices (`BLUETOOTH_CONNECT`) | negligible | in the driveway; he can still go back in | Connect broadcasts are among the few Android still delivers to an app that is not running, though Google advises against leaning on them and Samsung may hold them for a "sleeping" app. False triggers: sitting in the parked car; another driver's phone also connects. Misses: walking, a lift in someone else's car. A sturdier variant is Android's companion-device pairing, which wakes the app when the paired device appears. |
| **Home Wi-Fi drops** | none if only "a Wi-Fi network was lost" is used; precise location (and location switched on) to read the network's name | low, but needs the app alive (a callback ends with the process) | 30–100 m down the road: **too late for "take your wallet"** | False triggers: router restarts, Wi-Fi switched off, the far end of the garden. |
| **Leaving a circle around home (geofence)** | location "all the time" | low to moderate | later still: Google gives 2–3 minutes typical delay in the background, up to 6 when the phone has been still, and a minimum useful radius of 100–150 m | Must be re-registered after a restart. Samsung battery management is the known hazard (archived spike card 05). |
| **Activity change to "in a vehicle"** | physical activity | low | after driving has begun | Delay varies by phone. False triggers: a bus, a train, being a passenger. Does not know where home is. |

Conclusions: only the first two are early enough for a checklist. Wi-Fi, geofence and activity are useful only for *recording that a trip began* when there was no note. Start with manual and car Bluetooth; add one more only if the log shows trips being missed.

The owner's idea of asking "Track this trip? Yes/No" each time is not built as a question: answering it every time he leaves is itself a nag. A trip is started quietly and thrown away if it comes to nothing (QUESTIONS.md Q30).

## Scope

### Stage 1: the checklist, on a manual start (no new permission)

1. Table `checklist_item` (migration and test): `id`, `text`, `scope` (`ALWAYS`, `VEHICLE`, `PLACE`), `scope_subject_id`?, `active`, `sort`, `created_at`, `updated_at`.
2. Screen **Leaving list** (behind the gate): add, reorder, switch off; pick "always", a car, or a place for each. Seed nothing; he writes it.
3. `domain/LeavingList.kt` (pure): given the items, the destination if known and the vehicle if known → the lines to show: `ALWAYS` items; items for that vehicle; items for that place; and one count line, "2 things to get at Costco", from open tasks at that place. Destination comes from "I'm heading to Costco" (`place` on the note that set `outing.leaving`), vehicle from the Bluetooth signal or his words.
4. When a trip starts by his action (voice or the Start a trip button): post **one** notification on a new channel "Leaving". Text: the items, comma-separated. If there are no items and no count line, post nothing.
5. **Lock screen:** checklist items are shown (they are labels he wrote for exactly this, not notes); the tasks line is always a count only. A setting hides the items ("Heading out? Your list is ready"). QUESTIONS.md Q32.
6. **Budget** (extends card 06's planner): a leaving reminder is an *event* notification. At most one per trip, at most two a day, none within 30 minutes of the last one, never in quiet hours. It does not use up the daily digest's allowance, and the total for a day is still capped at four (card 06). Dismissed or untouched five departures running → the leaving reminder pauses itself and the weekly review says so.
7. `nudge_log` type `LEAVING`; outcomes as usual.

### Stage 2: one automatic signal at a time, each behind its own capability

8. `DepartureSignal` interface (`start()`, `stop()`, emits `Departed(source, at, vehicleSubjectId?)` and `Arrived(source, at)`), with a do-nothing version. `TripStarter` listens: on `Departed` with no active trip → start a provisional trip (`start_source` = the signal), post the leaving reminder if stage 1 would. A provisional trip that ends within 10 minutes with no note is discarded silently.
9. **`DEPARTURE_BLUETOOTH`:** Settings lists paired devices; he ticks his cars and names each (creating a vehicle subject). A manifest receiver for the connect and disconnect broadcasts, filtered to those addresses. Connect at home (within the home radius by last known position, or position unknown) → `Departed` with the vehicle. Disconnect away from home is a stop boundary; disconnect at home → `Arrived`.
10. **`DEPARTURE_WIFI`** (only if the log shows missed trips): he marks the current network as home once, while connected. Loss of that network for more than 3 minutes → `Departed`; rejoining → `Arrived`. Used to *record* trips, not to remind. Needs the process alive; note in the log how often it was not.
11. **`DEPARTURE_GEOFENCE`**, **`DEPARTURE_ACTIVITY`:** not built in this card. They are listed so the interface has room for them; build one only after stage 2's log says Bluetooth and Wi-Fi are not enough, and run the archived geofence spike first.
12. Every signal event is written to `app_event` (source, code, no names), so a week of real departures can be compared with what the app noticed.

### Stage 3: suggestions (small, optional)

13. When an item he places is of `DAILY` volatility and he has asked where it is three or more times, add one attention item: "Add 'reading glasses' to your leaving list?".

## Out of scope

Asking "Track this trip?" (see Context). Arrival reminders at shops (card 11). Speaking the list aloud. Any model involvement. Reminders when leaving places other than home.

## Acceptance checks

- **Unit:** `LeavingList` for: always only; a car; a place with open tasks; nothing → no notification.
- **Unit:** the budget rules in item 6, in card 06's simulated-month test, with three departures in a day and a week of ignored reminders.
- **Unit:** provisional trip discarded after a short connect with no note; connect away from home does not start a trip.
- **Unit (Robolectric):** the public notification never contains a task title.
- **Real phone, stage 1:** put three items on the list; tap Start a trip with the phone then locked: one notification, items readable on the lock screen. Turn the setting off: generic text.
- **Real phone, stage 2, one week:** each time you leave home, jot the time and how (car, on foot). Afterwards compare with `peek.py events`: departures noticed versus made, how long after getting in the car the reminder appeared, false starts. Write the table in `docs/EXPERIMENT_LOG.md`. **Pass:** at least 8 in 10 car departures noticed within a minute of the car connecting, and no more than one false reminder in the week. **Fail:** leave the capability off and keep stage 1.
- **Owner, after two weeks:** one line in the log: helpful / ignored / annoying.

## Notes and risks

- Set the app's battery use to "Unrestricted" before the stage 2 week, and record that it was.
- Bluetooth permission is asked for only when he opens the car list.
- If the phone connects to the car while he is merely unloading shopping, the "within 30 minutes" rule and the provisional-trip discard are what keep it quiet; watch for this in the week's log.
