# 03 · Extraction core: the record shape, private-note check, checks, dates, projection, rules v2

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

**Status: done 2026-11-11** (built and unit-tested: 246 tests pass, 154 new; nothing of it runs in the app yet, by design, and the phone was not connected, so no phone check was made). See "What was built, and what differs" at the end.

Revision 2 split the old card 03 in two. This card is the pure Kotlin half (no Android imports, all JVM unit tests). The background pipeline that runs it is [card 13](13-capture-pipeline.md). The old combined card is at `archive/rev1/03-extraction-pipeline.md`.

## Goal

Everything needed to turn a capture's text into rows, as plain functions that can be tested without a phone: the shape an extractor returns, the check that keeps private notes on the phone, the checks on extractor output, date resolution, projection into tables, and the offline rules extractor.

## Depends on

01.

## Context

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
    "at_place":  string | null,        // task: where it is to be done ("Costco")
    "place":     string | null,        // any kind: where it happened or where he says he is ("Ross")
    "spot":      string | null,        // where within the place, in his words ("Italian section, row 5, bucket 10")
    "photo_hint": boolean | null,      // true when the note describes a physical thing a picture would help find again
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

`place` is new in revision 2; `spot` and `photo_hint` in revision 3 ("It's a Valpolicella by Allegrini, in the Italian section, row 5, bucket 10" → `place` null unless he names the shop, `spot` "Italian section, row 5, bucket 10", `photo_hint` true). `place` costs one nullable field and is what lets "Just bought some jeans at Ross" carry its place, and a price carry the shop it was seen in. **A price is a reading**: "the Tide is on sale for $24.99" is a `reading` record with subject "Tide", one reading `{metric: "price", value: "$24.99", number: 24.99, unit: "$"}` and `place: "Costco"` if he said so. "$4.97 per bottle" has unit `"$/bottle"`. No new kind.

The model is never told the date; it returns what was said and the phone resolves it.

## Scope

### A. Types and the extractor interface (`domain/`, `model/`)

1. Kotlin data classes for the shape above (`ExtractedRecord`, `ExtractedReading`, `DateExpr`), with a strict JSON parser and serialiser (`org.json`, as the prototype uses). Unknown `kind` → `other`. A reply that is not this shape → `null`.
2. `interface Extractor { val name: String; suspend fun extract(text: SendableText, context: ExtractContext): ExtractResult }`. `ExtractContext` holds the word list, known subject names with their metric names, and open task titles with labels (`T1`…). `ExtractResult` is `Ok(records, rawJson, tokensIn, tokensOut)` or `Failed(reason, retryable)`. This replaces `model/Structurer`.
3. `SendableText` is a value class with a private constructor, created only by `Sensitivity.sendable(capture)` (returns null for a sensitive capture). The rules extractor takes plain text through a separate `LocalExtractor` interface. This makes "a private note reached a hosted extractor" a compile error rather than a bug.
4. `FakeExtractor` (test source set): returns canned results by text.

### B. Sensitive check (`domain/Sensitivity.kt`, pure)

Revision 3: one mark, `sensitive` (shown as **Private**), replaces the prototype's unused `localOnly`. A sensitive capture is never sent anywhere, sits behind a second unlock (card 02), and is left out of digests, suggestions, previews and diagnostics. The mark is set in three ways, each with a reason code in `sensitive_reason`: he says so (`SAID`), he toggles it (`TOGGLED`), or the app finds a secret or a personal code and turns it on by default (`AUTO_SECRET`, `PERSONAL_CODE`; he can turn it off, with a confirmation). For softer categories the app only **suggests** (item 6a) and never sets it.

5. `Sensitivity.check(text, extraWords): Reason?` returns a reason when: the text starts with a marker ("private note", "private", "this is private", "local note", allowing a leading "ok" or "um"); it contains a sensitive word or phrase (password, passcode, PIN, pin number, combination, "the code is", "code for", security code, social security, SSN, account number, routing number, card number, licence/license number); it contains a run of 6 or more digits allowing spaces or dashes between them; or it contains one of the owner's extra "always private" words. `stripMarker(text)` removes the marker phrase (applied to the stored `text`, never to `raw_text`).
6a. `Sensitivity.suggest(text): Category?` returns `HEALTH` (blood pressure, blood sugar, glucose, weight "I weigh", diagnosis, prescription, dose, mg of a named medicine), `MONEY` (salary, balance, owe, debt, account) or null. The pipeline turns a non-null result into attention `SUGGEST_PRIVATE:<category>` on the first record; nothing else changes and the note is filed normally. Pool readings must not match.
6. A price is not a secret: "$24.99", "4.97 per bottle" and "pH 7.2 alkalinity 70" must not trip the digit-run rule. Test it.

