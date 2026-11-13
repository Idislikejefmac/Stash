# 26e · Long notes: keeping the engine inside the memory guide

Read [00-READ-FIRST.md](00-READ-FIRST.md) first. Part of the card 26 split ([index](26-voice-notes-with-audio.md), D81, D82).
**Status: built 2026-11-12, JVM only** (770 unit tests pass in both build configurations, with and without the engine AAR, one skipped in each; 17 new; no schema change, no new dependency). Nothing was installed on the phone: the memory measurement, the ramble's score and the outcome M1, M2 or M3 wait for the owner, so `DEFAULT_PACK_ORDER` still holds D82's proposal (base first). See "What was built, and what differs" at the end.

## Goal

A three-minute note is written out without the app being killed for memory on a 4 GB phone, and without the loss of accuracy that cutting at every silence caused in card 25. The card ends with a measured answer to one question: **is Moonshine base the default model, or Moonshine tiny?**

## Depends on

26d (the `Transcriber` and the adapter). Uses 26a's `Pcm`.

## Context

Read "Speed, load, memory, size" and "Accuracy" in "Card 25: engine results" (`docs/EXPERIMENT_LOG.md`).

The facts to design from:

- D81's guide is about 400 MB of peak memory for the app's process. Moonshine base: 351 MB on notes up to 8 seconds with a screen open; **486 MB on the 27.7-second note with a screen open, 418 MB in a background job** (misses by 22% and 5%). Memory grows with the length of one piece of sound, and ONNX Runtime's pool does not shrink afterwards.
- Moonshine tiny passes on everything (243 to 376 MB) and is about one key token in 43 less accurate, which is within noise.
- **Cutting with a silence detector hurt accuracy** (3 to 6 words in 147): it started pieces at the first speech frame, clipping first words, and it split a 7.6-second sentence in the middle of two wine names. Padding fixed the clipped words, not the split.
- No engine invented text on leading silence, so silence does not need removing.

So the rule here is different from the detector the spike tried: **short notes are never cut; long notes are cut only at a real pause, in a few large pieces, and no sample is thrown away.**

## Scope

1. `PauseSplitter.split(pcm, targetMs = 15_000, maxMs = 20_000, minPauseMs = 400): List<IntRange>` (pure Kotlin, `app.stash.audio`):
   - A recording no longer than `maxMs` is returned as one piece. (Every corpus sentence except the ramble is under 9 seconds, so none of them is ever cut.)
   - Otherwise, walk forward: for each piece look for the **quietest stretch of at least `minPauseMs`** between 60% and 133% of `targetMs` from the piece's start, and cut in the **middle** of it. Every sample belongs to exactly one piece; nothing is trimmed, so no first word is clipped.
   - If there is no such pause before `maxMs` (he spoke without a break, or the room is loud), cut at the quietest 100 ms in that window and make the pieces **overlap by 300 ms**; mark the cut as `forced`.
   - Levels are measured as in 26a's `Endpointer` (share the function).
2. `ChunkedTranscriber(inner: Transcriber, splitter)`: implements `Transcriber`. Transcribes the pieces in order with the same loaded recogniser and joins the texts with one space. After a `forced` cut, if the last two words of one piece equal the first two of the next, they are dropped once. `chunks` in the result is the number of pieces. A failure on any piece fails the whole job (26f retries the note; half a note is never stored as if it were whole). `Container.transcriber` becomes this wrapper.
3. Threads: `num_threads = 2` when the app is on screen, `1` in a background job (measured 0.018 of real time at one thread, still far inside the speed bar; fewer threads means less memory pressure and heat). The caller says which.
4. **The measurement, on the phone**, with a debug-only action that runs `Container.transcriber` inside a real WorkManager job on the corpus's 27.7-second recording (`files/spike25/s10.wav`), on three copies of it joined end to end (about 83 seconds), and on a generated three-minute file made by repeating it; sampling peak memory every 200 ms as the spike did (`Debug.getMemoryInfo().totalPss`). Each run starts from a freshly killed process (`adb shell am kill app.stash`).
5. **Named outcomes.** Write the one that happened in the experiment log and set the constant `DEFAULT_PACK_ORDER` accordingly:
   - **M1:** base stays at or under about 400 MB in the background job at every length, and its word error rate on the ramble is no worse than the uncut run by more than two words. **Base is the default.** Keep `MAX_NOTE_MS` at three minutes.
   - **M2:** base passes for notes up to some length L and misses beyond it. **Base is the default up to L; longer notes are transcribed with tiny if the tiny pack is installed, otherwise with base and a logged event.** Say what L is. (Both packs then need importing; say so in Settings.)
   - **M3:** base misses even on short notes in the background job on a measured mid-range phone, or the owner treats 400 MB as a hard line. **Tiny is the default; base is an opt-in** ("Better with names and numbers; needs a phone with more memory").
   - Either way, record the same three runs for tiny.
6. Score the ramble's text with `python -I docs/spike25/score.py` against the spike's uncut `moonshine-base` result and report words wrong, uncut and cut.

