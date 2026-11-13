# Read first: shared context for every card

Revision 3, 2026-11-11. Every card assumes you have read this page and nothing else. If a card and this page disagree, the card wins. If either disagrees with a result in `docs/EXPERIMENT_LOG.md`, the log wins: it records what the real phone did.

**Card numbers are names, not the order.** Revisions 2 and 3 split two cards and added eleven; the existing numbers were kept so that references between cards stay true. The build order is in `README.md`. Retired cards are in `archive/` (see [ARCHIVE.md](ARCHIVE.md)); do not build from one unless a current card sends you there.

## What the app is

Working name **Stash**, package `app.stash`. A private voice inbox for one person on one Android phone (Samsung Galaxy S25 Edge, Android 17). He says almost anything, the app saves it, and he moves on **without checking it**. Later he asks questions or is reminded.

| He says | The app makes |
| --- | --- |
| "I'm putting my reading glasses in the nightstand drawer" | a placement |
| "I loaned my guitar to Frank on January 5th" | a loan |
| "I have to remember to clean out the back of the car soon" | a task, nudged at intervals |
| "Pool test: free chlorine 5, pH 7.2, alkalinity 70" | one reading with three values |
| "I changed the furnace filter today" | an event |
| "The furnace filter is 20 by 25 by 1" | a reference fact |
| "The Tide is on sale for $24.99" (said at Costco) | a reading (a price), with its place; part of a trip |
| a shared screenshot plus "I love Saves the Day; tell me when they play Phoenix" | a note with a picture, and a proposal for a watch |

A **prototype already exists in `app/`** and has real notes on the owner's phone. You are changing a working app, not starting one. Read the code a card names before writing.

## The one idea to hold on to

**Two layers, and a third thing that is also truth.** A **capture** is what he said: the raw words, kept for good, never rewritten. **Records** are what the app worked out from a capture: disposable, re-derivable, possibly wrong. A capture can produce several records. Each run of an extractor over a capture is stored as an **extraction**. What the **owner does** (done, snooze, edit, merge) cannot be rebuilt from the words either, so every such action is written down as a `revision` and **pins** the record it touched; re-reading a capture must not silently replace a pinned record.

Trips built from notes are derived in the same way. Pictures and watch definitions are the owner's and are not.

## Rules that apply to every card