### C. Checks on extractor output (`domain/ExtractionChecks.kt`, pure)

7. For each record: `quote` must occur in the capture text, ignoring case, punctuation and runs of spaces; `placed` needs `item` and `where`; `loan` needs `item` and (`person` or direction `returned`); `task` needs a non-empty title; `reading` needs at least one reading, and each reading's `number`, if present, must appear in the quote as digits or as number words (write `NumberWords.matches("7.2", "seven point two")`; money forms too: "24.99" matches "twenty four ninety nine" and "$24.99"); `closes` must be a label that was in the context.
8. A record that fails becomes `reference` with the same title and quote and an attention reason (`BAD_QUOTE`, `MISSING_FIELD`, `NUMBER_NOT_IN_TEXT`, `UNKNOWN_TASK_LABEL`). It is never dropped.
9. More than 8 records → replace them all with one `reference` record covering the whole text, attention `TOO_MANY`. Zero records → one `other` record covering the whole text.
10. Coverage: if the quotes together contain fewer than half of the text's content words (reuse the stop-word list in `domain/Search.kt`), set attention `PARTLY_FILED` on the first record.

### D. Date expressions (`domain/DateResolver.kt`, pure, clock and zone injected)

11. `resolveDue(expr, today): Due` → `NONE`, `SOON`, `ON(date)`, `BY(date)`, `WINDOW(from, to)`. Rules: a weekday means the next such day (today counts only if `which = this` and it is that day); "this weekend" is the coming Saturday–Sunday (today and tomorrow if today is Saturday); `n` + `unit` counts from today; month and day with no year is the next such date; `end_of_month` is the last day of this month; `someday` and null are `NONE`; `soon` is `SOON`; strictness `by` turns a date into `BY`.
12. `resolveHappened(expr, today): LocalDate?` → for "yesterday", "on Tuesday", "January 5th": the most recent such date not in the future.

### E. Projection (`domain/Projector.kt`, pure; returns rows, writes nothing)

