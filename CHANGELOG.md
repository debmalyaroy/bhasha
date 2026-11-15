# Changelog

Each release has a section headed `## X.Y.Z`; the release workflow publishes that
section as the release notes.

## 0.2.0

- **Better, faster translation:** English now comes from IndicTrans2, a model built only for
  translating Indian languages. On our test sentences it is clearly more accurate than
  Gemma 4 (chrF 61 against 55), takes about a quarter of a second per sentence instead of
  one to two seconds, and is a 235 MB download instead of 2.8 GB. Gemma 4 is now optional
  and only needed for the tutor's explanations.
- **Hears quiet and distant speakers:** the microphone signal is levelled before it is
  recognised. Speech 30 dB quieter than normal used to produce four times as many mistakes;
  it is now close to speech at normal volume.
- **Whole sentences:** a sentence may run for 10 seconds before it is cut (was 6), so long
  sentences are translated in one piece.
- **Lighter on battery and memory:** the live preview is throttled, a Battery saver switch
  in Help turns it off, and all models are released from memory three minutes after
  listening stops.
- **Dark and light theme:** Menu → Theme.
- **New icon.**
- **Live transcription:** the words now appear while they are being spoken, not only when
  the sentence ends, and the English translation follows along. Unfinished sentences are
  shown in grey and corrected when the speaker pauses.
- **Transcribe only:** a new mode that writes down Hindi, Bengali or Kannada in its own
  script without translating. It needs only a speech pack. Copy puts the text on the
  clipboard.
- **Translation no longer holds up listening:** sentences are translated in the background,
  so the recogniser keeps up with a speaker who does not pause.
- **Menu:** Help, Take the tour, Theme, Check for updates and About, at the top right.
- **Tour and help:** a first-run tour and a help page that explain how to use the app.
- **Why models are needed:** the Models tab now explains what each download does, which
  ones you need, and what is already installed.
- **Updates:** the app checks GitHub once a day for a newer version, downloads it, verifies
  its checksum and opens Android's installer. The check can be switched off in Help.

Upgrading from 0.1.0 has to be done by hand once (download the APK from the release page);
from 0.2.0 on the app offers updates itself.

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
