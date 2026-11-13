# 26 · Voice notes with audio: index of cards 26a to 26l

Read [00-READ-FIRST.md](00-READ-FIRST.md) first.

**This page is an index, not a card to build.** Card 26 was written on 2026-11-12 as twelve small cards (DECISIONS.md D82), following the card 16 split. The decision behind it is D77 (the owner: voice notes keep their audio until the note or the audio is deleted); the measurements it is designed from are the sections "Card 24", "Card 25" and "Card 25: engine results" of `docs/EXPERIMENT_LOG.md`. **Where 00-READ-FIRST rule 4 says audio "is never stored", these cards win** (D77 reopened that line; the list at the foot of this page says which lines elsewhere still need changing).

**Build status (2026-11-13):** 26a to 26g are built. Checks C1 (J1, one screen-off run so far), C2 (R1: the locked phone records) and C3 (Q1: Opus 12 kbps) passed on the S25 Edge. 26g, the capture screen switch-over, is built and tested on the PC only (953 unit tests); **its phone checks have not been run** (`docs/EXPERIMENT_LOG.md`, "Card 26g"). Next: 26h.

## What it is, in one paragraph

"Add a note" records his voice with the app's own recorder, saves the recording at once as one more encrypted attachment, plays the save tone, and lets him go. A speech model inside Stash then writes the recording out, in the app if it is on screen and otherwise in a background job, and the usual reading of the note follows. Later he can **listen** to any note, **fix the words** while listening, have it **written out again**, and remove the recording while keeping the words. If recording is off, has no model, or cannot get the microphone on a locked phone, the same screen takes the note as it does today, through the phone's own recogniser, with nothing recorded.

## What was measured, and what follows from it

| Measured on the S25 Edge (2026-11-12) | So the design |
| --- | --- |
| The phone's recogniser cannot be given recorded audio, and cannot share the microphone with our recorder | A note is recorded **or** dictated, never both. Keeping the audio means our own recorder and our own engine |
| Every bundled engine was within noise of the phone's recogniser on accuracy; none clearly better | The engine is chosen for what else it does: it writes out a **whole** note (the live recogniser stops at pauses), works from a file, and can be run again. Nobody is promised better words |
| Moonshine base (MIT, 141 MB, sherpa-onnx 2.0.1, int8): about 0.15 s for a 10 s note on the S25, about 0.5 s estimated mid-range; memory 351 MB short notes, 418 to 486 MB on a 28 s note against a 400 MB guide | Moonshine base is the proposed default; long notes are cut at real pauses and re-measured (26e) |
| Moonshine tiny (44 MB) passes every bar and is about one key token less accurate | The named fallback if base cannot be kept inside the memory guide |
| Parakeet 0.6B and Whisper small: too big (D81). ML Kit GenAI: alpha terms forbid production use | Not used |
| Silence trimming (VAD) cost 3 to 6 words in 147 | No VAD. Nothing is trimmed. A plain level rule decides only when to **stop** (26a) and where to **cut** a long note (26e) |
| Word steering worked in no runner tried | Names (Syd, frunk, Cavit) are fixed afterwards by the app's own correction rules and word list (`domain/Corrector`), applied to every writing-out (26c, 26f), and learned when he fixes a word (26h) |
| Opus at 12 kbps in Ogg: 170 KB a minute, no key token lost. AAC at 32 or 16 kbps lost 3 | Opus 12 kbps is the stored format; AAC and uncompressed WAV are fallbacks so a recording is never lost to a codec (26b) |
| Opus encoding takes about 0.43 s per second of sound | The encoder runs **while he speaks**; Stop only finishes the tail, so the save tone is not delayed (26b) |
| A background job with the screen on: started in 59 ms, finished, about 3 times slower than in the foreground | The pass runs in the foreground when it can and as a job otherwise (26f) |

## Decisions every 26x card keeps (D36, D67, D68, D77, D78, D80, D81)

