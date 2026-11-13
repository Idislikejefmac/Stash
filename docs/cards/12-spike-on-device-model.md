# 12 ◆ · Spike: can the phone's own model file notes? (optional, half a day)

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

Find out, on the owner's actual phone, whether Gemini Nano through Android's ML Kit GenAI Prompt API can turn a spoken note into records well enough to be used for private notes (which today are filed only by simple rules), or for everything when the hosted model is off. This is an experiment with a written result, not a feature.

## Depends on

04 (for the seed set, the scorer and the instruction text). Can be run any time after it.

## Context

What is known, from Google's documentation as read on 2026-11-11 by a research helper. **None of it has been tried on this phone; check each point and record what you find:**

- The Prompt API is in beta. Google's device list for it includes the Galaxy S25 Edge.
- The model is downloaded and managed by the system (AICore). The app asks for status: available, downloadable, or unavailable. The download size is not published.
- Input is limited to roughly 4,000 tokens. There is a per-app quota.
- **Inference is allowed only while the app is the top foreground app.** A call from a background worker or foreground service fails with a "background use blocked" error.
- Typed structured output exists as an alpha library using Kotlin annotations (`@Generable`, `@Guide`) processed by KSP. It may not support every shape (no recursive types).
- Google states the system component keeps no record of inputs or outputs. Nothing leaves the phone.

Documentation: `https://developers.google.com/ml-kit/genai/prompt/android` and its "get started" and "structured output" pages. Follow the live pages; the API is changing.

Why it matters: the app's privacy promise is strongest if private notes can be understood without leaving the phone. Why it might not work: beta, foreground-only, and small models are weakest at exactly the hard parts here (several records from one note, exact numbers).

## Scope

1. **Throwaway code only**, in the `debug` source set (`app/src/debug/java/app/stash/debug/nano/`) and a debug-only screen reachable from the debug menu. Nothing in `src/main` changes. The ML Kit dependency is `debugImplementation`.
2. Screen step 1: show model status; a button to start the download if needed; record how long it takes and, if the system shows it, the size.
3. Screen step 2: a text box and "File this": runs the card-04 instruction (shortened if needed) plus the note through the model and shows the raw reply and the time taken. Try two ways of getting structure and note which works: (a) the typed structured-output library with a simplified record class (kind, title, quote, item, where, person, due words, a list of metric/value pairs); (b) plain text with the instruction "reply with JSON only" parsed by card 03's parser.
4. Screen step 3: "Run the seed set": the 40 seed sentences from `eval/seed.jsonl` (bundled into the debug build as an asset), one after another, results written to `files/debug/nano-results.jsonl`. `tools/score_extraction.py --from-file` scores that file with the same rules as the hosted model.
5. Try once from a background worker and record the exact error, to confirm the foreground rule.
6. Try keeping the capture screen open after "Saved" and calling the model there: does it work when the capture was started from the lock screen? Record it.

## Out of scope

Wiring the model into the pipeline. An `Extractor` implementation in `src/main`. Other on-device models (Gemma through LiteRT-LM): note in the hand-back whether this spike makes them worth a look. Embeddings.

## Acceptance checks

- **Real phone, S25 Edge:** every item below is written in `docs/EXPERIMENT_LOG.md` under a new heading "On-device model (card 12)":
  - Status on first run (available / downloadable / unavailable), download time and size if seen.
  - Whether typed structured output worked, and with what limits.
  - Seed-set scores in the same columns as the hosted model's: tasks found, false tasks, reading numbers exact, right record count; median seconds per note.
  - The background-call error text.
  - Whether it runs from the capture screen over the lock screen.
  - Any quota or rate-limit message met.
- **Verdict line**, one of:
  - **Pass** (tasks found ≥ 8 in 10, reading numbers ≥ 9 in 10, under 5 seconds a note, runs from the capture screen): recommend a follow-up card to add it as the extractor for private notes.
  - **Partial** (passes on single-record notes only): recommend using it for kind and title only, rules for the rest.
  - **Fail or unavailable:** say why; recommend revisiting in a year.

## Notes and risks

- If the device reports "unavailable", stop there: that is the result. Do not try to side-load a model.
- Do not put real notes through this screen until the verdict is in; use the seed sentences.
- Remove nothing afterwards: the code is debug-only and documents the experiment. If it stops compiling later because the API changed, delete it and keep the log entry.
