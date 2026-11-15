# Third-party software and models

Bhasha itself is licensed under AGPL-3.0-or-later (see `LICENSE`). It builds on the
following components, each under its own licence.

## Compiled into the APK

| Component | Version | Licence | Source |
| --- | --- | --- | --- |
| llama.cpp (with ggml) | v1.0.0 | MIT | https://github.com/ggml-org/llama.cpp |
| whisper.cpp (with ggml) | v1.9.5 | MIT | https://github.com/ggml-org/whisper.cpp |
| sherpa-onnx (prebuilt C API) | v1.13.8 | Apache-2.0 | https://github.com/k2-fsa/sherpa-onnx |
| ONNX Runtime (prebuilt, shipped with sherpa-onnx) | as bundled | MIT | https://github.com/microsoft/onnxruntime |
| AndroidX, Jetpack Compose, Material 3 | see `gradle/libs.versions.toml` | Apache-2.0 | https://developer.android.com/jetpack |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | see `gradle/libs.versions.toml` | Apache-2.0 | https://github.com/JetBrains/kotlin |

The APK also contains the token table of the IndicConformer models
(`app/src/main/assets/asr/indic-conformer-tokens.txt`), taken from
https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx (Apache-2.0).

## Downloaded by the user inside the app

These are not distributed with Bhasha. The app downloads them from Hugging Face when
the user asks for them, and each is subject to its own terms.

| Model | Used for | Licence |
| --- | --- | --- |
| AI4Bharat IndicConformer (Hindi, Bengali, Kannada), ONNX conversion by parismitaglobalsolutions | Speech recognition | MIT (models), Apache-2.0 (conversion) |
| Silero VAD | Finding sentences in the audio | MIT |
| OpenAI Whisper (ggml conversions) | Optional: language detection, direct speech-to-English | MIT |
| Google Gemma 4 | Translation | Gemma Terms of Use |
