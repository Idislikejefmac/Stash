# Retired cards

On 2026-11-11 (revision 1) the thirty first-design cards were moved to [archive/](archive/) and replaced by twelve new ones. Nothing was deleted.

## Why

The first cards were written before any code existed, for three households on three phones, with six spikes to run on all of them. Since then:

- a prototype was built directly (capture, speech, corrections, simple structuring, asking, settings), covering parts of old cards 07, 09–13, 15, 18–21 in thin form;
- the scope was cut to the owner's own phone (decision D34);
- the product changed from "where did I put it" to a capture-anything inbox with tasks, readings and events (D35), with a new two-layer data model (D36) that the old cards' schema does not match.

Editing thirty cards in place would have left most of them half-true. The new cards start from the prototype as it is.

## Reading an archived card

- Their links to `00-READ-FIRST.md` no longer resolve: that page is one folder up and has been rewritten for the new design. The vocabulary they use (`PLACED`, `ERRAND`, `FACT`, `note`, `reminder`, `NoteStructurer`) is the first design's. The first design itself is at `docs/archive/DESIGN-v1-2026-11-10.md`.
- Do not build from one unless a current card sends you there (card 11 does).

## What happened to each

| Old card | Status | Where its content went |
| --- | --- | --- |
| 01 Spike: triggers from a locked phone | Retired for now | The prototype implements the notification action and tile; the owner's test script (README) records whether they work locked. The other triggers and the other phones are deferred. Revive for the family rollout. |
| 02 Spike: flip-phone cover screens | Deferred | Other phones only. |
| 03 Spike: speech to text | Retired | The platform recogniser is in use and transcribed correctly (D46). Its remaining question is RISKS.md R12, a fifteen-minute test. |
| 04 Spike: hosted model structuring and cost | Replaced | New card 04 (scoring is part of the card, not a separate spike). |
| 05 Spike: geofence reliability | Deferred, still valid | Referenced by new card 11. |
| 06 Spike: installing and updating | Deferred | Other phones only. |
| 07 Project skeleton | Done by the prototype | — |
| 08 Encrypted database | Replaced | New cards 01 (schema, migration) and 02 (encryption). |
| 09 Domain rules | Replaced | Supersession and staleness live on in new cards 03 and 08; expiry in 05. |
| 10 Offline structurer | Replaced | New card 03 (rules v2). |
| 11 Structuring pipeline | Replaced | New card 03. |
| 12 Capture | Done by the prototype | Save-first, vibration and Undo are added in new card 03. |
| 13 Speech engine and retries | Retired | No bundled engine, no stored audio (D46). |
| 14 Location and places | Deferred | Referenced by new card 11. Capture already stores the last known fix. |
| 15 Triggers: notification, tile, widgets | Partly done by the prototype | Widgets and extra triggers deferred. |
| 16 Assistant role | Deferred | Still the best one-button trigger; revive when the owner wants a hardware key (QUESTIONS.md Q1). |
| 17 App lock | Replaced | New card 02. |
| 18 Notes screens | Partly done by the prototype | Extended by new cards 01, 05, 07, 09. |
| 19 Search and answers | Replaced | New card 08. |
| 20 Ask by voice or text | Partly done by the prototype | New card 08. |
| 21 Hosted model | Replaced | New cards 04 (extraction) and 08 (questions). |
| 22 Reminders, expiry, notification budget | Replaced | New cards 05 (lifecycle, expiry) and 06 (nudges, budget). |
| 23 Place reminders | Deferred, still valid | Referenced by new card 11. |
| 24 Loans | Folded in | Loans are a record kind (card 03), returned by speaking (03, 05), mentioned in the weekly review (06), asked about in 08. |
| 25 Encrypted backup and restore | Replaced | New card 10 (same design). |
| 26 Export and delete everything | Replaced | New card 10. |
| 27 First run, simple mode, accessibility | Deferred | For the parents. Accessibility rules stay in 00-READ-FIRST. |
| 28 Weekly digest | Replaced | New card 06 (weekly review). |
| 29 Release and updates | Deferred | Other phones only. |
| 30 Sealed inbox | Deferred, still valid | The fix for the known weakness described in `docs/PRIVACY.md`. Its table names need translating to the new model before it can be built. |

## Revisions 2 and 3 (2026-11-11)

