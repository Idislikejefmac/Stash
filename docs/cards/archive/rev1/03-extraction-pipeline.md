# 03 · Extraction pipeline: save first, many records per note, private notes, rules v2

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

Saving a note never waits for anything. Understanding happens afterwards in the background, can produce several records from one note, never sends a private note anywhere, and can be redone. This card builds everything except the hosted call itself (card 04), using the offline rules and a fake model.

## Depends on

01. (02 is recommended first but not required.)

## Context

Today `Repository.saveNote` (in `data/Container.kt`) calls the hosted model **before** inserting the note, so a slow network delays the save by up to 15 seconds, and it sends every note because nothing ever sets `local_only`. Both are fixed here.

The model's reply (card 04) and the rules' output have the same shape, **extraction schema version 1**:

```
{ "records": [ {
    "kind":      "placed|loan|task|reading|event|reference|idea|other",
    "title":     "short restatement in his words (max about 80 characters)",
    "quote":     "the exact words from the note this record came from",
    "subject":   "pool" | null,
    "subject_type": "item|person|place|thing" | null,
    "item":      string | null,        // placed, loan
    "where":     string | null,        // placed; with its preposition: "in the nightstand drawer"
    "person":    string | null,        // loan
    "direction": "lent|borrowed|returned" | null,
    "at_place":  string | null,        // task: "Costco"
    "perishable": boolean | null,      // task
    "when":      DateExpr | null,      // when it happened, if he said
    "due":       DateExpr | null,      // task
    "closes":    string | null,        // label of an open task this finishes, e.g. "T3"
    "readings":  [ { "metric": string, "value": string, "number": number | null, "unit": string | null } ],
    "aliases":   [string],
    "tags":      [string],
    "sure":      "high|medium|low"
} ] }

DateExpr: { "said": string,
            "named": "today|tonight|tomorrow|yesterday|this_weekend|next_weekend|this_week|next_week|end_of_month|soon|someday" | null,
            "weekday": "mon|tue|wed|thu|fri|sat|sun" | null, "which": "this|next|last" | null,
            "n": number | null, "unit": "day|week|month" | null,
            "month": number | null, "day": number | null, "year": number | null,
            "strictness": "on|by|around" | null }
```

The model is never told the date; it returns what was said and the phone resolves it.

## Scope

### A. Types and the extractor interface (`domain/`, `model/`)

1. Kotlin data classes for the shape above (`ExtractedRecord`, `ExtractedReading`, `DateExpr`), with a strict JSON parser and serialiser (`org.json`, as the prototype uses). Unknown `kind` → `other`. A reply that is not this shape → `null`.
2. `interface Extractor { val name: String; suspend fun extract(text: String, context: ExtractContext): ExtractResult }` where `ExtractContext` holds the word list, known subject names with their metric names, and open task titles with labels (`T1`…). `ExtractResult` is `Ok(records, rawJson, tokensIn, tokensOut)` or `Failed(reason, retryable)`. This replaces `model/Structurer`.
3. `FakeExtractor` (test source set): returns canned results by text.

### B. Local-only check (`domain/LocalOnly.kt`, pure)

