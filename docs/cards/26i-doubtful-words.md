# 26i ◆ · A small mark on notes whose words may be wrong

Read [00-READ-FIRST.md](00-READ-FIRST.md) first. Part of the card 26 split ([index](26-voice-notes-with-audio.md), D82). **Optional.** Nothing else depends on it.

## Goal

Without any model, and for almost no work, the app notices a writing-out that deserves a second listen and puts a quiet mark on that note. It never asks, never notifies and never changes the words.

## Depends on

26c (`transcription.doubt`), 26f (the pass that stores a writing-out). For the mark on screen: 26h.

## Context

Read `domain/Corrector.kt` (its `closeMatch`), `data/Repositories.kt` (the word list) and "Accuracy" in "Card 25: engine results".

What card 25 showed about where the words go wrong: **names, brands and wine words** ("Cavit" heard as "Cabot", "caveat", "Cavet"; "frunk" as "front", "frk"; "Syd" as "Sid", "sit"), and prices. Plain speech was right, quiet and noisy alike. So the useful signals are the cheap ones that point at a name the app already knows, and at a recording the engine plainly did not cope with.

Signals considered and what became of them:

| Signal | Verdict |
| --- | --- |
| The engine's own confidence | Used **only if** the result carries one (26d's `confidence`). Moonshine through sherpa-onnx is not known to report it; do not invent a number |
| The phone's recogniser and ours disagree | **Not available.** They cannot hear the same speech (the microphone goes to one of them, card 24) and the recogniser cannot take a file. Two installed model packs can disagree instead; that is the owner's "Try the other model" button (26h), not an automatic check, because running both doubles the work and the memory on every note |
| Rare words not in the word list | Needs a list of common English words, which is a data file with its own licence (D78: record it first). **Not built.** The near-miss signal below covers the names he has already told the app about, with no file |

## Scope

1. `domain/TranscriptDoubt.kt` (pure Kotlin): `flags(text, audioMs, chunks, forcedCuts, hitLimit, vocabulary, engineConfidence): Int`, a set of bits:
   - `NEAR_NAME`: a word in the text is close to a word-list word (the same `closeMatch` the `Corrector` uses, one step looser) but was **not** corrected to it. "Cabot" beside a word list containing "Cavit". This is the signal aimed at the misses card 25 found.
   - `TOO_FEW_WORDS`: fewer than about 0.5 words a second over a recording in which speech was detected for most of its length (the engine dropped speech).
   - `TOO_MANY_WORDS`: more than about 5 words a second (invented or repeated text).
   - `REPEATS`: the same three-word run three or more times in a row.
   - `NO_WORDS`: speech was detected but the text is empty.
   - `CUT_SHORT`: the recording hit the three-minute limit.
   - `FORCED_CUT`: 26e had to cut where there was no pause.
   - `LOW_CONFIDENCE`: only when the engine supplied a figure, below a constant.
   The thresholds are named constants in this file. `explain(flags): List<String>` gives a plain line for each ("A word sounds like 'Cavit' from your word list").
2. `TranscriptionPipeline` (26f) calls `flags` after a successful writing-out and stores the result in `transcription.doubt`. It is computed from the engine's words and the word list as they are at that moment; it is not recomputed later.
3. The mark: on the note's row, when the **current** writing-out has any flag and the owner has not since edited the words or tapped "Looks right": a small "Check the words" label beside **Looks wrong** (26h). Opening the panel shows the `explain` lines above the player, and a **Looks right** button that clears the mark (a `revision` of a new kind `WORDS_CONFIRMED`, an enum value only and no schema change, so it stays cleared).
4. A Private note's mark is drawn only where its words would be (inside `PrivateContent`). The mark never appears in a notification, a digest count or the weekly review, and is not an attention-list item unless card 09 is built and the owner asks for it there (card 09 would add a reason `CHECK_WORDS` rendered by its generic card).
5. Events: `Dbg.event(TRANSCRIBE, FLAGGED, n1 = flags)`. The flag bits are numbers; no word is logged.

## Out of scope

A dictionary of common words. Running a second model automatically. Highlighting the doubtful word inside the text (the engine gives no reliable word positions; the explain line names the word). Changing the words. Any notification.

## Acceptance checks

**Unit tests (JVM)**

- "Total wine has the Cabot Pinot Grigio" with "Cavit" in the word list: `NEAR_NAME`, and the explain line names Cavit. With "Cavit" already in the text: no flag. With an empty word list: no flag.
- "Sit as a spare key in the front" with "Syd" and "frunk" in the list: `NEAR_NAME` (say in the hand-back which of the two the looser match catches; do not loosen it until ordinary words start matching).
- The fifteen corpus references (harmless ones, typed into the test) as text with their durations: no flag on the plain sentences.
- 30 seconds of detected speech and 4 words: `TOO_FEW_WORDS`. 5 seconds and 40 words: `TOO_MANY_WORDS`. "thank you thank you thank you thank you": `REPEATS`. Empty text with speech: `NO_WORDS`. A null confidence never sets `LOW_CONFIDENCE`.
- Robolectric: a flagged note shows the mark; after "Looks right" or an edit it does not, and stays cleared after the app restarts; a Private flagged note shows no mark before it is shown.

**Real phone (S25 Edge); experiment log, "Card 26"**

- Record the four hard corpus sentences (Valpolicella, Tide price, Cavit, Syd and the frunk) and six plain ones. Write down which were marked. The aim is a mark on most of the notes that are in fact wrong and on **none** of the plain ones; if a plain one is marked, tighten the thresholds and say what changed.
- After a fortnight of use, ask the owner whether the mark is right often enough to keep. If not, the mark is switched off with one constant and this card has cost little.

## Notes and risks

- This will miss a wrong word that is not near anything in the word list (a wine he has never mentioned). It is a nudge, not a check. The recording is the check.
- A too-eager `NEAR_NAME` trains him to ignore the mark. Prefer missing some to marking plain speech.

**Suggested builder: Sonnet.** One pure function with a table of examples, one stored number, one small label.