## Out of scope

A speech-detector model. Limiting ONNX Runtime's memory arena (try it only if the library exposes the setting in the pinned release without a rebuild; report, do not depend on it). Streaming transcription while he speaks. Changing `MAX_NOTE_MS` beyond three minutes. Running the engine in its own process.

## Acceptance checks

**Unit tests (JVM)**

- `PauseSplitter` on synthetic sound (tone = speech, low noise = pause): a 19-second recording is one piece; a 50-second one with pauses every 6 seconds is cut only inside pauses, each piece between 9 and 20 seconds, and the pieces cover every sample once; one continuous 50-second tone is cut with 300 ms overlaps and `forced` set; a pause of 300 ms is not used when one of 600 ms is in the window.
- `ChunkedTranscriber` with a fake inner engine that returns a known text per piece: joined in order; the two-word duplicate after a forced cut is removed once and never after a natural cut; a failure in piece 2 of 3 fails the job; `chunks` is right.

**Real phone (S25 Edge); experiment log, "Card 26"**

- The table from item 4: length, pieces, peak memory in the job, processing time, for base and tiny.
- Item 6's score.
- The outcome M1, M2 or M3, in those words, and the constant set to match.
- The 15 corpus sentences through the wrapper give exactly the texts they gave in 26d (none is cut).

## Notes and risks

- The S25 Edge is the optimistic case (D81). Memory is the one figure that carries over to a cheaper phone fairly directly (the model is the same size everywhere); speed does not. If an older or cheaper phone is to hand, repeat item 4 on it: that is worth more than this estimate.
- A cut inside a real pause can still fall between two words of one name spoken slowly ("Total … Wine"). With pieces of about 15 seconds this is rare, and the recording is there to check against, which is the point of the whole feature.
- A measured 418 MB against a 400 MB guide is not, by itself, a kill. Whether Samsung actually kills the job is card 26f's screen-off check (C1). This card makes the number as small as plain code can; 26f handles the kill if it still happens.

**Suggested builder: Opus.** The splitting rule is easy to get subtly wrong in a way that only shows as a missing word, and the outcome decides the default model.

## What was built, and what differs from the card (2026-11-12)

Built (under `app/src/main/java/app/stash/` unless noted):
- `audio/PauseSplitter.kt` (pure): `Piece(range, forcedEnd)`, the `Splitter` seam and `PauseSplitter`, with every threshold a named constant: `TARGET_MS` 15 s, `MAX_MS` 20 s, `MIN_PAUSE_MS` 400 ms, the window `WINDOW_FROM` 0.60 to `WINDOW_TO` 1.33 of the target, `FORCED_QUIET_MS` 100 ms, `OVERLAP_MS` 300 ms, `QUIET_TOLERANCE` 2.
  - A recording up to 20 s is one piece: the very same samples.
  - A longer one: chunk levels (20 ms RMS) over the whole recording, with the noise floor and "speech" decided by the level rule 26a's `Endpointer` uses. For each piece, the quietest 400 ms in which every chunk is not speech, wholly inside the window, and the cut in its middle (so at least 200 ms of quiet on each side). Every sample is in exactly one piece; nothing is trimmed.
  - No such pause: the quietest 100 ms in the window, early enough that the first piece plus its half of the overlap stays within 20 s. The pieces share 300 ms around the cut, and the first is marked `forcedEnd`.
