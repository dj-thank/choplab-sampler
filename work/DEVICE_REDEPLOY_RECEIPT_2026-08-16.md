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

## Historical adversarial-review remediation run (superseded by the fail-closed rerun below)

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

## Historical fail-closed rerun (superseded by the final run below)

The authoritative manifest is `work/device-evidence/20260816-185024-61a02ec3/manifest.json`.
It was collected from clean HEAD `61a02ec3b2ad06ed1b7fdd074f70248741af6dbb` on exact serial
`5A121JEBF08094`, after Sanporoid explicitly released the Pixel.

- App APK: `outputs/ChopLab-v0.13.1-61a02ec-waveform-evidence-debug.apk`, `31,749,939` bytes,
  SHA-256 `89E876A071043A6115A3BBEB091E071BB24BA54CBC7C0C640412741202383FD5`
- Android-test APK: `outputs/ChopLab-v0.13.1-61a02ec-waveform-evidence-androidTest.apk`, `2,409,901` bytes,
  SHA-256 `DD6C98DE009CE3F98730713E23393E9842EE16AC0B9A58D2A098F190CFD1E376`
- Candidate, installed app, and installed test identities were machine-checked: package
  `com.choplab.sampler`, version `0.13.1 (21)`, test package `com.choplab.sampler.test`, signer
  `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`.
- `adb install -r` returned `Success`; app and test `base.apk` readbacks matched their candidate hashes.
- All three autosave hashes matched before/after; any mismatch now terminates the runner.
- Instrumentation reported `OK (3 tests)` in `4.536 s`, including marker endpoint/clipping and
  boundary no-op coverage in addition to true two-pointer injection and reversible actions.
- The timestamp-bounded log window contained zero app-scoped fatal/ANR matches.
- Phone state was snapshotted and restored: Launcher before/final, media volume `14` before/final,
  automatic rotation `1` before/final. ChopLab was force-stopped and the Pixel was explicitly returned
  to Sanporoid; uninstall and clear-data were never used.

This promotes the deterministic contracts and exact deployment chain to `INSTRUMENTATION_PASS`, not full
`DEVICE_PASS`. Actual TalkBack speech/focus traversal, real microphone recording/contention, subjective
one-hand comfort, and audio quality remain explicitly unclaimed and require separate human-supervised evidence.

## Final authoritative fail-closed rerun

- Manifest: `work/device-evidence/20260816-185953-b3579f05/manifest.json`
- Clean app source HEAD: `b3579f0592738ccf2e95f10d1f0bba42cc343578`
- App output: `outputs/ChopLab-v0.13.1-b3579f0-waveform-evidence-debug.apk`, SHA-256
  `89E876A071043A6115A3BBEB091E071BB24BA54CBC7C0C640412741202383FD5`
- Test output: `outputs/ChopLab-v0.13.1-b3579f0-waveform-evidence-androidTest.apk`, SHA-256
  `DE97432A1C1278E7661FD656DFCC054CFABA6A4BCC6D9DECF44B810564F83EC8`
- Exact serial/device, app package/version, app/test hashes and signers, `install -r`, both readbacks,
  autosave preservation, and final state restoration all passed fail-closed checks.
- Instrumentation: `OK (3 tests)` in `5.138 s`; both lower and upper chop-marker endpoints are covered.
- App-scoped fatal/ANR matches: `0`.
- Before/final: Nexus Launcher, media volume `14`, automatic rotation `1`; ChopLab force-stopped and
  the Pixel explicitly returned to Sanporoid with no uninstall, clear-data, or microphone recording.

The same gate boundary applies: `LOCAL_PASS` and `INSTRUMENTATION_PASS` are established. Full
`DEVICE_PASS` and `HUMAN_GO` remain withheld pending actual TalkBack speech/focus traversal, real microphone
contention, and subjective one-hand/audio evaluation.

## Final clustered-handle deployment and bounded physical continuation

- Authoritative manifest: `work/device-evidence/20260816-195805-6943b5ea/manifest.json`
- Clean app source HEAD/tree: `6943b5ea92bbf9bbe2a2da51c27cc2bb4d2f059b` /
  `d03b3bcab270571021a709a7086d4e309b0af7dc`
- App APK SHA-256: `9A3997B78D309A2B53C78A6B0DB2970D02E08DC656314B8F91F0A2F8BF1C9162`
- Android-test APK SHA-256: `B9FFDE5C7923DE807DB696127BB11569978A6F23DDA86F82BC8E90B91C5DA4D0`
- App/test signer SHA-256: `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`
- Exact serial/device, package/version, signer, serial-fixed `adb install -r`, both installed-base readbacks,
  three autosave hashes, timestamp-bounded fatal/ANR scan, and final phone state passed the fail-closed runner.
- Instrumentation: `OK (3 tests)` in `5.276 s`. The deterministic fixture covers true two-pointer pinch/pan,
  viewport reset/custom actions, 48 dp S/E and clustered marker targets, reversible actions, and lower/upper
  marker endpoint behavior without reading or mutating the user's project.
- With the real TalkBack service and touch exploration enabled, the corrected installed app exposed S, E,
  chop-1, and chop-2 concurrently in the Android accessibility hierarchy. A real green accessibility-focus
  ring was observed over the clustered S/marker region, closing the earlier production-only S-occlusion bug.
  ADB-generated swipe input was not consistently interpreted as TalkBack next-item navigation, so spoken
  S-to-E-to-marker traversal and service-dispatched custom actions remain unclaimed.
- A real microphone capture was started with media volume `0` and no external transfer. While capture owned
  audio, `PLAY SONG` was rejected with the status `マイク素材録音中です。STOPしてから音を鳴らしてください`.
  Android BACK returned safely to Launcher and cancelled the pending capture. After relaunch, all three
  autosave hashes were byte-identical to their pre-capture values, so no test recording entered project state.
- Final readback: ChopLab force-stopped, Nexus Launcher top-resumed, TalkBack service `null`, accessibility
  enabled `0`, touch exploration `null`, media volume `0`, automatic rotation `1`, and zero app-scoped
  fatal/ANR matches. The accessibility stream was verified at the saved value `9` while TalkBack was active;
  Android reports its inactive alias value `1` after the service is disabled.

`LOCAL_PASS`, exact `DEVICE_DEPLOY_PASS`, retained-data PASS, deterministic `INSTRUMENTATION_PASS`, actual
TalkBack focus-path evidence, and bounded microphone/playback exclusion are established. Full `DEVICE_PASS`
is still withheld because actual spoken focus order/custom-action dispatch and every real recording versus
preview/loop transition were not directly proven. Subjective one-hand feel and audio quality remain
`HUMAN_GO`; provider/public gates are not claimed.