- **Save first.** The recording is encrypted to disk and the capture row written before anything is written out. Nothing about transcription can delay or fail the save. The save tone means the recording is on disk.
- **The recording is the truth for a recorded note.** `capture.raw_text` is the first writing-out, set once and never changed. Every writing-out is its own `transcription` row. `capture.text` changes only through a named action with a `revision`.
- **One store.** A recording is an `AUDIO` attachment in card 16a's encrypted files: own key per file, app-private storage, purge, tombstones, sweep, consistency check, excluded from Android's backup and device transfer. No second store.
- **Audio never leaves the phone and is never sent to a model.** No card here opens a network connection. A source-scan test (26c) keeps recordings away from the network packages.
- **No audio outside the note.** Never in a notification, preview, widget, log, event, diagnostic report, spoken answer, or export. Played only inside `MainActivity` (which sets `FLAG_SECURE`, D80), with no media notification or lock-screen control. The **one** other place a recording may go is the app's own encrypted backup, and only when card 10 is built as 26j section C says.
- **Private follows the capture** (D68). A recording on a Private note is behind the second unlock; there is no second flag.
- **One capability, `VOICE_RECORDING`**, default on, with a do-nothing engine (`NoTranscriber`), an explanation card, and a stated "without it": the quick note through the phone's recogniser. That switch is the hybrid setting. Tier 0 is unchanged: with no key, no network and no model pack, voice notes work as they do today.
- **Kept until deleted.** No automatic age limit. He deletes a recording with its note, alone from the note (26h), or many at once (26j).
- **The model is a pack on the phone, not in the APK and not downloaded** (26d gives the reasons). `ModelLocator` also reads APK assets, so bundling stays a build choice; a download is card 26l, designed and not scheduled.
- **Licences are recorded before use** (D78) and their texts ship in an "Open source notices" row (26d).
- **The S25 Edge is the optimistic case** (D81). Capture must stay instant on a cheap phone; transcription may wait but must finish.

## The cards

| Card | What it builds | Suggested builder |
| --- | --- | --- |
| [26a](26a-recorder.md) | Our own recorder into memory, the level rule that decides when a note has ended, the three-minute limit, the blocked-microphone test. **Answers check C2** (locked phone) | Sonnet |
| [26b](26b-audio-format.md) | Opus in Ogg encoded while he speaks, the AAC and WAV fallbacks, decoding back to samples. **Answers check C3** (by ear) | Opus |
| [26c](26c-recording-store.md) | The migration (`transcription` table, `capture.transcribe_state`), saving a recording as an `AUDIO` attachment with its capture, applying a writing-out, removing a recording alone, purge and consistency | Opus |
| [26d](26d-speech-engine.md) | The `Transcriber` seam and `NoTranscriber`, the sherpa-onnx library and Moonshine adapter, model packs and their import, the Open source notices row | Opus |
| [26e](26e-long-notes-and-memory.md) | Cutting long notes at real pauses, and the measurement that decides base or tiny as the default | Opus |
| [26f](26f-transcription-job.md) | The transcription pass, its states (`PENDING`, `DONE`, `RETRY`, `GAVE_UP`), retries, kill detection, foreground and background starts. **Answers check C1** (screen off) | Opus |
| [26g](26g-capture-with-recording.md) | The capture screen switch-over: record route, quick-note route, the one switch, Done and Cancel, the fallbacks | Opus |
| [26h](26h-listen-and-fix.md) | On a note: Listen, Looks wrong (fix while listening), Write it out again, earlier versions, Remove the recording; Private handling | Sonnet |
| [26i](26i-doubtful-words.md) ◆ | A quiet "Check the words" mark from cheap signals, no model | Sonnet |
| [26j](26j-recordings-storage-and-delete.md) | Settings: space used, what is waiting, deleting many recordings; what card 10 must do | Opus |
| [26k](26k-recording-explanations.md) | The explanation card and "What leaves my phone" wording (needs card 23) | Sonnet |
| [26l](26l-model-delivery.md) ◆ | **Later, unscheduled:** getting the model onto other people's phones (in the APK, a Play asset pack, or a download) | Opus |