- `domain/Endpointer.kt`: the level rule is now shared, with no change in behaviour (26a's tests pass unchanged): `Endpointer.isSpeech(rms, floor)`, `Endpointer.rmsOf(samples, from, len)`, and the floor tracker as its own class, `NoiseFloor`.
- `speech/ChunkedTranscriber.kt` (pure): implements `Transcriber` over any inner one.
  - One piece: the audio goes to the engine untouched and its result comes back untouched.
  - Several: the pieces in order through the same loaded engine; words joined with one space (an empty piece adds nothing); `procMs` summed; `chunks` = pieces; `confidence` null.
  - After a `forcedEnd` cut only: when the last two words of a piece equal the first two of the next (ignoring case and punctuation), they are dropped once from the next.
  - The first failed piece is returned as the job's result, and no later piece is started.
  - Event `TRANSCRIBE SPLIT` (n1 pieces, n2 forced cuts, ms note length).
  - `Container.transcriber` is now this wrapper around the engine. A build without the engine still gets `NoTranscriber`.
- `speech/Transcriber.kt`: `RunPlace` (`ON_SCREEN` 2 threads, `BACKGROUND` 1), `TranscribeOptions(place, noteMs)`, and a second `transcribe(audio, options)` whose default body ignores them, so every existing `Transcriber` (`NoTranscriber`, test fakes) is unchanged. `PackTranscriber` honours both: a model opened with other threads is reopened; with no place given it uses its own default (2), as before. `SherpaMoonshineTranscriber` forwards the new call explicitly.
- `speech/DefaultPack.kt` (pure), the base-or-tiny decision:
  - `MemoryGuide`: `GUIDE_MB` 400; **`HARD_LINE = false`: the owner's question 5 as one constant**; `GUIDE_TOLERANCE_MB` 10 ("about 400 MB"); `MAX_EXTRA_WRONG_WORDS` 2.
  - `DefaultPackRule.decide(baseInJob, extraWrongWordsWhenCut, hardLine)`: base's measured peaks in the background job at each length, and the extra wrong words on the ramble, give M1, M2 (with L) or M3, and the policy to set.
  - `DefaultPack`: `MEASURED` (null until the phone run), `DEFAULT_PACK_ORDER` (base, tiny), `BASE_UP_TO_MS` (M2's L; null), and the `POLICY` built from them.
- `speech/ModelPacks.kt`: `PackPolicy(order, longNoteFromMs, longNotePackId)` and `PackChoice.choose(installed, free, noteMs, policy)`: the installed packs in the policy's order; a note longer than L gets tiny first when tiny is installed; memory still decides, as in 26d. `PackTranscriber` takes the policy (default `DefaultPack.POLICY`) and the whole note's length (the wrapper passes it with every piece, so one note uses one pack). Outcome M2 with no tiny pack uses base and logs `TRANSCRIBE NO_LONG_NOTE_PACK` (n1 note ms).
- `domain/Dbg.kt`: codes `SPLIT` and `NO_LONG_NOTE_PACK`.
- Debug only: `app/src/debug/java/app/stash/debug/LongNoteMemoryCheck.kt` (`LongNoteMemoryReceiver`, `LongNoteMemoryWorker`) and its one `<receiver>` line in the debug manifest. No existing debug file was edited.
  - It runs `Container.transcriber` inside a real WorkManager job on `files/spike25/s10.wav`, on three copies of it, or on three minutes of it repeated; with base or tiny (or the app's own choice, or uncut); at one thread unless told `place screen`.
  - It samples total PSS every 200 ms and writes the spike's `summary.tsv` layout, plus one numbers-only row per run in `files/spike26/long-notes/table.tsv`: pieces, forced cuts, longest piece, idle and peak PSS, engine and wall time, free and total memory, and the process's age (to show it was fresh).
- Tests: `LongNotesTest` (17). Every unit check in the card's list, plus: a three-minute note; softer sound at speech level never taken for a pause; the threads; outcome M2 in the adapter; the policy order; the decision rule, including card 25's own figures; the constants in force.

What differs from the card, and why:
- **`split` returns `List<Piece>`, not `List<IntRange>`**: a range cannot say that its cut was forced, which the joiner needs.
- **"The quietest stretch" has a tolerance, and ties go to an even share.** Taken literally (the single quietest 400 ms wins), the cut moves with noise differences of a few percent, and on the card's own test (50 s, a pause every 6 s) it can leave a last piece of 2.5 s, failing "each piece between 9 and 20 seconds". So places within twice the quietest level (or the floor) count as equally quiet, and among them the one nearest an even share of the rest (rest / ceil(rest / 15 s)) wins. Speech is at least three times the floor, so a cut still never falls in sound the level rule calls speech.
- **The noise floor is measured over the whole recording** (the running floor's final value), so every chunk is judged by the same floor. As with 26a's rule, sound that is loud and steady from the very first chunk becomes the floor; the tests start their "continuous speech" with 200 ms of room noise for that reason.
- **Threads:** the card's "the caller says which" is the new `TranscribeOptions.place`. Nothing calls it yet (26f will); calls without it keep 26d's two threads.
- **`DEFAULT_PACK_ORDER` is not set from a measurement yet** (the card sets it from the outcome). It holds D82's proposal, which is exactly 26d's behaviour, and `MEASURED` is null. The experiment log says what to change after the run. `PackChoice`'s working-set figures (base 330 MB, tiny 220 MB, from short notes) are unchanged; afterwards, the run's `peakPssKb - pssIdleKb` is the figure to put there.
- **Guide or hard line is `MemoryGuide.HARD_LINE`.** With `false`, "about 400 MB" allows 10 MB over, which keeps card 25's readings where the log put them: 401 MB on a short note in the job passes, 418 MB on the ramble misses. With `true`, base missing 400 MB even on a short note gives M3.
- **A case the card does not name:** every length within the guide, but cutting costs more than two words. The rule gives M2 with L = 20 s (base only for notes it never cuts).
- **ONNX Runtime's memory arena** cannot be limited through the pinned library. In the 2.0.1 AAR, `OfflineModelConfig` has `numThreads`, `debug`, `provider`, `modelType`, `tokens`, `modelingUnit`, `bpeVocab` and the per-model configs, and no arena or memory field (read with `javap`). Not tried further, as the card says.
- **The corpus check:** the card says none of the 15 sentences is cut, but s10 (27.7 s, the ramble) is longer than 20 s, so it is cut, in two pieces if it has a pause in the window. The other 14 go through untouched.

Unverified, needs the owner (nothing was installed and no adb command was sent): the measurement, the score, the outcome and the corpus check. The exact steps are in `docs/EXPERIMENT_LOG.md`, "Card 26e".
