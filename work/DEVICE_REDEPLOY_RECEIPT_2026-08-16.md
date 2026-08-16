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

## Historical accessible-waveform device run (superseded by the authoritative rerun below)

- Source baseline: `ebdce0e8d1a8294f0b03a9ddd4c3e2dec6c5720a`; final commit is the commit containing this section.
- Production APK: `outputs/ChopLab-v0.13.1-waveform-accessibility-final-debug.apk`
- Production APK SHA-256 / installed `base.apk` readback: `2618F161128197DBD517E55E84BB9FF2DCB6BA591E01225A0254888F1E277FB1` (exact match)
- Production APK size: `31,743,455` bytes
- Package/version: `com.choplab.sampler` `0.13.1` (`21`)
- Production and installed signer SHA-256: `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`
- Install method/result: serial-fixed `adb install -r`, `Success`; no uninstall or clear-data.
- Final test APK: `outputs/ChopLab-v0.13.1-waveform-accessibility-final-v2-androidTest.apk`
- Final test APK SHA-256: `AADDD7556633B185479B8D15207E6A79DC60A0EF672303581BB3F7400D5E7520`
- Test APK signer SHA-256: `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`

### Objective device results

- PASS: portrait 1080x2424 main flow fit; all major controls were present without a required scroll. Rotation remained portrait-locked and automatic rotation was restored to `1`.
- PASS: real two-pointer Compose injection changed the viewport for pinch and horizontal pan.
- PASS: TalkBack semantics announced whole/zoomed viewport state; previous range, next range, and reset custom actions executed and changed/restored the state.
- PASS: S, E, and chop-1 exposed at least 48 dp device bounds, frame state descriptions, and reversible nudge actions.
- PASS: SAVE-screen UNDO and REDO both reported the expected restored/redone status; BACK and ALL STOP returned safely without a crash.
- PASS: beat playback, selected-source loop, source sampling preview, and ALL STOP transitions were exercised at media volume 0. Starting beat playback while the selected-source loop was active stopped the prior owner.
- PASS: force-stop/relaunch restored `MainActivity` in stopped state; no app-scoped fatal exception or ANR was found in the observation window.
- PASS: the final instrumentation run completed `OK (2 tests)` in `22.495 s` on Pixel 9a serial `5A121JEBF08094`.

### Data boundary

Each production `adb install -r` preserved the three pre-install autosave hashes recorded above. The reversible
E2E edit intentionally caused normal autosave-generation rotation, so the final three archive byte hashes are
not claimed to equal the pre-E2E hashes. After the run, the temporary `autosave.pending.choplab` completed and
disappeared; the current archive's `project.txt` was read on-device and its `steps` row was restored to empty.
No project file was overwritten or deleted outside the app.

### Final gate

`LOCAL_PASS` and the objective device checks listed above are established. Full `DEVICE_PASS` remains withheld:
the run did not prove an actual spoken TalkBack focus traversal/order with the accessibility service enabled, and
real microphone recording/recording-playback contention was intentionally not initiated. Subjective one-hand feel
and audio quality remain `HUMAN_GO` work. `PROVIDER_PASS`, `PUBLIC_PASS`, and `HUMAN_GO` are not claimed.

## Adversarial-review remediation rerun

The authoritative rerun is `work/device-evidence/20260816-184328-d01a299a/manifest.json`.
Unlike the earlier narrative-only receipt, this run binds one clean Git object to the build, APKs, device,
installation, readback, test output, data checks, log window, and final phone state.

- Git HEAD: `d01a299a5af46ed5a582822f7855efcf88560513`
- Git tree: `75436e679237005f852dc2abc479a23946557eca`
- Tracked worktree at build: clean
- App APK: `outputs/ChopLab-v0.13.1-d01a299-waveform-evidence-debug.apk`
- App APK size/SHA-256: `31,749,939` / `89E876A071043A6115A3BBEB091E071BB24BA54CBC7C0C640412741202383FD5`
- Test APK: `outputs/ChopLab-v0.13.1-d01a299-waveform-evidence-androidTest.apk`
- Test APK size/SHA-256: `2,409,761` / `F0A7F98DC1149F839C74E621FF18A9B699885D966113D727120FD299AACECCEB`
- App/test/installed signer SHA-256: `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`
- Install: serial-fixed data-preserving `adb install -r`, `Success`
- Installed `base.apk` readback SHA-256: exact app-APK match (`89E876...383FD5`)
- Autosave before/after: all three lines byte-identical; no pending archive in the final project listing
- Instrumentation: deterministic in-memory fixture, `OK (3 tests)`, `4.793 s`
- App-scoped fatal/ANR matches in the timestamp-bounded run window: `0`
- Final phone state: ChopLab force-stopped, Nexus Launcher top-resumed, media volume `14`, automatic rotation `1`

The deterministic tests do not open `MainActivity`, read autosave, import user audio, record microphone input,
or claim spoken TalkBack output. They establish `INSTRUMENTATION_PASS` for Compose gesture/semantics/geometry
contracts. Spoken TalkBack focus order, physical one-hand comfort, and subjective audio remain separate checks;
full `DEVICE_PASS` and `HUMAN_GO` remain withheld.
