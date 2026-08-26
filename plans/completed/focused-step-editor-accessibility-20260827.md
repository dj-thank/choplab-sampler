# Make all sixteen Beat steps accurately editable

## Purpose and user-visible outcome

`並べる詳細 / STEPS`を開くと、選択PADの16 stepsが一画面に同時表示され、compact phone、large text、landscape、Windowsのいずれでも各stepを48dp以上のtargetとして正確にON/OFFできる。portraitは4×4、landscapeは8×2にして順序を保ち、scrollやhidden pageを増やさない。BPM、音色、配置preset等の従来fine controlsは別の明示actionから引き続き利用できる。

## Current state

- exact base: `600211fa20b0cb3f4e1fdcaf0878d0aecb7166a3` / tree `3300900798ede19eef00fc2992fefc192fbab531`。
- owner root: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-step-accessibility-20260827`、branch `codex/choplab-step-accessibility-20260827`。
- `StepSequencer.kt`は16 cellsを8×2で描く。portrait fine surfaceは全高72/84dpなのでcell heightは48dp未満。
- landscape fine surfaceは`BeatLaneBoard`と`SequenceControlDeck`を分割し、selected-pad sequencerはさらに狭い。個別semanticsはあるがphysical target sizeを証明しない。
- `docs/FEATURE_MATRIX.md`は16-step全セル48dpをresponsive redesign待ちと明記する。
- canonical preservation checkout `6033d85b` / tree `39f8aa19`はowner外で変更しない。

## Constraints and invariants

- exact target files: `shared/.../ui/StepSequencer.kt`、新しいfocused-layout policy、`OtohiroiDeck.kt`、shared/app tests、SSOT/plan docs。
- current revisionからproduction state、step key、active/playhead/enabled semantics、toggle callback、Undo/autosave classificationを変えない。
- 16 stepsを同時表示し、portrait 4×4 / landscape 8×2、row-major 1–16、各cell幅/高さ48dp以上。
- focused surfaceは非scroll。既存fine controlsを削除せず、明示的に往復できる。
- audio engine、realtime callback、DSP、archive schema、WAV/export、signing/release workflowは変更しない。
- Pixel/ADB、provider/public、secret/signing、recording、Human操作はscope外。

## Architecture and interfaces

shared pure policyは`DeckLayoutMetrics`とviewport width/heightからfocused workspaceのcontent bounds、orientation別columns/rows、headerとgapを差し引いたcell boundsを計算する。UIとtestは同じconstants/policyを使い、supported reference viewportでminimum targetを反証する。

`SequenceWorkspace`はlocal presentation stateだけで`QUICK`、`FOCUSED_STEPS`、既存`FINE_CONTROLS`を切り替える。`FOCUSED_STEPS`は48dp navigation headerとfull-weight `StepSequencer`だけを持つ。`QUICK`と`FINE_CONTROLS`は既存composableを再利用する。production stateやcontroller interfaceへ新しいfield/commandを加えない。

## Milestones

### Milestone 1: Layout and navigation RED

- Scope: pure focused-step geometryとworkspace mode policy。
- Files: new shared policy/test、`GuidedWorkflow`またはlocal presentation seam。
- Tests: 360×640、412×820、640×360、1280×720、font scale 1.0/1.3/2.0。16 cells、orientation、row-major index、minimum 48dp、invalid viewport fail-closed。
- Acceptance: current source lacks policy/API and RED test fails before production implementation。

### Milestone 2: Focused shared Compose surface GREEN

- Scope: navigation header、focused `StepSequencer`、existing fine-control preservation。
- Files: `StepSequencer.kt`、`OtohiroiDeck.kt`、policy tests and contract tests。
- Tests: all 16 descriptions/keys、disabled state、playhead、active state、QUICK/fine-control transitions、compile on shared Android/Desktop。
- Acceptance: focused surface uses policy columns and available bounds; no scroll/pagination/modal; existing control page remains reachable。

### Milestone 3: Review and full local gate

- Scope: Standards/Spec adversarial review、configured validation、packages/artifacts、SSOT。
- Checks: shared Desktop/Android host、app unit/Lint/APKs、JVM core、desktop test/package、Python policy、public surface、release verifier negative signed control、Windows/SBOM、diff/status。
- Acceptance: unresolved review 0/0、exact HEAD/tree/artifact hashes、canonical dirty preservation、remaining device/Human gates recorded。

## Progress

- [x] 2026-08-27T00:42+09:00 — Wave 10 exact closeout、SSOT、explicit 16-step 48dp gapをread-backし、dedicated worktreeを作成。
- [x] 2026-08-27T01:20+09:00 — Milestone 1 RED/GREEN。viewport/font geometry、row-major 1–16、48dp、invalid/undersized fail-close、3-mode transitionをshared pure policyへ固定。
- [x] 2026-08-27T01:20+09:00 — Milestone 2 shared Compose integration。`STEPS`はfocused editorを開き、QUICK／BPM・音色controlsへ明示的に往復する。selected PAD key、active/playhead/disabled semanticsを同一presentationへ集約。
- [x] 2026-08-27T01:31+09:00 — Milestone 3 full gate and closeout。clean 197 tasks、553 tests / 99 suites、lint／configured／Python／public-history／Android正負control／Windows／SBOMをread-backし、Standards/Spec unresolved 0/0で`LOCAL_PASS`。

## Discoveries

- existing portrait fine surfaceは8×2 gridへ72/84dpしか割り当てず、vertical target 48dpを構造上満たせない。
- full focused workspaceなら、content padding/fixed chromeと48dp navigation headerを差し引いても360×640 font 2.0でportrait 4×4、640×360 font 2.0でlandscape 8×2を48dp以上にできる。
- paginationやscrollは不要で、既存fine-control surfaceを二次面として保てば機能削除も不要。
- nominal 640×360のsystem/outer inset後を模した632×328 font 2.0では通常3dp gapがcell height 47.5dpになる。compact landscape focused editorだけ2dp gapにすることで48.5dpを確保し、他surfaceのlayout constantは変更しない。
- 新しいgeometry、workspace mode、step presentationはshared内部契約で足りる。既存public `BeatWorkspaceSurface(Boolean)`を拡張せず、source/binary互換面を増やさない。

## Decision log

- 2026-08-27T00:42+09:00 — Wave 11はstems/pan/release基盤ではなくcentral Beat step accuracyを選択。explicit SSOT gap、local falsifiability、user outcomeへの直接性を優先。
- 2026-08-27T00:42+09:00 — hidden page/scrollでtargetを稼がず、focused full-workspaceをorientation別4×4/8×2にする。
- 2026-08-27T00:42+09:00 — existing BPM/音色/preset面を削除せず、focused editorから明示的に往復する。
- 2026-08-27T01:20+09:00 — root inset controlをsupported contractへ加え、compact landscape focused gapを2dpへ限定。unsafeな縮小gridを表示する代わりに、policyは48dp未満をfail closedにする。
- 2026-08-27T01:20+09:00 — controller/state/schemaへmodeを足さず、`rememberSaveable`のpresentation nameとpure reducerだけでQUICK／FOCUSED_STEPS／FINE_CONTROLSを管理する。

## Validation log

- baseline doctor: Git/JDK 17/Codex/repository/clean PASS。machine-local SDK pathはworktreeへ保存せず、explicit `ANDROID_HOME`でconfigured validation 18 tasks、public surface 430、XML、wrapper SHA、UTF-8 policy PASS。
- geometry RED: `resolveFocusedStepLayout`／workspace mode API不在でshared test compile failure。GREEN: reference 360×640、412×820、640×360、1280×720のfont 1.0/1.3/2.0、row-major、minimum target、fail-close PASS。
- semantics RED: `stepCellPresentations`不在でshared test compile failure。GREEN: selected PADの16 exact keys、active/playhead/disabled descriptions、invalid columns PASS。
- review RED: inset control 632×328 font 2.0がassert-not-null failure（3dpで47.5dp）。GREEN: scoped 2dp gapで48.5dp以上。
- product checkpoint `96118949fc8d8ec766715a6eb110d76523ac1095` / tree `d4e9b929f2e250e784bd2eeeb4dc309044bdb00c`。clean full Gradle gate 197 tasks（196 executed / 1 up-to-date）、553 tests / 99 suites、failure/error/skip 0。Lint debug/release各fatal 0 / errors 0 / warnings 7。
- configured validation 18 tasks、Python policy 59、current＋reachable-history public surface 433、`git diff --check` PASS。unsigned Android verifierは`manifest_tool=aapt2`でPASSし、`--require-signed`はexpected exit 1。Windows ProductVersion、405-file app-image manifest、CycloneDX 650/651もPASS。

## Risks and rollback

最大のriskはpresentation-only stateがproduction historyへ混入すること、small viewportで実cellがpolicyより縮むこと、existing fine controlsへの到達を失うこと。pure geometry、source contract、host compilation、full gateで反証する。rollbackはこのisolated branchを採用しないことだけで、Wave 10 clean closeoutとcanonical dirty checkoutは不変。

## Remaining device validation

Compose geometry/semanticsはphysical touch、TalkBack/Narrator speech/order、one-hand comfortを証明しない。Pixel/ADB、actual accessibility service、Human acceptanceは個別権限付きtaskへ分離する。
