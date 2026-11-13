# 26k · Recordings: the explanation card and what the "What leaves my phone" screen says

Read [00-READ-FIRST.md](00-READ-FIRST.md) first. Part of the card 26 split ([index](26-voice-notes-with-audio.md), D67, D82).

## Goal

The switch that keeps recordings explains itself in the same five lines as every other switch, and the screen that lists what can leave the phone says, truthfully, that recordings cannot.

## Depends on

**23** (the `ExplanationCard` composable, `ExplanationTexts`, the "What leaves my phone" screen) and 26g (the `VOICE_RECORDING` capability). Until card 23 is built, the capability's one-line without-it sentence (26g) and the one-time line on the capture screen are what he sees.

## Context

The wording is written already, in `docs/EXPLANATION_CARDS.md` ("Voice notes", "Keep the recording", and the note under them). Copy it; do not rewrite it while building. If a sentence is false for the code as built, stop and say so in the hand-back (card 23's rule).

Nothing in cards 26a to 26j opens a network connection, so the outbound register gains **no row**. A model download would be a row; it is card 26l and not scheduled.

## Scope

1. `ExplanationTexts`: a `CardText` for `Capability.VOICE_RECORDING` from "Keep the recording", with live slots for the measured size per minute (from 26b's default format), the totals (26j), and the model's name and size (26d).
2. Change the `CardText` for `VOICE_CAPTURE` to the revised "Voice notes" text, whose "What it sends" line now depends on whether recording is on.
3. The card is shown in Settings → "What Stash may use", and, through card 23's first-use rule, the first time a recording would be made. "Not now" turns the switch off (the quick note is used) and is remembered as card 23 says. This replaces 26g's one-time line.
4. "What leaves my phone": no new row. The fixed paragraph "Never sent, whatever is switched on" reads "your voice and any recording of it". Under 26f's J2 or J3 nothing changes here (when a recording is written out is not something that leaves the phone).
5. The live state line on the card: "On · 214 recordings, 34 MB · Moonshine base" or "On, but no speech model is added: voice notes are written down by the phone and nothing is recorded" or "Off".
6. If check C2 was blocked, the card's "What it costs" gains the sentence about the locked phone that `docs/EXPLANATION_CARDS.md` gives for that case.

## Out of scope

New capabilities. A download card (26l). Changing what is recorded or kept.

## Acceptance checks

**Unit tests**

- Card 23's test still passes: every `Capability`, including `VOICE_RECORDING`, has a `CardText` with five non-empty lines; no `Outbound` kind was added by cards 26a to 26j.
- The three state lines are produced for the three seeded situations.
- With every sending capability off, the header still reads "Nothing leaves your phone", with recording on and a model installed.

**Real phone (S25 Edge)**

- Read both cards at 200% font size: nothing is cut off. Ask the owner whether any sentence is unclear or untrue and write his words in the experiment log.
- Aeroplane mode for a day with recording on: everything works (the claim "nothing leaves your phone" is checkable this way, and `docs/PRIVACY.md` says so).

## Notes and risks

- Two cards mention the microphone ("Voice notes" and "Keep the recording"). That is one capability doing one thing each: taking a note by voice, and keeping its sound. Keep them two cards of five lines; do not merge them into one of eight.

**Suggested builder: Sonnet.** Copying fixed text into an existing structure, with a test that already exists.
