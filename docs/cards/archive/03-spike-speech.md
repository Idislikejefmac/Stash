# 03 · Spike: speech to text on the phone

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

Choose the on-device speech engine and model by measuring accuracy (especially names), speed and size on the real phones with the real speakers.

## Depends on

None (can run in parallel with 01).

## Context

Speech must be turned into text on the phone, with no network. Two candidates:

- **A bundled engine: sherpa-onnx** (Apache-2.0, from the k2-fsa project; runs ONNX speech models; has Kotlin/Android examples and prebuilt Android libraries; includes a Silero voice-activity detector). Candidate models: Whisper tiny.en / base.en / small.en, Moonshine tiny / base (English), NVIDIA Parakeet TDT 0.6B. A third-party benchmark puts these between roughly 100 MB and 650 MB on disk and reports large speed differences between engines for the same model; treat those numbers as unverified.
- **Android's own on-device recognizer:** `SpeechRecognizer.isOnDeviceRecognitionAvailable(context)` and `SpeechRecognizer.createOnDeviceSpeechRecognizer(context)`. It may be missing, may need a language pack, and may be Google's or the manufacturer's.

The hard cases are proper nouns and odd words: "Frank", "Costco", "frunk", "110 volt", "$15.99", "Jan 5th".

Throwaway code in `spikes/speechlab/` (applicationId `app.stash.speechlab`).

## Scope

1. An app with a record button that captures 16 kHz mono PCM to a WAV file in app storage, and a list of recordings.
2. For each recording, a "transcribe with…" menu: each sherpa-onnx model that has been pushed to the phone (load models from app external files dir so they can be swapped with `adb push`, no rebuild), and the platform on-device recognizer (for that one, re-speak live, since it will not take a file; note that difference).
3. Show text, seconds taken, and model size. A "run all" button transcribes every recording with every model and writes a CSV.
4. Put the phone in aeroplane mode for the whole test to prove nothing needs the network.
5. Test set, spoken by the phone's real owner at normal distance, once in a quiet room and once with background noise (kitchen, car):
   - "I am putting the 110 volt outlet plug for the Tesla charger in the frunk of the car."
   - "I'm putting my prescription reading glasses in my nightstand drawer."
   - "I loaned my guitar to Frank on January fifth."
   - "I need to remember to buy ground beef when I go to Costco next, it was only fifteen ninety-nine on April third."
   - "Where did I put my guitar?"
   - "Who did I lend the drill to?"
   - "What did I need at Costco?"
   - Ten more of the speaker's own, including two family names and two local shop names.
6. Log per phone, per engine+model in `docs/EXPERIMENT_LOG.md` under "Speech to text on the phone": sentences fully right, names right, median seconds from end of speech to text, size on disk.

## Out of scope

Capture UI, cues, saving notes, cloud speech.

## Acceptance checks

- **Real phone, S25 Edge:** all candidate rows logged. **Real phone, razr ultra and Flip 7:** at least the two best candidates from the S25 run, spoken by that phone's user (accent and voice differ; the parents matter most here).
- **Pass:** some engine+model gets at least 13 of 17 sentences right in meaning, gets "Frank" and "Costco" right most of the time, and returns text within 3 seconds of the end of a 6-second sentence on the slowest phone. Record the winner as a decision at the bottom of the log section.
- **Marginal:** names often wrong but sentences otherwise right → proceed; the correction and "did you mean" features carry it. Say so in the log.
- **Fail:** nothing meets the bar on some phone → tell the owner; options are a larger model on that phone, or revisiting a cloud fallback (must-have 7 allows it only if accuracy requires).
- No unit tests.

## Notes and risks

- Ship only `arm64-v8a` libraries; all three phones are 64-bit ARM.
- Android 17 requires native libraries loaded at run time to be read-only files; libraries packaged normally inside the APK are fine. Models are data, not code, and may live in app storage.
- Check the licence of each **model** as well as the engine (Whisper is MIT; Parakeet is CC-BY-4.0; Moonshine is MIT as of last check) and write it in the log.
- If the platform recognizer wins on accuracy, note whether it worked with the screen locked and from the capture activity shown over the lock screen; a recognizer that needs an unlocked phone is disqualified.
