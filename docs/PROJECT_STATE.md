# Project state

## Current snapshot — 2026-08-24 reverse PAD resampling tail candidate

This snapshot records one bounded JVM/Desktop PAD-rendering correction on the exact main that merged Desktop recorder startup cancellation. It changes reverse non-loop host PCM frame selection plus focused no-device regressions and preserves the pre-existing LOOP renderer/playhead contract; Android realtime code, forward playback, DSP controls, project schema and Java Sound resource ownership are unchanged.

- Observed at: `2026-08-24`.
- Source state: reachable product commit `1b8b424036f76dabbb0671f4954ed079f32b9247`, tree `614c93f60c1e55e8df097302ef0e22250ddf7d37`, advancing the product lineage rooted at merged `main@a0b356c2e5820b7f9a8288ebcdd555c19e0cb6b5`; the later evidence commit is documentation-only. Renderer/test blobs are `ffd0be29db89d9ec75d553ab88c6d2d832e77e75` / `315ec9b078f9a8ed76f1783dce53877cbb430a53`; Desktop adapter/test blobs are `95a69a20e1e7c1667bcfa0f759d902eea73327fc` / `e4ccb83e52cdbde65f28d4cdf020cae7ffa72f2f`.
- Reproduced defect: `PadPcmRenderer` allocated every direction from `ceil(rangeLength / sourceStep)`, but reverse playback starts at `endFrame - 1`, not at the exclusive end. With 64 source frames resampled from 48 to 60 kHz (`sourceStep=0.8`), only 79 reverse positions are valid; the old 80-frame array broke out at position 99.8 and retained one synthetic trailing zero.
- Repair: reverse one-shot/gate count and PCM rendering now advance the same `VoicePlaybackCursor` used by realtime. The first pass counts positions, then resets the cursor before allocating and rendering the exact result, preserving floating-point operation order without an oversized array or trim-copy. Reverse LOOP deliberately retains main's `ceil(rangeLength / sourceStep)` allocation and direct end-to-start positions; no wrapped cursor or changed playhead mapping is introduced. Windows resolves `forceLoop` before rendering, so the one-shot correction cannot shorten a Clip that the Desktop intends to repeat. The existing forward path remains unchanged.
- Focused regressions: the 48→60 kHz fixture requires 79 realtime/host frames and a nonzero final valid PCM instead of the old 80-element zero tail. The review boundary at 8→48 kHz and pitch −12 fixes repeated-subtraction rounding to 12 frames. The reported two-frame pitch −5 non-integral fixture requires reverse one-shot 2 frames while LOOP retains main's three-element finite render and terminal boundary zero; the Desktop no-device seam requires `forceLoop` to retain that same legacy boundary. Arbitrary-pitch loop phase continuity and playhead parity remain separate pre-existing work, not claims of this one-shot/gate repair.
- Fresh local checks: arithmetic reproduction, Python policy 39/39, public-surface current/history over 396 candidates, conflict-marker scan and `git diff --check` PASS. Uncached Gradle 9.7.1 is unavailable in the sandbox, so hosted JVM/Windows execution is required.
- Gate ceiling: source/static candidate only. Hosted JVM compilation/tests, exact-head clean review and all required PR workflows must pass before merge; audible reverse-tail quality, physical Windows output, public artifacts and `HUMAN_GO` remain unclaimed.

## Previous snapshot — 2026-08-24 Desktop recorder startup cancellation merged

This snapshot records one bounded Desktop capture-lifecycle correction now merged as PR #73. It changes only the `TargetDataLine` recorder and its host regression; audio format, recording limits, controller behavior, project schema and previously merged import/transport behavior are unchanged.

- Observed at: `2026-08-24T21:42+09:00`.
- Source state: merged `main@a0b356c2e5820b7f9a8288ebcdd555c19e0cb6b5`, tree `6c00c73d23ac419f12162f3f465812136b403252`; product repair `27283f8bc6ace63a27a9ea84e60db2abee5b4bd6` / tree `4ffc5f746f60cc67b71c29bb1f8a5b5db1a227ad` remains the runtime/test anchor.
- Reproduced defect: `stop()` could run while `TargetDataLine.start()` was blocked, observe no published line or worker, and return. Startup could then publish `running=true` and launch the capture worker after that stop/close request, leaving recording active without a corresponding live session owner.
- Repair: one lifecycle lock now protects `starting`, a latched stop request, the opened startup line and final line/worker publication. STOP atomically claims and clears a pending line, then closes it outside the lock to unblock native start; startup cleanup closes the line only when it still owns that exact reference. A start that wins publishes and starts its worker inside the same boundary so stop observes the exact active resources. Cancellation clears temporary state and deletes the incomplete WAV.
- Deterministic regression: a proxy line blocks inside `TargetDataLine.start()`, and only its `close()` releases that gate. The test issues stop and requires a failed start with `録音の開始はキャンセルされました`, zero `read` calls, exact-once open/start/close, idle recorder state and removal of the partial output.
- Hosted evidence: exact head `a1cc5a7e832f2faf11b64c03ed1100453d1a9daa` received clean Codex review comment `5395404451`, had zero unresolved threads, and passed Android `32728698800`, Windows `32728698763`, iOS `32728698759` and supply-chain `32728698801` before expected-head squash merge.
- Gate ceiling: merged source and hosted CI only. Physical Windows microphone timing, audio contents/quality, device removal, publication and `HUMAN_GO` remain unclaimed.

## Previous snapshot — 2026-08-24 shared Android/Desktop import-name latest-main integration

This snapshot records one bounded Desktop data-loss prevention follow-up integrated with the main that merged transport step-zero ordering. It moves the reviewed Android naming rule to shared production code and applies it to Desktop decode before PCM enters project state; audio bytes, archive schema, provider/filesystem I/O and #65 transport behavior are unchanged.

- Observed at: `2026-08-24T21:10+09:00`.
- Source state: reachable integration product `e0d3fa1df5862bcfa038812bb12ecf6d2c45911e`, tree `1558c4a2331e8ef3a7b2809b38f264e671c188a6`, with parents prior clean PR head `d2c99fc2d4bb9c84bf6366a7f2568cca88294422` and merged `main@3072eedd84b357f4ccd22c611dcc7b7f22f92874`; the later evidence commit is documentation-only.
- Main preservation: #65 controller/transport/test blobs remain exact `5bc74ba` / `c92613c` / `10b3600` / `073696e`; #68 runtime/test blobs remain exact `de686ba` / `9d51556`. The #70 provider/URI name-selection behavior and archive regression remain intact through the shared seam.
- Reproduced defect: `DesktopWavDecoder` published unbounded `File.name` values into `PcmAudio`, while `ProjectArchiveCodec` rejects blank asset names and names above 240 UTF-16 code units during read-back. A Desktop import could therefore succeed, then make atomic autosave and manual-save verification fail.
- Repair: `persistableAudioDisplayName` now lives in shared model production code. Android retains provider/URI/final fallback priority; Desktop applies the same nonblank, post-truncation fallback and surrogate-pair contract before returning decoded PCM.
- Regression: shared common tests bind candidate priority, whitespace-only bounded-prefix fallback and surrogate-safe truncation. Desktop feeds a 240-space prefix plus visible suffix, requires `sample`, and round trips the decoded state through `ProjectArchiveCodec`; the existing Android archive regression now exercises the same shared function.
- Prior exact-head evidence: `d2c99fc` received a clean Codex review with zero threads and passed Android `32724477061`, Windows `32724477012`, iOS `32724476941` and supply-chain `32724476968`. Those receipts predate merged #65 and do not replace fresh hosted execution for this integration.
- Fresh local checks: Python policy 39/39, public-surface 395 candidates, exact product/main blob comparisons, conflict-marker scan and `git diff --check` PASS. Gradle 9.7.1 remains unavailable locally, so fresh hosted Android/Windows/shared execution is required.
- Gate ceiling: source/static latest-main integration plus revision-bound prior-head evidence only. Real filesystem/provider imports, autosave recovery, device playback, publication and `HUMAN_GO` remain unclaimed.

## Previous snapshot — 2026-08-24 desktop transport step-zero ordering candidate

- Observed at: `2026-08-24T20:47+09:00`.
- Source state: reachable integration product commit `08fb123888fb840496d34e4ba7a586013e1305f6`, tree `dc9a9e8563e18168c5d20af9084ffaab01f0f742`, with parents prior exact PR head `5d630c8a769e4b840bba9914f59bc0ec1c705638` and merged `main@333088147cdc77932efc41b90a08eb37e1c1cf42`. This candidate is not yet merged into main. Its four Desktop product/test blobs remain exact, and merged #66 timing, #67 Desktop import boundary, #68 realtime PAD retirement and #70 Android import-name source/tests/docs are retained; the later evidence commit is documentation-only.
- Root cause: the Windows transport worker could synchronously reach `onTransportStep(0)` after `Thread.start()` but before `DesktopSamplerController` published `transportPlaying=true`. The controller then rejected the callback as stale, delaying audible playback until step 1 while the UI already showed step 0.
- Repair: `DesktopTransport.start` now executes a caller readiness barrier before starting its worker. Normal start and scratch-return start publish playing/current-step state through one controller helper, so Java's thread-start happens-before boundary makes step 0 observable exactly once rather than scheduler-dependent. If worker startup then fails, scratch return restores the pre-start recording arm instead of leaving the callback's provisional disarm behind.
- Regression scope: a transport test records that the readiness barrier precedes step 0; one controller test uses one audible step-0 PAD and verifies one hit plus coherent stop state, while another injects worker-start failure after the scratch-return callback and requires the recording arm to survive. Tests use fake audio only and do not open Java Sound hardware.
- Prior exact-head evidence: remote head `0a9def1816bffc903319b5358249f71b43f4c2cf` received a clean exact-head Codex re-review and workflow runs `32720971504` (Android), `32720971498` (Windows), `32720971362` (iOS), and `32720971385` (supply chain) all completed successfully. The latest-main integration still requires its own hosted read-back.
- Fresh local checks: Python policy tests 39/39, public-surface scan over 394 candidates, six Android XML parses, wrapper checksum/UTF-8 policy, exact equality of all four reviewed Desktop product/test files, and `git diff --check` passed. The focused Gradle tests could not run because Gradle 9.7.1 is not cached; hosted `:desktop:test` remains required for this integrated head.
- Gate ceiling: source/static latest-main integration plus revision-bound prior-head hosted evidence only. Windows scheduling beyond the deterministic contract, audible timing/latency, device removal, provider, public artifact, and Human acceptance are not inferred.

## Previous snapshot — 2026-08-24 Android import-name persistence integrated candidate

This snapshot records one bounded Android import-admission correction on the exact main that merged realtime PAD terminal-sample retirement. It changes only the display name published with decoded PCM; audio bytes, provider I/O, archive schema and #68 callback behavior are unchanged.

- Observed at: `2026-08-24T20:30+09:00`.
- Source state: reachable integration product commit `b7364eeab02cccdde260d65337cf9a403f9a6a5d`, tree `dae6252f28c70f56f817c1d5d4e18374de882ea4`, with parents prior reviewed PR head `caea87f823d96f091f8515dd0eb3e86f18d9d27e` and merged `main@2786c3722a9e56fa299d2a88f009d882545b0768`; the later evidence commit is documentation-only.
- Main preservation: #68 runtime/test blobs remain exact `de686ba13d61b83d161e0480849ccef484fc03f6` / `9d515564f99cf2cd461604b60d2ba2131cc6f4be`, and its merged docs are retained below. The reviewed import-name product/test blobs remain exact `2da4fa2a90b0e4e60020202c88e1a4a14af6f4a8` / `4ba02e5520399eeb10636d02beeada72d1ef79c1`.
- Reproduced defect: Android accepted a blank or arbitrarily long provider `DISPLAY_NAME` into `PcmAudio`, while `ProjectArchiveCodec` rejects blank asset names and names above `ProjectLimits.MAX_ASSET_NAME_CHARS` (240 UTF-16 code units). Import could therefore appear successful and only fail at autosave/manual save after later edits.
- Repair: provider and URI fallback names are canonicalized before PCM publication. Blank names fall through to a nonblank URI segment and then `sample`; long names are bounded without splitting a surrogate pair, and a bounded whitespace-only prefix re-enters fallback.
- Regression: the focused Android unit test covers blank inputs, the 240-spaces-plus-visible-character review case and a surrogate pair straddling code-unit 240, then requires the resulting `PcmAudio` name to round trip exactly through `ProjectArchiveCodec`.
- Prior-head review/evidence: exact pre-integration head `caea87f` received a clean Codex re-review after the whitespace-boundary repair; its Android and supply-chain workflows passed. These receipts do not replace fresh hosted execution for the integrated head.
- Fresh local checks: Python policy suite 39/39, public-surface scan 394 candidates, exact product/main blob comparisons and `git diff --check` PASS. Gradle 9.7.1 remains unavailable in the local sandbox, so hosted Android compilation/tests are the executable gate.
- Gate ceiling: source/static latest-main integration plus revision-bound prior-head evidence only. No provider-specific naming, device import, autosave recovery, audible output, public release or `HUMAN_GO` evidence is inferred.

## Previous snapshot — 2026-08-24 realtime PAD terminal-sample retirement candidate

This snapshot records a bounded Android realtime mixer correction independently from the completed offline pattern/master repair. It is based on the exact merged fractional-timing main and preserves that timing implementation and evidence.

- Observed at: `2026-08-24T20:08+09:00`.
- Source state: reachable integration product commit `5dd3d6613fcc99577996a28fabb06e7f7615b02f`, tree `c6a7e9b9cfdaa1f109da6fe0b568424c406e9170`, with parents prior reviewed PR head `72dbaaa1b79d1c0f92b4213c65f915908b3e894e` and merged `main@3de1cc5de2fc950ee7e24dfac29a2bc926cf1553`; the later evidence commit is documentation-only.
- Integration boundary: the two reviewed runtime/test blobs are byte-identical to original product `eabd063`; merged #66 carried-residual timing and merged #67 Desktop sample-rate admission source/tests/docs are retained.
- Runtime correction: the Android pooled-PAD mixer now adds the value returned by `Voice.render()` before deactivating a voice that became finished during that same call. Loop-monitor identity is captured before rendering and its frame is published only while the voice remains active, preserving the prior loop-state contract.
- Realtime boundary: the callback helper accepts and returns primitives plus an existing pooled `Voice`; it performs no allocation, lock, I/O, logging, Android UI call or heavy JNI work.
- Focused regression: the established reverse, pitched and filtered parity fixture reaches retirement after exactly 403 rendered frames. The final returned sample remains in the runtime mix as PCM `-61`, then the pool slot is inactive and finished.
- Fresh local checks: Python policy suite 39/39, public-surface scan 394 candidates, deterministic float/PCM fixture reproduction and `git diff --check` PASS. `./scripts/doctor.sh` found Java 17/Git and reported the expected absent Android SDK/ADB. `./scripts/validate_project.sh` passed its public-surface and executable-mode phases, then the uncached Gradle 9.7.1 distribution could not be provisioned; the focused Android unit task reached the same network-unreachable prerequisite with a writable task-local cache.
- Gate ceiling: source/static candidate only. Hosted Android compilation/unit tests, APK/device execution, audible terminal behavior, audio quality, latency, provider/public release and `HUMAN_GO` remain unclaimed.

## Previous snapshot — 2026-08-24 Desktop import sample-rate admission candidate

This snapshot records one bounded Desktop decoder admission correction. It changes neither decoded PCM shape nor the project archive schema.

- Observed at: `2026-08-24T19:49+09:00`.
- Source state: reachable product commit `3ad2bd9eda0561b0f1cf304b477ca726edd1becc`, tree `8272a51c4b537dd06ec02e0ff780e574babe4d46`, based directly on merged `main@3260f5cb560e2cbd2d245c7eee6f96ecb3540ddc`. The final follow-up is documentation-only and binds this immutable source.
- Reproduced defect: Java Sound could expose a finite source rate above the shared 192 kHz project ceiling. Desktop decoded and published that PCM, but the archive codec later rejected the same state, so autosave/manual save could fail only after the user had edited an unsupported project.
- Repair: both the external `decode` boundary and the internal streaming reader use one validator backed by `ProjectLimits.MAX_SAMPLE_RATE`. Exact 192 kHz remains accepted; 192,001 Hz and higher, sub-8 kHz and non-finite rates fail before PCM payload materialization or state publication.
- Regression: a focused Desktop test accepts the exact shared ceiling. A second test wraps an unsupported 192,001 Hz stream in a fail-on-read source and requires `IllegalArgumentException` with zero payload reads.
- Fresh local checks: Python policy suite 39/39, public-surface scan 394 candidates, conflict-marker scan and `git diff --check` PASS. The Gradle distribution is unavailable in this container, so hosted Desktop compilation/tests remain the executable gate.
- Gate ceiling: source/static candidate only. No Windows import, audio-quality, archive recovery, physical device, provider or `HUMAN_GO` evidence is inferred.

## Previous snapshot — 2026-08-24 fractional pattern-timing candidate

This snapshot records a focused realtime/offline timing correction. It changes pattern event quantization and exported frame count only; the merged constrained PAD gesture repair, decode boundary and all earlier histories remain intact below.

- Observed at: `2026-08-24T18:43+09:00`.
- Product source: reachable integration commit `0b75c71112cd004d9fa7ca34a6e916742c5d8825`, tree `18968b17b4c8a7d97e868dde4bc633e61e1da7c9`, joining the prior PR head with `main@6b645ca5005f905e93c572edfc1d375d4a6eeeb5`. Its four audio product/test files preserve the exact reviewed timing implementation; the later evidence commit is documentation-only.
- Reproduced defects: at 48 kHz / 92 BPM / 54% swing, old offline truncation started step 1 at frame 8,452 instead of realtime 8,453 and per-bar rounding made four bars 500,872 frames instead of 500,870. The first ceiling repair still accumulated a growing absolute `Double`, which is not IEEE-equivalent to realtime's carried countdown: at 120 BPM / 55% swing it scheduled step 3 at 18,600 instead of 18,601, and at 40 BPM / 56% swing it ended one bar at 288,000 instead of the next realtime boundary at 288,001.
- Repair: shared `scheduledFrameAtOrAfter` defines the first whole-frame advance that is not earlier than a fractional countdown. Offline scheduling now performs realtime's add-step-length, ceiling-advance, subtract-advance recurrence and carries the resulting remainder through every step and bar.
- Regression scope: shared exact/inexact ceiling coverage remains. End-to-end WAV tests fix the 92 BPM / 54% four-bar onsets and 500,870-frame length, the early 120 BPM / 55% step-3 boundary at 18,601, and the 40 BPM / 56% one-bar/header length at 288,001.
- Prior exact-head evidence: remote head `786e2e76feb1e5cad544491b039431cd61befdb7` received a clean exact-head Codex re-review and passed Android, Windows, iOS, and supply-chain workflows. The current integration still requires its own hosted read-back.
- Local evidence: Python policy tests 39/39, public-surface scan over 394 candidates, six Android XML parses, wrapper checksum/text policy, residual-schedule arithmetic (`step 3 = 18,601`, one bar `= 288,001`), exact equality of all four reviewed audio product/test files, and `git diff --check` passed. Gradle 9.7.1 is not cached, so Kotlin/Gradle execution remains hosted.
- Gate ceiling: source/static latest-main integration plus revision-bound prior-head hosted evidence only. Physical playback timing, audio perception, provider, public release and `HUMAN_GO` remain unclaimed.

## Previous snapshot — 2026-08-24 constrained PAD gesture and semantics review repair

This snapshot records the bounded PR #62 review repair separately from the earlier exact APK and emulator receipts. It does not reuse those earlier binaries as proof of the new pointer behavior.

- Observed at: `2026-08-24T19:00+09:00`.
- Source state: reachable product/verification commit `5b9592ff27608166a99fe77af0876ad1d6b917f5`, tree `97569b6a07fb74f9ee5b59101c0ea27059259a1b`, integrating exact prior PR #62 head `9a844c667e7372df970004aa1583ffbc3c6d6ceb` with merged `main@ae77cd92d3ee14baecc01f4862c639328bae43bb`. The later evidence commit is documentation-only.
- Pointer arbitration: only a PAD embedded in the large-text BEAT scroll body defers selection and playback while the parent can claim a drag. ONE SHOT commits on completed tap. GATE waits through a short 120 ms scroll-classification window, then remains active until pointer-up; if opening TRIM replaces and cancels the pointer-input node after trigger, the cancellation path still releases the owned GATE exactly once. `pad.playMode` is a pointer-input key, so an in-place ONE SHOT→GATE update restarts the handler instead of retaining stale completed-tap routing. A parent-consumed drag before activation sends no `selectPlayablePad`, `triggerPad` or `releasePad`. Non-scroll PAD surfaces retain immediate press-down triggering.
- Accessibility truth: every assigned PAD description now includes both play mode and content kind. `LOOP`, `DRM` and `VOX` therefore remain available to accessibility services even when a compact 48 dp large-text cell omits its secondary visual caption.
- Regression coverage: the Compose fixture first observes ONE SHOT on the already composed PAD, updates that same PAD to GATE, then proves an 80 ms pre-activation swipe moves the workspace with zero controller calls. A long real-pointer GATE press opens TRIM, thereby replacing the gesture node, and requires one trigger followed by exactly one release on a later test-clock frame. Focused host assertions cover LOOP, DRM and VOX descriptions, and the existing device fixture checks actual large-text DRM semantics and 48 dp bounds.
- Fresh local checks: Python policy suite 39/39, public-surface scan 394 candidates, six Android XML files and `git diff --check` PASS. Exact remote head `9a844c6` exposed one Android compile failure because the new `longClick` extension lacked the Compose Test import; the local candidate now imports the same API already compiled by `SourceWaveformDeviceTest`. Focused Gradle execution could not initialize the uncached Gradle 9.7.1 distribution in this environment; hosted Gradle and device execution remain required.
- Gate ceiling: source/static candidate only. The prior closeout's exact APK/emulator evidence remains historical and does not prove this new gesture arbitration, TalkBack speech, touch feel, audio onset, provider checks or `HUMAN_GO`.

## Previous snapshot — 2026-08-24 guided first-screen integrated and constrained-flow closeout

This snapshot is the current product behavior anchor for first entry and shared navigation. Moving GitHub `main` and hosted workflow identities are provider read-backs, while the immutable implementation claim is bound to the product commit below.

- Observed at: `2026-08-24T16:45+09:00`.
- Source state: isolated closeout branch `codex/choplab-screen-flow-closeout`, reviewed product commit `07f8dcf3c2b0fe17c1e1d8ed3d135728c18f0c96`, tree `dcd5969bf72ceab1facbceb43c3fe63a9df99b4d`, based on merged product main `495ddc9dfac02a9e72160c637f65d2b53d6829ce`. The dirty canonical checkout remains untouched.
- GitHub integration: PR #52 merged as `495ddc9`; its exact main workflows Android `32699830912`, Windows `32699830918`, iOS `32699830897` and Supply-chain `32699830925` passed. The closeout branch contains the subsequent constrained-flow review repairs and awaits its own PR/main read-back.
- First entry: a pristine project now leads with own audio, previous project and recording choices, and names the included `DUSTY JAZZデモ` as a separate playable route. Loaded/loading/recording states retain their waveform and safety controls.
- Shared navigation: `入れる → チョップ → ビート → 保存` remains the only workflow model. Font scale 1.2+ uses a simplified header, a two-by-two stage strip and multi-line status. CAPTURE scrolls for large text or compact landscape; large-text BEAT quick/detail bodies use bounded scrolling with explicit waveform and 48 dp-safe PAD-grid heights while global chrome stays fixed.
- Desktop correction: BEAT entry delegates to the shared playable-PAD transition, so PAD `B-01`, BANK B and page `01–16` remain coherent.
- Fresh local gate: clean 191-task Gradle gate for the constrained-flow repair plus final 184-task cross-platform gate PASS. Shared Android host 25, shared Desktop 25, Android unit 234, JVM-core 52 and Desktop 77 tests; failures/errors/skips 0. Debug/release Lint errors 0, warnings 7; project/Python/public-surface policy gates PASS over 389 candidates.
- Exact local artifacts: debug APK 32,452,040 bytes / SHA-256 `F766D047F74BED45B5E44515230F2104403BCBFF1CA2936CBBBD23B354739EA3`; androidTest APK 10,960,156 bytes / `BDD527A21A0D5F9B1A80D9D6330D7C750C03BA722DDB2AA582F7F9CC7324BD67`; unsigned release APK 24,061,044 bytes / `7CDB6C80ED5B6FD62FA60FD8147841C5AA09FB2BA3DCFC8DC4842FB1107392D0`; Windows app-image 405 files / 176,497,058 bytes / digest `0954c0cec3daf8df91c489cc542c2fc8f6e5ebaf306a601e3ab5d14561cfd6d4`, EXE SHA-256 `05BA300784A2B98197200A7B5AFCEDD70B62913DB71C1971B23A5E9785281630`.
- Runtime evidence: exact final debug/test APKs passed API 36 instrumentation `OK (8 tests)` after data-preserving installs. The two new first-screen tests are deterministic in-memory Compose deck fixtures, not MainActivity/controller-adapter E2E. Separately, the exact production MainActivity was cold-launched and navigated manually: normal compact-landscape CAPTURE reached recording/demo by physical swipe; 200% BEAT showed complete B01–B16 labels and reached PLAY/REC/CLEAR, loop and QUICK/STEPS/ADD/SCRATCH by physical swipe; the detailed 16-step surface fit its primary controls. Exact packaged Windows CAPTURE/demo BEAT responded and B DRUMS/B-01/01–16 aligned.
- Current daily Windows: merged-main artifact `9510151389` for `495ddc9`, app-image digest `250216a8bd4f524df3c618604eaaade8a3dc257a3b8a0d1f0c3a731adf378c25`, installed at `%LOCALAPPDATA%/Programs/ChopLab/0.17.0-250216a8bd4f`; Start Menu/Desktop targets, responding isolated runtime and unchanged real-project digest passed. It predates only the closeout large-text repair and will be replaced from merged closeout main rather than treated as those bytes.
- Gate ceiling: `LOCAL_PASS` plus scoped API 36 emulator runtime evidence. The physical Pixel was not connected, so current physical `DEVICE_PASS`, touch feel, TalkBack speech, listening/latency, recording, route loss, provider, binary Release and `HUMAN_GO` are not inferred.
- Architecture: [`ADR-0005`](architecture/ADR-0005-guided-first-screen-flow.md) fixes guided entry, constrained body scrolling and adaptive chrome. No project-schema or audio-rendering migration was introduced.

