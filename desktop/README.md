# ChopLab Windows Desktop prototype

This target is the first local Windows proof for the ChopLab desktop direction. It provides a 4 x 4 pad surface, local WAV loading/playback, and a Spotify OAuth/API seam for metadata and playback state.

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

## Build the Windows app image

```powershell
./gradlew.bat :desktop:packageWindows
```

The generated self-contained launcher is under `desktop/build/windows-app-image/ChopLab/ChopLab.exe`. This is an app-image containing a private Java runtime, not yet a signed installer or public release.
