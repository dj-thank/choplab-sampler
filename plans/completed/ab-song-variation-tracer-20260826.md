# Make an A/B beat into one truthful four-bar Song

## Purpose and user-visible outcome

BEATで作った16-step patternをA/Bの二つへ分け、現在のpatternをもう片方へ複製してvariationを編集し、4小節の各sectionをAまたはBへ割り当てられるようにする。Song modeでAndroid/Windowsから同じ順序を聴け、保存して開き直しても並びが残り、WAV exportも同じ4小節を出力する。

## Current state

- baseline HEAD `41d715c7bca702ddd5582a798e69511287241f62` / tree `091f6a307e685b1e8fb22e4a883778acb45563fa`。
- Wave 7 exact inputはcleanで、190 Gradle tasks、471 tests / 87 suites、failure/error/skip 0、lint 0 errors / 7 warnings、Android/Windows package、SBOM、artifact read-backが`LOCAL_PASS`。
- current production stateは`SamplerUiState.activeSteps`一つ。Android `SamplerEngine`、Windows `DesktopTransport`、`PatternRenderer`、schema 5 `ProjectArchiveCodec`もone-pattern前提。
- `ProjectPattern` / `SongSection`はfoundation modelとして存在するが、current MVP state、UI、transport、archive、exportへ接続されていない。
- source inspectionではodd BPM/swing時、Android countdownのevent frameとofflineのfloor + per-bar roundが一致しない可能性がある。Song full-PCM acceptanceのREDで反証する。

## Constraints and invariants

- exactly two patterns A/B、各16 steps、exactly four one-bar sectionsだけを実装する。
- `activeSteps`は選択中patternの編集truthとして保持し、pattern switch、persistence、playback、render境界でsnapshotを正規化する。非選択slotを暗黙に上書きしない。
- pattern/section/mode editはProductionSession historyとautosave revisionを進める。runtime current stepはarchiveへ保存しない。
- schema 1–5はPattern A、B empty、sections A/A/A/A、Song mode offへ移行する。schema 6の矛盾したselected steps、範囲外slot/section、過剰fieldはfail closedする。
- playback中のpattern selection、copy、section edit、mode changeはmid-barの曖昧な適用をせず、停止を促して変更しない。
- Android callbackはprecomputed primitive arraysだけを読む。callback内のcollection allocation、locking、I/O、log、UI workを禁止する。
- Windowsはwall-clock transportでありsample-exact Android claimへ含めないが、bar/step orderをhost testで証明する。
- UIは既存BEAT production dockから一つのArrange dialogを開く。compact inline layoutを増やさない。
- canonical dirty checkout、Pixel/ADB、OAuth、GitHub/provider/public、secret/signing、録音、Human GOはscope外。

## Architecture and interfaces

- `shared`にbounded `PatternArrangement` valueとpure transitionsを置く。二つのstored slot、selected slot、4 section、Song modeを所有し、`SamplerUiState.activeSteps`をselected slotへmaterializeするhelperを唯一の正規化境界にする。
- `SamplerDeckController`へselection/copy/section/mode commandsを追加し、Android/Windows controllerが同じpure transitionをProductionSession transactionとして適用する。
- Android `SamplerPlaybackEngine`はexisting `setPattern`を互換入口として残し、default method `setPatternSequence`を追加する。`SamplerEngine`はset時に`Array<Array<IntArray>>`へprecomputeし、step 15→0でsectionを進める。
- Windows `DesktopTransport` callbackはbar indexとstep indexを通知する。section/mode変更は再生中lockしつつ、controllerは各stepでcurrent normalized sequenceを読むため、既存どおりstep編集だけは次のtriggerへ反映する。
- `PatternRenderer`はone-pattern repeatをsequence APIへ委譲する。event scheduleはAndroid countdownと同じcontinuous frame boundaryを使い、barごとのfractional resetをしない。
- schema 6 manifestはlegacy `steps`に加え、selected slot/mode/sectionsとA/B stepsを保存し、selected slot bytesとの一致を検証する。
- Arrange dialogはpure presentation policyを使い、A/B selection、copy、section 1–4、loop/Song truthを日本語で表示する。

## Milestones

### Milestone 1: Shared arrangement contract RED → GREEN

- Scope: bounded model、selection/copy/section/mode transitions、history equality。
- Files/interfaces: `shared/.../model`, `SamplerDeckController`, shared tests。
- Tests/checks: A/B isolation、copy、invalid input、playback edit rejection、Undo/Redo content classification。
- Acceptance evidence: same common tests on Desktop JVM and Android host target。

### Milestone 2: Versioned persistence RED → GREEN

