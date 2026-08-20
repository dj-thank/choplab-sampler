# ChopLab Windows Desktop

This target is the local Windows EXE for ChopLab. It renders the Android-origin おとひろい deck through Compose Multiplatform: the four-step workflow, exact source/chop/beat/save copy, 4 x 4 PAD surface, selected-PAD editor, BANK A–D dock, guided production actions, and 16-step sequencer are the same shared UI source.

## Run locally

```powershell
./gradlew.bat :desktop:test
./gradlew.bat :desktop:run
```

The packaged launcher also accepts a `.wav` or `.choplab` path as its first argument, which is used for Windows “Open with” workflows and deterministic loaded-state visual checks.

The desktop app supports user-selected WAV import, microphone recording, a driver-exposed Windows playback loopback such as `Stereo Mix`, PAD voice controls, 16-step transport, scratch, four-bar WAV export, Undo/Redo, manual `.choplab` save/open, and app-owned three-generation autosave. A loopback input is never silently replaced with a microphone; unsupported drivers return a visible error.

## Spotify development login

1. Register a Spotify Developer app.
2. Register the dynamic-port loopback redirect allowed by Spotify: `http://127.0.0.1/callback` (no port in the dashboard entry; the app adds its one-shot local port).
3. Set only the public client ID in the current shell:

```powershell
$env:CHOPLAB_SPOTIFY_CLIENT_ID = 'your-public-client-id'
./gradlew.bat :desktop:run
```

Start `連携 > Spotify ログイン` from the native Windows menu. The OAuth session uses Authorization Code with PKCE and keeps access/refresh tokens in memory only. No client secret or token belongs in source control, logs, project archives, or release artifacts.

Spotify is deliberately a metadata/playback-control integration. The desktop app does not capture Spotify audio, download Spotify Content, stream-rip, record, extract, or convert Spotify tracks to MP3. Use a user-selected local WAV as the sampler source.

Spotify's current official rules require an explicit loopback IP rather than `localhost`, recommend PKCE for desktop clients, and require Spotify Premium for pause/resume Player API calls. See [Redirect URIs](https://developer.spotify.com/documentation/web-api/concepts/redirect_uri), [Authorization Code with PKCE](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow), and the [Pause Playback reference](https://developer.spotify.com/documentation/web-api/reference/pause-a-users-playback).

## Build the Windows app image

```powershell
./gradlew.bat :desktop:packageWindows
```

The generated self-contained launcher is under `desktop/build/windows-app-image/ChopLab/ChopLab.exe`. This is an app-image containing a private Java runtime, not yet a signed installer or public release.

The default desktop version is `0.3.0` and can be overridden for a controlled build with `-PdesktopVersion=0.3.1`. Every GitHub PR touching the desktop target runs the Windows test/package workflow and uploads the app-image plus an EXE SHA-256 receipt. A `v*` GitHub Release also packages the Windows app-image beside the Android APK and iOS Simulator preview. This app-image is self-contained but not a single-file program; keep its runtime directory together. Signing and installer publication remain separate authorized steps.
