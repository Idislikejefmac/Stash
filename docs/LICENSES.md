# Licence register

Rule (D78): nothing is added to the app, or even tested for it, until its licence is recorded here. This covers **libraries and model files separately**, because a model is a data file with its own licence. Not legal advice; a lawyer should review before any sale (Q48).

**Allowed without asking:** Apache-2.0, MIT, BSD (2 or 3 clause), ISC, and CC-BY (attribution only).
**Ask the owner first:** LGPL, MPL, any custom or "community" licence, anything with a revenue or user cap.
**Not allowed:** GPL, AGPL, CC-BY-NC or any non-commercial or research-only terms, models whose licence forbids commercial use, paid SDKs that charge per user.

Obligations that apply to every allowed licence: ship the licence text and copyright notices (a screen "Open source notices" at release), keep any NOTICE file, do not use the authors' names to promote the app.

**Confidence note:** the entries below come from the designer's memory, not from reading each licence on the day. Mark a line **verified** (with date and link) when someone has read the real text. Until then it is a lead, not a fact. The speech-engine section was checked against primary pages on 2026-11-12; the other sections are still from memory.

## Shipped in the app today (`app/build.gradle.kts`, `implementation`)

| Item | Licence (expected) | Status |
| --- | --- | --- |
| AndroidX: core-ktx, activity-compose, lifecycle, Compose BOM, Material 3, Room, WorkManager | Apache-2.0 | not yet verified |
| kotlinx-coroutines-android | Apache-2.0 | not yet verified |
| Kotlin standard library | Apache-2.0 | not yet verified |
| Tink `com.google.crypto.tink:tink-android:1.24.0` (added by card 16a, 2026-11-12), with protobuf shaded inside it, and its dependencies `androidx.annotation:annotation-jvm` 1.8.2, `com.google.code.findbugs:jsr305` 3.0.2, `com.google.code.gson:gson` 2.13.2, `com.google.errorprone:error_prone_annotations` 2.41.0 | Apache-2.0 (each POM on Maven Central names Apache 2.0, read 2026-11-12) | lead, not verified: only the POM licence fields were read, not the licence texts or NOTICE files |

Test-only, not shipped: JUnit (EPL-1.0), Robolectric (MIT), org.json (JSON licence, test only), coroutines-test.

## Planned by later cards (not added yet)

| Item | Card | Licence (expected) | Status |
| --- | --- | --- | --- |
| ML Kit text recognition, barcode scanning | 16, 21 | Google ML Kit terms: free, proprietary; usage reporting to Google (Q46) | partly verified 2026-11-12, https://developers.google.com/ml-kit/terms : the terms "incorporate and are subject to the Google APIs Terms of Service"; "The ML Kit APIs also send metrics about the performance and utilization of the APIs in your app to Google" and the app must tell its users so; no reverse engineering of the models. The page does not state fees or commercial terms; the Google APIs Terms of Service were not read |
| CameraX | 16 | Apache-2.0 | not verified |
| Tink (streaming AEAD) | 16 | Apache-2.0 | not verified |
| SQLCipher for Android | 15, 14 | BSD-style community edition (Zetetic) | not verified |

## Speech engine candidates (card 25)

Read on 2026-11-12 by a reviewing agent from the primary pages named. Licence names are as the licence file or model card states them. Sizes are the files as packaged for the runner, not the APK delta. "Ask" means D78's "ask the owner first".

