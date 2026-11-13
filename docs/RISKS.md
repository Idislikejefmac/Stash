# Risks: assumptions that could sink the design, and a cheap test for each

Revisions 2 and 3, 2026-11-11, add R21–R35 and re-rank. Revision 1's risks R11–R20 keep their numbers and text, with card numbers updated. The first version (three phones) is kept at [archive/RISKS-v1-2026-11-10.md](archive/RISKS-v1-2026-11-10.md); its risks R1–R10 are listed at the end with what became of them. New risks are numbered from R11 so that old references still mean what they meant.

Each risk has a **spike**: a small experiment with a pass or fail result, written into [EXPERIMENT_LOG.md](EXPERIMENT_LOG.md). Risks are ordered by how much of the new design falls if the assumption is wrong.

Confidence marks as in DESIGN.md: **high**, **medium**, **low**.

## The top five, re-ranked (revision 3)

| Rank | Risk | Why it is here | The cheapest thing that reduces it |
| --- | --- | --- | --- |
| 1 | **R11** The model does not file real speech well enough | Unchanged at the top. Every later feature (prices, places, spots, picture hints, watch proposals) is one more field for it to get right | The desk spike in card 04 section 0: two hours, under five cents, before any app code |
| 2 | **R19** He does not keep using it | Three large features were added before the core has had its two weeks. More to build is more ways to never reach "useful" | The two-week check after cards 05–08, kept as a hard stop before anything in revisions 2 and 3 is built |
| 3 | **R21** The notes are lost during development | New, and likelier than any attacker: a changed signing key or a debug-to-release switch forces an uninstall, and there is no backup until card 10 | Fifteen minutes: a project keystore now (card 01 item 0). Until then, do not uninstall |
| 4 | **R14** Notifications become a nag | Was "reminders may annoy". Now also arrivals, leaving lists, picture offers and watch matches | One budget function for every notification (card 06); offers are buttons, not notifications |
| 5 | **R22** Tier 0 feels broken, and R12 behind it (speech mangles numbers) | Revision 3 promises a phone-only tier. The rules file perhaps a third of free speech (a guess). Prices add more numbers to mis-hear | One day with the model switched off, written up; the ten pool dictations |

Safe to postpone, by the same reasoning: R24 (departure signals), R28 (providers), R29 (diagnostics), R30 (watches), R32 and R33 (barcodes; the camera on the lock screen). Each belongs to a card that is not near the front of the order.

## R11. The model may not file messy real speech well enough to be trusted unchecked

**Assumption:** a small hosted model, given one speech-recognised sentence or three, returns the right records often enough that the owner never needs to look: tasks found at least 9 times in 10, false tasks no more than 1 capture in 20, reading numbers right 19 in 20.

**Why it might be false:** real speech is not the tidy examples in a design document. It has false starts, recogniser errors, half-sentences, and things said for no reason ("I am updating the app for stash right now"). Nobody has measured this model on this owner's notes. The numbers above are targets, not findings (low confidence that they are met first time; medium that prompt work gets there).

**What breaks:** the central promise. If filing is wrong often, tasks are missed or invented and he stops trusting it.

**Spike (card 04 section 0, run before any other card as a script):** take the 40 seed sentences (card 04 lists them) plus every real note on the phone today, run them through the extraction prompt on the developer's PC, and score by hand. **Pass:** the targets above on the seed set, and no real note produces something he would call silly. **Marginal:** tasks found 8 in 10 → proceed, tune the prompt, measure weekly. **Fail:** below that after one round of prompt changes → try the next model tier on the same set (cost is still pennies) before changing the design.

**Cost:** two hours and under five cents. Needs an API key (Q17).

## R12. Speech recognition may cut off or mangle exactly the notes this revision adds

**Assumption:** the phone's recogniser captures a 15-second list of readings ("free chlorine 5, pH 7.2, alkalinity 70, …") whole, with the decimals right.

**Why it might be false:** the prototype stops after about 1.8 seconds of silence, and recognisers may ignore that setting (medium). People pause while reading numbers off a test strip. Decimals are a known weak point: "7.2" can arrive as "72" or "7 2". Chemistry words ("cyanuric acid") may not be in the recogniser's comfort zone.

**What breaks:** readings, the owner's first new example. A wrong number stored confidently is worse than none.