## Order and dependencies

Build in this order. "Before the checks?" says whether the card can be built before the three open checks are answered.

| Order | Card | Depends on | Before the checks? | Why here |
| --- | --- | --- | --- | --- |
| 1 | 26a | 13 | **Yes. It answers C2** | Pure logic and a thin recorder; everything else consumes its samples, and the lock-screen answer is needed early |
| 2 | 26b | 26a | **Yes. It answers C3** | The format must exist before anything is stored, and the save-tone delay depends on it |
| 3 | 26c | 01, 13, **16a**, 26b | **Yes** | The migration and the save and delete paths, built and tested alone, with no microphone and no engine |
| 4 | 26d | 13, 26a | **Yes** | The native library and model, behind a seam, testable against the spike's own results |
| 5 | 26e | 26d | **Yes** | Decides the default model before the job that runs it is tuned |
| 6 | 26f | 13, 26b, 26c, 26d, 26e | **Yes to build. It answers C1**, and two constants are set from the answer | The first card where a real recording is written out end to end, from a debug action |
| 7 | 26g | 26a to 26f | **No: wait for C1, C2 and C3** in the experiment log | It changes how the owner's real notes are captured. Until it is built his capture is exactly as today |
| 8 | 26h | 26c, 26f, 26g; 16d for the pattern | Yes (with seeded notes), but ordered after 26g | Without it a recording can be kept and not heard |
| 9 | 26j | 26c, 26f; best after 26h | Yes | The owner can see and control what is kept |
| any time after 6 | 26i ◆ | 26c, 26f (26h for the mark) | Yes | Optional; a fortnight's use decides whether it stays |
| when 23 is done | 26k | **23**, 26g | Waits for card 23 | Until then the switch shows its one-line "without it" text |
| not scheduled | 26l ◆ | 26d; 13 and 23 for a download; a new decision | Waits | Matters only when someone without a PC installs Stash |

Where this sits among the other cards: 26c needs 16a, which is being built now; nothing else in the 16x block is needed except 16d's pattern for 26h. The hardening block (10, 02, 14) is not needed. **What building before hardening costs**, as for pictures: recording keys sit in an unencrypted database on a debug build until card 14 (D77 accepted this); there is no backup of recordings until card 10; until card 02 anyone holding the unlocked phone can open Stash and listen to recordings that are not Private.

## Open checks and what each decides

All three need the owner on the phone. Each is run as part of the card named, with the steps in that card, and written in `docs/EXPERIMENT_LOG.md` under "Card 26".

| Check | Run in | If the answer is… | Then |
| --- | --- | --- | --- |
| **C1.** Does a WorkManager job write out a recording with the **screen off**? | 26f (steps also in the log, "Background job and battery") | **J1** starts within about a minute and finishes | The job is the normal route; nothing to tell the owner |
| | | **J2** finishes, but after minutes or hours | The work is done in the foreground right after saving (while the capture screen is still up) and whenever Stash opens; the job catches the rest late. Notes say "Not written out yet" |
| | | **J3** killed, or never runs | Foreground as J2; the job runs only while charging; tiny is used for background work (revisit 26e); Settings says when recordings are written out |
| **C2.** Does our recorder get real sound when started from the **locked** phone? | 26a | **R1** works | Record on the lock screen like anywhere else |
| | | **R2** blocked (zeros or an error) | While locked the note is a quick note through the phone's recogniser, with nothing recorded, and the screen says so, with "Unlock to keep a recording". A setting offers "Ask me to unlock first" instead. Capture is never refused |
| | | either way | A blocked microphone is also detected at run time and the screen switches to the quick note on the spot (26g) |
| **C3.** Can the owner follow **12 kbps Opus by ear**? | 26b (or today on the PC: `docs/spike25/results/enc/opus12/`) | **Q1** yes | Opus 12 kbps, about 170 KB a minute |
| | | **Q2** no, but 24 kbps is fine | Opus 24 kbps, about 260 KB a minute (it also lost no key token) |
| | | **Q3** neither | Measure Opus 32 kbps the same way first. Not AAC: no smaller than Opus 24 and worse for the engine |

