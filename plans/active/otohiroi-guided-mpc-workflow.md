# おとひろいを5工程のガイド付き制作機へ刷新する（歴史計画）

> Historical plan. The current Android source has since converged on the
> shared four-stage workflow `入れる / チョップ / ビート / 保存`; consult
> `docs/ui/android-parity-contract-v2.json` and `docs/architecture/multiplatform-parity.md`
> for current behavior. This retained plan is not an implementation contract.

## Purpose and user-visible outcome

音楽制作を知らない人でも、固定ナビゲーションの `入れる / 切る / 叩く / 並べる / 完成` を左から順に進めるだけで、音声の取込、切り出し、PAD演奏、16-step作成、WAV書き出しまで到達できるようにする。元HTMLの最短導線である「曲を再生し、ここだと思った瞬間にPADを押して音を拾う」は `叩く` の中心機能として維持する。選択中PADのキー、音色、音量は演奏と打込みの画面から直接変更できる。

## Current state

- 作業開始点は public `main` の `84263f7`、worktreeはclean。
- Androidアプリは `app/src/main/java/com/choplab/sampler/ui/OtohiroiDeck.kt` の固定 `CHOP / PAD / SEQ / SOURCE` 4モードで、縦横スクロールを使わない。
- `CHOP` は元HTML由来の load/mic、source waveform、source playback、live chop、4 BANK × 16 PADを実装済み。
- `PAD` は pitch/tone/gain、reverse、one-shot/gate、choke、clear、`SEQ` は16-step/BPM/Swing/REC/export、`SOURCE` は範囲・slice編集を実装済み。
- 視覚目標は生成済みの `叩く` 画面 `C:\Users\rambo\.codex\generated_images\019fe6e3-807a-7d31-a87b-7fbe7fc4c388\exec-b0a3fa89-1939-4ee8-aaf6-029b9b065980.png` と、`並べる` 画面 `C:\Users\rambo\.codex\generated_images\019fe6e3-807a-7d31-a87b-7fbe7fc4c388\exec-079585ce-60c8-49c4-b72e-aedbd8ffe4d2.png`。
- 元HTML仕様は `C:\Users\rambo\.codex\attachments\3f2fc606-7aeb-4fda-8333-83b63b96c961\pasted-text.txt`。画面文言だけでなく、再生中PADで現在位置を拾い、停止中PADで割当音を鳴らす挙動をcanonicalとして扱う。
- 今回はphone未接続。過去のPixel 9a検証を今回変更のDEVICE_PASSへ流用しない。

## Constraints and invariants

- `app/` のAudioTrack MVPと既存ViewModel intentを維持し、DSPや録音方式を同時に変更しない。
- `minSdk=29`、versioned public preview、既存4 BANK × 16 PAD、16-step、live chopを維持する。
- top-levelで `verticalScroll`、`horizontalScroll`、`rememberScrollState` を使わない。
- 360 × 640dp級portraitと800 × 320dp級landscapeで固定領域へ収め、主要タップ対象は原則40dp以上にする。
- 初心者向けの日本語を主表示、専門語は短い英語副表示にする。高度設定は隠蔽せず `叩く` 内の詳細導線から到達可能にする。
- AKAI/MPCのロゴ、固有asset、firmware、project形式、特徴的trade dressは複製しない。cream/charcoal/orange/greenの既存おとひろい視覚言語を使う。
- LOCAL_PASS、EMULATOR_PASS、DEVICE_PASS、PUBLIC_PASS、HUMAN_GOを分離する。

## Architecture and interfaces

- pure `GuidedWorkflow.kt` に5工程、復元時fallback、状態に応じた開始工程、各工程の初心者向け短文を置く。
- `OtohiroiDeck` は `WorkflowStage` を `rememberSaveable` し、5工程railとstage bodyを表示する。
- `入れる`: source capture/import、source status、波形、再生とsource keyを初心者向けに集約する。
- `切る`: 既存 `SourceWorkspace` の精密range/slice編集を再利用する。
- `叩く`: 既存 `ChopWorkspace` をcanonical HTMLフローとして維持し、選択PADのKEY/TONE/LEVEL quick editorと詳細PAD editorを統合する。
- `並べる`: 既存 `SequenceWorkspace` を再利用し、選択PADのKEY/TONE/LEVEL quick editorを配置する。WAV exportは `完成` へ集約する。
- `完成`: assigned PAD数、active step数、BPM、再生状態を要約し、4 bars WAV exportと戻り導線を提供する。
- ViewModelのpublic intentとaudio thread契約は変更しない。

## Milestones

### Milestone 1: Workflow model and navigation
- `GuidedWorkflow.kt` とunit testsをRed/Greenで追加する。
- 4-mode stripを5-stage railへ置き換え、保存状態の不正値は安全にfallbackする。
- acceptance: 5工程の順序、label、初期推奨工程、次工程がpure testsで確認できる。

### Milestone 2: Beginner-first workspaces
- `入れる` と `完成` を追加し、既存workspacesを5工程へ対応付ける。
- UI文言を日本語主表示へ更新し、status stripに工程ごとの一行ガイドを出す。
- acceptance: 全既存ViewModel intentが到達可能で、source未読込でもクラッシュしない。

