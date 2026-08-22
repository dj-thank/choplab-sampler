# ChopLab Windows Desktop

This target is the local Windows EXE for ChopLab. It renders the Android-origin おとひろい deck through Compose Multiplatform: the four-step workflow, exact source/chop/beat/save copy, 4 x 4 PAD surface, selected-PAD editor, BANK A–D dock, guided production actions, and 16-step sequencer are the same shared UI source.

## Run locally

```powershell
./gradlew.bat :desktop:test
./gradlew.bat :desktop:run
```

The packaged launcher also accepts a `.wav` or `.choplab` path as its first argument, which is used for Windows “Open with” workflows and deterministic loaded-state visual checks.

The desktop app supports user-selected WAV import, microphone recording, a driver-exposed Windows playback loopback such as `Stereo Mix`, PAD voice controls, 16-step transport, scratch, four-bar WAV export, Undo/Redo, manual `.choplab` save/open, and app-owned three-generation autosave. A loopback input is never silently replaced with a microphone; unsupported drivers return a visible error.

Use `診断 > Windows 音声エンドポイント` to run the JNA/WASAPI endpoint probe. It reports the current shared-mode render/capture formats when available and an explicit unavailable reason otherwise; it does not record audio.

## Spotify development login

1. Register a Spotify Developer app for Web API use.
2. Register the dynamic-port loopback redirect `http://127.0.0.1/callback` (no port in the dashboard entry; the app adds its one-shot local port).
3. In Development Mode, make sure the app owner has Spotify Premium and the intended Spotify account is on the app allowlist. Development Mode is limited to five authenticated users.
4. Set only the public client ID in the current shell:

```powershell
$env:CHOPLAB_SPOTIFY_CLIENT_ID = 'your-public-client-id'
./gradlew.bat :desktop:run
```

Start `連携 > Spotify Connect パネル` from the native Windows menu. The panel makes the setup state, OAuth progress, retryable errors, current playback, library metadata, and Connect control state visible. It lets the user cancel an in-progress login and treats cancellation or disconnect as authoritative: a late callback cannot reconnect the session. The OAuth session uses Authorization Code with PKCE and keeps access/refresh tokens in memory only. No client secret or token belongs in source control, logs, project archives, or release artifacts.

The public Client ID can be supplied by `CHOPLAB_SPOTIFY_CLIENT_ID` or entered into the panel for the current process only. It is not written to disk. The panel shows connection state, current playback, up to 20 saved-library track titles/artists, pause/resume controls, and recovery guidance for expired login, missing Premium or allowlist access, missing Connect devices, rate limits, and temporary provider errors.

Spotify is deliberately a metadata/playback-control integration. The desktop app does not capture Spotify audio, download Spotify Content, stream-rip, record, extract, or convert Spotify tracks to MP3. Use a user-selected local WAV as the sampler source.

Spotify's current official rules require an explicit loopback IP rather than `localhost`, permit dynamically assigned ports for a registered loopback IP literal, recommend PKCE for desktop clients, and impose Development Mode Premium/allowlist limits. See [Redirect URIs](https://developer.spotify.com/documentation/web-api/concepts/redirect_uri), [Authorization Code with PKCE](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow), [Quota Modes](https://developer.spotify.com/documentation/web-api/concepts/quota-modes), and the [Get Playback State reference](https://developer.spotify.com/documentation/web-api/reference/get-information-about-the-users-current-playback).

## Build the Windows app image

```powershell
./gradlew.bat :desktop:packageWindows
```

The generated self-contained launcher is under `desktop/build/windows-app-image/ChopLab/ChopLab.exe`. This is an app-image containing a private Java runtime, not yet a signed installer or public release.

The default desktop version is `0.3.0` and can be overridden for a controlled build with `-PdesktopVersion=0.3.1`. Every GitHub PR touching the desktop target runs the Windows test/package workflow and uploads the app-image plus an EXE SHA-256 receipt. A `v*` GitHub Release also packages the Windows app-image beside the Android APK and iOS Simulator preview. This app-image is self-contained but not a single-file program; keep its runtime directory together. Signing and installer publication remain separate authorized steps.
