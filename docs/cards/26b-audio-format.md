# 26b · The stored format: Opus while recording, the fallbacks, and reading it back

Read [00-READ-FIRST.md](00-READ-FIRST.md) first. Part of the card 26 split ([index](26-voice-notes-with-audio.md), D77, D82).

**Status: built 2026-11-12, JVM only** (686 unit tests pass, 29 new; no schema change, no new dependency). Nothing was installed on the phone: E1/E2, the timings, and check C3 wait for the owner. **Opus is written by the app's own Ogg writer (E2) by default**, because the phone's Ogg muxer puts one packet on each page, which doubled the size measured in card 25. See "What was built, and what differs" at the end.

## Goal

A recording becomes a small, standard audio file **in memory**, finished within a fraction of a second of Stop, and can be turned back into samples for the speech engine and for playback. If the small format fails for any reason the recording is still kept, larger. The card also answers open check C3: can the owner understand 12 kbps Opus by ear?

## Depends on

26a (`Pcm`, the recorder's 20 ms chunks).

## Context

Read first: `app/src/debug/spike25/java/app/stash/debug/Spike25Codec.kt` (the encoder and decoder that ran on the phone for card 25) and "Card 25: engine results", "Audio size (leg C)" in `docs/EXPERIMENT_LOG.md`.

Measured on the S25 Edge (2026-11-12):

- Opus in Ogg through `MediaCodec` (`c2.android.opus.encoder`, software, accepts 16 kHz) and `MediaMuxer` (`MUXER_OUTPUT_OGG`) worked on all 75 files. **Opus at 12 kbps measured 170 KB a minute and lost no key token** against the uncompressed run (43 of 49 both). Opus at 24 kbps: 259 KB a minute, none lost. AAC at 32 and 16 kbps each lost 3 key tokens: AAC is worse for the engine.
- Opus decodes at 48 kHz and must be brought down to 16 kHz for the engine.
- **Encoding is slow: about 0.43 seconds of work per second of sound** (3.6 s for an 8.4 s note, 11.9 s for the 27.7 s one, `docs/spike25/results/enc/report.tsv`). Encoding after Stop would delay the save tone by seconds, which rule 3 forbids. So the encoder runs **while he speaks**, and Stop only has to finish the last fraction of a second.

Design rules:

- The words are always written out **from the stored file**, never from the samples still in memory. What he hears later is then exactly what the engine heard, and "write it out again" gives the same input.
- Nothing plain is written to disk (16a). The encoded note is small (a three-minute note is about 510 KB), so it is held in memory and handed to the attachment store as a stream.
- Every stored recording says what it is (`mime`), so files in different formats can sit side by side for ever.

## Scope

In `app.stash.audio`.

1. `AudioFormat` (pure): `OPUS_12` (`audio/ogg`, 12,000 bit/s), `OPUS_24` (`audio/ogg`, 24,000), `AAC_32` (`audio/mp4`, 32,000), `WAV` (`audio/wav`, uncompressed). One constant, `DEFAULT_FORMAT`, set by check C3 (start with `OPUS_12`). A setting "Recording quality": **Small** (about 170 KB a minute) and **Clearer** (about 260 KB a minute), applied to notes recorded after the change.
2. `StreamingEncoder`: `start(format)`, `feed(chunk)` (called for every 20 ms chunk from 26a's recorder, on the recorder's thread or its own, never the main thread), `finish(): EncodedAudio(bytes, mime, durationMs, format)`, `abort()`. It configures the codec at 16 kHz mono and adds the muxer track from the encoder's own output format (after `INFO_OUTPUT_FORMAT_CHANGED`), as the spike did.
3. **Where the muxer writes.** `MediaMuxer` needs a seekable file descriptor. Use an anonymous in-memory file: `android.system.Os.memfd_create` (API 30; this app's `minSdk` is 35), read the bytes back at `finish`, close it. This has **not** been tried on the phone. Named outcomes, decided by the first phone check:
   - **E1:** the muxer accepts the memory file. Done.
   - **E2:** it refuses. Write the Ogg pages yourself: a small pure-Kotlin `OggOpusWriter` (page header, lacing values, the Ogg CRC-32, the `OpusHead` page from the codec's `csd-0`, an `OpusTags` page, granule positions at 48 kHz), fed the encoder's packets. JVM tests check the CRC against known vectors and the page structure; the phone check is that `MediaExtractor` opens the result and it decodes to the same duration.
   - **E3** (only with the owner's yes, because it breaks 16a's "no plain temporary file" rule): a file in `noBackupFilesDir/rec/`, deleted in a `finally`, swept at every app start. Do not build E3 without asking.
4. **Fallback order at run time**, so a recording is never lost to a codec:
   - No Opus encoder in `MediaCodecList`, or it fails to start: use `AAC_32` in MPEG-4 (the same memory-file route). The phone check below proves this path by forcing it.
   - Any encoder error during the note, or `finish` taking longer than 1.5 seconds: `abort()` and return the samples as `WAV` (a 44-byte header and the samples; no codec involved, so it cannot fail). The recorder still has every sample, so nothing is lost; the file is about eleven times larger.
   - `Dbg.event(AUDIO, ENCODED, n1 = bytes, n2 = format ordinal, ms = finish time)`; a fallback is logged with code `DOWNGRADED`.
5. `Compactor.compact(wav): EncodedAudio?`: encodes a stored `WAV` recording to the default format off the main thread (not streaming; slow is fine). Card 26c calls it later to replace a fallback file. Returns null if the encoder still fails.
6. `AudioDecoder.decode(bytes, mime): Pcm` at 16 kHz mono: `MediaExtractor` over an in-memory `MediaDataSource` (never a file path), `MediaCodec` decoder, then `Resampler` (pure Kotlin: 48 kHz to 16 kHz by a low-pass filter and taking every third sample; pass-through for 16 kHz input; WAV is read directly). Off the main thread.
7. A debug-only screen (extend 26a's `RecorderCheckActivity`): record a sentence, then show for `OPUS_12`, `OPUS_24`, forced `AAC_32` and forced `WAV`: bytes, bytes per minute, milliseconds between Stop and `finish`, decode time, and a Play button for each (decoded, from memory). Results also go to `files/spike26/format.txt`.

## Out of scope

Storing the bytes (26c). The real capture screen (26g). The playback control on a note (26h). Other sample rates, stereo, noise removal. A bundled Opus library: the platform's encoder is used, so nothing is added to `docs/LICENSES.md` by this card.

## Acceptance checks

**Unit tests (JVM)**

- `Resampler`: a 1 kHz tone at 48 kHz comes out as a 1 kHz tone at 16 kHz of the right length; a 10 kHz tone is strongly reduced; 16 kHz input is returned unchanged.
- WAV: header fields and round-trip are exact.
- `OggOpusWriter` (only if E2 is built): CRC vectors; a page never exceeds 255 segments; the last page carries the end-of-stream flag.
- The fallback rule, with a fake encoder: an encoder that throws on the 40th chunk yields a `WAV` holding every sample fed; one whose `finish` hangs is abandoned after 1.5 s with the same result; no Opus encoder present chooses `AAC_32`.

**Real phone (S25 Edge); write each result in the experiment log, "Card 26"**

- Which of E1, E2 it is, with the error text if E1 failed.
- A 10-second and a 3-minute note in `OPUS_12`: bytes per minute (expect about 170 KB), and **milliseconds from Stop to `finish`** (bar: under 300 ms for 10 s, under 1 s for 3 minutes). Does the encoder keep up while recording (no growing queue)? Battery and warmth are not expected to matter; say if the phone got warm.
- Decode each back: duration equal to the original within 50 ms.
- Forced `AAC_32` and forced `WAV` both produce a playable result.
- **Open check C3.** The owner listens, on the phone's speaker and once on the earpiece or headphones, to his own sentence in `OPUS_12` and `OPUS_24`, including one quiet sentence and one with hard names. (He can also play `docs/spike25/results/enc/opus12/s07.ogg` and `s13.ogg` on the PC today.) Write his answer as one of: **Q1** "12 is fine" (keep `OPUS_12`); **Q2** "12 is hard to follow, 24 is fine" (set `DEFAULT_FORMAT = OPUS_24`, about 260 KB a minute); **Q3** "neither" (measure Opus at 32 kbps the same way before choosing; do not switch to AAC, which is no smaller than Opus 24 and worse for the engine).

## Notes and risks

- Confidence: `MediaMuxer` with a memory file, **medium, untried**; that is why E2 is designed. Opus encode keeping up in real time, high (0.43 of real time measured in a batch loop). `MediaExtractor` reading Ogg from a `MediaDataSource`, medium to high.
- The Opus encoder ignores its nominal bit rate at the low end (12 kbps measured 170 KB a minute, not 90). Sizes shown to the owner must be the measured ones.
- The `WAV` fallback stores about 1.9 MB a minute until 26c's compaction replaces it. It is rare by design; log every use.
- If a later phone has no Opus encoder, its recordings are AAC and transcribe slightly worse (3 of 49 key tokens in card 25). The mime column records which notes those are.

**Suggested builder: Opus.** Codec and muxer work with an untried memory-file route, and a mistake in the fallback order loses a recording.

## What was built, and what differs from the card (2026-11-12)

Built (under `app/src/main/java/app/stash/` unless noted):
- `domain/AudioFormat.kt` (pure): `AudioFormat` (`OPUS_12`, `OPUS_24`, `AAC_32`, `WAV`, each with `mime`, codec mime and bit rate), `AudioFormats.DEFAULT_FORMAT = OPUS_12` (the one constant check C3 changes), the fallback order, `FINISH_LIMIT_MS = 1500`, `RecordingQuality` (`SMALL`, `CLEARER`; its default follows `DEFAULT_FORMAT`), `OpusContainer` (`OWN_WRITER` = E2, the default; `MUXER` = E1), `EncodedAudio`.
- `domain/Wav.kt` (pure): writes the 44-byte header. The reader skips other chunks, keeps whatever a cut-off file holds, and downmixes and resamples.
- `domain/Resampler.kt` (pure): a windowed-sinc low-pass filter (Blackman window, cut-off 7 kHz, 133 taps at 48 kHz, gain 1 at 0 Hz, centred so there is no delay), evaluated at every third sample. 16 kHz input is returned as the same array. Other multiples of 16 kHz work; any other rate is refused. Output is held at ±32767 and never wraps. `downmix` averages the channels.
- `domain/Ogg.kt` (pure):
  - `OggCrc`.
  - `OggStreamWriter`: lacing, at most 255 segments a page. A packet too big for one page continues on the next with the continued flag, and a page on which no packet ends gets granule -1. The open page is kept until the next packet arrives, so the last page always carries end-of-stream.
  - `Ogg.readPages`: checks the capture pattern, version, CRC and cut-off pages. `Ogg.packets` joins the packets.
- `domain/OggOpus.kt` (pure):
  - `OpusHeaders`: `OpusHead`, `OpusTags` and their readers.
  - `OpusToc`: 48 kHz samples per packet, from the TOC byte.
  - `OpusCodecConfig`: the encoder's delay from its codec data, plain or in Android's `AOPUSDLY` marker form.
  - `OggOpusWriter` (50 packets a page) and `OggOpusInfo` (reads a file back: header, vendor, last granule, playable length).
- `audio/StreamingEncoder.kt` (pure Kotlin, no Android import): the `AudioEncoder` and `EncoderFactory` seams and `StreamingEncoder` (`start(format)`, `feed(chunk, len)`, `finish()`, `abort()`).
  - It keeps its own copy of every sample and hands chunks to the codec on its own thread (`stash-encoder`).
  - It returns WAV on any codec error, or when `finish` takes longer than 1.5 s.
  - For the debug screen it also reports `maxQueued`, `finishMs` and what went wrong.
- `audio/Compactor.kt` (pure Kotlin): `compact(wav, format = DEFAULT_FORMAT): EncodedAudio?`.
- `audio/MediaCodecEncoder.kt` (Android): `PlatformEncoders` (looks in `MediaCodecList`) and `MediaCodecEncoder`.
  - 16 kHz mono, configured as the spike did. The muxer track is added from the encoder's own output format after `INFO_OUTPUT_FORMAT_CHANGED`.
  - Two sinks. One is the app's own Ogg writer (E2). The other is `MediaMuxer` writing into `Os.memfd_create`, read back with `Os.pread` and then closed; it is E1 for Opus and is always used for AAC in MPEG-4.
  - If the drain sees no end of stream for 5 s it throws, so the compactor can never hang.
- `audio/AudioDecoder.kt` (Android): the `Decoder` seam and `AudioDecoder`.
  - WAV is read directly.
  - Anything else goes through `MediaExtractor` over an in-memory `MediaDataSource`, then the platform decoder, `downmix` and `Resampler`.
  - For an Ogg file this app wrote (vendor `Stash`), the decoded sound is cut to the length its last granule states. It never adds sound.
- `Settings.recordingQuality`: a preference, like 26a's `endPause`. Nothing reads it until card 26g, and the Settings screen has no row for it yet.
- Events: `DbgCode.ENCODED` (n1 bytes, n2 format ordinal, ms finish time). A fallback is `DOWNGRADED` (n1 from, n2 to). A decode failure is `FAILED` with n1 = 100 + format ordinal; a compaction failure, 200 + format ordinal.
- Debug only: `app/src/debug/.../AudioFormatCheckActivity.kt`, registered in the debug manifest (steps below).
- Tests: `AudioCodecLogicTest` (18) and `StreamingEncoderTest` (11). Every unit check in the card has a test. The Ogg writer's tests exist because E2 was built.

**The muxer decision: E2, the app's own Ogg writer, is the default for Opus. E1 (MediaMuxer into a memory file) is also built, and AAC still uses it.**
- **One packet per page.** Card 25's own Ogg files (`docs/spike25/results/enc/opus12/*.ogg`, written by the phone's MediaMuxer) were read page by page on the PC. Every page holds one 20 ms packet, so each 20 to 40 byte packet carries a 28-byte page header. Across the 15 files, only 143,491 of 285,941 bytes (50.2%) are Opus packets; the rest is page headers.
- **The encoder was not the cause.** That overhead, not the encoder, is why "12 kbps" measured 170 KB a minute: the packets alone come to about 85 KB a minute, which is the nominal rate.
- **The size with 50 packets a page.** The same packets packed 50 to a page come to **91,093 bytes a minute at 12 kbps and 180,034 at 24 kbps**. These figures are computed from those real packets; the phone has not measured them yet. The packets are identical, so what the engine hears does not change, and neither does card 25's key-token result.
- **Other muxer faults.** The same files show:
  - no end-of-stream flag on the last page;
  - granules that count from the start of each page's packet rather than its end;
  - a pre-skip of 104, which is the encoder's 6.5 ms delay counted at 16 kHz instead of the 48 kHz RFC 7845 asks for (312).

  None of these stops decoding, but the app's writer does each one the way the RFC says.
- **A memory file very probably works.** The documentation for `MediaMuxer(FileDescriptor, int)` (API 26) asks only for an fd open for writing (read-write is also accepted), and a memfd is read-write and seekable. That is reasoning, not a phone result. The debug screen runs E1 beside E2, so the first run answers it.
- **Why E2 is the default:** the smaller size, and its code is pure Kotlin, tested here. If the phone shows that `MediaExtractor` will not read the app's files, set `OpusContainer.DEFAULT = MUXER` (one constant); the sizes then go back to card 25's.
- E3 (a plain temporary file) was not built.

**Sources for the Ogg and Opus layout** (read 2026-11-12):
- RFC 3533 section 6: Ogg page header, flags, lacing, byte order, CRC polynomial.
- RFC 7845 sections 3, 4, 5.1 and 5.2: which pages carry the two headers, granule positions and pre-skip, and the byte layout of `OpusHead` and `OpusTags`.
- RFC 6716 sections 3.1 and 3.2.5: the TOC byte, frame sizes, frame count.
- The CRC's initial value and bit order are not in RFC 3533. They are libogg's: initial value 0, not reflected, no final XOR; the check value of "123456789" is `0x89A1897F`. This was confirmed against all 267 pages of `s13.ogg`, and the first two pages of that file (headers only, no sound) are a test vector.
- The `AOPUSHDR`/`AOPUSDLY` marker form is from memory of Android's `OpusHeader.cpp`, not re-read. The code also accepts a plain `OpusHead` or an 8-byte delay, and the debug screen prints which form the phone gives.

What differs from the card, and why:
- **E2 is the default, decided on the PC from card 25's files** rather than left to the first phone check (reasons above). The sizes change: "Small" is about 90 KB a minute and "Clearer" about 180 KB (the card said 170 and 260). Both are calculations from real packets until the phone run measures them.
- **The debug screen is a new activity, `AudioFormatCheckActivity`**, not an extension of `RecorderCheckActivity`: this build was told not to edit 26a's screen.
- **Five rows, not four:**
  - `OPUS_12` through the app's writer (E2);
  - `OPUS_12` through MediaMuxer in memory (E1);
  - `OPUS_24`;
  - "forced AAC_32": Opus is asked for with the Opus encoder hidden, so the real fallback path picks AAC;
  - "forced WAV": Opus is asked for with the encoder made to fail on the 40th chunk.
- **`StreamingEncoder` keeps its own copy of the samples** for the WAV fallback, so it does not depend on the caller. Together with the recorder's own buffer that makes two copies (about 11.5 MB at three minutes). Card 26g can drop one.
- **Pre-skip for the app's own files** comes from the codec's delay in nanoseconds (`csd-1`, or `AOPUSDLY`). If the codec gives none, it is 312 (6.5 ms, libopus's look-ahead). The pre-skip the codec writes in its own `OpusHead` is reported but not used (it is 104 on this phone, see above).
- **The codec is started on the caller's thread.** The debug screen calls `start` from a background thread; card 26g should do the same.
- **The 1.5 s limit** runs from the call to `finish()` and includes any chunks still queued.
- **`Resampler` refuses rates that are not a whole multiple of 16 kHz** (other rates are out of scope per the card). The platform's AAC decoder gives 16 kHz and its Opus decoder gives 48 kHz.

### Phone steps for the owner (nothing here has been run)

**Prepare.**
- Build and install the debug build the usual way (`installDebug`).
- The screen asks for the microphone permission when it first opens.
- Connect adb (Wi-Fi works).
- Results are appended to `files/spike26/format.txt` (numbers and codec names only). To read the file, run `adb exec-out run-as app.stash cat files/spike26/format.txt`, or read the screen.

1. **E1 or E2, and a 10-second note.**
   1. Run `adb shell am start -n app.stash/app.stash.debug.AudioFormatCheckActivity`. The first line says whether the phone has Opus and AAC encoders.
   2. Tap "Record a sentence", say about ten seconds of speech, then stay quiet. It stops 2.5 s later.
   3. Each of the five rows shows `format`, `bytes`, `bytesPerMin`, `stopToFinishMs`, `maxQueuedChunks`, `decodeMs`, `decodedMs` against `originalMs` (`diffMs`), `problem`, `decodeError`, and the codec's own details (`codec=`, `container=`, `delayNs=`, `opusHeadPreSkip=`, `memfdBytes=`).
   4. **E1 is the MediaMuxer row.** If it says `format=OPUS_12` with no decode error, the muxer accepted the memory file. If it says `format=WAV`, write down its `problem` text: that is E1's error.
   5. **E2 is the first row.** `decodeError=none` with `diffMs` within ±50 means `MediaExtractor` reads the app's own Ogg. If E2 fails and E1 works, change `OpusContainer.DEFAULT` to `MUXER`.
   6. Write down `bytesPerMin` for the first row (expect about 90,000) and for the second (expect about 170,000).
2. **Timing, with one encoder only.**
   1. Tap the top button until it says "only OPUS_12 own writer".
   2. Record a 10-second sentence. `stopToFinishMs` must be under 300.
   3. Tap "Record 3 minutes" and talk (or leave a radio on) until it stops by itself at 3:00. Then check:
      - `stopToFinishMs` is under 1,000;
      - `maxQueuedChunks` is a handful, not hundreds (a growing queue means the encoder cannot keep up);
      - `diffMs` is within ±50.
   4. Say whether the phone got warm.
3. **Fallbacks.** Switch back to "all five" mode and record once.
   - "Forced AAC_32" must say `format=AAC_32`.
   - "Forced WAV" must say `format=WAV`.
   - Tap each one's Play button: both must play the sentence.
4. **Check C3, by ear.**
   1. In "all five" mode, record three sentences: an ordinary one, one said quietly, and one with hard names ("The Valpolicella by Allegrini is in row 5, bucket 10"; "Syd has the spare key in the frunk").
   2. For each, tap Play on the `OPUS_12` row, on the `OPUS_24` row, and "Play the original" to compare. Listen once on the phone's speaker and once with headphones.
   3. Write the answer in `docs/EXPERIMENT_LOG.md` as one of:
      - **Q1** "12 is fine";
      - **Q2** "12 is hard to follow, 24 is fine" (then set `AudioFormats.DEFAULT_FORMAT = OPUS_24`);
      - **Q3** "neither".
   4. This can also be done on the PC today: play `docs/spike25/results/enc/opus12/s07.ogg` and `s13.ogg`. They hold the same packets the app's writer would store.
