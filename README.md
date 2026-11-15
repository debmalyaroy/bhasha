# Bhasha

[![CI](https://github.com/debmalyaroy/bhasha/actions/workflows/ci.yml/badge.svg)](https://github.com/debmalyaroy/bhasha/actions/workflows/ci.yml)

On-device live translation (Hindi, Bengali, Kannada → English) and language
learning for the same three languages. Speech recognition, translation and the
tutor all run on the phone; the network is only used to download models.

Android only for now (arm64, Android 10 or newer). Speech models of this size need
a recent phone with plenty of memory; the target is a flagship with 12 GB of RAM.

## Install

1. Download `Bhasha-<version>.apk` from the
   [latest release](https://github.com/debmalyaroy/bhasha/releases/latest) and open it on
   the phone (allow installing from your browser or file manager when asked), or run
   `adb install -r Bhasha-<version>.apk`.
2. Open the **Models** tab and download the language packs you want (198 MB each) and
   Gemma 4 E2B (2.8 GB). Use Wi-Fi.
3. Open **Translate**, choose the language being spoken and start listening.

## How it works

```
microphone → Silero VAD (sentences) → IndicConformer (text) → Gemma 4 (English) → captions / speech
```

| Path | What |
| --- | --- |
| `engine/include/tr_engine.h` | Portable C API of the engine (used from Android through JNI) |
| `engine/src/tr_asr.cpp` | Sentence segmentation and speech recognition (whisper.cpp, sherpa-onnx) |
| `engine/src/tr_llm.cpp` | Translation with llama.cpp |
| `engine/jni/` | JNI bindings |
| `engine/tests/` | Command-line test programs for the engine |
| `asr/`, `llm/` | Android library modules that build the two native libraries |
| `app/` | Kotlin + Jetpack Compose app |
| `content/learn/` | Course sources, one JSON file per unit (format in `content/learn/SPEC.md`) |
| `tools/` | Course builder and the third-party fetch script |

whisper.cpp and llama.cpp each bundle their own ggml, so they are built as two
separate shared libraries: whisper statically with hidden symbols, llama.cpp as
shared libraries with one CPU backend per instruction-set level (the best one
the phone supports is chosen at start-up).

## Models

Downloaded in the app from Hugging Face, or side-loaded with
`adb push <file> /sdcard/Android/data/com.bhasha.translator/files/models/`.

| Model | File on the phone | Size |
| --- | --- | --- |
| IndicConformer language pack (Hindi / Bengali / Kannada) | `indic-conformer-{hi,bn,kn}.int8.onnx` | 198 MB each |
| Silero VAD | `ggml-silero-v5.1.2.bin` | 0.9 MB |
| Gemma 4 E2B (translator) | `gemma-4-E2B-it-Q4_0.gguf` | 2.8 GB |
| Whisper (optional: automatic language detection, direct speech → English) | `ggml-*.bin` | 190 MB – 1.1 GB |

Word error rates measured on 30 FLEURS test recordings per language through the app's
own engine: Hindi 11 %, Bengali 13.5 %, Kannada 21 %.

## Build

Requires JDK 17, Python 3, Git Bash (on Windows) and the Android SDK with platform 36,
build-tools 36.0.0, NDK 28.2.13676358 and CMake 3.31.6. `local.properties` must point
at the SDK (`sdk.dir=...`).

```
bash tools/fetch_third_party.sh                          # pinned whisper.cpp, llama.cpp, sherpa-onnx
./gradlew assembleRelease                                # arm64 phones
./gradlew assembleDebug -Pbhasha.abis=arm64-v8a,x86_64   # also the emulator
adb install -r app/build/outputs/apk/release/app-release.apk
```

Native code is always compiled optimised, including in debug builds.

Without a signing key a release build is signed with your debug key. To use your own
key, create `keystore.properties` in the project root (it is ignored by git):

```
KEYSTORE_FILE=/absolute/path/to/key.jks
KEYSTORE_PASSWORD=...
KEY_ALIAS=...
```

### Courses

Edit the files in `content/learn/<language>/`, then run `python tools/build_courses.py`.
It validates the content and writes `app/src/main/assets/learn/<code>.json`; do not edit
those merged files by hand. CI fails if they are out of date.

## Releases

Versions follow `MAJOR.MINOR.PATCH`. To release:

1. Set `bhasha.version` in `gradle.properties`.
2. Add a `## X.Y.Z` section to `CHANGELOG.md`.
3. Commit, then `git tag vX.Y.Z && git push origin main vX.Y.Z`.

The release workflow checks that the tag matches the version, builds the APK, signs it
with the key held in the repository secrets (`BHASHA_KEYSTORE_BASE64`,
`BHASHA_KEYSTORE_PASSWORD`, `BHASHA_KEY_ALIAS`) and publishes a GitHub release with the
APK, its SHA-256 and the changelog section as notes.

## Licence

Copyright (C) 2026 the Bhasha authors.

This program is free software: you can redistribute it and/or modify it under the
terms of the GNU Affero General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later version.
It is distributed without any warranty; see [`LICENSE`](LICENSE) for the full text.

Third-party components and models keep their own licences; see
[`THIRD_PARTY.md`](THIRD_PARTY.md).
