# Verify Android document bytes before reporting success

## Purpose and user-visible outcome

AndroidでWAVまたはportable制作を選んだ保存先へ書き込んだ後、そのURIを再度streaming readし、app-owned validated sourceとbyte countおよびSHA-256が一致した場合だけ成功表示する。providerが例外を出さずsilent truncation、extra bytes、corruptionを起こした場合は成功と呼ばず、選んだdocumentが不完全な可能性と、アプリ内制作／安全コピーが保持されていることを明示する。

## Current state

- exact base: `9043af2f582b4f8965ce973ab007d79de4d9324b` / tree `7e71a8c4f23d7eae810c57acc7b80190881edb2e`（Wave 13 closeout）。
- owner root: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-verified-document-publication-20260827`、branch `codex/choplab-verified-document-publication-20260827`。
- `SamplerViewModel.exportPattern`は完成したcache WAVを`openOutputStream(destination, "w")`へcopyし、stream closeだけで成功を表示する。selected URIのread-backはない。
- `saveProject`はcache archiveのcodec read-backとinternal autosaveを済ませるが、provider destinationは同じくcopy closeだけで成功扱いする。
- 両経路の`runCatching`は`CancellationException`とfatal `Error`もordinary failureへ変換し得る。WAV temporary cleanupはrun block後にあり、fatal/cancellation pathのownershipが弱い。

## Constraints and invariants

- target files: JVM streaming publication helper/tests、Android ViewModel binding、pure status copy/tests、SSOT/plan docs。
- sourceは既にclosed/validatedなapp-owned file。copyとread-backはbounded bufferで行い、音声やarchive全体をmemoryへ積まない。
- destination outputのclose完了後にdestination inputを新規openし、exact bytes countとSHA-256を比較する。
- silent mismatch、output/input open failure、write/read failureは成功statusを公開しない。WAVは制作がアプリ内に残り、projectはinternal safety copyが残ることを伝える。
- cancellationは再throw、fatal `Error`は捕捉しない。owned temporaryは`finally`でbest-effort cleanupする。
- provider publicationはtransaction/atomic replaceではない。failure後に既存document bytesを復元できるとは主張しない。
- Windows、renderer/DSP、project schema/autosave format、version/signing/release、device/provider/public/Humanはscope外。

## Architecture and interfaces

`jvm-core`へsmall streaming publisherを置き、source input、destination output factory、destination read-back factoryを引数とする。copy中のsource count/digestと、close後read-backのcount/digestを比較し、mismatchをtyped/recoverable I/O failureにする。Android adapterだけが`ContentResolver`を所有し、WAV/project文脈の日本語statusを付ける。

ViewModelの二経路はunique cache temporaryを使い、`try/finally`で所有権を閉じる。`CancellationException`は明示rethrowし、ordinary `Exception`だけをユーザー向けfailureへ変換する。

## Milestones

### Milestone 1: Silent mismatch RED

- outputが正常closeするがread-backを短くするfake destinationを追加する。
- Acceptance: current sourceにはverified publisher APIがなくcompile REDになる。

### Milestone 2: Streaming GREEN and Android binding

- exact copy、silent truncation、extra/corrupt bytes、output/input open/write/read failureをhost testsで固定する。
- Android WAV/project経路を同じpublisherへ接続し、success/failure/cancellation/fatal/cleanup truthをsource testsで固定する。

### Milestone 3: Review and full local gate

- provider reopen timing、zero bytes、large stream、digest comparison、close failure、exception masking、cancellation、status overclaimをadversarial reviewする。
- focused/full tests、configured validation、clean full package/SBOM/artifact read-back、SSOT/active-state closeout。
- Acceptance: unresolved Standards/Spec findings `0/0`、exact product/closeout objectsとremaining provider/device gatesを記録する。

## Progress

- [x] 2026-08-27T02:50+09:00 — Wave 13 clean closeoutからAndroid document boundaryをread-backし、validated temporaryとprovider success表示の間にdestination read-backがないことを選定。専用clean worktreeを作成。
- [x] 2026-08-27T02:54+09:00 — Milestone 1 RED。JUnit harness差を修正後、silent truncationを置くtestはmissing `publishVerifiedDocument`だけでcompile failure。
- [x] 2026-08-27T03:03+09:00 — Milestone 2 GREEN。exact/short/same-size corruption/extra/empty、missing source、output/read-back open、write/read/close、zero-progress、cancellation、fatal controlsをstreaming helperでPASS。Android WAV/projectをselected URI read-backへ接続し、検証不能時の非success copyとunique temporary/finally cleanupへ変更。host全群582 tests / 103 suitesをPASS。
- [ ] Milestone 3 review/full gate/closeout。

## Decision log

- 2026-08-27T02:50+09:00 — active-loop history transaction、直前laneと重なるstartup ordering、rare internal-filesystem fallback、広いproduction breadthより、既存Android save/exportのsuccess truthを優先。
- 2026-08-27T02:50+09:00 — generic providerでatomic replace/rollbackを仮定しない。local sliceはexact-byte read-backとtruthful failureに限定し、provider-specific transactionは別gateに残す。

## Validation log

- baseline: clean `9043af2` / tree `7e71a8c`、Wave 13 full 197-task / 568-test gate完了済み。product bytes変更前に同じ高コストgateは再実行しない。
- RED: first harness run exposed this module's JUnit4 rather than `kotlin.test`; test importsをcorrect harnessへ直した後はmissing publisher APIだけでcompile RED。failure copy testもmissing shared messageでcompile RED。
- GREEN focused: publisher 13 tests、failure copy 1 test、shared Android/Desktop 66/66、Android app 265、JVM-core 81、Desktop 104。合計582 tests / 103 suites、failures/errors/skips 0。
- Standards review: caller未使用のpublic fingerprint resultを削除し、deep boundaryの外はsuccess/typed failureだけへ縮小。output close完了前のread-back、fatal/cancellation wrapping、zero-progress spinをnegative controlsで防止。
- Spec review: selected documentのcount＋SHA-256一致だけをsuccess条件にし、検証不能copyはWAV制作／project safety copy保持とdestination不完全可能性を表示。provider atomicity/rollbackは明示的に未達。

## Risks and rollback

最大riskはread-backを許可しないproviderで、書込み後に検証failureとなること。CreateDocument URIの通常read grantを使うが、検証不能を成功へ昇格しないfail-closed policyを採る。source file、internal autosave、production stateは保持する。rollbackはこのisolated branchを採用しないことだけで、Wave 13 closeoutとprotected checkoutsは不変。

## Remaining external validation

generic DocumentsProviderはatomic replace/rollbackを保証しない。実Google Files/Drive/Dropbox等のreopen timing、partial existing-document state、network/offline behavior、process death、device/provider/public/signing/Human qualityは別gate。
