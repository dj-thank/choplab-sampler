# ChopLab Pixel 9a redeploy receipt — 2026-08-16

- Serial: `5A121JEBF08094`
- Device: Pixel 9a (`tegu`), ADB state `device`
- Package: `com.choplab.sampler`
- Installed version before/after: `0.13.1` (`21`)
- APK: `outputs/ChopLab-v0.13.1-mobile-waveform-local-debug.apk`
- APK SHA-256: `A257E9FBF654E7E4A265C9AAA4FF82447E20F0528474ED93BFD2352A1E588D00`
- Installed/APK signer SHA-256: `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`
- Install method: `adb install -r`; result `Success`
- Launch: `com.choplab.sampler/.MainActivity` became `topResumedActivity`

## Retained project data

The following hashes matched before and after installation:

- `autosave.choplab`: `7E9204F5291E2E80BE0397385F96FE3B0CA76F786ECA672B95F1406DDDAA5C61`
- `autosave.previous.choplab`: `2AC907FFE69600F380206D47ED90DC51FF549B602E5579855CF2F1D6AA727106`
- `autosave.previous2.choplab`: `6AE17C19B49A919737107C7C9A77B81D02C063ED21E2EE821712001EE9F0F2B3`

## Evidence boundary

DEVICE_DEPLOY_PASS and retained-data PASS are established. Pinch/pan, portrait one-hand reach,
TalkBack, STOP/BACK/UNDO/REDO, recording/playback ownership, and the ten-minute product flow
still require interactive human/device verification.

## Integrated viewport build redeploy

- Commit/shared `main`: `c2e4503f2817266c406efb6102fe7afb9b3deee2`
- APK: `app/build/outputs/apk/debug/app-debug.apk`
- APK size: `32,219,535` bytes
- APK SHA-256: `34E7C1B79B9493BADA0ACD4A7A3A26EF0842230A177A5F2E8E0B26FF55A18C2F`
- Package/version: `com.choplab.sampler` `0.13.1` (`21`)
- APK signer SHA-256: `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`
- Install: `adb -s 5A121JEBF08094 install -r ...`; result `Success`
- Autosave hashes: all three values above matched before and after this installation.
- Cold launch: `MainActivity`, `734 ms`, `topResumedActivity` confirmed.
- App-scoped fatal/ANR: none observed during the cold-launch window.

Interactive E2E stopped without further input when a different app became foreground. Portrait/rotation,
waveform gestures and handles, TalkBack traversal, STOP/BACK/UNDO/REDO recovery, playback ownership,
and relaunch remain `DEVICE_UNVERIFIED`; this receipt does not promote them to `DEVICE_PASS`.
