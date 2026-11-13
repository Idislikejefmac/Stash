# 07 · Readings and subject pages

**Status: pass 1 built 2026-11-12, JVM only** (855 unit tests pass, 50 new; no schema change, no new dependency). Pass 2 (section C, rhythm, CSV) is not built. Nothing was installed on the phone: the phone checks wait for the owner. See "What was built, and what differs" at the end.

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

"Pool test: free chlorine 5, pH 7.2, alkalinity 70" becomes numbers he can ask about ("what was my pH last time?"), see over time, and trust, with mis-heard numbers flagged instead of silently stored. And every thing his notes are about (the pool, the car) gets one page.

## Depends on

03, 13. Better with 04.

## Context

Card 03 already writes, for a `READING` record, one `reading` row per value (`metric_id`, `value_text`, `value_num`, `unit`, `measured_at`, `subject_id`) and creates `subject` and `metric` rows by name. Nothing reads them yet.

Rules that must hold: values are stored as said and **never corrected**; a unit is stored only if he said one; the app shows readings back and **never says whether they are good** (no target ranges, no advice). Speech recognition mangles decimals ("7.2" → "72"), so an unlikely value is flagged for the attention list, not fixed.

**Prices are readings** (card 03): "the Tide is on sale for $24.99" is a reading of metric "price" for subject "Tide", and its record may carry the place. So "what was the Tide price last time?" is answered by this card with no extra work, provided the answer names the place when there is one.

**Build it in two passes.** Pass 1: sections A, B items 3, 4 and 6, D, and a plain list of a metric's values. Pass 2: the Things tab, subject pages, the chart, rhythm, CSV.

## Scope

### A. Queries (`data/ReadingRepository`)

1. `latest(metricId, n)`, `series(metricId, from, to)`, `latestSession(subjectId)` (the newest `READING` record for the subject with all its rows), `metricsFor(subjectId)`, `findMetric(words)` (match a spoken name against `metric.label`, `key` and `aliases`, across subjects; if the same label exists under two subjects, return both).
2. `sessionsFor(subjectId)`: reading records by date, for rhythm.

### B. Rules (`domain/ReadingRules.kt`, pure)

3. **Outlier:** for a new numeric reading with at least 3 earlier numeric values of the same metric: if the new value is more than 5 times, or less than one fifth of, their median (and the median is not zero) → attention `ODD_VALUE` on the record, with the median kept in the reason (`ODD_VALUE:pH:7.4`). Called from the pipeline after projection (an `AfterProjection` hook, card 13). Never changes the value.
4. **Unit drift:** a unit that differs from the metric's stored unit → attention `UNIT_CHANGED`. The first unit heard for a metric becomes `metric.unit`.
5. **Rhythm:** for a subject with at least 4 reading sessions, `usualGapDays` = the median gap between sessions; `overdue` when today is more than 1.5 × that gap after the last one (and at least 3 days). Returns a line for the weekly review ("Pool test: usually every 7 days; the last one was 12 days ago"). Per-subject switch "Mention when a test is overdue" (default off; offered once on the subject page when a rhythm is first detected).
6. **Answer wording** (pure, used by the question below and by card 08): `lastValue(metric)` → "pH was 7.2, two days ago. Before that: 7.6, nine days ago." With one value only: no "before that". When the reading's record has a place: "Tide was $24.99 at Costco, three weeks ago." Non-numeric: "Water clarity was 'cloudy', two days ago." `lastSession(subject)` → "Your last pool test was two days ago: free chlorine 5, pH 7.2, alkalinity 70."

### C. Screens (behind the app gate)

