# 26a · The recorder: our own microphone capture, when to stop, and the locked-phone check

Read [00-READ-FIRST.md](00-READ-FIRST.md) first. First card of the card 26 split ([index](26-voice-notes-with-audio.md), DECISIONS.md D77, D82). Where this card and 00-READ-FIRST disagree about audio being stored, this card wins: D77 reopened that rule.

**Status: built 2026-11-12, JVM only** (657 unit tests pass, 25 new; no schema change, no new dependency). Nothing was installed on the phone: checks C2 and the other phone checks wait for the owner. See "What was built, and what differs" at the end.

## Goal

The app can record the microphone itself into memory, show that it is hearing something, and decide by plain code when a note has ended. Nothing is saved, encoded or transcribed here, and `CaptureActivity` is not touched. The card also answers open check C2: does our own recorder get real sound when started from the locked phone?

## Depends on

13 (capability switches, `Dbg`). Nothing else.

## Context

Read first: `app/src/debug/java/app/stash/debug/AudioSpikeActivity.kt` (the recorder that worked in card 24: 16 kHz mono 16-bit `AudioRecord`, loudest sample 21575 of 32767 on a real sentence), `capture/CaptureActivity.kt` (the timers and phases this recorder will later sit under), `capture/Cues.kt`, and the sections "Card 24" and "Card 25" of `docs/EXPERIMENT_LOG.md`.

Measured facts this card relies on: our recorder and the phone's recogniser cannot use the microphone at the same time (card 24), so a note is recorded **or** dictated, never both. Raw sound is 1.92 MB a minute, so a three-minute note is under 6 MB in memory.

Design rules:

- **The sound being recorded lives in memory only.** Nothing plain is written to disk, in any build.
- **When in doubt, keep it.** Code may refuse to save only a recording that is plainly empty (rule in item 6). It never throws away sound because it thinks nobody spoke.
- The recorder never trims. Every sample from start to stop is kept; silence at either end stays in.

## Scope

All new code is in a new package `app.stash.audio`.