Two revision 1 cards were **split** and their originals moved to [archive/rev1/](archive/rev1/). Nothing was deleted. Eleven cards were added. The other ten were amended in place (their numbers and purposes are unchanged). Card numbers are now names, not the order; the build order is in the README.

| Revision 1 card | Status | Where its content went |
| --- | --- | --- |
| 02 App gate and encryption at rest | Split | New [02](02-app-gate.md) (the gate, plus the second unlock for Private items) and new [14](14-encryption-at-rest.md) (SQLCipher and the release-signed build), preceded by spike [15](15-spike-sqlcipher.md). Reason: the two protect against different things and belong at different moments (`docs/STORAGE.md`) |
| 03 Extraction pipeline, rules v2, private notes | Split | New [03](03-extraction-core.md) (the pure Kotlin half: record shape, private check, checks, dates, projection, rules) and new [13](13-capture-pipeline.md) (save-first, background worker, capability switches, event log, outbound register). Reason: the old card said itself that it was too large |

| New card | What |
| --- | --- |
| 13 | Capture pipeline, capability switches, event log (from old 03) |
| 14 | Encryption at rest and the release build (from old 02) |
| 15 ◆ | Spike: SQLCipher with this Room and phone |
| 16 | Pictures: share, camera, notes that ask for one |
| 17 | Trips built from notes |
| 18 | Leaving list and departure signals |
| 19 | Watches |
| 20 ◆ | Diagnostics ("Try a sentence" now; the report at product stage) |
| 21 | Barcodes and QR codes |
| 22 ◆ | A second model provider and the conformance run |
| 23 | Explanation cards and "What leaves my phone" |

Amended in place: 01 (foundations: signing key, timestamps, tombstones, defined delete, revisions, event log; core tables only), 04 (desk spike first; provider seam; filing earlier notes; no text logging), 05, 06 (one budget, two classes; posting from a stored plan; private tasks excluded), 07 (prices), 08 (answer trace; place and picture filters; private matches as a count), 09 (new reasons), 10 (before encryption; tombstones; pictures in the backup), 11 (event budget), 12 (unchanged).

First-design card 30 (sealed inbox) remains archived; `docs/STORAGE.md` now says when it is worth building and which three seams earlier cards keep ready for it.

## Card 16 split (2026-11-12)

One revision 3 card was **split** into twelve and its original moved to [archive/rev3/](archive/rev3/). Nothing was deleted. Unlike the two earlier splits, the old number was **not** reused for one of the halves: `16-share-and-pictures.md` is now a short index, because 00-READ-FIRST and cards 02, 09, 10, 21, 24 and 25 all say "card 16" and must keep leading somewhere. Decision D79.

| Revision 3 card | Status | Where its content went |
| --- | --- | --- |
| 16 Pictures: Share to Stash, a camera in the app, and notes that ask for a picture | Split; the number is now an [index](16-share-and-pictures.md) | Section A (attachment model, file store, size, delete) → [16a](16a-attachment-store.md) and [16b](16b-picture-size-and-format.md). Section B (share, camera, annotate, merging, text to the model) → [16c](16c-share-and-annotate.md), [16e](16e-camera.md), [16f](16f-picture-text.md), [16j](16j-picture-text-to-model.md). Section C (note first) → [16g](16g-picture-offer.md). Section D (finding) → [16h](16h-finding-pictures.md). Section E (recent picture offer) → [16i](16i-recent-picture-offer.md). Section F (capabilities, backup exclusion) → each card adds its own capability row; the explanation text is [16k](16k-picture-explanations.md); the backup exclusion is in 16a. The owner's size decision at the foot of the old card → 16b. "Out of scope: hosted vision" → designed, not scheduled, as [16l](16l-hosted-vision.md) |

New in the split, not in the old card: any number of pictures per note, with ordering, adding later, removing one and the thumbnail strip ([16d](16d-pictures-on-a-note.md)); the gallery photo picker as a named route; the attachment store written so that a voice note's audio (D77) can use it unchanged.

Reason: the old card was one piece of work touching a migration, an encrypted file format, three screens, a camera, a text reader, the search index, the router and the outbound register. A cheaper builder following it literally could not finish or test it in one pass, and the risky parts (encryption, delete, what leaves the phone) could not be given to a stronger builder separately.

