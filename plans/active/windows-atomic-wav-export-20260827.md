# Preserve an existing Windows WAV when export fails

## Purpose and user-visible outcome

Windowsで既存WAVへ4小節を書き出し直すとき、新しいWAVが最後まで生成・closeできた場合だけdestinationを置換する。render、flush、close、moveの途中で失敗した場合は、以前の完成WAVをbyte-for-byte保持し、同じfolderにChopLabの一時fileを残さず、既存の失敗statusを返す。

## Current state

- exact base: `6fb1c94f283019580d0cbf0fecfadeefa324675a` / tree `ce7ce3d2acb7124708e801291b50838b0814e57f`（Wave 12 closeout）。
- owner root: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-atomic-wav-export-20260827`、branch `codex/choplab-atomic-wav-export-20260827`。
- `DesktopSamplerController.exportBeat(outputFile)`はuser destinationを直接`PatternRenderer.renderSequenceToWav`へ渡す。
- `WavFileWriter`は`RandomAccessFile(file, "rw")`を開き、header前に`setLength(0)`する。後続のrender/I/O failureは以前のdestination bytesを戻さない。
- `DesktopProjectFiles.save`は同一folderのtemporaryへwrite/read-back後、atomic-move-firstで置換し、failure時にtemporaryを消す。WAV exportには同等のboundaryがない。

## Constraints and invariants

- target files: desktop persistence/export helper、`DesktopSamplerController`、desktop tests、SSOT/plan docs。
- temporaryはdestinationと同じparentに作り、writerが正常returnしてresource closeを完了した後だけreplaceする。
- render/write/close failureは既存destinationの存在・size・bytesを変更しない。temporaryはfinallyで消す。
- successはこれまでと同じ`PatternRenderSummary`とvalid PCM-16 WAV bytesを返し、destination name/status contractを変えない。
- atomic move unsupported時のsame-filesystem replace fallbackは既存project-save policyと同じにし、directory syncはbest effortのままにする。
- Android SAF export、renderer DSP/event order、project schema/autosave、version/signing/release、device/provider/public/Humanはscope外。

## Architecture and interfaces

Desktop packageへsmall internal atomic sibling replacement helperを置き、target parentの作成／temporary ownership／atomic-move-first／fallback／directory sync／finally cleanupを一つのdeep boundaryへまとめる。`DesktopProjectFiles.save`の既存実装も同じhelperへ寄せ、同等のfailure-preservation testを維持する。

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
- [ ] Milestone 1 preservation RED。
- [ ] Milestone 2 atomic GREEN and integration。
- [ ] Milestone 3 review/full gate/closeout。

## Decision log

- 2026-08-27T02:15+09:00 — safe-but-disruptive Undo/Redo、直前laneと同型のloop-start ordering、広いproduction breadthではなく、既存の完成outputを失うdata-preservation境界を優先。
- 2026-08-27T02:15+09:00 — renderer全体へatomic semanticsを押し込まず、user-selected filesystem destinationを所有するdesktop adapterに置く。renderer temp outputとAndroid SAF temporary renderはそのまま保つ。

## Validation log

- baseline: clean `6fb1c94` / tree `ce7ce3d`、Wave 12 full 197-task / 560-test gate完了済み。product bytes変更前に同じ高コストgateは再実行しない。

## Risks and rollback

最大riskはnon-atomic fallback、move failure時のcleanup、同名temporary、success後のdirectory sync failureを本体failureとして誤報すること。same-parent unique temporary、write完了後move、best-effort directory sync、finally cleanup、existing-project-save controlsで反証する。rollbackはこのisolated branchを採用しないことだけで、Wave 12 closeoutとprotected checkoutsは不変。

## Remaining external validation

Android SAF destinationのcopy途中failureはprovider-specificであり今回のWindows filesystem contractから昇格しない。Windows antivirus/network share/filesystem-specific rename、actual export audition、device/provider/public/signing/Human qualityも別gate。