| Item | Kind | Licence | Commercial use | Attribution needed | Size; Android | Status |
| --- | --- | --- | --- | --- | --- | --- |
| sherpa-onnx | runner library (code) | "Apache License, Version 2.0" (LICENSE has no copyright line; keep the project's NOTICE/third-party files if the AAR ships any) | yes | licence text in Open source notices | v2.0.1 (newest numbered release, dated 26 Oct; page shows no year, 2026 inferred) ships `sherpa-onnx-2.0.1.aar` (47.2 MB, all ABIs) and `sherpa-onnx-static-link-onnxruntime-2.0.1.aar` (36.8 MB); arm64 included. 16 KB alignment of its `.so` files not stated anywhere: check on the APK | verified 2026-11-12, https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE and https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1 . **Card 25 run, 2026-11-12:** v2.0.1 confirmed newest (no v2.0.2 to v2.1.1 assets); `sherpa-onnx-2.0.1.aar` downloaded is 49,441,341 bytes, sha256 `a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7`; its four arm64 `.so` files (31.4 MB) have every ELF LOAD segment at 2**14 and pass `zipalign -c -P 16` in the APK (**16 KB aligned, verified**) |
| ONNX Runtime | library inside the sherpa-onnx AAR (code) | "MIT License", "Copyright (c) Microsoft Corporation" | yes | licence text and copyright line; ORT also has a ThirdPartyNotices file to carry (not read) | bundled by sherpa-onnx; version inside the Android AAR not stated | verified 2026-11-12, https://github.com/microsoft/onnxruntime/blob/main/LICENSE |
| whisper.cpp | fallback runner (code); the only tested way to give Whisper a text prompt (`initial_prompt` in `whisper.h`) | "MIT License", "Copyright (c) 2023-2026 The ggml authors" | yes | licence text and copyright line | latest v1.9.5 (6 Oct; year not shown, 2026 inferred); Android is an example app built from source with the NDK (no prebuilt AAR seen) | verified 2026-11-12, https://github.com/ggml-org/whisper.cpp/blob/master/LICENSE |
| Whisper `base.en`, `small.en` | model weights | OpenAI README: "Whisper's code and model weights are released under the MIT License." (The Hugging Face cards `openai/whisper-*.en` carry the metadata tag "apache-2.0"; both are allowed; the GitHub statement is the primary one) | yes | MIT text with OpenAI's copyright line | 74 M / 244 M parameters. sherpa-onnx int8: base.en 29.1 + 131 MB, small.en 112 + 262 MB. whisper.cpp ggml: 142 MiB / 466 MiB | verified 2026-11-12, https://github.com/openai/whisper (README, LICENSE), https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base.en , https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small.en |
| NVIDIA Parakeet-TDT 0.6B v2 (English) | model weights | "CC-BY-4.0" ("GOVERNING TERMS: Use of this model is governed by the CC-BY-4.0 license.") | yes ("ready for commercial/non-commercial use") | CC-BY-4.0: credit NVIDIA, link the licence, and say the files were changed (the int8 ONNX export is a change) | 600 M parameters; sherpa-onnx `sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8`: encoder 622 MB + decoder 6.9 MB + joiner 1.7 MB (about 630 MB). Runs in sherpa-onnx as `nemo_transducer` | verified 2026-11-12, https://huggingface.co/nvidia/parakeet-tdt-0.6b-v2 and https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/nemo-transducer-models.html |
| NVIDIA Parakeet TDT-CTC 110M (English) (added 2026-11-12) | model weights | "CC-BY-4.0" ("License to use this model is covered by the CC-BY-4.0.") | yes under CC-BY-4.0 (the card makes no separate commercial statement) | as Parakeet v2 | 114 M parameters; sherpa-onnx `sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8`: `model.int8.onnx` 126 MB. Runs as a CTC model, so **no hotwords** | verified 2026-11-12, https://huggingface.co/nvidia/parakeet-tdt_ctc-110m and https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-ctc/nemo/english.html |
| NVIDIA Parakeet-TDT 0.6B v3, parakeet-unified-en-0.6b, nemotron-speech-streaming-en-0.6b | model weights | v3 not read. unified (7 Apr 2026) and nemotron streaming (13 Mar 2026): "NVIDIA Open Model License Agreement" | the cards say "ready for commercial/non-commercial use", but it is a custom licence | n/a | 0.6 B, same size class as v2 (unified int8 about 630 MB in sherpa-onnx) | **not a candidate**: custom licence, D78 "ask"; also no smaller than v2 and v3 is multilingual. Revisit only if the owner accepts that licence. Read 2026-11-12, https://huggingface.co/nvidia/parakeet-unified-en-0.6b , https://huggingface.co/nvidia/nemotron-speech-streaming-en-0.6b |
| Moonshine code (`moonshine-ai/moonshine`, Android library `ai.moonshine:moonshine-voice`) | runner library (code) | "MIT License", "Copyright (c) 2025 Useful Sensors, Inc. (dba Moonshine AI)" | yes | licence text and copyright line | README lists Android; Quickstart gives `ai.moonshine:moonshine-voice:0.1.5`. 16 KB alignment not stated | verified 2026-11-12, https://github.com/moonshine-ai/moonshine/blob/main/LICENSE and https://moonshine-voice.readthedocs.io/en/latest/quickstart/ |
| Moonshine English models (tiny, base; streaming tiny, small, medium) | model weights | "MIT License" (LICENSE: all speech-to-text models are MIT except an exhaustive list of legacy non-streaming, **non-English** models under the "MOONSHINE AI COMMUNITY LICENSE AGREEMENT", which is non-commercial above US $1,000,000 annual revenue and needs registration). Every English model is MIT | yes (English only) | MIT text | streaming tiny 34 M, small 123 M (Open ASR mean WER 7.84), medium 245 M (6.65) parameters; int8 sizes not published (expect roughly 1 byte per parameter). sherpa-onnx packages `moonshine-tiny-en-int8`, `moonshine-base-en-int8` and `moonshine-{tiny,base}-en-quantized-2026-02-27` | verified 2026-11-12; also the LICENSE file inside `sherpa-onnx-moonshine-{base,tiny}-en-quantized-2026-02-27` (read in the card 25 run, same text: English models MIT, Section 1), https://github.com/moonshine-ai/moonshine/blob/main/LICENSE , https://huggingface.co/UsefulSensors/moonshine-streaming-small , https://huggingface.co/moonshine-ai/moonshine-streaming-medium , https://k2-fsa.github.io/sherpa/onnx/moonshine/index.html . Never download a non-English Moonshine file |
| Silero VAD | model | "MIT License", "Copyright (c) 2020-present Silero Team" | yes | licence text and copyright line | sherpa-onnx's export `silero_vad.onnx` about 629 KB (`silero_vad.int8.onnx` about 208 KB), 16 kHz; Android examples exist | verified 2026-11-12, https://github.com/snakers4/silero-vad/blob/master/LICENSE and https://k2-fsa.github.io/sherpa/onnx/vad/silero-vad.html |
| ML Kit GenAI Speech Recognition (`1.0.0-alpha2`) (added 2026-11-12) | Google SDK plus system-downloaded model | ML Kit terms plus "ML Kit GenAI API Additional Terms of Service" (the additional terms were not read) | proprietary; sends usage metrics to Google | n/a | Accepts **recorded files** (`AudioSource.fromFile`, `fromPfd`; 16 kHz mono PCM or MP3/WAV/FLAC/M4A/OGG). Basic mode API 31+; Advanced mode lists the Snapdragon SM8750, believed to be the S25 Edge's chip (not checked). Alpha, no SLA | **ask the owner** (custom terms, Google metrics; D8 rejected it as alpha and Pixel-only, which is no longer true). Not measured unless the owner says yes. Read 2026-11-12, https://developers.google.com/ml-kit/genai/speech-recognition/android and https://developers.google.com/ml-kit/terms . **Card 25 run, 2026-11-12 (measured with the owner's yes):** Advanced mode's chip list read on the Android page: SM8750 (the S25 Edge's chip, `ro.soc.model` = SM8750, verified), SM8845, SM8850, SM8950, SM8975, MT6991/MT6993/MT6995, Exynos 2600, Pixel 10 and 11; not on devices with an unlocked bootloader. The "ML Kit GenAI API Additional Terms of Service" were read (https://developers.google.com/ml-kit/genai-terms , last modified 14 May 2025): Prohibited Use Policy applies; "preview or experimental services" may not be used in production (so not while alpha); users must be 18 or over; the app must inform users about metrics processing; no clinical use; rate limits possible; no fee stated; benchmarking not mentioned. The library pulls in Play services base/basement/tasks, Firebase encoders and components, and Google datatransport (the metrics path). It is compiled with Kotlin 2.4 metadata |

### Files used in the card 25 run (downloaded 2026-11-12 to `C:\jeffsource\spike25-models\`, outside the repository)

Each is the English model named on the register above. sha256 computed locally; for the Hugging Face files it matches the LFS id that the Hugging Face API lists. Moonshine **tiny** English was added to the run because memory was the deciding bar; it is on the register (Moonshine English models row).

| File | Source | Bytes | sha256 |
| --- | --- | --- | --- |
| `sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8/model.int8.onnx` (+ `tokens.txt`, 9,953) | github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/ (tar.bz2) | 131,652,171 | `9177a9146cf32ee0cc8152276ef95116f312018d316be37ccf57f7efea81fc1a` |
| `sherpa-onnx-moonshine-base-en-quantized-2026-02-27/encoder_model.ort` | same | 31,326,816 | `7c66495948d0d08ec1af454cd4b5514862ae6511e94712a60e6d83eaec8dc8cf` |
| `.../decoder_model_merged.ort` (+ `tokens.txt`, 549,350) | same | 109,424,400 | `d9d7b333af34bc552580576ddcf248a1c6c839e0d3b43b09afb9376ed009899d` |
| `sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27/encoder_model.ort` | same | 13,281,600 | `94e90a4654fc45cdfedb77c4c08e1739f48862998e58fada384b25118134f221` |
| `.../decoder_model_merged.ort` | same | 30,412,256 | `cf524c4862d36e9e5ab032eddc73637efd822d70e868ac575cf1a46e1e4708a0` |
| `base.en-encoder.int8.onnx` / `base.en-decoder.int8.onnx` (+ `base.en-tokens.txt`, 835,554) | huggingface.co/csukuangfj/sherpa-onnx-whisper-base.en | 29,120,534 / 130,669,978 | `ef6b936f4c9b1d90a3b68634b60c4ed8576b26172b33c2535ec0e933c9edb823` / `f7162ad6db2dbef16cfaeaa7f945b9d7dd9c1b8d472f6aca82f2273d185e4d41` |
| `small.en-encoder.int8.onnx` / `small.en-decoder.int8.onnx` | huggingface.co/csukuangfj/sherpa-onnx-whisper-small.en | 112,442,483 / 262,223,042 | `8bdac288f369aa94ee2194059238c465ed82ea9d47ee8fa4a8c0a891873e462f` / `710ccf890e10f3faa15f51ec346081a2723c9f3adb6e4da81c6573a5a6f877fb` |
| `sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/encoder.int8.onnx`, `decoder.int8.onnx`, `joiner.int8.onnx` | github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/ (tar.bz2, 482 MB) | 652,282,309 / 7,257,753 / 1,739,080 | `92a10bd9c23936ea0566c29f963bc57c2a5df4261b6a8921c39abea66557ca8b` / `4d097bae3bac5c96018d5293ba39f1b657296102b142affda40378f38b020f41` / `77a29f3e5ac7d37074e0af465391b80f314015d5b97f0f90c6c7767f1d56f428`. Its README states "license: cc-by-4.0" and that the ONNX export was made by sherpa-onnx. **No `bpe.vocab` in the package** |
| `silero_vad.onnx` | github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/ | 643,854 | `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6` |
| `com.google.mlkit:genai-speech-recognition:1.0.0-alpha2` | Google Maven (debug only; measured, not adopted) | n/a | n/a |

## Services (not libraries)

| Item | Note |
| --- | --- |
| Anthropic API (and any other provider) | A product either has each user bring their own key (current design) or takes on the provider's commercial terms. See Q39, Q44, Q48 and `docs/PROVIDERS.md`. |

## Added by the card 16 split (2026-11-12) — leads only, all UNVERIFIED (from the card writer's memory)

| Item | Card | Licence (expected) | Status |
| --- | --- | --- | --- |
| Tink (`com.google.crypto.tink:tink-android`) | 16a | Apache-2.0; pulls in protobuf-javalite (BSD-3-Clause) and possibly Gson and annotation artifacts (Apache-2.0) | unverified. Added as 1.24.0 by card 16a: see "Shipped in the app today" (protobuf is shaded inside the jar, not a separate protobuf-javalite) |
| CameraX (`camera-core`, `-camera2`, `-lifecycle`, `-view`) | 16e | Apache-2.0; pulls in Guava concurrent-futures (Apache-2.0) | unverified |
| ML Kit Text Recognition v2, bundled Latin model | 16f | Google ML Kit terms (proprietary, free, usage reporting); model file believed under the same terms; pulls in Play services basement and tasks (Android SDK licence) and datatransport/Firebase components | unverified. **D78 "ask first": needs the owner's yes** |
| WebP encoding, image decoding, photo picker, direct-share shortcut | 16b, 16c | No new library: platform APIs and the already-shipped `androidx.activity` / `androidx.core` | n/a |
| Hosted vision providers | 16l | Services under their own terms, no library | n/a |

## Owner decisions on licences

- **2026-11-12, ML Kit GenAI Speech Recognition (alpha):** the owner approved **measuring** it as a fourth candidate in card 25, with no commitment to ship it. Adopting it would need a separate yes under D78 (Google's terms and usage reporting).
- **2026-11-12, "MIT-only":** not a goal. CC-BY-4.0 is allowed under D78; choose engines on accuracy, speed and size, and use the licence only as a tie-break.
- **2026-11-12, ML Kit Text Recognition v2 (card 16f):** approved by the owner under D78 "ask first". Disclose usage reporting to Google. Barcode scanning (card 21) is a separate ML Kit library and still needs its own yes.

## Added by the card 26 design (2026-11-12): to do when card 26d lands

- **Move to "Shipped in the app today" when 26d is built:** sherpa-onnx 2.0.1 AAR (Apache-2.0, arm64 only; changes from `debugImplementation` to `implementation`), ONNX Runtime inside it (MIT, "Copyright (c) Microsoft Corporation"), and the Moonshine English base and tiny `quantized-2026-02-27` models (MIT, "Copyright (c) 2025 Useful Sensors, Inc. (dba Moonshine AI)").
- **Texts to ship in the "Open source notices" screen:** the Apache-2.0 text; ONNX Runtime's MIT text and its `ThirdPartyNotices.txt`; the Moonshine package's `LICENSE` file (in the app and in each model pack).
- **Not yet read:** ONNX Runtime's `ThirdPartyNotices.txt`, and any NOTICE or third-party licences for code statically built into sherpa-onnx's four `.so` files. Read them before release.
- **Missing hashes:** this register has no sha256 for either Moonshine `tokens.txt` (base is 549,350 bytes; tiny's size is not recorded). Card 26d's `KnownModels` needs them.
- **Not shipped (spike only):** Silero VAD, Parakeet (both), Whisper (all), ML Kit GenAI Speech. Card 26d removes the spike's debug-only Gradle lines and the `-Xskip-metadata-version-check` flag.
- **No entry needed:** the Opus encoder and decoder, the Ogg muxer and AAC are the Android platform's own.
- **Rule:** only model packs listed in `KnownModels` may load, so a non-English Moonshine file (non-commercial licence) cannot be used by accident.
- **If card 26i adds a common-words list,** that data file needs its own row first. **If card 26l bundles or downloads the model,** that is redistribution under MIT: same texts, plus Q48.
- **ML Kit GenAI Speech Recognition:** its additional terms forbid production use of preview services and require users to be 18+ and metrics disclosure, so it is **not** a shipping candidate (found 2026-11-12 in the card 25 spike).

## Added by card 16e (2026-11-12): CameraX, read from Google Maven POMs on 2026-11-12 (POM licence fields only; licence texts and NOTICE files not read)

| Item | Version | Licence per POM | Notes |
| --- | --- | --- | --- |
| androidx.camera: camera-core, camera-camera2, camera-lifecycle, camera-view | 1.6.2 (newest stable; metadata latest is 1.7.0-rc02) | "The Apache Software License, Version 2.0"; camera-core also lists BSD-3-Clause | shipped (`implementation`) |
| camera-camera2-pipe, camera-video, featurecombinationquery | 1.6.2 | Apache 2.0 | transitive |
| viewfinder-core 1.5.1, concurrent-futures and concurrent-futures-ktx 1.1.0, exifinterface 1.4.2, core-backported-fixes 1.0.0, tracing and tracing-ktx 1.3.0, jspecify 1.0.0 | as listed | Apache 2.0 | transitive |
| dagger 2.59, javax.inject 1, atomicfu 0.28.0 | as listed | Apache 2.0 | transitive |
| listenablefuture (resolves to the empty `9999.0-empty-to-avoid-conflict-with-guava` stub), auto-value-annotations 1.6.3 | as listed | no licence in the artifact's own POM; the parent POM (guava-parent / auto-parent 7) says Apache 2.0 | transitive |

16 KB alignment: `zipalign -c -P 16 -v 4` (build-tools 36.0.0) on the debug APK reported "Verification successful"; CameraX's two native libraries (`libimage_processing_util_jni.so`, `libsurface_util_jni.so`) are OK. Debug APK size grew by 8.93 MB (unminified, includes the spike libraries). Tink 1.24.0 (card 16a) is listed above.

## Added by card 16f (2026-11-12): ML Kit Text Recognition v2, bundled Latin. Read from Google Maven POMs (POM fields only)

| Item | Version | Licence per POM | Notes |
| --- | --- | --- | --- |
| com.google.mlkit:text-recognition | 16.0.1 (newest stable; metadata last updated 2024-08-07) | "ML Kit Terms of Service" (proprietary; https://developers.google.com/ml-kit/terms) | owner approved 2026-11-12 (D78 "ask first"; D80). Usage reporting to Google is disclosed in Settings |
| mlkit: text-recognition-bundled-common 17.0.0, common 18.11.0, vision-common 17.3.0, vision-interfaces 16.3.0; gms: play-services-mlkit-text-recognition 19.0.1 and -common 19.1.0 | as listed | "ML Kit Terms of Service" | transitive, same terms |
| **gms: play-services-base 18.5.0, play-services-basement 18.4.0 (resolves to 18.9.0), play-services-tasks 18.2.0; android.odml:image 1.0.0-beta1** | as listed | **"Android Software Development Kit License" (proprietary)** | transitive. **Needs the owner's explicit yes under D78: a second proprietary licence on the path, separate from the ML Kit terms.** Not read: the licence text itself |
| datatransport: transport-api 2.2.1, transport-backend-cct 2.3.3, transport-runtime 2.2.6; firebase-components 16.1.0, firebase-encoders 16.1.0, firebase-encoders-json 17.1.0, firebase-annotations 16.0.0 | as listed | Apache 2.0 | transitive. `transport-backend-cct` is probably the route for ML Kit's usage reports (not verified) |
| gson 2.13.2, jsr305 3.0.2 | as listed | Apache 2.0 | transitive |

16 KB alignment: `zipalign -c -P 16 -v 4` (build-tools 36.0.0) reports "Verification successful"; `libmlkit_google_ocr_pipeline.so` (11.06 MB) and all other `.so` files are aligned (LOAD segments `p_align` 0x4000). Debug APK grew by about 9.9 MiB (arm64, unminified; before-file not rebuilt from the same source). The release size is not measured.

## Card 26d built (2026-11-12): moved to SHIPPED

| Item | Version | Licence | Status |
| --- | --- | --- | --- |
| sherpa-onnx (arm64 AAR, outside the repository, path from `stash.sherpaAar` in `local.properties`) | 2.0.1 | Apache-2.0 | **Shipped** (with the AAR present; without it the build compiles with `NoTranscriber`). Licence text bundled in `app/src/main/assets/licenses/` (read from the v2.0.1 tag; the AAR holds no NOTICE) |
| ONNX Runtime (inside the AAR) | 1.28.2 (string found in `libonnxruntime.so`) | MIT, Microsoft | **Shipped**. `LICENSE` and `ThirdPartyNotices.txt` (325,054 bytes) bundled; ThirdPartyNotices now read |
| Moonshine English base and tiny, `quantized-2026-02-27` | as pinned in `KnownModels` | MIT, "Copyright (c) 2025 Useful Sensors, Inc. (dba Moonshine AI)" | **Shipped as importable packs** (not in the APK). `tokens.txt` sha256 `2870d843e14c1e187bf1913a521562a63b53933814bd7f2145120468f494a049` (549,350 bytes), identical in both packs; `LICENSE` sha256 `6148d757…0abd` (13,344 bytes) |

Components named in sherpa-onnx's CMake at v2.0.1 (texts bundled under `assets/licenses/sherpa-onnx-third-party/`, index in `sherpa-onnx-components.txt`): kaldifst v1.8.0, OpenFst (COPYING), simple-sentencepiece v0.7, utf8proc v2.12.0, nlohmann json v3.12.0 (MIT), hclust-cpp (BSD-2), kaldi-native-fbank v1.22.4 and kaldi-decoder v0.3.0 (Apache-2.0). **No GPL or espeak code found in the libraries.**

**OPEN: Eigen 3.4.0 is MPL-2.0** ("ask first" under D78). kaldi-decoder uses it; it is **not confirmed** from the libraries whether it is compiled in. The MPL text is already inside ONNX Runtime's ThirdPartyNotices. Decide before any release (Q48 lawyer review); MPL-2.0 is file-level copyleft that does not reach the app's own source.

APK sizes (unsigned release): before 81,048,163 bytes (all four ABIs); after, without the engine, 50,988,872 (arm64 only); with the engine 82,774,158. The engine itself adds 31,785,286 bytes. `zipalign -c -P 16` passes on the release APK with the engine (all four sherpa/ORT `.so` and the other four). No Maven Central coordinate exists from k2-fsa for the AAR (a JitPack coordinate `com.github.k2-fsa:sherpa-onnx:v2.0.1` returns a POM; not switched).

## Owner decisions on licences (2026-11-12, continued)

- **Google Play services libraries under the "Android Software Development Kit License"** (play-services-base, basement, tasks, android.odml image) that come with ML Kit text reading: **accepted for now by the owner**; flagged for the lawyer review (Q48) if Stash becomes a product. If declined later, card 16f would need an open-source text reader (usually less accurate).
