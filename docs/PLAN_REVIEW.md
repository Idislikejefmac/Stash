# Plan review

2026-11-11. An independent read of the whole plan after folding in revision 2 (trips, pictures, watches, diagnostics, permission switches, the storage decision) and revision 3 (providers and tiers, explanation cards, sensitive content, picture flows in both directions, barcodes). It says what was found, what was changed and why, what was left alone, and what to do in the first week.

Nothing in this review was tested on a phone. Where it leans on a fact from the web, the fact was read by a research helper on the day and is marked in the document that uses it.

## The verdict in one paragraph

The development plan should change, in three ways. **First, the order:** understanding comes before hardening, and there is a hard stop for two weeks of real use before any of the new features is built. **Second, a short list of storage rules goes into the very first card**, because they are cheap now and expensive later, and one of them (a signing key that does not change) guards against the likeliest way to lose every note. **Third, the new material is added as separate cards at the back**, not woven into the core, with watches last. What should not change: the two-layer data model, "the model proposes, code decides", the bounded tool loop, the digest-first reminders. They absorbed three rounds of new requirements without bending, which is the best evidence so far that they are right.

The plan's real weakness is not in any document. It is that the plan has now been revised three times in two days while the two cheapest experiments in it, twenty-five minutes of the owner's time, remain undone.

## What changed in the plan, and why

| Change | Why |
| --- | --- |
| **New build order** (README): 01 → 15 (spike) → 03 → 13 → 04 → 05 → 06 → 07 → 08 → **stop: two weeks of use** → 09 → 10 → 02 → 14 → 23 → 16 → 21 → 17 → 18 → 19. Optional: 11, 12, 20, 22 | The two things least known are whether a model files his real speech well and whether he keeps using it. Nothing should be built on top until they are known. Revision 1 put encryption second "before the model starts receiving unfiltered speech"; but what protects against *sending* is the private-note check, not encryption, and a debug build exposes its own data whatever the database does |
| **Card 02 split** into 02 (app gate) and 14 (encryption plus the release build) | They have different values and different right moments. Encryption belongs with the end of the debug build; the gate is half a day and can go just before it |
| **Card 03 split** into 03 (pure logic) and 13 (pipeline, capability switches, event log, outbound register) | The old card said itself that it was too large. The cheaper builder model does better with a card that is all unit tests followed by a card that is wiring |
| **Card 01 gained "foundations"**: stable signing key; `updated_at`; tombstones; one defined delete; every owner action recorded; an event-log table; opaque ids; core tables only | STORAGE.md's "decide now" list. Perhaps a day's work in total; each is expensive or impossible to add truthfully later |
| **Feature tables arrive with their cards** instead of all in card 01 | Revision 1's "card 01 creates every table" would have meant guessing the columns for trips and watches before using either |
| **One notification budget** with two classes (2 scheduled, 2 event, 4 in all) | Card 11 already carried an exception to "2 a day"; leaving lists and watch matches would each have added another |
| **Backup (10) moved before encryption (14)** and before the release build | Both are moments when a slip loses the notes. The safety net goes up first |
| **Spike card 15** (SQLCipher, one to two hours) straight after card 01 | Turns "encryption can wait" from a hope into a measured fact |
| **Answer trace added to card 08**; text logging forbidden from card 13 on | Cannot be reconstructed afterwards; costs little now |
| **`place`, `spot`, `photo_hint` added to the extraction shape now** | The shape is free to change only until card 04 records replies against it |
| **`localOnly` became one Private mark** with wider effect (rev 3) | The owner's confirmed approach. It touched cards 02, 03, 06, 08, 09, 13, 19, 20 |
| **A provider seam in card 04**; a second adapter as optional card 22 (rev 3) | The seam is free now. A second adapter is real upkeep and waits until someone needs it |
| **Capability switches and an outbound register in card 13**; explanation cards as card 23 (rev 2, 3) | Tiers later become a list; the "What leaves my phone" screen cannot fall behind the code |
| **New cards at the back:** 16 pictures, 21 codes, 17 trips, 18 leaving list and signals, 19 watches, 20 diagnostics | Each stands alone and none is needed to learn whether the core works |
| **"Build in two passes" notes** on cards 06, 07, 08, 09, 10 | See "Over-built" below |

### What revision 3 changed in what revision 2 had already written

