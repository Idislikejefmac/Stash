# 25 · Spike: which speech engine, if any, should ship inside the app

Read [00-READ-FIRST.md](00-READ-FIRST.md) and the "Card 24" section of `docs/EXPERIMENT_LOG.md` first. Decisions D77 (keep the audio) and D78 (every dependency and model must be sellable) govern this card.

## Why this exists

Card 24 proved the phone's own recogniser cannot be given recorded audio, and that it and our recorder cannot share the microphone. So keeping the audio of a note (D77) means the app records for itself and transcribes with an engine it carries. The same engine can later **re-transcribe** a saved note, which is the owner's actual complaint: odd transcriptions that become "the fact" and cannot be checked or redone.

This spike answers, with measurements on the S25 Edge and the owner's own voice, whether such an engine is **accurate enough, fast enough, small enough and licensed so the app could be sold**. Nothing here is permanent. The code and model files are throwaway; the output is a written verdict in `docs/EXPERIMENT_LOG.md`.

## Depends on

Card 24 (done; verdict C). No schema change, no release code, no new dependency in `app/src/main`: everything lives in `app/src/debug/` and a developer-only Gradle configuration (`debugImplementation`) so a rejected candidate leaves no trace in a release build. Needs the phone (wireless adb is set up) and the owner's voice for about 20 minutes.

## What is being compared

The **runner** is not a candidate; the **models** are. The runner is sherpa-onnx (Apache-2.0; runs Whisper, NeMo/Parakeet, Moonshine and others through ONNX Runtime, MIT). Verified 2026-11-12: release v2.0.1 ships `sherpa-onnx-2.0.1.aar` (47.2 MB, all ABIs including arm64-v8a) and a `static-link-onnxruntime` AAR. Use the AAR as a file dependency (`debugImplementation(files(...))`) and keep only `arm64-v8a` (`abiFilters`). Use the newest release on the day and record its version. Fallback runner: whisper.cpp (MIT), built from source with the NDK; it is also the only way to give Whisper a text prompt (below).

Licences, sizes and links are in `docs/LICENSES.md` (verified 2026-11-12). Sizes below are the int8 files as packaged for sherpa-onnx.

| Candidate | Why | Known concerns |
| --- | --- | --- |
| Whisper `base.en` (about 160 MB) and `small.en` (about 375 MB) | MIT; well known | 30-second window: longer audio must be cut by the VAD first. Invents text on silence and noise (use the VAD). **sherpa-onnx has no prompt option for Whisper**; a prompt needs whisper.cpp |
| NVIDIA Parakeet-TDT 0.6B v2 (English) | Among the most accurate open English models; supports hotwords in sherpa-onnx | About 630 MB on disk and likely over 1 GB of memory while running; CC-BY-4.0 (attribution) |
| NVIDIA Parakeet TDT-CTC 110M (English) | A fifth of v2's size (126 MB), still accurate on benchmarks; CC-BY-4.0 | Runs as CTC in sherpa-onnx, so no hotwords |
| Moonshine English (sherpa-onnx `base-en`, newest packaging) | MIT; built for small devices | Lower accuracy than the larger models; no hotwords. The newer Moonshine "streaming small/medium" models (MIT, better on benchmarks) need Moonshine's own Android library: try only if base is close to the best |
| **Baseline:** the phone's own recogniser, live | What the owner has today | Cannot read files, so it is run by speaking the corpus a second time (different audio: note it) |

Not candidates: Parakeet "unified" and "nemotron-speech-streaming" (NVIDIA Open Model License, a custom licence: D78 "ask first"); non-English Moonshine files (non-commercial licence). ML Kit GenAI Speech Recognition (alpha) now accepts recorded files and lists this phone's chip family, but its terms are Google's own and it sends usage metrics to Google: **measure it only if the owner says yes in writing** (record the answer in the log); if he does, it is one more row run through the same corpus.

Add or drop a candidate if its licence fails D78 (see "Licence leg"). Do not test anything that is not on the register in `docs/LICENSES.md`.

## Scope

### A. The corpus (owner, once, about 20 minutes)

