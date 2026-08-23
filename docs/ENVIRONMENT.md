# Environment contract

## Required locally

- Git
- JDK 17
- Android SDK Platform 37.0 (compile); API 36 system image remains the instrumentation target
- Build Tools 36.0.0
- Android platform-tools
- Android Studio or command-line SDK tools
- Codex CLI for local agent work

## Required when native audio integration begins

- NDK 29.0.14206865
- CMake 3.22.1
- Oboe version selected and locked by Gradle/CMake after primary-source verification
- A physical API 29+ device for low-latency and capture testing

## Environment variables

Use one of:

```text
ANDROID_HOME
ANDROID_SDK_ROOT
```

Do not commit `local.properties`. Bootstrap scripts create it from the environment when possible.

## Optional container

`.devcontainer/` provides a reproducible Linux command-line Android environment. It is intended for Gradle, unit, lint and native compilation. Playback Capture, microphone, Bluetooth/USB MIDI and latency measurements still require physical-device validation.
