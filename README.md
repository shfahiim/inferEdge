# inferEdge

inferEdge is a personal fork of Google AI Edge Gallery focused on on-device AI experiments, model management, and a new mobile inference server feature.

## Overview

The app is an Android Compose project that runs models locally on the device. It includes:

- Chat and single-turn LLM flows
- Model download and management
- Benchmarking tools
- Experimental tasks like Mobile Actions and Tiny Garden
- A mobile inference server that exposes a selected on-device model over an OpenAI-compatible HTTP API

## Requirements

- Android 12 or newer
- JDK 11
- Android Studio if you want the IDE workflow
- A device or emulator with enough RAM for the selected model

If you want model downloads to work, configure your own Hugging Face OAuth app in:

- [`Android/src/app/src/main/java/com/google/ai/edge/gallery/common/ProjectConfig.kt`](Android/src/app/src/main/java/com/google/ai/edge/gallery/common/ProjectConfig.kt)
- [`Android/src/app/build.gradle.kts`](Android/src/app/build.gradle.kts)

## Run

### With Android Studio

1. Open [`Android/src`](Android/src) as the Android project.
2. Update the Hugging Face placeholders if you need downloads.
3. Pick a device or emulator.
4. Run the `app` configuration.

### Without Android Studio

From [`Android/src`](Android/src):

```bash
./gradlew assembleDebug
./gradlew installDebug
```

The debug APK will be installed on the connected device or emulator.

## Build

### Debug

```bash
cd Android/src
./gradlew assembleDebug
```

### Release

```bash
cd Android/src
./gradlew assembleRelease
```

This fork currently signs the release build with the debug signing config. Replace that if you want a production release.

### Tests and checks

```bash
cd Android/src
./gradlew test
./gradlew connectedAndroidTest
./gradlew lint
```

## Architecture

The app is organized around a single Android app module with task-based UI flows.

- [`Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/`](Android/src/app/src/main/java/com/google/ai/edge/gallery/ui) contains the main Compose screens, navigation, and shared UI pieces.
- [`Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/`](Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks) contains the feature tasks.
- [`Android/src/app/src/main/java/com/google/ai/edge/gallery/runtime/`](Android/src/app/src/main/java/com/google/ai/edge/gallery/runtime) contains model/runtime helpers.
- [`Android/src/app/src/main/java/com/google/ai/edge/gallery/data/`](Android/src/app/src/main/java/com/google/ai/edge/gallery/data) contains config, allowlists, and model metadata.
- [`Android/src/app/src/main/java/com/google/ai/edge/gallery/common/`](Android/src/app/src/main/java/com/google/ai/edge/gallery/common) contains shared utilities and project config.

### Mobile inference support

The new inference server lives under:

- [`Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/inferenceserver/`](Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/inferenceserver)

It works like this:

1. A model is selected and initialized in the model manager.
2. The inference server task starts an embedded HTTP server on the device.
3. Requests are handled through an OpenAI-compatible API surface, including streaming responses over SSE.
4. The screen shows live telemetry such as request counts, memory, CPU, and token throughput.

By default the server binds to localhost. You can optionally expose it to the local network and require an API key.

## Configuration

- `Android/src/app/src/main/java/com/google/ai/edge/gallery/common/ProjectConfig.kt` holds the Hugging Face OAuth placeholders.
- `Android/src/app/build.gradle.kts` contains the `appAuthRedirectScheme` manifest placeholder.
- `Android/src/app/src/main/res/values/strings.xml` contains the app name and user-facing labels.

## Troubleshooting

- If model download fails, verify the Hugging Face OAuth client ID and redirect URI.
- If the inference server will not start, make sure the selected model is initialized first.
- If the server reports a port conflict, choose another port.
- Large models may exceed device memory. Start with a smaller model.

## License

Apache License 2.0. See [`LICENSE`](LICENSE).
