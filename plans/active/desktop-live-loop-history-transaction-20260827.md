# Keep same-owner Windows loops audible across Undo and Redo

## Purpose and user-visible outcome

WindowsでBeat loopを鳴らしながらKEY/TONE/LEVEL、trim、reverse、CHOKE等を編集した直後にUndo/Redoしても、同じloop ownerがtarget snapshotに残る限り音を途切れさせない。restored PADが変わる場合はreplacement candidateを開始できた後だけhistory/revision/project/autosaveを進める。candidate failureは現在のloop、編集済みPAD、Undo/Redo frontierを保持し、操作を適用しなかったことを明示する。

## Current state

- exact base: Wave 14 closeout `924fb3c05697fb1ff801d3dba7fb1a77ee8c29dc` / tree `cd39425af780098df944b21e4c2a4bc50eeafcc6`。
- owner root: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-loop-history-transaction-20260827`、branch `codex/choplab-loop-history-transaction-20260827`。
- `ProductionSession.undo/redo`は`EditHistory`とrevisionを即時変更し、preview/cancel seamを持たない。
- Desktop `undoEdit/redoEdit`はhistory transitionを取得した後、`applyHistoryState`でtransport/scratch/player全停止とloop owner clearを必ず行う。
- Wave 12の`DesktopSamplerAudioEngine.triggerPad`は、same-PAD replacement startup failureならexisting voicesを保持するcandidate-first contractを持つ。

## Constraints and invariants

- shared history targetはpreview時に消費しない。current planだけがexact once commit/cancelでき、stale/cross-session planは拒否する。
- currentとtargetの`loopingPadIndex`が同一かつtarget PADがassignedのときだけcontinuity pathへ入る。
- target PAD bytes/parametersが同一ならaudio retriggerしない。異なる場合だけ`forceLoop=true` candidateを開始する。
- recoverable candidate failureはplanをcancelし、project/history/revision/canUndo/canRedo/loop owner/voiceを保持する。fatal `Error`はordinary statusへ変換しない。
- successはhistoryを一度だけcommitし、same ownerとplayhead truthを維持する。candidate replacement時だけplayheadをrestored PAD先頭／逆再生末尾へresetする。
- targetがloop ownerを保持しない、別ownerを指す、またはassignedでない場合はexisting disruptive Undo/Redo経路を維持する。このwaveでcross-mode atomicityを主張しない。
- companion policy、Android、project schema、DSP/render/export、release/version/signing、device/provider/public/Humanは変更しない。

## Architecture and interfaces

`ProductionSession`へcommand planと同じowner/epoch/exact-once規則を持つhistory planを追加し、`EditHistory`にはnon-mutating peekを置く。`planUndo/planRedo`がtarget snapshotを返し、platform adapterが必要なblocking effectを実行後にだけ`commit(plan)`する。`cancel(plan)`はhistory/revisionを変更しない。既存同期`undo/redo` APIは同じplan lifecycleを内部利用して互換を保つ。

Desktop controllerはUndo/Redoを一つのhelperへ集約する。same-owner targetではPAD差分だけをcandidate replacementし、成功後にtransition stateへcurrent runtime ownerを重ねる。non-continuity targetは従来の`applyHistoryState`へ流す。

## Milestones

### Milestone 1: Transaction RED

- shared testでhistory target preview、cancel不変、commit exact-once/stale rejectionを要求する。
- Desktop testでactive-loop edit後のUndoがloopをclearせず、replacement failureがhistoryを消費しないことを要求する。
- Acceptance: current sourceにはhistory plan API/continuity pathがなくcompileまたはbehavior REDになる。

### Milestone 2: Shared lifecycle and Windows GREEN

- history planをshared deep boundaryへ実装し既存Undo/Redo互換を保持する。
- same-owner no-change、replacement success、failure cancel、Undo→RedoをDesktopへ接続する。
- Acceptance: focused shared Android/Desktop host testsとDesktop controller/actual adapter testsがPASS。

### Milestone 3: Review and full local gate

- revision overflow/stale plan、history mutation order、fatal wrapping、no-op retrigger、playhead truth、companion ownership、autosave exactly onceをadversarial reviewする。
- clean full tests/Lint/package/SBOM、configured/Python/public-surface/release negative/artifact read-back、SSOT/active-state closeout。
- Acceptance: unresolved Standards/Spec findings `0/0`、exact product/closeout objectsとremaining physical/cross-mode gatesを記録する。

## Progress

- [x] 2026-08-27T03:27+09:00 — Wave 14 clean closeoutからportfolioを再計算し、same-owner active-loop Undo/Redoのhistory-first全停止を選定。専用clean worktreeを作成。
- [x] Milestone 1 transaction RED。
- [x] Milestone 2 shared lifecycle and Windows GREEN。
- [ ] Milestone 3 review/full gate/closeout。

## Decision log

- 2026-08-27T03:27+09:00 — provider/device/Human gate、初回loop-startのcross-mode transaction、copy/layout proxy、pan/stems/mixer/native/MIDI/AIより、既存live editの直後に毎回起こるaudible disruptionとhistory failure orderを優先。
- 2026-08-27T03:27+09:00 — same owner retained targetだけをcontinuity pathにする。loop ownershipが変わるhistory transitionへ同じ保証を一般化しない。

## Validation log

- baseline: clean `924fb3c` / tree `cd39425`、Wave 14 full 197-task / 582-test gate完了済み。product bytes変更前に同じ高コストgateは再実行しない。
- 2026-08-27T04:09+09:00 — RED: shared common test compileが`planUndo` / `planRedo` / `restoredState`未実装で失敗（`BUILD FAILED in 19s`）。
- 2026-08-27T04:16+09:00 — focused shared Desktop host + Windows controller GREEN（21 tasks、34 controller tests）。fixtureの内蔵B-01 step/LOOP制約を誤認した2 assertionsは、実contractを読んで別one-shot PAD editへ修正。
- 2026-08-27T04:19+09:00 — shared Android host `ProductionSessionTest`と実Java Sound `JavaSoundVoiceReplacementTest` GREEN（23 tasks）。
- 2026-08-27T04:23+09:00 — adversarial controls GREEN: no-change no-retrigger、Undo/Redo recoverable cancellation、fatal passthrough、stale/cross-session exact-once、owner removal disruptive fallback。

## Risks and rollback

最大riskはpreview targetとcommit targetのdrift、candidate開始後のhistory commit失敗、runtime-only loop truthの過去snapshot混入。owner/epoch/exact-once plan、serialized controller、same-owner predicate、focused negative controlsで限定する。physical Java Soundではcandidate-first replacement時に短いoverlap/clickがあり得るためHuman/device gateへ残す。rollbackはこのisolated branchを採用しないことだけで、Wave 14 closeoutとprotected checkoutsは不変。

## Remaining external validation

physical Windows endpointでのclick/pop、短いoverlap、loop continuity、latency、driver removal、Bluetooth/sleep/resume、Narrator/Human理解は未確認。loop start/stop、owner変更、source/transport/scratch、Android/device/provider/public/signing/Humanは別gate。