## Previous snapshot — 2026-08-24 sample-rate-bounded decode rebased candidate

## Previous snapshot — 2026-08-24 sample-rate-bounded decode merged-main candidate

- Merged-main source: PR #61 is merged at `main@ae77cd92d3ee14baecc01f4862c639328bae43bb`; its reachable pre-merge product anchor is `8279ea4f7e04cfec2c41440e65f4a40bc4d68451`, tree `f6a5bc3844317169edf1100e79da1ea08b46c524`.
- Import policy: materialized mono frames are bounded by `min(30,000,000, sampleRate × 600)`. Android reapplies the ceiling when the effective decoder rate arrives; Desktop applies it to declared and unknown-length streams.
- Hosted evidence: the exact PR #61 head passed Android, Windows, iOS, and supply-chain workflows and received a clean exact-head review before merge.

## Previous snapshot — 2026-08-24 release checksum sidecar hardening candidate

- Source boundary: integrated executable candidate `77630cdb56e54f1f217a107bdce3d2d307000871`, tree `1d8f23de24e19c8d2b88571a16e0146dbcdbaeb2`, with direct merged-main parent `5430d0d91a4e19ca02170d0143378a5d7917776b`. Later commits on this PR only bind documentation to that immutable candidate; hosted workflow identities are recorded after integration.
- Publication policy: release manifest creation now validates every `.sha256` sidecar and requires byte-matching sidecars for the three runnable platform archives plus the release-bound CycloneDX SBOM. Missing, malformed, cross-named, mismatched, and orphan checksum files fail before attestation and `gh release create`.
- Scope: release policy and its Python regression tests only. Product/audio/UI bytes, signing configuration, tags, and Releases are unchanged. Current-container policy gates are run before PR publication; hosted CI is required before promotion.

## Previous snapshot — 2026-08-24 iOS import/recording exclusion local candidate

This snapshot records a bounded iOS preview safety correction. It does not promote Simulator source inspection into physical recording or audio evidence.

- Observed at: `2026-08-24T16:37+09:00`.
- Source state: branch product commit `6ceb4d26c862f4cfe645ec23029a466c6ebe27a5`, tree `a9abb15ef1fb3f81d5104ece0a9d341ee2a7383d`, based on merged `main@495ddc9dfac02a9e72160c637f65d2b53d6829ce`; subsequently integrated as PR #60 at `main@5430d0d91a4e19ca02170d0143378a5d7917776b`.
- Import ownership: `SamplerStore` rejects a file-import result while recording before staging or replacing any source. The import button is disabled for the same state, so UI and store admission agree.
- Non-destructive picker outcome: cancellation and provider failure update a distinct status without calling `stopAll`; the current source and any active recording remain owned by their existing lifecycle. A late successful picker result cannot replace the recording source.
- Focused tests: new MainActor store tests bind recording admission and prove cancellation/error preserve an already imported source name. Existing source repository tests continue to cover bounded copy and promotion.
- Local evidence: 23 Python policy tests, public-surface scan over 390 candidates and `git diff --check` PASS. Linux has no Xcode/Simulator, and the Gradle wrapper distribution was unavailable locally, so Swift compilation, Simulator execution, microphone behavior and audible output remain for hosted macOS/device verification.
- Gate ceiling: source-level local policy evidence only; no `DEVICE_PASS`, signed iOS build, recording-quality, route-loss or `HUMAN_GO` claim.

## Previous snapshot — 2026-08-24 desktop recorder startup cleanup candidate

- Observed at: `2026-08-24T16:36+09:00`.
- Source state: branch product commit `53f4bf5a62d23d9db63f538be3a06298eaf48936`, tree `d74f6314b4efd4a5604568e3c21395cfae42aaf6`, based on `main@495ddc9dfac02a9e72160c637f65d2b53d6829ce`; subsequently integrated as PR #59 at `main@364ccde764b88f0bb79e10b8aaeb8284a5c069cc`.
- Recorder lifecycle: after a Windows `TargetDataLine` is acquired, every setup failure now closes that exact local line before clearing recorder state. In particular, an exception from `TargetDataLine.start()` after a successful `open()` cannot leak the native capture line or leave a stale output/worker/failure reference.
- Regression scope: a deterministic proxy line exercises successful `open`, failing `start`, exact-once `close`, idle state, temporary-WAV deletion, and inert follow-up `stop` / `close`, without opening recording hardware.
- Fresh local checks: Python policy tests 23/23, public-surface scan over 390 candidates, Android XML parse, wrapper checksum/UTF-8 policy, and `git diff --check` passed. The focused local Gradle test was blocked by the uncached distribution; merge/provider evidence remains revision-bound separately.
- Gate ceiling: source/static candidate only. No Windows capture hardware, audible recording, latency, device removal, provider, public artifact, or Human claim is inferred from the local receipt.

## Previous snapshot — 2026-08-24 global optimization source/device/daily-install closeout

This snapshot is the current product and source-state truth. Four bounded optimization phases and their docs-only closeout are merged; no implementation plan is selected beyond the next explicit product/audio decision.

- Observed at: `2026-08-24T06:36:06.4166553+09:00`.
- Source state: `main` contains docs-only closeout PR #50. Its exact moving commit is a Git/provider read-back, not a self-referential constant in this file. Product source/binary claims remain anchored to `ecc6c540143388bbf7f4f5523c94056d0770d1ac`, tree `0178b1b563a42136b6c9ad8f335a8883a583da31`, source version `0.17.0 (27)`.
- Integrated PRs: #46 shared ProductionCommand semantics; #47 ProductionSession/restart-safe revision; #48 shared DSP primitives/non-finite policy/PAD PCM oracle; #49 full-bar pattern/master oracle and final-sample repair; #50 source-state/active-plan closeout.
- Current checks: product-anchor merged-main Android `32665966662`, Windows `32665966566`, iOS `32665966531`, Supply-chain `32665966589` passed. PR #50 merged-main source read-back Android `32667411667`, Windows `32667411630`, iOS `32667411640`, Supply-chain `32667411664` also passed.
- Current local/device evidence: final product checkpoint `c2dd5c7`; full 152-task gate; Android 229, shared hosts 25/25, JVM-core 52, Desktop 76, all failures/errors/skips 0; Pixel exact APK `DB089F70…` retained install/readback/project-preservation/cold-launch scope is DEVICE_PASS.
- Current daily Windows: merged-main artifact id `9500051082`, metadata commit `ecc6c54`, app-image digest `802A667D…`, installed at `%LOCALAPPDATA%/Programs/ChopLab/0.17.0-802a667d39cb`; Start Menu/Desktop targets, responding runtime and real project digest passed. Older versioned installs remain.
- Audio truth reached: allocation-free shared pitch/tone/gain/fade/limiter/swing policy; explicit NaN/Inf policy; realtime PAD/host PCM delta <= 1; single-event full-bar realtime/offline master delta <= 1. RED locator for the repaired bug was frame 402, offline 0 / realtime -61.
- Evidence ceiling: `LOCAL_PASS` + scoped exact-APK `DEVICE_PASS` + GitHub source/main CI readback. Physical audio quality/latency, recording, route loss, TalkBack speech, providers, binary Release and HUMAN_GO remain separate.
- SSOT invariant: a docs-only merge advances `main` without changing product bytes. Bind behavior/device/binary claims to the immutable product anchor and put the exact moving `main` identity in a revision-bound provider receipt; do not make this document stale at the instant it merges.
- Release boundary: `v0.17.0` tag remains immutable at old main `ab68d2d`; its failed release run is not the latest-integrated route. Stable Android signing secrets are absent. After they exist, use a new version from current main (candidate `v0.18.0`) with PR/tag/Release/reverse-download; do not rewrite the old tag.
- Next product decision: select exactly one of polyphony/choke, loop/vocal, stereo, or multi-pattern/Song. A native/Voice-kernel rewrite is not admitted before the selected audio dimension has an oracle.

## Previous snapshot — 2026-08-24 full-bar pattern/master parity local candidate

This snapshot supersedes the primitive-level parity snapshot below for current branch truth. It records the single-event full-bar oracle, terminal-sample repair plus fresh local and bounded device evidence; GitHub integration remains pending.

- Observed at: `2026-08-24T05:46:00.9406555+09:00`.
- Worktree/branch: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-pattern-master-oracle-20260824`, `codex/choplab-pattern-master-oracle`; product checkpoint `c2dd5c74b79e09ce2134011caf966c9d89fa777f`, tree `2070dd502260b311ca942d0d56af8d6b86f029f9`, based on public `main@5c56d844c86dc7dcdbd57e3f88154d99469e1a65`. Dirty canonical and earlier worktrees remain untouched.
- Oracle: one reverse/pitched/filtered PAD at step 0 is rendered for one full bar. Expected PCM is Android `SamplerEngine.Voice` plus shared master limiter; actual PCM is `PatternRenderer` WAV. Every frame and the last energetic frame are compared with maximum tolerance one integer PCM unit.
- RED evidence: initial maximum delta was 61 at frame 402, with offline 0 and realtime -61.
- Root cause/repair: OfflineVoice returned a final nonzero sample and marked itself finished in the same call. PatternRenderer removed it before adding that returned value. The renderer now mixes the value first, then retires a finished voice; focused oracle is GREEN.
- Scope: this closes single PAD / single event / mono master ordering. Polyphony, choke, repeated events, loop/vocal, stereo and physical perception are not inferred.
- Fresh local gate: 152 Gradle tasks PASS; shared Desktop 25, shared Android host 25, Android 229, JVM-core 52 and Desktop 76; failures/errors/skips 0. Debug/release Lint, APKs and Windows app-image PASS.
- Policy: Python policy 23 and public current/reachable-history scan 386 candidates PASS. Dependency inputs are unchanged and require fresh hosted supply-chain readback before merge.
- Local artifacts: debug APK 31,459,442 bytes / SHA-256 `DB089F705E526CFD7D7D848988C77E6EC4D7791FD535DD95451FF6C2B200F7D5`; unsigned release APK 24,044,660 bytes / `519DC77F3F1928E35CC73A15F941D662A5371337242024D12723D3A1B974F976`; Windows app-image 405 files / 176,479,579 bytes, EXE SHA-256 `05BA300784A2B98197200A7B5AFCEDD70B62913DB71C1971B23A5E9785281630`.
- Windows runtime: exact packaged launcher/UI responded with the expected title in an isolated data root; exact processes stopped and real projects remained 2 files / 365,609 bytes with unchanged digest.
- Pixel device: signer-matched retained update from `F9CD14E0…` to exact candidate `DB089F70…`; installed bytes matched host, projects remained 7 files / 62,592 KiB, cold MainActivity top-resumed, four stages + ALL STOP present, current-PID fatal/ANR 0 and stopped PID absent. Receipt: parent PAD `work/PAD_CHOPLAB_PATTERN_MASTER_7B04728_DEVICE_RECEIPT_20260824.json`.
- Review: initial Standards pass found only stale SSOT plus accepted cross-module test-helper duplication; Spec found no source issue. Final re-review unresolved Standards 0 / Spec 0.
- Gate ceiling: `LOCAL_PASS` plus scoped `DEVICE_PASS` for exact install/readback/project shape/cold-launch/navigation. Physical listening/export capture, broader audio parity, provider, PR/main, binary Release and HUMAN_GO remain pending or excluded.

## Previous snapshot — 2026-08-24 shared audio-parity primitives local candidate

This snapshot supersedes the ProductionSession snapshot below for current branch truth. It records a numeric parity tracer plus fresh local and bounded device evidence; GitHub integration remains pending.

- Observed at: `2026-08-24T05:13:59.5923913+09:00`.
- Worktree/branch: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-audio-parity-20260824`, `codex/choplab-audio-parity`; reviewed product checkpoint `6838028420cb36b8b17dfe03d2e94fa2a8cfe08c`, tree `9278c6201823408ffae85ee6ddb99c9cbfb76fb1`, based on public `main@28bd388acef12dde96befac9774a1853831f82b0`. Dirty canonical and earlier worktrees remain untouched.
- Shared policy: allocation-free `SamplerDspPrimitives` owns finite/clamp policy, pitch source step, tone coefficient, forward/reverse boundary fade, soft limiter and swung step duration for Android realtime, JVM offline export and Windows PAD rendering.
- Invalid inputs: NaN/Infinity map explicitly to pitch 0 st, tone bypass, gain silence, BPM 92 and straight swing. Sample rates are bounded to 8,000..192,000. Finite parameter clamps remain pitch -24..24, tone 0..1, gain 0..1.5, BPM 40..240 and swing 50..75.
- Realtime safety/performance: callback call sites remain allocation/lock/I/O free. Tone exponential is calculated at voice start/live-control update; offline rendering no longer recalculates it for every active voice sample.
- Parity oracle: the same PAD rendered through Android `SamplerEngine.Voice` and JVM `PadPcmRenderer` differs by at most one PCM integer unit for the fixture. Offline renderer has direct non-finite/timing regressions, while shared numeric contracts run on Desktop JVM and Android host.
- Scope boundary: Voice lifecycle, command queues, AudioTrack/Java Sound handles, offline writer and project schema remain in their existing modules. Full-pattern/master/stereo tolerance and native-engine replacement remain later horizons.
- Fresh local gate: 152 Gradle tasks PASS; shared Desktop 25 / 4 suites, shared Android host 25 / 4, Android 228 / 44, JVM-core 52 / 8 and Desktop 76 / 16; failures/errors/skips 0. Debug/release Lint, debug/unsigned-release APK and Windows app-image all PASS.
- Policy: Python policy 22 and public current/reachable-history scan 382 candidates PASS. Dependency inputs are unchanged; current-main SBOM identity remains separately hosted-verified and requires a fresh PR check before merge.
- Local artifacts: debug APK 31,529,520 bytes / SHA-256 `F9CD14E0A620D59F6D8877D76260118CCDE9D5F5A64291CB47F55F87A0DFE93D`; unsigned release APK 24,044,660 bytes / `A4FF8D3619BA320774453C24F25C62DA0096C90A5DF771C34FD87B95E43308D1`; Windows app-image 405 files / 176,479,580 bytes, EXE SHA-256 `05BA300784A2B98197200A7B5AFCEDD70B62913DB71C1971B23A5E9785281630`.
- Windows runtime: exact packaged app-image produced responding launcher/UI and title `ChopLab — おとひろい PC` in an isolated data root. Exact tracked processes were stopped; real projects remained 2 files / 365,609 bytes with the same digest.
- Pixel device: signer-matched retained update from `9E5C5767…` to exact candidate `F9CD14E0…` used `adb install -r --no-streaming`. Installed base APK matched host bytes; projects stayed 7 files / 62,592 KiB before/after/launch; cold MainActivity top-resumed, four stages + ALL STOP present, current-PID fatal/ANR 0, stopped PID absent. Receipt: parent PAD `work/PAD_CHOPLAB_AUDIO_PARITY_3CCD414_DEVICE_RECEIPT_20260824.json`.
- Review: first local-parent Standards/Spec passes found stale SSOT, a tone-alpha middle man, incomplete sample-rate bounds and missing direct finite-clamp contracts. All findings are fixed; final re-review reports unresolved Standards 0 / Spec 0.
- Gate ceiling: `LOCAL_PASS` plus scoped `DEVICE_PASS` for exact install/readback/project shape/cold-launch/navigation. Physical audio quality/latency and realtime/offline listening parity, providers, PR/main, binary Release and `HUMAN_GO` remain pending or excluded.

## Previous snapshot — 2026-08-24 shared ProductionSession local candidate

This snapshot supersedes the first command-spine snapshot below for current branch truth. It records the Horizon 2 application transaction owner plus fresh local and bounded device evidence; GitHub integration remains pending.

- Observed at: `2026-08-24T04:40:24.4018332+09:00`.
- Worktree/branch: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-production-session-20260824`, `codex/choplab-production-session`; reviewed product checkpoint `894a5ba88c0711d0b5633f29bea3104c50ddcd51`, tree `332263892639a31bfc6d8715886414f25529a7b9`, based on public `main@41be2c22b29521909ff0a0443e21523eff5e4e8a`. Dirty canonical and earlier worktrees remain untouched.
- Application ownership: shared `ProductionSession` now owns bounded history, merge coalescing, monotonic revision, PROJECT/SESSION/NONE classification, canUndo/canRedo, persistence admission and one-use command plans. Android/Windows controllers no longer instantiate `EditHistory`; Android no longer owns a separate `projectRevision`.
- Two-phase truth: a command is planned first, required blocking effects run, and only success permits commit. Foreign, stale, cancelled and double-resolved plans fail closed. Platform controllers retain StateFlow publication, actual I/O scheduler and audio/document/lifecycle adapters.
- Project lifecycle: source import, recording result, reset, manual open, validated autosave recovery, legacy edit and Undo/Redo all pass through the same session owner. Recovery can suppress an immediate redundant write while still advancing the in-memory project revision.
- Restart-safe revision: `AtomicProjectStore.loadWithRevision()` returns the verified generation revision. Recovery adopts `max(session,disk)+1`; the next durable edit is therefore newer than disk and is no longer silently rejected while a counter restarts from zero. Legacy metadata-less generations retain fallback behavior.
- Concurrency boundary: JVM-atomic `ProjectOperationEpoch` remains a platform adapter. Moving it into common mutable state is deferred until asynchronous completion and runtime observation have a safe shared state owner; no weaker non-atomic replacement was introduced.
- Fresh local gate: 152 Gradle tasks PASS; Android 226 / 44 suites, JVM-core 50 / 8, Desktop 76 / 16, shared Desktop 19 / 3 and shared Android host 19 / 3; failures/errors/skips 0. Debug/release Lint, debug/unsigned-release APK and Windows app-image all PASS.
- Policy: Python policy 22 PASS and public current/reachable-history scan 378 candidates PASS. Dependency inputs did not change; SBOM identity remains the merged-main verified CycloneDX 1.6 `com.choplab:ChopLab:0.17.0`, 650 components / 651 dependencies, with a fresh hosted check still required before merge.
- Local artifacts: debug APK 31,688,132 bytes / SHA-256 `9E5C5767E8FD0FDE89C94E9F4C4691E334FF32BB334291F5294BA65F2F76DB23`; unsigned release APK 24,044,660 bytes / `1249EC0C563D3D5102D089B3DC89015754783020D3A3656982919EC14C0F2E1E`; Windows app-image 405 files / 176,477,521 bytes, EXE SHA-256 `05BA300784A2B98197200A7B5AFCEDD70B62913DB71C1971B23A5E9785281630`.
- Windows runtime: exact packaged app-image produced responding launcher/UI processes and title `ChopLab — おとひろい PC` in an isolated data root. The exact tracked process tree was stopped; real user projects remained 2 files / 365,609 bytes with the same pre/post digest.
- Pixel device: signer-matched retained-package update from previous `EADA7421…` bytes to exact candidate `9E5C5767…` used `adb install -r --no-streaming`. Installed base APK matched host bytes; projects remained 7 files / 62,592 KiB before/after/launch; cold `MainActivity` was top-resumed, four stages + ALL STOP were present, current-PID fatal/ANR 0, and the process was absent after force-stop. Receipt: parent PAD `work/PAD_CHOPLAB_PRODUCTION_SESSION_69EFBED_DEVICE_RECEIPT_20260824.json`.
- Review: first local-parent Standards/Spec passes found stale SSOT, one missing foreign-plan negative and ambiguous operation-epoch roadmap wording. All findings are fixed; final re-review reports unresolved Standards 0 / Spec 0.
- Gate ceiling: `LOCAL_PASS` plus scoped `DEVICE_PASS` for exact candidate install/readback/project-shape/cold-launch/navigation. Physical recovery behavior, audio/latency/gesture, provider, PR/main, binary Release and `HUMAN_GO` remain pending or excluded.

## Previous snapshot — 2026-08-24 global ProductionCommand local/device candidate

This snapshot supersedes the Windows-only local snapshot below for current product-development truth. It records the first system-level semantic-spine tracer, fresh local verification and a bounded data-preserving Pixel receipt; GitHub integration for this tracer remains pending.

- Observed at: `2026-08-24T03:52:53.9475490+09:00`.
- Worktree/branch: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-global-optimization-20260824`, `codex/choplab-global-optimization`; reviewed implementation checkpoint `fcbed5b01bf6c14e1fafc045159fe086011db747`, tree `334a0bb6d2eb9a13a01bbfec394bb25bba6ad4ea`, based on `main@ab68d2d`. The canonical dirty checkout remains untouched.
- System direction: UI breadth, native-engine-first rewrite and DAW-wide expansion were rejected as the immediate path. The selected capability ladder is shared Production semantics -> ProductionSession -> realtime/offline audio parity -> arrange/mix -> assist/release operations. SSOT: [`docs/architecture/global-product-optimization-2026-08-24.md`](architecture/global-product-optimization-2026-08-24.md), ADR-0001 and the active ExecPlan.
- Implemented tracer: range START/END, slice marker add/move, slice selection and normal PAD mode now enter one `ProductionCommand` reducer. Results distinguish PROJECT/SESSION/NONE and emit typed effects. Android and Windows no longer differ on zero-crossing/minimum range safety, end-exclusive selection, selection-only Undo/autosave, or ONE_SHOT/GATE versus explicit Beat LOOP.
- Runtime failure truth: a loop-owned PAD is stopped before state publication. A failed stop leaves the previous mode/owner and adds no history. A post-publication refresh failure preserves the edit but reports that runtime application failed.
- Fresh local gate: 152-task Gradle gate PASS; Android 226 / 44 suites, JVM-core 49 / 8, Desktop 76 / 16, shared Desktop 12 / 2 and shared Android host 12 / 2; failures/errors/skips 0. Debug/release Lint, debug/unsigned-release APK and Windows app-image all PASS.
- Policy/SBOM: 22 Python tests PASS; public current/reachable-history scan PASS over 374 candidates; wrapper SHA `7a9ce74c…` and explicit UTF-8 PASS; CycloneDX 1.6 `com.choplab:ChopLab:0.17.0`, 650 components / 651 dependencies PASS.
- Local artifacts: debug APK 31,443,058 bytes / SHA-256 `EADA7421F8420FEFAE4E5942BDA881E131D00EAB9D5BB3F8218ADAD0EC43527D`; unsigned release APK 24,028,276 bytes / `09578400AD7B242663AD7891B6C7CDC2A635D61B7474DDA52DC6793C540AB9CA`; Windows app-image 405 files / 176,465,465 bytes, EXE 449,024 bytes / `05BA300784A2B98197200A7B5AFCEDD70B62913DB71C1971B23A5E9785281630`.
- Windows runtime: exact packaged EXE produced a responding launcher/UI pair and title `ChopLab — おとひろい PC` in an isolated data root. Both tracked PIDs were stopped; real `%LOCALAPPDATA%/ChopLab/projects` remained 2 files / 365,609 bytes with the same pre/post digest. A PowerShell 7 JSON date-coercion mismatch in the generic stop helper was safely handled through its Windows PowerShell 5.1 compatibility boundary after exact PID/path/creation verification; this was not a ChopLab failure.
- Pixel device receipt: Pixel 9a / Android 17 / API 37 / arm64-v8a; existing `0.16.2 (26)` signer matched the candidate. `adb install -r --no-streaming` updated to `0.17.0 (27)`; installed base APK SHA exactly equals the host debug APK. Project shape stayed 7 files / 62,592 KiB before install, after install and after cold launch. `MainActivity` was top-resumed, all four stage labels plus ALL STOP were present, current-process fatal/ANR count was 0, and the process was absent after exact force-stop. Receipt: parent PAD `work/PAD_CHOPLAB_GLOBAL_OPT_FCBED5B_DEVICE_RECEIPT_20260824.json`.
- Review: initial local-parent Standards/Spec passes found stale ExecPlan/SSOT, missing range-END/move tests, missing hosted shared-test tasks and one target/current wording issue. The implementation/test/workflow findings are repaired in `fcbed5b`; SSOT closure and final re-review follow this snapshot.
- GitHub/release: this tracer has not yet been pushed or merged. Existing v0.17 source/tag state remains at `main@ab68d2d`; binary Release is still fail-closed on stable Android signing secrets and is not weakened or partially published here.
- Gate ceiling: `LOCAL_PASS` plus scoped `DEVICE_PASS` for exact APK install/readback, project-shape preservation, cold launch/navigation labels and fatal/ANR negative path. Physical range/marker gestures, PAD audio/latency, recording, route loss, TalkBack speech, provider behavior, binary Release and `HUMAN_GO` remain unverified.