Two further questions are settled by measurement inside a card, without the owner: **can `MediaMuxer` write to an in-memory file** (26b: E1 yes; E2 write the Ogg pages ourselves; E3 a plain temporary file, only with the owner's yes), and **does Moonshine base stay inside the memory guide once long notes are cut** (26e: M1 base is the default; M2 base up to a length, tiny beyond; M3 tiny is the default).

## Platform facts the cards rely on, and how each is checked

| Fact | Confidence | Checked by |
| --- | --- | --- |
| `AudioRecord` at 16 kHz mono works from a visible activity | high (card 24, measured) | 26a |
| It also works from that activity over the lock screen | **medium, untried** | 26a, check C2 |
| The platform Opus encoder accepts 16 kHz and `MediaMuxer` writes Ogg | high (card 25, measured, 75 files) | 26b |
| `MediaMuxer` can write to a file made with `Os.memfd_create` | **medium, untried** | 26b, outcomes E1 to E3 |
| The encoder keeps up with speech in real time | high (0.43 of real time in a batch loop) | 26b |
| `MediaExtractor` and `MediaPlayer` read Ogg Opus from a `MediaDataSource` | medium to high | 26b, 26h (fallback: decode and `AudioTrack`) |
| sherpa-onnx 2.0.1 with the 2026-02-27 Moonshine packaging runs on this phone; its libraries are 16 KB aligned | high (card 25, measured) | 26d: texts must equal the spike's |
| Cutting at real pauses keeps accuracy and lowers peak memory | **unknown**; the plain VAD cut cost accuracy | 26e, outcomes M1 to M3 |
| An expedited WorkManager job needs no notification on Android 12 and later | medium | 26f: read the live page |
| Samsung lets the job run with the screen off | **unknown** | 26f, check C1 |

## Libraries and model files (for `docs/LICENSES.md`)

Nothing here is new to the register; all were verified on 2026-11-12. What changes is that they move from "candidates" to **shipped**, with obligations:

| Item | Card | Licence | What must ship |
| --- | --- | --- | --- |
| sherpa-onnx 2.0.1 AAR (arm64 libraries only) | 26d | Apache-2.0 | The licence text; any NOTICE or third-party licence files for code built into its libraries (**not yet read: the AAR and the repository must be looked through**) |
| ONNX Runtime, inside that AAR | 26d | MIT, "Copyright (c) Microsoft Corporation" | Licence text and copyright line; its `ThirdPartyNotices.txt` (**not yet read**) |
| Moonshine English models, base and tiny, `quantized-2026-02-27` | 26d | MIT, "Copyright (c) 2025 Useful Sensors, Inc. (dba Moonshine AI)" | The package's `LICENSE` file, in the app and in each pack. Never a non-English Moonshine file |
| Opus encoder and decoder, Ogg muxer, AAC | 26b | The Android platform's own; nothing is bundled | Nothing |
| Silero VAD, Parakeet, Whisper, ML Kit GenAI Speech | none | n/a | **Not shipped.** The spike's debug-only lines are removed in 26d |

## What other documents still say, and the lines to change

Changed with these cards (2026-11-12): `docs/PRIVACY.md`, `docs/EXPLANATION_CARDS.md`, `docs/DESIGN.md` §6 and §10, `README.md`, `docs/DECISIONS.md` (D82). **Not changed, and needing these edits** (cards are not edited by another card's writer):

| File and line | Says now | Should say |
| --- | --- | --- |
| `cards/00-READ-FIRST.md` line 36 | "Audio never leaves the phone and is never stored." | "Audio never leaves the phone and is never sent to a model. It is stored only as a voice note's own encrypted recording (cards 26a to 26l, D77)." |
| `cards/00-READ-FIRST.md` line 62 | `speech/` "SpeechEngine and the Android on-device recogniser" | add: `Transcriber`, model packs, `speech/sherpa/` (26d); and a new line `audio/` recorder, format, playback (26a, 26b, 26h) |
| `cards/00-READ-FIRST.md` line 107 | Attachment: "A picture linked to exactly one capture" | "A picture, or a voice note's recording, linked to exactly one capture" |
| `cards/00-READ-FIRST.md` line 126 and the feature-table list under it (line 150) | `capture` columns; `attachment` "one row per picture" | add `transcribe_state`?, `transcribe_attempts`; add a row `transcription` / 26c / "every writing-out of a recording"; `attachment` "one row per picture or recording" |
| `cards/02-app-gate.md` line 28 (item 3a) | "A capture, record, picture or scan marked sensitive" | add "or recording"; `PrivateContent` also wraps 26h's Listen control |
| `cards/02-app-gate.md` line 43 | the Robolectric test seeds "one private note with a picture" | "with a picture and a recording … no thumbnail and no Listen control" |
| `cards/09-attention-and-repair.md` line 30 | later cards add reasons `ADD_PICTURE`, `WATCH_PROPOSED` | add `CHECK_WORDS` (26i, optional) |
| `cards/09-attention-and-repair.md` line 34 | "captures with `extract_state = GAVE_UP`" | add "or `transcribe_state = GAVE_UP`" (a recording that could not be written out) |
| `cards/09-attention-and-repair.md` line 61 (item 5) | the capture page shows `raw_text` and `text` | add: the Listen control, the earlier versions (`transcription` rows) and 26h's panel; "Correct a word" is offered while listening |
| `cards/10-backup-export-delete.md` line 33 (item 1) | the backed-up table list | add `transcription` |
| `cards/10-backup-export-delete.md` line 39 (item 4) | "one entry per picture, `att/<attachment id>`" | "one entry per attachment (pictures and recordings)", and the "Include recordings in backups" choice (26j items 11 to 13) |
| `cards/10-backup-export-delete.md` line 48 (item 10) | Markdown export of words | add "(recording kept, 0:12)"; no export contains sound |
| `cards/10-backup-export-delete.md` lines 54 and 55 (items 13, 14) | deletes "picture files" | "picture and recording files, and the speech model pack" |
| `cards/10-backup-export-delete.md` line 75 | the restore check lists notes, tasks, readings, the word list | add "and a recording plays after the restore" |
| `cards/13-capture-pipeline.md` line 28 (item 1) | `Inbound.Capture` carries "raw text, corrected text" | add: both are empty for a recording until it is written out (26c) |
| `cards/13-capture-pipeline.md` line 41 (item 5) | when a capture goes back to `PENDING` | add: "and when a recording's words arrive (26c, 26f)" |
| `cards/13-capture-pipeline.md` lines 51 and 57 | the capability table and "later cards add" list | `VOICE_CAPTURE` without-it stays; add `VOICE_RECORDING` (26g) to the later list |
| `cards/14-encryption-at-rest.md` line 37 (item 6) | "an 'Open-source licences' row containing SQLCipher's notice" | "adds SQLCipher's notice to the Open source notices list (26d made the row, if built first)" |
| `cards/14-encryption-at-rest.md` line 39 (item 8) | excludes "the picture files" from transfer | "`files/att/` (pictures and recordings) and `files/models/`" |
| `cards/14-encryption-at-rest.md` lines 44 and 57 (item 10 and the release check) | debug screens absent from release | add: no ML Kit GenAI and no spike code in the release classpath; the release APK's arm64 libraries pass `zipalign -c -P 16` |
| `cards/23-explanation-cards.md` line 22 (item 2) | copies one `CardText` per capability from EXPLANATION_CARDS.md | nothing false; note that "Voice notes" changed and "Keep the recording" is new (26k) |
| `cards/16a-attachment-store.md` line 76 | `remove` writes a `PICTURE_REMOVED` revision | "`PICTURE_REMOVED`, or `AUDIO_REMOVED` for a recording (26c)" |
| `cards/16a-attachment-store.md` line 89 | playback through Tink's seekable channel | not needed: a recording is at most about half a megabyte and is decrypted into memory (26h) |
| `cards/16c-share-and-annotate.md` line 39 | the Share screen's microphone uses `SpeechEngine` | still true; add "no recording is kept for words spoken on this screen" |
| `cards/16d-pictures-on-a-note.md` line 48 | Settings: "Pictures: 42, using 11 MB" | 26j adds the recordings line beside it |
| `cards/20-diagnostics.md` lines 35 and 38 | "picture storage sizes"; never in a report: pictures | add recordings: sizes may be counted; no recording, and no `transcription.text`, is ever in a report |
| `docs/PROVIDERS.md` line 11 | Tier 0: "The phone's own speech recogniser, then code" | "The phone's own speech recogniser, or the speech model inside Stash, then code" |
| `docs/PROVIDERS.md` line 136 (the Capture table) | no row for recordings | add "Keep the recording; listen; write it out again · ● ● ● · Microphone, a speech model pack · the quick note, nothing recorded" |
| `docs/STORAGE.md` line 42 (rule 7) and line 52 | delete removes "its picture files"; "Pictures are files, not rows" | "its attachment files (pictures and recordings)"; add one sentence that recordings use the same files and keys |
| `docs/DESIGN.md` line 740 (§16 table) | "Voice capture · Microphone · Typed notes" | add a row "Keep the recording · Microphone, a speech model pack · the quick note" |
| `docs/RISKS.md` R12 (line 33) | speech may cut off the notes this revision adds | partly answered by card 25: a file engine writes out the whole note; numbers and names still need the correction rules |
| `README.md` line 129 ("What is in the prototype") | the prototype's "Audio is never saved." | **changed with 26g (2026-11-13)** |
| `app/.../speech/SpeechEngine.kt` line 17 (code comment) | "audio is never stored" | **changed with 26g (2026-11-13)**: the comment says which route stores what |

## Open questions for the owner

1. **The model pack instead of a bundled model or a download.** 26d keeps the model out of the APK (installs stay small and quick; nothing is fetched) and asks you to add it once. Would you rather have it inside the APK (about 170 MB more per install, nothing to add)? It is one build line either way.
2. **Default on.** The switch "Keep a recording of each voice note" defaults to on (your decision D77), and takes effect when the model pack is added. The cost you will notice: **no words appear while you speak**. Is that acceptable, or should the default be the quick note with recording as the choice?
3. **The three-minute limit** (today 60 seconds) and the **2.5-second pause** that ends a note. Right, or longer?
4. **Backups** (when card 10 is built): recordings included by default, with a switch to leave them out. Agreed? They make each backup larger (about 100 MB a year of notes, kept five times).
5. **Is 400 MB a hard line or a guide?** It decides between Moonshine base and tiny if 26e's measurement is close.
6. **If the locked phone cannot record (C2 blocked):** a quick note with no recording (proposed), or ask you to unlock first?
7. **Words spoken on the Share screen (16c) and questions spoken on the Ask tab** stay with the phone's recogniser and are not recorded. Should a spoken addition to a shared picture be recorded too? (Not designed.)
8. **Saving a recording out of Stash** (to send to someone, or as an open-format export): not designed, because the app today contains nothing that takes sound out. Wanted?
9. **s13 and s10 of the corpus:** the log asks you to listen and confirm what was actually said ("I put the password, I put the passport…"; "…and I think I had tuna…"). It changes the accuracy figures by 4 to 6 words.
10. **A cheaper or older phone** to run 26e's measurement on, if you have one. It is worth more than the "times three" estimate.