1. The owner writes the **reference text** for 15 sentences in `docs/spike25/corpus.txt` (git-ignored folder; D53) using his real habits. Include at least: the pool sentence with numbers ("Pool test: free chlorine 5, pH 7.2, alkalinity 70, cyanuric acid 40"); a price ("the Tide is on sale for twenty four ninety nine"); a placement ("I'm putting my reading glasses in the nightstand drawer"); a loan ("I loaned my guitar to Frank on Tuesday"); "Syd has the spare key in the frunk"; a task with a place ("I need to remember to buy ground beef at Costco"); two wine labels read aloud (names he finds hard); a Tesla sentence; one 30-second ramble with natural pauses; two sentences spoken in a car or with background noise; one spoken quietly; one with a second or two of silence before he speaks. Any of his own that the phone's recogniser got wrong in practice are the most valuable. Next to each sentence write its **key tokens** in the form expected (`7.2`, `24.99`, `Syd`).
2. A debug screen `CorpusRecorderActivity` (extend `AudioSpikeActivity`; keep the Mode button idea): shows sentence N, Record / Stop / Redo, and saves each as 16 kHz mono 16-bit PCM WAV, named `s01`..`s15`, into `files/spike25/`. Encoding to AAC and Opus is done afterwards from the WAV (leg C), so every format starts from the same audio. No transcription in this screen.
3. A second pass, same sentences, with the phone's recogniser live (the baseline): store its text per sentence in the same folder. (Existing normal-dictation button; add the sentence label.)

### B. The engine runner (debug only)