7. **Things** tab: subjects of type `THING` and `PLACE` that have any record, then items and people, each with counts. Search field.
8. **Subject page:** name and aliases (editable); **Latest readings** (one row per metric: label, latest value and unit, age, an arrow up/down/level against the previous value, and the flag if attention is set); **To do** (open tasks about it); **Where things are** (for a place-like subject: current placements whose where-text or subject points here; for an item: where it is now); **What happened** (events, newest first); **Good to know** (reference records); **Ideas**.
9. **Metric detail:** a line chart of `value_num` over time drawn with Compose `Canvas` (no chart library): points and a line, the first and last dates on the axis, the minimum and maximum values labelled, the latest point labelled. Below it the list of values with dates; each row links to its capture. A reading can be **edited** (value, unit) or **removed** here; an edit writes a `revision`, pins the record, and never touches the capture's raw words.
10. Readings export to CSV (subject, metric, value, number, unit, date and time) through the system share sheet, from the subject page.
11. The Notes list shows a `READING` record as its values on one line.

### D. The questions

12. Extend the prototype's answering so that these are answered by code, with no model: "what was my <metric> (last time)", "what's my latest <metric>", "last <subject> test/reading(s)/results", "how is the <subject>" (→ last session). The metric and subject names are matched against the tables, so only things he has actually logged match. If a metric name exists under two subjects, answer for the one logged most recently and name it ("Pool pH was …"). (Card 08 moves this into the router.)

### E. Hook-ups

13. `planWeekly` (card 06) includes the overdue-rhythm lines for subjects with the switch on.
14. `ExtractContext` (cards 03/04) already sends known subjects with their metric labels; confirm metrics created here appear there.

## Out of scope

Target ranges, dosing or any advice (not planned). Correlations. Entering readings by form (he speaks them). Photos of test strips. Charts with more than one metric. The attention list screen (09).

## Acceptance checks

