# 19 · Search and answers, entirely on the phone

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

Given a question as text, find the right notes and produce a short, plain answer that states when the note was made and warns when it is probably stale, with no network and no model.

## Depends on

11.

## Context

The database holds notes plus structured rows: `item` (name, aliases, volatility), `placement` (the newest per item and copy label is current), `loan`, `place`, `reminder`, and an FTS5 table `note_fts` over each note's text and names. Domain helpers exist for staleness and for ages in words (card 09). A hosted model may later answer free-form questions (card 21) using the same retrieval; this card must stand alone, because it is what the parents get with no API key and what everyone gets offline.

### Question shapes (`search/QuestionParser`, pure Kotlin)

Input is speech-recognizer text. Recognise, case-insensitively and tolerating fillers:

| Shape | Examples |
| --- | --- |
| `WHERE_IS(item)` | "where did i put my guitar", "where are my glasses", "where's the tesla plug", "have you seen my keys", "find my passport" |
| `WHO_HAS(item)` | "who did i lend the drill to", "who has my guitar", "who borrowed my ladder" |
| `LOANS_WITH(person)` | "what does frank have", "what did i lend frank", "what did i borrow from dave" |
| `ALL_LOANS` | "what have i lent out", "who owes me stuff" |
| `WHAT_AT(place)` | "what did i need at costco", "what do i need from the hardware store" |
| `WHAT_HERE` | "what do i need here" (uses the current location snapshot if the caller supplies one) |
| `REMINDERS` | "what are my reminders", "what do i need to do" |
| `FREE(text)` | anything else |

### Retrieval (`search/Retriever`)

- Entity lookup: exact name/alias → fuzzy (the same similarity used by `EntityResolver`).
- FTS5 `MATCH` on the content words (drop stop words; OR the terms; prefix-match the last term), ordered by `bm25`.
- Score = text match, × 1.0 for `ACTIVE`, 0.4 for `SUPERSEDED`/`DONE`/`EXPIRED`, plus a mild recency boost. Soft-deleted notes never appear.

### Answers (`search/AnswerWriter`, pure Kotlin; returns text plus the note IDs used)

- `WHERE_IS`, one current placement: "**Nightstand drawer.** You told me on 12 March, 8 months ago, so it may have moved." (the warning clause only when stale). Fresh: "Kitchen counter. You told me yesterday."
- Item currently lent: "Frank has it. You lent it to him on 5 January, 10 months ago."
- Several copies or several matching items: up to three lines, newest first, each with its date; then "Tell me which one if that's not it."
- Item known but superseded history only matters when asked ("where was it before") → not needed now.
- Two active notes that disagree: "Most recently the hall closet, 3 days ago. Before that you said the garage, 2 weeks ago."
- `WHO_HAS`: "Frank, since 5 January." Returned loans: "Frank had it; he gave it back on 2 March."
- `LOANS_WITH` / `ALL_LOANS`: a short list with dates.
- `WHAT_AT` / `WHAT_HERE`: "At Costco: ground beef (it was $15.99 on 3 April), AA batteries." Expired errands are left out; if only expired ones exist: "Nothing current. Earlier you wanted ground beef, but that was 3 months ago."
- `FREE`: no sentence; return the top 5 notes for display, each with its summary and age.
- Nothing found: "I don't have a note about your guitar." If there are near-misses: "The closest I have is 'guitar stand', in the hall closet (2 months ago)."
- Private (local-only) notes are answered like any other here; nothing leaves the phone in this card.
- Location of capture is mentioned only when it adds something: if the note has a `captured_place_id` different from "home", append "You were at the cabin when you said it."

## Scope

`QuestionParser`, `Retriever`, `AnswerWriter`, an `AskEngine.ask(question, locationSnapshot?) : Answer` that joins them, and tests. `Answer` = `spokenText`, `displayText`, `notes: List<NoteRef>`, `usedModel = false`.

## Out of scope

Any UI or voice (20), the hosted answerer (21), semantic/embedding search (see FEATURES.md, later), learning from clicks.

## Acceptance checks

All **unit (JVM)** against a seeded test database with a fixed clock (2026-11-10):

- The three questions from the brief ("where did i put my guitar", "who did i lend the drill to", "what did i need at costco") give the expected sentences for seeded notes.
- Stale wording appears for a 100-day-old `NORMAL` placement and a 3-day-old `DAILY` one, and not for a 3-day-old `NORMAL` one.
- "where are my specs" finds "reading glasses" when "specs" is an alias; finds nothing and offers the near-miss when it is not.
- "where are my keys" with "car key (spare)" and "house keys" lists both with dates.
- Item lent → "Frank has it"; item lent then returned then placed → the placement.
- Expired errands excluded from `WHAT_AT`; done errands excluded.
- Soft-deleted notes never returned.
- Unknown question → `FREE` with ranked notes; empty database → the "I don't have a note" sentence.
- Every answer string for a note-based answer contains an age or date (assert by pattern).
- If `questions-15.json` exists in test resources (from spike 04), report how many are answered correctly by rules alone.
- Performance: 5,000 seeded notes, `ask` returns in under 150 ms on the JVM.

## Notes and risks

- Answers are for the ear first: lead with the place or person, then the date. No "According to your notes".
- Do not answer from a low-scoring FTS hit as if it were certain; below a threshold, use the near-miss wording.
- Keep wording in one file so it can be reviewed and later localised.