## Previous snapshot — 2026-08-24 Windows daily-use v0.17.0 local candidate

This snapshot supersedes the earlier integrated candidate below for current local source and distribution truth. It records local implementation, packaging, installation, and non-audio runtime only; GitHub PR/merge/Release remain pending at this point.

- Observed at: `2026-08-24T02:33:05.8057664+09:00`.
- Worktree/branch: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-desktop-daily-20260824`, `codex/choplab-desktop-daily-release`; product source `b6efbde30a0fc1d8ce8a944405b20422fc238782`, tree `9760029f723c55465004908899255a7ad1c165a3`, based on public `main@c4956cfbf0a825dff76ee472b3a8fead9e4814ef`. The canonical dirty checkout remains untouched.
- Product behavior: the existing simple shared deck and Android touch PAD visuals are preserved. Windows maps `1234 / QWER / ASDF / ZXCV` to the currently visible assigned 16-PAD page; repeated key-down, Ctrl/Alt/Meta, loading, source playback/transition, and recording are rejected. Key-up, focus loss, and close release the exact owned global PAD. Native File/Edit/Transport menus expose open/save/export, Undo/Redo, source play/stop, and ALL STOP.
- MPC/Cubase boundary: installed MPC Beats `2.12.3.9` and its live Program Edit, Sample Edit, Step Sequencer, Pad Mixer, and Pad Mute modes were inspected read-only; Akai primary manuals establish contextual PAD roles. Cubase is not installed, so only current Steinberg zone documentation was used. No third-party assets, wording, project formats, or trade dress were copied. Research: [`docs/research/mpc-pad-functional-model-2026-08-24.md`](research/mpc-pad-functional-model-2026-08-24.md).
- Dependency integration: PR #40–#44 intents are present: actions/attest 4.2.2, CycloneDX 3.4.1, Kotlin 2.4.10, AGP 9.3.1, Compose BOM 2026.08.00, Core KTX 1.19.0, and Gradle 9.7.1. The red #42 compileSdk gap is repaired with compileSdk 37 while minSdk 29 / targetSdk 36 remain; the red #43 wrapper contract is repaired with SHA `7a9ce74c…` and explicit UTF-8 enforcement.
- Fresh local gate: final 142-task Gradle gate PASS; Android unit 226 / 44 suites, JVM-core 49 / 8 suites, desktop 72 / 16 suites, failures/errors/skips 0; debug/release Lint, debug APK, unsigned release APK, Windows app-image/package all PASS. Configured Git Bash validation, 22 Python policy tests, public-surface 369 candidates, `git diff --check`, and Android `0.17.0 (27)` unsigned identity check PASS.
- SBOM: CycloneDX 1.6 root identity `com.choplab:ChopLab:0.17.0`, 650 components / 651 dependencies; an unspecified root now fails CI.
- Local artifacts: debug APK 31,495,040 bytes / SHA-256 `E2BFD3A81CC50352DAD8D06FC683AF03DD01206628C4170B8863ECEA4AEF1935`; unsigned release APK 24,011,892 bytes / `8188FCAE5AA3248EABF5DB68FC200862BB60C1F429821980CC498D6B81C7D206`; Windows EXE 449,024 bytes / `05BA300784A2B98197200A7B5AFCEDD70B62913DB71C1971B23A5E9785281630`; desktop JAR 330,897 bytes / `7164DFF0B7521FFCB2DF10032F5D45790F63D542FD656623378CC52BC80953BA`; full app-image digest `8487C2376FBCB5A4B83D84631E50A6165ECB0E1E772E5CAC0BFA0A2F65F98CC6`.
- Daily installation: `%LOCALAPPDATA%/Programs/ChopLab/0.17.0-8487c2376fbc/ChopLab.exe`; Start Menu and Desktop `ChopLab.lnk` targets read back exactly. Existing `%LOCALAPPDATA%/ChopLab/projects` remained 2 files / 365,609 bytes with identical digest before install, after install, and after runtime.
- Installed runtime: a temporary `LOCALAPPDATA` sandbox and empty Spotify Client ID produced responding title `ChopLab — おとひろい PC`; an unassigned `1` key smoke did not crash or start audio; exact launcher/UI process tree was stopped. Screenshot: parent PAD `work/CHOPLAB_WINDOWS_0.17.0_INSTALLED_RUNTIME.png`.
- Full receipt: parent PAD `work/PAD_CHOPLAB_WINDOWS_0.17.0_LOCAL_RECEIPT.json` plus `work/CHOPLAB_WINDOWS_LOCAL_INSTALL_0.17.0.json`.
- Gate ceiling: `LOCAL_PASS`. This run does not claim a fresh Android `DEVICE_PASS`, Spotify/provider access, physical audio/latency, accessibility speech, signed installer reputation, public GitHub bytes, or `HUMAN_GO`.

## Previous snapshot — 2026-08-24 integrated local candidate

This snapshot supersedes the two source-branch snapshots below. It records one local integration candidate only; it does not change public `main`, publish a release, authenticate Spotify, or authorize recording.

- Observed at: `2026-08-24T00:10:19+09:00`.
- Worktree/branch: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-session-integration-20260823`, `codex/choplab-session-integration`.
- Product source: merge commit `6914e3c4d7bfabc85b43eaadfcfaa8de69072739`, tree `94fbc43839d2d74ae383ac973b456ceb4fea9dca`; parents `261d034` (full hardening + Spotify UX) and `df61bb5` (cross-platform production continuity); merge base `9a4e9edc`.
- Integrated behavior: reproducible release/resource/security hardening, Spotify metadata/control-only panel and lifecycle, source recording→CHOP routing, vocal Beat-loop restart, startup-project autosave, and output-device failure stop/temp cleanup coexist. Only `docs/VALIDATION.md` and `plans/active/README.md` conflicted; both histories were retained.
- Fresh local gate: clean 184-task Gradle gate PASS; Android unit 226 / 44 suites, JVM-core 49 / 8 suites, desktop 66 / 15 suites, failures/errors/skips 0; Android Lint fatal/error 0 and warnings 6. Configured project validation, 19 Python release-policy tests, public-surface 355 candidates, 9-region UI contract, Android release policy, Windows ProductVersion, SBOM, and `git diff --check` all PASS.
- Source-bound receipt: [`outputs/session-integration-receipt-6914e3c4d7bf.json`](../outputs/session-integration-receipt-6914e3c4d7bf.json). Debug APK SHA-256 `797531839DEBF5B3E589BB56038366AFDCBE47754707332E80785E5EEE206DE6`; test APK `13A7E1EC8312DC2226AFA419312D65A1DF5C500601739B6C4BB05C1C193C1191`; unsigned release candidate `9F0D4CCF1FB9D024A2243C5C7645BE72976C80B7FEBD8E2A952C9B65B81F1325`; Windows EXE `2DCBA5BED76C97E4D2EF85B5F18304C325653ADF4BFFA66A77A443EB80C2622A`; desktop JAR `4EC3C580CAEE07FA55DDE52D4FF0C91E4642F07AF375B59AF17F4E935058FA5C`.
- Windows runtime: exact packaged launcher PID `29280` produced responding UI PID `25408`, title `ChopLab — おとひろい PC`; both exact-path processes were stopped. No Client ID, token, audio, or provider operation was used.
- Android device evidence: integrated `app`, `shared`, `jvm-core`, root build, settings, and Gradle-property Git objects exactly equal accepted source `8306ed2`; integrated host APK/test APK hashes exactly equal its host and installed read-back hashes. The accepted receipt proves data-preserving install, package/version/signer, 6 deterministic instrumentation tests, autosave preservation, cold launch with zero fatal/ANR/crash, and phone-state restoration. The device is not currently attached, so no new mutation was attempted.
- Review: local parent Standards and Spec passes report zero unresolved findings. The previous Luna probe was rejected for writable effective sandbox; no substitute child model was used.
- Gate ceiling: `LOCAL_PASS` plus scoped `DEVICE_PASS` for the exact Android APK/test APK bytes and recorded non-recording scope only. Provider, public, physical audio/latency, accessibility speech, and `HUMAN_GO` are not reached.

## Input snapshot — 2026-08-23 cross-platform production continuity

This source-branch snapshot remains historical input to the integrated current snapshot above. Historical device/public receipts below remain scoped to their own revisions.

- Observed at: `2026-08-23T20:28:30+09:00`.
- Implementation worktree: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-cross-platform-polish-20260823`; branch `codex/choplab-cross-platform-polish`; reviewed source commit `31061be2cc8f82327a2881f5dcc56c54b9753482`, tree `27c3c22be94716d7315231ac4c5f791f951dd196`, based on `9a4e9edc2686914c28c91b2d614dfb95281935c2`. The dirty canonical checkout and the separate Spotify/full-hardening branch remain untouched.
- Selected plan: [`plans/active/cross-platform-production-continuity-20260823.md`](../plans/active/cross-platform-production-continuity-20260823.md). The direction matrix selected production-state continuity over broad reskin, feature expansion, or hardware-dependent native audio work.
- Windows parity candidate: recorder dependencies now use the existing `DesktopAudioRecorder` seam; decoded source recording publishes a fresh `ProjectLaunchTarget.CHOP`; successful vocal recording restarts and retains the selected Beat loop; startup-file sessions skip stale recovery without disabling subsequent three-generation autosave.
- TDD: the three primary public-controller seams plus the review-found playback-device failure seam were each observed RED before their minimal implementation and GREEN afterward. Final results are 225 Android unit tests / 44 suites, 44 JVM-core tests / 8 suites, and 39 desktop tests / 12 suites, with failures/errors/skips 0. Android Lint has 0 fatal, 0 errors, and 4 warnings.
- Local gate: `:desktop:test :jvm-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :desktop:packageWindows` BUILD SUCCESSFUL (91 tasks); configured `scripts/validate_project.sh` PASS; public-surface scan PASS over 322 candidates; UI contract PASS (`9` regions, exact `4`, semantic `4`, adapted `1`); `git diff --check` PASS.
- Source-bound artifacts: [`outputs/build-provenance-31061be2cc8f.json`](../outputs/build-provenance-31061be2cc8f.json) binds the exact source commit/tree, debug APK 30,937,621 bytes / SHA-256 `040570008F4B2CD9CA4E27419C321AB830E07B8B47705F1CD383CD8DC4CDF33B`, test APK 10,564,866 bytes / SHA-256 `13A7E1EC8312DC2226AFA419312D65A1DF5C500601739B6C4BB05C1C193C1191`, package `com.choplab.sampler` `0.16.1 (25)`, and signer SHA-256 `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`. [`outputs/windows-provenance-31061be2cc8f.json`](../outputs/windows-provenance-31061be2cc8f.json) binds packaged `ChopLab.exe`, 449,024 bytes / SHA-256 `40903D73A17CD6DE66D33567779C2350B72C3FD6B16701662008265534F8E69A`.
- Final Windows runtime smoke: tracked launcher PID `41504` produced responding child PID `22664` with title `ChopLab — おとひろい PC`; both exact-path processes were stopped after the smoke. No audio endpoint, recording, or Spotify operation was used.
- Review: local parent Standards/Spec two-pass found one shared issue—Beat output creation could throw after the vocal recorder opened. A RED regression now proves that the recorder is stopped asynchronously, the temporary take is deleted, loop state returns idle, and actionable Windows-output guidance is shown. Final focused `:desktop:test :desktop:packageWindows` passed with 39 tests.
- Device boundary: this task is the Pixel/ADB owner, but bounded preflight and post-local-gate recheck both returned no attached device at `2026-08-23T20:03:47+09:00` and `2026-08-23T20:27:57+09:00`. No install or device mutation was attempted; repeated polling stops here. Another branch's receipt is not promoted.
- Gate ceiling: `LOCAL_PASS`. Fresh `DEVICE_PASS` is explicitly blocked on Pixel reconnection and remains separate from physical audio, recording, TalkBack speech, provider, public, and `HUMAN_GO`.

## Previous restart snapshot — 2026-08-19

This is the current restart snapshot. The dated sections below are a historical validation ledger; they remain useful as provenance, but they do not override this section when a revision or gate differs.

- Observed at: `2026-08-19T02:40:04.8396920+09:00`.
- Canonical root: `C:/Users/rambo/Documents/ChatGPT/pad/work/codex-workspace/ChopLab-Codex-Workspace`.
- Branch: `agent/gpt-pro-ui-integration`.
- HEAD: `6033d85b68c9b67f767a31b8878dbe4f4be3392c`.
- HEAD tree: `39f8aa19e77b56acbd21c5bdde0f2aa911e6366f`.
- Upstream relation: `git rev-list --left-right --count '@{u}...HEAD'` returned `0 49`; the checkout is 49 commits ahead of its upstream and 0 commits behind.
- Git boundary: 5 tracked modifications (the documentation files being edited in this restart), 1,471 untracked paths (`git status --short` presents 169 `??` rows). This is operationally dirty because documentation edits, evidence, and artifacts must be preserved. Do not clean, reset, stage, delete, or force-checkout this boundary as part of a restart.
- Current build target: `app/` AudioTrack MVP. `reference/pro-v0.2/` remains incomplete design/history material and is not a second buildable project.
- Current restart check: `plans/active/restart-playback-interruption-local-20260819.md` ran the focused local policy suites with the portable JDK/SDK environment. Result at `2026-08-19T02:13:31.3948208+09:00`: `BUILD SUCCESSFUL`, 3 suites / 23 tests, failures 0, errors 0, skipped 0. The first no-environment attempt failed only at `SDK location not found`; no source change was made. This is `LOCAL_PASS` for the policy seam, not physical audio or any upper gate.

### Revision-bound receipts found in the current filesystem

The following receipts bind the exact current HEAD and tree and can therefore be treated as current local evidence for their recorded scope. They predate the uncommitted documentation edits above; they do not claim the current working-tree bytes are clean.

- `work/device-evidence/20260817-012052-6033d85b/manifest.json`, captured at `2026-08-17T01:23:11.9810764+09:00`, records the clean-source Gradle command, APK/test APK bytes and signer, and an instrumentation evidence collection run.
- `outputs/ChopLab-v0.13.1-6033d85-api36-review-provenance.json` binds the same HEAD/tree to the app and androidTest APK hashes.
- `outputs/ChopLab-v0.13.1-6033d85-api36-review-avd-evidence.json` binds the same APK pair to the isolated API 36 AVD matrix; its declared gates are `COMPOSE_INSTRUMENTATION_PASS` and `FRAMEWORK_NODE_PASS`, with physical `DEVICE_PASS` and `HUMAN_GO` explicitly not claimed.

The manifest also contains a Pixel serial and install/state observations. Those are scoped external observations made on 2026-08-17, not a fresh device run in the current context-cleanup task; keep them as historical/scoped device evidence and do not promote the PAD ceiling from `LOCAL_PASS`. Provider, public, and human gates were not re-observed.

### Restart frontier

The selected restart seam [`plans/active/restart-playback-interruption-local-20260819.md`](../plans/active/restart-playback-interruption-local-20260819.md) is complete with no implementation diff. The next implementation seam is intentionally unselected. When one is chosen, the owner is the root integrator; rollback is to the pre-change working tree without reset/clean; stop if it crosses into device/provider/public scope or requires the Sanpo-owned Pixel 9a. A feature plan under `plans/active/` is not current merely because the file exists; consult its registry before resuming one.

The parent PAD's context-restart audit is recorded in [`PAD_HISTORY_RESTART_EXTRACT_20260819.md`](../../../../work/PAD_HISTORY_RESTART_EXTRACT_20260819.md). It is a historical-lead/current-snapshot reconciliation and skill extraction record only; it does not change app source or promote the current `LOCAL_PASS` ceiling.

## Precision trim long-press and numeric wheels / v0.16.1 candidate — 2026-08-21

The isolated branch `codex/choplab-precision-trim` is based on clean main `923d7bb711d399efdf7ea8726e9a72769f1d97a5` and leaves the dirty canonical checkout untouched. Reviewed implementation commit `f89877c10371dcd57077dc0413a46f536386422d`, tree `2122bb183fbb2f87c9b40384a6d13f9c526852aa`, adds one shared Android/Windows precision-editing path without changing PCM bytes, archive schema, provider behavior, or recording.

- Assigned PAD long press opens `切り位置 / TRIM` directly. Portrait uses the full editor while landscape keeps the existing split workspace.
- Waveform long press chooses the nearer START/END boundary, moves it to the pressed frame, performs long-press haptics, and focuses a source-clamped viewport no wider than one second. Normal tap, double-tap, pinch/pan, handles, Preview, entry Revert, Undo, and Redo remain available.
- Independent `ここから / START` and `ここまで / END` controls show dial position plus previous/current/next exact time. Vertical touch drag, mouse wheel, and accessibility actions use one bounded frame policy with `1 FRAME`, `1 ms`, and `10 ms` precision.
- The shared trim policy now uses saturated arithmetic, keeps start-inclusive/end-exclusive bounds inside the assigned source, and preserves a minimum two-frame range even for extreme wheel input.
- Source-bound full local gate after review fixes: Android 225 tests / 44 suites, JVM-core 44 tests / 8 suites, desktop 35 tests / 12 suites, API 36 instrumentation 6 tests / 2 suites, all zero failures/errors/skips; Android Lint 0 errors / 8 warnings; APK and Windows app-image packaging PASS. Configured project validation and public-surface scan over 320 candidates PASS.
- API 36 `medium_phone(AVD) - 16`: Compose instrumentation 6/6 PASS. Manual data-preserving install observed PAD long press→TRIM, waveform long press→`3:38.374–3:39.374` at `388.6x`, ZOOM+→same-focus `3:38.624–3:39.124` at `777.1x`, 10 ms wheel→END +10 ms, and Revert→original boundaries plus `1.0x`. Evidence locator: parent PAD `work/CHOPLAB_PRECISION_TRIM_EVIDENCE_20260820/`.
- Final local artifacts: parent PAD `outputs/ChopLab-v0.16.1-preview.1-debug.apk`, 30,937,621 bytes / SHA-256 `040570008F4B2CD9CA4E27419C321AB830E07B8B47705F1CD383CD8DC4CDF33B`; `outputs/ChopLab-v0.16.1-preview.1-windows-app-image.zip`, 88,675,862 bytes / SHA-256 `4BE4FAEFEA04436500EC295DFB8CB7EF0555056F9DA235D342E22ED97EA2009C`; contained EXE 449,024 bytes / SHA-256 `40903D73A17CD6DE66D33567779C2350B72C3FD6B16701662008265534F8E69A`.
- Current terminal launch: the v0.16.1 app-image replaced the prior v0.16.0 window. Tracked launcher PID `35212`; UI PID `36820` is responding with title `ChopLab — おとひろい PC` from the reviewed worktree app-image.
- Gate ceiling: `LOCAL_PASS` plus scoped API 36 emulator interaction/instrumentation. Physical touch/haptics, audible boundary quality, TalkBack speech, provider/public Release, iOS native feature parity, and `HUMAN_GO` are not yet promoted.
- Review: local parent two-pass from fixed point `923d7bb` (no substitute child model). Standards found absolute-frame delta overflow and a 38 dp precision touch target; both are fixed with shared saturated setting, a regression test, and 48 dp controls. Final Standards findings 0; final Spec findings 0.

## Windows Spotify Connect UX lifecycle — 2026-08-23

The single integration branch `codex/choplab-spotify-connect` is based on `origin/main` merge base `9a4e9edc2686914c28c91b2d614dfb95281935c2`, contains the merged release/audio hardening branch, and has documentation-only commits after the source/device receipt commit `8306ed2114398a0d1adc89a9a4a653c1db409c1f`, without touching the dirty canonical checkout.

- The panel now has explicit `Client ID未設定 → 接続準備完了 → 認証中 → 接続済み → 接続エラー` state, a current-process-only Client ID path, clear Development Mode setup guidance, cancel/retry actions, and polite accessibility announcements.
- Cancel, disconnect, and Client ID reconfiguration invalidate an OAuth lifecycle epoch; an outstanding callback/token exchange cannot reconnect the session or restore stale metadata after the user cancels or disconnects.
- Current playback handles HTTP 204 by clearing stale display state. Saved-library responses distinguish an empty library from malformed provider data. 401/403/404/429/5xx guidance directs the user to re-login, Premium/allowlist/scopes, a Connect device, a bounded retry, or a later retry as appropriate.
- Malformed environment Client IDs now start unconfigured, provider denial/cancellation is distinct from configuration failure, and default-browser, loopback-bind, and authentication-network failures each identify the recovery boundary. The panel shows an explicit library summary instead of a blank empty list and clears the entered Client ID after memory-only configuration.
- The provider still uses Authorization Code with PKCE, an explicit `127.0.0.1` loopback callback, memory-only access/refresh tokens, and no Client Secret UI/persistence. Callback pages now set `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`.
- Cross-platform review hardening also bounds unknown-size iOS imports while they stream and rejects implausible Android decoder sample-rate/channel metadata before it reaches PCM mixing.
- Final local validation: clean 184-task full Gradle gate PASS; Android 226 tests, JVM-core 49 tests, final desktop 62 tests, Python 19 tests, Android Lint, project validator, current/history public-surface scan, Android release metadata check, Windows app-image identity/denylist, SBOM generation, and packaged hidden launch all PASS. Windows `ChopLab.exe` SHA-256 is `2DCBA5BED76C97E4D2EF85B5F18304C325653ADF4BFFA66A77A443EB80C2622A`; final packaged `desktop.jar` SHA-256 is `85A51849256511F45028E4D05946F7AF4222146D4B8555493553D736A6A31814`.
- A sealed fixed-diff security review closed 48/48 files with complete coverage and zero reportable findings. Its sole suppressed self-only iOS resource candidate was fixed anyway.
- Pixel `5A121JEBF08094` is now a scoped `DEVICE_PASS`: accepted receipt `work/device-evidence/20260823-025301-8306ed21/manifest.json` plus `launch-smoke.json` bind clean source `8306ed2`, app/test APK hashes and signer, `adb install -r`, six instrumentation tests, cold launch, autosave preservation, and foreground/rotation/volume restoration. The initial parser-only false stop is retained separately and not promoted. Browser OAuth, real Premium/allowlist/Connect-device behavior, physical audio quality, screen-reader speech, publication, and `HUMAN_GO` are not claimed.

## Windows Desktop full rebuild merged — 2026-08-20

The user-requested PC rebuild was implemented in the isolated branch `codex/choplab-desktop-exe` without resetting or cleaning the dirty canonical checkout. PR #32 was squash-merged to `main` as `d88f022e5c7023c987e9b82036c04c5207415597`; the merged main tree `05e255ccb7eeba9c0cc9b939b68684229581c322` exactly matches the validated branch tree.

- Worktree: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-desktop-exe-20260819`.
- Target: `:shared` Compose Multiplatform UI/domain + `:jvm-core` archive/autosave/export/PCM rules + `:desktop` Windows adapters. Android `:app` consumes the same shared UI/model and JVM core.
- Merged implementation: one Android-origin four-stage deck; separate source and polyphonic PAD voices; PAD KEY/TONE/LEVEL/REVERSE/GATE/LOOP/CHOKE rendering; 16-step BPM/Swing transport; bidirectional scratch stream; microphone and fail-closed driver-loopback recording; shared `.choplab` manual save/open and three-generation autosave; shared four-bar WAV export; bounded EditHistory; and a native Spotify metadata/control menu.
- Spotify boundary: Authorization Code with PKCE, loopback callback, memory-only token refresh, current playback, pause and resume. Spotify audio bytes, stream ripping, recording through the provider, full-track download and MP3 conversion remain deliberately absent.
- Current local evidence (observed `2026-08-20`): UI contract validator PASS (`9` regions, exact `4`, semantic `4`, adapted `1`); `:jvm-core:test :desktop:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` BUILD SUCCESSFUL; `:desktop:packageWindows` BUILD SUCCESSFUL; packaged PID child responds with title `ChopLab — おとひろい PC`. The same 32,451,057-byte local project used by the Android reference was opened through the packaged EXE; `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-desktop-loaded-project-20260820.png` is a complete 1106×2202 capture at 200% DPI and maps the waveform, slice markers, assigned A01/A02, selected A02, BANK/page, dock and exact copy.
- GitHub read-back: PR head Android/Windows/iOS checks all passed twice; merged-main Android run `32344903904`, Windows run `32344903955`, and iOS run `32344903922` all passed. Final local main-tree ZIP is `ChopLab-Windows-0.3.0-main-d88f022.zip`, SHA-256 `9929CF01E75556735410AB2D50BD703BFCC6D66C83FC47622E3DB08E4A23CBA5`.
- Explicit follow-up: driver loopback availability and audio quality, microphone/transport latency, Spotify account/device behavior, native low-latency WASAPI, signing/installer publication and Human acceptance remain separate.
- Gate ceiling: `LOCAL_PASS`. The adapter rejects missing loopback hardware rather than claiming success; no provider/device/public/Human promotion is made.

