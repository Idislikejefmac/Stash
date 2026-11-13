# 11 · Structuring pipeline: from text to items, loans, errands and reminders

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

After a note's text is saved, run a structurer, match the names it found against what is already known, and apply the result to the database in one transaction: item and placement, loan, errand or reminder row, supersession, expiry, search index.

## Depends on

08, 09, 10.

## Context

A captured note starts as `kind = UNSORTED`, `structure_source = NONE` (card 08). `RulesStructurer` (card 10) returns `NoteFields`; the domain rules (card 09) decide supersession, expiry and date resolution. This card joins them and is the only place that writes structured rows. A hosted structurer will later plug into the same pipeline (card 21), so the pipeline takes a list of structurers in order and must be able to **re-apply** a better result to a note that was already structured by rules.

### Steps for one note (`data/StructuringPipeline.apply(noteId)`)

1. Load the note. Skip if deleted. If `structure_source = USER` (the user edited the fields), never overwrite; stop.
2. Run `LocalOnlyDetector`; if it fires, set `local_only = true` and store the cleaned text.
3. Run the structurers allowed for this note in order (for now only rules). Take the last `Ok`.
4. **Resolve names** (`data/EntityResolver`, pure logic over lists so it is unit-testable):
   - Item: exact match on name or any alias (case-insensitive, ignoring "my/the") → that item. Otherwise the closest existing item by normalised edit distance; if the similarity is at least 0.85, or the names sound alike (a simple Double-Metaphone-style key is enough) **and** share the last word, treat as a *suggestion*: create the new item anyway and set `needs_review = true` with a suggestion record ("same as 'guitar'?"). Never merge silently.
   - Person and place: same procedure against `person` and `place`.
   - `isCorrection` with no item named ("actually it's in the garage"): use the item of the most recent `PLACED` note created in the last 10 minutes; if none, keep as `FACT` and set `needs_review`.
5. Resolve dates with `DateResolver` against the note's local date (from `created_at` and `zone`).
6. In one transaction:
   - undo anything a previous structuring of this same note created (its placement, loan, reminder rows; restore notes it superseded), so re-applying is clean;
   - upsert item/person/place; merge new aliases into the item;
   - `PLACED`: insert `placement`; mark the notes returned by `Supersession` as `SUPERSEDED` with `superseded_by`;
   - `LOAN`: insert `loan`; if `LENT`, supersede placements as above;
   - `ERRAND`: insert `reminder` with `trigger = PLACE` if a place was named, else `NONE`;
   - `REMINDER`: insert `reminder` with `trigger = TIME` if a `DUE` date resolved (default time 09:00 local), else `NONE`;
   - set `note.kind`, `structure_source`, `structure_json`, `structure_confidence`, `expires_at` (from `Expiry`), and `needs_review = true` when confidence is below 0.5 or a suggestion exists;
   - rewrite the note's `note_fts` row.
7. Emit nothing to the UI directly; screens observe the database.

### When it runs

- Immediately after a note is inserted (in-process, off the main thread).
- From a `WorkManager` job `StructureWorker` that processes every note with `structure_source = NONE`, so nothing is stranded if the process dies. Unique work, no network constraint for now.
- After the user edits a note's text (card 18 calls it).

Suggestions are stored in a small table `review_suggestion(id, note_id, type, existing_id, text)` (this is a schema change: add `Migration(1, 2)` and its test, following the pattern from card 08).

## Scope

`StructuringPipeline`, `EntityResolver`, `StructureWorker`, the migration, tests.

## Out of scope

Any UI, notifications or geofence registration (cards 22–23 observe `reminder`), the hosted model, asking the user anything.

## Acceptance checks

**Unit (JVM, with the test database from card 08):**

- "i'm putting my reading glasses in the nightstand drawer" → one item, one placement, note `PLACED`, FTS finds it by "glasses".
- Then "i put my glasses on the kitchen counter" → same item (alias match), first note `SUPERSEDED`, `superseded_by` set, one current placement.
- Then "actually they're in the car" within 10 minutes → same item, previous superseded.
- "i loaned my guitar to frank on jan 5th" on 2026-11-10 → loan with `on_date` 2026-01-05, person Frank; an earlier placement of the guitar is superseded.
- "i loaned my drill to frankie" when Frank exists → new person, `needs_review`, a suggestion pointing at Frank.
- "buy ground beef when i'm at costco" → reminder `PLACE`, place Costco created with no location, `expires_at` 14 days out (perishable).
- Re-applying the pipeline to a note with different fields (simulated second structurer) leaves exactly one set of rows and restores then re-supersedes correctly.
- A note with `structure_source = USER` is left untouched.
- Migration 1→2 test passes.

**Real phone, S25 Edge:** using the debug insert from card 08 with real sentences, rows appear as expected (inspect with a debug dump screen or logs).

## Notes and risks

- The "undo previous structuring" step is the subtle part. Tag every row the pipeline creates with the `note_id` that caused it, and record superseded note IDs in `structure_json` under a private key, so undo needs no guessing.
- Keep `EntityResolver` conservative: a duplicate item is a small annoyance fixed in the review list; a wrong merge gives a wrong answer about where something is.