4. `LocalOnly.check(text, extraWords): Reason?` returns a reason when: the text starts with a marker ("private note", "private", "this is private", "local note", allowing a leading "ok" or "um"); it contains a sensitive word or phrase (password, passcode, PIN, pin number, combination, "the code is", "code for", security code, social security, SSN, account number, routing number, card number, licence/license number); it contains a run of 6 or more digits allowing spaces or dashes between them; or it contains one of the owner's extra "always private" words. The marker phrase is removed from the stored `text` (not from `raw_text`).
5. Settings: an editable "Always private words" list; a switch "Never use the hosted model" (the prototype's switch, kept).
6. The check runs when the capture is inserted and sets `local_only` and `local_only_reason`. A per-note toggle "Private" in the Notes list; turning it **on** is always allowed; turning it **off** asks for confirmation.

### C. Checks on extractor output (`domain/ExtractionChecks.kt`, pure)

7. For each record: `quote` must occur in the capture text, ignoring case, punctuation and runs of spaces; `placed` needs `item` and `where`; `loan` needs `item` and (`person` or direction `returned`); `task` needs a non-empty title; `reading` needs at least one reading, and each reading's `number`, if present, must appear in the quote as digits or as number words (write `NumberWords.matches("7.2", "seven point two")` and test it); `closes` must be a label that was in the context.
8. A record that fails becomes `reference` with the same title and quote and an attention reason (`BAD_QUOTE`, `MISSING_FIELD`, `NUMBER_NOT_IN_TEXT`, `UNKNOWN_TASK_LABEL`). It is never dropped.
9. More than 8 records → replace them all with one `reference` record covering the whole text, attention `TOO_MANY`. Zero records → one `other` record covering the whole text.
10. Coverage: if the quotes together contain fewer than half of the text's content words (reuse the stop-word list in `domain/Search.kt`), set attention `PARTLY_FILED` on the first record.

### D. Date expressions (`domain/DateResolver.kt`, pure, clock and zone injected)

11. `resolveDue(expr, today): Due` → `NONE`, `SOON`, `ON(date)`, `BY(date)`, `WINDOW(from, to)`. Rules: a weekday means the next such day (today counts only if `which = this` and it is that day); "this weekend" is the coming Saturday–Sunday (today and tomorrow if today is Saturday); `n` + `unit` counts from today; month and day with no year is the next such date; `end_of_month` is the last day of this month; `someday` and null are `NONE`; `soon` is `SOON`; strictness `by` turns a date into `BY`.
12. `resolveHappened(expr, today): LocalDate?` → for "yesterday", "on Tuesday", "January 5th": the most recent such date not in the future.

### E. Projection (`domain/Projector.kt` + `data/`)

13. `Projector.project(capture, checkedRecords, today, zone)` → the rows to write: `record` (kind, title, quote, confidence from `sure`, attention, aliases, tags, `happened_on` and `happened_text` from `when`), plus by kind: `task` (state `OPEN`, due fields from `resolveDue`, `due_text` = `due.said`, `at_place_text`, `perishable`, `last_active_at` = capture time, `expires_on` left NULL for card 05), `whereabouts`, `reading` rows (`measured_at` = capture time, or noon of `happened_on` if he gave a date).
14. **Subject resolution** (`domain/SubjectResolver.kt`, pure over a list of existing subjects): normalise the name (lower-case; drop leading my/the/a/our; singular by the prototype's `Searcher.stem`). Exact match on `norm_name` or an alias → link. Otherwise, a near match (the fuzzy matcher in `domain/Corrector.kt`) → create a new subject **and** set attention `SAME_AS:<existing id>` on the record. Otherwise create. Subject type comes from `subject_type`, or `ITEM` for `item`, `PERSON` for `person`, `PLACE` for `at_place`. A subject created only from a local-only capture has `local_only = 1`. For `reading` records, find or create the `metric` by normalised label within the subject.
15. **Supersession:** a new `placed` or `loan` record for an item subject sets `supersedes_record_id` to that item's newest current `PLACED`/`LOAN` record (same `copy_label` only when one is given) and pins the older record. Direction `returned` sets `returned_at` on the item's open loan instead of creating a competing placement.
16. **`closes`:** if present and valid, store the labelled task's record id in the new record's `claims_to_close_record_id`. Do not change the task. (Card 05 owns the decision to close.)
17. Everything for one capture is written through `RecordRepository.replaceRecordsFor` in one transaction (card 01). A capture with any pinned record is skipped by automatic re-reads.

### F. Rules extractor v2 (`model/rules/RulesExtractor.kt`)

18. Port the prototype's `RulesStructurer` patterns to the new shape (placed, loan out, loan in, remind-me/buy). Add, conservatively:
    - **task:** "I (need|have|ought|want) to …", "I should …", "I must …", "don't forget to …", "remember to …", "remind me to …"; due hint from a trailing "soon", "tomorrow", "this weekend", "next week", "by/before/on <weekday>".
    - **event:** "I (just )?(finished|changed|replaced|cleaned|fixed|paid|called|mowed|watered|tested) …", "I am/I'm …ing … right now".
    - **reading:** a leading subject then two or more "name number" pairs ("pool … chlorine 5, pH 7.2"), numbers as digits or number words; one record, several readings.
    - **loan returned:** "<person> (gave|returned|brought) … back".
    - anything else → one `other` record.
    One record per capture; `sure = "medium"` for the prototype's shapes, `"low"` for the new ones; no aliases.
19. `extractor.name = "rules-2"`.

### G. The background pipeline (`pipeline/`)

20. `CaptureActivity` and typed entry call `CaptureRepository.insertCapture` (corrected text, location, locked flag, zone, local-only check) and **then** play the save tone and show "Saved". Add a short vibration with each cue. Add **Undo** on the saved screen for 5 seconds (soft-deletes the capture). Add a text field on the main screen for typing a note (outside the gate for entry only).
21. `ExtractionWorker` (WorkManager, unique work, runs immediately when enqueued; no network constraint): for each capture in `PENDING` or `RETRY`, oldest first:
    - Build the context from non-local-only data.
    - Choose extractors: always `RulesExtractor`. If the capture is not local-only, a key is set, the hosted switch is on and an `Extractor` named hosted is registered (none until card 04; use `FakeExtractor` in tests), run it too.
    - Hosted `Ok` → its records are used. Hosted `Failed(retryable)` → rules records are used, state `RETRY`, attempts + 1, back-off 1 min, 10 min, 1 h, 6 h, 24 h, then `GAVE_UP`. Hosted `Failed(not retryable)` → rules records, `DONE`.
    - No hosted extractor applicable → rules records; `DONE` if local-only or no key; `RETRY` without counting an attempt if a key exists but there is no network (re-enqueue with a network constraint).
    - Store the extraction row (`text_hash` = SHA-256 of the capture text), run checks, project, write.
22. Enqueued after every insert, at app start, and at boot. A capture whose `text` was edited goes back to `PENDING`.
23. Migrated captures from card 01 are all `PENDING`: the worker re-reads them with `rules-2` (and with the hosted model once card 04 exists).
24. "Read it again" on a capture in the Notes list: sets `PENDING` and enqueues, even if some records are pinned (pinned ones stay; new ones are added; a new record whose kind and normalised title equal a pinned one's is discarded).

### H. Enforcement tests

25. A unit test that scans `app/src/main` and fails if any file outside `app/stash/model/hosted/` contains `java.net.`, `HttpURLConnection`, `okhttp`, `Socket(` or `WebView`.
26. A unit test that runs the pipeline over a local-only capture with a hosted extractor whose transport throws if called.

## Out of scope

The real hosted call, its prompt, schema file and scoring (04). Acting on `CLOSES` and task expiry (05). Nudges (06). Reading screens and outlier flags (07). The attention list UI (09): this card only stores the reasons.

## Acceptance checks

- **Unit:** parser round-trip; unknown kind; wrong shape → null.
- **Unit:** `LocalOnly`: each marker and sensitive phrase; digit runs ("my code is 4 4 7 1 9 2"); a negative list of ordinary sentences that must **not** be private ("I put the pin in the drawer" is acceptable to mark private; "pH 7.2 alkalinity 70" must not be); the marker is stripped from `text` only.
- **Unit:** each check in section C, with the number-words cases ("seven point two", "7.2", "72" must not match 7.2).
- **Unit:** `DateResolver` table tests with a fixed clock: every `named` value, weekdays across a week boundary, "this weekend" on a Saturday, month/day past and future, `by` strictness.
- **Unit:** projection of a three-record result (two tasks, one placement) writes three records with the right side rows; a pool result writes one record and three readings with one subject and three metrics; a second pool note reuses the subject and metrics.
- **Unit:** subject resolution: exact, alias, near-miss (creates and flags), none.
- **Unit:** supersession and `returned`.
- **Unit:** rules v2 table: at least 30 sentences, including "I have to remember to clean out the back of the car soon" (task, soon), "I am updating the app for stash right now" (event), "I cleaned out the car" (event), "Pool test free chlorine 5 pH 7.2 alkalinity 70" (one reading, three values), and ten that must be `other`.
- **Unit:** worker logic with `FakeExtractor`: success; retryable failure then success; give up after five; local-only never reaches the hosted fake; pinned record survives a re-read.
- **Unit:** tests 25 and 26.
- **Real phone, S25 Edge:** in aeroplane mode, add a note by voice: the save tone comes at once (under a second after you stop). `peek.py dump 5` shows the capture, a `rules-2` extraction and a record. Say "private note the gate code is 4 4 7 1": `local_only` is 1, `text` has no "private note", `raw_text` does. After install over the card-01 build, the migrated captures have been re-read (`extractor = rules-2`), and "I am updating the app for stash right now" is an `EVENT`. Log it.
- **Real phone:** Undo within 5 seconds removes the capture from the list.

## Notes and risks

- This card is large. If it must be split, do A–F (pure Kotlin) first, then G–H.
- WorkManager is the one new dependency (`androidx.work:work-runtime-ktx`).
- Samsung may delay background work for a "sleeping" app. The capture is safe regardless; note in the log how long extraction took after a locked capture.
- Do not make the rules clever. Their job is to be a floor that never invents; the model is the main path.
