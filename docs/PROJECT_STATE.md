# Project state

> **Local integration — 2026-09-16, unmerged.** Branch `claude/choplab-latest-android-20260916` merges the local production line `8a279bc` (Spotify automatic import and search/add, Windows built-in drum separation, BEAT finishing hub) with the draft PR #92–#97 chain `348d341`, then extends Spotify library import/search and drum separation to Android. Selected plan: [latest integration and Android parity](../plans/active/latest-android-integration-20260916.md), which records this integration's verification.

> **UI follow-up — 2026-09-11, unmerged.** Current selected plan: [UI quality](../plans/active/ui-quality-20260911.md). Exact baseline PR96 `3fe5906bbc29e54bb46689606ffb0cac3599e1ca` passed Windows/iOS/policy; Android failed three debug-sensitive startup assertions. This follow-up improves readable shared controls, PAD contrast/identity, SAVE sizing and state details, and adds actual shared-UI rendering tests. Supplemental host:74 distinct bodies, both debug modes PASS. Supported new-head CI and physical UI/audio remain separate. See [current UI receipt](ui/UI_QUALITY_20260911.md). No merge, release or installation. The first historical release snapshot below is retained unchanged.

## Current snapshot — 2026-09-03 Windows UI integration after PR #87

GitHub `main@61147bf06d8947b3c64d74aa9f046a1a2cbb1c17` contains the H13 correction, stable Compose 1.11.1, v0.17.2 signer recovery, Android audio-fidelity repair, PR #69's artifact/history scanner, and PR #87's complete corrective follow-up. This sole-writer branch integrates the reviewed Windows/UI brush-up on top of that product line before release hardening and final-main verification. Existing tags and the `v0.17.0` Release remain unchanged.

- UI product `822305c` moves KEY rendering to the project I/O worker, preserves the exact/latest resume intent, cancels late resume after ALL STOP/competing playback, bulk-copies WAV chunks, reuses one WAV chooser/last folder, and adds bounded Windows JVM startup options.
- Shared UI adds target-aware 4-second confirmation disarm, hover feedback, waveform wheel zoom/pan, enforced 1600 dp centered content, balanced landscape CHOP/PAD layout, compact BANK labels, 9 sp status, and 760×600 minimum window size.
- Exact integration checkpoint `68296f2079349ac0c61df2fb4e34e03fce250e16` / tree `2e6dea9f2da62d072407edf35419660cf3553bfb` passes shared 91×2, Android 289, JVM core 88, Desktop 185, H13 24, Android lint/APK/AndroidTest compile, full policy 207, current/history public scans 488 each, and `packageWindows`; failures/errors/skips 0 apart from the explicit local Windows symlink-privilege policy skip.
- Final main-ancestry integration `ee1ea293af37037846c0bd16a9b0087001b5c170` / tree `b28c2fc333223b43d3537979fb117d94f11fd3da` passes full policy 213 and current/history scans 488 each. `app`, `shared`, `jvm-core`, `desktop`, root build and settings objects are byte-identical to the Gradle-verified `68296f2` checkpoint.
- PR #88 hosted Windows first exposed the committed source snapshot exceeding the generic 4 MiB archive total. Corrective head `69e487385e3b49165c1082051dd316364a14c596` / tree `c6d7319316d5bc84880ca298841ca4e018dcd339` keeps generic limits unchanged and adds a source-only 16 MiB total / 4 MiB member CLI. The exact `git archive` passes; full policy 214 and current/history scans 488 each pass.
- PR #88 final product candidate `133951be7a5c58df23a6ecc5a3e3e228197aadb1` / tree `f71f00a3553cd79c16cf0be054bdb4ef7dc5c7dd` closes queued/latest/failed pitch-render races under one ownership lock, renders source PCM and prepares Clips outside the JavaSound monitor, keys destructive confirmation to project content, preserves pointer-anchored zoom, and keeps the strict accessibility-node condition with a 30-second hosted scheduling bound. Hosted exact-head results: shared 94×2, Android 289, JVM core 88, Desktop 191, H13 24, Android instrumentation 29, lint/APK/iOS/Windows package and 8/8 workflows PASS; policy 214 and current/history public scans 489 each PASS.
- PR #69 merged only scanner/policy/workflow/evidence changes; PR #87 adds complete historical-body classification, UTF-32, commit/tag header and per-message scans, ZIP symlink targets, PKCS#7-bound APK exemptions, V7/GNU TAR and PKCS#12 controls across members and APK signing pairs. No user audio, credential, tag, Release, device/provider account, or Human action is included.
- Receipt: [windows-ui-brushup-20260902](../outputs/windows-ui-brushup-20260902.md). Physical audio, real long-file timing, OS pointer delivery, accessibility speech, provider/public and Human gates remain separate.

