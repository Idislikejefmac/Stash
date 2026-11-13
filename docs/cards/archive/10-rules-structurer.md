# 10 · Offline structurer (rules)

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

Turn the common spoken sentence shapes into `NoteFields` with no network and no model, so the app is useful with no API key, offline, and for private notes.

## Depends on

09.

## Context

`NoteFields`, `Kind`, `DateExpr` and friends exist in `app.stash.domain` (card 09). This card adds the interface every structurer implements and its first, offline implementation. A hosted-model implementation comes later (card 21) and is optional forever; this one always runs first.

```kotlin
// app.stash.model
interface NoteStructurer {
    suspend fun structure(text: String): StructureResult
}
sealed interface StructureResult {
    data class Ok(val fields: NoteFields, val source: StructureSource) : StructureResult   // RULES or MODEL
    data class Failed(val reason: String) : StructureResult
}
```

Input is speech-recognizer output: lower or mixed case, little or no punctuation, numbers as digits or words, fillers ("um", "okay so"). The structurer never receives the date, time or location.

### Shapes to recognise (case-insensitive, after stripping fillers and a leading "note that" / "remember that" / "private note")

| Kind | Patterns (X = item, Y = where, P = person or place, D = date expression) |
| --- | --- |
| `PLACED` | "i('m| am) putting X (in\|on\|under\|behind\|inside\|into\|at\|next to\|by) Y" · "i (put\|left\|stored\|stashed\|hid\|moved\|placed\|parked) X …Y" · "X (is\|are) (now )?(in\|on\|under\|…) Y" · "i left X with P" is a `LOAN` instead |
| `PLACED` correction | starts with "actually", or contains "i moved X to Y" → `isCorrection = true` |
| `LOAN` lent | "i (loaned\|lent\|gave\|handed) X to P [on D]" · "i (loaned\|lent) P X" · "P (borrowed\|has\|took) (my )?X" |
| `LOAN` borrowed | "i borrowed X from P [on D]" · "P (loaned\|lent) me X" |
| `ERRAND` | "(i need to )?(remember to )?(buy\|get\|pick up\|grab\|return\|drop off) X (at\|from\|when i('m\| am) at\|when i go to\|next time i('m\| am) at) P" · "next time i('m\| am) at P …" · the same without a place → `ERRAND` with `placeName = null` |
| `REMINDER` | "remind me to … [D]" · "i need to … by D" · "don't forget to …" |
| `FACT` | anything else |

Extras:

- **Item name**: strip leading determiners and possessives ("my", "the", "a", "our"); keep the rest as said. `copyLabel`: if the name begins with "spare", "extra", "other", "second", or a colour word, that word is the label and the remainder the name ("spare car key" → name "car key", label "spare").
- **Volatility**: `DAILY` for a small built-in list (keys, wallet, glasses, phone, purse, badge, earbuds, sunglasses, remote); `STORED` if the where-text contains attic, basement, storage, shed, garage shelf, top shelf; otherwise `NORMAL`.
- **Aliases**: for multi-word items, the last word and the last two words ("prescription reading glasses" → "glasses", "reading glasses").
- **Dates**: "on jan(uary) 5(th)", "april 3", "the 5th of january", "tomorrow", "today", "yesterday", "in N days/weeks/months", "N days/weeks ago", "next friday", "last tuesday", "by friday". Roles: in a `LOAN` → `EVENT`; in `REMINDER`/`ERRAND` after "by/on/before" → `DUE`; attached to a price ("it was only $15.99 on april 3") → `SEEN`.
- **Price**: `$15.99`, "15.99", "fifteen ninety-nine", "15 dollars" → kept as the string said, normalised to `$15.99` when unambiguous.
- **Perishable**: a small list of food words (beef, chicken, milk, eggs, bread, fish, produce, lettuce, berries, …).
- **Confidence**: 0.8 when a specific pattern matched with all its slots, 0.5 when a slot is missing or the item name is longer than 8 words, 0.2 for the `FACT` fallback.
- **Local-only detection** (`model/LocalOnlyDetector`, used by later cards before any hosted call): true if the text starts with "private note" or "just for me", or contains any of: password, passcode, pin, combination, social security, account number, routing number, "the code is", "code for". Returns the text with the marker phrase removed.

## Scope

`NoteStructurer`, `StructureResult`, `RulesStructurer`, `LocalOnlyDetector`, and tests. A table-driven test file so adding a sentence is one line.

## Out of scope

Writing to the database, resolving names against known entities, resolving dates to real dates (card 11 does all three), the hosted model.

## Acceptance checks

All **unit (JVM)**:

- The owner's four examples produce: (1) `PLACED`, item "110v outlet plug for the tesla charger" (accept "110 volt…"), where "frunk of the car"; (2) `PLACED`, item "prescription reading glasses", where "nightstand drawer", volatility `DAILY`, aliases include "glasses"; (3) `LOAN` `LENT`, item "guitar", person "frank", date `ABSOLUTE(null, 1, 5)` role `EVENT`; (4) `ERRAND`, place "costco", price "$15.99", date `ABSOLUTE(null, 4, 3)` role `SEEN`, todo containing "buy".
- At least 40 further table rows covering every pattern, including: no punctuation, fillers, "actually it's in the garage" (`PLACED`, `isCorrection`, item null), "frank has my drill", "i borrowed a ladder from dave", "pick up the dry cleaning", "remind me to call the vet in two weeks", "the wifi password is hunter2" (`FACT`, and `LocalOnlyDetector` true).
- If `app/src/test/resources/notes-40.json` exists (from spike 04), a test reports how many of its sentences the rules get right and fails below 60%. The model is expected to do better; this is the floor.
- Never throws on any input, including empty string and 2,000 characters of noise (property-style test with random strings).

## Notes and risks

- Keep the rules as data (a list of pattern + builder) rather than nested `if`s; they will be tuned often.
- English only. Other languages go through the hosted model or become `FACT`; say so in the class comment.
- Resist making this clever. When unsure, return `FACT` with low confidence: a searchable plain note is a better failure than a wrong item.