- **Unit:** outlier: with history 7.4, 7.6, 7.2 a new 72 is flagged and 7.8 is not; fewer than 3 values → never flagged; zero median → never flagged.
- **Unit:** rhythm: sessions every 7 days, last one 12 days ago → overdue line; 9 days → none; 3 sessions → none.
- **Unit:** answer wording for one value, two values, non-numeric, a session, and a price with a place.
- **Unit:** `findMetric`: "ph", "pH", "free chlorine", "chlorine" (alias), a name under two subjects.
- **Unit:** question patterns, with negatives ("what was my plan last time" must not match unless a metric called "plan" exists).
- **Unit:** editing a reading writes a revision and pins the record; "Read it again" on that capture keeps the edited value.
- **Unit (Robolectric):** the subject page lists metrics with latest values for a seeded database; the chart composable draws without crashing for 1, 2 and 50 points.
- **Real phone, S25 Edge:** say the pool sentence three times on different days (or use a debug action to back-date two of them). The Pool page shows three metrics with arrows; the pH chart shows three points. Ask aloud "What was my pH last time?": the coded answer with the previous value. Say "pool test pH 72": it is stored as 72 and flagged. Export the CSV and open it. Log what the recogniser did with the decimals (this is RISKS.md R12's evidence).

## Notes and risks

- Run the R12 spike first if it has not been done: if the recogniser cuts the pool sentence short most of the time, tell the owner before building charts.
- Keep the chart plain. It needs to answer "is it drifting?", nothing more.
- Health numbers (blood pressure, weight) will work the same way. Do not add health-specific wording, ranges or warnings.


## What was built, and what differs from the card (2026-11-12, pass 1)

Built (under `app/src/main/java/app/stash/` unless noted):
- `domain/ReadingRules.kt` (pure): `ReadingFact` and `ReadingSession`; `outlierMedian(value, earlier)` (more than 5 times or under one fifth of the median of at least 3 earlier numbers; a zero median or a word value never flags), `oddValueReason` (`ODD_VALUE:pH:7.4`), `unitDrifted` (both units known and different), `unitToStore` (the first unit heard), `flagText` (plain words, never a judgement).
- `domain/ReadingAnswers.kt` (pure): `ReadingAnswers.lastValue` and `lastSession` (the card's wording, ages in words up to twelve, a place named when there is one, a word value quoted, a price named by its subject), and `ReadingQuestions.parse`, which matches only names in a `ReadingCatalog` (metrics and subjects that have readings): "what was my X (last time)", "what's my latest X", "last <subject> test/reading(s)/results", "how is the <subject>", and the price forms ("the Tide price", "price of Tide", "Tide's price").
- `data/ReadingRepository.kt` (`repo.readings`): `latest`, `series`, `all`, `latestSession`, `metricsFor`, `sessionsFor`, `findMetric`, `catalog`, `summaries`, `answer`. Every function takes `includeSensitive` with no default. `data/Daos.kt`: `ReadingRow`, `ReadingDao.visible/forMetric/forRecord/get`, `MetricDao.get/update` (queries only; no schema change).
- `pipeline/ReadingHook.kt`: an `AfterProjection` hook registered in `Container`. It sets `ODD_VALUE:<metric>:<median>` or `UNIT_CHANGED` as the record's attention (never over an existing reason, except the weak `SAME_AS`), and stores the first unit heard on a metric that has none. It never changes a value or a unit of a reading. Event: `READING FLAGGED` (n1 1 = odd value, 2 = unit changed; a row id; no words).
- `ui/ReadingsScreen.kt` and a fifth tab, "Readings" (before Settings): one card per metric (subject, label, latest value and age, count, a note when the latest is flagged); tapping opens the plain list of every value with its age, place and flag; Back returns. `MainViewModel.submitQuestion` asks `repo.readings.answer` after the task questions and before the word search; the answer is marked as made by code (no model).
- Subject and metric resolution was already built by card 03 (`SubjectResolver`, `MetricResolver`: exact names merge, a near name creates a new subject and flags `SAME_AS`, nothing merges silently); it is used unchanged.

Tests (50 new): `ReadingRulesTest` (pure: outlier edges, drift, wording, questions and negatives), `ReadingsTest` (database: the pool sentence as three rows, a price at a place, the coded answers, queries, `findMetric`, flags, Private, deleted notes, the model context, no app code passing true), `ReadingsScreenTest` (Robolectric: the list, a metric's values, a price's place, empty, Private).

Differs from the card:
- **Pass 1 only.** Not built: the Things tab, the subject page, the chart, edit and remove of a reading (so the revision-and-pin acceptance check for edits waits for pass 2), the Notes row line for a reading (item 11), rhythm (B5) and its weekly-review line (E13), the CSV. `sessionsFor` exists for rhythm.
- **The tab is called "Readings"**, not "Things": it is the card's "plain list of a metric's values". Pass 2 replaces it with Things.
- **No app gate yet**, so the Readings tab, the question answering and every other caller pass `includeSensitive = false`; a Private note's readings are never drawn. A test scans the app's sources so that nothing passes true.
- **Flags compare within the same privacy class**: a note that is not Private is never compared with, and its reason never holds a number from, a Private note (as card 05's hook does).
- **The first unit heard** is also stored when a metric first appears without one and a later note says one; the earlier readings keep having no unit.
- **A price reads "Tide was $24.99 at Costco"**: for the metric "price" the answer names the subject instead of the word price. A price said without a dollar sign or word ("24.99") is shown as said, without "$".
- **`ODD_VALUE` compares by size**: a sign change alone is not odd.

Needs the owner (nothing installed here):
1. `.\gradlew.bat installDebug` over the existing app (never uninstall: it destroys the notes).
2. Say the pool sentence three times on different days, then open the new **Readings** tab: three pool metrics, each with its latest value; tap pH to see every value with its age.
3. Ask aloud "What was my pH last time?": the coded answer with the previous value ("pH was 7.2, two days ago. Before that: ..."). Ask "How is the pool?": the last session.
4. Say "Pool test: free chlorine 5, pH 72, alkalinity 70": pH is stored as 72 and shown as said, with the line "Very different from the usual (...). Check it was heard right." on its list.
5. Say "The Tide is on sale for 24.99 dollars at Costco" and ask "What was the Tide price last time?": the answer names Costco.
6. Write in `docs/EXPERIMENT_LOG.md` what the recogniser did with the decimals ("7.2" or "72"), as RISKS.md R12's evidence. Pass 2's phone checks (arrows, chart, CSV) are not yet possible.
