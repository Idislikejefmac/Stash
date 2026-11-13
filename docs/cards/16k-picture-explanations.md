# 16k · Pictures: explanation cards and the "What leaves my phone" lines

Read [00-READ-FIRST.md](00-READ-FIRST.md) first. Part of the card 16 split ([index](16-share-and-pictures.md)).

## Goal

Every picture switch explains itself in the same five lines as every other switch, and the "What leaves my phone" screen tells the truth about pictures, including the one thing that leaves without being asked each time (Google's text reader reporting usage figures).

## Depends on

**23** (the explanation-card composable, `ExplanationTexts`, the "What leaves my phone" screen), and whichever of 16c, 16e, 16f, 16i, 16j have been built: this card writes text only for capabilities that exist in `domain/Capability.kt`. Do not start before card 23.

## Context

Until this card, each picture card shows its capability in card 13's plain list ("What Stash may use") with its one "without it" sentence, and 16f and 16j show their disclosure as plain text. That is enough to build and use pictures before the hardening block. This card replaces those stopgaps with the real cards.

The wording lives in `docs/EXPLANATION_CARDS.md`. It already has "Pictures", "Offer my latest screenshot" and "Send the words in a picture to the model". It has no card for the camera or for reading words, because the old card 16 treated them as part of "Pictures". Card 23's test fails for any capability without text, so this card adds the two below. **Add them to `docs/EXPLANATION_CARDS.md` in the same change**, after "Pictures".

> ### The camera in Stash
>
> - **What you get:** Take a photo straight into a note. It never goes into your gallery.
> - **What it sends:** Nothing leaves your phone.
> - **If it's off:** Share pictures in from another app, or pick them from your gallery.
> - **What it costs:** The Camera permission. The camera is used only while the camera screen is showing.
>
> ### Read the words in pictures
>
> - **What you get:** Find a picture later by the words printed on it, such as a label or a headline.
> - **What it sends:** Your pictures and their words are not sent. The reader is made by Google and sends Google figures about how it is used and how it performs. Stash cannot switch that part off while this is on.
> - **If it's off:** Pictures are found by what you said, the place and the date.
> - **What it costs:** About 4 MB of the app's size. Nothing in money.

## Scope

1. `domain/ExplanationTexts.kt`: a `CardText` for each of `PICTURES`, `CAMERA`, `PICTURE_TEXT`, `RECENT_MEDIA` and `PICTURE_TEXT_TO_MODEL` that exists, copied from `docs/EXPLANATION_CARDS.md` (and the two cards above). Do not reword them while building; if a sentence is false for the code as built, stop and say so.
2. In the "Pictures" card, replace "About 250 KB of storage a picture" with the measured figure from card 16b's phone check, as a live slot: "About <size> a picture at your chosen size. You have <count> pictures using <total>."
3. Remove the stopgap plain-text disclosures added by 16f (item 13) and 16j (item 10); the cards now carry them.
4. First-use cards (card 23 item 4): the first tap on a camera button shows "The camera in Stash" with **Turn on** / **Not now** before Android's permission dialog; the first picture shared in shows "Pictures" once (this replaces 16c's one-off gallery line).
5. "What leaves my phone":
   - the row "Words read from a picture" comes from `Outbound.EXTRACT_NOTE_WITH_PICTURE_TEXT` (16j) by itself; check that it shows Off by default;
   - under "Sent with any request and not in Stash's control", add a third fixed paragraph, shown only while `PICTURE_TEXT` (or card 21's `CODES`) is on: "**Sent without asking each time:** Google's text and barcode readers, which run on your phone, send Google figures about how they are used. Not your pictures, their words or your codes. Turn off 'Read the words in pictures' to stop the text reader.";
   - the header sentence "Nothing leaves your phone" must **not** be shown while that paragraph applies; show "Nothing you chose to send leaves your phone. One thing leaves by itself: see below." instead. Put the rule in the pure function that picks the header.
   - "Never sent, whatever is switched on" keeps "pictures". (Card 16l, if it is ever built, changes that line; this card does not.)
6. "Turn everything off" also turns off `PICTURE_TEXT_TO_MODEL`. It does **not** turn off `PICTURE_TEXT`, `PICTURES` or `CAMERA`, which send nothing of his; the paragraph in item 5 tells him how to stop the reader.
7. `docs/PRIVACY.md`, "Pictures": check each sentence against the code as built (many pictures per note; removing one deletes its file at once; the size setting) and correct what differs, in the same change.

## Out of scope

New capabilities. Changing what is sent. The hosted-vision consent sheet (16l). Card 21's own cards.

## Acceptance checks

**Unit tests**

- Card 23's test passes: every `Capability` has a `CardText` with five non-empty lines, every `Outbound` kind a row.
- Header rule: all sending switches off and `PICTURE_TEXT` off gives "Nothing leaves your phone"; all sending switches off and `PICTURE_TEXT` on gives the "one thing leaves by itself" sentence; any sending switch on gives the count sentence.
- "Turn everything off" leaves `PICTURE_TEXT`, `PICTURES`, `CAMERA` as they were and turns `PICTURE_TEXT_TO_MODEL` off.
- The Pictures card renders the live count and total from a seeded database.
- No stopgap disclosure string from 16f or 16j remains in the source (search for a phrase of each).

**Real phone (S25 Edge); write each result in the experiment log**

- Read all five cards at 200% font size: nothing is cut off.
- A fresh install (or cleared first-use flags): the first tap on the camera shows the card, then the permission dialog; "Not now" leaves the camera off and does not ask again for a month.
- Ask the owner whether any sentence is unclear or untrue, especially the Google line, and write his words down.

## Notes and risks

- The Google usage-figures line is the one place the app's short promise ("nothing leaves") needs a qualifier. Do not soften it and do not hide it in a sub-screen (QUESTIONS.md Q46 is the owner's to answer; the text is honest either way).
- If card 23 was built before any picture card, its test starts failing the moment a picture capability is added without text. In that case build this card's item 1 together with each picture card instead of at the end.

**Suggested builder: Sonnet.** It is text and one small pure rule for the header, checked by card 23's existing test.
