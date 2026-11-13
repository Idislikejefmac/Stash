# 26d · The speech engine: the `Transcriber` seam, the Moonshine adapter, and where the model lives

Read [00-READ-FIRST.md](00-READ-FIRST.md) first. Part of the card 26 split ([index](26-voice-notes-with-audio.md), D77, D78, D81, D82).

**Status: built 2026-11-12, JVM and PC only** (753 unit tests pass with the engine AAR, and 753 without it with one skipped; 38 new; no schema change; the one new dependency is the sherpa-onnx 2.0.1 AAR the card names, kept outside the repository). Nothing was installed on the phone: the phone checks wait for the owner. See "What was built, and what differs" at the end.

## Goal

Given the samples of a recording, the app returns its words, on the phone, off the main thread, through one small interface that has a do-nothing version. The engine and its model are added to the real build with their licences recorded and shown. Nothing here records, stores or schedules.

## Depends on

13 (`Dbg`, `Container`), 26a (`Pcm`). `docs/LICENSES.md` rows for sherpa-onnx, ONNX Runtime and the Moonshine English models (present and verified 2026-11-12).

## Context

Read first: `app/src/debug/spike25/java/app/stash/debug/Spike25Runner.kt` and `EngineSpikeActivity.kt` (the code that ran these models on this phone: **copy its recogniser configuration, do not write one from the library's documentation**), `app/build.gradle.kts` (the spike's debug-only wiring, which this card replaces), `speech/SpeechEngine.kt` (the live recogniser's interface, which stays and is a different thing), and "Card 25: engine results" in `docs/EXPERIMENT_LOG.md`.

Measured on the S25 Edge, Moonshine base English (int8, CPU, 2 threads) through sherpa-onnx v2.0.1: about 0.015 of real time (a 10-second note in about 0.15 s; 0.5 s estimated on a mid-range phone); load 0.5 s; model 141.3 MB on disk; peak memory 351 MB for notes up to 8 s and 486 MB on a 28-second note with a screen open. Moonshine tiny: 44.2 MB, 243 to 376 MB, one key token less accurate. The four arm64 libraries are 31.4 MB and 16 KB aligned. **Word steering does not exist for Moonshine**: names are fixed afterwards by `domain/Corrector` (card 26f applies it).

### Decision: where the model comes from

**The model is not inside the APK and is not downloaded by the app. It is a "speech model pack" that is put on the phone once.** Reasons:

- A download would be the app's first request to a third party that the owner did not start (GitHub or Hugging Face would see his address), needs a host a product can rely on, and Stash has no server. It would need its own network package, outbound kind and explanation card. That is designed in [26l](26l-model-delivery.md) and not scheduled.
- Bundling adds about 141 MB to an APK that is installed with `adb` over Wi-Fi many times a day by every card's builder. Out of the APK, the cost to everyone else is the 31.4 MB of libraries (about 11.7 MB compressed).
- A pack in the app's files survives app updates and can be swapped (base, tiny, a better one later) without a new build.
- Tier 0 is unchanged: with no pack, voice notes use the phone's recogniser and the app says so.

The loader looks in two places, so that **bundling stays a one-line build choice** and not a redesign: first `files/models/<pack id>/`, then APK assets `models/<pack id>/`. Nothing in this card puts a model in the assets.

## Scope

### A. The seam (`speech/`)

1. ```kotlin
   interface Transcriber {
       /** Names the engine and model, e.g. "moonshine-base-en-2026-02-27/sherpa-2.0.1". Null: none available. */
       val id: String?
       fun availability(): Availability          // READY, NO_MODEL, LOAD_FAILED(code), OFF
       suspend fun transcribe(audio: Pcm): TranscribeResult
       fun release()                             // free the native memory now
   }
   sealed interface TranscribeResult {
       data class Ok(val text: String, val procMs: Long, val chunks: Int, val confidence: Float?) : TranscribeResult
       data class Failed(val code: TranscribeFailure, val retryable: Boolean) : TranscribeResult
   }
   ```
   `NoTranscriber` returns `NO_MODEL` and `Failed(NO_ENGINE, retryable = false)`. `Container.transcriber` chooses the real one or `NoTranscriber`. Feature code never names sherpa or Moonshine.
2. **Never on the main thread.** `transcribe` switches to one dedicated single-thread dispatcher owned by the adapter (one transcription at a time in the whole process) and starts with a check that it is not on the main looper. `confidence` is null unless the engine reports one; do not invent it.

### B. The adapter (`speech/sherpa/`)

3. Dependency: the file `sherpa-onnx-2.0.1.aar` (sha256 `a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7`), as `implementation(files(...))` from a path outside the repository given by a Gradle property, with `ndk { abiFilters += "arm64-v8a" }` for **all** build types. The build fails with a plain message if the file is missing or its hash differs. If a Maven coordinate for the same version exists, say so in the hand-back; do not switch to it without the licence register being updated. Remove the spike's `debugImplementation` of the AAR, the ML Kit GenAI line and the `-Xskip-metadata-version-check` flag; the `src/debug/spike25` sources may stay only if they still compile without them, otherwise delete them (the results are in the log).
4. `SherpaMoonshineTranscriber(locator, threads = 2)`: builds the offline recogniser from the pack's files exactly as `Spike25Runner` did (CPU provider, greedy search, no VAD, no NNAPI or GPU), feeds 16 kHz float samples, returns the text trimmed. The recogniser is created on first use and **released after each job** by the caller (26f): ONNX Runtime's memory pool does not shrink, and a reload costs about half a second.
5. Only `app.stash.speech.sherpa` may import `com.k2fsa.sherpa.onnx`; a source-scan unit test enforces it. No JVM unit test loads the native library.
6. Failures map to codes: `NO_ENGINE`, `MODEL_MISSING`, `MODEL_CORRUPT`, `NATIVE_LOAD`, `OUT_OF_MEMORY` (catch `OutOfMemoryError` around the call; a native kill cannot be caught and is handled in 26f), `ENGINE_ERROR`. Events: `DbgComponent.TRANSCRIBE` with `STARTED`, `OK` (n1 audio ms, n2 chunks, ms), `FAILED` (n1 code). Never words.

### C. Model packs (`speech/ModelPacks.kt`, `speech/ModelImporter.kt`)

7. `KnownModels` (pure): for each allowed pack, its id, display name, the exact file names and the sha256 and size of each file, taken from `docs/LICENSES.md`: **`moonshine-base-en-2026-02-27`** (`encoder_model.ort` 31,326,816 bytes, `decoder_model_merged.ort` 109,424,400 bytes, `tokens.txt` 549,350 bytes) and **`moonshine-tiny-en-2026-02-27`** (13,281,600 and 30,412,256 bytes, plus its `tokens.txt`). The register lacks a hash for the `tokens.txt` files: compute them and ask for them to be added to `docs/LICENSES.md`. **Only packs in this list load.** That is the integrity check and the licence check in one place: a non-English Moonshine file (non-commercial licence) cannot be loaded by accident.
8. `ModelLocator.find(): InstalledPack?`: the best installed pack (base before tiny), from `files/models/` then assets. A pack counts as installed only if a marker file written after verification is present; hashes are verified at import, not at every load.
9. `ModelImporter.import(zip: InputStream): ImportResult`: reads a zip made by `tools/make_model_pack.py` (new, small: takes the official sherpa-onnx archive, checks the hashes, writes a zip holding the model files and the package's own `LICENSE` file, stored not compressed). Entries are accepted only if their names are exactly those in `KnownModels` (nothing else is written, so a crafted path cannot escape the folder); each is streamed to `files/models/<id>.tmp/` while hashing; on a full match the folder is renamed and the marker written; on any mismatch the temporary folder is deleted and the result says which file was wrong. Needs 300 MB free; says so if not.
10. Two ways to give the app the zip, neither with network code:
    - **Settings → Voice notes → "Add the speech model"**: the system file picker (`ACTION_OPEN_DOCUMENT`); a progress line; then "Speech model: Moonshine base, 141 MB · Remove". "Remove" deletes the folder.
    - A debug-only receiver (`app/src/debug/`) that imports a zip already copied into the app's files with `adb` (the route the spike used), so the owner's phone can be set up from the PC. `tools/make_model_pack.py --push` does both steps.
11. Add `files/models/` to the data-extraction rules' exclusions (16a item 13): a 141 MB file that any phone can re-import is not worth transferring.

### D. Licences and notices (D78)

12. Add to the app, as plain text assets under `assets/licenses/`: the Apache-2.0 text for sherpa-onnx (and any NOTICE or third-party licence file found inside the AAR or in the sherpa-onnx repository for code built into its libraries), the MIT text with "Copyright (c) Microsoft Corporation" for ONNX Runtime together with its `ThirdPartyNotices.txt`, and the `LICENSE` file from the Moonshine package (MIT, "Copyright (c) 2025 Useful Sensors, Inc. (dba Moonshine AI)"). The Moonshine text ships in the **app**, not only in the pack, because the app is what uses the model.
13. `OpenSourceNotices` (pure list: name, what it is, licence name, asset path) and a plain Settings row **"Open source notices"** that shows the list and each text. Card 14 item 6 planned such a row for SQLCipher; whichever card is built second adds its entry to this list instead of making a second screen.
14. The hand-back states, for `docs/LICENSES.md`: the items now **shipped** (moved from "candidates"), the exact files, and anything read in the AAR that the register does not list.

## Out of scope

Long recordings and the memory guide (26e). When to run, retries, applying corrections (26f). Recording and decoding (26a, 26b). Parakeet, Whisper, ML Kit GenAI Speech (card 25 ruled each out: size, memory, or Google's alpha terms). Downloading a model (26l). GPU or NPU acceleration. A second process for the engine (the spike's separate process crashed in `StashApp.onCreate`; revisit only if 26f's kill handling proves insufficient).

## Acceptance checks

**Unit tests (JVM)**

- `NoTranscriber` behaves as item 1 says; a fake `Transcriber` can be chosen by `Container`.
- `KnownModels`: every pack has names, sizes and hashes; ids are unique.
- `ModelImporter` with small fake "models" and matching fake hashes: a good zip installs and is found by `ModelLocator`; one wrong byte installs nothing and leaves no temporary folder; an entry named `../x` or an unknown name is ignored and the import fails; a second import of the same pack replaces it cleanly; base is preferred over tiny when both are present.
- The import-statement scan in item 5.
- With no pack, `Container.transcriber.availability()` is `NO_MODEL`, and the "every capability off" test from card 13 still passes.

**Real phone (S25 Edge); experiment log, "Card 26"**

- APK size before and after (expect about +31 MB installed, +12 MB compressed). `zipalign -c -P 16 -v 4` on the **release-type** APK: every `.so` passes. The library loads on first use with no error.
- Import the base pack through the file picker and through the PC route: time taken, and `files/models/` contents. Flip one byte in a copy of the zip: refused, nothing left behind.
- A debug-only button transcribes the 15 corpus recordings still on the phone (`files/spike25/sNN.wav`) through `Container.transcriber`. The texts must equal the spike's `moonshine-base` run (`docs/spike25/results/`), sentence for sentence. Any difference means the configuration was not copied faithfully: stop and say so.
- Load time cold and warm, and peak memory for an 8-second note with only this screen open (expect about 350 MB). After `release()`, memory falls back near the idle figure: record both numbers.
- The Open source notices row shows three entries and their full texts at 200% font size.
- Aeroplane mode throughout: everything above works.

## Notes and risks

- sherpa-onnx's Kotlin classes changed shape between releases; the spike's code is the only version known to work with the 2026-02-27 Moonshine packaging (`encoder_model.ort`, `decoder_model_merged.ort`). Pin 2.0.1.
- The libraries add about 46 MB of mapped memory to any process that loads them. Load them lazily, on first transcription, never at app start: capture must stay instant on a cheap phone (D81).
- If the owner would rather have the model inside the APK (nothing to import, a 170 MB larger install), that is `ModelLocator`'s second location plus an assets folder in Gradle. It is his call; the index lists the question.
- Not legal advice. The texts shipped are the ones the register names; Q48 (a lawyer's review before any sale) stands.

**Suggested builder: Opus.** Native-library integration, a build change that touches every build type, and a file importer that must not be trickable.

## What was built, and what differs from the card (2026-11-12)

Built (under `app/src/main/java/app/stash/` unless noted):
- `speech/Transcriber.kt` (pure): `Transcriber`, `Availability` (`Ready`, `NoModel`, `LoadFailed(code)`, `Off`), `TranscribeResult` (`Ok`, `Failed`), `TranscribeFailure` (`NO_ENGINE` 1, `MODEL_MISSING` 2, `MODEL_CORRUPT` 3, `NATIVE_LOAD` 4, `OUT_OF_MEMORY` 5, `ENGINE_ERROR` 6) and `NoTranscriber`.
- `speech/PackTranscriber.kt` (pure): everything about running a pack that does not depend on the library. The engine is a small `SpeechRuntime` (load the library, open a pack, decode floats), so the tests drive the real adapter logic with a fake and no JVM test loads native code.
  - One process-wide single thread, `stash-transcriber` (`TranscriberThread`); `transcribe` switches to it and then checks it is not on the main looper.
  - The library and model load on the first writing-out, never at start-up, and stay loaded until `release()`. `release()` from the main thread is queued behind any running job instead of blocking; from any other thread it waits.
  - Failures map as item 6 says; `OutOfMemoryError` also lets go of the model. `confidence` is always null (sherpa-onnx's offline result has no confidence field).
  - Samples are divided by 32768 to floats, exactly as the spike did.
- `speech/sherpa/` lives in **two source folders**, chosen by the build:
  - `app/src/sherpa/java/.../SherpaMoonshineTranscriber.kt`: `SherpaEngine` (`BUILT = true`), `SherpaMoonshineTranscriber(locator, threads = 2, ...)` and `SherpaRuntime`. The recogniser configuration is the spike's MOONSHINE_V2 branch copied line for line (CPU, `greedy_search`, `maxActivePaths` 4, 16 kHz, 80-dim features, dither 0, no hotwords, no VAD, 2 threads). A pack in the APK's assets would be opened through the `AssetManager` constructor.
  - `app/src/nosherpa/java/.../SherpaEngine.kt`: `BUILT = false`, no engine.
- `speech/ModelPacks.kt` (pure): `PackFile`, `KnownPack`, `KnownModels` (base, tiny), `InstalledPack`, `PackMarker` (`.verified`), `AssetLister`, `ModelLocator` (`installed()`, `find()`, `broken()`, files then assets, base before tiny), `PackChoice` (the memory-aware choice, below) and `ModelPacks` (where packs live; `remove`).
- `speech/ModelImporter.kt` (pure): `ModelImporter.import(zip, progress)`, `ImportResult`, `ImportFailure` (codes 1 to 9) and `ImportWords` (the Settings sentences).
- `domain/OpenSourceNotices.kt` (pure): `Notice` and the three entries; `forBuild(engineBuilt)`.
- `assets/licenses/`: the texts listed in the hand-back table below.
- `data/Container.kt`: `modelPacks`, `modelLocator`, `modelImporter`, `speechEngineBuilt`, and `transcriber` (a settable property: the engine in this build, else `NoTranscriber`; a test or debug tool may set a fake). Free memory comes from `ActivityManager.MemoryInfo.availMem`.
- `domain/Dbg.kt`: `DbgComponent.TRANSCRIBE`; new codes `LOADED` and `RELEASED`. `STARTED` (n1 audio ms), `OK` (n1 audio ms, n2 chunks, ms), `FAILED` (n1 code, n2 1 if retryable), `LOADED` (n1 pack rank, n2 0 files or 1 assets, ms), `STORED` (pack imported: n1 bytes, n2 rank, ms), `REFUSED` (n1 import failure code), `REMOVED`. Never words.
- `ui/SpeechModelSettings.kt` and two hooks in `ui/MainActivity.kt` / `MainViewModel.kt`: Settings → "Voice notes": "Speech model: none" with "Add the speech model" (the system file picker, `ACTION_OPEN_DOCUMENT`), a percentage progress line, then "Speech model: Moonshine base, 141 MB · Remove" (with a confirming question) and the licence line "MIT licence, Moonshine AI (English model)". A build without the engine says so instead. Below it, "Open source notices": the list, and each entry's full text split into paragraphs (ONNX Runtime's notices are 325 KB). Back returns to the list, then to Settings.
- `res/xml/data_extraction_rules.xml`: `models/` excluded from cloud backup and device transfer (item 11).
- `tools/make_model_pack.py`: checks every file of the official archive (or the folder it unpacks to) against the same sizes and sha256 as `KnownModels`, writes `<pack id>/<file>` entries stored, not compressed; `--push` copies it into the app's files with `adb exec-in run-as` and asks the debug receiver to import it. It downloads nothing.
- Debug only (`app/src/debug/`): `ModelImportReceiver` (the PC route) and `TranscriberCheckActivity` (the corpus through `Container.transcriber`, with cold and warm load and memory), both in the debug manifest; `src/debug/spike25-nogenai/` (see the build below).
- `app/build.gradle.kts`, the build:
  - The AAR's path is the property `stash.sherpaAar`, from `-P`, `gradle.properties` or `local.properties` (on this PC it is in `local.properties`, which is not in the repository).
  - Given: the file must exist (else the build stops at configuration with a plain message) and have the card's sha256 (task `verifySherpaAar`, before `preBuild`; plain message naming the expected hash). It is `implementation(files(...))` for every build type, and `src/sherpa/` is compiled.
  - Not given, or given empty (`-Pstash.sherpaAar=`): `src/nosherpa/` is compiled, the app has no engine and every test still runs.
  - `ndk { abiFilters += "arm64-v8a" }` moved to `defaultConfig` (all build types).
  - Removed: the spike's `debugImplementation` of the AAR, the ML Kit GenAI line, and `-Xskip-metadata-version-check`.
- Tests (38 new): `SpeechEngineTest` (25), `SpeechSourceScanTest` (8), `SpeechContainerTest` (4, Robolectric, including the notices screen) and `RealPackImportTest` (1; it imports the real 141 MB base zip made by the tool and runs only when `STASH_REAL_PACK_ZIP` names one; it was run and passed: 141,313,910 bytes verified and installed in 460 to 658 ms on the PC). `AttachmentTest`'s exclusion check now expects `models/` beside `att/`. Every unit check in the card has a test.

What differs from the card, and why:
- **The build without the AAR.** The card says the build fails if the file is missing; the brief for this build asked that a build without it still compiles with `NoTranscriber` and passes the tests. Both hold: a path that is given but wrong or missing stops the build with a plain message; no path at all builds without the engine. That is why `speech/sherpa/` has two source folders, one per case.
- **The spike sources stay and still compile.** `Spike25MlKit.kt` cannot compile without the ML Kit GenAI library, and `EngineSpikeActivity` names it. The card would delete the spike sources, but this build was told not to delete or edit spike files. So `Spike25MlKit.kt` is excluded from Kotlin compilation, and `src/debug/spike25-nogenai/Spike25MlKitRemoved.kt` stands in for it: same constructor, and it only says the library was removed. The rest of the spike compiles as before, whenever the engine does. Deleting `src/debug/spike25*` and the `exclude` line later is safe.
- **`KnownModels` lists four files a pack, not three:** the package's own `LICENSE` (13,344 bytes, sha256 `6148d757...0abd`) is verified like the models, so each pack provably carries it (D78). Base and tiny have byte-identical `tokens.txt` and `LICENSE` files.
- **Zip layout:** entries are `<pack id>/<file>`, because base and tiny have the same file names. Only those exact names (and the bare `<pack id>/` folder entry) are accepted; anything else, including `../x`, fails the whole import and nothing is written.
- **Memory-aware load (asked for in this build's brief, not in the card):** `PackChoice` loads the best installed pack whose working set fits the memory free now: base needs 330 MB, tiny 220 MB (card 25's short-note peaks less the idle app). If none fits, the result is `OUT_OF_MEMORY`, retryable, and nothing is loaded. A model already loaded is kept. With only base installed, a phone with less than 330 MB free gets `OUT_OF_MEMORY` (retry later) instead of a load that might be killed. Card 26e may change the figures.
- **The import's free-space check** is "300 MB free before starting", as the card says (the zip is streamed, not copied first).
- **The notices** list three entries, as the card says, each with one or more texts. sherpa-onnx's entry also carries a short index of the code its build compiles in, and those projects' licence files. A build without the engine lists none.
- **Asset packs** count as installed if `models/<id>/` in the APK lists every file and the marker; no file sizes are checked there (a bundling build would verify them).
- **The debug corpus button** is a debug activity started by `adb` (with an `autostart` extra), as 26a's check screen is.

Unverified, needs the owner (nothing was installed and no adb command was sent): every "Real phone" check of the card. The steps are in `docs/EXPERIMENT_LOG.md`, "Card 26d".
