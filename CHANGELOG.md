# Changelog

Each release has a section headed `## X.Y.Z`; the release workflow publishes that
section as the release notes.

## 0.1.0

First release. Android only (arm64 phones, Android 10 or newer).

- **Live translation:** listens through the microphone and shows English captions for
  Hindi, Bengali and Kannada speech, entirely on the phone. Translations can also be
  spoken through the phone speaker or connected headphones.
- **Speech recognition:** one AI4Bharat IndicConformer language pack per language
  (198 MB each), with Whisper as an optional extra for automatic language detection.
- **Translation:** Gemma 4 E2B through llama.cpp.
- **Learn:** Hindi (35 units, 513 phrases), Bengali (38 units, 552 phrases) and Kannada
  (34 units, 499 phrases) courses with grammar guides, script lessons, listening and
  speaking practice.

Models are not part of the APK. Download them in the app's Models tab: the language
packs you want plus Gemma 4 E2B (2.8 GB).

Known limits: the courses have not been reviewed by a human native speaker, and the
app has only been tested in the Android emulator so far.