1. **Kotlin, Jetpack Compose, Gradle (Kotlin DSL), single `app` module.** `minSdk 35`, `compileSdk`/`targetSdk 37`. No dependency-injection framework (the prototype's `Container` is the service locator). No agent or LLM-orchestration library.
2. **Do not add a dependency the card does not name** without writing the reason in your hand-back.
3. **Save first.** A capture is written to the database before any model or rule looks at it. Nothing in understanding may delay or fail the save.
4. **Privacy boundaries, enforced in code and tested:**
   - Audio never leaves the phone and is never stored.
   - Coordinates, timestamps and the phone's clock never leave the phone. The model is never told the date.
   - Only note text may go to a hosted model (through the provider-neutral seam of card 04), and never for a capture marked sensitive. With it may go: the word list, names of known subjects and metrics, and titles of open tasks, all built from non-sensitive notes only.
   - Pictures never leave the phone. The text read from a picture leaves only if the owner turns on that one switch (card 16), and never for a sensitive capture. The app never inspects what a picture shows.
   - **Sensitive ("Private") means never sent anywhere**, a second unlock to see, and left out of reminders (even counts), suggestions, previews, spoken answers, reports and logs. Lookups take an explicit `includeSensitive` argument with no default; planners are never handed sensitive rows; only `PrivateContent { }` (card 02) decides whether private content is drawn.
   - A scanned code is text. The app never opens a link without a confirming tap and never acts on Wi-Fi, payment or sign-in codes (card 21).
   - A **watch** (card 19) is the one other thing that may go out: a short search text the owner has seen and approved, to the service named on its confirmation screen. Nothing is watched without that approval.
   - The only code allowed to open a network connection is in `app.stash.model.hosted` (one sub-package per provider) and, when their cards are built, `app.stash.watch.net` (19) and `app.stash.lookup.net` (21). A test enforces the list. Every request names its kind in the outbound register (card 13); a request with no kind, or whose capability is off, is refused.
   - No telemetry, analytics or crash reporting.
5. **Locked-phone policy:** adding a note while locked is allowed. Showing, searching, speaking or answering from stored notes requires the device unlocked and (from card 02) the app gate passed. An activity either *adds* (shows only the thing being added now) or *reads* (is `MainActivity`). Notifications show counts, not note text, on the lock screen.
6. **The app must be fully usable with no API key, no network, and every optional capability switched off** ("tier 0", `docs/PROVIDERS.md`): capture, search by words, tasks and reminders all work. **Nothing fails silently**: what a missing model or permission prevents is said on screen.
7. **Every optional ability sits behind one capability switch and one interface** (card 13). Feature code asks `Capabilities.isOn(...)`; it never checks a permission or a setting directly. Each card that adds an ability adds its row (what it needs, and what still works without it) and its explanation-card text from `docs/EXPLANATION_CARDS.md`.
8. **The model proposes; code decides.** Model output is data. Code validates it, stores it, links it and schedules. The model never writes to the database and never starts anything that sends.
9. **Everything testable is tested.** Pure logic lives in plain Kotlin with no Android imports, under JVM unit tests (`./gradlew testDebugUnitTest`). No test calls the network: use recorded responses and fakes. Each card says which checks are unit tests and which need the real phone.
10. **Storage rules that protect later work** (`docs/STORAGE.md`): all data access goes through the repositories and `Container.store()`, never a DAO from a screen or a raw file path; every row has a permanent random id that no code parses; rows that can change carry `updated_at`; a real delete follows card 01's purge and leaves a tombstone; every schema change ships a hand-written migration and a migration test; background code must cope with "the database is not available right now" by rescheduling.
11. **Logs hold events and codes, never words.** The only logging call is `Dbg.event(component, code, numbers…)` (card 13). No `Log.d`, no `println`, no note text, question text, names or coordinates in any log, in any build.
12. **Plain language in the UI.** Short sentences, no jargon. Minimum touch target 56 dp. Works at 200% font scale and with TalkBack.
13. **Record what happened.** When a check needs the phone, write the result in `docs/EXPERIMENT_LOG.md` (date, what was tried, result, status mark). Never invent a phone result. Never retry something the log says failed without a new idea.
14. **Never lose the owner's notes.** If in doubt, copy the database file first. Never suggest an uninstall without saying it destroys the notes unless a backup (card 10) exists.
15. **Do not run git commands** unless the owner asks.

## Package layout

```
app/src/main/java/app/stash/
  capture/     capture screen, tones, InboundSink                      (exists; sink from card 13)
  speech/      SpeechEngine and the Android on-device recogniser        (exists)
  triggers/    notification action, tile, boot receiver                (exists)
  data/        Room entities, DAOs, database, repositories, Container, Capabilities  (reshaped by card 01)
  domain/      pure Kotlin: kinds, date expressions, checks, projection rules, task rules,
               nudge planner, notification budget, trip builder, answer wording, Corrector, Dbg
  model/       Extractor interface; rules/ (offline); hosted/ (ModelProvider seam; hosted/anthropic/ etc.)
  pipeline/    background extraction work                              (card 13)
  ask/         router, read-only lookups, tool loop, answer trace      (card 08)
  reminders/   scheduling and notifications                            (card 06)
  security/    SecretStore (exists), app gate (02), database key (14), picture files (16)
  backup/      backup, restore, export, delete everything              (card 10)
  share/       Share to Stash                                          (card 16)
  lookup/      product lookup; lookup/net/ is a network package        (card 21)
  trips/       departure signals, leaving list                         (cards 17, 18)
  watch/       watches; watch/net/ is the second network package       (card 19)
  ui/          Compose screens                                         (exists)
app/src/debug/java/app/stash/debug/   debug-only tools (dump for tools/peek.py, spikes, Try a sentence)
app/src/test/java/app/stash/          JVM unit tests; test/resources/recorded/ holds recorded model replies
tools/peek.py                         developer's view of the phone over USB
```

## Vocabulary

| Word | Meaning |
| --- | --- |
| Capture | One thing he said, typed or shared: raw words, corrected words, time, zone, location, locked or not, private or not |
| Record | One thing the app understood from a capture. Has a kind, a short title, and the exact quote it came from |
| Kind | `PLACED`, `LOAN`, `TASK`, `READING`, `EVENT`, `REFERENCE`, `IDEA`, `OTHER` |
| Extraction | One run of an extractor over a capture, with its raw output kept |
| Extractor | Turns capture text into records. `RulesExtractor` works offline; `HostedExtractor` calls the hosted model |
| Projection | The code that turns an extraction's output into rows |
| Pinned | A record (or trip) the owner or a later capture has acted on; survives re-reads |
| Revision | The written record of one thing the owner did |
| Subject | A named thing records attach to: an item, a person, a place, or a thing notes are about ("pool", "car", "Tide") |
| Metric | A named measurement of a subject ("pH" of the pool; "price" of Tide) |
| Sensitive | Marked "Private" in the UI. Never sent anywhere, second unlock, left out of reminders and previews. Replaces the prototype's `localOnly` |
| Capability | One optional ability with its own switch (location at capture, hosted model, pictures, trips, watches, …) |
| Tier | Who does the understanding: 0 the phone and code; 1 a model on the phone; 2 a hosted model with the user's key |
| Provider | A company whose model can be used at tier 2, behind the `ModelProvider` seam. Only Anthropic's adapter exists until card 22 |
| Outbound kind | One named sort of request that can leave the phone; the "What leaves my phone" screen lists them |
| Explanation card | Five lines beside a switch: what you get, what it sends, if it's off, what it costs |
| Attention list | Records the app was unsure about. Optional reading; never blocks anything |
| Nudge | A reminder notification. Decided by plain code |
| Digest | One notification covering several tasks: "You have 3 things you wanted to do" |
| Scheduled / event notification | Scheduled: digest, weekly review, due today. Event: arrival, leaving, a watch match. Each class has its own daily cap (card 06) |
| Attachment | A picture linked to exactly one capture, whichever came first; the app's own small encrypted copy |
| Code scan | A barcode or QR code as its raw payload, symbology and parsed type |
| Spot | Where within a place, in his words ("row 5, bucket 10") |
| Trip | An outing: start, end, stops, car, the notes made during it. Built from notes unless he started it himself |
| Leaving list | Things to take when going out, always or for a given car or place |
| Watch | A recurring read-only web check the owner approved, shown with exactly what it sends |
| Answer trace | The stored steps behind one answer: route, lookups, sources. Feeds "Why this answer?" |
| Tombstone | A content-free marker that a row was deleted |
| Date expression | What he said about time ("this weekend"), as data from the model, resolved to dates on the phone |
| Cue | A short tone: start of listening, saved, failed |

## The data model in one table

IDs are opaque strings (UUIDs for new rows); times are UTC epoch milliseconds; local dates are ISO strings (`2026-11-14`); enums are stored as names; JSON lists are stored as text. Tables whose rows can change also have `created_at` and `updated_at` (not repeated below).

**Core (card 01, schema version 2):**

| Table | Columns |
| --- | --- |
| `capture` | `id`, `created_at`, `zone`?, `raw_text`, `text`, `source` (`VOICE`,`TYPED`; `SHARED`,`PHOTO` from card 16), `captured_locked`?, `sensitive`, `sensitive_reason`?, `lat`?, `lon`?, `accuracy_m`?, `extract_state` (`PENDING`,`DONE`,`RETRY`,`GAVE_UP`), `extract_attempts`, `app_version`?, `photo_hint` (`NONE`,`PENDING`,`DISMISSED`), `deleted_at`? |
| `extraction` | `id`, `capture_id`, `at`, `extractor`, `schema_version`, `text_hash`, `output_json`, `status` (`CURRENT`,`REPLACED`,`FAILED`), `error`?, `tokens_in`?, `tokens_out`? |
| `record` | `id`, `capture_id`, `extraction_id`, `seq`, `kind`, `title`, `quote`, `subject_id`?, `place_subject_id`?, `place_text`?, `spot_text`?, `happened_on`?, `happened_text`?, `confidence` (`HIGH`,`MEDIUM`,`LOW`), `attention`?, `pinned`, `supersedes_record_id`?, `claims_to_close_record_id`?, `aliases`, `tags`, `deleted_at`? |
| `task` | `record_id` PK, `state` (`OPEN`,`SNOOZED`,`DONE`,`EXPIRED`,`DROPPED`), `due_kind` (`NONE`,`SOON`,`ON`,`BY`,`WINDOW`), `due_from`?, `due_to`?, `due_text`?, `at_place_subject_id`?, `at_place_text`?, `perishable`, `next_nudge_on`?, `nudge_count`, `ignored_in_a_row`, `quiet`, `snoozed_until`?, `closed_at`?, `closed_how`? (`TAP`,`SPOKEN`,`EXPIRED`,`DROPPED`), `closed_by_record_id`?, `last_active_at`, `expires_on`? |
| `reading` | `id`, `record_id`, `subject_id`?, `metric_id`, `value_text`, `value_num`?, `unit`?, `measured_at` |
| `metric` | `id`, `subject_id`?, `key`, `label`, `aliases`, `unit`? |
| `whereabouts` | `record_id` PK, `item_subject_id`?, `item_text`, `where_text`?, `person_subject_id`?, `person_text`?, `direction` (`PLACED`,`LENT`,`BORROWED`,`RETURNED`), `copy_label`?, `said_date_text`?, `due_back_on`?, `returned_at`? |
| `subject` | `id`, `type` (`ITEM`,`PERSON`,`PLACE`,`THING`), `name`, `norm_name`, `aliases`, `volatility`? (`DAILY`,`NORMAL`,`STORED`), `sensitive`, `merged_into_id`? |
| `place_location` | `id`, `subject_id`, `lat`, `lon`, `radius_m`, `source` |
| `nudge_log` | `id`, `at`, `type` (`DAILY`,`WEEKLY`,`DUE`; later `ARRIVAL`,`LEAVING`,`WATCH`), `record_ids`, `outcome` (`SHOWN`,`OPENED`,`ACTED`,`DISMISSED`,`IGNORED`) |
| `revision` | `id`, `at`, `capture_id`?, `record_id`?, `subject_id`?, `what`, `old_value`, `new_value` |
| `tombstone` | `id`, `table_name`, `row_id`, `deleted_at` |
| `miss_log` | `id`, `at`, `question`, `reason` (`NO_MATCH`,`MARKED_WRONG`) |
| `app_event` | `id`, `at`, `component`, `code`, `n1`?, `n2`?, `ref_id`?, `ms`? |
| `vocab_word`, `correction` | unchanged from the prototype |
| `search_fts` | full-text index: one document per record and one per capture (which from card 16 includes text read from its pictures) |

**Added by feature cards, each with its own migration and migration test:**

| Table | Card | Holds |
| --- | --- | --- |
| `answer_trace` | 08 | the steps behind each answer |
| `attachment` | 16 | one row per picture; the file's own key |
| `trip`, `trip_stop`; `capture.trip_id`, `capture.trip_stop_id` | 17 | outings and their stops |
| `checklist_item` | 18 | the leaving list |
| `watch`, `watch_run`, `watch_seen` | 19 | watches, their runs, matches already seen |
| `code_scan` | 21 | scanned codes |

A card adds only the tables it says it adds. Schema version numbers are taken in build order: use the next free one.

## This phone

Samsung One UI on Android 17: aggressive battery management ("sleeping apps") may delay background work, alarms and broadcasts; Android 17 silently drops sound played from the background; the microphone opens only from a visible activity, a notification action, a widget tap or the assistant role; an app cannot open a screen from the background, so anything the app wants to ask unprompted is a notification. Any statement about what the phone does must be checked on the phone and logged.

## Tools for the builder

- `./gradlew testDebugUnitTest`, `assembleDebug`, `installDebug` (see README "Building").
- `python tools/peek.py notes|rules|log|screen` reads the connected phone over USB (debug build only). From card 01 it also has `dump`, and from card 13 `events`. After card 14 encrypts the database, `dump` is the only way to see data; after the release build, Export in Settings is.
- The hosted model is Anthropic's Messages API, model `claude-haiku-5-5`, called from `app.stash.model.hosted.anthropic` through the `ModelProvider` and `HttpPoster` seams. Before changing request shapes, read the live documentation page the card names; APIs drift.
