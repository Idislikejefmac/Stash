# 04 · Spike: can a cheap hosted model structure notes and answer questions reliably

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

Measure, before building around it: (1) whether a Haiku-class model turns note text into correct fields using structured output; (2) the real token counts and cost; (3) whether Anthropic's official Java SDK runs inside an Android app.

## Depends on

None. Needs an Anthropic API key from the owner (see QUESTIONS.md Q3); put it in `local.properties` or an environment variable, never in the repository.

## Context

The app sends a hosted model **only the text of a note** and gets fields back, and for questions sends the question plus a few notes. The model must not be told the date, time or location. Dates in a note come back as expressions the app resolves itself.

Model to test: `claude-haiku-5-5` (list price $0.10 per million input tokens, $0.50 per million output, as of early November 2026). Compare with `claude-haiku-4-5` ($1 / $5) only if 5.5 is unavailable on the owner's account. Facts about the API that matter (from Anthropic's SDK guidance; confirm against the SDK's README before writing code, do not guess class names):

- Use the official SDK for the JVM: `com.anthropic:anthropic-java`. Requests go to the Messages API.
- Ask for JSON that matches a schema with **structured outputs** (`output_config.format` with a JSON schema; the SDK has helpers). Every property must be listed as required and `additionalProperties` false; use `null` for "absent".
- On Haiku 5.5, thinking is on by default and counts as output tokens; set effort to `low`. Do not send `temperature`, `top_p` or `top_k` (non-default values are rejected).
- Always check `stop_reason` before reading content. `"refusal"` and `"max_tokens"` are possible and must be treated as "no result".
- Set `max_tokens` around 2000 so thinking plus a small JSON object fits.

Draft schema (adjust during the spike, record the final one in the log):

```json
{
  "v": 1,
  "kind": "PLACED | LOAN | ERRAND | REMINDER | FACT",
  "item": { "name": "string", "aliases": ["string"], "volatility": "DAILY | NORMAL | STORED", "copy_label": "string|null" } ,
  "where_text": "string|null",
  "person": "string|null",
  "loan_direction": "LENT | BORROWED | null",
  "place_name": "string|null",
  "todo_text": "string|null",
  "dates": [ { "role": "EVENT | DUE | SEEN", "type": "ABSOLUTE | RELATIVE | WEEKDAY", "year": "int|null", "month": "int|null", "day": "int|null", "amount": "int|null", "unit": "DAY | WEEK | MONTH | null", "weekday": "int|null" } ],
  "price": "string|null",
  "perishable": "boolean",
  "is_correction": "boolean",
  "confidence": "number 0..1"
}
```

## Scope

1. A plain Kotlin/JVM Gradle project in `spikes/modellab/` with a test set of at least 40 sentences in a JSON file, each with the expected fields. Include: the owner's four examples; corrections ("actually the glasses are in the car"); items with copies ("my spare car key"); borrowed items; errands with and without a place; reminders with relative dates ("in two weeks", "next Friday"); facts with prices; three sentences with transcription errors ("I loaned my guitar to Frankie on Jan fifth", "putting the plug in the trunk frunk"); two that are not notes at all ("um, never mind").
2. A runner that calls the model once per sentence, validates the JSON, compares with the expected fields (exact for kind, direction, dates; case-insensitive containment for names and where), and prints a table plus `usage` input and output tokens per call.
3. A second set of 15 questions, each with 6 candidate note texts (some irrelevant, some superseded, with ages in words), expected answer gist, and two where the right answer is "I don't have a note about that". Check the answer is under 40 words, mentions the note's age, and does not use an irrelevant note.
4. Compute cost per 100 notes and per 100 questions from measured tokens.
5. A minimal Android app (one activity, one button) in `spikes/modellab-android/` that makes one structuring call with the same SDK, built in **release** mode with R8 minification on, run on the S25 Edge. Record: APK size added by the SDK, any missing-class or reflection failures, whether a call succeeds.
6. Log everything in `docs/EXPERIMENT_LOG.md` under "Language model".

## Out of scope

The rules-based structurer, prompt caching (the prompt is too short to cache), streaming, tool use, any other provider.

## Acceptance checks

- **JVM run (no phone):** at least 36 of 40 notes structured correctly; 0 schema-invalid replies; at least 13 of 15 questions answered acceptably, including both "no note" cases.
- **Cost:** measured cost per 100 notes at or under $0.10 on Haiku 5.5. If it is much higher, find out why (thinking tokens are the usual cause) before proceeding.
- **Real phone, S25 Edge:** the Android release build completes a call. If the SDK cannot be made to work on Android within the session, record exactly why; the fallback for card 21 is a small hand-written client for the single Messages endpoint, and the owner should be told that this departs from "use the official SDK".
- Results logged. No unit tests are kept from this spike, but **keep the 40-sentence and 15-question files**: cards 10, 19 and 21 reuse them as test data (`app/src/test/resources/`).

## Notes and risks

- Never print or commit the API key. Add `spikes/**/local.properties` to `.gitignore` if not already covered.
- Run the note set twice; if results differ between runs, record how many and which.
- If the model keeps "helpfully" resolving dates to a year, tighten the instruction: it must return the expression as said and leave `year` null when no year was spoken.