**Spike S1 (no code; the prototype as it is):** dictate the pool sentence ten times in normal conditions, reading from a card with realistic pauses. Record for each: was it cut off, were all numbers right, were the names right. Add the metric names to the word list and repeat five times. **Pass:** at most 1 in 10 cut off and at most 1 in 10 with a wrong number. **Fail on cut-offs:** use the "Done" button for long notes, try a longer silence setting, and plan "add to the last note". **Fail on numbers:** rely on the number checks and the outlier flag (card 07), and consider spoken read-back for readings (Q27).

**Cost:** fifteen minutes.

## R13. Splitting may go wrong in both directions

**Assumption:** the model splits a note into separate records when, and only when, the parts stand alone.

**Why it might be false:** models asked to "extract everything" tend to over-split (a pool test becomes five records; one task becomes a task plus an idea plus a reference) and occasionally merge (two errands become one). Over-splitting makes lists noisy and duplicates nags.

**What breaks:** the feel of the lists, and trust. Not data: the capture is whole.

**Spike (inside R11's run):** the seed set includes ten multi-part sentences and five that must *not* be split. Count records per capture against the expected count. **Pass:** exact count on at least 13 of 15. **Fail:** add two worked examples to the prompt; if still failing, lower the cap from 8 to 4 and treat anything above as one `REFERENCE`.

**Guard regardless:** quote check, cap, coverage check, "Treat as one note" (DESIGN.md 4.4).

## R14. Reminders may annoy him, or be so quiet they are useless

**Assumption:** one digest a day on days with something to say, backing off when ignored, is felt as helpful.

**Why it might be false:** nobody can design this from a desk. "Soon" means different things for different tasks. A digest at the wrong hour is always ignored, and the back-off would then silence everything within a week.

**What breaks:** the task half of the vision. Either he turns notifications off (annoyed) or forgets the app exists (too quiet).

**Spike (no code needed first):** for one week before card 06 is built, the owner writes down each time he wished he had been reminded of something, and when. Then card 06's planner is a pure function with a fixed clock: replay that week through it in a unit test and see what it would have sent. After release: the `nudge_log` shows opened versus ignored; review it after two weeks. **Pass:** he opens at least half the digests in week two and has not changed the notification settings in anger. **Fail:** move to weekly-only plus dated reminders, and ask him what he wants.

**Cost:** a notebook for a week; an hour of test writing.

## R15. Tasks closed or left open wrongly when he reports by speaking

**Assumption:** "I cleaned out the car" closes "clean out the back of the car" and nothing else, and only when the app is sure.

**Why it might be false:** matching a report to the right open task is fuzzy ("I did the car thing"; two car tasks open; "I cleaned the car" when the task was "clean out the *back* of the car"). A wrong close is silent.

**What breaks:** a reminder that stops when it should not, which he will not notice until it matters.

**Spike (inside R11's run):** eight seed sentences that should close a listed task, four that sound similar and should not. **Pass:** no wrong closes in 12; at least 6 of 8 right closes found (the rest fall to the attention list, which is acceptable). **Fail on wrong closes:** never close automatically; always ask in the attention list.

**Guard regardless:** "Done this week" in the weekly review; one-tap reopen.

## R16. The derived data may drift from the raw words

**Assumption:** records, task state, readings and the search index always agree with the captures they came from.

**Why it might be false:** two layers, several tables, background work, re-reads, user edits and a migration. A crash between steps, or a re-read that replaces a record he had acted on, leaves the layers disagreeing.

**What breaks:** quiet wrongness: a task that exists in one list and not another, a search hit for a deleted note, a reopened task.

**Spike (unit tests in cards 01, 03 and 13, no phone):** the consistency check as a function; a test that kills projection halfway (throwing fake) and shows the check finds and repairs it; a test that re-reads a capture with a pinned record and shows the pin survives. **Pass:** the tests pass and the debug screen shows zero violations after a week of real use. **Fail:** tighten transactions before building more on top.

## R17. Dependence on one hosted provider and its terms

**Assumption:** Anthropic's API stays available, cheap, and on terms the owner accepts (no training on API content, deletion within 30 days, as read on 2026-11-11).

**Why it might be false:** prices, model names and terms change; a model version is retired; the service is down when he captures; the terms were read on one day by one reader, and one point (whether a private individual's key is squarely within the commercial terms) is a confident reading rather than a quoted sentence (medium).

**What breaks:** filing quality drops to the rules floor; in the worst case a change of terms makes him unwilling to send text at all.

**Spike (card 04):** a test proving the app is fully usable with the transport failing every call; the model name and prices as constants in one file; a calendar note to re-read the three terms pages yearly. And card 12: half a day to learn whether the phone's own model is a credible fallback. **Pass:** switching the hosted model off mid-week loses convenience, not notes or reminders.

## R18. The on-device model may be unusable in practice

**Assumption (optional path):** Gemini Nano on the S25 Edge can read private notes well enough to be worth wiring in.

**Why it might be false:** the API is in beta; the device list came from Google's page, not from this phone (medium); it runs only in the foreground; its structured-output support is alpha; quality on numbers and splitting is unknown (low).

**What breaks:** nothing in the main design. Private notes stay filed by rules.

**Spike (card 12):** a throwaway screen that runs the 40 seed sentences through the Prompt API on the phone and scores them. **Pass:** tasks found 8 in 10, reading numbers 9 in 10, under 5 seconds each. **Fail or unavailable:** record why; revisit in a year.

## R19. He will not keep using it

**Assumption:** capture is easy enough and the returns (found things, timely reminders) frequent enough to form a habit.

**Why it might be false:** every memory aid dies this way. Widening the scope helps (more reasons to speak to it) and hurts (more ways to be wrong).

**Test (a milestone, not a card):** after cards 01–06, two weeks of use. Log: captures per day, questions per day, digests opened, things it told him that he had forgotten, and each time he did not bother. **Pass:** still speaking to it daily in week two, and at least one "glad it remembered that". **Fail:** stop adding features; find out which step is the friction.

## R20. Smaller risks

| Risk | Confidence it is a problem | Check | Where |
| --- | --- | --- | --- |
| The migration loses or garbles the owner's real test notes | low, but unrecoverable | Migration test with rows shaped like the real ones; a copy of the old database kept until he has seen his notes; `peek.py` count before and after | Card 01 |
| Converting the plain database to an encrypted one fails halfway | low | Convert to a new file, verify the row counts, then swap; keep the plain file until verified; a backup first | Card 14 |
| SQLCipher, Room and FTS do not combine cleanly | low to medium (see R26) | The spike | Card 15 |
| After encryption the developer can no longer inspect the data, and quality measurement stops | high if nothing is done | Debug-only dump command that `peek.py` reads | Card 01 |
| Samsung delays or drops the daily digest for a "sleeping" app | medium | Note the arrival time for a week; if late by more than 30 minutes, ask for unrestricted battery | Card 06 |
| The private-note check misses a secret said in an unusual way | medium | Unit tests with awkward phrasings; the "private note" phrase; PRIVACY.md says it cannot be promised | Cards 03, 13 |
| The phone's recogniser is not actually on-device | medium | Dictate in aeroplane mode; log it | README test script step 15 |
| A note's text carries an instruction to the model | low | Fixed output shape, read-only tools, checks against the text | Cards 04, 08 |
| The tool loop runs away (cost or time) | low | Hard limits in code, tested with a misbehaving fake model | Card 08 |
| Structured outputs are not available as described for the chosen model | low | The builder reads the live documentation page first; fallback is one strict tool whose input is the same schema | Card 04 |
| Real notes leak into the repository through the evaluation set | medium | `eval/` is git-ignored; only rewritten, harmless sentences are promoted to the seed set | Card 04 |
| Facts in this revision that came from a research helper's reading turn out wrong (device lists, API limits, product histories) | medium | Each is marked in DESIGN.md and PRIVACY.md with its confidence and source | — |
| The reading gate is enforced by code, not cryptography (old R8) | accepted | None needed; stated in PRIVACY.md | arch. card 30 |

## R21. The notes are lost during development *(rev 2)*

**Assumption:** the owner's notes survive from now until an encrypted backup exists (card 10).

**Why it might be false:** Android ties an app's data to its signature. The prototype is signed with this PC's automatically made debug key (**high**: `app/build.gradle.kts` has no signing configuration). A build from another PC, a reinstalled Android Studio, or a release build signed with a different key will not install over it; the remedy Android offers is "uninstall first", which deletes everything. Android's own backup is off on purpose. There is no export except a text share.

**What breaks:** all of it, irrecoverably, and with it the owner's trust in the idea.

**Spike (fifteen minutes, no app code):** copy `C:\Users\<you>\.android\debug.keystore` somewhere safe today. Then card 01 item 0: one project keystore used for every build type. **Pass:** a build from a clean checkout installs over the app on the phone. **Until card 10:** before any risky step, `python tools/peek.py notes 1000 > notes.txt`.

## R22. Tier 0 feels broken rather than modest *(rev 3)*

**Assumption:** with no model, the app is a usable, honest "save it and find it by its words" tool that sorts the simple sentences.

**Why it might be false:** the rules handle a short list of sentence shapes (PROVIDERS.md §1). "Perhaps a third of free speech" is a guess. If most notes come out "not sorted", tier 0 is a notes list with a search box, and saying "needs a model" on most rows reads as a sales pitch.

**What breaks:** the product claim that the app is fully usable with no key; the promise to people who want nothing to leave the phone.

**Spike (one day, after card 13):** the owner switches the hosted model off for a full day of normal use. Count: notes sorted by rules, notes "not sorted", questions answered, questions that returned only a list. **Pass:** at least half of notes sorted and he would describe it as "basic but fine". **Fail:** either widen the rules where the misses cluster, or change the wording so tier 0 is presented as "capture and search" and stops showing an unsorted mark on every row.

## R23. Trips from notes show nothing, or the wrong places *(rev 2)*

**Assumption:** he makes notes while out often enough for trips to exist, and the phone's stored position is close enough to name the stop.

**Why it might be false:** he may simply not speak to his phone in shops. The prototype stores the *last known* position, which can be many minutes and several hundred metres stale (**high**: code read). Without a saved place or a spoken name a stop is "a stop".

**Spike (no code; a week):** on each outing, note on paper whether he made any note. After card 13, look at the coordinates of notes made in known places with `peek.py dump` and measure the error on a map. **Pass:** notes on at least half of outings; position within 150 m for most. **Fail on position:** ask for a fresh position after saving. **Fail on habit:** trips need a departure signal to be worth having; reconsider card 17's place in the order.

## R24. Departure signals are late, missed or false on this phone *(rev 2)*

**Assumption:** the car's Bluetooth connecting is delivered to the app within a minute, most times, even when the app has not been opened that day.

**Why it might be false:** Google's own page says these broadcasts still work but should not be relied on; Samsung's battery manager is reported to stop background delivery for apps it has put to sleep (**medium**: a community-maintained page, not Samsung); nothing has been tried.

**Spike:** card 18 stage 2's week: paper log of departures against the app's event log. **Pass:** 8 in 10 car departures within a minute; at most one false reminder. **Fail:** manual "Start a trip" only.

## R25. Scope has outrun the evidence *(rev 2, 3)*

**Assumption:** designing trips, pictures, codes, watches, diagnostics, tiers and providers now does not delay learning whether the core works.

**Why it might be false:** the plan grew from 12 cards to 23 in a day while the two cheapest experiments (the locked-phone test, ten dictations) are still unrecorded. A builder model given 23 cards will happily build them.

**What breaks:** months go into features hung on a core nobody has used for two weeks.

**Guard:** the README order puts a **stop** after cards 05–08: two weeks of use and the R19 check, in writing, before any card numbered above 15 is started. New ideas go into QUESTIONS.md, not into cards, until then.

## R26. Room and SQLCipher drift apart *(rev 2)*

**Assumption:** SQLCipher can be dropped under Room later with no other change.

**Why it might be false:** Room 3 removed the database-opening API that the classic SQLCipher integration uses; SQLCipher's driver for Room 3 is about three months old; whether its prebuilt library includes FTS4 could not be confirmed (STORAGE.md).

**Spike:** card 15, one to two hours, straight after card 01. **Pass:** "drop-in".

## R27. A lock-screen notification says too much *(rev 2, 3)*

**Assumption:** no notification reveals note content, an interest, a place or a picture to someone glancing at the locked phone.

**Why it might be false:** revisions 2 and 3 add four notification types. Each has a private and a public version and one mistake leaks. The leaving list is shown on the lock screen on purpose.

**Guard:** one notifier; a Robolectric test per type asserting the public version's exact text; Private items excluded before the planner sees them. **Spike:** with each new type, lock the phone and read the screen; log the words seen.

## R28. Providers differ more than an interface can hide *(rev 3)*

**Assumption:** one schema and one tool loop work acceptably across providers.

**Why it might be false:** small models differ in how well they follow a schema, split notes and copy numbers; "compatible" endpoints lag native ones; model names and request shapes were changing on the day they were read; one provider's cheapest model costs ten times another's.

**What breaks:** a user picks a provider and the app quietly files worse.

**Spike (card 22, with a key at the second provider):** the conformance run. The result is a table, shown to the user in summary. **Guard:** only Anthropic is offered until that run exists.

## R29. Pseudonymised diagnostics give false comfort *(rev 2)*

**Assumption:** a report with tokens in place of words is safe to send.

**Why it might be false:** structure identifies. An unusual pattern of stops or a rare price can point to one person; a support engineer who knows the customer can undo tokens by context.

**Guard:** the wording ("contains none of your words", never "anonymous"); preview; user-chosen destination; nothing Private; no coordinates, rounded offsets for time. **Spike (when card 20 stage 3 is built):** give a report from the owner's real data to someone who knows him and ask what they can tell.

## R30. Watches cost, miss or mislead *(rev 2)*

**Assumption:** a monthly check finds the concert and only the concert, for cents.

**Why it might be false:** a listing service covers only its own venues (a silent miss); web search finds tribute acts and old pages (false alerts); a model with search can use more tokens than expected; the listing service's terms may not allow product use.

**Spike (card 19, developer PC):** run both engines for three bands with known dates, one with none, and one tribute act; record matches, misses, tokens and cost.

## R31. On-device text recognition is poor on the things he photographs *(rev 3)*

**Assumption:** the phone reads wine labels, shelf tags and screenshots well enough that a search for the name finds the picture.

**Why it might be false:** labels use script fonts, curved glass, foil and vertical text; shelf tags are small; Google's guidance wants letters at least 16 pixels tall and in focus. Screenshots should be easy.

**Spike (before card 16; twenty minutes; no code):** photograph ten labels and five shelf tags; open each in Google Lens or Samsung's built-in text selection (both use comparable on-device recognition; a proxy, not the same engine) and note whether the name and price can be selected as text. **Pass:** name readable on 8 of 10. **Fail:** the feature still works by his spoken words, place and date; say so and lower the claim.

## R32. Barcode scanning is unreliable in a shop *(rev 3)*

**Assumption:** a product barcode decodes within two seconds on curved, shiny packaging in shop light.

**Spike:** card 21's twenty-barcode check. **Fail:** try the open decoder (zxing-cpp) on the same items before giving up.

## R33. The camera will not open over the lock screen *(rev 3)*

**Assumption:** "Add a picture" works from a note started on the locked phone.

**Why it might be false:** undocumented. Android's stated rule covers background apps only; one user report says Samsung allows only its own camera on the lock screen (**low** either way).

**Spike:** card 16's lock-screen check. **Fail** is acceptable: the button says "Unlock to take a picture".

## R34. A private thing is shown by accident *(rev 3)*

**Assumption:** nothing marked Private appears without the second unlock: not a title in a list, a thumbnail, a search preview, a count that names it, a spoken answer, a notification, a report or a log.

**Why it might be false:** the mark is checked in many places built by different cards over months. Every new list, widget or notification is a new place to forget. A picture the app never classified is only as private as its note's mark and the defaults.

**What breaks:** the worst kind of failure for this product: exposure in front of another person.

**Guard:** one composable (`PrivateContent`) is the only code that decides whether private content is drawn; lookups take an explicit `includeSensitive` argument with no default; planners never receive sensitive rows. **Spike (with card 02, and again after every card that adds a screen):** a seeded private note with a picture and a distinctive word; a Robolectric test walks every screen and asserts the word and the thumbnail are absent; then the owner tries to find it on the phone for five minutes without the second unlock.

## R35. The automatic Private marking is wrong both ways *(rev 3)*

**Assumption:** secrets and personal codes are marked; pool readings and prices are not.

**Why it might be false:** digit-run and keyword rules are crude. Too eager, and ordinary notes vanish behind a second unlock and never remind; too timid, and a gate code is sent to a model. Boarding-pass and payment recognition is best-effort.

**Guard:** unit tests on both lists; the mark is removable; "say 'private note' when in doubt" in PRIVACY.md. **Measure:** the weekly real-notes review (card 04) counts wrong marks in both directions.

## What became of the first design's risks

| Old | Subject | Now |
| --- | --- | --- |
| R1 | One-action capture from a locked phone | **Open, cheap to close:** the prototype implements it; the owner's test-script result for the locked notification and tile is not in the log yet. |
| R2 | Flip-phone cover screens | Deferred with the other phones. |
| R3 | On-device speech accuracy | Partly answered: the platform recogniser transcribed the first real note correctly (one note; reported in the revision brief). Continues as R12. |
| R4 | Cheap model reliability; SDK on Android | Replaced by R11. The SDK question is moot: the prototype's own small client is kept. |
| R5 | Geofence reliability | Deferred with card 11. |
| R6 | Updates on family phones | Deferred. |
| R7 | Assistant role side effects | Deferred. |
| R8 | Reading gate enforced by code | Accepted; restated in PRIVACY.md. |
| R9 | People will not keep using it | Continues as R19, for one person. |
| R10 | Smaller risks | Folded into R20 where still relevant. |
