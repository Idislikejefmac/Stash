# 12 · Capture: one screen that listens, stops by itself, and saves

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

The capture experience, end to end with a stand-in speech engine: launch (also over the lock screen), start tone, record, stop when the user stops talking, save, save tone, brief confirmation with Undo, close.

## Depends on

01 (its results say which launch paths work), 08, 11.

## Context

Capture must work without looking at the phone. The contract with the user is two sounds: **one rising tone = "I'm listening"**, **two quick tones = "your words are stored"**. A single low tone means "nothing was saved". The save tones play only after the note is written to the database.

Android rules that shape this (verified in Google's documentation; card 01 checked them on the phones):

- The microphone can be used by an activity the user can see. An activity declared with `android:showWhenLocked="true"` and `android:turnScreenOn="true"` appears over the lock screen without unlocking. It must **not** dismiss the keyguard.
- A foreground service of type `microphone` may be started from a widget tap or a notification action without an activity. Card 15 uses that; this card provides the service-capable core.
- On Android 17, sounds played when the app has no visible activity and no properly started foreground service are silently dropped. Play cues only from the visible activity or from the capture service after `startForeground`.

The speech engine is not chosen yet (spike 03, card 13). This card defines the interface and uses a fake.

```kotlin
// app.stash.speech
interface SpeechToText {
    suspend fun isReady(): Boolean
    suspend fun transcribe(pcm: ShortArray, languageTag: String): Transcript   // must not use the network
}
data class Transcript(val text: String, val confidence: Float?, val engine: String)
```

## Scope

1. **`capture/CaptureSession`** (plain class, testable with fake audio source, fake cues, fake clock): a state machine `Idle → Starting → Listening → Transcribing → Saved | NothingHeard | Failed | Cancelled`.
   - Start: play start cue, then open the microphone (cue first so it is not recorded).
   - Listening: read 16 kHz mono 16-bit frames. End-of-speech by energy-based voice activity (30 ms frames, adaptive noise floor): if no speech within 6 s → `NothingHeard`; after speech, 1.8 s of silence → stop; hard limit 45 s. Constants in one `CaptureConfig`.
   - Transcribing: call `SpeechToText`. Empty text → `NothingHeard`.
   - Save: `NoteRepository.insertCapturedNote(rawText, createdAt, zone, capturedLocked, sttEngine)`, then play the save cue, then start the structuring pipeline (`StructuringPipeline.apply(noteId)`, already built) without waiting for it.
   - If transcription throws or the engine is not ready: write the PCM to an encrypted file (AES-GCM with a Keystore key via `security/`), insert the note with empty text plus a `pending_audio` row, play low tone then save cue. (Retry logic is card 13.)
   - `NothingHeard`/`Failed`: failure cue, nothing stored.
   - Stop (finish now) and Cancel (discard) commands at any time.
2. **`capture/Cues`**: three short sounds generated in code or bundled as small WAV files; played with `AudioAttributes.USAGE_ASSISTANCE_SONIFICATION`; also a short vibration with each (different patterns), so capture works with the ringer muted. A setting can turn sound or vibration off.
3. **`capture/CaptureActivity`**: `showWhenLocked`, `turnScreenOn`, `excludeFromRecents`, `noHistory`, its own task affinity, portrait and landscape, works at small sizes (cover screens). UI: a big pulsing "Listening…" label and level meter, a large **Stop** button, a smaller **Cancel**; then "Saved" with the transcript of *this* note and a large **Undo** button for 8 seconds; then finishes. Undo soft-deletes the note. It never shows any other note and has no navigation into the app. `FLAG_KEEP_SCREEN_ON` while listening.
4. **`capture/CaptureService`**: foreground service, type `microphone`, running the same `CaptureSession` with a minimal notification ("Listening… / Saved"), for launches without an activity. Stops itself when the session ends.
5. `RECORD_AUDIO` permission: if missing, the activity explains in one sentence and asks; when launched over the lock screen without the permission, it shows "Open the app once to allow the microphone" and plays the failure cue.
6. `FakeSpeechToText` (debug builds): returns "test note at <time>" so the flow can be exercised before card 13.
7. The launcher icon opens the app's home (placeholder for now) with a large "Add a note" button that starts `CaptureActivity`.

## Out of scope

The real speech engine and retries (13), location (14), tiles/widgets/notification/assistant entry points (15, 16), spoken read-back (27), the app lock (17).

## Acceptance checks

**Unit (JVM) on `CaptureSession` with fakes:**

- Speech then silence → `Saved`, one note inserted, cue order `start`, `save`; the save cue is requested only after the repository call returned.
- Silence for 6 s → `NothingHeard`, no note, failure cue.
- Continuous speech → stops at 45 s and saves.
- Cancel while listening → no note, no save cue. Stop while listening → saves what was heard.
- Engine throws → note with empty text and a `pending_audio` row; the file on disk is not plain PCM (does not start with the test pattern).
- Voice-activity detector: a synthetic buffer of noise + tone bursts is segmented as expected.

**Real phone, S25 Edge:**

- From the unlocked app: tap, tone, speak, stop talking, two tones within about 2.5 s of the last word, "Saved" shows the fake text.
- Lock the phone, then `adb shell am start -n app.stash/.capture.CaptureActivity`: the screen lights over the lock screen, both tones are heard, the phone is still locked afterwards (pressing home asks for unlock).
- With the ringer on silent/vibrate: vibrations are felt; record in the log whether the tones are audible or muted (and which stream volume governs them).
- With Bluetooth earbuds connected: tones come through the earbuds; note which microphone was used.
- Log results in `docs/EXPERIMENT_LOG.md`.

## Notes and risks

- Request transient audio focus (`AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`) so music pauses while listening and resumes after.
- A pocket launch must fail safe: `NothingHeard` after 6 s and the screen turns off again.
- Do not hold the audio in a file while listening; keep it in memory (45 s of 16 kHz mono is under 1.5 MB).
