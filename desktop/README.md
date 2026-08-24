# ChopLab Windows Desktop

This target is the local Windows EXE for ChopLab. It renders the Android-origin おとひろい deck through Compose Multiplatform: the four-step workflow, exact source/chop/beat/save copy, 4 x 4 PAD surface, selected-PAD editor, BANK A–D dock, guided production actions, and 16-step sequencer are the same shared UI source.

## Run locally

```powershell
./gradlew.bat :desktop:test
./gradlew.bat :desktop:run
```

The packaged launcher also accepts a `.wav` or `.choplab` path as its first argument, which is used for Windows “Open with” workflows and deterministic loaded-state visual checks.

The visible 4 × 4 PAD page is playable from the computer keyboard using `1234 / QWER / ASDF / ZXCV`. A key-down triggers one assigned PAD and key-up releases that exact PAD. The mapping is intentionally inactive while a source is playing, a recording is active, or a project is loading, and Ctrl/Alt/Meta combinations remain available to Windows shortcuts. Native `ファイル`, `編集`, and `トランスポート` menus expose WAV/project open, save, export, Undo/Redo, source playback, and ALL STOP.

The desktop app supports user-selected WAV import, microphone recording, a driver-exposed Windows playback loopback such as `Stereo Mix`, PAD voice controls, 16-step transport, scratch, four-bar WAV export, Undo/Redo, manual `.choplab` save/open, and app-owned three-generation autosave. Closing first awaits startup state publication, revokes recovered-audio hydration without waiting for a device open, then invalidates and drains any admitted project publication before capturing the resulting snapshot. Recovery, project replacement and save/export status use separate ownership: manual save/export cannot cancel hydration, a failed replacement falls back to recovery, and only a successful replacement supersedes it. Recovered-audio device work has its own revision, so a master-pitch edit wins over queued hydration; a failed pitch reload stops the retained source instead of leaving old audio playing. Output-device failures retain the recovered source, publish the same actionable error as a normal WAV load, and make subsequent source playback fail safely instead of throwing. Neither a recovery-error placeholder, an unchanged successful recovery, nor the fresh placeholder shown while an explicit startup file has not loaded is persisted unless a later edit owns new work. Close stops live audio before waiting on autosave, performs teardown best-effort so an unavailable device cannot skip the final flush, flushes one scheduled save, waits for an already-running successful save without duplicating a recovery generation, and retries the latest snapshot after a failed save; a newer close revision is persisted once after an older successful body. A loopback input is never silently replaced with a microphone; unsupported drivers return a visible error.

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

The public Client ID can be supplied by `CHOPLAB_SPOTIFY_CLIENT_ID` or entered into the panel for the current process only. It is not written to disk, and a malformed environment value fails closed as unconfigured. The panel shows connection state, current playback, an explicit empty/error/populated summary for up to 20 saved-library track titles/artists, pause/resume controls, and recovery guidance for denial, timeout, missing default browser, unavailable loopback port, network failure, expired login, missing Premium or allowlist access, missing Connect devices, rate limits, and temporary provider errors.

Spotify is deliberately a metadata/playback-control integration. The desktop app does not capture Spotify audio, download Spotify Content, stream-rip, record, extract, or convert Spotify tracks to MP3. Use a user-selected local WAV as the sampler source.

Spotify's current official rules require an explicit loopback IP rather than `localhost`, permit dynamically assigned ports for a registered loopback IP literal, recommend PKCE for desktop clients, and impose Development Mode Premium/allowlist limits. See [Redirect URIs](https://developer.spotify.com/documentation/web-api/concepts/redirect_uri), [Authorization Code with PKCE](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow), [Quota Modes](https://developer.spotify.com/documentation/web-api/concepts/quota-modes), and the [Get Playback State reference](https://developer.spotify.com/documentation/web-api/reference/get-information-about-the-users-current-playback).

## Build the Windows app image

```powershell
./gradlew.bat :desktop:packageWindows
```

The generated self-contained launcher is under `desktop/build/windows-app-image/ChopLab/ChopLab.exe`. This is an app-image containing a private Java runtime, not yet a signed installer or public release.

The embedded desktop version comes from the same `choplabVersion` property used by Android/iOS release metadata. Every GitHub PR touching the desktop target runs the Windows test/package/install workflow and uploads the app-image plus an EXE SHA-256 receipt. A `v*` GitHub Release also packages the Windows app-image beside the Android APK and iOS Simulator preview.

## Install for daily use

After packaging, install the exact app-image into a version-and-hash-bound user directory and create Start Menu/Desktop shortcuts:

```powershell
$release = python scripts/release_metadata.py | ConvertFrom-Json
./scripts/install-windows-app.ps1 `
  -AppImage 'desktop/build/windows-app-image/ChopLab' `
  -Version $release.version
```

The destination is `%LOCALAPPDATA%\Programs\ChopLab\<version>-<app-image-hash-prefix>`. The underlying full SHA-256 covers every launcher, runtime, library, and resource file in deterministic relative-path order and is recorded in the install receipt. Re-running the command with identical bytes is idempotent; different or tampered bytes never overwrite/reuse that immutable app-image directory. Older app versions are retained, while the two `ChopLab.lnk` shortcuts move to the exact newly selected EXE. The installer never removes or rewrites `%LOCALAPPDATA%\ChopLab\projects`, where app-owned autosaves live.

This app-image is self-contained but not a single-file program; keep its runtime directory together. It is not a code-signed MSI/MSIX, so Windows reputation/signing remains a separate release boundary.
