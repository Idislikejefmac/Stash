# 09 · Domain rules: fields, dates, supersession, expiry, staleness

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

The app's rules as plain Kotlin with no Android imports, fully unit-tested: what the structured fields are, how spoken dates become real dates, when a note replaces another, when notes expire, and when an answer must be marked stale.

## Depends on

07.

## Context

These rules are the heart of the product and the part most likely to be wrong in small ways, so they are isolated from the database and UI. Later cards call them.

### Fields (`domain/NoteFields.kt`)

The result of structuring a note, whatever produced it:

```kotlin
data class NoteFields(
    val v: Int = 1,
    val kind: Kind,                 // PLACED, LOAN, ERRAND, REMINDER, FACT
    val item: ItemRef?,             // name, aliases, volatility (DAILY/NORMAL/STORED), copyLabel
    val whereText: String?,         // "nightstand drawer", as said
    val person: String?,
    val loanDirection: LoanDirection?,   // LENT, BORROWED
    val placeName: String?,         // "Costco": where an errand applies
    val todoText: String?,          // "buy ground beef"
    val dates: List<DateExpr>,      // unresolved, see below
    val price: String?,
    val perishable: Boolean,
    val isCorrection: Boolean,      // "actually…", "I moved…"
    val confidence: Float
)
```

It serialises to and from JSON with kotlinx.serialization (this is what is stored in `note.structure_json`; unknown keys are ignored and `v` is checked).

### Dates

A structurer never knows today's date. It returns expressions: `ABSOLUTE(year?, month, day)`, `RELATIVE(amount, unit)` ("in 2 weeks"), `WEEKDAY(dayOfWeek)` ("next Friday"), each with a role: `EVENT` (when something happened: "loaned on Jan 5th"), `DUE` (when something should happen), `SEEN` (when a fact was observed: "$15.99 on April 3"). `DateResolver.resolve(expr, noteDate: LocalDate)`:

- `ABSOLUTE` with a year: that date.
- `ABSOLUTE` without a year, role `EVENT` or `SEEN`: the most recent such date on or before the note date. Role `DUE`: the next such date on or after the note date.
- `RELATIVE`: note date plus the amount (role `EVENT` with words like "ago" arrives as a negative amount).
- `WEEKDAY`, role `DUE`: the next such weekday strictly after the note date. Role `EVENT`: the most recent on or before.
- 29 February in a non-leap year resolves to 28 February.

### Supersession (`domain/Supersession.kt`)

Given a new note's fields and the item's existing current placements (possibly several, by copy label):

- New `PLACED` for an item with no copy label supersedes the current placement that also has no copy label. If the only existing placements have labels, it supersedes nothing and both are current (answers will list them).
- New `PLACED` with a copy label supersedes only the placement with the same label (case-insensitive).
- New `LOAN` with direction `LENT` supersedes the item's current placement(s) under the same label rule: the item is now with the person.
- `isCorrection` changes nothing about these rules; it is a hint used earlier to find *which* item is meant.
- The function returns the list of note IDs to mark `SUPERSEDED`; it does not touch storage.

### Expiry (`domain/Expiry.kt`)

`defaultExpiry(kind, fields, createdAt, settings)` returns an instant or null:

| Kind | Default |
| --- | --- |
| `PLACED`, `FACT` | never |
| `LOAN` | never |
| `ERRAND` | 45 days after creation; 14 days if `perishable` |
| `REMINDER` with a resolved `DUE` date | 7 days after the due date |
| `REMINDER` without | 90 days after creation |

Each default is a value in a `ExpirySettings` data class so the user can change it per kind later.

### Staleness (`domain/Staleness.kt`)

`staleness(kind, volatility, noteAge)` returns `FRESH` or `STALE`:

- `PLACED`: stale after 2 days for `DAILY` items, 90 days for `NORMAL`, 365 days for `STORED`.
- `LOAN` not returned: never stale (but see nudges in card 24).
- `ERRAND`/`REMINDER`: not applicable (they expire instead).
- `FACT`: stale after 365 days.

### Ages in words (`domain/AgeWords.kt`)

`ageInWords(from, to)`: "just now", "20 minutes ago", "3 hours ago", "yesterday", "5 days ago", "3 weeks ago", "8 months ago", "2 years ago". And `dateInWords(date, today)`: "today", "yesterday", "on Tuesday" (within 6 days), "on 12 March", "on 12 March 2025" (other year).

## Scope

Implement the four files above plus their tests. Nothing else.

## Out of scope

Reading or writing the database, producing fields from text (card 10), answer sentences (card 19), notification logic.

## Acceptance checks

All **unit (JVM)**:

- JSON round-trip of `NoteFields`, including unknown extra keys and a missing optional key.
- `DateResolver`: note date 2026-11-10 → "Jan 5th" `EVENT` = 2026-01-05; "Jan 5th" `DUE` = 2027-01-05; "April 3" `SEEN` = 2026-04-03; "in 2 weeks" = 2026-11-24; "next Friday" `DUE` = 2026-11-13; "Nov 10" `EVENT` = 2026-11-10; "Feb 29" `DUE` from 2026-11-10 = 2027-02-28.
- Supersession: plain replaces plain; labelled replaces same label only; plain does not replace labelled; loan (lent) supersedes placement; borrowed loan supersedes nothing.
- Expiry: every row of the table, plus changed settings.
- Staleness: boundaries (exactly 2 days is fresh, 2 days + 1 second is stale) for each volatility.
- Age and date words: each phrase, including month and year boundaries.

## Notes and risks

- Use `java.time`; pass a `Clock` everywhere rather than calling `now()` so tests are deterministic.
- "Months ago" by calendar months, not 30-day blocks, so 31 January to 1 March reads "1 month ago".