- Scope: schema 6 write/read、schema 5 migration、malformed manifest controls、autosave/manual save round-trip。
- Files/interfaces: `ProjectArchiveCodec`, JVM/Android/Desktop persistence tests。
- Tests/checks: selected pattern and inactive pattern bytes, four sections, mode, legacy fixture, invalid slot/section/mismatch fail closed。
- Acceptance evidence: archive byte read-back returns normalized exact arrangement; previous valid archive remains readable。

### Milestone 3: Live and offline Song order RED → GREEN

- Scope: Android precomputed sequence、Windows bar callback、offline 4-bar sequence、fractional schedule parity。
- Files/interfaces: `SamplerPlaybackEngine`, `SamplerEngine`, `DesktopTransport`, controllers, `PatternRenderer`。
- Tests/checks: A-only control、A/B/A/B order、stop/restart begins at bar 1、odd BPM/swing full-PCM oracle、no callback collection/helper invocation regression。
- Acceptance evidence: Android live/offline maximum PCM delta within existing tolerance and Windows exact emitted pad order。

### Milestone 4: One Arrange surface RED → GREEN

- Scope: BEAT dock entry and modal, Japanese truthful copy, accessibility semantics, no compact inline expansion。
- Files/interfaces: `ProductionDockPolicy`, `OtohiroiDeck`, UI/policy tests。
- Tests/checks: action enabled state, selected A/B, copy destination, four section labels, Song/loop mode, playback-locked guidance。
- Acceptance evidence: shared UI compile/tests and packaged Windows visual smoke if current host can safely render it without external audio/provider work。

### Milestone 5: Exact local closeout

- Scope: focused tests, full configured validation, package/SBOM/public-surface/artifact read-back, two-axis local review, SSOT and receipts。
- Acceptance evidence: exact product commit/tree, clean worktree, no canonical delta, gate ceiling `LOCAL_PASS`。

## Progress

- [x] 2026-08-26T22:05:35+09:00 — Wave 7 clean closeout and canonical dirty boundary re-read.
- [x] 2026-08-26T22:05:35+09:00 — Portfolio comparison selected bounded A/B four-bar Song tracer; stereo/accessibility/release deferred.
- [x] 2026-08-26T22:46:23+09:00 — Shared arrangement contract RED/GREEN、history/autosave classification、inactive B work safety。
- [x] 2026-08-26T22:46:23+09:00 — Schema 6 persistence、schema 1–5 migration、manual save/open read-back、malformed controls。
- [x] 2026-08-26T22:46:23+09:00 — Android/Windows/offline A/B order、continuous odd-tempo timing、full-PCM parity、desktop WAV read-back。
- [x] 2026-08-26T22:46:23+09:00 — Arrange UI/policy compile、playback lock、overwrite confirmation、scroll-safe explicit return-to-step action。physical visual/TalkBack smokeはdevice/Human gateへ保持。
- [x] 2026-08-26T23:00:00+09:00 — Exact product commit、190-task full gate、artifact/SBOM/manifest read-back、Standards/Spec review、SSOT closeout。

## Discoveries

- `ProjectPattern` and `SongSection` are not current MVP persistence/runtime truth; treating their presence as implementation would be a false completion claim.
- `PatternRenderer` currently floors fractional event starts and resets each rounded bar while Android uses a continuous frame countdown. This is a hypothesis until the new Song oracle produces deterministic RED evidence.
- Inline controls would compete with the already height-constrained compact step editor; a modal keeps the experiment end-to-end without claiming a responsive 48dp redesign.
- Existing pristine-starter/readiness logic observed only `activeSteps`; a changed B or Song arrangement could be misclassified as untouched and the Finish/export UI could be disabled. Whole-arrangement helpers now own work/audibility decisions.

## Decision log

- 2026-08-26 — Choose Song over another isolated audio-parity wave because it directly advances the creative outcome; timing work is admitted only when the Song oracle proves it blocks truth.
- 2026-08-26 — Bound the first tracer to A/B and four one-bar sections; arbitrary patterns, names, repeats, stereo and stems remain future decisions.
- 2026-08-26 — Preserve `activeSteps` as selected-edit compatibility truth and centralize materialization rather than mechanically rewrite every existing caller in one migration.
- 2026-08-26 — Reject arrangement mutations while transport plays; exact next-bar editing is a later transport-design decision.
- 2026-08-26 — Copying A→B/B→A requires a second click only when it would overwrite different non-empty work; Undo remains available after the accepted copy.

## Validation log