- PR #83 merged as `2864117fe3c81b308033155dae337a6030165344`; PR #84 head `51238b1f29dc0c9bfb904d569fdf2081b23e56e3` passed hosted checks/review and merged as `f6cbfdcc65584264ca7fd1cf7c450e9cab284b14` with merged-main 4/4 workflows.
- PR #85 head `a7bf79fe790adff178e9dc3c0ed840ba2b489168` merged as current GitHub `main@e71e0fde2e8a7ef82020cfc905d07473b95c073b`. Merged-main Android `33625076385`, Windows `33625076392`, iOS `33625076368`, and supply-chain `33625076363` all succeeded. PR #86 follow-up `231da39e4c42b26d135bb3f5d7ce366fc6a540af` also rejects multiple identical signer PEM blocks.
- Annotated `v0.17.1` tag object `20f1beeaf68bc88da9913bf059bcb2aff9a5dcd4` peels to `f6cbfdc`. Release run `33622584694` stopped before staging because the old verifier could not parse a signer label; no `v0.17.1` Release exists and the tag will not move.
- Android audio product checkpoint `b63ed650e47c5555f4a328171c222ca4888a88ae` replaces the always-on `x/(1+abs(x))` curve: default gain 0.9 is bit-for-bit linear and only overload enters a C1-continuous knee below 0.98. Android `SamplerEngine.renderLoop` and offline `PatternRenderer` retain one shared limiter.
- Import/master hardening `9dc71a4652c943ded89c62bb50d9512270182d20`, DC/import-speed refinement `c5c5bcc692b947e684713f7a4e9b8c3762728f34`, dense invariant checkpoint `7e7c7ae5a5b30f7f3e526ba887825fe60b400fd1`, and review repair `2b0da00c7c117c0b188683739c6a495f13958664` / tree `7cc6a11584b89a165a855db67015207925202c53` bind the actual AudioTrack output seam, reject unsupported/mid-stream PCM encoding changes and raw NaN/Infinity before nominal clamping, prevent DC-created clipping, and densely verify limiter monotonicity/symmetry.
- Exact JDK 17 local audio result at `2b0da00`: shared Desktop 87, shared Android host 87, Android unit 289, JVM core 88; failures/errors/skips 0. Android lint, debug APK assembly, and AndroidTest Kotlin compilation pass; 99/99 selected tasks executed under the documented bounded-memory configuration.
- PR #86 exact head `0e32770a3778c583f06b0f9b48464338824d6b72` passed hosted checks 8/8, all review threads resolved, and an exact-head no-major-issue review before normal merge as `012b131784394b2fd641d580aaf4cd2d56b907f4`. Its merged-main workflows are the current provider read-back gate.
- Public Release contract remains Android debug preview + unsigned iOS Simulator archive only; stable-signed Android and Windows app-image remain verification-only. The new `v0.17.2` tag is explicitly held until every accepted branch converges on final main.
- Retained evidence: [PR #83 / #84 stabilization](../outputs/PR83-postmerge-accessibility-stability-20260902.md), [v0.17.1 failure / v0.17.2 recovery](../outputs/v0.17.1-tag-failure-v0.17.2-recovery-20260902.md), and [Android audio fidelity](../outputs/android-audio-fidelity-20260902.md).
- Current selected execution plan: [H13 GitHub integration and product convergence](../plans/active/h13-github-release-20260902.md). PR #85 merged-main and PR #86 exact-head are scoped provider evidence; PR #86 merged-main, this PR #69 integration, physical audio, Spotify provider, public binary, screen-reader speech, and Human gates remain separate until read back.

## Runtime candidate status — 2026-09-10

PR95 `d1626def5c8f7f117e954d71034b0e32d048e99a` passed all four hosted workflows before this integration. PR93 `a932918e25399a863ac79da1b74df6e14bfb3f97` had failing Android/Windows workflows. The Windows log identified six H13 assertions broken by an appended display-gain suffix in the viewport state description. The candidate restores the range-only state description and exposes the gain explanation on its own text node, without weakening those assertions. Do not reuse parent CI as this candidate's result.

The local Kotlin 1.9/JDK21 environment ran the actual selected production code with temporary test annotation/import adaptation. It is not the supported Gradle/JUnit/Compose or Android toolchain. The regular Gradle attempt failed downloading its distribution (DNS); no Android SDK/ADB is installed. Public-surface, release-policy and diff checks have separate logs.

No new dependency, schema, recording-gain, playback-DSP, workflow-permission or publication change is included. Stereo analysis intentionally changes detection/editing/display decisions, not PCM values. The new recording scratch buffer is per writer; waveform caches remain bounded and input-keyed. See the feature matrix and selected plan for scope and remaining gates.

## Local production line — 2026-09-05 to 2026-09-14

These dated checkpoints come from the unpushed local branch `codex/choplab-spotify-auto-import-20260913` (through `8a279bc`). They are retained verbatim apart from heading levels; each keeps its own gate and does not validate the 2026-09-16 integration.

### 2026-09-14 Built-in drum separation (Windows)

Desktop CAPTURE carries a built-in drum separator: the current source is rendered and passed through a bundled HT-Demucs FT drums-specialist ONNX export (StemSplitio, fp16 weights, 166 MB, commit-pinned URL plus SHA-256 verification at build time) executed by ONNX Runtime Java on CPU. Host DSP (44.1 kHz windowed-sinc resample, 343980-sample segments with 25% overlap, linear-fade weighted overlap-add) is pure and unit-tested; a real-model CLI smoke run separated a 10 s stereo fixture into a sane drums WAV. Completion auto-imports the stem into the private library for chopping; progress/cancel surface in the Capture panel. Six-minute job cap. LOCAL_PASS; subjective mix quality on real songs and GPU providers remain unverified.

### 2026-09-13 Search and add audio

Product `98073b6b965ac5b8a774fe18c494deb0f4d11529`. The normal import/search screen searches Spotify metadata and shows an Add action. Add runs the existing matching YouTube download and validated private-library publication; metadata is not presented as downloaded audio. During an active import, selections queue (100 max) and run afterward. Search results never overwrite liked tracks. Existing automatic favorites, deduplication and cancellation are retained. Windows UI uses 検索 / 追加; Android's existing UI is unchanged.

Desktop206/JVM107/Android294/UI-controller37 checks (12 overlap) passed with zero failure/error/skip; Android lint/build, project validation and Windows packaging passed. The mock-provider test covers Spotify search through actual library-file creation. The fresh image `desktop/build/windows-app-image-spotify-search-20260913/ChopLab` launched/responded/closed with an isolated profile and preserved existing processes. PAD receipt `work/PAD_CHOPLAB_SPOTIFY_SEARCH_20260913.json` binds 414 files. LOCAL_PASS + isolated Windows launch; live provider/download/audio/public not run. PAD report: `outputs/ChopLab-検索して追加.md`.

### 2026-09-13 Windows Spotify automatic library import

Product `3e67984a5469133c4e12b270c101521de54be4f8`, isolated worktree `work/choplab-spotify-auto-import-20260913`, based on the existing September 5 import build `e1b9d42`. Connecting Spotify now automatically reads liked-track metadata (up to 2000) and imports matching YouTube sources sequentially into the private library. No track or video picker is required. Already imported tracks persistently skip retrieval; unmatched/failed tracks remain visible in the result. A completed import does not select audio or alter the active project. Closing the panel continues the queue; explicit cancellation/disconnect only cancels the owned sync, preserving unrelated manual imports. Android keeps its manual Spotify flow.

Final local checks: Desktop 205, JVM core 107, Android 294, shared Desktop 115 and shared Android host 115; failures/errors/skips zero. Desktop UI/controller 36 checks (12 overlapping controller tests), zero failures/errors/skips. Android lint errors 0/warnings 11, APK, fresh Windows package and `scripts/validate_project.sh` passed. The mock OAuth-to-library test includes pagination and repeat synchronization; cancellation, candidate drift, deduplication and manual-import ownership have regression coverage. An initial test waited on a transient pre-start condition; it now waits for the second actual backend search and completion.

The packaged `0.17.2` app image launched with an isolated empty LOCALAPPDATA profile and a responding `ChopLab — おとひろい PC` window, then closed; pre-existing ChopLab processes were preserved. All 414 image files are hashed in the PAD receipt. Existing configured Client ID is retained, while tokens remain memory-only. Artifact: `desktop/build/windows-app-image-spotify-auto-20260913/ChopLab/ChopLab.exe`.

Gate: `LOCAL_PASS + isolated Windows launch`. No fresh live Spotify login/YouTube retrieval, physical audio, Android device, iOS or public release claim. Report: PAD `outputs/ChopLab-Spotify自動取り込み.md`; receipt: PAD `work/PAD_CHOPLAB_SPOTIFY_AUTO_IMPORT_20260913.json`.

### 2026-09-05 Desktop connect/import navigation

Product `58e543199cb7e945940bfc4dfb366327fe7e236e`. The native 連携 menu routes directly to Library, Spotify or YouTube import; PC-file actions open the existing chooser directly. The Connect panel now leads with source import choices and library access. Spotify playback controls are secondary and expandable; setup diagnostics are absent from the configured panel's main view. The existing internal storage/import pipeline and Android code are unchanged.

Desktop 202 + JVM core 100 + UI/controller 35 (12 overlap), final failures/errors/skips zero. Responsive panel pointer navigation at 760/1100, Windows packaging and project validation passed. A pre-existing loop UI wait timeout prompted a settled-geometry input wait in the test helper; final full validation passed. Native OS file-picker interaction and fresh provider/device checks were not run. New Windows artifact: `desktop/build/windows-app-image-connect-20260905/ChopLab`.

See [report](../outputs/desktop-connect-import-20260905.md) and companion JSON. LOCAL_PASS only for this change.

### 2026-09-05 audio source library

Product `6f7bc7c36a3ee667d4890eeed9c2c762f5e784e4`, packaging guard `cda7e5fa633ea2ecd6bea04f27dc636ff7fdf6f0`. Shared file/library/YouTube/Spotify source hub on Android and Windows. Decoded imports stay in an app-private hash-addressed library; selecting sources retains production pads/patterns. Spotify saved metadata selects a unique compatible YouTube candidate, with ambiguity requiring user choice. Existing four sources were copied to the Windows private library and a personal .choplib transfer artifact. Music is not in APK or repository assets.

826 standard tests + 34 UI/controller checks (overlap retained), final failures/errors/skips zero. Android lint/APK/test-APK compile, fresh Windows app image, project validation passed. One unchanged prior loop UI test timed out and passed the unchanged full recheck; no flake-fix claim. Runtime source 6f7bc7c; build-only guard prevents touching an active app image. Previous default image was partially cleaned before the guard; use the new `desktop/build/windows-app-image-audio-source-20260905/ChopLab` image.

Scoped external observations: Desktop Spotify OAuth/favorites 20, normal YouTube Part 4 download/decode, Android redirect save/readback after user approval. Physical Android import/audio, iOS and public release remain unverified. See [report](../outputs/audio-source-library-20260905.md) and JSON for exact artifact bytes and limitations.

### 2026-09-05 explicit Loop and additive layers

Product `360ccb0b14d7ed2fb4018d280335242904a2fc54` from `c83cad8`, including the compatible drum-rhythm fix checkpoint `a616bd5`. Latest user correction replaces the sparse loop dashboard and withdraws a step-order-first proposal: select a Chop, explicitly press Loop, then add favorite loops one by one. The shared workbench restores sound choices, core/layer context and independent layer removal while keeping automatic S/E editing and the source map.

Android admitted layer commands and Desktop incremental voice startup preserve an active core. Restart/scratch/recording companion paths include configured LOOP layers; export protects loop choke groups from vocal companions. Drums can start without retargeting an active core. Kit replacement preserves A/B rhythms and Song, including Undo and project reopen.

LOCAL_PASS: 812 standard tests plus 33 UI/controller checks (12 controller overlaps), all final XML failures/errors/skips zero. Android lint/APK/androidTest compile, Windows package and project validation pass. Independent Standards/Spec findings resolved; [report and immutable screenshots](../outputs/beat-loop-layers-20260905.md). Physical audio/touch, Android instrumentation runtime, iOS and Human acceptance remain unverified. Loop content is persisted; live addition timing and runtime core-monitor identity are not a recorded performance.

### 2026-09-05 chosen-loop handoff and compact Beat

Product checkpoint `3a51ca8` on the existing isolated refinement branch, based on `8e27658`. TRIM now explicitly starts the edited Chop with the pattern before navigating to Beat. Prior loop configuration cannot override the chosen sound; rejected loop admission keeps the editor and prior loop. Start admission is returned by both platform controllers and VOCAL cannot become a new loop owner through this action.

Compact Beat reserves useful waveform space and allows desk scrolling without adding zoom/precision controls. At 360×520/font1.3, the waveform and >=48dp controls are exercised through actual shared scene input. See [the report](../outputs/loop-handoff-compact-20260905.md) for final gate/results. Device audio/touch, Android instrumentation execution and iOS remain unverified.

### 2026-09-05 automatic range and loop-first Beat

Base `1e55128`, sole-writer refinement branch. The user's correction replaces the selected-range zoom/precision/beat-fit proposal with an exact-range waveform and always-visible S/E rolling dials. The whole-source map preserves cut locations and admits source playback before navigating into a new capture session. Earlier/manual cuts are pinned against later live-chop reflow.

Beat now centers the loop and exposes drums, live scratch, and secondary arrangement. Android live bound edits update the audible voice; layered loop/step startup fails closed. Repeated loop selection does not retrigger; starting during pending STOP is rejected. Failed rechop keeps editor, selection and history.

Local verification and artifact binding are recorded in [the workflow report](../outputs/automatic-range-loop-workflow-20260905.md). Physical audio/touch/recording, Android instrumentation execution/ViewModel E2E, iOS and public/Human acceptance remain unverified. Android gesture fixtures target the retained CHOP PAD surface; compiled instrumentation is not a device pass.

### 2026-09-05 UI and edit admission audit

Base `e5aa359`, same isolated refinement branch. Save now has six actions in four 56dp rows, compact counters and scroll-safe layout. Destructive editing moved to the arrangement context. Shared timed/content-bound confirmation replaces the separate copy path and binds PAD selection. Busy/no-op UI states and Android/Windows kit edit admission are aligned; Android ordinary edits reject loading while owned vocal completion can commit. Dormant autoChopTransient now guards every terminal path with the existing operation epoch (no current UI caller).

Final validation: 777 standard tests + 29 UI/controller checks (12 controller checks overlap), failures/errors/skips 0; Android lint/APK/androidTest compile, Windows package, project policy PASS. Source review and selected rendered/input scenarios only; physical audio/recording, Android ViewModel E2E, iOS and public/Human gates not claimed. [Report and evidence](../outputs/ui-audit-refinement-20260905.md); [completed plan](../plans/completed/ui-audit-refinement-20260905.md).

### 2026-09-05 pattern editing refinement

Isolated branch `codex/choplab-production-refinement-20260905` starts at fetched main `bed7a55`. This new user-authorized milestone supersedes the prior selected release plan for local product work; existing release artifacts and canonical dirty checkout remain preserved.

- Shared ProductionCommand now owns selected-PAD preset, selected-PAD clear and all-pattern clear for Android/Windows, including recording/loading rejection and no-op history behavior.
- Layer editing adds one-step forward/backward rotation with bar wrap; other PADs and the other variation remain unchanged. The sound rail keeps at least 96 dp and the layer body scrolls when controls do not fit.
- A/B editor offers confirmed selected-variation-only clear, retaining samples, the other variation and Song references; transport must be stopped.
- Both new edits use ProductionSession Undo/Redo and existing schema/autosave/export contracts.
- Validation: LOCAL_PASS; 769 standard tests plus 25 UI/controller checks, failures/errors/skips 0. Android lint/APK, Windows package, project validation and public-surface check PASS. Compact 520px component click and screenshot verified. See [receipt](../outputs/pattern-editing-refinement-20260905.md). Device audio, actual OS input, iOS runtime, publication and Human acceptance are not claimed.

## Preserved historical evidence

The original full state ledger is retained byte-for-byte in [PROJECT_STATE_HISTORY_20260903.md](PROJECT_STATE_HISTORY_20260903.md). The release-contract first snapshot above remains verbatim; later dated historical sections are in that ledger, not deleted. Its relative receipt links still resolve from the same directory. Historical device/provider/public/human observations do not validate new candidate bytes.
