# Workflow NEXT / locked-stage reason UX

## Outcome

既存の4工程を増やさず、現在の制作状態から「次に一つ何をすればよいか」と、押せない工程の具体的な理由を常時伝える。modal tutorial、新しいscreen、scrollは追加しない。

## Boundary

- Root / owner: `C:/Users/rambo/Documents/ChatGPT/pad/work/choplab-next-action-ux-20260826` / current root task
- Baseline: `8b9c00ba2a382705c3478c1ce8984225d30a6c8d`
- Product checkpoint: `a9f2245abd1673ce02b9a94f231b66d5fe87a4ea`, tree `05d84e34c62f83057ef28175ea2d4d3d9d7de96a`
- Target gate: `LOCAL_PASS`
- No schema/audio engine/project-data change; no ADB/device, OAuth/provider, GitHub/publication, secret or Human action
- Whole-system/discovery decision: parent PAD `work/PAD_CHOPLAB_GOAL_PORTFOLIO_20260826.md`

## Governing constraint and selection

機能数ではなく、workflow comprehensionをcurrent constraintとした。Boolean-onlyな工程availabilityでは、disabled tabが「なぜ押せないか」「何が一つ足りないか」を伝えなかった。5方向を比較し、固定surfaceを壊さないNEXT status＋locked reasonを選択した。persistent overlay、Song mode、provider breadthはdefer/rejectした。

## TDD and challenge evidence

- RED: `WorkflowStageAvailability` / `WorkflowNextActionPresentation` が存在せずfocused test compile failure。
- GREEN: empty、pristine demo、source loaded、source+starter、source chop、PAD-only、loop、export-ready、loading、recording、stoppingを一つのpure policyで分類。
- Locked stageはCAPTURE/CHOP/BEAT/SAVEごとに具体的な日本語prerequisiteを持ち、stage semanticsの`stateDescription`へ公開。
- Existing status stripを`NEXT 1–4`または`NEXT 待つ／録音を止める`へ置換。通常／large-textとも同じ情報、screen-readerはstrip全体を一度だけ読む。
- Challenge: pristine demoを誤ってSAVEへ送らない、source+starterをCHOPへ送る、PAD-onlyをCAPTUREへ戻さない、LOOP/VOCAL相当のaudible contentをSAVEへ進める、busy stateでnavigation actionを競合させない、title/guidanceの固定copy budgetを超えない。

## Validation

- Focused `GuidedWorkflowTest` plus shared Android/Desktop host tests PASS。
- `scripts/validate_project.sh`: public-surface baseline 409、executable modes、JVM-core/Desktop 18 tasks、Android XML、wrapper checksum/UTF-8 PASS。documentation-inclusive final public-surface 410 PASS。
- Full local gate: 190 tasks PASS。Android unit 244、shared Android 34、shared Desktop 34、JVM-core 54、Desktop 80、failures/errors/skips 0。
- Lint debug/release: errors 0、warnings 7。debug APK 31,541,362 bytes / `500B675B04A3D4DED7C88FB5F286AB6CBF2E571F99BB8DEAC7EED951FCCD21B4`、androidTest 10,878,631 / `37F3AEDB16F4FD2BFCEC1D429D7E44A38A7ED157CAE30A6FB052CE0FB7093290`、unsigned release 24,093,812 / `3477E4CC631F2C1F8195FC388E4BD65216F6926638AA120E34F949BDFCA6A1CE`。
- Windows app-image ProductVersion `0.17.0` / EXE 449,024 bytes / `05BA300784A2B98197200A7B5AFCEDD70B62913DB71C1971B23A5E9785281630` verifier PASS。
- CycloneDX build、全Python policy 40、final public-surface 410、`git diff --check` PASS。

## Gate and next experiment

This is `LOCAL_PASS`. Physical screen fit, touch, TalkBack/VoiceOver speech, first-time task completion and audio/Human value are not claimed. The next UX experiment is the fallback “export completion confidence” only after an authorized screenshot/device/Human check tests whether NEXT copy actually reduces hesitation.

## Rollback

Product commit `a9f2245` is isolated and has no migration. Revert or decline it to restore the previous status copy and Boolean stage semantics.
