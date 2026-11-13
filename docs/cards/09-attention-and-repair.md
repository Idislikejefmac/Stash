# 09 · The attention list and repairing a note

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

A quiet place where the app's doubts wait, and a small set of actions that fix a misfiled note in one or two taps. He never has to open it; when he does, each item is one question with one-tap answers.

## Depends on

03, 13, 05, 07.

## Context

The owner will not review notes. Earlier cards therefore *park* uncertainty instead of asking: each record has a nullable `attention` reason set by the checks and rules. The reasons so far:

| Reason | Set by | Means |
| --- | --- | --- |
| `BAD_QUOTE`, `MISSING_FIELD`, `UNKNOWN_TASK_LABEL` | card 03 checks | The extractor's output failed a check; the record was kept as a reference |
| `NUMBER_NOT_IN_TEXT` | card 03 | A reading's number is not in the words |
| `TOO_MANY` | card 03 | More than 8 records; kept as one note |
| `PARTLY_FILED` | card 03 | Less than half the note's words were covered |
| `SAME_AS:<subject id>` | card 03 | A new name is close to an existing one ("Frankie" / "Frank") |
| `DID_THIS_FINISH:<task record id>` | card 05 | An event might have finished an open task |
| `DUPLICATE_OF:<id>`, `SAME_AS_TASK:<id>` | card 05 | A task repeats or resembles an open one |
| `ODD_VALUE:<metric>:<median>`, `UNIT_CHANGED` | card 07 | A reading looks unlike its history |

`SUGGEST_PRIVATE:<category>` (card 03) asks "This looks like a health note. Keep it private?" with **Make private** · **No** (No records the category so it is not asked again for a month). Items belonging to a sensitive capture are not shown until the second unlock; the count row says "and 2 private".

Later cards add their own: `ADD_PICTURE` (16), `WATCH_PROPOSED` (19), a leaving-list suggestion (18). Unknown reasons must render as the generic "I wasn't sure how to file this" card, so a new reason never breaks the list.

**Build it in two passes.** Pass 1: the list with the generic card for every reason plus the two questions that change data (`DID_THIS_FINISH`, `ODD_VALUE`), the capture page, and the capture actions. Pass 2: the other bespoke questions, record editing, merging, the developer's view.

Also relevant: records with `confidence = LOW`, captures with `extract_state = GAVE_UP`, and captures made while the phone was locked (`captured_locked = 1`) that he has not seen.

Everything here works because the capture's raw words are kept: any record can be thrown away and remade.

## Scope

### A. The list (behind the app gate)

1. **Attention** screen, reached from a row on the main screen that shows a count and is hidden at zero. No notification of its own; the weekly review (card 06) mentions the count.
2. One card per item, newest first, each showing the capture's words, what the app made of it, and a plain question with buttons:

   | Reason | Question | Buttons |
   | --- | --- | --- |
   | `DID_THIS_FINISH` | "Did this finish 'Clean out the back of the car'?" | Yes (closes the task, `SPOKEN`) · No |
   | `SAME_AS` | "Is 'Frankie' the same as 'Frank'?" | Same (merge) · Different |
   | `SAME_AS_TASK` | "Is this the same as 'Buy milk at Costco'?" | Same (fold in) · Different |
   | `DUPLICATE_OF` | (informational) "Already on your list." | OK · Keep both |
   | `ODD_VALUE` | "pH 72 looks different from your usual 7.4. What was it?" | It was 72 · field to type the value |
   | `UNIT_CHANGED` | "You said 'ppm' this time; before it was no unit." | Keep both · Use ppm from now on |
   | `NUMBER_NOT_IN_TEXT`, `BAD_QUOTE`, `MISSING_FIELD`, `TOO_MANY`, `PARTLY_FILED`, low confidence | "I wasn't sure how to file this." | Looks right · Fix it (opens section B) |
   | `GAVE_UP` | "I couldn't read this one properly." | Read it again · Leave it |

3. Every button clears the reason, pins the record, and writes a `revision`. "Dismiss all" at the top clears every reason without changing anything else.
4. Items older than 60 days are cleared automatically by the daily worker (the note stays; only the question goes).

### B. Fixing a note (from the attention card, or from any capture's page)

5. **Capture page:** the words as heard (`raw_text`) and as corrected (`text`); when and where; private or not; each record with its kind, title and fields; the extractor that produced them.
6. Actions on the capture:
   - **Correct a word** (the prototype's feature, kept: fixes the text, learns the correction, queues a re-read).
   - **Read it again** (card 13).
   - **Treat as one note**: replaces the capture's unpinned records with a single record of a kind he picks (default `REFERENCE`), pinned.
   - **Make private / not private** (card 13's toggle).
   - **Delete** (soft, 30 days, with Undo).
7. Actions on a record:
   - **Change kind** to any of the eight. Changing to `TASK` creates the `task` row (`OPEN`, `NONE`); changing away from `TASK`, `READING`, `PLACED` or `LOAN` removes the side rows. Pins.
   - **Edit** title; for a task its due (the snooze presets plus "no date") and place; for a placement its item and where; for a loan its person and returned; for a reading its values (card 07's editor).
   - **Remove this part** (soft-deletes the record only; the capture stays).

### C. Merging subjects (`domain/SubjectMerge.kt` + repository)

8. `merge(keepId, otherId)`: repoint every `record.subject_id`, `reading.subject_id`, `metric.subject_id`, `whereabouts.*_subject_id`, `task.at_place_subject_id` from other to keep; add the other's name and aliases to keep's aliases; merge metrics with the same key (repoint readings, delete the duplicate metric); set `other.merged_into_id`; rebuild affected search documents. One transaction.
9. Reachable from the `SAME_AS` question and from a subject page ("Same as…" with a picker). "Different" records the pair so the question is not asked again (store in the subject's aliases as a negative entry, `!frankie`, or a small table if cleaner; say which).
10. After a merge, a placement supersession may now apply across the two former items: re-run the supersession rule for the kept subject.

### D. Developer's view (debug build only)

11. A debug screen with counts for the last 7 and 30 days: captures; records by kind; records per capture (histogram 0–8); attention reasons by type; tasks dropped within a day of creation (a sign of invented tasks); tasks closed by speaking and then reopened (wrong closes); re-reads; extraction failures by reason; consistency-check violations (card 01). `peek.py stats` prints the same from the dump.

## Out of scope

Asking him to confirm anything at capture time. Notifications for attention items. Bulk editing. Merging captures. A general tag editor.

## Acceptance checks

- **Unit:** each button's effect on the database, including pinning and the revision row.
- **Unit:** "Treat as one note" leaves pinned records, replaces unpinned ones, and survives a later automatic re-read.
- **Unit:** change kind in each direction creates and removes the right side rows.
- **Unit:** merge: all references repointed; metrics with the same key merged; aliases combined; search finds the kept subject by the other's name; "Different" suppresses the question on the next near-miss.
- **Unit:** the 60-day sweep clears reasons and nothing else.
- **Unit (Robolectric):** the list renders one card per reason type from a seeded database, and the row on the main screen is hidden at zero.
- **Real phone, S25 Edge:** say "Frankie has the drill" (with "Frank" already known): the question appears; **Same** merges and "who has the drill" answers "Frank". Say "pool test pH 72" after three normal tests: the odd-value card appears; type 7.2; the chart shows 7.2 and the capture's words still say 72. Say "buy milk and eggs" and use **Treat as one note**. Use **Dismiss all**. Log it.

## Notes and risks

- Resist adding questions. Each new reason is something that will pile up unread; the weekly quality numbers (section D) are where problems should show first.
- "Looks right" must be the cheapest action on every card.
