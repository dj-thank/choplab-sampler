# ChopLab Windows Desktop prototype

This target is the local Windows EXE proof for the ChopLab desktop direction. Its Swing surface follows the original Android おとひろい deck: the five-step workflow, source/chop waveform, 4 x 4 PAD surface, selected-PAD editor, BANK A–D dock, guided production actions, and the arrange-stage 16-step sequencer are kept in the same visual vocabulary.

## Run locally

```powershell
./gradlew.bat :desktop:test
./gradlew.bat :desktop:run
```

The desktop app intentionally supports WAV first. It does not capture Spotify audio, download Spotify Content, convert Spotify tracks to MP3, or expose Spotify audio bytes to the sampler.

## Spotify development login

1. Register a Spotify Developer app.
2. Register the loopback redirect base allowed by Spotify: `http://127.0.0.1`.
3. Set only the public client ID in the current shell:

```powershell
$env:CHOPLAB_SPOTIFY_CLIENT_ID = 'your-public-client-id'
./gradlew.bat :desktop:run
```

The application uses Authorization Code with PKCE and keeps the first-slice token in memory only. No client secret or token belongs in source control.

Spotify is deliberately a metadata/playback-control integration. The desktop app does not capture Spotify audio, download Spotify Content, stream-rip, record, extract, or convert Spotify tracks to MP3. Use a user-selected local WAV as the sampler source.

## Build the Windows app image

```powershell
./gradlew.bat :desktop:packageWindows
```

The generated self-contained launcher is under `desktop/build/windows-app-image/ChopLab/ChopLab.exe`. This is an app-image containing a private Java runtime, not yet a signed installer or public release.

The default desktop version is `0.2.0` and can be overridden for a controlled build with `-PdesktopVersion=0.2.1`. Every GitHub PR touching the desktop target runs the Windows test/package workflow and uploads the app-image plus an EXE SHA-256 receipt. Signing, installer publication, and GitHub Release creation remain separate authorized steps.
