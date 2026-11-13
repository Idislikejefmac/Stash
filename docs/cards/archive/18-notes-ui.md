# 18 · Notes screens: home, recent, "check these", edit, undo, delete

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

The screens for looking at and fixing what has been captured: a simple home, a recent list, a review list for notes the app is unsure about, and an editor that lets the user correct text or fields.

## Depends on

11, 17.

## Context

Notes are created by voice and structured automatically (cards 10–11). Speech recognition will get names wrong and the structurer will sometimes guess; the user needs a quick way to see and fix that, and the app must say when it is unsure rather than hide it. Notes carry `needs_review`, `structure_confidence`, `captured_locked`, and there is a `review_suggestion` table ("same as 'Frank'?"). All reading screens sit behind the app lock (`ProtectedContent`, card 17).

Users include the owner's elderly parents: big text, few choices, forgiving actions (everything can be undone for 30 days through soft delete).

## Scope

1. **Home**: two large buttons, **Add a note** and **Find something** (Find opens a plain text search box for now; card 20 replaces it). Under Add a note, a small "Type it instead" link opening a text box that saves through the same path as a spoken note (`insertCapturedNote` then the pipeline, trigger `typed`). Below: a "Check these (N)" row when there is anything to review, and the five most recent notes.
2. **Note row**: an icon for the kind, a one-line summary written from the fields (`domain/Summary.kt`, pure Kotlin): "Reading glasses → nightstand drawer", "Guitar → lent to Frank", "At Costco: buy ground beef", "Remind: call the vet (24 Nov)", or the plain text for `FACT`/`UNSORTED`; the age in words ("3 days ago"); a small badge for superseded, expired, done, private (local-only), waiting-to-be-understood.
3. **All notes**: the full list, newest first, with filter chips (All, Things, Loans, Errands, Reminders, Other) and a toggle to include superseded/expired/done.
4. **Note detail / edit**: the text (editable; saving writes a revision and re-runs the pipeline unless fields were hand-edited); the fields as plain controls (kind picker, item, where, person, place, date, expires); "Private (never sent to a model)" switch; when and (if known) the saved place where it was captured; history ("replaced by…", "replaces…", earlier revisions). Editing a field sets `structure_source = USER` and re-applies the database rows through the pipeline's apply step with the user's fields. Buttons: **Mark done** (errands/reminders), **Delete** (soft delete, with a snackbar Undo), **This is still right** (re-confirms an old `PLACED` note: creates a fresh placement dated now pointing at the same note, so it is no longer stale).
5. **Check these**: notes with `needs_review`, notes captured while locked in the last 7 days not yet seen, and suggestions. Each as a card with the sentence and one-tap answers: "Looks right" (clears the flag), "Fix" (opens the editor), and for suggestions "Same as Frank" (merge: re-point rows to the existing entity, add the new spelling as an alias, delete the duplicate) / "Different person".
6. **Recently deleted**: restore or delete for good; automatic purge after 30 days via a daily worker.
7. Empty states that teach by example: "Try: 'I'm putting my passport in the desk drawer'".
8. All text scales to 200%; all controls ≥ 56 dp; content descriptions on icons; no information carried by colour alone.

## Out of scope

Voice asking and answers (19, 20), reminders UI (22), backup and export (25, 26), onboarding (27), photos.

## Acceptance checks

- **Unit (JVM):** `Summary` for every kind and for missing fields; merge of two persons re-points loans and adds an alias; "This is still right" makes a 100-day-old `NORMAL` placement fresh; editing text of a rules-structured note re-structures, editing fields then text does not overwrite the fields.
- **Unit (Robolectric/Compose test):** home shows "Check these (2)" when two notes need review; filter chips filter; deleting shows Undo and Undo restores.
- **Real phone, S25 Edge:** capture "I loaned my drill to Frankie" when Frank exists → appears in Check these with "Same as Frank?"; tapping it merges; the loan shows under Frank.
- **Real phone:** system font at maximum and display size at maximum: no clipped or overlapping text on any screen in this card. TalkBack reads each row sensibly ("Reading glasses, nightstand drawer, 3 days ago").
- **Real phone, Flip 7 (when available):** screens are usable on the main screen at the parents' font size; record anything awkward.

## Notes and risks

- Keep navigation shallow: Home → list → note. No drawer, no tabs beyond the filter chips.
- The editor is the escape hatch for every recognition failure; it must never lose the original `raw_text`.