- **Card 16 was rewritten.** Revision 2's version had no camera in the app ("out of scope") and treated text read from pictures as never leaving the phone under any setting. Revision 3 needs an in-app camera (it is also the only route that keeps a photo out of the gallery) and allows the picture's *text* to go to the model behind its own switch, off by default.
- **"Local-only" was renamed and widened everywhere.** Revision 2's cards said "local-only" meaning "not sent to the model". It is now `sensitive`, shown as Private, and also means a second unlock and no reminders.
- **Card 19:** a private note now proposes no watch at all (revision 2 let it propose).
- **Card 20:** reports now exclude pictures, picture text, code payloads and everything private outright.
- **The order** gained card 23 in the hardening block and card 21 after 16. The first week gained two paper tests (labels; a day at tier 0 later).

### Where the addendum's instruction was not followed exactly, and why

1. **The picture offer is a button only, not also a notification action, and cannot be accepted by saying "yes".** The addendum allowed a notification "subject to the budget" and voice acceptance. A notification for "want to add a picture?" spends one of two daily event slots on a nicety and is exactly the nag the owner fears; listening for "yes" means reopening the microphone after such notes and guessing. Both are left as a question (Q43) rather than built.
2. **Private tasks are left out of reminder counts entirely**, as the addendum says, but the review flags the cost: a private to-do never reminds. That is a real loss and is put to the owner (Q41) with the alternative.
3. **The automatic marking of secrets stays automatic.** The addendum says the app suggests the mark "without ever forcing it". For passwords, codes and personal barcodes the mark is turned on by default and can be removed; only softer categories are suggestions. Without that, a gate code would be sent to a model before any suggestion could be answered.

## Contradictions found, and how each was fixed

