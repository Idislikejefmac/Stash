# 20 · Ask by voice or text

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

## Goal

"Find something": the user speaks or types a question and gets the answer on screen and read aloud, only after unlocking.

## Depends on

13, 14, 15, 17, 18, 19.

## Context

`AskEngine.ask(question, locationSnapshot?)` (card 19) returns an `Answer` with `spokenText`, `displayText` and the notes used. The capture components (card 12: cues, recorder with end-of-speech detection; card 13: speech engine) can be reused to hear a question. Reading is gated by the app lock (card 17): **nothing stored may be shown or spoken on a locked phone**. When the ask screen is started while the phone is locked (from a widget on a cover screen, for example), it must first have the system dismiss the keyguard, then pass the app gate, and only then listen.

Answers are spoken with Android's built-in `TextToSpeech` using an on-device voice (no network synthesis: check `Voice.isNetworkConnectionRequired` and pick a voice where it is false).

## Scope

1. `ui/ask/AskActivity` (inside `ProtectedContent`): on open, after the gate, plays the start cue and listens (same detector and limits as capture, but 8 s to start speaking). Shows the recognised question in large text, then the answer in larger text, then the notes it used as tappable rows (opening the note detail from card 18).
2. A text field with a keyboard button for typing instead; a microphone button to ask again; "Speak answers" toggle (default on; automatically off when the ringer is silent unless headphones are connected).
3. Uses a `ListenOnce` helper extracted from `CaptureSession` so the recorder/detector code is shared, not copied. Questions are **not** saved as notes; the audio is discarded immediately.
4. Passes a location snapshot (card 14) to the engine so "what do I need here" works.
5. **Misrouted speech:** if the recognised text parses as a note rather than a question (it matches a `RulesStructurer` pattern with confidence ≥ 0.8 and no question shape), ask "Save that as a note?" with large Yes/No. Likewise, in `CaptureActivity`, if the captured text is clearly a question, save nothing and show "That sounded like a question. Unlock to ask." with a button that opens `AskActivity`.
6. Follow-ups after an answer, as large buttons where relevant: "That's wrong" (opens the note editor), "It's still there" (re-confirm), "Mark returned" (loans), "Done" (errands).
7. Wire "Find something" on the home screen, the launcher shortcut and the 4×2 widget to this activity.
8. `TextToSpeech` is shut down when the activity stops; speaking stops instantly on any touch, on screen off, and on lock.

## Out of scope

A hosted model for free-form answers (21 adds it behind the same screen), conversation memory across questions, answering from the lock screen without unlocking (never).

## Acceptance checks

- **Unit (JVM/Robolectric):** misroute detection both ways on a table of sentences; "speak answers" default logic for ringer modes; no listening or speaking is started before the gate reports unlocked.
- **Real phone, S25 Edge:** after seeding with the brief's four notes by voice: ask the three questions by voice; each answer is correct, shown, and spoken; median time from end of question to start of speech logged (target under 2 s).
- **Real phone, aeroplane mode:** all of the above still works.
- **Real phone, locked:** start `AskActivity` via `adb` → the system unlock prompt appears; nothing is heard or shown until it is passed; cancel → the activity finishes.
- **Real phone, Flip 7 closed / razr closed (when available):** the 4×2 widget's "Find something" → fingerprint → listens and answers on the cover screen, or the log records that the phone must be opened.
- **Real phone:** with TalkBack on, the answer is announced once, not twice (TalkBack and the app's own speech must not overlap).

## Notes and risks

- When TalkBack is active, do not use the app's own `TextToSpeech`; post the answer as a live-region announcement instead.
- Some phones ship only network voices for certain languages; if no on-device voice exists, show the answer without speaking and say so once in settings.
- Speaking an answer aloud in public is a privacy choice: the toggle is on the ask screen itself, not buried in settings.
