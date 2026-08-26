# Preserve an existing Windows WAV when export fails

## Purpose and user-visible outcome

Windowsで既存WAVへ4小節を書き出し直すとき、新しいWAVが最後まで生成・closeできた場合だけdestinationを置換する。render、flush、close、moveの途中で失敗した場合は、以前の完成WAVをbyte-for-byte保持し、同じfolderにChopLabの一時fileを残さず、既存の失敗statusを返す。

## Current state

- exact base: `6fb1c94f283019580d0cbf0fecfadeefa324675a` / tree `ce7ce3d2acb7124708e801291b50838b0814e57f`（Wave 12 closeout）。
- owner root: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-atomic-wav-export-20260827`、branch `codex/choplab-atomic-wav-export-20260827`。
- product checkpoint: `ffe1bbdf59d0645652c3f8556fb6f601e5218670` / tree `d3ae7a9bb18d48ae323e92aaabfe58e25acd975f`。
- `DesktopSamplerController.exportBeat(outputFile)`はuser destinationを直接`PatternRenderer.renderSequenceToWav`へ渡す。
- `WavFileWriter`は`RandomAccessFile(file, "rw")`を開き、header前に`setLength(0)`する。後続のrender/I/O failureは以前のdestination bytesを戻さない。
- `DesktopProjectFiles.save`は同一folderのtemporaryへwrite/read-back後、atomic-move-firstで置換し、failure時にtemporaryを消す。WAV exportには同等のboundaryがない。

## Constraints and invariants

- target files: desktop persistence/export helper、`DesktopSamplerController`、desktop tests、SSOT/plan docs。
- temporaryはdestinationと同じparentに作り、writerが正常returnしてresource closeを完了した後だけreplaceする。
- render/write/close failureは既存destinationの存在・size・bytesを変更しない。temporaryはfinallyで消す。
- successはこれまでと同じ`PatternRenderSummary`とvalid PCM-16 WAV bytesを返し、destination name/status contractを変えない。
- atomic move unsupported時、既存targetはnon-atomic overwriteへfallbackせずfail closedにする。新規targetだけは競合を上書きしないsame-filesystem moveを許可し、directory syncはbest effortとする。
- Android SAF export、renderer DSP/event order、project schema/autosave、version/signing/release、device/provider/public/Humanはscope外。

## Architecture and interfaces

Desktop packageへsmall internal atomic sibling replacement helperを置き、target parentの作成／temporary ownership／file sync／atomic-move-first／existing-target fail-close／directory sync／finally cleanupを一つのdeep boundaryへまとめる。`DesktopProjectFiles.save`の既存実装も同じhelperへ寄せ、同等のfailure-preservation testを維持する。

Windows Beat exportは`DesktopBeatFiles.export`のような限定adapterを通し、temporary pathだけを`PatternRenderer.renderSequenceToWav`へ渡す。controllerはexisting operation generation、snapshot、success/failure statusを保持する。

## Milestones

### Milestone 1: Preservation RED

- existing destinationへsentinel bytesを置き、temporary writerがpartial bytesを書いてthrowするnegative controlを追加する。
- Acceptance: current sourceにはatomic sibling APIがなくcompile REDになる。

### Milestone 2: Atomic GREEN and integration

- failureはexisting bytes保持＋temporary 0、successはdestination置換＋return value保持を実装する。
- manual project saveを同じhelperへ移し、既存read-back/failure preservationを回帰確認する。
- real `PatternRenderer`を通るWindows export adapterがvalid WAVとsummaryを生成することを確認する。

### Milestone 3: Review and full local gate

- overwrite race、target parent、extension、temporary collision、move fallback、cleanup masking、fatal error境界をadversarial reviewする。
- focused/full tests、configured validation、clean full package/SBOM/artifact read-back、SSOT/active-state closeout。
- Acceptance: unresolved Standards/Spec findings `0/0`、exact product/closeout objectsとremaining Android/device gatesを記録する。

## Progress

- [x] 2026-08-27T02:15+09:00 — Wave 12 clean closeoutからcurrent sourceをread-backし、direct destination truncationとmanual-project atomic-saveとの差を選定。専用clean worktreeを作成。
- [x] 2026-08-27T02:18+09:00 — Milestone 1 RED。existing `beat.wav`のsentinelとpartial writerを置くtestがmissing `replaceWithAtomicSibling`でcompile failure。production adapter testもmissing `DesktopBeatFiles` / renderer seamでcompile failure。
- [x] 2026-08-27T02:28+09:00 — Milestone 2 GREEN。same-parent temporary、file sync、atomic replace、existing-target fail-close、finally cleanupをdeep helperへ実装。WAV adapter failure/success/actual RIFF、new-target failure、temporary消失、manual project failure-preservation controlsとdesktop full 104 tests / 22 suitesをPASS。
- [x] 2026-08-27T02:43+09:00 — Milestone 3 closeout。Standards/Spec findings `0/0`、clean 197-task gate、568 tests / 102 suites、configured/Python/public-surface/release-negative/package/SBOM read-backをPASS。product checkpoint `ffe1bbdf59d0645652c3f8556fb6f601e5218670` / tree `d3ae7a9bb18d48ae323e92aaabfe58e25acd975f`を固定し、Android SAF provider atomicity、filesystem variation、actual audition以下を別gateに保持。

## Decision log

- 2026-08-27T02:15+09:00 — safe-but-disruptive Undo/Redo、直前laneと同型のloop-start ordering、広いproduction breadthではなく、既存の完成outputを失うdata-preservation境界を優先。
- 2026-08-27T02:15+09:00 — renderer全体へatomic semanticsを押し込まず、user-selected filesystem destinationを所有するdesktop adapterに置く。renderer temp outputとAndroid SAF temporary renderはそのまま保つ。
- 2026-08-27T02:24+09:00 — existing targetでatomic moveを提供しないfilesystemは、旧fallback overwriteを続けず明示failureにする。既存完成fileを守ることを互換性より優先し、新規targetだけ競合なしmoveを許可する。

## Validation log

- baseline: clean `6fb1c94` / tree `ce7ce3d`、Wave 12 full 197-task / 560-test gate完了済み。product bytes変更前に同じ高コストgateは再実行しない。
- RED: `AtomicSiblingFileTest`はmissing helper、`DesktopBeatFilesTest`はmissing adapter/renderer interfaceでそれぞれcompile failure。
- GREEN focused: helper＋WAV adapter＋manual project＋controller tests PASS。desktop fullは104 tests / 22 suites、failures/errors/skips 0。
- Standards review: export controllerの`runCatching`がfatal `Error`もrecoverable failure statusへ変換する境界を発見し、`Exception`だけをstatusへ変換する明示try/catchへ限定。
- Standards review: move成功後のredundant cleanup checkが例外を出すと、published targetを成功済みなのにfailureと誤報し得た。`published` ownershipを明示し、cleanupは未publish pathだけに限定。
- Spec review: rendererがsuccess summaryを返してもcanonical PCM-16 header/sizeが不一致なら旧targetを置換しないstreaming shape read-backを追加。invalid-success negativeとactual RIFF/WAVE positiveがPASS。
- full clean gate: `BUILD SUCCESSFUL in 5m 18s`、197 tasks（193 executed / 4 up-to-date）。Android 264、shared Android/Desktop 66/66、JVM-core 68、Desktop 104、合計568 tests / 102 suites、failures/errors/skips 0。Lint debug/releaseは各errors 0 / warnings 7。
- configured/release gates: explicit Git Bash validation、Python policy 59、current＋reachable-history public surface 440、`git diff --check`、unsigned APK positiveとsigned-required exit-1 negative、CycloneDX 1.6 / 650 components / 651 dependenciesをPASS。
- package read-back: Windows app-image 405 files / 176,651,039 bytes / manifest SHA-256 `53AB068CDDE342ECDB74E5C0C3D5A395AC9EAC9A8A8D3F86D3131EAC99D176E1`。Desktop JAR SHA-256 `C4B2F22F74F0FC4C1AE4126B80FAA92728898E9897213A71274008266EC32DC9`。

## Outcome

Wave 13は`LOCAL_PASS`で完了した。Windowsの既存WAV上書きは、新しいcanonical WAVが閉じられ、summaryとのheader/size整合を通り、同一filesystem上でatomic publicationできたときだけ成功する。失敗時の旧output保全はsynthetic host contractであり、実filesystem/antivirus/network shareや聴感品質へ一般化しない。protected dirty/conflicted checkouts、Android/provider、GitHub/public stateは不変。

## Risks and rollback

最大riskはatomic move非対応、move failure時のcleanup、同名temporary、success後のdirectory sync failureを本体failureとして誤報すること。same-parent unique temporary、write完了＋file sync後move、existing-target fail-close、best-effort directory sync、finally cleanup、existing-project-save controlsで反証する。rollbackはこのisolated branchを採用しないことだけで、Wave 12 closeoutとprotected checkoutsは不変。

## Remaining external validation

Android SAF destinationのcopy途中failureはprovider-specificであり今回のWindows filesystem contractから昇格しない。Windows antivirus/network share/filesystem-specific rename、actual export audition、device/provider/public/signing/Human qualityも別gate。
