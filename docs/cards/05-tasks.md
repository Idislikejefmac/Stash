# 05 · Tasks: lifecycle, closing by speaking, and "What should I handle?"

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

Things he said he would do become a list he can trust: each task is open, put off, done, dropped, or quietly expired; saying "I cleaned out the car" finishes the right task; and one screen (and one spoken answer) shows what is outstanding.

## Depends on

03, 13. Works on rules-only extraction; better with 04.

## Context

Card 03 writes a `task` row for every `TASK` record (`state = OPEN`, due fields resolved from what he said, `at_place_text`, `perishable`, `last_active_at`) and stores, on any record whose extractor said it finishes a listed task, `claims_to_close_record_id`. Nothing acts on tasks yet. Notifications are card 06; this card is state and screens.

States: `OPEN` (to do) · `SNOOZED` (put off until `snoozed_until`) · `DONE` · `EXPIRED` (the app stopped asking after a long silence) · `DROPPED` (he said he does not need it). Nothing is ever deleted by a state change, and every transition can be undone.

A wrongly closed task is worse than one left open: it disappears silently. So automatic closing has a high bar.

## Scope

### A. Task rules (`domain/TaskRules.kt`, pure, clock injected)

1. Transitions as functions returning the new row: `done(task, how, byRecordId?)`, `reopen`, `snooze(until)`, `wake` (snoozed and the date has come → `OPEN`), `drop`, `expire`, `bringBack`. Each sets `last_active_at`, except `expire`. `done`, `drop` and `snooze` reset `ignored_in_a_row` and `quiet`. Every change made by the owner pins the record and writes a `revision` (card 01, item 10).
2. `expiresOn(task)`: no date → 90 days after `last_active_at`; at a place → 45 days, or 14 if perishable; dated (`ON`, `BY`, `WINDOW`) → 14 days after `due_to` (or `due_from`). Lifetimes are settings.
3. Snooze presets resolve to dates: later today (same day; only meaningful to card 06), tomorrow, this weekend (next Saturday), next week (next Monday), in a month.
4. Ordering for lists: overdue dated first, then dated by date, then `SOON` by age, then at-a-place, then `NONE` by age.

### B. Closing by speaking (`domain/TaskCloser.kt`, pure)

5. Input: a newly projected record and the list of open tasks. Decide one of `Close(taskId)`, `Ask(taskId)`, `Nothing`:
   - The record has `claims_to_close_record_id`, the task is `OPEN` or `SNOOZED`, and the record's confidence is `HIGH` → `Close`.
   - It has a claim but confidence is lower → `Ask`.
   - No claim, the record is an `EVENT`, and exactly one open task shares at least two content-word stems with it (or one stem and the same subject) → `Ask`. Use the stemmer and stop words already in `domain/Search.kt`.
   - Otherwise `Nothing`.
6. `Close` → `done(task, SPOKEN, record.id)`; pins both. `Ask` → set the event record's `attention = DID_THIS_FINISH:<task record id>`. Called by the pipeline right after projection (registered as an `AfterProjection` hook, card 13).
7. Setting "When I say I did something": close when sure (default) / always ask / close on any match (treats `Ask` as `Close`).
8. Loans: a `LOAN` record with direction `returned` already sets `returned_at` (card 03); nothing more here.

### C. Duplicate tasks

9. When a new `TASK` record's normalised title equals an open task's (same stems, same place), do not create a second nag: keep the new record (the capture is truth) but mark its task `DROPPED` with `closed_how = DROPPED`, set attention `DUPLICATE_OF:<id>` on it, and bump the existing task's `last_active_at`. Near-duplicates (most stems shared) are left alone and flagged `SAME_AS_TASK:<id>` for the attention list.

### D. Daily upkeep (`pipeline/TaskUpkeepWorker`, WorkManager, once a day)

10. Wake snoozed tasks whose date has come; expire tasks past `expiresOn`; recompute `expires_on`; run card 01's `purgeDeletedOlderThan(30)`. No notifications (card 06).

### E. Screens (behind the app gate)

11. **"To handle"** as a main tab. Sections, in order, each hidden when empty: **Due** · **Soon** · **At a place** (grouped by `at_place_text`) · **Whenever** · **Later** (snoozed, with the date) · **Done recently** (14 days; each with how it was closed, and for `SPOKEN` the words that closed it) · **Stopped asking** (expired). Row: title; "You said: '<quote>'" and its age; the due words as he said them ("this weekend").
12. Row actions: **Done**, **Later** (the presets), **Don't need**. In Done recently: **Not done**. In Stopped asking: **Bring back**. Press-and-hold is not required; each action shows an Undo snackbar for 5 seconds.
13. Tapping a row opens the capture it came from (whole text, other records from the same capture, "Read it again").
14. A count badge on the tab: open tasks in Due and Soon.
15. The Notes list shows a task's state on its record.

### F. The question

16. Extend the prototype's answering (`ui/MainViewModel.answer`, `domain/AnswerComposer`) so that questions matching "what should I handle", "what are the things I said I should/need to/have to (do|handle)", "what's on my list", "what do I need to do", "anything I need to do" return the top five open tasks in the order above, written by code: "You have 7 things. Soonest: clean out the back of the car (you said that 5 days ago); …". And "what do I need at <place>" returns that place's open tasks. No model call. (Card 08 moves this into the router.)

## Out of scope

Notifications, digests, nudge scheduling, ignored counts (06). Place locations and arrival (11). Recurring tasks. Sub-tasks and priorities (not planned). The attention list screen (09): this card only sets the reasons.

## Acceptance checks

- **Unit:** every transition with a fixed clock; pinning; undo of each (`reopen`, `bringBack`).
- **Unit:** `expiresOn` boundaries for each due kind; perishable; a snooze extends life.
- **Unit:** ordering with a mixed list.
- **Unit:** `TaskCloser` table: claim + high → close; claim + medium → ask; no claim, one match → ask; no claim, two matches → nothing; claim on a `DONE` task → nothing; the three settings.
- **Unit:** duplicate: identical title folds in; near-duplicate is flagged; different place is not a duplicate.
- **Unit:** the question patterns, including negatives ("what did I handle yesterday" must not match).
- **Unit (Robolectric):** the screen shows the sections for a seeded database and hides empty ones.
- **Real phone, S25 Edge:** say "I have to remember to clean out the back of the car soon". It appears under Soon with "you said". Say "I cleaned out the car". With a key (card 04 done): the task moves to Done recently showing those words. Without a key: the event appears and the task is still open (the attention reason is set; `peek.py dump` shows it). Tap **Not done**: it is open again. Say "buy milk at Costco" twice: one open task. Ask aloud "What are the things I said I should handle?": the list is read out as text. Log it.

## Notes and risks

- "Later today" has no visible effect until card 06; keep the preset anyway.
- Keep wording calm: "Stopped asking", not "Expired"; "Don't need", not "Delete".
- `TaskCloser` runs as a hook after `replaceRecordsFor`; if it must be atomic with the write, say so and move it inside the transaction.