- Baseline: exact Wave 7 closeout receipt at `41d715c` reused because input bytes are unchanged; no expensive full gate rerun.
- Shared model/UI RED: unresolved A/B arrangement and Arrange presentation contracts; GREEN on Desktop and Android host targets.
- Persistence RED: 8 focused schema tests initially failed; schema 6 write/read, schema 5 migration and malformed controls GREEN. Desktop controller save→mutate→open returns exact selected/inactive patterns, sections and mode.
- Android sequence RED: unresolved primitive compiler/cursor; GREEN after precomputation. Odd BPM 123 / swing 57 A/B full-PCM live/offline oracle passes within the existing exact tolerance.
- Offline timing RED: first schedule implementation diverged at the final event (`61600` expected / `61601` actual); countdown-residual scheduling matches Android across fractional bar boundaries.
- Safety review RED: legacy snapshot dropped B/Song; three starter/readiness controls misclassified inactive work; the old dock contract omitted the new action. All targeted controls are GREEN.
- Pre-commit unit matrix: 59 Gradle tasks exit `0`; shared Desktop 53/10 suites, shared Android host 53/10, Android 257/46, JVM-core 61/8, Desktop 87/18 — 511 tests / 92 suites, failures/errors/skips `0`.
- Product anchor: `d70d5fcae1b6655241c2cc81a2596873f1cb4f45` / tree `5a0024f88281ab1fd08641c47f5e0c3b7d2855a7`; clean before docs-only closeout.
- Full configured Gradle gate: 190 actionable tasks（134 executed / 56 up-to-date）、exit `0`; the exact 511-test / 92-suite XML set has failures/errors/skips `0`. Input bytes were unchanged from the pre-commit unit matrix, so no duplicate clean rebuild was started.
- Lint debug/release: fatal 0 / errors 0 / warnings 7 each. `scripts/validate_project.sh` exit `0`（18-task Gradle fallback, XML/wrapper/UTF-8 checks）、Python policy 40 tests、public-surface 424 candidates、`git diff --check` PASS。
- Final artifacts: debug APK 31,656,050 / `C800E7B8FBB19B628AEC19600B451D6AB62553ED8983E74E21B0A61856AC6090`; androidTest 10,878,631 / `37F3AEDB16F4FD2BFCEC1D429D7E44A38A7ED157CAE30A6FB052CE0FB7093290`; unsigned release 24,175,732 / `09B43846CBEF6356089BA3B063E22C500B0F6484A2E2E5E42B313905FF6A8944`; Windows EXE 449,024 / `05BA300784A2B98197200A7B5AFCEDD70B62913DB71C1971B23A5E9785281630`。
- Android read-back: package/version/code/min/target、permission/exported allowlists、non-debuggable manifest、16-KiB-aware zip alignment PASS; `apksigner` reports unsigned as expected. The Python artifact verifier could not start because the installed SDK has no `apkanalyzer`; `aapt2`/`zipalign`/`apksigner` supplied the equivalent read-only evidence without installing tools or touching keys。
- Windows read-back: ProductVersion `0.17.0`; app-image 405 files / 176,604,385 bytes, sorted path-size-file-hash manifest SHA `0541DEFF54C196B24B59E0B06BF88EBEC58FEF7C58905087BC5F4BA6E173C7DD`。SBOM: CycloneDX 1.6、650 components / 651 dependencies、1,581,101 bytes / `23509E6C543E2C7B6E6F6FC49A6DDC7E5C463BCE4D72516AD8152697951B4FC8`。
- Release bytecode: `processTransportFrame` reads precomputed `int[][][]`, primitive cursor and fixed pad array; sequence compiler/collection construction/blocking/I/O/log/UI calls are absent. Standards/Spec unresolved findings `0/0`。

## Risks and rollback

- Risk: stale selected slot and `activeSteps` diverge. Mitigation: one normalization helper, archive mismatch check, sequence/save tests, and no direct slot writes outside pure transitions.
- Risk: schema 6 makes old clients unable to open new files. This is expected version behavior; old files remain readable and the UI/docs must not imply backward-open compatibility.
- Risk: sequence update allocates in callback. Mitigation: all conversion happens in `setPatternSequence`; callback reads arrays only and release bytecode check remains mandatory.
- Risk: Song UI crowds compact deck. Mitigation: modal-only surface and existing production dock height.
- Rollback: discard only this isolated branch/worktree. Canonical and Wave 7 closeout remain untouched.

## Remaining device validation

- Physical Android timing, xRun, audio focus/route loss and subjective transition quality.
- TalkBack focus order and spoken A/B/section/mode labels.
- Compact phone touch ergonomics and 48dp acceptance.
- Windows physical output-device playback and latency.
- Public/signed distribution and Human acceptance.
