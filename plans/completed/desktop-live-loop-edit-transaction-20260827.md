# Keep a playing Windows loop truthful while editing its PAD

## Purpose and user-visible outcome

WindowsでBeat loopを鳴らしながらKEY/TONE/LEVEL、trim、reverse、CHOKEを調整したとき、更新後のloop開始に成功した場合だけproject/history/autosaveへ編集を確定する。出力adapterが失敗した場合は、旧loopと旧PAD設定を保持し、画面だけ「再生中」または編集済みになる分裂を起こさない。録音中やloading中に拒否した編集は、音声adapterへretriggerを送らない。

## Current state

- exact base: `7e8603c0e9a64ff6cb4b74d4d244985494a7256c` / tree `2ca0ce170de67417907021ee7f6405aed90ee555`（Wave 11 closeout）。
- owner root: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-desktop-loop-edit-transaction-20260827`、branch `codex/choplab-desktop-loop-edit-transaction-20260827`。
- `DesktopSamplerController.updateSelected`はproject editを先にcommitし、active loopを明示stopしてから新しいvoiceをtriggerする。trigger failureは例外を外へ出し、committed state/historyと停止した実音を戻さない。
- 同helperはloading/recordingで`commitEdit`が拒否した後もcurrent loopを再triggerする。
- `JavaSoundWavPlayer.triggerPad`はcandidate Clip生成・開始より前にsame-PAD/choke voiceをcloseするため、candidate failure時に旧loopを保持できない。
- Android engineはpreallocated voiceのlive parametersをcursorを保ったまま更新する。今回はAndroid経路を変更しない。

## Constraints and invariants

- target files: `desktop/.../DesktopSamplerController.kt`、`DesktopSamplerAudioEngine.kt`、`JavaSoundWavPlayer.kt`、desktop tests、SSOT/plan docs。
- active loop editはcandidate playbackを開始できた後だけproject mutationとしてcommitする。failureは旧editable state、history、loop ownershipを保持し、truthful Japanese statusを返す。
- Java Sound adapterはreplacement candidateの開始に失敗した場合、既存conflicting voicesをcloseしない。candidate自身はcloseしてリークしない。
- successful replacementは従来どおりsame physical PADをmonophonicにし、同じnonzero CHOKE groupを止め、異なるPADのintentional polyphonyを保持する。
- loading/recording edit admission、Undo/Redo、autosave merge key、loop playhead reset behaviorを広げない。
- Android、shared model/schema、WAV/export、version/signing/release、Pixel/ADB、provider/public/Humanはscope外。
- 実Windows endpointが現hostに無いため、audible qualityやdriver behaviorをLOCAL testから主張しない。

## Architecture and interfaces

`DesktopSamplerAudioEngine.triggerPad`のfailure contractを「replacement candidateを開始できなければexisting voicesを保持する」と明文化する。Java Soundはcandidate Clipを準備してactive registryへ仮所有させ、開始成功後にだけ事前計算したconflict setをretireする。開始失敗時はcandidateだけをabandonする。このorderingはactual adapterが使うsmall internal helperでhost-testする。

controllerはeditable snapshotからcandidate PADをpureに作り、loading/recordingとno-opを先に拒否する。selected PADがactive loop ownerならadapter replacementをpreflightし、成功後にだけ既存`ProductionSession.applyEdit`/merge key/autosave経路へcommitする。失敗時はsession-only status更新だけを行う。

## Milestones

### Milestone 1: Failure and rejection RED

- controller test: loop edit trigger failureがthrowせず、PAD value、loop owner、stop calls、history frontierを変えない。
- controller test: active recordingで拒否したPAD editがloop retriggerを増やさない。
- adapter ordering test: candidate start failureはcandidateだけをabandonし、existing conflictsをretireしない。successはstart後にretireする。
- Acceptance: current implementationで少なくともfailure/rejection assertionsがREDになる。

### Milestone 2: Transactional GREEN

- `triggerPad` replacement orderingをcandidate-firstにする。
- `updateSelected`をadmission → candidate playback → project commitの順へ限定する。
- successful KEY/TONE/LEVEL/reverse/trim/choke loop edit、same-PAD monophony、CHOKE、recording/loading rejectionを回帰確認する。

### Milestone 3: Review and full local gate

- Standards/Spec adversarial review、desktop focused/full tests、configured validation、full clean Gradle/package/SBOM/artifact read-back、SSOT/active-state closeout。
- Acceptance: unresolved findings `0/0`、exact product/closeout HEAD/tree/hashes、canonical dirty preservation、remaining physical audio gatesを記録する。

## Progress

- [x] 2026-08-27T01:48+09:00 — Wave 11 exact closeoutとcurrent sourceをread-backし、active-loop editのcommit/stop/trigger failure splitとrejected-edit retriggerを選定。専用clean worktreeを作成。
- [x] 2026-08-27T01:51+09:00 — Milestone 1 RED。controller failure／recording rejectionの2 testsが既存実装で2/2 failure。adapter start-order helperはmissing API compile RED、candidate open cleanupもmissing API compile REDを確認。
- [x] 2026-08-27T01:51+09:00 — Milestone 2 GREEN。admission→candidate replacement→project commit、candidate prepare/start failure cleanup、success後だけconflict retireを実装し、focused正負controlとdesktop full testをPASS。
- [x] 2026-08-27T02:05+09:00 — Milestone 3 closeout。Standards/Spec findings `0/0`、clean 197-task gate、560 tests / 100 suites、configured/Python/public-surface/release-negative/package/SBOM read-backをPASS。product checkpoint `b8db4368511d8dd3578634bdd071be0da0f38b8a` / tree `a9b28c0d486cde7a7625258e10d97619d669fccf`を固定し、physical Windows audio以下を別gateに保持。

## Discoveries

- `updateSelected`は`commitEdit`がloading/recordingを拒否しても後続のstop/triggerを実行していた。録音中の編集値は保護されても、再生adapterには副作用が漏れていた。
- Java SoundはPCM render／Clip open／listener登録／startのいずれでも失敗し得る。conflict setを先にsnapshotし、candidate resourceを各段階でowned cleanupしてからsuccess時だけretireする必要がある。
- same-PAD monophonyとCHOKE retirementは`triggerPad`自身の責務なので、controllerの先行`stopPad`はtransactionを壊す重複ownerだった。

## Decision log

- 2026-08-27T01:48+09:00 — another layout proxy、pan/stems/mixer、arbitrary Song、native/MIDIではなく、日常のlive loop調整でstate/history/audioが分裂するbounded reliability defectを選択。
- 2026-08-27T01:48+09:00 — Java Soundのold-voice preservationをcontroller fakeだけの仮定にせず、adapter contractとactual ordering helperの両方で固定する。
- 2026-08-27T01:48+09:00 — candidate開始後の数ms overlapは、旧loopを先に破壊して失敗するより安全。物理click/overlap/latencyの聴感はdevice/Human gateとして残す。

## Validation log

- baseline: clean branch `7e8603c` / tree `2ca0ce1`、Wave 11 full gateは完了済み。product bytes変更前に同一高コストgateは再実行しない。
- controller RED: `failedActiveLoopEditKeepsTheOldPadLoopAndHistoryFrontier`と`rejectedRecordingTimePadEditDoesNotRetriggerTheLoop`が既存実装で2/2 failure。GREEN後はsuccess/no-op/history controlを含む3 controller testsがPASS。
- adapter RED/GREEN: missing `startReplacementBeforeRetiringConflicts`と`prepareCandidateOrAbandon`でそれぞれcompile RED。GREENはprepare failureでcandidate close、start failureでcandidate abandonのみ、successでstart→same-PAD/choke conflict retire順を固定。
- Standards review RED/GREEN: recoverable adapter handlingが`runCatching`でfatal `Error`までstatusへ変換していた。`AssertionError` negative controlをRED確認後、UI recoverable境界を`Exception`だけへ限定し、fatalはstateをcommitせず再throwする。
- `:desktop:test` full PASS。actual endpoint/Clip driverは現host unavailableのため未観測。
- configured validation PASS（product checkpoint public surface 435、18 Gradle tasks、six XML、wrapper/executable/UTF-8）、closeout Python policy 59、current＋reachable-history public surface 435、`git diff --check` PASS。
- clean full Gradle/package gate PASS in 5m04s: 197 tasks（193 executed / 4 up-to-date）、560 tests / 100 suites、failures/errors/skips 0、lint debug/release各0 errors / 7 warnings。
- unsigned release APK 24,208,500 / `E9DEB956D6F47FB24B89A26A8B6E70C5B941D7C93FDB9A3DAEB91FB78C2464BA`はversion `0.17.0` / code `27` / `aapt2` policyをPASSし、signed-required negativeはexit 1。Windows app-image 405 files / 176,642,851 bytes / manifest `EE98847088B77942ECD98F0BF1DD27F336A8D0421A0E22C0AD1D8B09BCC3A479`。CycloneDX 1.6は650 components / 651 dependencies。

## Risks and rollback

最大のriskはcandidate-first開始時の一時overlap、candidate listenerとactive registryのrace、playback成功後にproject commitが進まない分裂である。start/abandon/retire order test、controller success/failure/no-op/admission controls、full desktop gateで反証する。rollbackはこのisolated branchを採用しないことだけで、Wave 11 closeoutとprotected dirty checkoutは不変。

## Remaining device validation

現hostにはpresent Windows AudioEndpointが無い。actual Clip/driver failure、audible continuity、click/pop、latency、route removal、Bluetooth、sleep/resume、Narrator、Human qualityはこのLOCAL contractから昇格しない。
