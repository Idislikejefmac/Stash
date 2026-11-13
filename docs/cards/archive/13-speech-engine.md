# 13 · The real speech engine, and retries

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

Replace the fake speech engine with the on-device engine chosen in spike 03, and make failed transcriptions retry by themselves.

## Depends on

03 (read its decision in `docs/EXPERIMENT_LOG.md`, section "Speech to text on the phone"), 12.

## Context

`SpeechToText` (card 12) takes a finished buffer of 16 kHz mono PCM and returns text, with no network. Capture already saves encrypted audio and a `pending_audio` row when transcription fails.

Spike 03 chose between:

- **A bundled engine, sherpa-onnx** (Apache-2.0; Kotlin API; ONNX Runtime native library) with a specific model. Expected default.
- **Android's on-device `SpeechRecognizer`.** It listens to the microphone itself rather than accepting a buffer, so if the spike chose it, it does not fit `transcribe(pcm)`: implement it instead as an alternative `LiveSpeechCapture` used by `CaptureSession` in place of its own recorder + detector, keep the cue order and state machine identical, and note that failed transcriptions cannot be retried (no audio is kept).

Build whichever the log says. If the log has no decision yet, stop and ask.

## Scope

For the bundled engine:

1. Add the sherpa-onnx Android library for `arm64-v8a` and the chosen model. Put the model in the APK's assets if the APK stays under about 300 MB; otherwise download is **not** an option (no general network code is allowed): ask the owner. Record the APK size before and after.
2. `speech/SherpaSpeechToText`: loads the recognizer once (lazily, off the main thread), keeps it for the process lifetime, `isReady()` true after load. Converts `ShortArray` to the float samples the library wants. Language tag passed through for multilingual models, ignored for English-only ones.
3. Warm-up: `CaptureActivity`/`CaptureService` call `isReady()` as soon as they start, so the model loads while the user is speaking.
4. Replace the engine's own trailing-silence trimming only if needed; the capture session's detector already decided the end.
5. Simple text clean-up (`speech/TranscriptCleaner`, pure Kotlin): trim, collapse whitespace, drop a lone leading filler, remove bracketed non-speech tags such as `[BLANK_AUDIO]` or `(music)`; a result that is empty or only such tags counts as no speech.
6. **Retries:** `speech/PendingAudioWorker` (`WorkManager`, unique, backoff): for each `pending_audio` row, decrypt, transcribe, update the note's `raw_text`/`text`, run the structuring pipeline, delete the file and row. After 7 days or 10 attempts, delete the audio, set the note text to "(could not understand a recording from <date>)" and `needs_review = true`. Enqueued whenever a pending row is written and at app start.
7. Settings: none yet beyond a debug screen showing engine name, model, load time and last transcription time.
8. Licence notices for the engine and the model in an "About" text asset.

## Out of scope

Cloud speech, speaker verification, custom vocabulary biasing (note in the hand-back whether the chosen engine supports hot-words; it would be a later improvement for names), other languages.

## Acceptance checks

- **Unit (JVM):** `TranscriptCleaner` cases; `PendingAudioWorker` logic with a fake engine (success path; give-up path after 10 attempts; file deleted in both).
- **Unit:** `NetworkBoundaryTest` still passes (the engine must not be given network access; the app has no `INTERNET` permission at this point anyway).
- **Real phone, S25 Edge, aeroplane mode:** the four example sentences from the brief are captured from the lock screen (`adb` launch as in card 12) and appear as notes with correct structure (`PLACED`, `PLACED`, `LOAN`, `ERRAND`). Median time from last word to save tones recorded in the log; target under 3 s.
- **Real phone:** force a failure (debug switch makes the engine throw once): the note appears as waiting, and within a minute or so the worker fills it in.
- **Real phone, razr ultra and Flip 7 (when available):** same four sentences by that phone's user; logged.

## Notes and risks

- Loading a large model can take a second or more and hundreds of MB of RAM. Android 17 enforces per-app memory limits; if the process is killed with a "MemoryLimiter" exit reason (`ApplicationExitInfo`), record it and pick a smaller model.
- The repository's `.gitignore` excludes model files (`*.onnx`, `*.ort`, `*.tflite`) because they are too large for git. Add a small script or Gradle task that fetches the chosen model into `app/src/main/assets/` by exact URL and SHA-256, and document it in the README's Building section; the build must fail with a clear message if the model is missing.
- Keep the engine behind the interface; nothing outside `app.stash.speech` may import the library.
- If the chosen model is multilingual, pass the phone's language; do not add a language picker yet.