4. `EngineSpikeActivity`: for a chosen model folder in `files/spike25/models/` (pushed with `adb`; models are **not** in the APK or the repository) transcribes every `sNN` file and writes `sNN.<engine>.txt` plus a timing line. Show progress on screen; read results with `uiautomator dump` or by pulling the text files (rule: the screen alone is lost on restart). Run on a background thread, never the main thread.
5. Silence and long audio: run the Silero VAD (sherpa-onnx's `silero_vad.onnx`, MIT) before Whisper and Moonshine, transcribing only the speech segments; it stops Whisper inventing text on silence and splits the 30-second ramble under Whisper's window. Run each candidate once with the VAD and once without on the silence and noise sentences, and report both.
6. Word steering, **with and without**, reported separately. Build one word list from the corpus key tokens and names (about 20 words):
   - Parakeet-TDT 0.6B v2 in sherpa-onnx: `model_type` `nemo_transducer`, `decoding_method = modified_beam_search` (greedy search ignores hotwords), `hotwords_file` (one phrase per line, optional `:score` at the end), `hotwords_score` (try 1.5 and 3.0), and `modeling_unit = bpe` with a `bpe_vocab` file. Hotword support for NeMo transducers is in the sherpa-onnx source as read on 2026-11-12; if the release used rejects it, or the model folder has no `bpe.vocab`, say so and report Parakeet without steering. Run "without" also with `modified_beam_search`, so the difference is the hotwords only.
   - Whisper: only through whisper.cpp (`initial_prompt`, a short sentence that uses the words). Do this leg only if a Whisper model is within about two key tokens of the best candidate without steering; otherwise write "not run" and why.
   - CTC Parakeet and Moonshine: no steering exists; say so.
7. Settings, recorded with every result: model precision (int8 for all; also fp16 or fp32 for the winner only, to see what int8 costs), CPU provider, and `num_threads`. Run the winner at 2 and at 4 threads. Do not use NNAPI (deprecated since Android 15) or a GPU/NPU provider in this spike; note it as later work if CPU speed fails the bar.
8. Timing: for each sentence, wall-clock seconds and the **real-time factor** (processing seconds divided by audio seconds; below 1 is faster than speech), excluding model load. Separately: model load time, cold (first after app start) and warm. Report median and worst per candidate. Peak memory: `adb shell dumpsys meminfo app.stash` during the ramble (record the TOTAL PSS); note if the app is killed.
9. Battery and heat: the 15 sentences are too short to move the battery gauge. For the leading candidate only, transcribe the whole corpus 10 times in a row with the screen on and record percent before and after, and whether the phone got warm. Rough is fine; say it is rough.

### C. The audio-size leg ("smallest legible")

10. Encode each WAV with MediaCodec: AAC-LC 32 kbps (as card 24); AAC-LC 16 kbps; Opus 24, 16 and 12 kbps. Opus: encoder "audio/opus" (Android 10+), written into Ogg with `MediaMuxer` and `MUXER_OUTPUT_OGG` (API 29; this app's `minSdk` is 35, so both are available; verified 2026-11-12 on developer.android.com). First list the phone's encoders with `MediaCodecList` and log the Opus encoder's name and supported sample rates and bitrates; add the muxer track from the encoder's output format (after `INFO_OUTPUT_FORMAT_CHANGED`), not a hand-made one. If Opus or the Ogg muxer fails on this phone, log the error and continue with AAC only. Decode each back to 16 kHz PCM (MediaExtractor + MediaCodec) and run the **best engine** on every one. Report: bytes per minute, accuracy on the corpus, and whether the owner can still understand a played-back sample by ear. "Legible" means both: the engine still gets the words, and a person can hear what was said. Pick the smallest setting that loses no key tokens against the WAV run.

### D. Background and locked-phone checks

11. Start a recording from the locked phone through the existing notification action pattern (microphone only from a visible activity) using our own recorder: does it capture real audio (loudest sample is not near zero)?
12. Run the best engine from a WorkManager job with the screen off on a 30-second recording: does it finish, how long, does Samsung's battery management delay it, is the process killed for memory? (Today's design extracts in the background; this decides whether transcription can too.)

### E. Native-library checks (before measuring)

13. Record `adb shell getconf PAGE_SIZE` (16384 means a 16 KB kernel). Whatever it says, check the debug APK's native libraries for 16 KB alignment (Android Studio APK Analyzer, or `zipalign -c -P 16 -v 4 <apk>` and `llvm-objdump -p <lib>.so | grep LOAD`, every LOAD at `2**14` or more) and record the result per `.so`. A release would need them aligned (Google Play rule for apps targeting Android 15+). Also record whether Android showed a "page size compat mode" warning on first launch.

### F. Licence leg (do this before building anything)

14. The register in `docs/LICENSES.md` was checked on 2026-11-12. Before downloading, confirm each file you push is the one on the register (same model, English only) and add any new one first. Anything non-commercial, research-only, copyleft that would affect the app's own source, or a custom licence the owner has not accepted is dropped before it is measured. CC-BY-4.0 models need credit, a licence link and a note that the files were converted. This is not legal advice; it is the checklist a lawyer would want (Q48).

## What to record

In `docs/EXPERIMENT_LOG.md`, a section "Card 25" with real results only:

- Versions: sherpa-onnx (and whisper.cpp if used), each model archive's exact name, page size, alignment result.
- Per candidate: word error rate on the corpus (wrong + missing + extra words, divided by reference words; lower case, no punctuation; numbers compared as digits, so "five" and "5" match); **key-token accuracy** (of the key tokens written in A1, how many come out right, counted by hand); with and without steering; with and without the VAD where run. With about 150 words, a difference of one or two words is noise: say so.
- The phone's recogniser baseline on the same sentences.
- Speed: real-time factor median and worst, load time cold and warm, threads used; peak memory; battery and heat (rough); the background-job result.
- Size: model on disk; APK size delta (the AAR's arm64 libraries); peak memory.
- Audio-size table (leg C).
- Licence table (leg F), copied from the register.
- Three or four example sentences as heard vs written, harmless ones only, to give the owner a feel.

## Verdicts (the owner decides; these are proposed bars, change them if you like)

- **A. Adopt an engine as the default for voice notes** (build card 26: record, save, transcribe in the background, replay and re-transcribe). Bar: key-token accuracy at least as good as the baseline, overall word error rate no worse; a 10-second note transcribed in under about 5 seconds warm (real-time factor 0.5) and under about 15 seconds including a cold model load; peak memory low enough that the background job is not killed; added size acceptable to the owner (state it: APK delta plus model download); 16 KB-aligned libraries available; licence clean.
- **B. Hybrid.** The phone's live recogniser stays the quick capture path (no audio), and the engine is an optional "keep the recording" mode or an "improve this note" action on recorded notes. Fits an engine that is accurate but too slow or too big to run on every note.
- **C. No engine clears the bar.** Keep the phone's recogniser; offer a "record only, transcribe later" memo (audio plus a placeholder) so nothing is lost while better engines arrive. State what would change the verdict.

## Out of scope

Building any of this into the real capture path, schema, encryption of the audio (card 14 and card 16's store design), playback UI, backup size, retention settings, speaker separation, languages other than English, GPU/NPU acceleration, live (streaming) transcription while speaking, and any engine that is not a candidate above.

## Notes

- The builder should be a strong model for the integration of a native library (sherpa-onnx) and the codec work; the corpus recorder is plain Android and fine for a cheaper one.
- Keep the owner's recordings and reference text out of the repository; delete `files/spike25/` from the phone at the end.
- If a native library fails to load on this phone (16 KB page-size alignment is the usual cause), record the error text and the library version, and try the next candidate; do not retry the same combination.
- Order to save time: Parakeet 110M and Moonshine base first (small, quick to push), then Parakeet v2, then Whisper. Stop adding candidates once one clears verdict A's bar with a clear margin and the others are clearly behind; say which were not run.

## Owner decision, 2026-11-12

**ML Kit GenAI Speech Recognition is approved for measurement as a fourth candidate, with no commitment to ship it** (see `docs/LICENSES.md`, "Owner decisions"). Measure it on the same corpus and the same metrics as the others, and record that it runs under Google's terms and reports usage to Google. Adopting it would need a new, explicit yes. "MIT-only" is not a goal: CC-BY-4.0 candidates are allowed, and licence is only a tie-break.

## Device target (D81, 2026-11-12): this section overrides the speed, memory and size bars above

The app must run well on a **low-to-mid-range 2026 phone** (aim: 90%+ of phones; 85 to 89% acceptable for a clear gain). The S25 Edge is a flagship, so it flatters every number.

1. **Reference mid-range phone, as a guide:** 4 to 6 GB of RAM, an eight-core mid-range chip whose big cores are roughly a third to a half as fast as the S25's, Android 15 or later. Without a real one to test on, **estimate** by (a) running the winner pinned to the phone's slower cores or fewer cores (try `adb shell taskset` on the app's threads, and `num_threads` 1 and 2; unverified that this works for the app's own threads, so say whether it did) and (b) multiplying S25 timings by 3 as a rough guess, stating it as a guess. If the owner has an older or cheaper phone to hand, run the winner on it; that is worth more than any estimate.
2. **Bars, replacing the old ones:** a 10-second note transcribes in under about 5 seconds warm on the estimated mid-range phone (so about 1.5 seconds on the S25 at 3x); the whole job fits in **about 400 MB of peak memory** for the app process (TOTAL PSS from `dumpsys meminfo`) so it is not killed on a 4 GB phone; the model on disk is **no more than about 200 MB** by default; a cold start loads in a few seconds. Treat these as guides: say by how much each candidate misses and let the owner decide.
3. **Parakeet-TDT 0.6B v2 (about 630 MB)** is measured **last and only as the accuracy ceiling** (what the best open model could do on his voice), with its memory and speed recorded, and is not a verdict-A candidate. If it is far more accurate than everything small, the finding is "offer a large model as an opt-in download for capable phones", not "ship it".
4. **Order of candidates:** Parakeet TDT-CTC 110M (126 MB), Moonshine base, Whisper base.en, ML Kit GenAI Speech (system-managed model; check which phones it supports, because a feature missing on a mid-range chip fails this target), then Whisper small.en, then the ceiling run.
5. **Also record:** whether the engine must be a download at first use (and how big) or can be in the APK; a download is a new outbound request and needs its own explanation card (card 23).
6. **Opt-in size tiers** (like picture size in card 16b) are a possible outcome: a small default model for everyone and a larger one for phones that can afford it. The spike reports whether the accuracy gain from the larger model is worth that complexity.
