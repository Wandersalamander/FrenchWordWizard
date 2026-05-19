# Vocabulary Trainer

An Android app for learning vocabulary in French, German, Italian, and Chinese. Tracks per-word mastery, schedules practice adaptively, and generates fresh example sentences on-device using a local language model — no network calls during a session.

## Highlights

- **Adaptive scheduling**: each word has a per-skill mastery score (read / listen / invert). Words near mastery are spot-checked before being retired; the active pool size adapts to your accuracy.
- **On-device sentence generation**: example sentences are produced by [Gemma 4 E2B-it](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) running locally via the LiteRT-LM SDK. Apache 2.0, ungated. GPU acceleration on supported devices (OpenCL), CPU fallback otherwise.
- **Streaks & reminders**: daily streak counter, configurable reminder notifications, boot-resilient via `BootReceiver`.
- **Offline after first run**: the only network call is the one-time model download (~2.59 GB). Falls back to bundled CSV sentences if the model isn't available.

## Build

Requires Android Studio with SDK 34. `minSdk` is 26.

```
./gradlew assembleDebug
```

Release builds use R8 (`minifyEnabled true`) — keep rules for LiteRT-LM live in [app/proguard-rules.pro](app/proguard-rules.pro).

## Model setup

On first launch you'll be prompted to download the model from HuggingFace (~2.59 GB, requires ~3 GB free). Alternatively, sideload to skip the download:

```
adb push gemma-4-E2B-it.litertlm \
  /sdcard/Android/data/com.example.myapplication/files/llm/model.litertlm
```

The model file path is per-app external storage, so no root is required.

## Project layout

```
app/src/main/java/com/example/myapplication/
├── quiz/          # MainActivity, QuizController, swipeable cards, progress UI
├── dictionary/    # Vocab model, scheduling math, language data, CSV parsing
├── llm/           # LlmService (Gemma 4 wrapper), ModelDownloader
├── streak/        # Streak tracking, alarms, notifications, boot receiver
├── settings/      # SettingsActivity
├── wordlist/      # WordListActivity + adapter (browse all vocab)
├── service/       # MyForegroundService (dictionary reload coordination)
└── data/          # AppPrefs (SharedPreferences wrapper)
```

Vocabulary CSVs live in [app/src/main/assets](app/src/main/assets) under one folder per language.

## Tests

```
./gradlew test                  # unit tests (scheduling math, dictionary progress)
./gradlew connectedAndroidTest  # instrumented tests (currently stub)
```
