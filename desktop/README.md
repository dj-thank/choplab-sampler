# ChopLab Windows Desktop prototype

This target is the local Windows EXE for ChopLab. It renders the Android-origin おとひろい deck through Compose Multiplatform: the four-step workflow, exact source/chop/beat/save copy, 4 x 4 PAD surface, selected-PAD editor, BANK A–D dock, guided production actions, and 16-step sequencer are the same shared UI source.

## Run locally

```powershell
./gradlew.bat :desktop:test
./gradlew.bat :desktop:run
```

The desktop app supports user-selected WAV import and Windows microphone capture first. It does not capture Spotify audio, download Spotify Content, convert Spotify tracks to MP3, or expose Spotify audio bytes to the sampler.

## Spotify development login

1. Register a Spotify Developer app.
2. Register the loopback redirect base allowed by Spotify: `http://127.0.0.1`.
3. Set only the public client ID in the current shell:

```powershell
$env:CHOPLAB_SPOTIFY_CLIENT_ID = 'your-public-client-id'
./gradlew.bat :desktop:run
```

The OAuth helper uses Authorization Code with PKCE and keeps the first-slice token in memory only. The current shared deck keeps that provider seam separate from local audio; no client secret or token belongs in source control.

Spotify is deliberately a metadata/playback-control integration. The desktop app does not capture Spotify audio, download Spotify Content, stream-rip, record, extract, or convert Spotify tracks to MP3. Use a user-selected local WAV as the sampler source.

## Build the Windows app image

```powershell
./gradlew.bat :desktop:packageWindows
```

The generated self-contained launcher is under `desktop/build/windows-app-image/ChopLab/ChopLab.exe`. This is an app-image containing a private Java runtime, not yet a signed installer or public release.

The default desktop version is `0.2.0` and can be overridden for a controlled build with `-PdesktopVersion=0.2.1`. Every GitHub PR touching the desktop target runs the Windows test/package workflow and uploads the app-image plus an EXE SHA-256 receipt. A `v*` GitHub Release also packages the Windows app-image beside the Android APK and iOS Simulator preview. Signing and installer publication remain separate authorized steps.