| # | Where | The contradiction | Fix |
| --- | --- | --- | --- |
| 1 | DESIGN §1, FEATURES, D43 vs watches | "It does not browse"; "web search: won't"; "the model has no tools except read-only lookups in the app's own data" | Reworded: the question loop still never browses; a watch is a separate, fixed, approved check. D43 marked amended by D64 |
| 2 | PRIVACY vs pictures | "Contacts, calendar, photos and other apps' data are never read" | Reworded: the photo library is not read unless one named switch is on; the app sees pictures handed to it |
| 3 | DESIGN §8 / D48 vs card 11 | "At most 2 notifications a day" against "an arrival may exceed the budget by one" | One budget with two classes; card 11 reworded; D48 marked amended |
| 4 | DESIGN §10 vs card 04 | "No note text in release logs" against "in debug builds, log … the extractor result" (which is note content, and the debug build is where the real notes are) | Events and codes only, in every build; card 04's note rewritten; a source-scan test in card 13 |
| 5 | 00-READ-FIRST vs new features | "The only code allowed to open a network connection is in `model.hosted`" | A named list of network packages, added to by cards 19 and 21, enforced by the same test |
| 6 | DESIGN §4.2 vs card 01 | "IDs are UUID strings" against migrated ids like `<note id>-r0` | "Opaque strings; no code parses one" |
| 7 | 00-READ-FIRST | "Card 01 creates all tables so later cards need no schema change" against five new feature tables | Core tables in card 01; feature tables with their cards, each with a migration |
| 8 | Card 10 | Import "replaced only if the snapshot's capture is newer", with no timestamp to compare and nothing to stop a deleted note returning from an old backup | `updated_at` and tombstones (card 01); card 10 reworded |
| 9 | DESIGN §4.2 "soft delete for 30 days, then real" | No card ran the purge, and nothing said what a real delete removes (the model's stored output still held the words) | Card 01 item 9 defines the delete; card 05's daily worker runs it |
| 10 | Card 10 depends on 02; D47; Q28 | "Switch to a release build when you start relying on it" with nothing noting that a differently signed build forces an uninstall | Card 01 item 0, card 14, Q38, RISKS R21; Q28's recommendation corrected |
| 11 | README, D47 | "Card 02 should be done before the app is used freely with the hosted model on": the stated reason is about sending, which card 03's check governs, not card 02 | The order's reasoning rewritten (D56) |
| 12 | FEATURES, Q15 vs revision 2 | "Photo attached to a note: Later … the opposite of the flow" | Promoted with cards 16 and 21; Q15 marked answered |
| 13 | EXPERIMENT_LOG header | "Spike cards (docs/cards/01 to 06)" pointed at archived cards | Reworded |
| 14 | Card 08 vs DESIGN §7.4 | "Also in your private notes: …" shown under an answer, against revision 3's "no previews of private items" | A count line and a second unlock |
| 15 | DESIGN §7.3 vs revision 3 | "A capture read only by rules is re-read by the model when one becomes available", silently and in bulk | One confirming tap with a count and a cost (card 04) |
| 16 | Prototype vs PRIVACY | The `localOnly` column exists and nothing sets it (found in revision 1); revision 3 retires it | Migrated to `sensitive` in card 01 |
| 17 | Revision 2's own card 16 vs revision 3 | "Out of scope: a camera inside the app"; "picture text is never put in an extraction request" | Rewritten; see above |
| 18 | PRIVACY "nothing leaves the phone with the model off" vs ML Kit | Google's on-phone libraries report usage figures to Google | Stated in PRIVACY, on the explanation card, and as a fixed line on "What leaves my phone"; Q46 |

Left as they were, deliberately: DESIGN.md's revision 1 text in §§1–15 is marked up rather than rewritten, with the full pre-revision copy in `docs/archive/`; card numbers were kept (see below).

## Hidden dependencies and sequencing traps

1. **The signing key.** The one item that can destroy the project's data at any moment and is fixed in fifteen minutes. First step of card 01; first item of the first week.
2. **The extraction shape freezes at card 04.** Recorded replies are test fixtures; changing the shape afterwards means re-recording everything, for every provider. Hence `place`, `spot` and `photo_hint` now. The trip `outing` object and the `watch` hint are added later as schema versions 2 and 3, each with new recordings; that cost is accepted because those features may never be built.
3. **Backup before encryption before release build.** In that order, or a failure in the second or third is unrecoverable.
4. **The app gate must exist before the Private mark means much.** Until card 02, "Private" stops sending and reminders but the second unlock is not there. Cards built before 02 store and honour the mark; card 02 adds the lock. PRIVACY.md's "prototype today" section covers the gap.
5. **Every screen card after 02 must use `PrivateContent`.** A list added by card 17 or 21 that reads rows directly would show private items. R34's walking test is the guard.
6. **Three seams for the sealed inbox** (one way in; background code that tolerates a closed database; reminders posted from a stored plan) are in cards 13 and 06. If a builder "simplifies" them away, the sealed inbox goes back to being the most intricate card in the project.
7. **Schema version numbers depend on build order.** Feature cards say "the next free version", not a number.
8. **Card 17 (trips) before card 18 (signals), and a week of paper notes before either.** If he does not make notes while out, trips from notes show nothing, and that should be known before the builder spends days on stops and screens.
9. **Card 16 before 21**: codes reuse the camera, the annotate screen and the attachment table.
10. **Card 23 needs something real to show**, so it follows 04; but its registers are built in 13, so nothing has to be retrofitted.
11. **The stored position is stale** (the prototype reads the last known fix). Harmless for "where was I roughly"; possibly fatal for naming a stop. Measured before card 17, fixed there if needed.
12. **Providers' documentation was moving on the day it was read.** Card 22 must start from the live pages, not from PROVIDERS.md.

## Risks: what was missing or under-rated

Added as R21–R35 in RISKS.md, each with a cheap test. The ones that were missing rather than merely new: **R21** (losing the notes in development), **R25** (the plan outrunning the evidence), **R27** (lock-screen leaks multiplying with notification types), **R34** (a private item shown by accident). Under-rated before: **R14**, which was about one daily digest and is now about five kinds of notification.

**Top five, re-ranked:** R11 extraction quality · R19 he stops using it · R21 the notes are lost in development · R14 notifications become a nag · R22 tier 0 feels broken (with R12, numbers mis-heard, close behind). The table at the top of RISKS.md gives the cheapest test for each.

## Where the plan is over-built for "does it feel useful to me?"

The first usable version needs less than the cards describe. Each of these cards now carries a "build it in two passes" note; pass 1 is what the two-week check needs.

| Card | Pass 1 (enough to judge) | Pass 2 (after the check) |
| --- | --- | --- |
| 06 Reminders | The daily digest, the budget, count-only lock screen | Afternoon check, weekly review and its screen, self-pausing |
| 07 Readings | "Last value" answers, a plain list per metric, the outlier flag | Things tab, subject pages, chart, rhythm, CSV |
| 08 Asking | Router, coded answers, the trace, plain search | The tool loop, read-aloud |
| 09 Attention | A generic card for every reason, plus the two questions that change data | Bespoke questions, record editing, merging, developer stats |
| 10 Backup | Encrypted backup, restore, JSON export, delete everything | Schedule, QR code, test restore, Markdown and CSV |

Also thinner than it looks: card 20 is a debug screen now and nothing else until there are users; card 22 is optional; card 18's geofence and activity signals are named and not built; card 19's second engine can wait for the first to run for a month.

What is **not** over-built, though it may look it: card 04's seed set and scorer (the only way the central risk gets a number), card 01's consistency check and migration test, and the capability and outbound registers in card 13 (a few dozen lines that every later privacy claim rests on).

## What the plan protects now for a possible product, without building it

| Protected | By | Cost now |
| --- | --- | --- |
| Encryption, sealed inbox, backup, sync all remain cheap | STORAGE.md's eleven rules in cards 01, 06, 13 | about a day |
| Lighter permission tiers | One capability per ability, each with a do-nothing version | built as features are built |
| A phone-only product and an on-phone-model product | Tier 0 treated as the app; nothing fails silently; later filing | wording, and one confirming dialog |
| Other model providers | The seam in card 04; provider-neutral stored data | a few interfaces |
| Support without reading notes | Answer trace; event-only logs; "Try a sentence" | perhaps half a day in cards 08 and 13 |
| A truthful "what leaves my phone" | The outbound register that requests cannot bypass | one argument on one function |
| Sensitive content handled without the app judging it | Private-by-default pictures; one mark | design discipline, no detector |
| The name and identity of the app | Q39 before the release build | a decision |
| Legal exposure known in advance | DESIGN §21 and Q48 name what a lawyer must look at | nothing |

Not protected, knowingly: multiple users or devices (one database, one person); other languages; anything on iOS; accessibility beyond the rules in 00-READ-FIRST; Play Store distribution.

## What was left alone, and why

- **The two-layer data model, the eight kinds, the checks on model output, the bounded loop, the date-on-the-phone rule.** Every new requirement fitted: a price is a reading, a purchase an event, a trip a derived grouping, a watch a proposal. No ninth kind was needed.
- **Card numbers.** Renumbering 12 cards that refer to each other by number, to make the numbers match an order that will change again, would have introduced errors for no gain. Numbers are names; the README holds the order.
- **Room, FTS4, the hand-written HTTP client, no dependency-injection framework.** Nothing new argues against them. Room 3 exists and is not needed.
- **Anthropic as the only built provider, Haiku as the model.** Unmeasured, like every alternative; measuring it is step one.
- **The first-design material** (family phones, assistant role, cover screens). Still deferred, still archived.
- **Card 11 (place arrival) and card 12 (on-phone model spike).** Reworded for the budget; otherwise as they were.

## The recommended first week

Nothing in days 1–2 needs a builder or any new app code.

| Day | Who | What | Time | It decides |
| --- | --- | --- | --- | --- |
| 1 | Owner | Copy `debug.keystore` somewhere safe. Run the owner test script (README), steps 12–15 at least. Write the results in the experiment log | 15 min | Whether capture works from the locked phone; whether speech stays on the phone (PRIVACY.md is hedged until this is known) |
| 1 | Owner | Dictate the pool sentence ten times, then a price sentence five times | 20 min | How much readings and prices can lean on the recogniser (R12) |
| 1 | Owner | Answer Q17 (buy $5 of API credit), Q38 (signing key), Q37 (storage timing), Q40 (the stop) | 15 min | Unblocks the week |
| 2 | Owner | Photograph ten labels and five shelf tags; try the phone's own text selection on each | 20 min | Whether "find the bottle by its label" is realistic (R31) |
| 2 | Builder, with the key | **The desk spike** (card 04 section 0): the schema, the request builder, the seed sentences, the scorer; run the seed set and the handful of real notes | 2–3 h, a few cents | R11, the risk everything else stands on. **If the targets are badly missed after two prompt changes, stop here and talk** |
| 3–4 | Builder | Card 01, starting with the signing key | | The notes are in the new model and cannot be lost to a reinstall |
| 4 | Builder | Card 15, the SQLCipher spike | 1–2 h | Encryption can wait, as a measured fact |
| 5 onward | Builder | Cards 03, then 13 | | Save-first; private notes; tier 0 |
| All week | Owner | On paper: each time you go out, did you make a note? Each time you wished for a reminder, when? | seconds | Whether trips from notes will have anything to show (R23); what the reminder cadence should be (R14) |

At the end of the week the owner should have: a recorded answer to the three questions the documents hedge on, a number for extraction quality, his notes safe in the new model, and a week of paper evidence about outings and reminders. Then cards 04 to 08, then the two-week stop.

**Safe to postpone without regret:** everything numbered 16 and above; the gate and encryption until the stop is passed (keeping sensitive things out meanwhile); the second provider; the weekly review; the tool loop; any decision about watches.

**Not safe to postpone:** the signing key; the desk spike; the two owner tests; card 01's foundations.
