# 24 · Spike: keep the audio of a voice note and still transcribe it

Read [00-READ-FIRST.md](00-READ-FIRST.md) first. Decision D77 (2026-11-12) reopened "no audio is stored". This spike finds out how to do it on this phone. It builds nothing permanent: a debug-only screen, no schema change, nothing in the release path.

## Goal

Answer, with measurements on the S25 Edge, in under half a day:

1. Can the app record the microphone itself (`AudioRecord`, 16 kHz mono PCM) and feed that same audio to Android's on-device recogniser using `RecognizerIntent.EXTRA_AUDIO_SOURCE` (a `ParcelFileDescriptor` pipe; also `EXTRA_AUDIO_SOURCE_CHANNEL_COUNT`, `EXTRA_AUDIO_SOURCE_ENCODING`, `EXTRA_AUDIO_SOURCE_SAMPLING_RATE`), and does the text match what the normal path gives?
2. Does it still work from the locked phone via the notification action (microphone only from a visible activity)?
3. Can a saved recording be fed back to the recogniser later (re-transcribe from a file)?
4. Size of the stored audio per minute with AAC (MediaCodec, about 32 kbps) vs raw PCM.
5. If 1 or 3 fails: does the recogniser behave differently with a file, and what would a bundled engine cost (APK size, seconds per note)? Only measure; do not build.

## Scope

- `app/src/debug/java/app/stash/debug/AudioSpikeActivity` (debug only, launched from `adb shell am start`): Record / Stop; transcribes through the pipe path; shows the text, the engine name and the elapsed times; plays the audio back; "Transcribe saved audio again" feeds the stored file back.
- Write the audio to `files/spike/` as PCM and as AAC (M4A). No encryption in the spike. Delete the folder at the end.
- No change to `CaptureActivity`, the schema or any release code.

## Acceptance

Record each in `docs/EXPERIMENT_LOG.md` (new section "Card 24"), with real results only:

- Pipe path text vs normal path text for the same five sentences (pool sentence, price sentence, "Syd has the spare key in the frunk", a loan, a long ramble of 30 s).
- Locked-phone run: opened / asked for unlock; audio captured or silent.
- Re-transcription from file: worked / not; any difference.
- Bytes per minute: PCM and AAC.
- Verdict: **A** record-and-feed works (build card 25: voice notes with audio); **B** only record works, transcribe separately from the file; **C** neither: bundled engine spike.

## Out of scope

Encryption of the audio (follows card 16's `AttachmentStore` design, card 14), playback UI in Notes, schema, backup, retention, speaker or noise processing.