13. `Projector.project(capture, checkedRecords, existingSubjects, today, zone)` → the rows to write: `record` (kind, title, quote, confidence from `sure`, attention, aliases, tags, `happened_on` and `happened_text` from `when`, `place_text` and `place_subject_id` from `place`, `spot_text` from `spot`), plus by kind: `task` (state `OPEN`, due fields from `resolveDue`, `due_text` = `due.said`, `at_place_text`, `perishable`, `last_active_at` = capture time, `expires_on` left NULL for card 05), `whereabouts`, `reading` rows (`measured_at` = capture time, or noon of `happened_on` if he gave a date).
14. **Subject resolution** (`domain/SubjectResolver.kt`, pure over a list of existing subjects): normalise the name (lower-case; drop leading my/the/a/our; singular by the prototype's `Searcher.stem`). Exact match on `norm_name` or an alias → link. Otherwise, a near match (the fuzzy matcher in `domain/Corrector.kt`) → create a new subject **and** set attention `SAME_AS:<existing id>` on the record. Otherwise create. Subject type comes from `subject_type`, or `ITEM` for `item`, `PERSON` for `person`, `PLACE` for `at_place` and `place`. A subject created only from a sensitive capture has `sensitive = 1`. For `reading` records, find or create the `metric` by normalised label within the subject.
15. **Supersession:** a new `placed` or `loan` record for an item subject sets `supersedes_record_id` to that item's newest current `PLACED`/`LOAN` record (same `copy_label` only when one is given) and pins the older record. Direction `returned` sets `returned_at` on the item's open loan instead of creating a competing placement.
16. **`closes`:** if present and valid, store the labelled task's record id in the new record's `claims_to_close_record_id`. Do not change the task. (Card 05 owns the decision to close.)

### F. Rules extractor v2 (`model/rules/RulesExtractor.kt`)

17. Port the prototype's `RulesStructurer` patterns to the new shape (placed, loan out, loan in, remind-me/buy). Add, conservatively:
    - **task:** "I (need|have|ought|want) to …", "I should …", "I must …", "don't forget to …", "remember to …", "remind me to …"; due hint from a trailing "soon", "tomorrow", "this weekend", "next week", "by/before/on <weekday>".
    - **event:** "I (just )?(finished|changed|replaced|cleaned|fixed|paid|called|mowed|watered|tested|bought|picked up) …", "I am/I'm …ing … right now"; a trailing "at <Capitalised word>" fills `place`.
    - **reading:** a leading subject then two or more "name number" pairs ("pool … chlorine 5, pH 7.2"), numbers as digits or number words; one record, several readings.
    - **loan returned:** "<person> (gave|returned|brought) … back".
    - **spot:** "(aisle|row|shelf|bin|bucket|bay|section|drawer) <word or number>" phrases are copied to `spot`. **photo_hint:** true when the note has a `spot`, or names a container or label word (bottle, box, can, jar, label, part number, model number, serial) and is not a task.
    - anything else → one `other` record.
    One record per capture; `sure = "medium"` for the prototype's shapes, `"low"` for the new ones; no aliases.
18. `extractor.name = "rules-2"`.

## Out of scope

Running any of this in the app (card 13). The hosted call (04). Acting on `closes` (05). Outlier flags (07). Trips, pictures, watches: their cards extend this shape under a new schema version.

## Acceptance checks

All unit tests; no phone.

- Parser round-trip; unknown kind; wrong shape → null.
- `Sensitivity`: each marker and sensitive phrase; digit runs ("my code is 4 4 7 1 9 2"); ordinary sentences that must **not** be private, including the price and pool cases in item 6; the marker is stripped from `text` only. `SendableText` cannot be built for a sensitive capture.
- Each check in section C, with the number-words cases ("seven point two", "7.2"; "72" must not match 7.2; "twenty four ninety nine" matches 24.99).
- `DateResolver` table tests with a fixed clock: every `named` value, weekdays across a week boundary, "this weekend" on a Saturday, month/day past and future, `by` strictness.
- Projection of a three-record result (two tasks, one placement) gives three records with the right side rows; a pool result gives one record and three readings with one subject and three metrics; a second pool note reuses the subject and metrics; "Tide is on sale for $24.99" at Costco gives one reading with metric price, number 24.99, and a place subject.
- Subject resolution: exact, alias, near-miss (creates and flags), none.
- Supersession and `returned`.
- `Sensitivity.suggest`: health and money examples; pool and price sentences return null.
- Rules v2 table: at least 30 sentences, including the Valpolicella sentence above (spot and photo hint), including "I have to remember to clean out the back of the car soon" (task, soon), "I am updating the app for stash right now" (event), "I cleaned out the car" (event), "Just bought some jeans at Ross" (event, place Ross), "Pool test free chlorine 5 pH 7.2 alkalinity 70" (one reading, three values), and ten that must be `other`.

## Notes and risks

- Do not make the rules clever. Their job is to be a floor that never invents; the model is the main path.
- The shape is not built anywhere yet, so adding `place` now is free. After card 04 records its first responses, any change to the shape is a new schema version and new recordings.

## What was built, and what differs from the card (2026-11-11)

Built (all under `app/src/main/java/app/stash/`):
- `domain/Extraction.kt`: `ExtractedRecord`, `ExtractedReading`, `DateExpr`, `CheckedRecord`, and `ExtractionJson` (strict `parse`, `serialise`; unknown kind to `other`; wrong shape to `null`; a code fence is tolerated).
- `model/Extractor.kt`: `Extractor` (takes `SendableText`), `LocalExtractor` (takes a plain string), `ExtractContext`, `ExtractResult.Ok/Failed`. Interfaces only: no hosted call (card 04).
- `domain/Sensitivity.kt`: `check`, `stripMarker`, `suggest`, `sendable`, `SendableText` (private constructor, one internal door that a test keeps out of every other file).
- `domain/NumberWords.kt`, `ExtractionChecks.kt`, `DateResolver.kt`, `DatePhrases.kt` (the time phrases the rules know), `SubjectResolver.kt` (with `MetricResolver`), `Projector.kt`.
- `model/rules/RulesExtractor.kt` (`rules-2`).
- `data/ExtractionRunner.kt`: reads one capture with an extractor and stores the result in one transaction; not called from anywhere yet. DAO additions in `Daos.kt` (all subjects and metrics, current placements and loans, open tasks for the context, a system pin, a returned mark). No schema change (`2.json` is unchanged).
- Tests: `RulesExtractorTest` (35), `ExtractionLogicTest` (JSON, sensitivity, number words, checks), `DateTest`, `ProjectorTest` (projector and resolvers), `ExtractionRunnerTest` (Robolectric, real Room).

Differs from the card, and why:
- **`model/Structurer` is not removed.** The save path and the hosted prototype structurer still use it; card 13 replaces them. `Extractor`/`LocalExtractor` sit beside it.
- **`task` records may carry `item`** (the card lists `item` for placed and loan only). A shopping task needs it ("buy ground beef" has item ground beef), and it costs no new field. The projector links an item subject for tasks.
- **A bad quote is replaced by the whole text, not kept.** The card says a failed record keeps the same quote; a quote that is not in the capture is not his words, so the downgraded `reference` quotes the whole capture and keeps the title. Other failures keep title and quote as the card says.
- **The ExtractionRunner is more than the card asks.** The card stops at pure functions; the brief asked for "mapping extraction results onto records/tasks/readings/whereabouts" and re-extraction without losing pinned records, and that needs one place that loads context and writes in one transaction. It is not wired into `Repository.saveNote`, because card 13 owns when it runs.
- **Dates resolve against the day the capture was made**, not the day it is read, so a re-read ten days later still says "tomorrow" meant the day after he said it.
- **Supersession ignores records of a later capture and of the capture being re-read**, and a new record pins the older one it replaced (card says so) using a system pin (no `revision`, since the owner did nothing). Known gap: re-reading an older capture after a newer one exists does not re-point the newer one's `supersedes_record_id` (the relink on delete handles only removal).
- **`returned`**: the "gave it back" record is a LOAN with direction RETURNED; it sets `returned_at` on the open loan, pins that loan, and does not supersede anything.
- **"next Friday"** means the Friday of next calendar week (Monday to Sunday); "this Friday" is the next one coming (today counts only for "this" and its own weekday).
- **`I am updating the app for stash right now` is an EVENT** (the card and DESIGN 4.3 say so), not a `reference`; it has no task.
- **Reading `value` is the words or digits exactly as heard**; `unit` is never invented (a bare "24.99" has no unit; "$24.99" has "$"; "$4.97 per bottle" has "$/bottle").
- **Rules readings need a known metric word or a "test/checked/measured/reading/levels" cue**, so "I need 2 apples and 3 oranges" is `other`, not a reading. The known metric list (chlorine, pH, alkalinity, hardness, cyanuric acid, salt, bromine, ...) also takes the metric names from `ExtractContext`.
- **Imperative tasks** ("Finish adding in the features ...") are recognised only for a short verb list (finish, complete, call, email, schedule, buy, purchase, fix, clean, send, pay, renew, cancel, add, write, replace, organize, submit, mow, research, pick up). Words like book, text, order, plan are left out because they are nouns too.
- **A spot** needs "aisle|row|shelf|bin|bucket|bay|section|drawer" followed by a number, a letter or a number word, or "<word> section/aisle/bay"; so "nightstand drawer" gives no spot. The Valpolicella sentence is a `reference` (low) with the spot and a photo hint.
- **The owner's first sentence** ("looking at my warm tobacco pipe candle and I want to remember to purchase one the next time I go to Ross") is one task: item `warm tobacco pipe candle` (found from the first clause because "one" has no object of its own), at_place `Ross`, tag `shopping`, no due. The quote is the whole text because the first clause supplied the item.

Unverified / still to do:
- Nothing here has run on the phone (it was not connected), and no code path calls it yet: a captured note still goes through the prototype structurer until card 13.
- The rules are a floor measured only against the card's sentences and the owner's real ones (a table of about 60). How they fare on his next hundred notes is unknown; that is what card 04's seed set measures.
- `Sensitivity` digit-run rule: any 6+ digit run (even a phone number or an ISO date with dashes) is private. That matches the card; the owner can turn it off per note (card 13).