### Milestone 3: PAD quick edit and no-scroll hardening
- `叩く` と `並べる` にKEY/TONE/LEVEL quick editorを追加する。
- `叩く` からreverse/gate/choke/clearを含む詳細PAD editorへ遷移できる。
- compact portrait/landscapeの固定高さを見直し、scroll API不使用をsource scanする。
- acceptance: 360 × 640、412 × 820、800 × 320のpolicy testsがPASSし、主要操作高40dp以上を保つ。

### Milestone 4: Validation, visual QA, and public preview
- offline validation、unit、lint、assembleを実行し、debug APKとSHA-256を記録する。
- emulatorが安全に準備できる場合は同一viewport/stateでreferenceとscreenshotを比較する。emulatorがない場合はvisual/device evidenceを未検証と明示する。
- code reviewを実施し、指摘を修正してcommitする。
- local gate後にmainへpushし、CI確認、`v0.3.0-preview.1` tag/release、公開APKのhash照合を行う。

## Progress

- [x] 2026-08-10 — 元HTML、選定画像、現行4モード、既存state/intentを対応付けた。
- [x] 2026-08-10 — pure workflow modelと5工程railを実装した。
- [x] 2026-08-10 — `入れる`、`叩く`、`並べる`、`完成` を初心者向けに統合した。
- [x] 2026-08-10 — unit/lint/APK、Pixel 9/API 36 emulator visual QA、二軸code reviewを完了した。
- [x] 2026-08-10 — public previewを公開し、artifact identityを確認した。PR #2、main/tag CI、Release workflowがPASSし、公開APKと`.sha256`のdigest一致を確認した。

## Discoveries

- 既存 `CHOP` が元HTMLの核心機能をすでに保持しているため、捨てずに `叩く` へ昇格する方がaudio regression riskが低い。
- compact portraitはPAD gridを48dp近辺に保つため、初心者ガイドを独立cardとして積まずstatus/readoutへ統合する必要がある。
- `PAD` を独立工程にすると初心者の5工程を崩すため、`叩く` の詳細subpageとして残す。

## Decision log

- 2026-08-10 — 5工程は線形に見せるが、`切る` は必須gateにしない。曲を流して直接拾う元HTMLの速さを守るため。
- 2026-08-10 — `KEY` は選択PADのsemitone pitchとして実装する。source master pitchとは別物として表示する。
- 2026-08-10 — `完成` は既存の4-bar mono WAV exportを正確に案内し、Song/stem/stereo完成機能があるようには見せない。

## Validation log

- `scripts/validate_project.sh` — 2026-08-10 — PASS。
- `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` — 2026-08-10 — PASS。23 tests、0 failures/errors/skips、lint 0 errors / 9 warnings。
- source scan — 2026-08-10 — scroll API match 0。
- Pixel 9 AVD / Android 16 API 36 — capture、live chop PAD 01/02、KEY C3→C#3、TONE 32%、LEVEL 75%、steps 1/5/9/13、finish readinessを確認。
- `design-qa.md` — 生成した`叩く`/`並べる` targetと同一状態で比較し、P2の直接調整と初心者TIPを修正後 `final result: passed`。
- local APK — versionCode 4 / versionName 0.3.0、30,616,083 bytes、SHA-256 `718814700DF1929D53CC90B2B0A10A7230E677C598E226080114ACC8D87348D2`。
- code review — standards hard violation 0。`CaptureWorkspace`のorientation重複と巨大UI fileは判断事項として将来分割候補に残す。spec指摘のviewport testsは既存`DeckLayoutPolicyTest`で360×640 / 412×820 / 800×320を確認済み。finish再生状態と詳細PAD editorの日本語主表示はreview後に追加した。
- public preview — PR `#2`、merge `a882ec633d6b9ad849a8c900171fbbd1006f29d1`、main CI `31357298769`、tag CI `31357435542`、release workflow `31357435588` がPASS。`v0.3.0-preview.1` 公開APKは30,116,752 bytes、SHA-256 `E5C79BF01F62C5445E23798CF0603B46305E37BC932F3B9AE94C580E3E4E7219`で公開`.sha256`と一致。

## Risks and rollback

- 5項目railの幅不足は短い日本語labelと小さい英語副表示で抑える。読めない場合はcompactで英語を省略する。
- quick editor追加でPADが小さくなる場合は、既存readout/action rowと置換し、gridのweightを奪わない。
- 保存済み旧mode名はpure parserでfallbackし、`valueOf` crashを避ける。
- commitは既存 `v0.2.0-preview.1` を変更せず、通常の `git revert` で戻せる。

## Remaining device validation

- 360–430dp portrait、compact landscape、font scale、gesture navigation insetでのclipping。
- PAD連打中のlive chop、KEY/TONE/LEVEL変更、step入力、exportのaudio E2E。
- TalkBack reading order、haptic、source/play transport exclusion、lifecycle resume。