### Windows WASAPI endpoint probe — 2026-08-20

The follow-up branch `codex/choplab-wasapi` adds JNA 5.19.1 MMDevice/IAudioClient endpoint probing and a native `診断` menu without replacing the existing engine. WaveFormat parsing and platform/error behavior are host-tested. On the current Windows session, JNA and an independent in-memory C# COM probe agree: render/capture all-state collection counts are zero and every default role returns `0x80070490`; PnP lists ten historical AudioEndpoint records but none are present. This is a current-device unavailable receipt, not audible render/capture evidence. No audio was recorded and no default device setting was changed.

## Android production continuity / v0.16.0 — 2026-08-20

The isolated branch `codex/choplab-app-product-intent` reconnects launch recovery, CAPTURE project opening, CHOP/BEAT material continuity, starter drums, and scratch performance without changing archive schema or the dirty canonical checkout.

- Observed candidate: `1e15fe3c09e39c946905a1747b4f8a57ef7f9baf`, tree `bce75c1ecb4f30e193e12da38339c50c0cbc078c`, based on main `8c12f71f7c5a699669fdf0d5392a599fb50759c3`.
- Product route: CAPTURE now exposes `制作を開く / OPEN PROJECT`; validated recovery routes untouched starter-only state to CAPTURE, Source plus untouched starter drums to CHOP, and user Beat/pad-only work to BEAT.
- Shared material surface: normal BEAT keeps the selected waveform, BANK A–D, PAD page, and a fixed 4×4 playable PAD grid from CHOP. The existing responsive lane/16-step editor remains behind `並べる詳細 / STEPS`.
- First sound: new/reset/new-Source productions receive the original generated DUSTY JAZZ 16-pad kit plus starter pattern. Restored/manual projects remain exact; Android and Windows reject BANK B replacement without the existing confirmation action.
- Scratch: pointer-down acquisition, bounded symmetric speed with dead zone, 120 ms idle silence, live direction/speed/playhead, and one-time return to a still-valid prior loop/transport. Runtime return/launch fields are excluded from schema-5 archives and covered by round-trip tests.
- Local validation observed by `2026-08-20T22:05:13+09:00`: Android 217 tests, JVM-core 44 tests, desktop 35 tests, all zero failures/errors/skips; Android lint 0 errors / 8 warnings; APK, Windows app-image, and project validator PASS. Local parent Standards/Spec two-pass review has no unresolved finding; no substitute child model was used.
- API 36 emulator `emulator-5580`: instrumentation 4/4 PASS; CAPTURE OPEN invoked DocumentsUI; normal BEAT showed 16 PADs; live scratch showed `FORWARD ×0.58 / 080%`; release resumed B-01 loop; edited autosave relaunched directly to BEAT. Evidence locator: parent PAD `work/CHOPLAB_APP_CONTINUITY_EVIDENCE_20260820/`.
- Local artifacts: parent PAD `outputs/ChopLab-v0.16.0-preview.1-debug.apk`, 30,872,085 bytes, SHA-256 `D12F572C70525E4218E03D1326771F688430528AA8221523C6B0FB33A06125F6`; `outputs/ChopLab-v0.16.0-preview.1-windows-app-image.zip`, 88,640,599 bytes, SHA-256 `4D740801C091B165716ECAB921045750FD4F352B07FAE57823E1146721DFDD32`.
- Gate ceiling before GitHub: `LOCAL_PASS` plus scoped emulator UI/instrumentation evidence. Physical Android/iPhone behavior, audible scratch/drum quality, Bluetooth/route loss, TalkBack/VoiceOver speech, signed iOS IPA, provider/public Release, and `HUMAN_GO` are not promoted here.

### GitHub merge and public preview closeout