1. `Pcm` (pure Kotlin): 16 kHz mono 16-bit samples as a `ShortArray` plus `durationMs`. `PcmBuffer`: a growing buffer with a fixed ceiling (`MAX_NOTE_MS`), appended to from the recorder thread, readable as one `Pcm` at the end. No Android imports.
2. `MicRecorder` (Android): wraps `AudioRecord` at 16,000 Hz, mono, 16-bit, source `VOICE_RECOGNITION` (use whatever source `AudioSpikeActivity` used if it differs, and say which). `start(onChunk)`, `stop(): Pcm`, `cancel()`. Reads on its own thread in chunks of 20 ms, never on the main thread. A failure to create or start `AudioRecord` returns a `MicFailure` code; it does not throw into the caller. `stop()` and `cancel()` always release the microphone.
3. `Endpointer` (pure Kotlin, fed the same 20 ms chunks; this is the only "silence detection" in the feature, and it decides **when to stop**, never what to transcribe):
   - Tracks a noise floor (the quietest second seen so far) and marks a chunk as speech when its level is clearly above it. The thresholds are constants in one place.
   - `heardSpeech`: true once at least 300 ms of speech has been seen.
   - Emits **`STOP_PAUSE`** when `heardSpeech` and the last `pauseMs` were all quiet. `pauseMs` comes from a setting (item 5).
   - Emits **`STOP_NOTHING_HEARD`** when 25 seconds pass with no speech at all (the same wait as today's `SILENCE_LIMIT_MS`, so thinking before speaking is still not a failure).
   - Emits **`STOP_LIMIT`** at `MAX_NOTE_MS`, and **`WARN_LIMIT`** 15 seconds before it.
   - Exposes `level` (0 to 1) for a meter.
4. `MicHealth` (pure Kotlin): after the first 1.5 seconds, if **every** sample so far is exactly zero, report `BLOCKED` (Android hands a silenced app zeros). Any non-zero sample means `OK`. This is what lets later cards fall back at run time instead of trusting the lock-screen check alone.
5. Constants and one setting:
   - `MAX_NOTE_MS = 180_000` (three minutes). Today's limit is 60 seconds; the live recogniser stops at pauses anyway, a file engine does not, so longer notes are the point. Card 26e decides whether three minutes is safe for memory.
   - Setting "End a voice note after a pause of": **2.5 seconds** (default), 5 seconds, "Only when I tap Done". Stored in `Settings`; read by the capture screen in card 26g.
6. `RecordingVerdict.of(pcm, heardSpeech, loudest)` (pure): `EMPTY` only when the loudest sample is below `EMPTY_PEAK` (a very low constant, set from the phone check below) **and** the endpointer never saw speech; otherwise `KEEP`. A quiet sentence must come out `KEEP`.
7. Events (numbers only): add `DbgComponent.AUDIO` with codes `RECORDED` (n1 duration ms, n2 loudest sample), `MIC_BLOCKED`, `FAILED` (n1 failure code). Never a sample, never a file name.
8. A debug-only screen `RecorderCheckActivity` in `app/src/debug/` (it may replace `AudioSpikeActivity`'s record button): `showWhenLocked`, launched by `adb shell am start` **and** by a debug-only action on the existing capture notification. Record, a level meter, the endpointer's events as they fire, Stop. At the end it shows and writes to `files/spike26/check.txt`: duration, loudest sample, `MicHealth`, which stop reason fired, and `isKeyguardLocked` at start. It plays the recording back once from memory. It saves no sound.

## Out of scope

Encoding or storing (26b, 26c). Any change to `CaptureActivity`, the cues or the real capture path (26g). Transcription (26d). Removing background noise, gain control, Bluetooth or wired headset microphones (the phone's own microphone only; say in the hand-back what happened if a headset was connected). A speech detector model (Silero VAD is **not** used: it hurt accuracy in card 25 and is not needed to decide when to stop).

## Acceptance checks

**Unit tests (JVM)**

- `PcmBuffer`: appends keep order; the ceiling is honoured and reports that it was hit; duration is right to the millisecond.
- `Endpointer`, with synthetic chunks (quiet noise, a tone standing in for speech): speech then 2.5 s quiet gives `STOP_PAUSE` at the right chunk; speech with 2 s gaps never stops; no speech gives `STOP_NOTHING_HEARD` at 25 s and not before; "Only when I tap Done" never emits `STOP_PAUSE`; steady loud noise from the first chunk (a running tap) does not count as speech for ever and `STOP_LIMIT` still fires; `WARN_LIMIT` fires once.
- `MicHealth`: all zeros for 1.5 s is `BLOCKED`; one non-zero sample is `OK`; nothing is reported before 1.5 s.
- `RecordingVerdict`: digital silence is `EMPTY`; a quiet but real signal is `KEEP`; anything with `heardSpeech` is `KEEP`.

**Real phone (S25 Edge); write each result in `docs/EXPERIMENT_LOG.md` under a new heading "Card 26"**

- Unlocked: record "Syd has the spare key in the frunk". Loudest sample well above zero, `STOP_PAUSE` about 2.5 s after the last word, playback is his voice.
- Pause for four seconds in the middle of a sentence with the setting at 5 seconds: it keeps recording.
- Say nothing for 25 seconds: `STOP_NOTHING_HEARD`, verdict `EMPTY`.
- Speak quietly (as in the corpus's quiet sentence) and once with a tap running: verdict `KEEP` both times. Write down the loudest sample of the quiet run and of a silent room; set `EMPTY_PEAK` well below the first and say what you chose.
- **Open check C2.** Lock the phone. Start the check screen from the notification's debug action, without unlocking. Say a sentence. Record: did the screen open without a PIN; `MicHealth`; the loudest sample; does playback contain speech. Repeat from the Quick Settings tile if the debug build offers one. **Write the answer as "C2: works" or "C2: blocked (zeros / error code N)".** Do not retry a blocked result in other ways; card 26g has the fallback.
- A phone call arriving, or the screen turning off, during a recording: what does `AudioRecord` return? Record what happened; do not fix it here.

## Notes and risks

- The energy rule will sometimes stop late in a noisy place (the limit catches it) and could stop early on a long thinking pause (that is what the 5-second and "only Done" choices are for). It cannot cut a word out of the recording, because nothing is trimmed.
- If the process dies while recording, that recording is lost: it was only in memory. That is the same as the recogniser crashing today, and the limit bounds the loss at three minutes. Writing unfinished sound to disk was rejected: it would have to be plain or have its key on disk beside it.
- Confidence that a visible activity over the lock screen may record: medium to high (the phone's recogniser already works from `CaptureActivity` while locked, but that is Google's process, not ours). That is why C2 is a check and not an assumption.

**Suggested builder: Sonnet.** Pure logic with synthetic tests plus a thin wrapper copied from spike code that already ran on this phone; nothing here stores or can lose a saved note.

## What was built, and what differs from the card (2026-11-12)

Built (under `app/src/main/java/app/stash/` unless noted):
- `domain/Pcm.kt` (pure): `AudioFormatConstants` (16 kHz, 20 ms chunk of 320 samples, `MAX_NOTE_MS` 180 s, warning 15 s before, `NOTHING_HEARD_MS` 25 s), `Pcm` (samples, `durationMs`, `peak()`), `PcmBuffer` (one-second blocks, fixed ceiling, `hitCeiling`, `release()`).
- `domain/Endpointer.kt` (pure): `EndPause` (2.5 s default, 5 s, only Done), `EndEvent` (`WARN_LIMIT`, `STOP_PAUSE`, `STOP_NOTHING_HEARD`, `STOP_LIMIT`), `EndpointTuning` (every threshold), `Endpointer`, `MicHealth`, `NoteVerdict` and `RecordingVerdict`.
- `audio/Recorder.kt`: the `Recorder` interface, `MicFailure` (codes 1 no permission, 2 not initialised, 3 start refused, 4 threw, 5 read error), `RecordingSession` (chunks into level rule, health check, loudest sample and events; recorder and clock injected) and `RecordingResult`. `audio/MicRecorder.kt`: the `AudioRecord` wrapper.
- `Settings.endPause` (a preference; nothing reads it but the debug screen until card 26g). `DbgComponent.AUDIO`; `DbgCode.RECORDED` (n1 duration ms, n2 loudest sample, ms wall time) and `MIC_BLOCKED`; failures are `FAILED` with n1 the failure code and n2 its detail.
- `app/src/debug/.../RecorderCheckActivity.kt`, registered in the debug manifest with `showWhenLocked` and `turnScreenOn`. Record, a level bar, the pause setting (tap to cycle), the events as they fire, Stop, "Play it back once" (from memory), and `files/spike26/check.txt` holding only numbers and names.
- Tests: `RecorderLogicTest` (23) and `RecorderCheckAbsentFromReleaseTest` (2: the screen is not in `src/main`; the recorder code uses no file, network or system-log call).

How it works and the numbers:
- **Level rule.** A chunk's level is its RMS. The noise floor is the quietest one-second average seen so far (the quietest single chunk until a second has passed). Speech is a level at least 3 times the floor and at least 0.006 of full scale. Heard speech is 300 ms of it in total. A pause is the run of non-speech chunks since the last speech chunk. Time is counted in samples, so the tests need no clock and a three-minute note runs in milliseconds.
- **Memory.** 1.92 MB a minute; ceiling 180 s = 5.76 MB held. Ending a note makes one contiguous copy (about 11.5 MB for an instant, then the recorder releases its blocks). The result is held by whoever called `finish()`; the debug screen drops it in `onDestroy`.
- **Source.** `MediaRecorder.AudioSource.MIC`, as `AudioSpikeActivity` used. The card's `VOICE_RECOGNITION` was not tried. No headset was involved (nothing was run).

What differs from the card:
- The pure code is in `domain/` (as the build instruction asked), not `audio/`; the recorder and session are in `audio/`.
- **Noise from the first chunk.** The card's test line "steady loud noise from the first chunk ... STOP_LIMIT still fires" cannot hold as written: noise that is steady from the start becomes the floor, is never speech, and so ends as `STOP_NOTHING_HEARD` at 25 s. The tests say that, and test the card's other reading (a tap that starts after speech: no pause ever, `WARN_LIMIT` then `STOP_LIMIT`). On the phone this means a running tap before anyone speaks may end the note as "nothing heard" if he waits 25 s; the verdict still says `KEEP` if any sound above `EMPTY_PEAK` was recorded.
- `EMPTY_PEAK` is **40** of 32767, provisional, not measured (the card sets it from a phone check that has not been run). It is a constant in `RecordingVerdict`; change it after the silent-room run.
- **No debug action on the capture notification.** That would put debug code in the main source set (a test now forbids it). The screen is started by `adb shell am start` (see the steps); the real notification route is exercised once card 26g exists. No Quick Settings tile exists in the debug build.
- `RecorderCheckActivity` has an `autostart` extra, so recording can begin with no touch on the locked screen.
- The verdict type is `NoteVerdict` (`Verdict` is taken by `NotificationBudget`).
- A read of zero frames more than 50 times in a row, or a negative read, ends the loop with `MicFailure.READ_ERROR` (n2 the code); a call or screen-off during a recording is not handled specially, only reported, as the card says.

### Phone steps for the owner (nothing here has been run)

Prepare: build and install the debug build the usual way (`installDebug`). Open Stash once so the microphone permission is granted (or allow it when the check screen asks). Connect adb (Wi-Fi works).

1. **Unlocked, normal.** `adb shell am start -n app.stash/app.stash.debug.RecorderCheckActivity`. Tap Record. Say "Syd has the spare key in the frunk", then stay quiet. Expect: `STOP_PAUSE` about 2.5 s after the last word, loudest sample well above zero (the spike saw 21575), "Play it back once" plays his voice. Write the line the screen prints.
2. **Long pause.** Tap the setting button until it says 5 seconds. Record, say half a sentence, wait 4 seconds, finish it. It must keep recording. Then change it to "Only when I tap Done": no `STOP_PAUSE` should come however long he waits (stop with the button).
3. **Nothing said.** Record and say nothing for 25 seconds. Expect `STOP_NOTHING_HEARD` and verdict `EMPTY`. Write the loudest sample (this is the silent-room peak).
4. **Quiet sentence, and a tap running.** Speak the corpus's quiet sentence quietly: verdict must be `KEEP`; write its loudest sample. Repeat with a tap running: `KEEP` again. Then set `EMPTY_PEAK` well below the quiet sentence's peak and above the silent room's, and say what was chosen.
5. **C2, the locked phone.** Lock the phone and wait for the screen to go off. From the PC: `adb shell am start -n app.stash/app.stash.debug.RecorderCheckActivity --ez autostart true`. Do not unlock. Say a sentence, then stay quiet (or wait for the note to end by itself). Then read what it shows (`adb pull /data/data/app.stash/files/spike26/check.txt` is not possible without `run-as`; read the screen after unlocking, or `uiautomator dump`, or use `python tools/peek.py screen`). Write down: did the screen show over the lock screen without a PIN; `keyguardLockedAtStart` (must say true); `MicHealth`; the loudest sample; and, after unlocking, does "Play it back once" contain his voice. Answer exactly "C2: works" or "C2: blocked (zeros / error code N)" in `docs/EXPERIMENT_LOG.md` under "Card 26a". Do not retry a blocked result in other ways (card 26g has the fallback).
6. **Interruptions.** Start a recording, then (a) let a phone call ring in, and separately (b) press the power button to turn the screen off. Record what the screen says afterwards (a failure code, zeros, or carried on). Do not fix anything.

The screen opened by `am start` while the phone is locked may need the screen woken first; if it does not appear, wake it with the power button (not unlocking) and repeat.
