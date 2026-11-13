# 23 · Explanation cards and the "What leaves my phone" screen

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

Wherever the owner switches something on or off, the app says in five plain lines what he gets, what it sends and to whom, what is lost without it, and what it costs. One screen lists, live, every kind of thing the app is able to send and whether it is on.

## Depends on

13 (the capability list and the outbound register). Best built with the hardening block, after 04 so that there is a real outbound request to show. Cards built later add their own card text and register rows as part of their own scope.

## Context

The wording is written already, in `docs/EXPLANATION_CARDS.md`. This card puts it in the app and keeps it true by construction. Do not rewrite the wording while building; if a sentence is false for the code as built, stop and say so in the hand-back.

Card 13 made two registers: `Capability` (every optional ability, its permission, its "without it" sentence) and `Outbound` (every kind of request that can leave the phone; `HttpPoster` refuses a request that does not name one, and counts each).

## Scope

1. `ui/explain/ExplanationCard`: a composable taking a `CardText(title, youGet, sends, ifOff, costs)` and the capability's live state (switch, permission granted or not, any live figure). Five labelled lines, always in the same order. "Nothing leaves your phone" and "Nothing" are shown in full, never left blank.
2. `domain/ExplanationTexts.kt`: one `CardText` per capability, copied from `docs/EXPLANATION_CARDS.md`, with named slots for the live values (`provider`, `cents`, `period`, `count`, …). Strings live here, not scattered in screens.
3. Settings → "What Stash may use" (card 13's list) shows each capability as its card. Turning a switch on that needs a permission shows the card first, then Android's permission dialog; if the permission is refused, the card's state line says so and the switch stays off.
4. The same card is shown at the point of use the first time a capability matters (for example the first time he taps the camera button), with **Turn on** / **Not now**. "Not now" is remembered for a month.
5. `ui/explain/LeavesMyPhoneScreen` (behind the app gate): the header sentence, one row per `Outbound` kind (destination, what is sent, on or off, count this month, last time), the three fixed paragraphs, and **Turn everything off**. Each active watch (card 19) is its own row. Rows for kinds whose cards are not built yet do not exist, because their enum values do not exist yet.
6. `ProviderFacts` (in `model/hosted/`): per provider, the lines the card shows (trains on input or not, retention, price per 1,000 notes at the median note size, link to terms) and a `checkedOn` date. Older than 365 days → the card adds "Check <provider>'s current terms".
7. Replace card 10's planned in-app privacy page with a link to this screen plus the "short version" text from `docs/PRIVACY.md`.

## Out of scope

New capabilities. Changing what is sent. Onboarding flows for other people.

## Acceptance checks

- **Unit:** every `Capability` has a `CardText` with all five lines non-empty; every `Outbound` kind has a row description. Adding an enum value without text fails the test.
- **Unit:** with every sending capability off, the header reads "Nothing leaves your phone" and `HttpPoster` refuses every kind.
- **Unit:** "Turn everything off" sets each sending capability off and cancels the watch worker.
- **Unit (Robolectric):** the screen renders counts from a seeded `outbound_count`; an over-age `checkedOn` shows the "check current terms" line.
- **Real phone, S25 Edge:** read every card on the phone at 200% font size: nothing is cut off. Turn the hosted model off and on: the row changes at once. Make one note with the model on: the count rises by one. Ask the owner whether any sentence is unclear or untrue and write his words in `docs/EXPERIMENT_LOG.md`.

## Notes and risks

- The value of this screen is that it cannot lie. If a later card adds network code that does not go through `HttpPoster` with an `Outbound` kind, card 13's source-scan test must fail; check that it does.
- Keep it to five lines. A sixth line is a sign the capability is doing two things and should be two capabilities.