- PR [#35](https://github.com/dj-thank/choplab-sampler/pull/35) final head `8d1f79cbb709b50fa670d78d3fad22e7aea67cdc`: Android 2/2, Windows 2/2, iOS 2/2 checks PASS. The initial Android smoke-list omission was fixed before merge by compiling all shared model sources.
- Squash-merged main: `64e84b82888598bf7282a92fd277b54c027c1979`, tree `6819b4237522991eb15eda060cc785fa2e071e6b`. Merged-main Android run `32374131628`, Windows `32374131637`, and iOS `32374131624` all PASS.
- Annotated tag `v0.16.0-preview.1`: object `4d881d5998381682e3739f8f0e0343d77d114f77`, peeled commit `64e84b8`. Release workflow `32374833191` passed Android, Windows, iOS, and publish jobs.
- Public non-draft prerelease: [ChopLab v0.16.0-preview.1](https://github.com/dj-thank/choplab-sampler/releases/tag/v0.16.0-preview.1). Anonymous HEAD read-back returned HTTP 200 for the Release page and all three primary downloads.
- Public Android APK: 30,872,085 bytes, SHA-256 `2F04339524022F25B4D1ABB513152195C331A3C955168C4E84140D523F01E437`, package `com.choplab.sampler`, version `0.16.0 (24)`, minSdk 29 / targetSdk 36, CI debug signer SHA-256 `F4DC354152DDE84D39E60D0FF810D5108D659D697FACE2E86E9A6A369379FC5C`.
- Public Windows app-image ZIP: 88,640,593 bytes, SHA-256 `60C78C1D23BB2FE959C325C3AD42995EFF2758D242ED21E90602E92CA145C27A`; contained EXE SHA-256 `A69373FE39324619903D7B575509AF976CF8ED8D2A5C6921C2E18F3B40F790CF` matches the public JSON.
- Public iOS Simulator ZIP: 241,058 bytes, SHA-256 `B704C1861477F7D5C2CD6297CAFAA5C95984B67778ED63FF4B7A8697A5008267`; it contains `ChopLab.app` and is not a signed device IPA.
- Public evidence gate: `PUBLIC_PASS` only for tag-bound artifact availability, digest/sidecar integrity, package metadata, and anonymous download. Physical touch/audio quality, full Android/iOS parity, signed iPhone distribution, accessibility speech, and `HUMAN_GO` remain separate.

## Public Android / iOS preview release track — 2026-08-19

The public-release worktree adds an iOS 16 SwiftUI + AVFoundation preview under `ios/` and a GitHub Actions macOS build that generates an unsigned Simulator app. The iOS slice covers local audio import, 16 PAD playback, normalized per-PAD ranges, recording, and `ALL STOP`; it intentionally does not claim signed-device or App Store delivery. Android `versionName` is `0.14.0` with version code `22`, and the release workflow packages both platforms with SHA-256 sidecars under the same `v*` GitHub Release. The public-surface scan rejects credential/signing/audio candidates before build and release.

Local Windows evidence for this track: public-surface scan PASS over tracked and non-ignored candidates; Android `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleDebug` PASS with the local SDK/JDK; the produced debug APK is not a release receipt until the final committed revision is rebuilt. Swift/Xcode and iOS Simulator execution are unavailable on this Windows host, so the GitHub macOS workflow is the required iOS build/test evidence. No public Release is current until the tag workflow has passed both build jobs and its downloaded assets have been read back.

## Source-bound build provenance — 2026-08-17

Windows verification now performs a clean deterministic app/unit/lint/androidTest build and then emits a fail-closed JSON receipt that binds a tracked-clean Git HEAD/tree to fresh app/test APK mtimes, SHA-256 values, package/version, signer SHA-256, JAVA_HOME, SDK root, and the selected installed build-tools version. It rejects stale APKs predating the commit and rejects app/test signer mismatch. The shell verifier now uses the same clean Gradle limits and prints HEAD/tree; the richer signed receipt remains the Windows path used for this checkpoint. No receipt is valid until rerun after the final commit.

## Reproducible API 36 review AVD preflight — 2026-08-17

`config/choplab-review-avd.json` pins a ChopLab-only Google Play API 36 x86_64 AVD name, medium-phone profile, 1080×2400/420 dpi display, Japanese locale, 1.0/1.3/2.0 font-scale matrix, 4096 MiB memory, and the headless Bluetooth-emulation workaround. The required `system-images;android-36;google_apis_playstore;x86_64` image was installed through `sdkmanager`, and the isolated `choplab_review_api36_play` AVD was created under a separate AVD home without changing another project's AVD.

The read-only preflight, idempotent provisioner, tracked-process launcher, and emulator-only matrix runner are now repository scripts. The runner refuses non-`emulator-*` serials and keeps physical Pixel evidence separate. Clean commit `9177229de91f2560b93f381fffda26909eaf4d75`, tree `2fe15415cef8a7a2907ea71ac840996a0d847e0b`, passed the deterministic four-test suite in portrait at font scales 1.0, 1.3, and 2.0 and in landscape on `emulator-5592`. All four runs reported `OK (4 tests)`; ChopLab fatal/ANR and Bluetooth fatal counts were zero; font scale and rotation readbacks matched their pre-run values; and the app process was absent after force-stop. This establishes source-bound `COMPOSE_INSTRUMENTATION_PASS` and `FRAMEWORK_NODE_PASS` for that commit, not physical `DEVICE_PASS` or `HUMAN_GO`.

Primary Android guidance and fixed revisions of Oboe, platform-samples, Now in Android, Accessibility Test Framework, Compose samples, and AndroidX Media are recorded in `docs/research/android-audio-accessibility-reference-review-2026-08-17.md`. The review does not justify an automatic Oboe migration and leaves real routes, xruns, microphone behavior, TalkBack speech, touch feel, and subjective audio in `DEVICE` / `HUMAN_GO`.

## Archive fuzz, audio oracles, and realtime filter cost — 2026-08-17

Archive validation now has an independent literal schema-1 fixture, a deterministic 256-input malformed corpus, a 1,000-small-entry rejection case, and a declared-PCM expansion case above the 512 MiB project budget. ZIP inflation is bounded by the manifest's audio-count/frame-count contract, the absolute PCM budget, and exact WAV/raw entry sizes; a ratio-only rejection is intentionally avoided because a legitimate silent WAV is also extremely compressible. Schema 1–5 compatibility remains covered.

Transient tests now require markers near four known onset frames and cover silence, short input, slice limits, and minimum-distance clustering. Pattern rendering now has independent PCM observations for vocal duration, continuous loops, straight versus swung event frames, reverse, pitch, gain, and tone instead of relying only on non-zero output and length. `SamplerEngine.Voice` precomputes its low-pass coefficient at start/live-control updates rather than running `pow`/`exp` per sample; bypass continuously tracks the current sample so re-enabling the filter does not resume stale state. These are LOCAL deterministic and cost-structure claims, not physical xrun, latency, or audio-quality evidence.

## New-project wording and recovery disclosure — 2026-08-16

The former `RESET ALL / 完全リセット` copy overstated what the recoverable autosave design does: starting fresh empties the current production state, while up to three verified app-private generations remain available for corruption recovery under PROJ-004. The action is now named `NEW PROJECT / 新しい制作を始める`, its second press says exactly that the production state will be emptied, and the privacy policy discloses the bounded recovery retention. No autosave, project, or exported user file is deleted by this wording correction. This is a LOCAL specification-truth fix, not a secure-erasure claim.

## Compact portrait and scratch accessibility — 2026-08-16

The phone-first portrait layout now keeps header, mode, primary control, page selector, PAD edit/stepper, Layer Studio, scratch selector, and compact waveform-control rows at a 48 dp minimum on 360 dp-class widths. Large Android font scales no longer cause compact machine-button typography to shrink from its normal `sp` value. The scratch platter now publishes a stopped/active state plus explicit start, stop, left, and right accessibility actions with fail-closed no-op behavior when audio is unavailable or the action does not match the current state. Focused JVM policy tests and androidTest compilation pass. This is LOCAL semantics/layout evidence; the dense 16-step lane needs a separate responsive editing design to make every cell 48 dp without breaking the no-scroll contract, and TalkBack wording, physical one-hand comfort, and audible scratch feel remain HUMAN/DEVICE boundaries.

## Playback UI truth and recording edit ownership — 2026-08-16

PAD preview now uses the same engine-and-state stop boundary as other primary playback transitions, so source, transport, loop, scratch, and pending-source UI cannot remain falsely active after the engine is silenced. Project mutations, Undo, and Redo are rejected while any MIC/DEVICE/VOICE recording phase owns the session; Undo/Redo buttons expose the same disabled state. A pure recording-policy matrix covers every kind and STARTING/RECORDING/STOPPING phase. This is LOCAL state-contract evidence.

## Archive zero-progress read hardening — 2026-08-16

The project reader now rejects an `InputStream` that returns zero bytes for a positive-length read. Both bounded manifest reads and legacy raw-PCM reads share this fail-closed progress contract, eliminating an attacker-controlled infinite loop while preserving schema 1–5 behavior. A deterministic zero-progress stream test and the complete archive compatibility suite pass locally.

## App-owned capture privacy cleanup — 2026-08-16

Microphone, vocal, and system-capture WAVs now pass through one allowlisted app-cache owner. Successful decode, decode failure, operation invalidation, recorder start failure, reset discard, and ViewModel teardown delete the exact owned temporary file. Startup performs an IO-thread sweep only for ChopLab-named capture WAVs older than 24 hours. SAF imports, exported WAVs, project archives, and unrelated cache files are outside this deletion boundary. Deterministic filesystem tests cover success/failure cleanup and reject similarly located but non-owned names. This is LOCAL privacy evidence; Android process-death timing remains DEVICE-only.

## Playback-capture teardown and queue-clear hardening — 2026-08-16

System-audio capture now owns one generation-tagged session at a time. STOP during setup prevents the recorder from reaching `RECORDING`; STOP during a blocking read first requests `AudioRecord.stop()`, then releases the recorder after a bounded wait, and terminalizes the foreground service within a 1.5 s + 0.5 s local contract. A stopped/old worker cannot clear or publish over a newer generation. Deterministic fake/worker tests cover startup cancellation, generation isolation, and the stop/release fallback; physical `AudioRecord`/`MediaProjection` ordering remains DEVICE-only.

The bounded realtime queue also tags reserved offers with a clear generation. An offer reserved before `clear()` but materialized afterwards is discarded and releases its capacity instead of surviving shutdown. The audio-thread poll remains lock-free. This is LOCAL concurrency evidence; sustained device command pressure and audible continuity remain DEVICE/HUMAN boundaries.

## Autosave revision and recovery ordering hardening — 2026-08-16

Each autosave generation now has a synced revision record bound to the archive SHA-256. `AtomicProjectStore` re-reads verified on-disk revisions before every save, rejects older or conflicting equal revisions even after store/process recreation, rotates archive/metadata pairs together, and chooses the highest verified revision during recovery instead of allowing a stale pending file to outrank a newer backup. Legacy archives without revision metadata remain readable using the established generation priority. This is LOCAL persistence evidence; crash-at-every-filesystem-instruction fault injection and storage-device durability remain outside the current proof.

## Runtime command lifecycle hardening — 2026-08-16

`SamplerEngine` command admission is now serialized with the same lifecycle boundary used by `start()` and `shutdown()`. Runtime commands admitted before shutdown are cleared by shutdown; commands racing after the stopped state are rejected and cannot remain in the mailbox for a later restart. Deterministic JVM regressions cover both the stopped/restarted sequence and concurrent producer/shutdown ordering. This is LOCAL concurrency evidence only; physical output latency, sustained command pressure, and audible behavior remain DEVICE/HUMAN boundaries.

## Virtual microphone lifecycle hardening — 2026-08-16

`MicrophoneRecorder` now exposes an internal recorder-input seam so its public `start(file)` / `stop()` contract can be exercised without a physical microphone. A deterministic latch-driven regression proves that a stop requested while recorder initialization is blocked publishes the stopped state, prevents a late worker from calling `startRecording()`, and releases the newly created input. Focused RED/GREEN and the full JVM/lint/app/androidTest build gate passed. This is LOCAL lifecycle evidence only; it does not claim physical microphone timing or audio quality.

## Final Pixel 9a waveform evidence — 2026-08-16 22:06 JST

Clean commit `233297e39f404bb8e0080110c3d29a528dd8c615` was rebuilt and installed in place on Pixel 9a `5A121JEBF08094` with no uninstall or data clear. The app and installed-base SHA-256 both equal `9A3997B78D309A2B53C78A6B0DB2970D02E08DC656314B8F91F0A2F8BF1C9162`; the test APK and installed test-base both equal `BE2588A01083D16F14CA01B6A3BAEAB086D5D0A03A36FE10238B5E05A4456DCE`; package/version is `com.choplab.sampler` `0.13.1 (21)` and both use signer `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`. Pixel instrumentation passed all four deterministic waveform tests in 7.484 seconds. The three autosave generations were byte-identical before install, after install, after actual TalkBack operation, and after a 929 ms cold relaunch. The bounded fatal/ANR query returned zero matches.

The real Google TalkBack service and touch exploration were enabled on the physical Pixel. A TalkBack one-finger next gesture moved Android accessibility focus into the formerly occluded S/clustered-marker region, producing a visible focus ring while the framework tree exposed S, E, chop 1, and chop 2. This is actual service/focus-path evidence, but ADB cannot reliably select TalkBack's local custom-action menu or assert TTS output, so complete spoken order and spoken quality remain a human acceptance boundary. At completion, ChopLab was force-stopped and the exact pre-run phone state was restored: X foreground, accessibility services `null`, accessibility enabled `0`, touch exploration `null`, accessibility stream volume `1`, media volume `0`, and automatic rotation `1`.

This establishes `LOCAL_PASS`, exact retained-data `DEVICE_DEPLOY_PASS`, deterministic `INSTRUMENTATION_PASS`, `FRAMEWORK_NODE_PASS`, actual TalkBack service/focus-path evidence, the previously recorded bounded real-microphone ownership matrix, cold-relaunch persistence, and safe device restoration. Subjective one-hand comfort, audio quality, and exact spoken TalkBack wording/order remain `HUMAN_GO`; provider/public gates are not changed.

## Waveform virtual-evidence follow-up — 2026-08-16

Candidate `1208abb` added an explicit waveform accessibility click and hardened the evidence runner for deterministic Gradle limits, a fixed androidTest artifact, and signer-first retained-data upgrades. Its historical Pixel receipt remains bound only to the older source. The current API 36 AVD is provisioned separately and its final automation receipt must bind the current clean source; neither AVD result may reuse or promote the `233297e` physical `DEVICE` evidence below.

## Waveform device-evidence hardening — 2026-08-16

The deterministic waveform instrumentation adds two official Android accessibility layers: Compose Accessibility Test Framework checks and Android framework-node inspection through `UiAutomation` / `AccessibilityNodeInfo`. A historical dedicated API 36 AVD receipt showed S, E, and five clustered/endpoint chop handles in framework depth-first tree order, with accessibility-focus actions and a custom nudge. The Google Play API 36 image and isolated review AVD are now reproducibly provisioned; the current-candidate automation run is reported independently from that historical receipt. UI Automator 2.4.0 remains pinned for device-level E2E support.

The waveform instrumentation no longer opens `MainActivity` or consumes the user's restored project. It renders a fixed 1,000-frame in-memory PCM fixture directly through `WaveformEditor`, with deterministic S/E and chop-marker positions. Tests distinguish Compose accessibility semantics callbacks from a running TalkBack service, check no-op action results, true two-pointer pinch/pan, 48 dp width and height, canvas clipping, endpoint handles, and exact reversible nudges. Overview drawing now uses a host-tested pure geometry contract.

`scripts/collect-device-evidence.ps1` binds one clean Git HEAD/tree to Gradle output, app/test APK hashes and signers, exact serial, installed signer preflight, autosave before/after, data-preserving `adb install -r`, installed-base readback, instrumentation stdout, package dumps, and bounded logcat under one run ID. It contains no uninstall or clear-data path and aborts before installation on signer mismatch.

This milestone can establish `LOCAL_PASS`, `COMPOSE_INSTRUMENTATION_PASS`, `FRAMEWORK_NODE_PASS` and, after an exact-device run, scoped install/readback/data-retention evidence. Framework order/action exposure does not by itself establish TalkBack's own spoken output, subjective one-hand comfort, audio quality, or `HUMAN_GO`.

Last prepared: 2026-08-15

## Post-v0.13.1 drum/loop/source interaction maintenance — 2026-08-15

Four user-reported interaction failures are corrected in the local candidate. BANK B can now be selected while empty and points directly to the built-in drum-kit choice instead of silently refusing the tap. In the Beat workspace, tapping a sound rail selects the PAD without auditioning it, so pressing Loop starts one selected loop rather than overlapping an automatic preview; Sample Layer and Scratch retain their intentional audition behavior. Starting source replacement immediately silences the current engine session and blocks new playback until decoding finishes, preventing old A01-style or source audio from continuing under the loading state. Scratch gesture ownership now tolerates ordinary event spacing with a 120 ms idle boundary instead of the former 42 ms cutoff.

These policies were established through five RED/GREEN regression slices, then reviewed against the user flow and repository standards. Review found and corrected two cross-surface issues: preview removal is scoped only to Beat, and source replacement owns one early stop boundary rather than stopping again after decode. The review was a local parent Standards/Spec two-pass because the child surface did not expose effective runtime-model metadata; no substitute child-model claim is made.

Final local gate: configured `scripts/validate_project.sh` PASS; `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --offline --no-daemon --console=plain --max-workers=1 --no-watch-fs` BUILD SUCCESSFUL; 213 tests in 44 suites with zero failures/errors/skips; Android Lint 12 warnings and no errors. Debug APK: `app/build/outputs/apk/debug/app-debug.apk`, 31,707,538 bytes, SHA-256 `5D9EC3A4F86CD0EE36B636E8E9A7C182AE7B64A18A98E6B82F86E02A647C8AAB`. Unsigned release APK: `app/build/outputs/apk/release/app-release-unsigned.apk`, 23,603,385 bytes, SHA-256 `E862905F40A5D934FDB8A0E76302347C488EAB4B6334056E3D223C184F3E70F8`.

This checkpoint is `LOCAL_PASS` only. Pixel 9a is reserved by the Sanpo device lane, so no ADB, install, input, emulator, provider, GitHub, tag, Release, or public-artifact operation was performed. Physical confirmation of BANK B selection, loading-time silence, selection-to-loop single playback, and continuous scratch feel remains pending after the device reservation is released.

## Post-v0.13.1 Pixel 9a retained-data install — 2026-08-15

Exact local maintenance commit `0bdf31c7701612c6c147b6ab9c19b00144bbf714` / tree `75993d0c89d126c926d087858f48dbfe1ae95e1b` is now installed on physical Pixel 9a `5A121JEBF08094` (Android 17/API 37) without uninstalling or clearing app data. The installed package advanced from `0.12.0`/19 to `0.13.1`/21 using `adb install -r`. The pulled installed APK exactly matches `outputs/ChopLab-v0.13.1-0bdf31c-local-debug.apk`: 31,046,270 bytes, SHA-256 `507181B5AA3ED958EAF45004189964723DCBE58D27823B4E1456EC6156426172`, with the same local debug certificate SHA-256 `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587` verified before installation.

All four existing project archives were exported as binary backups before installation. Their exact paths, byte lengths, and SHA-256 values remained identical before install, after install, and after cold launch. The launch returned `Status: ok`, left `MainActivity` top-resumed with a live process, showed zero recent fatal/ANR lines and zero historical crash/ANR exits, and produced a 176-node hierarchy with zero scrollable nodes. Visual review showed the canonical fixed Chop screen, restored source waveform/status, and A01-A16 as `EMPTY`. The current local APK and the public v0.13.1 preview were copied to unique phone Download paths without replacing existing files; the public APK remains download-only because its CI certificate differs from the installed/local certificate.

This establishes scoped `DEVICE_PASS` for exact APK identity, retained-data update, project-archive preservation, cold launch, process survival, and fixed no-scroll UI capture. It does not establish physical playback-interruption behavior, route-loss handling, focus contention, recording behavior, subjective no-double-audio listening, scratch feel, audio quality, or `HUMAN_GO`. Machine-readable evidence is under `work/pixel9a-0bdf31c-install-20260815-123455/`; the user-facing receipt is `outputs/ChopLab-v0.13.1-0bdf31c-Pixel9a-install-receipt.md`.

## Post-v0.13.1 local playback teardown ordering maintenance — 2026-08-14

The playback interruption owner now enforces one safety sequence: enqueue engine-wide silence before abandoning Android playback focus. The same implementation is used by Android interruption callbacks and active-session ViewModel teardown, while explicit in-app stops retain their existing engine-stop-then-focus-release order. `SamplerViewModel` receives a truthful `PlaybackInterruptionOutcome`, projects stopped UI state, and no longer sends a duplicate engine stop after the coordinator has already done so. UI, project schema, project bytes, audio assets, permissions, and version metadata are unchanged.

Three focused RED/GREEN slices established interruption order, active close order, and the stopped-outcome contract. A fixed-point review from `830936774d67f15e44fafc244cfc4fc1548ef3ae` then concentrated duplicated teardown logic and added regression coverage proving repeated interruption silences once while a recording-only interruption does not silence playback. Review execution was a local parent Standards/Spec two-pass because the child surface did not expose effective runtime-model metadata; no substitute child-model claim is made.

Final local gate: configured `scripts/validate_project.sh` PASS; `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --no-daemon --max-workers=1 --no-watch-fs` BUILD SUCCESSFUL; 210 tests in 44 suites with zero failures/errors/skips; Android Lint 11 warnings and no errors. Local debug APK: `app/build/outputs/apk/debug/app-debug.apk`, 31,046,270 bytes, SHA-256 `507181B5AA3ED958EAF45004189964723DCBE58D27823B4E1456EC6156426172`. Local unsigned release APK: `app/build/outputs/apk/release/app-release-unsigned.apk`, 23,603,385 bytes, SHA-256 `B0DD9596A33876AD851998D3B7ED2F78EFC9A3A6A5671C0448B7C0551E9F4F21`.

This is `LOCAL_PASS` only. No Pixel, emulator, ADB, provider, GitHub, tag, Release, or public-artifact operation was performed for this maintenance checkpoint. Prior public v0.13.1 evidence below remains historical evidence for its tagged bytes and must not be attributed to this local change.

## v0.13.1 playback interruption safety candidate — 2026-08-14

Playback now has one explicit Android interruption owner without adding another UI surface. Every audible source, preview, PAD, Beat loop, transport, vocal-monitor, and scratch start acquires or reuses one media/music audio-focus session before touching the engine. Home/background, permanent or transient focus loss, duck requests, and wired/Bluetooth output loss stop playback once and release focus; focus gain never auto-resumes stale audio. Source seek and live KEY retargets may restart the source only while the coordinator still owns the active focus session.

`MainActivity.onStop()` routes genuine background transitions to the session owner but ignores configuration recreation, so rotation does not cut an intentional performance. Microphone and vocal recording request a graceful stop when their audible reference is interrupted. Android Playback Capture is the deliberate exception: its recording continues when switching to another app, while ChopLab's own playback stops. Existing project bytes, PAD assignments, autosaves, history, schema, and UI layout are unchanged.

Independent Standards and Spec review passes covered repository rules and the ExecPlan contract, followed by a final parent-side verification. Their hard findings were corrected: state/evidence docs are updated, unknown future non-gain focus callbacks fail closed as interruptions, and source seek/KEY retargets now prove coordinator ownership instead of trusting UI state alone. The child runtime did not expose effective model metadata, so this milestone does not claim runtime-verified Luna execution. The remaining `SamplerViewModel` orchestration size is recorded as a maintainability judgement call, not a behavior or release blocker.

Final local gate: configured `scripts/validate_project.sh` PASS; `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --no-daemon` BUILD SUCCESSFUL; 207 tests in 44 suites with zero failures/errors/skips; Android Lint zero errors and 11 advisories. Exact local APK: `outputs/ChopLab-v0.13.1-playback-interruption-safety-local-debug.apk`, 30,821,319 bytes, SHA-256 `9A11118395AEC68AF6A739416514135FAEFF562302EB541573A49CF48A038668`; package `com.choplab.sampler`, versionCode 21, versionName 0.13.1, minSdk 29, targetSdk 36, APK Signature Scheme v2, local debug certificate SHA-256 `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`.

The exact APK installed in place on the dedicated tracked Android 16/API 36 emulator `emulator-5590`. Playback held one `USAGE_MEDIA` / `CONTENT_TYPE_MUSIC` focus entry with `PAUSES_ON_DUCKABLE_LOSS`; Home removed the live focus entry and returning showed `バックグラウンド移行のため再生を停止しました`. Focus remained owned before, during, and after portrait/landscape configuration recreation, then `ALL STOP` emptied the stack. The protected `ACTION_AUDIO_BECOMING_NOISY` broadcast cannot be spoofed by the shell, so physical wired/Bluetooth route-loss behavior remains a device-only check.

Provider verification is complete for exact tagged commit `903c698c2fdc443027a8190aa31985253ff3050a`: branch push run `31764219592`, PR run `31764223167`, tag verification run `31764417666`, and Release run `31764417670` all succeeded. Annotated tag object `b11eaa13be6c7e4d8bc7cbfcf805dc8ab25dc436` peels to that commit. The [public v0.13.1 preview](https://github.com/dj-thank/choplab-sampler/releases/tag/v0.13.1-preview.1) is a non-draft prerelease. Its 30,821,319-byte APK has SHA-256 `5EE5183C2CA6574E964CC4A6AE44B4BE72813A691843345D9FA78B5ADE6598D6`; GitHub's asset digest, checksum sidecar, authenticated reverse download, and anonymous download all match. Repository, Release page, and direct APK returned anonymous HTTP 200. Public metadata is package `com.choplab.sampler`, versionCode 21, versionName 0.13.1, targetSdk 36, APK Signature Scheme v2, certificate SHA-256 `A04BC943A7F0C31ABC619839CDE0B28B2165700DE2F57D501F5B9DA0D0F9A2E2`.

Pixel 9a `5A121JEBF08094` is not connected. Therefore `PUBLIC_PASS` is established, but physical retained-data install, actual route loss, another media app/call focus contention, microphone/system-capture behavior, subjective no-double-audio listening, and `HUMAN_GO` remain pending. The local retained-data APK and public CI APK have different debug certificates; the prepared `work/install-v0131-pixel9a.ps1` installs only the matching local build and copies both verified artifacts to Downloads without clearing app data.

## v0.13.0 Luna interaction integrity candidate — 2026-08-14

Twenty independent `gpt-5.6-luna` medium/default reviews examined the fixed mobile console from beginner flow, destructive-action safety, realtime audio, recording, persistence, accessibility, waveform performance, scratch behavior, open-source readiness, and release-evidence perspectives. Every accepted packet was runtime-verified before synthesis. Two fixed-point Standards/Spec reviews and a final independent verifier were also runtime-verified; the Spec pass found that the first full-Bank-A correction could still move selection to A01, and that path was corrected and regression-tested before the final verifier reported no P0-P2 blocker.

The fixed no-scroll UI and four-stage model remain intact, but their runtime contract is stricter. Destructive live Chop capture occurs only after a completed tap, so beginning a long press cannot overwrite a PAD before precision trim opens. Melody Chop defaults only to a genuinely empty Bank A PAD; when all 32 A PADs are occupied, the app keeps the current bank and selection and asks for an explicit overwrite or clear instead of spilling into drums or silently choosing A01. REC now starts transport when armed and records an immediate first PAD hit deterministically. Material-dependent workflow tabs disable truthfully, project/source replacement reconciles the active stage and kit state, and operation-specific permission guidance survives the Android permission round trip.

Realtime PAD state now crosses to the audio thread through a fixed-capacity indexed latest-wins mailbox, while Stop All establishes an out-of-band transport-and-voice silence boundary even if the normal command queue is saturated. Recording teardown keeps STOPPING ownership until its asynchronous result completes, and failed vocal capture clears its monitor loop. Source/preview/loop/scratch exclusivity, project-revision guards for transient analysis, rate-independent scratch velocity, PAD-range scratch targeting, cached waveform envelopes, bounded readouts, and TalkBack waveform actions were tightened without changing the archive schema. Built-in drum kits remain original deterministic synthesis; no artist-named or downloaded copyrighted sample pack was added. `PRIVACY.md`, `NOTICE`, issue templates, README provenance/privacy links, and the feature/evidence matrix now describe the open-source boundary.

Final local gate: `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --no-daemon --max-workers=1 --no-watch-fs` exited 0 with BUILD SUCCESSFUL; 194 tests in 42 suites, zero failures/errors/skips; Android Lint zero errors and 10 advisories. Configured `scripts/validate_project.sh` passed its pure Kotlin smoke, all four XML parses, and Gradle Wrapper SHA-256 `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`. `git diff --check` passed and the UI scroll API scan found zero matches.

Exact local candidate: `outputs/ChopLab-v0.13.0-luna-interaction-integrity-local-debug.apk`, 30,804,939 bytes, SHA-256 `3438CCD65D3C84BAEA47B9385B1EF465ED9A2E517C155D7A7E0C93E4D6FFB56B`; package `com.choplab.sampler`, versionCode 20, versionName 0.13.0, minSdk 29, targetSdk 36, APK Signature Scheme v2, local debug certificate SHA-256 `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`.

Android 16/API 36 emulator `emulator-5588` accepted the exact APK with `adb install -r`; the pulled installed base matched the host byte size and SHA-256. Its three retained archives kept identical pre-install/post-install/post-launch values: autosave 10,529 bytes / `06689B6194D18E3808E7CBB9533F8B9D4A13D0093676B39DA89046362E5B1128`, previous 23,614 bytes / `5D81576BDB43F0ABD549947B38C050698A8610EDF932288DD8225E1AA3471BF8`, and previous2 23,594 bytes / `2E0111AD2F586344A23071A69D1455605B573A98851C60B43CD32821E51B2D0B`. Cold launch completed, package-scoped exit history contained zero crash/ANR reason, and the captured Chop hierarchy had 175 app nodes and zero scrollable nodes. The visible A Melody page contained 16 empty PADs, so no old A01 sample was revived in this recovered project. Evidence is under `work/v013-emulator/`.

Provider verification is complete for exact commit `61f1044610ee172785d87478659862fb4f342be3`: branch push run `31724970140`, PR run `31724972880`, tag run `31725302532`, and release run `31725302549` all succeeded. Annotated tag `v0.13.0-preview.1` peels to that commit. The public prerelease is `https://github.com/dj-thank/choplab-sampler/releases/tag/v0.13.0-preview.1`; its 30,804,939-byte APK has SHA-256 `B25E018C8743D9EC7459FDDF5698F008E41D34D7FB34336961865B34F867C86A`. GitHub's asset digest, the checksum sidecar, an authenticated reverse download, and a separate anonymous HTTP download all match. Repository, Release page, and direct APK returned anonymous HTTP 200. The public package metadata is versionCode 20 / versionName 0.13.0 / targetSdk 36, APK Signature Scheme v2, certificate SHA-256 `5B499749A2C9392A90DB2C099E6EAD00D49D90A89DC1B9A36577959EED411182`.

The physical Pixel 9a serial `5A121JEBF08094` is not currently enumerated by ADB, mDNS, or Windows USB inventory; only the emulator is attached. Therefore `PUBLIC_PASS` is established for source/release availability and artifact identity, but no physical-Pixel `DEVICE_PASS`, subjective no-double-audio listening, microphone/system capture quality, scratch feel, or `HUMAN_GO` is claimed. The public CI debug certificate differs from the installed/local certificate, so no data-destructive public-APK replacement is attempted. A data-preserving local APK install and phone Download copy remain the physical-device gate.

## v0.12.0 recording/source session integrity — 2026-08-13

Capture PAD routing and recording state now have one explicit contract. While the source is applied as playing in the Chop Capture surface, tapping an assigned A01-style PAD no longer auditions its retained audio: it overwrites that PAD with the current source at the observed playhead. The overwrite preserves deliberate performance parameters and pattern placement, but clears any live loop/scratch reference to the replaced voice before updating the engine. Loading or recording a new source still uses the existing separate-project replacement path, which clears all old PAD audio, steps, runtime voices, and edit history.

Source playback has an applied-state boundary: PAD capture waits through both STARTING and STOPPING, so a stale playhead cannot overwrite A01 while the audio thread is still changing state. Source, range preview, Beat loop, transport, PAD scratch, and source scratch now share one exclusive primary-playback transition that stops existing voices before starting the next mode. This removes the former path where the UI requested a source stop but started a Beat loop without sending the corresponding engine stop, producing doubled audio. Ordinary PAD hits remain available for deliberate drum and layer performance after a primary mode starts.

Reset now treats an active device-audio capture as STOPPING until its service result arrives. Late Recording notifications from a capture marked for discard cannot reopen the recording session, and its eventual file/error result is discarded before the header returns to idle. This keeps a blank reset project from regaining stale recording state.

The former independent microphone/system/vocal booleans are replaced by a runtime-only `RecordingSession` with one kind and `STARTING / RECORDING / STOPPING` phases. Every recording begins after a real engine-wide playback stop; Vocal overdub alone intentionally restarts its selected Beat loop after the recorder starts. PAD performance/capture, source start/retarget, preview, transport start, loop start, and scratch start all reject new playback while a recording session is active. Late service `Recording` observations cannot reverse a prior STOPPING request. Project/open/import/export pickers stay disabled until recording and its decode/save phase finish; the fixed header becomes a kind-specific STOP from every workflow stage, and Layer Studio exposes the same STOP when its modal obscures the header. Archive schema and encoded project bytes are unchanged because recording session state is runtime-only.

The original symptoms were observed RED in focused host tests: assigned Capture PAD routed to preview, active recording did not block PAD playback, and live chop refused overwrite. A later review test also exposed a stale service notification that could regress STOPPING to RECORDING; all are GREEN after the domain change. Final local gate: 179 tests in 38 suites with zero failures/errors/skips; `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --no-daemon --max-workers=1 --no-watch-fs` BUILD SUCCESSFUL; Lint errors 0 / advisories 10; `git diff --check` PASS; UI scroll-container scan 0 matches. The standalone `scripts/validate_project.sh` launcher could not complete because this Windows shell has no standalone `kotlinc`; its underlying smoke was run with the cached Kotlin 2.3.21 compiler and passed, all 4 Android XML files parsed, and the Gradle Wrapper SHA-256 matched `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`. The script source now includes `RecordingSession.kt` for environments that provide `kotlinc`.

Exact local preview APK: `outputs/ChopLab-v0.12.0-recording-source-integrity-local-debug.apk`, 31,643,454 bytes, SHA-256 `8AA11856647F6D830E574AF143460FA418AB7BD47A4CE21EBD5254632C1CA574`; package `com.choplab.sampler`, versionCode 19, versionName 0.12.0, minSdk 29, targetSdk 36, APK Signature Scheme v2, debug certificate SHA-256 `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`. The exact APK installed with data-preserving `adb install -r` on Pixel 9a `5A121JEBF08094` (Android 17 / 1080×2424), and the pulled installed base matched size and SHA-256. The four on-device autosave files retained identical SHA-256 values before install, after install, and after launch. `MainActivity` started and remained the app task, but the Pixel keyguard stayed locked, so no final-hash visual interaction or physical audio claim is made. An earlier near-final emulator build observed zero scrollable nodes, visible MIC STOP through Capture and Chop, assigned-A01 recording-time playback rejection, Layer Studio modal STOP, and successful stop/decode transitions. `connectedDebugAndroidTest` previously completed successfully but the project currently contains 0 instrumentation cases. Subjective duplicate-audio listening, latency, system Playback Capture, public release, and `HUMAN_GO` remain unclaimed.

## v0.12.0 Production Dock contract and truthful autosave recovery — 2026-08-13

The Dock-specific part of the GPT Pro P2 display-drift concern is now addressed by a pure `ProductionDockPolicy`: Capture, Chop, and Beat receive immutable items containing intent, label, enabled/active state, weight, and confirmation copy. One renderer resolves every item through an explicit handler map, so Capture keeps RESET/START truth, Chop keeps BEAT/PAD EDIT/ADD/SCRATCH, and Beat Quick/Steps both keep QUICK/STEPS/ADD/SCRATCH without repeating labels or state branches inside Composables. The wider `OtohiroiDeck`/`SamplerViewModel` decomposition remains separate architectural work.

Physical-device acceptance exposed one adjacent state-truth problem: loading the retained 32 MB-class autosave takes several seconds, while the old initial state briefly claimed `NO SOURCE`. Startup now publishes `isLoading=true` before recovery, disables new import/recording starts while keeping an already-active recording's STOP control available, and shows `LOADING / 音声を読込中 / PLEASE WAIT`; valid recovery, no-project, and failure paths all leave loading state explicitly. Project schema, archive bytes, playback engine, PAD model, and DSP are unchanged.

Both Dock and recovery seams were observed RED before implementation and GREEN afterward. Final local gate: configured `scripts/validate_project.sh` PASS; Gradle `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline --no-daemon --max-workers=1` BUILD SUCCESSFUL; 169 tests in 37 suites with zero failures/errors/skips; Android Lint zero errors and 7 advisories; `git diff --check` PASS; UI scroll API scan zero matches. The exact APK is `outputs/ChopLab-v0.12.0-production-dock-contract-local-debug.apk`, 31,615,690 bytes, SHA-256 `B0CF6B6DFE21FF24B5AC5BD457E6EEE637B75BFDB4EA438044CB84A5A07B1C29`; package `com.choplab.sampler`, versionCode 19, versionName 0.12.0, minSdk 29, targetSdk 36, APK Signature Scheme v2, debug certificate SHA-256 `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`.

Physical Pixel 9a `5A121JEBF08094` (Android 17 / API 37 / arm64-v8a) accepted the exact APK through data-preserving `adb install -r`. The host APK, pulled installed base, and `/sdcard/Download/ChopLab-v0.12.0-production-dock-contract-local-debug.apk` have identical size and SHA-256. The loading screen and restored Capture/Chop/Beat screens each expose zero scrollable nodes; during recovery the FILE, MIC REC, and DEVICE REC parent buttons report `enabled=false`; Chop and Beat expose all four expected Dock actions; the app process remained alive with zero focused fatal/ANR matches. Four retained project archives kept identical sizes and SHA-256 before install, immediately after install, and after recovery/navigation. This is a scoped `DEVICE_PASS` for install, recovery truth, fixed UI, and data preservation—not subjective audio quality, duplicate-audio listening, TalkBack traversal, public release, or `HUMAN_GO`. Evidence is under `work/pixel9a-v0120-dock-contract/`.

## v0.12.0 state-truth playback and integrated Production Dock — 2026-08-12

The four-stage `入れる / チョップ / ビート / 保存` console now uses one audio-thread-grounded source state across Capture and Chop: `STOPPED / STARTING / PLAYING / STOPPING`. A queued start cannot capture into an empty PAD, a queued stop cannot be shown as already stopped, and source replacement, full reset, and project restore keep the last applied playback truth until the engine poll confirms silence. Runtime-only command intent is excluded from project archives and Undo/Redo snapshots. `ALL STOP` now retires the out-of-band voice boundary before stopping transport and clears transport, step, loop, and scratch UI without discarding an active recording.

Stage tabs only navigate; they no longer restart the source or silently select BANK A. The explicit Capture action prepares the first empty A Melody PAD and starts Chop. Beat Quick and Steps now retain the same fixed `QUICK / STEPS / ADD / SCRATCH` Production Dock, while Chop keeps `BEAT / PAD EDIT / ADD / SCRATCH`. The existing original cream/charcoal/orange/green language, no-scroll surface, square 4 x 4 Chop PADs, four BANK roles, 32 PADs per BANK, precision trim, drums, vocals, Beat Loop, and source Scratch remain intact.

The user-requested GPT Pro consultation used one privacy-scanned ZIP containing all 188 Git-tracked files; the response confirmed all 193 packet entries before proposing the bounded state-truth/dock slice. The review bundle is `outputs/ChopLab-gpt-pro-integrated-ui-fullrepo-review-bundle-20260812-r2.zip`, 812,286 bytes, SHA-256 `4B33ED6939D4B6505A2BF5CD5BB8F67FA0928DAFCE52D074D9AB343998F7E989`; the accepted transcript is 47,282 bytes, SHA-256 `D27FFE3765E23A3CE7E05A138F387BD8874B5AD260C9441462EBFBB78CE7C52E`. ChatGPT Pro account/UI was observed, but the provider did not expose a stronger underlying-model label, so no exact model claim is made.

Focused contracts were observed RED before implementation, including the review-found replacement/reset state-truth regression, runtime-only Undo intent, and truthful ALL STOP completion copy, then GREEN. Final local gate: configured offline validation PASS; 163 unit tests in 36 suites with zero failures/errors/skips; Android Lint PASS with zero errors and 10 platform/toolchain advisories; debug assemble PASS; `git diff --check` PASS; UI scroll API scan zero matches. The local APK is `outputs/ChopLab-v0.12.0-production-dock-local-debug.apk`, 31,592,862 bytes, SHA-256 `028737528EE6211DE8A9497216161FA870E4AAB8D4C193CE9C45AC26771A966F`; package `com.choplab.sampler`, versionCode 19, versionName 0.12.0, minSdk 29, targetSdk 36, APK Signature Scheme v2, certificate SHA-256 `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`.

Physical Pixel 9a `5A121JEBF08094` (Android 17 / API 37 / arm64-v8a) accepted the exact APK through data-preserving `adb install -r`, upgraded 0.10.0 to 0.12.0, cold-launched with the process alive, and produced zero focused fatal/ANR matches. Capture, Chop, and Beat dumps each contained zero scrollable nodes; Chop visibly retained square 4 x 4 PADs and its four-action dock, while Beat exposed Quick, Steps, Add, and Scratch together. All four pre-existing project archives retained identical byte sizes and SHA-256 values before install, immediately after install, and after launch/navigation. This is a scoped `DEVICE_PASS` for install, launch, fixed UI, and data preservation—not subjective audio quality, duplicate-audio listening, TalkBack traversal, landscape/font-scale coverage, public release, or `HUMAN_GO`.

## One-action Chop start and consolidated frontend local evidence — 2026-08-12

The fixed Chop workspace now has one primary source action instead of separate `PLAY FROM START` and CHOP/PLAY mode controls. `チョップ開始 / START CHOP` restarts the imported source from its beginning; once audio-thread-applied playback is active, the same control becomes `元曲を止める / STOP SOURCE`. During playback an empty PAD captures the current position, while an assigned PAD continues to audition its existing Chop and cannot be overwritten. A second press during pending or active start uses the existing pending-safe stop path.

Portrait and landscape now share the coach/project, source/key, and next-action modules. The full-width duplicate PAD EDIT row was folded into one compact `微調整 / EDIT` action alongside Beat, Add, and Scratch, leaving more fixed no-scroll space for the square PAD grid. Four unreachable legacy frontend definitions (`SourceWorkspace`, `SourceControlDeck`, `SourceEditRows`, and `SourceToolRow`) were removed; this deletes the inactive 4/8/16-split/manual assignment surface without removing the active source waveform, long-press precision trim, PARAM/PLAY editors, banks/pages, drums, vocals, Beat, or Scratch.

The contract was observed RED before `chopSessionPresentation` existed and GREEN after implementation. Final local gate: configured offline validation PASS; 153 unit tests in 34 suites with zero failures/errors/skips; Android Lint PASS with zero errors and 7 platform/toolchain advisories; debug assemble PASS; `git diff --check` PASS; UI scroll API scan zero matches. A fixed-point local-parent review ran as separate Standards and Spec passes because returned child runtime metadata was unavailable; neither pass found an implementation issue, and no substitute child model was used. The local APK is `outputs/ChopLab-v0.11.3-simple-chop-local-debug.apk`, 31,580,082 bytes, SHA-256 `95C9EB3E3F2171E9DC0DA66D5D9C78CC3BF039A2D53C6E864232E8247C84DAC2`. It reports package `com.choplab.sampler`, versionCode 18, versionName 0.11.3, minSdk 29, targetSdk 36, and debug certificate SHA-256 `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`.

The physical Pixel 9a `5A121JEBF08094` is absent from ADB and both known Windows USB records report `Present=False`. The only ADB target is shared `emulator-5588`, which currently has Neefo packages and no ChopLab package; this task did not claim device ownership or mutate it. Exact-final UI interaction, physical touch/audio, TalkBack, landscape interaction, and subjective duplicate-audio listening therefore remain unverified; this section establishes `LOCAL_PASS`, not `DEVICE_PASS`, `PUBLIC_PASS`, or `HUMAN_GO`.

## Precision trim and exclusive loop-start local evidence — 2026-08-12

The current `v0.11.3` development branch improves the beginner Chop path without adding scrolling:

- long-pressing an assigned PAD opens a larger waveform editor with visible time readout and centered zoom up to 256x;
- START and END can be nudged by exactly one frame, approximately 1 ms, or approximately 10 ms, with the existing start-inclusive/end-exclusive clamps;
- `編集前へ戻す / REVERT` restores both boundaries captured when the editor opened, and the restore remains part of the existing Undo history;
- Chop mode now explicitly distinguishes `空PADへ切る / CHOP MODE` from `音ありPADを確認 / PLAY MODE`;
- BANK and page controls expose the active A/B/C/D role, selected PAD, 01–16 versus 17–32 range, and assigned-sound count without relying on color alone;
- starting one PAD beat loop now retires imported-source playback, anonymous waveform preview, and same-PAD audition at the audio-thread boundary, while preserving intentional other-PAD/drum/vocal layers;
- loop start reserves bounded mailbox capacity before issuing its source-stop generation, so rejected commands cannot mutate source state or revive stale generations.

Focused regressions were observed RED before implementation and GREEN afterward. Final local gate: configured offline validation PASS; 153 unit tests with zero failures/errors/skips; Android Lint PASS with zero errors and 7 platform/toolchain advisories; debug assemble PASS; `git diff --check` PASS; UI scroll API scan zero matches. The final local APK is `outputs/ChopLab-v0.11.3-precision-trim-local-debug.apk`, 31,627,306 bytes, SHA-256 `4E255B329D4A8A85194F79B1E106B91D215C3CBFF4FFEB92DEDF1624970CE1A9`. Two independent Luna reviews found no P0/P1 issue. The mailbox-generation finding and its first rollback attempt were both replaced by reservation-before-side-effect admission tests; final audio re-review found no P0–P3 issue, and the trim/UI review found no P0–P3 issue.

Focused pre-final-candidate observation on `emulator-5588` confirmed data-preserving install, cold launch as `versionCode=18` / `versionName=0.11.3`, visible no-scroll Chop and precision-trim screens, assigned-PAD long-press navigation, and zero focused AndroidRuntime fatal signal. The final readout-color and queue-generation corrections were rebuilt locally but not reinstalled because a concurrently running Neefo task took foreground ownership of the same emulator. This is therefore not an exact-final-artifact or exclusive full-audio DEVICE_PASS. Physical Pixel installation, subjective duplicate-audio listening, TalkBack, landscape interaction, sustained command-pressure/realtime stress, production publication, and `HUMAN_GO` remain unverified.

## v0.11.3 clear Chop actions and accessibility candidate — 2026-08-12

最初の音をチョップした後も、固定TIPが `空PAD＝追加／音ありPAD＝試聴・長押し微調整 → ビートへ` と実際の分岐を明示する。従来は一度割り当てると「PAD＝試聴」だけになり、別の空PADへ続けて追加できることが画面から消えていた。操作や画面構成は増やさず、既存の波形、A/B/C/D BANK、正方形PAD、Beat、Add、Scratchを同じ固定コンソールに維持した。

Sol指定の読み取り専用レビューは、視覚TIPの修正後も割当済みPADのTalkBack説明だけが `現在位置をチョップ` と実動作に反していることを検出した。PAD説明を純粋関数へ分離し、割当済みは `タップで試聴。長押しで微調整`、空PADだけは `現在位置をチョップ` と読み分ける回帰テストを追加した。子タスクの実効モデル名はメタデータに露出しなかったため、runtime-verified Solとは主張しない。

Local candidate gate: configured offline validation PASS; 144 unit tests in 33 suites with zero failures/errors/skips; Android Lint PASS with zero errors and 10 warnings; clean debug assemble PASS; `git diff --check` PASS; UI scroll API scan zero matches. Version `0.11.3` (`versionCode=18`) local APK is 30,739,403 bytes with SHA-256 `463C58518F0D47B58DAD75C9DF0F0893D8838DD05372E7C74036FDBBB6908E3C`; package `com.choplab.sampler`, minSdk 29, targetSdk 36, APK Signature Scheme v2, certificate SHA-256 `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`.

Dedicated Pixel 9/API 36 emulator `emulator-5590` accepted that clean APK through `adb install -r`. Its retained 5,317,098-byte autosave stayed byte-identical at SHA-256 `C5B66AF4A464186571FEBE718B307FC411D33D2A2316DBD3D87D2A31D4AE3689`. Normal and Android font-scale 130% captures preserve the full TIP without ellipsis or scrolling; UI XML exposes A-01 as `PAD 01 割り当て済み。タップで試聴。長押しで微調整` and A-06 as `PAD 06 空。現在位置をチョップ`. Evidence is `work/v0113-final.png`, `work/v0113-final.xml`, `work/v0113-final-font130.png`, and `work/v0113-final-font130.xml`; the scoped fatal/ANR query returned zero matches.

PR [#26](https://github.com/dj-thank/choplab-sampler/pull/26) merged as `17d2e203bbece5d1f1be7e46042a0389256596bc`. Branch runs `31540964591` and `31540979286`, main run `31541222720`, tag verification `31541469351`, and release run `31541469492` all passed. Annotated tag `v0.11.3-preview.1` peels to the merge commit and the [public prerelease](https://github.com/dj-thank/choplab-sampler/releases/tag/v0.11.3-preview.1) is available. Anonymous HTTP checks returned 200 for the repository, Release page, and direct APK route.

The reverse-downloaded public APK is 30,739,403 bytes with SHA-256 `D1DB9F44054C239C2B0C9438FB97487B34CB678E7EA4E5366DDEA7BBBF053867`; downloaded bytes, GitHub asset digest, and checksum sidecar match. It reports package `com.choplab.sampler`, versionCode 18, versionName 0.11.3, minSdk 29, targetSdk 36, APK Signature Scheme v2, and certificate SHA-256 `3383BD82CBF84972CFF3A8C8B4EC39061868A2B2B08A05056823FD08CACDCBAA`. This CI certificate differs from the installed local-build certificate, so the public APK was not forced over retained app data.

The physical Pixel 9a `5A121JEBF08094` remains absent from ADB and mDNS, while its two Windows PnP records report `Present=False`. A data-preserving phone install, physical sound/touch quality, and `HUMAN_GO` therefore remain pending; public repository, CI, Release route, and artifact identity establish `PUBLIC_PASS` only.

## v0.11.2 truthful step placement candidate — 2026-08-12

Beatの16ステップ配置は、実際にシーケンサー再生・WAV書き出しできるPADだけを操作対象にするよう統一した。`LOOP` は選択音全体を連続反復する専用モード、`VOCAL` は開始時に一度重ねる録音として扱い、どちらもステップセル、演奏録音、配置プリセットから新しいstepを作らない。選択中はセルを暗く無効化し、`配置できません` と `ループは音声全体を反復。配置は別PAD` / `VOICEは開始時に一度再生` を表示する。通常PADの選択・16-step配置、KEY/TONE/LEVEL、選択音ループ、Add、Scratchは従来どおり同じ固定画面に残る。

旧プロジェクトに残るLOOP/VOCALのstep keyは読込時に破壊的削除せず保存互換性を維持する一方、リアルタイム再生、配置波形、Finish判定、プリセット状態、WAV書き出しでは同じ適格性関数を通して不可聴化した。録音待機中のPAD操作も `LOOP` はループ切替、`VOCAL` は一度の試聴だけにルーティングされ、見えない無効stepを追加しない。

Sol指定の読み取り専用監査と再レビューで、表示だけでなく演奏録音、保存済み旧key、プリセット、Finishまで同じ境界へ揃えた。子タスクの実効モデル名はメタデータに露出しなかったため、runtime-verified Solとは主張しない。

Local candidate gate: configured offline validation PASS; 143 unit tests with zero failures/errors/skips; Android Lint PASS with zero errors and 7 warnings; debug assemble PASS; `git diff --check` PASS; UI scroll API scan zero matches. Version `0.11.2` (`versionCode=17`) local APK is 30,739,403 bytes with SHA-256 `F706923F28495754CCB5B5DFEB42E2D7D89F574A6B27DEE10563A1A83344DAB4`; package `com.choplab.sampler`, minSdk 29, targetSdk 36, APK Signature Scheme v2, certificate SHA-256 `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`.

Dedicated Pixel 9/API 36 emulator `emulator-5590` accepted the local APK through `adb install -r`; its retained autosave stayed byte-identical at the install checkpoint with SHA-256 `76BF3EACA193F877033123590A5360E3D3A083696A812C254B029EB9EA151BF4`. After intentional test edits had created a newer archive, A-04 `LOOP` selection exposed disabled A-step semantics and the dedicated coaching. Pressing disabled step 2 left the stable 5,317,098-byte autosave unchanged at SHA-256 `C5B66AF4A464186571FEBE718B307FC411D33D2A2316DBD3D87D2A31D4AE3689`. Runtime evidence is `work/v0112-loop-disabled-after.png` plus `work/v0112-loop-disabled-after.xml`; the scoped fatal/ANR query returned zero matches.

PR [#24](https://github.com/dj-thank/choplab-sampler/pull/24) merged as `cf6996873b446f61f2e74910e93ad4495e74b263`. Branch runs `31536276746` and `31536297883`, main run `31536570140`, tag verification `31536868910`, and release run `31536868984` all passed. Annotated tag `v0.11.2-preview.1` peels to the merge commit and the [public prerelease](https://github.com/dj-thank/choplab-sampler/releases/tag/v0.11.2-preview.1) is available. Anonymous HTTP checks returned 200 for the repository, Release page, and direct APK route.

The reverse-downloaded public APK is 30,739,403 bytes with SHA-256 `7FE63CEADB27BBA59142EEDBFEB7A346C9F487E6CB00C5CD4B3EB7182EE3FCEE`; downloaded bytes, GitHub asset digest, and checksum sidecar match. It reports package `com.choplab.sampler`, versionCode 17, versionName 0.11.2, minSdk 29, targetSdk 36, APK Signature Scheme v2, and certificate SHA-256 `F100B8D8C189BDBA933779AB2ACCD6BBE374BC7D01E592F92684A26595C6B196`. This CI certificate differs from the installed local-build certificate, so the public APK was not forced over retained app data.

The physical Pixel 9a `5A121JEBF08094` remains absent from ADB and mDNS, while its two Windows PnP records report `Present=False`. A data-preserving phone install, physical sound/touch quality, and `HUMAN_GO` therefore remain pending; public repository, CI, Release route, and artifact identity establish `PUBLIC_PASS` only.

## v0.11.1 live controls and realtime reliability candidate — 2026-08-12

The fixed `入れる → チョップ → ビート → 保存` console now keeps its selected PAD loop running while KEY, TONE, or LEVEL changes are applied. The voice cursor is not restarted, so pitch and timbre can be performed during playback instead of requiring a trip into detailed editing. At Android font scale 130%, the compact fixed layout keeps the important stage, loop, and action labels complete without introducing scrolling.

The AudioTrack control path is now bounded to 512 queued commands and inspects at most 64 entries per render block. Stop All uses a separate sequenced boundary so it cannot be dropped by queue pressure and commands older than the stop are ignored. Thirty-two PAD voices and one source voice are created when the engine is initialized and reused on the realtime thread; normal render, command-drain, transport, and voice-start paths no longer construct `Voice` or `VoicePlaybackCursor` objects. AudioTrack teardown releases the exact owning track even after a write failure, and a replacement engine cannot start while an older render thread is still exiting. Microphone stop now fails closed when its worker has not closed the WAV writer within two seconds, preventing decode of an incomplete recording.

Local candidate gate: configured offline validation PASS; 137 unit tests with zero failures/errors/skips; Android Lint PASS with zero errors and 11 advisories; debug assemble PASS; `git diff --check` PASS; UI scroll API scan zero matches. Version `0.11.1` (`versionCode=16`) local APK is 30,739,399 bytes with SHA-256 `354571D8390BA8F86B20DBEA53E3954912A8FECA47D9171253E38B864FAB4059`; package `com.choplab.sampler`, minSdk 29, targetSdk 36, APK Signature Scheme v2, certificate SHA-256 `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`.

Dedicated Pixel 9/API 36 emulator `emulator-5590` accepted the candidate through `adb install -r`. Before and immediately after install, its retained autosave was byte-identical at 5,316,915 bytes and SHA-256 `3962BB989F4B59F8E98AB6D0C38D02DAAC46DBF6CEFDB49AA752552D2614A513`. Version 0.11.1 cold-launched with the existing waveform and PAD assignments, source Play advanced the visible playhead, Chop and Beat opened, selected PAD A-04 looped with a visible loop playhead, KEY was changed live and returned, and Scratch remained directly reachable. The app process stayed alive and the scoped fatal/ANR query returned zero matches. Runtime captures are under `work/v0111-final/`.

PR [#22](https://github.com/dj-thank/choplab-sampler/pull/22) merged as `755c30ffced5db408d89e37cf80c4caf53f02896`. Branch run `31530032522`, PR run `31530071852`, main run `31530374176`, tag verification `31530698604`, and release run `31530698633` all passed. Annotated tag `v0.11.1-preview.1` peels to that merge commit and the [public prerelease](https://github.com/dj-thank/choplab-sampler/releases/tag/v0.11.1-preview.1) is available. Anonymous HTTP checks returned 200 for the repository, Release page, and direct APK route.

The reverse-downloaded public APK is 30,739,399 bytes with SHA-256 `BB4502733C3382C91BE6391F9A1EADC5E9F3BC5F0B6621E54B179B8BB16F4C65`; downloaded bytes, GitHub asset digest, and checksum sidecar match. It reports package `com.choplab.sampler`, versionCode 16, versionName 0.11.1, minSdk 29, targetSdk 36, APK Signature Scheme v2, and certificate SHA-256 `F2F5461C71A08CC71FF074B00E0F99DFCDB1489BBAD9545C29D0C93C6F86DA3D`. This CI certificate differs from the installed local-build certificate, so the public APK was not forced over retained app data. The workflow now marks future preview-tag releases as prereleases directly instead of requiring a metadata correction.

The physical Pixel 9a `5A121JEBF08094` is still absent from ADB, mDNS, and the present Windows USB inventory. The local APK has therefore not been installed onto that phone in this candidate, and physical touch/audio/latency claims remain pending. Public repository, CI, Release route, and downloadable artifact identity establish `PUBLIC_PASS`; they do not establish physical `DEVICE_PASS` or `HUMAN_GO`.

## v0.11 safe handoff, beginner coaching, and landscape deck — 2026-08-12

The fixed journey remains `入れる → チョップ → ビート → 保存`, but the first-beat path is now explicit on the working surfaces. Chop explains waveform seek, empty-PAD capture, assigned-PAD audition, and long-press trim according to the current state. Beat keeps `PAD → 選択音をループ／並べる → 足す／擦る` visible and leaves KEY/TONE/LEVEL directly editable during playback. Whole-Chop repetition is named `選択音をループ`, separate from 16-step pattern placement.

Landscape Chop no longer compresses the stacked portrait deck: the waveform and source controls occupy the left side while a full square 4×4 PAD grid stays on the right. Landscape Beat uses compact BANK/page selection, guidance, direct sound controls, transport, selected-sound loop, recording, Add, Scratch, and Details rows. Portrait keeps the established cream/orange/green hardware-deck language and square PADs. No scroll API was added.

Starting a new source while material work exists now requires an explicit second press. Successful replacement begins a clean project; cancellation, decode failure, an older decode, and delayed microphone, device-capture, or vocal completion cannot mutate a reset or newer project. Autosave now rejects older revisions even when writes complete out of order. Source playback tracks issued and audio-thread-applied generations separately, and the ViewModel preserves the last applied value while start/stop is pending, so neither engine nor UI can publish `playing` before a voice exists. A second tap cancels a pending start, and switching to Beat, Scratch, reset, or another project invalidates it. An old completion cannot clear a newer voice. Scratch speed is finite and bounded at both queue and render boundaries.

Local gate: configured offline validation PASS; 125 unit tests with zero failures/errors/skips; Android Lint PASS with zero errors and 11 advisories; debug assemble PASS; `git diff --check` PASS; UI scroll API scan zero matches. Version `0.11.0` (`versionCode=15`) local APK is 31,516,578 bytes with SHA-256 `37D60CB25D7FC996B68BC83F7FDDCAFA3DE770117ABC1A072A53A8C256B7CC85`; package `com.choplab.sampler`, minSdk 29, targetSdk 36, APK Signature Scheme v2, certificate SHA-256 `C0BE467A0F8010BED6F2687D1FDD138498E99B0401722C487459AEEDC453D587`.

Dedicated Pixel 9/API 36 emulator `emulator-5590` received the final local APK through `adb install -r`. Its 5,316,915-byte autosave stayed byte-identical before and after installation at SHA-256 `3962BB989F4B59F8E98AB6D0C38D02DAAC46DBF6CEFDB49AA752552D2614A513`; cold launch reported version 0.11.0, the app process remained alive, and the focused fatal/ANR query returned zero matches. Accepted fixed-layout captures are `work/v011-audit/20-chop-final.png`, `23-chop-landscape-final.png`, `26-beat-landscape-final.png`, and `28-beat-details-landscape.png`.

PR [#20](https://github.com/dj-thank/choplab-sampler/pull/20) merged as `1e0446a29ba245383149de9bfab7863bd69b87e8`. Branch run `31522964955`, PR run `31522968714`, main run `31523293224`, tag verification `31523626784`, and release run `31523626790` all passed. Annotated tag `v0.11.0-preview.1` resolves to the merge commit and the [public prerelease](https://github.com/dj-thank/choplab-sampler/releases/tag/v0.11.0-preview.1) is available.

The reverse-downloaded public APK is 30,723,019 bytes with SHA-256 `04F7284DB3EF90F37561259BF1E0DBCDE59D4AD6A06A448B8729A942AC902B39`; GitHub asset digest and checksum sidecar match. It reports package `com.choplab.sampler`, versionCode 15, versionName 0.11.0, minSdk 29, targetSdk 36, APK Signature Scheme v2, and certificate SHA-256 `E2A9863BAAB8940BD1716D088118C1E766867CCEA48641678192F7B187F2CD1F`. That CI certificate differs from the installed local-build certificate, so the public APK was not installed over retained app data.

The physical Pixel 9a `5A121JEBF08094` was not present in either the final ADB or Windows USB inventory; data-preserving device install and physical audio interaction therefore remain pending. Subjective latency, sustained multi-touch/audio quality, microphone ambience, production signing, and `HUMAN_GO` are not claimed.

## Simple Chop and project isolation — 2026-08-12

The primary flow is now `入れる → チョップ → ビート → 保存`. Entering Chop starts the loaded source from the beginning, selects `A MELODY`, keeps direct live key controls beside transport, and exposes a compact A/B/C/D bank strip plus `01–16` / `17–32` pages. Empty PADs capture the current source position; assigned PADs play their existing chop and can be long-pressed for start/end trim. The main surface links directly to Beat, drums/voice layering, and Scratch without exposing the old 4/8/16 split and fine-control stack.

Importing a different source now starts a separate project state: old A/B/C/D PAD assignments, beat steps, loop/scratch references, slice markers, and edit history are removed before the new source is autosaved. `RESET ALL` uses the same complete blank-state boundary and a confirmed action. Source replacement and reset are covered by deterministic host tests; the physical Pixel project was not erased just to repeat this destructive proof.

Scratch now switches explicitly between the source range and the selected assigned PAD, offers Fine/Normal/Wide gesture sensitivity, and reports active source or PAD scratch consistently. The selected PAD trim editor presents one waveform with independent START and END controls and preview.

Local gate: offline validation PASS; 103 unit tests with zero failures/errors; Android Lint PASS; debug APK assemble PASS; scroll API scan zero matches. Version `0.10.0` (`versionCode=14`) local APK is 30,641,099 bytes, SHA-256 `2AD63450619685094DBFAB4B5E49E10AD4A51432181995767091023F8AF28E9C`. It was installed in place on physical Pixel 9a `5A121JEBF08094` without uninstalling or clearing app data, then copied to the phone's Downloads folder with the same hash. The phone changed foreground apps during UI inspection, so exact final-screen interaction, subjective audio quality, scratch latency, and Human GO remain unclaimed.

PR [#18](https://github.com/dj-thank/choplab-sampler/pull/18) merged as `74944a1c806b312d19364fcb11dfa6d4759cd5a0`. Branch run `31511983934`, PR run `31511988332`, main run `31512350479`, tag verification `31512681213`, and release run `31512681328` all passed. Annotated tag `v0.10.0-preview.1` resolves to the merge commit and the [public prerelease](https://github.com/dj-thank/choplab-sampler/releases/tag/v0.10.0-preview.1) is available. The reverse-downloaded public APK is 30,641,099 bytes with SHA-256 `83F641A154A0287BAA29230F863257CB0C91698F65F7FF2BFE045A1CBB12FD25`; GitHub asset digest, checksum sidecar, PC download, and Pixel Downloads copy match. The public APK reports package `com.choplab.sampler`, versionCode 14, versionName 0.10.0, minSdk 29, targetSdk 36, and APK Signature Scheme v2. Its CI debug certificate differs from the installed local build, so the exact public APK was not installed over retained user data.

## v0.9.3 playable Beat selection — 2026-08-11

Beatと「音を重ねる」は、空PAD・空ページ・空BANKを編集対象へ切り替えず、現在の再生可能PADを保持して日本語の案内を表示する。Beatへ入った時点で選択PADが空なら、現在BANK内、次に全BANKから既存音を選び直す。Chop/PADSの空PAD割り当て操作は従来どおり維持した。

空BANKのBeatレーンは `空 / EMPTY` と表示し、ステップを押しても直前に選んだ別BANKの音へ誤配置しない。純粋状態遷移とレーン対象決定をホストテストで固定し、全98テスト、Lint、assemble、オフライン検証、`git diff --check`、スクロールAPI 0件がPASSした。ローカルAPKは31,360,414 bytes、SHA-256 `3587D5CCC3BCB216D9E8FA231267420F785206388E4396F8389E023E13C34C20`。

Pixel 9/API 36エミュレーターへデータを消さず `versionCode=13` / `versionName=0.9.3` を上書き導入した。既存プロジェクト復元後、Beat入場で空選択から `A-04`へ復帰し、実波形・KEY/TONE・ループが有効になった。空 `A-06` と空 `PAD 17–32` のタップはいずれも `A-04`を保持し、対応する案内を表示した。runtime UI階層は非スクロールで、アプリプロセスは継続した。物理Pixelへの導入と公開Releaseはこの時点では未実施。

PR [#15](https://github.com/dj-thank/choplab-sampler/pull/15) は `27d1c7ce3e1487ac23311a48674014b4edad4e22` としてmergeされた。branch run `31496922708`、PR run `31496975115`、main run `31497276645`、tag verification `31497582713`、release run `31497582655` はすべてPASS。annotated tag `v0.9.3-preview.1` はmerge commitへ解決され、[public prerelease](https://github.com/dj-thank/choplab-sampler/releases/tag/v0.9.3-preview.1) が公開された。

逆ダウンロードした公開APKは30,591,947 bytes、SHA-256 `2B1A8453830CC7D2BBB6DE2CFB8064054EE208A14C22B4108171F889F841B600`。GitHub asset digestとchecksum sidecarが一致し、APK Signature Scheme v2、package `com.choplab.sampler`、`versionCode=13`、`versionName=0.9.3`、`minSdk=29`、`targetSdk=36` を確認した。CIとローカルのdebug署名鍵が異なるため、既存ローカル版を保持するエミュレーターへの公開APK上書きは `INSTALL_FAILED_UPDATE_INCOMPATIBLE` で拒否された。データ削除は行っておらず、物理Pixelも未接続のため、公開APKの実端末導入は未達として分離する。

## v0.9.2 accessibility semantics — 2026-08-11

The fixed, no-scroll interface now exposes its visual selection state to accessibility services for workflow tabs, machine toggles, PADs, sound rails, and Beat-bank selectors. Beat PAD announcements use the configured 32-PAD bank size instead of a hard-coded 16, so PAD 20 is no longer announced as PAD 4. Beat step states are announced in plain Japanese (`選択音`, `別の音`, `オフ`) instead of Kotlin enum identifiers.

Focused host tests cover both 32-PAD addressing and every translated Beat step state. The full local gate passes with 85 tests, zero lint errors, offline validation, and a 31,362,206-byte APK (SHA-256 `0F279F715AF9341BD47FA1FCB3463F1D98607EA0291B84618EC111F8C25283F2`). A Pixel 9/API 36 emulator restored the existing project, opened the fixed Beat view without scrolling, selected empty A-20 without crashing, and exposed `BANK A メロディー PAD 20`, plain-Japanese step states, and no enum identifiers in the runtime accessibility hierarchy. Physical TalkBack navigation remains a human/device check rather than a claimed pass.

Public release `v0.9.2-preview.1` is attached to merge commit `294720c42dcab6ac2152ac6466c61a60f436597c`. Tag verification and release workflows passed. The reverse-downloaded 30,575,563-byte public APK, GitHub digest, and checksum sidecar all match SHA-256 `BCE8A07E57E25255C57816DA21D9067A88C7B41A94E6485CA92D7A32C7B0BC5F`.

## v0.9.1 clarity audit follow-up — 2026-08-11

The post-v0.9.0 emulator audit produced a focused clarity pass without Figma or scroll containers:

- the machine header now shows only the current stage and caption; transient action/recovery status remains in the bottom status strip instead of leaking into unrelated stages;
- CHOP no longer repeats the three Capture input buttons, giving the waveform materially more editing room while input remains one top-level tap away;
- each fixed PAD page reports its assigned count (`5音`) or empty state (`空`), so page 17–32 cannot look like lost data;
- source Scratch always treats waveform taps as slice selection, provides an explicit non-destructive `SOURCE RANGE` choice that preserves chop markers, and ends source scratch on every dialog-dismiss path;
- the Layer Studio loop control is tall enough to show its full START/STOP label.

Validation at this checkpoint: 83 unit tests with zero failures/errors/skips, Android Lint PASS with zero errors, debug APK assemble PASS, and Pixel 9/API 36 emulator screenshots accepted for improved CHOP, PADS, Layer SOUNDS, and source Scratch. Local APK: 30,575,559 bytes, SHA-256 `5F5059DDC6C1EFC7BA1F1FFDCED37F7BACCC81AAA7731437F0C616231E227546`. Physical audio/TalkBack/multi-touch remain unclaimed.

## v0.9.0 four-stage workflow and safe playback local/device evidence — 2026-08-11

Version `0.9.0` (`versionCode=10`) responds to the latest hands-on feedback without Figma:

- the top-level journey is now `入れる → チョップ → ビート → 完成`; `切る` and `鳴らす` are explicit submodes of one Chop stage, so there is no numbered 1→3 jump;
- BANK roles are visible everywhere as A Melody, B Drums, C One Shots, and D Voice;
- every BANK now holds 32 PADs, shown as fixed `01–16` / `17–32` pages; a newly loaded source always targets the first empty A Melody PAD, while schema-4 projects with 16-PAD banks migrate into page one without index drift;
- the performance view keeps the editable source waveform, manual/automatic chop and PAD assignment controls directly above sixteen visible role-colored square sample pads; live capture is an explicit `LIVE CHOP` mode, so normal PAD performance remains audible while the source song plays;
- the Beat view is a fixed four-lane 16-step board with playhead, selected-sound rail, source/loop waveform, transport, loop controls, and direct KEY/TONE/volume editing;
- starting a PAD loop removes an existing audition voice for that PAD first, so previewing and then looping does not stack the same sound twice;
- Layer Studio can place Melody, Drums, One Shots, or Voice with quarter/eighth/sixteenth presets, and Scratch uses a selectable range on the original source waveform;
- sample slicing uses only empty PADs, a full bank refuses replacement, and replacing BANK B with a built-in drum kit requires an explicit second press when sounds already exist;
- manual project save validates a local archive and commits an app-owned safety copy before writing the selected destination; autosave uses a synchronized validated pending write plus three bounded generations;
- source playback now restarts from frame zero after reaching the final frame instead of immediately ending on the next Play press.

Local evidence:

- `scripts/validate_project.sh`: PASS;
- Gradle `testDebugUnitTest`: 81 tests, zero failures/errors/skips;
- Gradle `lintDebug` and `assembleDebug`: PASS;
- `git diff --check`: PASS; UI source scroll API scan: zero matches;
- local APK: `app/build/outputs/apk/debug/app-debug.apk`, 30,716,854 bytes, SHA-256 `F27FAB5034687E165554578C8F859E12A096FC7C05A93DED0BA499C3070AC867`.

Focused Pixel 9 / API 36 emulator evidence:

- an exact 5,316,915-byte schema-4 Pixel autosave (SHA-256 `3962BB989F4B59F8E98AB6D0C38D02DAAC46DBF6CEFDB49AA752552D2614A513`) restored its source waveform, chop markers, PADs, and pattern under schema 5;
- CHOP, PADS page 17–32, BEAT with direct KEY controls, Layer Studio SOUNDS, and original-source-range Scratch all fit the 1080 × 2424 portrait screen without scrolling;
- a fixed-size assertion left over from 16-PAD banks caused one BEAT navigation crash during validation; the assertion now targets the 16-PAD visible page, and the same navigation remained alive afterward.

Focused physical Pixel 9a evidence:

- the final local APK installed in place as `versionCode=10`, `versionName=0.9.0`, launched with the previous project intact, and was copied to `/sdcard/Download/ChopLab-0.9.0-ui-safe-playback.apk`; PC/device APK hashes matched exactly;
- the four-stage Chop/Pads and four-lane Beat layouts fit the portrait screen with no scrolling; the condensed PADS waveform stayed clearly visible, and `MANUAL` plus a waveform tap added a numbered chop boundary on device;
- after a completed source had left its playhead at the end, `SOURCE PLAY` changed to `SOURCE STOP`, confirming restart-from-zero behavior;
- with BANK B selected, source still playing, and `LIVE CHOP OFF`, tapping assigned B-01 left the autosave SHA-256 unchanged at `7367C2026579C76FF7C3EE3FC5278D8600B3062DB853DEB46139CFC400D99140`, kept `SOURCE STOP` visible, and left the app process alive. The host routing test independently confirms this path selects performance playback rather than capture.
- the latest local APK was installed in place while the phone remained locked and copied to `/sdcard/Download/ChopLab-0.9.0-latest.apk`; device/PC SHA-256 matched `F27FAB5034687E165554578C8F859E12A096FC7C05A93DED0BA499C3070AC867`. Latest-screen and subjective-audio checks remain intentionally unclaimed until the phone is unlocked.

This establishes `LOCAL_PASS` and focused local-build `DEVICE_PASS`. It does not yet claim `PUBLIC_PASS`, subjective audio/latency quality, physical multi-touch stress, microphone overdub, production signing/update continuity, or `HUMAN_GO`.

## v0.8.0 drum, vocal, and scratch workstation local/device evidence — 2026-08-11

Version `0.8.0` (`versionCode=9`) adds one fixed `LAYER STUDIO` without using Figma or introducing scroll containers:

- PAD cells are the largest centered squares that fit both the existing 4×4 and Arrange 8×2 grids;
- five original deterministic drum kits each provide 16 named KICK/SNARE/HAT/PERC one-shots, a professional list selector, BANK A〜C targeting, and a starter beat;
- microphone overdub restarts the active beat loop, stores the decoded take in BANK D, starts vocal takes once with the loop, and includes them in offline export;
- a large touch jog uses signed speed for forward/reverse scratch playback and stops on release;
- schema 4 persists PAD content roles and selected kit, while schema 1–3 remain readable;
- third-party artist recordings and unofficial branded kits are excluded. Candidate CC0 intake and provenance checks are documented in `docs/research/legal-drum-sample-sources.md`.

Local evidence:

- `scripts/validate_project.sh`: PASS;
- Gradle `testDebugUnitTest`: 66 tests, zero failures/errors/skips;
- Gradle `lintDebug`: PASS, zero errors (10 Android/toolchain advisories reported);
- Gradle `assembleDebug`: PASS;
- `git diff --check`: PASS;
- UI source scan: zero scroll API matches;
- local APK: `app/build/outputs/apk/debug/app-debug.apk`, 31,164,002 bytes, SHA-256 `DBD637B102E6133C9C7D55EF97F7DD01D7CF65298E2CDF96E2C213033C8A73E9`.

Focused physical Pixel 9a evidence on Android 17 / arm64-v8a:

- the final exact APK installed as `versionCode=9`, `versionName=0.8.0`, launched with a live process, and was copied to `/sdcard/Download/ChopLab-0.8.0-drum-vocal-scratch.apk`;
- device and PC APK SHA-256 matched exactly;
- Android rejected the first in-place update because the installed public build used a different debug signature. The existing 4,960,607-byte autosave was copied byte-for-byte to `/sdcard/Download/ChopLab-autosave-before-drum-vocal-scratch.choplab` with SHA-256 `ACE63AE664334728BB6D7FB432261035DDBA5B5EB49E3F04647C2D25A8AE4DB0`; inspection confirmed that archive was already truncated/corrupt and the old app also reported recovery failure;
- after the authorized package replacement, DUSTY JAZZ was applied to BANK B, real synthesized waveforms and starter-step markers appeared, square 8×2 PADs remained on-screen, and the improved DRUMS/VOICE/SCRATCH panels were captured without scrolling;
- the resulting schema 4 autosave restored successfully after a cold app restart.

Public evidence for the same source state:

- PR [#8](https://github.com/dj-thank/choplab-sampler/pull/8) merged as `d99a27f4bdb3aa609500bb1334aa782382fe25f8`; branch push run [31457463895](https://github.com/dj-thank/choplab-sampler/actions/runs/31457463895), PR run [31457485138](https://github.com/dj-thank/choplab-sampler/actions/runs/31457485138), and main run [31457675077](https://github.com/dj-thank/choplab-sampler/actions/runs/31457675077) passed;
- annotated tag `v0.8.0-preview.1` resolves to that merge commit; tag verification [31457697955](https://github.com/dj-thank/choplab-sampler/actions/runs/31457697955) passed;
- release workflow [31457697961](https://github.com/dj-thank/choplab-sampler/actions/runs/31457697961) passed build/package and publish jobs. [ChopLab v0.8.0-preview.1](https://github.com/dj-thank/choplab-sampler/releases/tag/v0.8.0-preview.1) is public and marked as a prerelease;
- reverse-downloaded public `ChopLab-v0.8.0-preview.1-debug.apk`: 30,477,259 bytes, SHA-256 `D3C26D20023A9D25B19E316D1C77A44D067DCA7717DDA3BDA2F82067A58EC1A8`. It matched the GitHub asset digest and attached checksum file, and reports package `com.choplab.sampler`, `versionCode=9`, `versionName=0.8.0`;
- the exact public APK was copied to the Pixel 9a at `/sdcard/Download/ChopLab-v0.8.0-preview.1-debug.apk`, where its SHA-256 matched the reverse download. The installed app remains the same-source locally signed build, so this does not claim an exact-public-APK install smoke.

This establishes `LOCAL_PASS`, `PUBLIC_PASS`, and focused local-build install/launch, kit-application, fixed-layout, and schema-4 recovery `DEVICE_PASS`. It does not establish ambient microphone recording on the user's phone, subjective drum/loop/scratch sound quality, measured latency/xRuns, sustained thermal behavior, TalkBack/haptic quality, production signing/update continuity, exact-public-APK installation, or `HUMAN_GO`.

The final two-axis review found and resolved two release blockers before publication: BANK D now refuses a seventeenth vocal take instead of overwriting D-01, and scratch-voice allocation was moved off the realtime audio thread. Scratch speed/frame atomics are handled once per block and an idle gesture returns speed to zero. The remaining duplicated realtime/offline layer scheduling is an accepted internal maintainability item, not a v0.8 behavior gap.

## Public v0.7.0 whole-chop beat-loop evidence — 2026-08-11

- PR [#6](https://github.com/dj-thank/choplab-sampler/pull/6) merged as `9d09228c7d19cdd709b7c864e21eddaa69715d67` after branch push run [31400890047](https://github.com/dj-thank/choplab-sampler/actions/runs/31400890047) and PR run [31400928956](https://github.com/dj-thank/choplab-sampler/actions/runs/31400928956) both passed.
- Main Android verification [run 31401298050](https://github.com/dj-thank/choplab-sampler/actions/runs/31401298050) passed before tagging.
- Annotated tag `v0.7.0-preview.1` resolves to the same merge commit; tag Android verification [run 31401606925](https://github.com/dj-thank/choplab-sampler/actions/runs/31401606925) passed.
- Release workflow [run 31401606890](https://github.com/dj-thank/choplab-sampler/actions/runs/31401606890) passed build/package and publish jobs. [ChopLab v0.7.0-preview.1](https://github.com/dj-thank/choplab-sampler/releases/tag/v0.7.0-preview.1) is public and marked as a prerelease.
- Public `ChopLab-v0.7.0-preview.1-debug.apk`: 30,346,187 bytes, SHA-256 `3393A60EBB8FDD3CE76CD459150049807D63DC39CF62BB4CF213365FB5FD1CB2`. The reverse-downloaded APK matched both the GitHub asset digest and attached `.sha256` file.
- The exact public APK was copied to the connected Pixel 9a at `/sdcard/Download/ChopLab-v0.7.0-preview.1-debug.apk`, where its SHA-256 matched the PC download.
- Android correctly rejected an in-place update from the local debug signature. Before replacing only `com.choplab.sampler`, the current 12,003,628-byte autosave was backed up, its ZIP entries were read successfully, and device/PC SHA-256 matched `75C8BB8E5FFC8E6FA0006212E4A869593A2C8D680B44DD5DA7474A862CC45B42`.
- The exact public APK was then installed as `versionCode=8`, `versionName=0.7.0`; the same autosave was restored with the same digest. A cold launch showed `Without You.mp3` and `前回の自動保存を復元しました`, the focused fatal query returned zero matches, and the previously focused Neefo activity was brought back to the foreground.
- The reverse-downloaded public APK and verified autosave backup remain under the task `outputs` folder.

This establishes `LOCAL_PASS`, `PUBLIC_PASS`, and focused install/launch plus state-migration `DEVICE_PASS` for the exact public v0.7.0 preview APK. It does not establish subjective loop-seam quality, sustained latency/thermal behavior, physical multi-touch layering, TalkBack/haptic quality, production signing/update continuity, or `HUMAN_GO`.

## Whole-chop beat loop local/device evidence — 2026-08-10

Version `0.7.0` (`versionCode=8`) separates a continuous beat loop from step-grid placement:

- `4 並べる` now gives one beginner path: `1 PADを選ぶ → 2 ビートをループ → 3 音を重ねる`;
- `ビートをループ` repeats the selected PAD's start-inclusive/end-exclusive audio range continuously instead of retriggering it on quarter/eighth/sixteenth steps;
- one project beat-loop PAD is active at a time, duplicate infinite voices are prevented, and both the same primary control and `ALL STOP` stop playback;
- the real loop waveform stays visible while another BANK is selected, with a live loop-position line and percentage;
- when another BANK is selected during playback, the primary control still names the active loop PAD and remains an unambiguous `ループ停止 / STOP` action;
- `4つ打ち / 8分 / 16分` remain available only as optional `配置プリセット` in `細かく調整`, for layering other PADs without conflating sequencing with the base loop;
- offline export starts LOOP PADs at frame zero and renders them continuously, while project archive schema 3 preserves the new mode and still reads schema 1/raw-PCM and schema 2/WAV projects;
- portrait and landscape keep the existing fixed console with no scrolling API.

Local evidence:

- `scripts/validate_project.sh`: PASS;
- Gradle `testDebugUnitTest`: 53 tests, zero failures/errors/skips;
- Gradle `lintDebug`: PASS with zero errors and nine pre-existing toolchain/platform advisories;
- Gradle `assembleDebug`: PASS;
- `git diff --check`: PASS;
- local APK: `app/build/outputs/apk/debug/app-debug.apk`, 30,942,730 bytes, SHA-256 `AD0E2079574DB72B28C928439F3CF3C45BB59322C2ADECBD8E3637F67A1C945A`.

Focused physical Pixel 9a evidence:

- installed the exact final local APK as `versionCode=8`, `versionName=0.7.0`, then copied the same bytes to `/sdcard/Download/ChopLab-v0.7.0-local-debug.apk`; device and PC SHA-256 both matched `AD0E2079574DB72B28C928439F3CF3C45BB59322C2ADECBD8E3637F67A1C945A`;
- the existing project restored after cold launch and its assigned source/PADs remained available;
- the final exact APK started A-04 from its saved LOOP PAD state and exposed a live 26–29% waveform position, `A-04の音声全体を繰り返し中`, and `ループ停止 / STOP` before the user returned another app to the foreground;
- on the immediately preceding v0.7.0 candidate, starting A-04 changed the accessible waveform state from 23% to 75%; after moving to empty BANK B and returning to Arrange, A-04's real waveform, live loop percentage and enabled STOP action remained visible, stopping from BANK B reported `ビートループを停止しました`, and the focused log contained no fatal exception match. The subsequent review fixes affected only allocation-free engine release loops and the export precondition, not this UI path.

This establishes `LOCAL_PASS` and a focused install/launch/control-state `DEVICE_PASS` for the exact local v0.7.0 APK. It does not yet establish CI/Release identity, `PUBLIC_PASS`, subjective loop-seam quality, sustained latency/thermal behavior, physical multi-touch layering, TalkBack/haptic quality, production signing/update continuity, or `HUMAN_GO`.

## Public v0.6.0 preview evidence — 2026-08-10

- PR [#5](https://github.com/dj-thank/choplab-sampler/pull/5) merged as `db0845d9de8129dae14d813eab10ad1cda88a0de` after both branch verification runs `31391730072` and `31391734337` passed.
- Main Android verification [run 31392024199](https://github.com/dj-thank/choplab-sampler/actions/runs/31392024199) passed before tagging.
- Annotated tag `v0.6.0-preview.1` resolves to the same merge commit; tag Android verification [run 31392345400](https://github.com/dj-thank/choplab-sampler/actions/runs/31392345400) passed.
- Release workflow [run 31392343101](https://github.com/dj-thank/choplab-sampler/actions/runs/31392343101) passed both build/package and publish jobs. The public prerelease is [ChopLab v0.6.0-preview.1](https://github.com/dj-thank/choplab-sampler/releases/tag/v0.6.0-preview.1).
- Public `ChopLab-v0.6.0-preview.1-debug.apk`: 30,313,419 bytes, SHA-256 `1E57FB66FDA11E3C4A69B2646A7CA340F67067A13AC5B11E505C40A5011B3B90`. The downloaded APK matched the Release digest and attached `.sha256`, verified with APK Signature Scheme v2, and reported package `com.choplab.sampler`, `versionCode=7`, `versionName=0.6.0`.
- The exact public APK installed fresh and cold-launched on the Pixel 9 AVD. `MainActivity` became top-resumed and the focused log query found no fatal exception.
- On the connected Pixel 9a, Android rejected the in-place update because the prior public debug signature differed from the new CI debug signature. Before replacing only `com.choplab.sampler`, the 12,003,624-byte autosave was backed up, its archive entries were read successfully, and device/PC SHA-256 matched at `63C3B7EA9183B9C88FADE32AD98125C6A625A1163B1921B9F62720C6494842E7`.
- The public APK was then installed as `versionCode=7`, `versionName=0.6.0`; the same autosave was restored with the same digest. A cold launch displayed `前回の自動保存を復元しました`, no focused fatal exception was detected, and the previously focused app was reopened.
- The exact public APK remains on the phone at `/sdcard/Download/ChopLab-v0.6.0-preview.1-debug.apk` and on the PC under the task `outputs` folder.

This establishes `LOCAL_PASS`, focused `EMULATOR_PASS`, `PUBLIC_PASS`, and install/launch plus verified autosave-migration `DEVICE_PASS` for the exact public v0.6.0 preview APK. It does not establish sustained physical multi-touch performance, TalkBack/haptic quality, long-session audio latency, production signing/update continuity, implemented AI assistance, or `HUMAN_GO`.

## Arrange quick flow and progressive controls — 2026-08-10

Version `0.6.0` (`versionCode=7`) makes the existing repeat presets discoverable and reduces the default Arrange control density without removing advanced editing:

- the default `4 並べる` screen now presents one explicit path: `1 PADを選ぶ → 2 反復を選ぶ → 3 ビートを聴く`;
- the repeat area has its own permanent orange outline and heading. An empty PAD explains that an audio-filled PAD must be selected; an assigned PAD asks, for example, `A-01を何拍ごとに鳴らす？`;
- `4つ打ち / 8分 / 16分` use beginner meanings (`1拍ごと / 半拍ごと / 細かく`) while retaining the exact existing repeat-grid behavior;
- PLAY/STOP, next-BANK sound layering and one `細かく調整` entry remain on the quick screen;
- REC/CLEAR, BPM/Swing, manual 16-step editing and KEY/TONE/LEVEL move to the reversible `細かく調整` view;
- portrait and landscape use the same quick/fine hierarchy and remain fixed, without a scroll API;
- `docs/AI_ASSIST_VISION.md` records a future one-entry, non-destructive, local-first AI proposal workflow. No AI feature is claimed as implemented.

Local evidence:

- `scripts/validate_project.sh`: PASS;
- Gradle `testDebugUnitTest`: 46 tests, zero failures/errors/skips;
- Gradle `lintDebug`: PASS;
- Gradle `assembleDebug`: PASS;
- UI source scan: zero `verticalScroll`, `horizontalScroll`, or `rememberScrollState` matches;
- `git diff --check`: PASS;
- local APK: `app/build/outputs/apk/debug/app-debug.apk`, 30,904,558 bytes, SHA-256 `F503EA14A5E89B26465F32A75A223F2E0AAB087015C9E7ADD5BEB3052621AEF8`.

Focused Pixel 9 AVD evidence on Android 16/API 36, x86_64, 1080 × 2424 px at density 420:

- captured the v0.5.0 Arrange baseline, then the same empty-PAD viewport with the new three-step quick hierarchy and inspected a side-by-side comparison;
- recorded a 3.1-second emulator source, assigned A-01, selected `4つ打ち`, and observed steps 1/5/9/13, the orange active preset and matching timeline markers;
- started beat playback from `3 ビートを聴く` and observed the moving waveform playhead, current-step readout and STOP state with no focused fatal exception;
- opened `細かく調整` and confirmed REC/CLEAR, BPM/Swing, all 16 manual steps and KEY/TONE/LEVEL remain reachable, with a visible return to quick creation;
- rotated to 2424 × 1080 landscape and confirmed PAD, waveform, repeat question/presets and the three primary actions remained visible without clipping.
- measured each portrait repeat preset at 127 px on the 420 dpi test device, slightly over the 48 dp minimum touch target.

This establishes `LOCAL_PASS` and focused `EMULATOR_PASS` for the control-hierarchy change. It does not yet establish public CI/Release identity, installation of version `0.6.0` on the physical phone, physical touch/TalkBack/haptic quality, any AI capability, or `HUMAN_GO`.

## Arrange waveform and repeat workflow — 2026-08-10

Version `0.5.0` (`versionCode=6`) turns `4 並べる` into a visible one-bar beat workspace without removing the original live-chop flow:

- the selected PAD's real PCM slice is down-sampled into a bounded waveform and drawn over 16 beat divisions;
- a high-contrast moving playhead, `いま xx / 16` readout and matching sequencer-cell outline show the current playback position;
- four labelled BANK rows show every sounding layer at each step, with the selected PAD marker separated from other BANK activity;
- `4つ打ち / 8分 / 16分` presets replace only the selected PAD's steps, preserve all other PAD/BANK layers, and remain Undo/Redo-compatible;
- Arrange uses a compact 8 × 2 PAD selector in portrait while the live `叩く` stage keeps its 4 × 4 PAD layout;
- `音を重ねる BANK →` selects an already-audible layer; when the same PAD in the next BANK is empty, `音を足す BANK →` moves directly to `叩く` on that BANK/PAD so a new sound can be captured;
- KEY shows truthful semitone offset plus `原キー / 高い / 低い` without pretending to detect the imported song's musical key, and TONE cycles through `暗い / なじむ / 原音` while the continuous editor remains available;
- landscape Arrange was reorganized into waveform/repeat/steps and transport/timing/sound columns so the new controls do not clip.

Local evidence:

- `scripts/validate_project.sh`: PASS;
- Gradle `testDebugUnitTest`: 44 tests, zero failures/errors/skips;
- Gradle `lintDebug`: zero issues;
- Gradle `assembleDebug`: PASS;
- UI source scan: zero `verticalScroll`, `horizontalScroll`, or `rememberScrollState` matches;
- `git diff --check`: PASS;
- local APK: `app/build/outputs/apk/debug/app-debug.apk`, 30,829,070 bytes, SHA-256 `3A0487513A6455A98AAC835B2E53ECDB1AB1FFDEF80911CFFDD840651D5C7E31`.

Focused Pixel 9 AVD evidence on Android 16/API 36, x86_64, 1080 × 2424 px at density 420:

- imported a 30-second WAV, created four slices, assigned A-01..04, placed A-01 as a four-on-the-floor pattern, then assigned B-01..04 and layered B-01 as eighth notes;
- portrait playback showed the selected real waveform, moving high-contrast playhead, A/B marker rows, active 8分 preset, KEY/TONE labels and all controls without scrolling or visible clipping;
- landscape playback/edit layout showed waveform, A/B layers, repeat presets, all 16 steps, transport, BPM/Swing, KEY and TONE/LEVEL without visible clipping;
- selecting empty C-01 through `音を足す BANK C →` moved directly to `叩く`, selected BANK C / PAD C-01, and displayed the instruction to press a PAD while the source plays;
- installed package reported `versionCode=6`, `versionName=0.5.0`, `minSdk=29`, `targetSdk=36`; `MainActivity` was top-resumed and the focused error-log query found no fatal exception.

Public and physical-device evidence:

- PR [#4](https://github.com/dj-thank/choplab-sampler/pull/4) merged as `48c645e8b6a0f96c9acf2a7249f26648e8430689`;
- main Android verification [run 31386734837](https://github.com/dj-thank/choplab-sampler/actions/runs/31386734837): PASS;
- tag `v0.5.0-preview.1` resolves to the same merge commit; tag Android verification [run 31387028904](https://github.com/dj-thank/choplab-sampler/actions/runs/31387028904): PASS;
- release workflow [run 31387028918](https://github.com/dj-thank/choplab-sampler/actions/runs/31387028918): PASS; [public release](https://github.com/dj-thank/choplab-sampler/releases/tag/v0.5.0-preview.1) published from the public repository;
- public `ChopLab-v0.5.0-preview.1-debug.apk`: 30,297,035 bytes, SHA-256 `DB3EC8CC7B23C7DFB82547FBFC10DFEC59A11BFE11AF707AD24DC2CEBF16C4F1`; the downloaded APK matched both the Release asset digest and attached `.sha256`;
- the downloaded public APK installed and cold-launched on the Pixel 9 AVD as `versionCode=6`, `versionName=0.5.0`, with no focused fatal exception;
- on the connected Pixel 9a, Android correctly rejected an in-place update because the previous local debug signature differed from the CI debug signature. Before replacing only `com.choplab.sampler`, the 4,494,933-byte autosave was backed up and verified as SHA-256 `7AAB7315A7922C7075F07DF561204CB5D7C4BE9E0B59CA3E67F73B12A8884140`. The public APK was then installed, the same autosave restored with the same digest, `MainActivity` launched as version `0.5.0` without a focused fatal exception, and the previously focused app was reopened;
- the public APK remains in the phone's Download folder; the temporary Arrange audit WAV was removed.

This establishes `LOCAL_PASS`, focused `EMULATOR_PASS`, `PUBLIC_PASS`, and install/launch plus state-migration `DEVICE_PASS` for the exact public APK. It does not establish sustained physical multi-touch performance, subjective haptic quality, long-session audio latency, production signing/update continuity, or `HUMAN_GO`.

## MVP project persistence and edit recovery — 2026-08-10

Version `0.4.0` (`versionCode=5`) adds a bounded persistence slice to the existing mono AudioTrack MVP without changing the original HTML live-chop flow:

- manual `.choplab` save/open from `完成`, with current source, shared PCM16 WAV assets, slice ranges, all 64 PAD assignments and parameters, sequence, BPM/Swing and source KEY;
- schema 2 writes standard WAV entries while the reader migrates schema 1 raw-PCM archives and rejects unknown newer schemas with an update message;
- app-owned autosave after 900 ms of edit inactivity, written through a synced temporary file and two recoverable generations; a valid pending generation can also recover an interrupted replacement;
- at most 40 Undo/Redo entries for slice, PAD, sequence and timing edits, with repeated slider updates coalesced into one operation;
- fail-closed archive checks for schema, normalized entry names, path traversal, duplicate audio IDs/entries, manifest size, total PCM size, unknown entries, malformed/truncated WAV, invalid ranges and invalid references;
- transient playback, recording and loading states are never restored as active.

Local evidence:

- `scripts/validate_project.sh`: PASS;
- Gradle `testDebugUnitTest`: 37 tests, zero failures/errors/skips;
- Gradle `lintDebug`: zero errors, nine warnings;
- Gradle `assembleDebug`: PASS;
- UI source scan: zero `verticalScroll`, `horizontalScroll`, or `rememberScrollState` matches;
- `git diff --check`: PASS;
- local APK: `app/build/outputs/apk/debug/app-debug.apk`, 30,357,226 bytes, SHA-256 `B0E9DF2E9D50E2AD3CBE1DF4C9CF8AA1F6C3296DA181BB57F25535024B205D0A`.

Focused Pixel 9 AVD evidence on Android 16/API 36, x86_64, 1080 × 2424 px at density 420:

- APK installed as `versionCode=5`, `versionName=0.4.0`, `minSdk=29`, `targetSdk=36`; `MainActivity` was top-resumed and no focused fatal exception was found;
- the final APK opened the previous schema 1 autosave at 92 BPM, then imported `choplab-ui-smoke.wav` and wrote a schema 2 autosave whose archive contained `audio/0.wav` with `RIFF/WAVE` headers;
- the expanded `完成` action area showed SAVE PROJECT, OPEN PROJECT, UNDO and REDO without clipping or scrolling;
- after the final APK change, a source-backed 92 BPM schema 2 project was manually saved through DocumentsUI, BPM was changed to 93, and opening the saved project restored 92 BPM with no focused fatal exception;
- saved an empty 92 BPM project through Android DocumentsUI, changed BPM to 93, restored 92 with Undo, restored 93 with Redo, then reopened the saved project and observed 92 BPM;
- changed the reopened project to 94 BPM, waited for autosave, force-stopped/relaunched the app, and observed 94 BPM plus the autosave-restored status;
- intentionally truncated only the emulator app's latest autosave to four bytes, force-stopped/relaunched, and observed recovery from the previous 92 BPM generation with no fatal exception;
- the temporary DocumentsUI `.choplab` and WAV test files were removed from emulator Downloads after verification.

Public evidence:

- PR [#3](https://github.com/dj-thank/choplab-sampler/pull/3) merged as `a1f8716339cf42660f8f9c1e7b0a3ade0cd97a46`;
- main Android verification [run 31360839715](https://github.com/dj-thank/choplab-sampler/actions/runs/31360839715): PASS;
- tag `v0.4.0-preview.1` resolves to the same merge commit; tag Android verification [run 31361047407](https://github.com/dj-thank/choplab-sampler/actions/runs/31361047407): PASS;
- release workflow [run 31361047377](https://github.com/dj-thank/choplab-sampler/actions/runs/31361047377): PASS; [public release](https://github.com/dj-thank/choplab-sampler/releases/tag/v0.4.0-preview.1) published;
- public `ChopLab-v0.4.0-preview.1-debug.apk`: 30,215,115 bytes, SHA-256 `ACCC866289D261DBC7694A2F02A24C90E2EF1DCEFDB250DF2DDB80C1C9C12FF2`; downloaded checksum matched the attached `.sha256`;
- the downloaded public APK was installed fresh on the Pixel 9 AVD, imported the WAV source, produced schema 2 `audio/0.wav` with `RIFF/WAVE`, restored autosave after restart, exposed SAVE/OPEN/UNDO/REDO without scrolling, and showed no focused fatal exception.

This establishes `LOCAL_PASS`, focused `EMULATOR_PASS`, CI build evidence and public-release artifact identity for the MVP persistence slice. It does not establish physical `DEVICE_PASS`, process-death durability under real storage pressure, large-audio performance, a stable production signing/update path, or `HUMAN_GO`.

## Guided five-stage sampler workflow — 2026-08-10

The Android application now presents the fixed `入れる / 切る / 叩く / 並べる / 完成` journey while preserving the original HTML workflow inside `叩く`: load or record audio, play the source, and press a PAD at the desired instant to create a live chop. `切る` retains the precision waveform tools, `並べる` retains the 16-step sequencer, and `完成` accurately exposes the implemented four-bar mono WAV export.

Version `0.3.0` (`versionCode=4`) adds:

- a Japanese-first five-stage rail with short English production captions;
- beginner guidance for source sampling and the starter `1・5・9・13` step pattern;
- selected-PAD KEY note names and semitone controls, plus direct TONE/LEVEL sliders on regular portrait layouts;
- the existing reverse, one-shot/gate, choke and PAD clear controls under `叩く` → `詳細`;
- a finish summary with assigned PAD count, audible step count, BPM, beat preview, confirmed pattern clear and four-bar export;
- safe restoration of legacy `CHOP / PAD / SEQ / SOURCE` saved mode names without `valueOf` crashes.

Local evidence:

- `scripts/validate_project.sh`: PASS;
- Gradle `testDebugUnitTest`: 23 tests, zero failures/errors/skips;
- Gradle `lintDebug`: zero errors, nine warnings;
- Gradle `assembleDebug`: PASS;
- UI source scan: zero `verticalScroll`, `horizontalScroll`, or `rememberScrollState` matches;
- `git diff --check`: PASS;
- local APK: `app/build/outputs/apk/debug/app-debug.apk`, 30,616,083 bytes, SHA-256 `718814700DF1929D53CC90B2B0A10A7230E677C598E226080114ACC8D87348D2`.

Emulator evidence on a headless Pixel 9 AVD, Android 16/API 36, x86_64, 1080 × 2424 px at density 420:

- final APK installed as `versionCode=4`, `versionName=0.3.0`, `minSdk=29`, `targetSdk=36`;
- `MainActivity` reached `topResumedActivity`; the focused post-launch log query found no fatal exception;
- microphone permission and a three-second emulator recording were used to verify capture → live source playback → PAD 01/02 assignment;
- PAD A-02 KEY changed from C3 to C#3, TONE to 32%, and LEVEL to 75%; steps 1/5/9/13 changed to `オン`;
- `完成` showed two assigned PADs, four audible steps, 92 BPM, enabled beat preview and enabled four-bar WAV export;
- post-review detail screens showed Japanese-first PARAM/PLAY, PITCH/TONE/LEVEL, reverse, one-shot/gate, choke and confirmed PAD-clear labels without clipping; `完成` now includes transport state;
- visual comparison against both selected generated targets completed after adding the direct sliders and arrange TIP; `design-qa.md` records `final result: passed`.

Public evidence for the exact merged UI commit `a882ec633d6b9ad849a8c900171fbbd1006f29d1`:

- public PR `#2`: `https://github.com/dj-thank/choplab-sampler/pull/2`, merged after the PR verification run `31357128321` passed;
- main Android verification run `31357298769`: PASS;
- tag Android verification run `31357435542`: PASS;
- tag: `v0.3.0-preview.1`;
- release workflow run `31357435588`: build/package and publication PASS;
- public Release: `https://github.com/dj-thank/choplab-sampler/releases/tag/v0.3.0-preview.1`;
- public APK: `ChopLab-v0.3.0-preview.1-debug.apk`, 30,116,752 bytes;
- public APK SHA-256: `E5C79BF01F62C5445E23798CF0603B46305E37BC932F3B9AE94C580E3E4E7219`;
- downloaded release APK and published `.sha256` file matched byte-for-byte by digest.

This establishes `PUBLIC_PASS` for the public repository, CI, Release publication, downloadable APK identity, and the focused `EMULATOR_PASS` above. No physical phone is connected, so it does not establish `DEVICE_PASS`, physical touch comfort, haptic quality, microphone fidelity, latency, or `HUMAN_GO`.

## Fixed no-scroll production console — 2026-08-10

The application source now uses a fixed `CHOP / PAD / SEQ / SOURCE` console instead of the former vertically scrolling card stack. The four workspaces preserve the existing sampler engine and expose live chop, 4 BANK × 16 PAD performance, per-PAD editing, 16-step sequencing, capture/import, slicing, assignment, and WAV export without top-level vertical or horizontal scrolling.

Local evidence for version `0.2.0` (`versionCode=3`):

- `DeckLayoutPolicyTest`: four portrait/landscape and compact/regular policy tests pass;
- Gradle `testDebugUnitTest`: 18 tests, zero failures/errors;
- Gradle `lintDebug`: zero errors, nine warnings;
- Gradle `assembleDebug`: PASS;
- UI source scan: no `verticalScroll`, `horizontalScroll`, or `rememberScrollState` usage;
- local debug APK: `app/build/outputs/apk/debug/app-debug.apk`;
- local debug APK SHA-256 after compact-landscape and accessibility hardening: `CDB02CFFA5F693F2550F41260558D04E259F31AC917E998ED16CDE12D07E8ABD` (30,433,927 bytes).

The current-run Android SDK Platform 36 and Build Tools 36.0.0 were installed locally after accepting their SDK licenses, allowing a real local Android compile and APK build rather than source-only validation.

No phone is connected for this milestone. Local validation establishes `LOCAL_PASS`, but does not claim `DEVICE_PASS`, screenshot parity, touch comfort, clipping-free rendering, audio E2E, or `HUMAN_GO`. Previous Pixel 9a evidence below applies only to the older `v0.1.1-preview.1` artifact.

Public evidence for the exact UI commit `e0896adf8ff96439556d551d1cae4b9d1927f868`:

- main Android verification run `31352372588`: PASS;
- tag Android verification run `31352511018`: PASS;
- tag: `v0.2.0-preview.1`;
- release workflow run `31352511062`: build/package and publication PASS;
- public Release: `https://github.com/dj-thank/choplab-sampler/releases/tag/v0.2.0-preview.1`;
- public APK: `ChopLab-v0.2.0-preview.1-debug.apk`, 30,034,832 bytes;
- public APK SHA-256: `B1CC4F6B014F507F3F928AF10CA0EB25E41EA58E50CCEC771CDE43A9A0F62C26`.

This establishes `PUBLIC_PASS` for repository visibility, CI build, and downloadable artifact identity. It still does not establish `DEVICE_PASS` for the new fixed console because no phone was available.

## Canonical 「おとひろい」 UI — 2026-08-10

The user-supplied 505-line HTML prototype is now treated as the canonical top-screen specification. Source changes add:

- the cream hardware-deck visual system, Japanese `おとひろい` identity, orange sampling lamp, green waveform, 4 × 4 PAD layout, and one-row 16-step sequencer in Compose;
- full-source playback, waveform seek, atomic source playhead reporting, ±12-semitone source pitch-by-rate, and source/beat transport exclusion;
- live chop capture: while the source is playing, pressing a visible PAD assigns the latency-compensated playhead and reflows same-audio PAD end frames within the current bank;
- the existing 4 BANK, advanced waveform selection, microphone/system capture, per-PAD editing, quantized record, Swing, and WAV export under an expandable details section.

Observed locally before Android publication:

- pure Kotlin JUnit: `OK (14 tests)` including two new live-chop boundary tests;
- `scripts/validate_project.sh`: PASS using the preserved portable JDK 17 / Kotlin 2.3.21 environment;
- `git diff --check`: PASS.

Public GitHub Actions run `31321170535` passed offline validation, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, and artifact upload for commit `1fc8fc8`. The CI APK SHA-256 is `BDAE4725031940B8452331D61BA689905AE1C947E77C615BAF0586EB7BAD32F5` (29,920,144 bytes).

The exact CI APK was installed on the connected Pixel 9a after a content-free `run-as` inspection found only cache and `files/profileInstalled`; Android rejected in-place update because GitHub runner debug signatures differ, so the old preview was uninstalled first. `MainActivity` launched with no immediate fatal exception. Visual inspection confirmed the canonical deck, header, source controls, waveform, banks, and PAD proportions without visible clipping. A generated 30-second mono 48 kHz sine test then verified `曲を読込` → `曲を再生` → PAD 01 during sampling → stop, with `PAD 01 割り当て済み` and marker `01` at approximately `0:00.8`. The temporary test WAV was removed from the device afterward.

This is a focused preview smoke, not latency measurement or complete microphone/system-capture/export validation. The source pitch slider, waveform seek, lower-page sequencer controls, advanced editor, and lifecycle stress remain unverified on this APK.

Final packaging for this UI milestone:

- version commit: `d273fe9997afa34c23868be0477b57fddcd198ae` (`versionCode=2`, `versionName=0.1.1`);
- final main CI: `31321683089`, all Android verification steps PASS;
- tag: `v0.1.1-preview.1`;
- release workflow: `31321828427`, build/package and publish jobs PASS;
- public Release: `https://github.com/dj-thank/choplab-sampler/releases/tag/v0.1.1-preview.1`;
- public APK: `ChopLab-v0.1.1-preview.1-debug.apk`, 29,920,144 bytes;
- public APK SHA-256: `F4C1C47066771ABF4FD47AB1F72C06A442A30FA7B6EE13B1ADC6777C416EFB6A`.

The exact public Release APK was installed on the Pixel 9a after a second content-free data check again found only cache/profile files. Package inspection reports `versionCode=2`, `versionName=0.1.1`, `minSdk=29`, and `targetSdk=36`; `MainActivity` is top-resumed and the immediate logcat query found no fatal exception.

## Public preview packaging — 2026-08-09

The repository is now prepared for public preview publication at:

- `https://github.com/dj-thank/choplab-sampler`
- public-release branch: `agent/public-choplab-release`
- release workflow: `.github/workflows/release.yml`

The successful GitHub Actions run `31319111062` verified the public branch with:

- `scripts/validate_project.sh`: PASS.
- `:app:testDebugUnitTest`: PASS.
- `:app:lintDebug`: PASS with warnings but no errors.
- `:app:assembleDebug`: PASS.
- debug APK artifact: `choplab-debug-and-reports`.
- downloaded APK SHA-256: `07A53C695D7A229816E0FC0F53C4B5C9F270C705228DE7320008B4074785FE67`.
- public Release: `https://github.com/dj-thank/choplab-sampler/releases/tag/v0.1.0-preview.1`.
- release workflow run `31319529630`: `build-and-package` and `publish-release` PASS.
- public Release APK: `ChopLab-v0.1.0-preview.1-debug.apk`.
- public Release APK SHA-256: `4E6220484F5991B34792CBCFCC5B251460893D9433DD2BE06A6B4635BCBEA513`.

The CI artifact was first installed onto the connected physical Pixel 9a. The public Release APK uses a different debug signing key, so Android correctly rejected an in-place update; a read-only app-data check found only cache/compiled-profile files and no project files. The old preview was then uninstalled and the public Release APK was installed successfully. Package `com.choplab.sampler` reports version `0.1.0`, `minSdk=29`, `targetSdk=36`; `MainActivity` launched and the immediate logcat check found no fatal exception. This is launch/install evidence only, not a complete audio workflow or latency result.

## Target-machine verification — 2026-08-09

Observed on Windows 11 with workspace-local Temurin JDK 17, Kotlin 2.3.21, Gradle 9.5.0, Android command-line tools 22.0, and adb 37.0.1:

- `scripts/validate_project.sh` passed after the domain changes.
- Six pure Kotlin JUnit test classes passed: `OK (12 tests)`.
- The Gradle wrapper starts successfully on JDK 17.
- The local Windows shell does not have `local.properties` or Platform 36 / Build Tools 36.0.0 installed, so the Android Gradle tasks were not run locally. The public GitHub Actions run listed above supplied the Android SDK and passed the Android tests, Lint, and debug APK build.
- adb sees a physical Pixel 9a as `device` on Android 16 / API 36 / `arm64-v8a`.
- `com.choplab.sampler` is installed from the successful CI debug artifact and launched once without an immediate fatal exception.
- Portable JDK/SDK/Kotlin/Gradle storage and Gradle/build/test output paths now resolve through verified NTFS junctions to `F:`. The offline validation and adb device check passed again after relocation.

Source-only foundations added in this checkpoint:

- Immutable, stereo-capable `PcmBuffer` validation and bounded versioned project models, including metadata and pattern-event limits.
- Legacy MVP state-to-project adapter.
- Pure pad-range assignment command shared by the ViewModel and host tests.
- Playback and pattern-render service interfaces for incremental legacy/native coexistence.

These foundations are host-tested but are not yet a user-visible Pro implementation. The public CI debug build and initial device launch are verified; complete device audio workflow, permissions, lifecycle, and latency tests remain open.

## Buildable baseline

The active Gradle project is the MVP under the repository root.

Implemented in the baseline:

- Android 10 / API 29 minimum.
- Audio import through SAF and MediaCodec.
- Microphone recording.
- Android Playback Capture for sources that allow capture.
- Mono PCM internal representation.
- Waveform range selection, zoom, scroll, manual/equal/transient chopping, zero-crossing snap.
- 4 banks × 32 pads, presented as two fixed 16-pad pages per bank.
- Auto-next pad/slice assignment.
- AudioTrack-based low-latency playback.
- Per-pad pitch-by-rate, tone, gain, reverse, one-shot/gate, choke.
- 16-step sequencing, BPM, swing, quantized recording.
- Four-bar mono WAV export.

The offline project validation script passed when this workspace was prepared:

```text
PASS: project-level offline validation completed
```

A complete Android SDK/NDK build was not run locally. The public Android debug build is verified in GitHub Actions; native NDK/Oboe targets remain unimplemented and therefore are not claimed.

## Intended Pro target

The requested target adds:

- Oboe/AAudio native audio engine.
- stereo-aware project migration beyond the current mono MVP archive and real-device lifecycle durability;
- deeper history policies beyond the current bounded MVP Undo/Redo;
- Stereo import, playback, processing, and export.
- Pitch-independent time stretch.
- ADSR.
- LFO.
- Pad and master effects.
- USB/Bluetooth/virtual MIDI, velocity, CC learn, clock and transport.
- Multiple patterns and Song mode.
- Master and stem export.

## Reference material

`reference/pro-v0.2/` contains partial source and design documents for the Pro target. It includes:

- `SamplerCore.cpp`
- `NativeBridge.cpp`
- `OfflineRenderer.kt`
- `ProjectArchive.kt`
- `MidiController.kt`
- architecture and feature documents

These artifacts are not wired into the root Gradle project and have missing dependencies. They are design input, not a completed implementation.

## Immediate next milestone

1. Keep the public preview CI and tag-release path green.
2. Continue `plans/active/choplab-pro-integration.md` as the native/pro migration plan.
3. Define a versioned stereo-capable domain model and pure tests.
4. Add NDK/CMake/Oboe infrastructure and a minimal native tone/single-sample proof before replacing the existing engine.
5. Migrate one vertical feature at a time while keeping the application buildable.

## Evidence policy

Update this file only with observed facts. Record exact commands, dates, device/API levels, ABI, and output paths. Separate:

- source implemented;
- host/unit tested;
- Gradle built;
- emulator tested;
- physical-device tested;
- latency measured.

## Workspace preparation checks

See `docs/PREPARATION_VALIDATION.md`. Offline validation and configuration syntax checks passed locally; the public GitHub Actions run established the current Android debug build status.
